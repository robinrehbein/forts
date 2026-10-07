package de.bollwerk.engine

import de.bollwerk.engine.rng.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SplitMix64Test {
    @Test
    fun matchesReferenceSequence() {
        // Referenzwerte der Original-C-Implementierung (seed 1234567)
        val r = SplitMix64(1234567L)
        val expected = listOf(
            6457827717110365317uL, 3203168211198807973uL, 9817491932198370423uL,
            4593380528125082431uL, 16408922859458223821uL,
        )
        for (e in expected) assertEquals(e, r.nextLong().toULong())
    }

    @Test
    fun sameSeedSameStream() {
        val a = SplitMix64(42L)
        val b = SplitMix64(42L)
        repeat(1000) {
            assertEquals(a.nextLong(), b.nextLong())
            assertEquals(a.nextInt(17), b.nextInt(17))
            assertEquals(a.nextFloat().toRawBits(), b.nextFloat().toRawBits())
        }
    }

    @Test
    fun rangesAreRespected() {
        val r = SplitMix64(7L)
        repeat(10_000) {
            val i = r.nextInt(10)
            assertTrue(i in 0 until 10)
            val f = r.nextFloat()
            assertTrue(f >= 0f && f < 1f)
        }
        val counts = IntArray(4)
        repeat(40_000) { counts[r.nextInt(4)]++ }
        for (c in counts) assertTrue(c in 9_000..11_000, "skewed: ${counts.toList()}")
    }

    @Test
    fun deriveIsReproducibleIndependentAndDoesNotAdvanceParent() {
        val p = SplitMix64(99L)
        val before = p.state
        val s1 = p.derive(1)
        val s1b = p.derive(1)
        val s2 = p.derive(2)
        assertEquals(before, p.state)
        val a = s1.nextLong()
        assertEquals(a, s1b.nextLong())
        assertNotEquals(a, s2.nextLong())
        assertNotEquals(a, SplitMix64(99L).nextLong())
    }

    @Test
    fun restoreRewinds() {
        val r = SplitMix64(5L)
        val s = r.state
        val x = r.nextLong()
        r.restore(s)
        assertEquals(x, r.nextLong())
    }
}
