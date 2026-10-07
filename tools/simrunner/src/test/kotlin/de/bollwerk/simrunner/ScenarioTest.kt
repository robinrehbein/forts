package de.bollwerk.simrunner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScenarioTest {
    @Test
    fun parsesScenarioJson() {
        val s = ScenarioIo.parse(
            """{"name":"t","map":"huegel","seed":3,"ticks":500,"idle":false,
                "commands":[{"tick":10,"action":"shoot","player":1,"device":"cannon","angleDeg":150,"power":0.8,"every":100,"count":3},
                            {"tick":0,"action":"ignite","player":0,"material":"wood","beams":2,"skip":1}]}""",
        )
        assertEquals("t", s.name); assertEquals("huegel", s.map); assertEquals(3L, s.seed); assertEquals(500L, s.ticks)
        assertEquals(2, s.commands.size)
        val c = s.commands[0]
        assertEquals(1, c.player); assertEquals("cannon", c.device); assertEquals(150f, c.angleDeg); assertEquals(0.8f, c.power)
        assertEquals(2, s.commands[1].beams); assertEquals(1, s.commands[1].skip)
    }

    @Test
    fun defaultsApply() {
        val s = ScenarioIo.parse("""{"name":"d"}""")
        assertEquals(SimArgs.DEFAULT_MAP, s.map); assertEquals(SimArgs.DEFAULT_TICKS, s.ticks); assertEquals(SimArgs.DEFAULT_SEED, s.seed)
        assertTrue(s.commands.isEmpty())
    }

    @Test
    fun rejectsInvalidScenarios() {
        assertFailsWith<Exception> { ScenarioIo.parse("""{"name":"x","bogusField":1}""") }
        assertFailsWith<Exception> { ScenarioIo.parse("not json") }
        assertFailsWith<IllegalArgumentException> { ScenarioIo.parse("""{"commands":[{"tick":1,"action":"explode"}]}""") }
        assertFailsWith<IllegalArgumentException> { ScenarioIo.parse("""{"commands":[{"tick":-1,"action":"fire"}]}""") }
        assertFailsWith<IllegalArgumentException> { ScenarioIo.parse("""{"commands":[{"tick":1,"action":"fire","count":3}]}""") } // count ohne every
        assertFailsWith<IllegalArgumentException> { ScenarioIo.load("does_not_exist_zzz") }
    }

    @Test
    fun expandsRepeatsInTickOrderAndCutsAtScenarioEnd() {
        val s = ScenarioIo.parse(
            """{"ticks":250,"commands":[
                {"tick":100,"action":"fire","every":100,"count":5},
                {"tick":0,"action":"aim"},
                {"tick":100,"action":"surrender","player":1}]}""",
        )
        val e = ScenarioIo.expand(s)
        assertEquals(listOf(0L, 100L, 100L, 200L), e.map { it.tick })
        // gleicher Tick: Dateireihenfolge (erst fire, dann surrender)
        assertEquals(listOf("aim", "fire", "surrender", "fire"), e.map { it.action })
    }

    @Test
    fun bundledScenariosAreValid() {
        val dir = scenarioDir()
        val names = listOf("idle_schlucht", "mortar_duel", "fire_spread", "stress_300")
        for (n in names) {
            val s = ScenarioIo.parse(File(dir, "$n.json").readText())
            assertEquals(n, s.name)
            ScenarioIo.expand(s)
        }
        val idle = ScenarioIo.load(File(dir, "idle_schlucht.json").path)
        assertEquals(3600L, idle.ticks); assertEquals("schlucht", idle.map); assertTrue(idle.idle); assertTrue(idle.commands.isEmpty())
        assertTrue(ScenarioIo.load(File(dir, "mortar_duel.json").path).commands.any { it.action == "shoot" && it.player == 0 })
        assertTrue(ScenarioIo.load(File(dir, "mortar_duel.json").path).commands.any { it.action == "shoot" && it.player == 1 })
        assertTrue(ScenarioIo.load(File(dir, "fire_spread.json").path).commands.any { it.incendiary })
        val stress = ScenarioIo.load(File(dir, "stress_300.json").path)
        assertTrue(stress.commands.filter { it.action == "spawnProjectiles" }.sumOf { it.projectiles } >= 100)
    }

    companion object {
        fun scenarioDir(): File = listOf(File("scenarios"), File("tools/simrunner/scenarios")).first { it.isDirectory }
    }
}
