package de.bollwerk.simrunner

import de.bollwerk.content.ClasspathContent
import de.bollwerk.content.ContentDb
import de.bollwerk.engine.sim.GameResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `--ai-vs-ai`: beide Spieler über `AiFactory` (StandardAi), ganze Partie headless. */
class AiVsAiTest {
    private fun plan(seed: Long, difficulty: String = "NORMAL", ticks: Long = 36_000L) =
        RunPlan(name = "ai", map = "schlucht", seed = seed, ticks = ticks, aiVsAi = true, difficulty = difficulty)

    @Test
    fun normalVsNormalOnSchluchtEndsWithAWinnerWithoutRejections() {
        val r = Runner.run(db, plan(seed = 2L))
        assertTrue(r.result is GameResult.Winner, "no winner: ${r.result} after ${r.ticksRun} ticks ${r.agents}")
        assertTrue(r.ticksRun < 36_000L, "should stop at the result")
        assertTrue(r.rejections.isEmpty(), "AI commands rejected: ${r.rejections}")
        assertTrue(r.shots > 20, "AIs should fight: ${r.shots} shots")
        assertTrue(r.impacts > 0 && r.structureHits * 2 >= r.impacts, "hit rate ${r.structureHits}/${r.impacts}")
        // jedem Treffer ist ein Schütze zugeordnet; die meisten treffen den Gegner, kaum einer die eigene Festung
        val attributed = r.enemyHits + r.ownHits + r.debrisHits
        println("impacts=${r.impacts} structure=${r.structureHits} enemy=${r.enemyHits} own=${r.ownHits} debris=${r.debrisHits}")
        assertTrue(attributed * 10 >= r.structureHits * 9, "most structure hits need a shooter: $attributed of ${r.structureHits}")
        assertTrue(r.enemyHits * 10 >= r.structureHits * 6, "enemy hits ${r.enemyHits} of ${r.structureHits}")
        assertTrue(r.ownHits * 10 <= r.structureHits, "own-fort hits ${r.ownHits} of ${r.structureHits}")
        assertEquals(2, r.agents.size)
        assertTrue(r.agents.all { "NORMAL" in it }, r.agents.toString())
        val text = Report.format(r, SimArgs(aiVsAi = true))
        assertTrue("wins (REACTOR_DESTROYED)" in text, text)
        assertTrue("hits: " in text && "by shooter: enemy" in text, text)
    }

    /** Abnahme WP11: NORMAL gegen NORMAL auf schlucht endet für (mindestens) 3 Seeds binnen 10 Sim-Minuten mit Sieger. */
    @Test
    fun normalVsNormalEndsWithAWinnerWithinTenMinutesForThreeSeeds() {
        for (seed in 1L..3L) {
            val r = Runner.run(db, plan(seed = seed))
            println("seed $seed: ${r.result} after ${r.ticksRun / 60} s")
            assertTrue(r.result is GameResult.Winner, "seed $seed: no winner within 10 min: ${r.result} ${r.agents}")
            assertTrue(r.ticksRun <= 36_000L, "seed $seed: ${r.ticksRun} ticks")
        }
    }

    @Test
    fun aiVsAiIsDeterministic() {
        val a = Runner.run(db, plan(seed = 9L, ticks = 2400L))
        val b = Runner.run(db, plan(seed = 9L, ticks = 2400L))
        assertEquals(a.finalHash, b.finalHash)
        assertEquals(a.shots, b.shots)
        assertEquals(a.agents, b.agents)
    }

    @Test
    fun difficultyFlagReachesTheFactory() {
        for (d in SimArgs.DIFFICULTIES) {
            val r = Runner.run(db, plan(seed = 1L, difficulty = d, ticks = 30L))
            assertTrue(r.agents.all { it.startsWith("StandardAi(") && " $d:" in it }, "$d -> ${r.agents}")
        }
    }

    @Test
    fun cliRunsAiVsAiWithDifficulty() {
        val out = StringBuilder()
        val code = execute(arrayOf("--ai-vs-ai", "--difficulty", "hard", "--ticks", "120"), out = { out.appendLine(it) }, err = { out.appendLine(it) })
        assertEquals(0, code, out.toString())
        assertTrue("StandardAi(p0 HARD" in out && "StandardAi(p1 HARD" in out, out.toString())
    }

    private companion object {
        val db: ContentDb by lazy { ClasspathContent.load() }
    }
}
