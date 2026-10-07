package de.bollwerk.engine.combat

import de.bollwerk.engine.math.ClosestParams
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.FxEvent

/**
 * Gemeinsame DERIVED-/Scratch-Daten der Kampf-Systeme eines Steppers (wie `PhysicsWorld` für die Physik): Broadphase,
 * Strahltest, Rechenpuffer und die Reaktor-Beobachtung innerhalb eines Ticks. Nichts hiervon ist Spielzustand.
 *
 * Das Balken-Raster wird zu Beginn von WEAPONS und PROJECTILES verworfen ([invalidateGrid]) und beim ersten Strahltest
 * neu aufgebaut; zusätzlich baut [BeamGrid.ensure] es neu, sobald es für einen anderen Zustand gebaut wurde oder seit dem
 * Aufbau Balken entstanden sind (Bruchhälften, `BeamPool.nextUid`). Ein Strahltest sieht damit immer alle lebenden
 * Balken – auch beim abwechselnden Rechnen mehrerer Zustände oder beim erneuten Simulieren eines Ticks nach einem
 * Restore. Puffer werden geteilt: ein `CombatSystems.all()` rechnet zu einem Zeitpunkt nur einen Zustand.
 */
class CombatWorld {
    internal val grid = BeamGrid()
    internal val caster = RayCaster(grid)
    internal val hit = RayHit()
    internal val geo = FloatArray(DeviceGeometry.SIZE)
    internal val ballistic = FloatArray(4)
    internal val cp = ClosestParams()
    internal var doors = IntArray(16)
    internal val reactors = ReactorWatch()

    /** Verwirft das Raster; der nächste Strahltest baut es aus dem aktuellen Zustand neu auf. */
    internal fun invalidateGrid() = grid.invalidate()
}

/**
 * Erkennt innerhalb eines Ticks, dass der Reaktor eines Spielers zerstört wurde – egal in welchem Slot (direkt durch
 * Kampfschaden, durch einen Balkenbruch, durch Einsturz in TOPOLOGY oder Trümmer-Schaden/-Zerfall in DEBRIS) – und löst
 * genau einmal die große Reaktor-Explosion aus (Prototyp `killDevice` → `reactorDown`: `FxEvent.ReactorDestroyed` +
 * Explosion 6,5 m / 280 / Impuls 2600, entzündend). Die Explosion selbst meldet kein eigenes `FxEvent.Explosion`:
 * Renderer und Audio zeigen sie über `ReactorDestroyed` (sonst doppelte Explosion, doppelter Shake).
 *
 * [capture] merkt sich zu Beginn von WEAPONS, welche Reaktoren leben; [check] (Ende von WEAPONS, PROJECTILES, FIRE und
 * im Slot REACTORS nach TOPOLOGY/DEBRIS) vergleicht. Es gibt **keine** Erinnerung über Tickgrenzen hinweg (ein
 * veralteter Stempel wird nur neu erfasst), damit ein Restore/Rollback zwischen zwei Ticks bitgleich weiterrechnet.
 * Das Spielergebnis setzt das RESULT-System (WP3) unabhängig davon über `PlayerState.reactorDeviceId`; es meldet
 * `ReactorDestroyed` nur, wenn es in diesem Tick noch nicht gemeldet wurde.
 */
internal class ReactorWatch {
    private var tick = Long.MIN_VALUE
    private var alive = BooleanArray(4)

    fun capture(state: GameState) {
        val pc = state.players.size
        if (alive.size < pc) alive = BooleanArray(pc)
        for (p in 0 until pc) alive[p] = reactorAlive(state, p)
        tick = state.tick
    }

    fun check(state: GameState, ctx: StepContext, world: CombatWorld) {
        if (tick != state.tick) { capture(state); return }
        val pc = state.players.size
        var changed = true
        while (changed) {
            changed = false
            for (p in 0 until pc) {
                if (!alive[p] || reactorAlive(state, p)) continue
                alive[p] = false
                changed = true
                blast(state, ctx, world, p)
            }
        }
    }

    private fun reactorAlive(state: GameState, p: Int): Boolean {
        val id = state.players[p].reactorDeviceId
        val d = state.devices
        if (id < 0 || !d.isAlive(id) || d.ownerOf[id] != p) return false
        return state.tables.devices[d.typeOf[id]].role == DeviceRole.REACTOR
    }

    private fun blast(state: GameState, ctx: StepContext, world: CombatWorld, p: Int) {
        val d = state.devices
        val id = state.players[p].reactorDeviceId
        // Slot-Daten bleiben nach der Freigabe bis zur Wiederverwendung (frühestens nächster Tick) erhalten.
        val off = state.tables.devices[d.typeOf[id]].mountOffset
        val x = d.x[id] + d.nx[id] * off
        val y = d.y[id] + d.ny[id] * off
        val c = state.config.combat
        ctx.fx.add(FxEvent.ReactorDestroyed(state.tick, x, y, p))
        CombatOps.explode(
            state, ctx, world, x, y, c.reactorBlastRadius, c.reactorBlastDamage, c.reactorBlastImpulse,
            c.reactorBlastIgniteRadius, weaponId = -1, hitMaterialId = -2, hitBeamUid = -1, hitBeamT = -1f, deviceFactor = 1f,
            emitFx = false,
        )
    }
}

/**
 * [de.bollwerk.engine.sim.SystemSlot.REACTORS] (WP4): letzte Reaktor-Prüfung des Ticks nach TOPOLOGY und DEBRIS. Fängt
 * Reaktoren, deren Struktur eingestürzt ist (Geräte auf Trümmerbalken sterben in TOPOLOGY) oder die durch
 * Trümmer-Einschläge/-Zerfall starben, und löst ihre Explosion noch im selben Tick aus (vor RESULT, das dann kein
 * zweites `ReactorDestroyed` meldet). Die Slot-Daten des Reaktors sind bis zum Tickende erhalten (verzögerte Freigabe).
 */
class ReactorSystem(private val world: CombatWorld) : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        world.reactors.check(state, ctx, world)
    }
}
