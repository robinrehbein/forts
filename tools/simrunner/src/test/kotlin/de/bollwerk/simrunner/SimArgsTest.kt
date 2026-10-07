package de.bollwerk.simrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimArgsTest {
    @Test
    fun parsesAllOptions() {
        val a = SimArgs.parse(
            arrayOf(
                "--scenario", "s1", "--map", "huegel", "--ticks", "120", "--seed", "9", "--hash", "--profile", "--assert-stable", "--ai-vs-ai",
                "--render", "out.png", "--at", "0", "--at", "3600", "--width", "800", "--height", "400", "--warmup", "30", "--view", "1,2,30.5,40",
            ),
        )
        assertEquals(
            SimArgs(
                scenario = "s1", map = "huegel", ticks = 120, seed = 9, hash = true, profile = true, assertStable = true, aiVsAi = true,
                renderPng = "out.png", renderAt = listOf(0L, 3600L), width = 800, height = 400, warmup = 30, view = listOf(1f, 2f, 30.5f, 40f),
            ),
            a,
        )
    }

    @Test
    fun unsetOptionsStayNullSoScenarioValuesApply() {
        val a = SimArgs.parse(arrayOf("--hash"))
        assertNull(a.map); assertNull(a.ticks); assertNull(a.seed); assertNull(a.scenario)
        assertEquals(SimArgs.DEFAULT_WIDTH, a.width)
        assertEquals(SimArgs.DEFAULT_HEIGHT, a.height)
        assertTrue(a.renderAt.isEmpty())
    }

    @Test
    fun rejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--ticks")) }
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--ticks", "abc")) }
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--ticks", "-5")) }
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--seed", "x")) }
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--bogus")) }
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--at", "10")) } // ohne --render
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--render", "a.png", "--width", "2")) }
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--view", "1,2,3")) }
        assertFailsWith<IllegalArgumentException> { SimArgs.parse(arrayOf("--view", "5,5,1,9")) }
        assertTrue(SimArgs.parse(arrayOf("--help")).help)
    }

    @Test
    fun planPrecedenceIsCliOverScenarioOverDefault() {
        val sc = Scenario(name = "x", map = "huegel", seed = 5, ticks = 100, idle = true)
        val plain = RunPlan.of(SimArgs(), sc)
        assertEquals(Triple("huegel", 5L, 100L), Triple(plain.map, plain.seed, plain.ticks))
        assertTrue(plain.idle)
        val over = RunPlan.of(SimArgs(map = "schlucht", seed = 2, ticks = 7), sc)
        assertEquals(Triple("schlucht", 2L, 7L), Triple(over.map, over.seed, over.ticks))
        val none = RunPlan.of(SimArgs(), null)
        assertEquals(Triple(SimArgs.DEFAULT_MAP, SimArgs.DEFAULT_SEED, SimArgs.DEFAULT_TICKS), Triple(none.map, none.seed, none.ticks))
    }
}
