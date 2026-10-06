package de.bollwerk.engine

import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.Terrain
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BallisticsTest {
    private val cfg = SimConfig.DEFAULT

    @Test
    fun predictMatchesTickByTickSimulationBitExactly() {
        val out = FloatArray(400)
        val n = Ballistics.predict(10f, 30f, 52f * FloatMath.DEG_TO_RAD, 0.78f, 33f, 3.2f, cfg, out, 200)
        assertEquals(200, n)
        val st = FloatArray(4)
        Ballistics.launch(10f, 30f, 52f * FloatMath.DEG_TO_RAD, 0.78f, 33f, st)
        for (i in 0 until n) {
            Ballistics.tick(st, 3.2f, cfg)
            assertEquals(st[0].toRawBits(), out[i * 2].toRawBits())
            assertEquals(st[1].toRawBits(), out[i * 2 + 1].toRawBits())
        }
    }

    @Test
    fun predictStopsAtTerrainAndWindDrifts() {
        val terrain = Terrain.flat(-40f, 300f, 34f)
        val out = FloatArray(4000)
        val n0 = Ballistics.predict(10f, 33f, 52f * FloatMath.DEG_TO_RAD, 0.78f, 33f, 0f, cfg, out, 2000, terrain)
        val lastX0 = out[(n0 - 1) * 2]
        assertEquals(34f, out[(n0 - 1) * 2 + 1], 1e-3f)
        var apex = 33f
        for (i in 0 until n0) apex = minOf(apex, out[i * 2 + 1])
        // Analytisch: v = 25,74 m/s, vy = 20,28 → Scheitel ≈ vy²/2g ≈ 21 m
        assertTrue(abs((33f - apex) - 20.97f) < 0.5f, "apex ${33f - apex}")
        val n1 = Ballistics.predict(10f, 33f, 52f * FloatMath.DEG_TO_RAD, 0.78f, 33f, 3.2f, cfg, out, 2000, terrain)
        assertTrue(out[(n1 - 1) * 2] > lastX0 + 1f, "Rückenwind trägt weiter")
    }

    @Test
    fun solveAngleHitsTargetLowAndHighArc() {
        val terrain = Terrain.flat(-40f, 300f, 34f)
        for (high in listOf(false, true)) {
            for (wind in listOf(-3f, 0f, 4f)) {
                val a = Ballistics.solveAngle(10f, 30f, 70f, 33f, 0.9f, 33f, wind, cfg, high)
                assertTrue(a.isFinite(), "unlösbar high=$high wind=$wind")
                val out = FloatArray(4000)
                val n = Ballistics.predict(10f, 30f, a, 0.9f, 33f, wind, cfg, out, 2000, terrain)
                // Bahn kreuzt x = 70 nahe y = 33
                var best = Float.MAX_VALUE
                for (i in 0 until n) if (abs(out[i * 2] - 70f) < 0.6f) best = minOf(best, abs(out[i * 2 + 1] - 33f))
                assertTrue(best < 0.8f, "high=$high wind=$wind err=$best")
            }
        }
        val lo = Ballistics.solveAngle(10f, 30f, 70f, 33f, 0.9f, 33f, 0f, cfg, false)
        val hi = Ballistics.solveAngle(10f, 30f, 70f, 33f, 0.9f, 33f, 0f, cfg, true)
        assertTrue(hi > lo)
        // nach links gespiegelt
        val left = Ballistics.solveAngle(110f, 30f, 50f, 33f, 0.9f, 33f, 0f, cfg, false)
        assertTrue(left > FloatMath.HALF_PI)
        // außer Reichweite
        assertTrue(Ballistics.solveAngle(0f, 30f, 500f, 30f, 0.3f, 33f, 0f, cfg, false).isNaN())
    }

    @Test
    fun deviceMountFollowsPrototypeConvention() {
        val out = FloatArray(DeviceGeometry.SIZE)
        // Balken von (0,0) nach (4,0): Normale (−0, 1) = nach unten; sideNegative → nach oben
        DeviceGeometry.mountAt(0f, 0f, 4f, 0f, 0.25f, true, 0.32f, 0.55f, 0.62f, 1.05f, 0f, out)
        assertEquals(1f, out[DeviceGeometry.X], 1e-6f)
        assertEquals(-0.16f, out[DeviceGeometry.Y], 1e-6f)
        assertEquals(-1f, out[DeviceGeometry.NY], 1e-6f)
        assertEquals(-0.71f, out[DeviceGeometry.CY], 1e-5f)
        assertEquals(-0.78f, out[DeviceGeometry.PIVOT_Y], 1e-5f)
        assertEquals(1f + 1.05f, out[DeviceGeometry.MUZZLE_X], 1e-5f)
        DeviceGeometry.mountAt(0f, 0f, 4f, 0f, 0.25f, true, 0.32f, 0.55f, 0.62f, 1.05f, FloatMath.HALF_PI, out)
        assertEquals(-0.78f - 1.05f, out[DeviceGeometry.MUZZLE_Y], 1e-5f) // 90° = nach oben
    }

    @Test
    fun deviceMountFromView() {
        val s = TestWorld.state()
        val a = s.nodes.alloc(0f, 0f, 0); val b = s.nodes.alloc(4f, 0f, 0)
        val beam = s.beams.alloc(a, b, TestWorld.WOOD, 4f, 100f, 0)
        val d = s.devices.alloc(TestWorld.MORTAR, beam, 0.5f, 90f, 0, 0, true, 0f, 1f)
        val out = FloatArray(DeviceGeometry.SIZE)
        DeviceGeometry.mount(s, d, out)
        assertEquals(2f, out[DeviceGeometry.X], 1e-6f)
        assertEquals(2f + 1.05f, out[DeviceGeometry.MUZZLE_X], 1e-5f)
    }
}
