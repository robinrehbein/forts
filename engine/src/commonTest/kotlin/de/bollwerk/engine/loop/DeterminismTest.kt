package de.bollwerk.engine.loop

import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.TurnConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Gleiches Setup und gleiche Skripte → gleiche Hashes bei jedem Prüfpunkt, unabhängig von Frame-Zeiten. */
class DeterminismTest {
    private fun duel(seed: Long = 11L, p1FireFrom: Long = 2300L) = LoopRig.runner(
        LoopRig.setup(seed), sources = listOf(DuelBot(0), DuelBot(1, fireFrom = p1FireFrom)),
    )

    private fun hotseat(seed: Long = 5L): MatchRunner {
        val cfg = SimConfig.DEFAULT.copy(turn = TurnConfig(resolveTicks = 120, handoverTicks = 60))
        return LoopRig.runner(
            LoopRig.setup(seed, turns = true, turnTicks = 300), hotseat = true, config = cfg,
            sources = listOf(TurnBot(0, endEarly = true), TurnBot(1, endEarly = false)),
        )
    }

    @Test
    fun sameSetupTwiceGivesIdenticalHashesAtEveryCheckpoint() {
        val a = duel().also { it.runTicks(3000) }
        val b = duel().also { it.runTicks(3000) }
        assertEquals(51, a.recordedCheckpoints.size)
        assertNull(ReplayVerifier.firstDifference(a.recordedCheckpoints, b.recordedCheckpoints))
        assertEquals(a.hash(), b.hash())
        assertEquals(a.recordedCommands, b.recordedCommands)
    }

    @Test
    fun hotseatMatchIsDeterministicToo() {
        val a = hotseat().also { it.runTicks(3000) }
        val b = hotseat().also { it.runTicks(3000) }
        assertNull(ReplayVerifier.firstDifference(a.recordedCheckpoints, b.recordedCheckpoints))
        assertTrue(a.state.turn.turnNumber >= 3, "several turns played: ${a.state.turn.turnNumber}")
    }

    @Test
    fun seedAndScriptChangeTheHash() {
        val base = duel().also { it.runTicks(2500) }
        val seed = duel(seed = 12L).also { it.runTicks(2500) }
        val script = duel(p1FireFrom = 2100L).also { it.runTicks(2500) }
        assertNotEquals(base.hash(), seed.hash())
        assertNotEquals(base.hash(), script.hash())
        // der Seed wirkt schon im Anfangszustand (Wind), das Skript erst später
        assertEquals(0L, ReplayVerifier.firstDifference(base.recordedCheckpoints, seed.recordedCheckpoints))
        assertTrue(ReplayVerifier.firstDifference(base.recordedCheckpoints, script.recordedCheckpoints)!! >= 2100L)
    }

    /** Frame-Zeiten beeinflussen nur, wie viele Ticks pro Frame laufen, nie das Ergebnis. */
    @Test
    fun frameTimingNeverChangesTheOutcome() {
        val reference = duel().also { it.runTicks(2400) }
        fun viaAdvance(frame: (Int) -> Float): MatchRunner {
            val r = duel()
            var i = 0
            while (r.tick < 2400L && i < 100_000) r.advance(frame(i++))
            return r
        }
        var lcg = 12345L
        val patterns = listOf<(Int) -> Float>(
            { 1f / 60f },
            { 1f / 30f },
            { 0.25f },
            { 0.004f },
            { lcg = lcg * 6364136223846793005L + 1442695040888963407L; 0.002f + ((lcg ushr 33) % 40L) / 1000f },
        )
        for ((k, p) in patterns.withIndex()) {
            val r = viaAdvance(p)
            // der Lauf kann über 2400 hinausgeschossen sein: bis dahin gleiche Prüfpunkte
            val n = minOf(reference.recordedCheckpoints.size, r.recordedCheckpoints.size)
            assertTrue(n > 30, "pattern $k ran far enough")
            assertNull(ReplayVerifier.firstDifference(reference.recordedCheckpoints.take(n), r.recordedCheckpoints.take(n)), "pattern $k")
        }
    }

    @Test
    fun pausingAndSpeedChangesDoNotChangeTheOutcome() {
        val reference = duel().also { it.runTicks(1500) }
        val r = duel()
        var i = 0
        while (r.tick < 1500L) {
            when (i % 50) {
                10 -> r.setPaused(true)
                14 -> r.setPaused(false)
                20 -> r.setSpeed(3f)
                30 -> r.setSpeed(0.5f)
                40 -> r.setSpeed(1f)
            }
            r.advance(1f / 60f)
            i++
        }
        val n = minOf(reference.recordedCheckpoints.size, r.recordedCheckpoints.size)
        assertNull(ReplayVerifier.firstDifference(reference.recordedCheckpoints.take(n), r.recordedCheckpoints.take(n)))
    }

    @Test
    fun localInputDrivenRunMatchesItsOwnReplay() {
        // dieselben Eingaben über LocalInputSource statt Skript: Replay trägt die Anwendungs-Ticks
        val r = LoopRig.runner(inputDelayTicks = 6)
        r.runTicks(30)
        r.submit(de.bollwerk.engine.command.Command.PlaceBeam(0, 0, aX = 26f, aY = 34f, bX = 29f, bY = 34f, materialId = 0))
        r.runTicks(600)
        val replay = r.toReplay()!!
        assertEquals(36L, replay.commands.single().tick)
        val v = ReplayVerifier.verify(replay, LoopRig.fingerprint(de.bollwerk.engine.rules.RuleTables.tables)) { LoopRig.session(replay.setup) }
        assertTrue(v.ok, v.message())
    }
}
