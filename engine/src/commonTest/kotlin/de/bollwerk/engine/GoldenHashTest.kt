package de.bollwerk.engine

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.loop.ScriptedCommandSource
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.MatchFactory
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StartFortSpec
import de.bollwerk.engine.sim.SystemSlot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plattform-Golden-Test: Ein festes Szenario (600 Ticks mit Verlet-Fall, Ballistik, Explosions-Impulsen,
 * Brüchen mit Slot-Wiederverwendung und RNG aus mehreren Strömen) muss auf **jeder** Plattform (JVM, ART,
 * später Kotlin/Native iOS) exakt diesen Hash ergeben. Schlägt er nur auf einer neuen Plattform fehl, ist
 * deren Float-Verhalten (FMA-Kontraktion, sqrt-Lowering) nicht IEEE-strikt.
 *
 * Ändert sich der Hash absichtlich (Hash-Format, Pool-Felder), Konstante anpassen und im Bericht nennen.
 */
class GoldenHashTest {
    private val map = MapSpec.flat().let {
        MapSpec(
            it.id, it.width, it.height, it.terrain, it.buildZones, it.ores, it.foundations,
            listOf(StartFortSpec(0, "mini", 20f, false), StartFortSpec(1, "mini", 100f, true)),
            it.baseY, -4f, 4f, it.killMinX, it.killMaxX, it.killMinY, it.killMaxY,
        )
    }

    /** Testsystem: grobe Physik + Kampf, nur erlaubte Operationen (+ − × ÷ sqrt, FastTrig, SplitMix64). */
    private val chaos = SimSystem { s, ctx ->
        val n = s.nodes
        val g = s.config.gravity * s.config.dt * s.config.dt
        val count = n.size
        for (i in 0 until count) {
            if (!n.isAlive(i) || n.isAnchored(i) || n.isNew(i)) continue
            val vx = (n.x[i] - n.px[i]) * 0.99f
            val vy = (n.y[i] - n.py[i]) * 0.99f
            n.px[i] = n.x[i]; n.py[i] = n.y[i]
            n.x[i] += vx; n.y[i] += vy + g
            val ground = s.terrain.heightAt(n.x[i])
            if (n.y[i] > ground) { n.y[i] = ground; n.px[i] = n.x[i] - vx * 0.4f }
        }
        // "Explosion" alle 50 Ticks: radialer Impuls mit Wurzel-Falloff
        if (s.tick % 50L == 25L) {
            val ex = s.rngWeapon.nextFloat(10f, 110f)
            for (i in 0 until count) {
                if (!n.isAlive(i) || n.isAnchored(i)) continue
                val dx = n.x[i] - ex; val dy = n.y[i] - 34f
                val d = sqrt(dx * dx + dy * dy) + 0.5f
                n.px[i] -= dx / (d * d) * 0.05f
                n.py[i] -= dy / (d * d) * 0.05f
            }
        }
        // Bruch alle 37 Ticks: Balken freigeben, Hälfte neu anlegen (Slot-Wiederverwendung im Folgetick)
        if (s.tick % 37L == 0L && s.beams.aliveCount > 0) {
            val k = s.rngDebris.nextInt(s.beams.size)
            if (s.beams.isAlive(k)) {
                val a = s.beams.a[k]
                s.beams.release(k)
                val m = n.alloc(n.x[a] + 0.5f, n.y[a] - 0.5f, s.beams.ownerOf[k])
                s.beams.alloc(a, m, 0, 0.7f, 60f, s.beams.ownerOf[k], s.beams.texOffset[k])
            }
        }
        // Feuer und Wind
        for (j in 0 until s.beams.size) {
            if (!s.beams.isAlive(j)) continue
            if (s.rngFire.chance(0.002f)) s.beams.fireOf[j] = 0.05f
            if (s.beams.fireOf[j] > 0f) s.beams.hpOf[j] -= s.beams.fireOf[j]
        }
        if (s.tick % 120L == 0L) s.wind += s.rngWind.nextFloat(-1f, 1f)
        // Projektil alle 30 Ticks
        if (s.tick % 30L == 0L) {
            val a = 0.9f + FastTrig.sin(s.tick.toFloat() * 0.01f) * 0.2f
            val st = FloatArray(4)
            Ballistics.launch(20f, 30f, a, 0.8f, 33f, st)
            s.projectiles.alloc(st[0], st[1], st[2], st[3], 0, 0, 300, sourceDevice = 1)
        }
        val p = s.projectiles
        val st = FloatArray(4)
        for (i in 0 until p.size) {
            if (!p.isAlive(i) || p.isNew(i)) continue
            st[0] = p.x[i]; st[1] = p.y[i]; st[2] = p.vx[i]; st[3] = p.vy[i]
            Ballistics.tick(st, s.wind, s.config)
            p.px[i] = p.x[i]; p.py[i] = p.y[i]
            p.x[i] = st[0]; p.y[i] = st[1]; p.vx[i] = st[2]; p.vy[i] = st[3]
            p.ageTicks[i]++
            if (p.y[i] > s.terrain.heightAt(p.x[i]) || --p.ttl[i] <= 0) p.release(i)
        }
        s.players[0].metal += 0.1f
        if (ctx.commands.isNotEmpty()) s.players[1].energy += 1f
    }

    private fun run(): GameState {
        val setup = MatchSetup(424242L, "flat", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.AI)))
        val state = MatchFactory.create(setup, TestWorld.tables, map)
        val cmds = ScriptedCommandSource(listOf(Command.EndTurn(100, 1), Command.Undo(300, 0)))
        val session = GameSession(state, SimStepper(mapOf(SystemSlot.PHYSICS to chaos)), sources = listOf(cmds))
        session.run(600)
        return state
    }

    @Test
    fun goldenHashIsPlatformIndependent() {
        val h = StateHash.of(run())
        assertEquals(StateHash.of(run()), h)
        assertEquals(GOLDEN, StateHash.hex(h))
    }

    private companion object {
        const val GOLDEN = "0054dfec22fde559"
    }
}
