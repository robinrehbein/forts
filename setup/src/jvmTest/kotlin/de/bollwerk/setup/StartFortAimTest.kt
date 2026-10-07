package de.bollwerk.setup

import de.bollwerk.content.ClasspathContent
import de.bollwerk.content.ContentDb
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.MatchRunner
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.tools.ShotSweep
import de.bollwerk.engine.tools.TrajectoryOutcome
import de.bollwerk.engine.view.FxEvent
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FX1 (Eigentreffer) auf den echten Startfestungen aus dem Content: Die Standard-Ausrichtungen schießen frei, die im Review
 * gefundenen Winkel (Mörser ≥ ~63–70°, Kanone ≥ ~38°) treffen das eigene Dach bzw. den eigenen ersten Stock – die Vorschau
 * ([ShotSweep]) meldet das und die Simulation bestätigt es (eigene Balken verlieren TP).
 */
class StartFortAimTest {
    private val deg = FloatMath.DEG_TO_RAD

    private fun runner(map: String): MatchRunner {
        val setup = MatchSetup(3L, map, listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN)))
        val r = MatchRunners.create(db, setup, record = false)
        r.runTicks(30)
        return r
    }

    private fun weapon(r: MatchRunner, owner: Int, key: String): Int {
        val d = r.state.devices
        for (i in 0 until d.size) if (d.isAlive(i) && d.owner(i) == owner && r.state.tables.devices[d.type(i)].key == key) return i
        error("no $key for $owner")
    }

    /** Sim-Winkel aus der Elevation zur Feindseite. */
    private fun angleOf(r: MatchRunner, owner: Int, elevationDeg: Float): Float =
        if (r.state.players[owner].facing >= 0) elevationDeg * deg else FloatMath.PI - elevationDeg * deg

    private fun ownBeamHp(r: MatchRunner, owner: Int): Float {
        val b = r.state.beams
        var s = 0f
        for (j in 0 until b.size) if (b.isAlive(j) && b.owner(j) == owner) s += b.hp(j)
        return s
    }

    @Test
    fun defaultAimsOfAllStartFortsAreClearInAnyWind() {
        // jeder Wind im Bereich der Karte (windMin..windMax, z. B. Hügel ±8), nicht nur ±6
        for (map in MAPS) {
            val r = runner(map)
            val s = r.state
            val sweep = ShotSweep()
            var checked = 0
            for (i in 0 until s.devices.size) {
                if (!s.devices.isAlive(i)) continue
                val p = s.tables.devices[s.devices.type(i)]
                if (p.weapon < 0) continue
                val w = s.tables.weapons[p.weapon]
                val owner = s.devices.owner(i)
                // Standard-Ausrichtung der Waffe (wie beim Bau) und die üblichen Bereiche: Mörser 45–58°, Kanone 12–30°
                val elevations = when (p.key) {
                    "mortar" -> listOf(w.defaultAimRad / deg) + (45..58).map { it.toFloat() }
                    "cannon" -> listOf(w.defaultAimRad / deg) + (12..30).map { it.toFloat() }
                    else -> listOf(w.defaultAimRad / deg)
                }
                for (wind in winds(s.map.windMin, s.map.windMax)) for (e in elevations) {
                    s.wind = wind
                    val o = sweep.device(s, i, angleOf(r, owner, e), w.defaultPower)
                    assertTrue(o != TrajectoryOutcome.BLOCKED_OWN, "$map p$owner ${p.key} at $e° wind $wind hits its own fort")
                    checked++
                }
            }
            assertTrue(checked > 100, "$map: $checked")
            assertTrue(s.map.windMax > 0f && s.map.windMin < 0f, "$map: wind range ${s.map.windMin}..${s.map.windMax}")
        }
    }

    @Test
    fun reviewProbeAnglesAreFlaggedAndTheSimulationConfirmsTheSelfHit() {
        for ((key, elevation) in listOf("mortar" to 70f, "mortar" to 78f, "cannon" to 38f, "cannon" to 50f)) {
            for (owner in 0..1) {
                val r = runner("schlucht")
                r.state.wind = 0f
                val dev = weapon(r, owner, key)
                val power = if (key == "mortar") 0.78f else 1f
                val angle = angleOf(r, owner, elevation)
                val sweep = ShotSweep()
                val pre = sweep.device(r.state, dev, angle, power)
                assertEquals(TrajectoryOutcome.BLOCKED_OWN, pre, "p$owner $key $elevation°")
                assertEquals(owner, sweep.hitOwner)
                val sim = fire(r, owner, dev, angle, power)
                assertTrue(sim.ownLoss > 50f, "p$owner $key $elevation°: own HP lost ${sim.ownLoss}")
                val ex = sim.explosion ?: error("no explosion")
                val dx = ex.x - sweep.hitX; val dy = ex.y - sweep.hitY
                assertTrue(sqrt(dx * dx + dy * dy) < 0.3f, "preview hit (${sweep.hitX}, ${sweep.hitY}) vs sim (${ex.x}, ${ex.y})")
            }
        }
    }

    @Test
    fun defaultShotsDoNotDamageTheOwnFort() {
        for ((key, elevation) in listOf("mortar" to 52f, "cannon" to 13f, "cannon" to 28f)) {
            for (owner in 0..1) {
                val r = runner("schlucht")
                r.state.wind = 0f
                val dev = weapon(r, owner, key)
                val power = if (key == "mortar") 0.78f else 1f
                val angle = angleOf(r, owner, elevation)
                assertTrue(ShotSweep().device(r.state, dev, angle, power) != TrajectoryOutcome.BLOCKED_OWN)
                val sim = fire(r, owner, dev, angle, power)
                assertTrue(sim.ownLoss < 1f, "p$owner $key $elevation°: own HP lost ${sim.ownLoss}")
            }
        }
    }

    /** Ganzzahlige Winde über den ganzen Bereich der Karte, Grenzen eingeschlossen (Hügel: −8..8). */
    private fun winds(min: Float, max: Float): List<Float> {
        val out = ArrayList<Float>()
        out.add(min)
        var w = min.toInt().toFloat()
        if (w < min) w += 1f
        while (w < max) { if (w > min) out.add(w); w += 1f }
        out.add(max)
        return out
    }

    private class Shot(val ownLoss: Float, val explosion: FxEvent.Explosion?)

    private fun fire(r: MatchRunner, owner: Int, dev: Int, angle: Float, power: Float): Shot {
        val before = ownBeamHp(r, owner)
        val ref = r.state.devices.ref(dev)
        r.submit(Command.SetAim(r.state.tick, owner, ref, angle, power))
        r.submit(Command.Fire(r.state.tick, owner, ref))
        var explosion: FxEvent.Explosion? = null
        for (k in 0 until 400) {
            r.runTicks(1)
            for (e in r.session.ctx.fx) if (e is FxEvent.Explosion && explosion == null && e.weaponId >= 0) explosion = e
            if (explosion != null && k > 30) break
        }
        return Shot(before - ownBeamHp(r, owner), explosion)
    }

    companion object {
        val db: ContentDb by lazy { ClasspathContent.load() }
        val MAPS = listOf("schlucht", "huegel")
    }
}
