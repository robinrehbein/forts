package de.bollwerk.engine

import de.bollwerk.engine.math.FastTrig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FastTrigTest {
    @Test
    fun sinCosMatchKotlinMath() {
        var maxErr = 0.0
        var a = -20.0
        while (a <= 20.0) {
            val f = a.toFloat()
            maxErr = maxOf(maxErr, abs(FastTrig.sin(f) - kotlin.math.sin(f.toDouble())))
            maxErr = maxOf(maxErr, abs(FastTrig.cos(f) - kotlin.math.cos(f.toDouble())))
            a += 0.0137
        }
        assertTrue(maxErr < 2e-6, "max error $maxErr")
    }

    @Test
    fun atan2MatchesKotlinMathInAllQuadrants() {
        var maxErr = 0.0
        for (i in 0 until 720) {
            val ang = i * kotlin.math.PI / 360.0 - kotlin.math.PI + 1e-4
            for (r in listOf(0.01, 1.0, 37.5)) {
                val x = (kotlin.math.cos(ang) * r).toFloat()
                val y = (kotlin.math.sin(ang) * r).toFloat()
                val expect = kotlin.math.atan2(y.toDouble(), x.toDouble())
                maxErr = maxOf(maxErr, abs(FastTrig.atan2(y, x) - expect))
            }
        }
        assertTrue(maxErr < 5e-6, "max error $maxErr")
    }

    @Test
    fun specialValues() {
        assertEquals(0f, FastTrig.atan2(0f, 0f))
        assertEquals(0f, FastTrig.sin(0f))
        assertEquals(1f, FastTrig.cos(0f), 1e-6f)
        assertEquals(kotlin.math.PI.toFloat() / 2f, FastTrig.atan2(1f, 0f), 1e-6f)
        assertEquals(kotlin.math.PI.toFloat(), FastTrig.atan2(0f, -1f), 1e-6f)
        assertEquals(kotlin.math.PI.toFloat() / 4f, FastTrig.atan(1f), 1e-6f)
    }

    @Test
    fun isPureFunction() {
        // Gleicher Input, gleiche Bits – Grundvoraussetzung für Determinismus
        for (i in 0 until 100) {
            val v = i * 0.731f - 30f
            assertEquals(FastTrig.sin(v).toRawBits(), FastTrig.sin(v).toRawBits())
            assertEquals(FastTrig.atan2(v, 1.3f).toRawBits(), FastTrig.atan2(v, 1.3f).toRawBits())
        }
    }
}
