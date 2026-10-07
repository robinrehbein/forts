package de.bollwerk.ai

import de.bollwerk.engine.math.FloatMath
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AimErrorTest {
    @Test
    fun aimErrorHasSigmaOfTheDifficulty() {
        for (d in Difficulty.entries) {
            val rng = AiRng(seed = 42L, playerId = 1)
            val n = 20_000
            var sum = 0.0
            var sq = 0.0
            for (i in 0 until n) {
                val e = (AimController.applyError(1f, d.aimErrorDeg, rng) - 1f) / FloatMath.DEG_TO_RAD
                sum += e; sq += e.toDouble() * e
            }
            val mean = sum / n
            val sd = sqrt(sq / n - mean * mean)
            assertTrue(abs(mean) < 0.05 * d.aimErrorDeg, "$d mean $mean")
            assertTrue(abs(sd - d.aimErrorDeg) < 0.05 * d.aimErrorDeg, "$d sd $sd (want ${d.aimErrorDeg})")
        }
    }

    @Test
    fun aiRngIsItsOwnDeterministicStream() {
        val a = AiRng(7L, 0); val b = AiRng(7L, 0); val c = AiRng(7L, 1)
        val sa = List(8) { a.nextFloat() }
        assertEquals(sa, List(8) { b.nextFloat() })
        assertTrue(sa != List(8) { c.nextFloat() }, "players must get different streams")
    }

    @Test
    fun zeroSigmaLeavesAngleUntouched() {
        val rng = AiRng(1L, 0)
        val s = rng.state
        assertEquals(0.7f, AimController.applyError(0.7f, 0f, rng))
        assertEquals(s, rng.state, "no draw without error")
    }
}
