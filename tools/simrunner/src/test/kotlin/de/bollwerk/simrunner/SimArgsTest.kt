package de.bollwerk.simrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimArgsTest {
    @Test
    fun parsesAllOptions() {
        val a = SimArgs.parse(arrayOf("--scenario", "s1", "--map", "huegel", "--ticks", "120", "--hash", "--profile", "--ai-vs-ai", "--render", "out.png", "--seed", "9"))
        assertEquals(SimArgs("s1", "huegel", 120, 9, hash = true, profile = true, aiVsAi = true, renderPng = "out.png"), a)
        assertEquals(SimArgs(ticks = 600, hash = true), SimArgs.parse(arrayOf("--ticks", "600", "--hash")))
    }

    @Test
    fun rejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--ticks")) }
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--ticks", "abc")) }
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--bogus")) }
        assertTrue(SimArgs.parse(arrayOf("--help")).help)
    }
}
