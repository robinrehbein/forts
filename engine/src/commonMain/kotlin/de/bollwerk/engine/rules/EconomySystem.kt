package de.bollwerk.engine.rules

import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.GameView

/**
 * Wirtschaft (Stil-Bibel §1, Prototyp `econTick`): Mine +6 ⚙/s, Windturbine +6 ⚡/s × Höhenfaktor (1,0 bis 1,5),
 * Reaktor +2 ⚡/s – die Werte stehen in `DeviceProps.metalPerSecond/energyPerSecond`. Nur fertig gebaute, lebende Geräte
 * auf Balken, die nicht Trümmer sind, liefern.
 */
object Economy {
    /** Turbinen-Höhenfaktor: `1 + clamp((baseY − y)/24, 0, 0,5)`, [y] = Montagepunkt (y wächst nach unten). */
    fun turbineFactor(baseY: Float, y: Float): Float =
        1f + FloatMath.clamp((baseY - y) / RuleConst.TURBINE_HEIGHT_RANGE, 0f, RuleConst.TURBINE_BONUS_MAX)

    /** Trägt Gerät [id] zur Wirtschaft bei (lebt, fertig gebaut, nicht deaktiviert, auf lebendem Nicht-Trümmer-Balken)? */
    fun produces(view: GameView, id: Int): Boolean {
        val d = view.deviceView
        if (!d.isAlive(id) || d.buildTicks(id) > 0) return false
        if ((d.flags(id) and (DeviceFlags.BUILDING or DeviceFlags.DISABLED)) != 0) return false
        return onLiveBeam(view, id)
    }

    /** Sitzt Gerät [id] auf einem lebenden Balken, der kein Trümmer ist? (Wirtschaft, Tech und Schießen setzen das voraus.) */
    fun onLiveBeam(view: GameView, id: Int): Boolean {
        val beam = view.deviceView.beam(id)
        val beams = view.beamView
        if (beam < 0 || beam >= beams.size || !beams.isAlive(beam)) return false
        return (beams.flags(beam) and BeamFlags.DEBRIS) == 0
    }

    /**
     * Raten (je s) aller Spieler: schreibt `out[2·p]` = Metall, `out[2·p + 1]` = Energie. [out] hat Länge ≥ 2 · Spielerzahl.
     * Eine Wahrheit für das Wirtschafts-System und HUD/KI. [geo] ist ein Arbeitspuffer (Länge `DeviceGeometry.SIZE`), den Aufrufer im Tick-Pfad wiederverwenden.
     */
    fun rates(view: GameView, out: FloatArray, geo: FloatArray = FloatArray(DeviceGeometry.SIZE)) {
        for (i in 0 until 2 * view.playerCount) out[i] = 0f
        val d = view.deviceView
        for (i in 0 until d.size) {
            if (!produces(view, i)) continue
            val props = view.tables.devices[d.type(i)]
            val owner = d.owner(i)
            if (owner < 0 || owner >= view.playerCount) continue
            out[2 * owner] += props.metalPerSecond
            var e = props.energyPerSecond
            if (e != 0f && props.role == DeviceRole.TURBINE) {
                DeviceGeometry.mount(view, i, geo)
                e *= turbineFactor(view.map.baseY[owner], geo[DeviceGeometry.Y])
            }
            out[2 * owner + 1] += e
        }
    }
}

/**
 * [de.bollwerk.engine.sim.SystemSlot.ECONOMY]: berechnet die Raten neu (`PlayerState.metalRate/energyRate`, DERIVED, für
 * das HUD) und schreibt sie gutschrift-weise gut. Einnahmen füllen nur bis zum Lager (`metalCap`/`energyCap`, Stil-Bibel
 * 1000/400) auf; ein Guthaben über der Grenze (z. B. durch Rückerstattung) wird nie abgeschnitten.
 */
class EconomySystem : SimSystem {
    private var rates = FloatArray(0)
    private val geo = FloatArray(DeviceGeometry.SIZE)

    override fun step(state: GameState, ctx: StepContext) {
        val n = state.players.size
        if (rates.size < 2 * n) rates = FloatArray(2 * n)
        Economy.rates(state, rates, geo)
        val dt = state.config.dt
        for (p in state.players) {
            p.metalRate = rates[2 * p.id]
            p.energyRate = rates[2 * p.id + 1]
            if (state.result != GameResult.Ongoing) continue
            if (p.metal < p.metalCap) p.metal = FloatMath.min(p.metalCap, p.metal + p.metalRate * dt)
            if (p.energy < p.energyCap) p.energy = FloatMath.min(p.energyCap, p.energy + p.energyRate * dt)
        }
    }
}
