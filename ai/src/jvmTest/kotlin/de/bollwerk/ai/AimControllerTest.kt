package de.bollwerk.ai

import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.WeaponMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Ballistisches Zielen: `Ballistics.solveAngle` mit `gravityScale` der Waffe und aktuellem Wind. */
class AimControllerTest {
    /** Höhenfehler der Bahn an der Stelle x = [tx] (erste Querung), oder +∞, wenn sie [tx] nie erreicht. */
    private fun missAtX(path: FloatArray, n: Int, x0: Float, y0: Float, tx: Float, ty: Float): Float {
        var px = x0; var py = y0
        for (k in 0 until n) {
            val x = path[2 * k]; val y = path[2 * k + 1]
            if ((px - tx) * (x - tx) <= 0f && x != px) {
                val f = (tx - px) / (x - px)
                return abs(py + (y - py) * f - ty)
            }
            px = x; py = y
        }
        return Float.POSITIVE_INFINITY
    }

    @Test
    fun ballisticSolutionHitsThePointWithWindAndGravityScale() {
        val m = AiMatch(seed = 7L, difficulties = listOf(null, null))
        m.run(60L)
        val st = m.state
        val d = st.devices
        val weaponDevice = (0 until d.size).first {
            d.isAlive(it) && d.owner(it) == 0 && st.tables.devices[d.type(it)].role == DeviceRole.WEAPON &&
                st.tables.weapons[st.tables.devices[d.type(it)].weapon].key == "mortar"
        }
        val props = st.tables.devices[d.type(weaponDevice)]
        val base = st.tables.weapons[props.weapon]
        assertTrue(base.mode == WeaponMode.BALLISTIC)
        val obstacles = Obstacles()
        val aim = AimController(obstacles)
        val tuning = AiTuning.of(Difficulty.HARD)
        val tx = 85f; val ty = 28f; val tr = 0.5f
        val path = FloatArray(AimController.MAX_POINTS * 2)
        val angles = ArrayList<Float>()
        for (wind in floatArrayOf(-5f, 0f, 5f)) for (gs in floatArrayOf(1f, 0.6f)) {
            st.wind = wind
            obstacles.rebuild(st)
            val w = base.copy(gravityScale = gs)
            val sol = AimSolution()
            assertTrue(aim.evaluatePoint(st, 0, weaponDevice, props, w, tx, ty, tr, -1, tuning, sol), "no solution wind=$wind gs=$gs")
            val mx = aim.pivotX + FastTrig.cos(sol.angle) * props.barrelLength
            val my = aim.pivotY - FastTrig.sin(sol.angle) * props.barrelLength
            val n = Ballistics.predict(mx, my, sol.angle, sol.power, w, wind, st.simConfig, path, AimController.MAX_POINTS, st.terrain, st.map)
            val miss = missAtX(path, n, mx, my, tx, ty)
            assertTrue(miss < 0.35f, "wind=$wind gs=$gs misses by $miss m")
            if (wind != 0f) {
                // Gegenprobe: dieselbe Lösung ohne Wind bzw. mit Standard-Schwerkraft verfehlt deutlich
                val nNoWind = Ballistics.predict(mx, my, sol.angle, sol.power, w, 0f, st.simConfig, path, AimController.MAX_POINTS, st.terrain, st.map)
                val m0 = missAtX(path, nNoWind, mx, my, tx, ty)
                assertTrue(m0 > 0.5f, "wind must matter (wind=$wind gs=$gs): $m0 m")
            }
            if (gs != 1f) {
                val nG = Ballistics.predict(mx, my, sol.angle, sol.power, base, wind, st.simConfig, path, AimController.MAX_POINTS, st.terrain, st.map)
                val m1 = missAtX(path, nG, mx, my, tx, ty)
                assertTrue(m1 > 1f, "gravityScale must matter (wind=$wind): $m1 m")
            }
            angles.add(sol.angle)
        }
        assertTrue(angles.distinct().size == angles.size, "every wind/gravity combination needs its own angle: $angles")
        // dieselbe Waffe/Ziel/Kraft: ab der zweiten Kombination startet der Löser warm (lokale Suche) und trifft trotzdem
        assertTrue(aim.localEvals > 0, "warm start (local search) was never used")
        assertTrue(aim.fullSolves < aim.arcEvaluations * 3, "full grid searches ${aim.fullSolves} for ${aim.arcEvaluations} arcs")
    }
}
