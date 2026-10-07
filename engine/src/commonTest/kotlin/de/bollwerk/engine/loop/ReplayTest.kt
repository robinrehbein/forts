package de.bollwerk.engine.loop

import de.bollwerk.engine.GameInfo
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.sim.GameResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Replay aufzeichnen, als JSON speichern, abspielen und mit [ReplayVerifier] prüfen. */
class ReplayTest {
    private val fingerprint = LoopRig.fingerprint(RuleTables.tables)

    private fun record(ticks: Long = 2400L, seed: Long = 11L): Replay {
        val setup = LoopRig.setup(seed)
        val r = LoopRig.runner(setup, sources = listOf(DuelBot(0), DuelBot(1, fireFrom = 2300L)))
        r.runTicks(ticks)
        return r.toReplay()!!
    }

    private fun verify(replay: Replay): ReplayVerification =
        ReplayVerifier.verify(replay, fingerprint) { LoopRig.session(replay.setup, config = replay.config) }

    @Test
    fun recordsSetupVersionsCommandsAndCheckpointsEverySecond() {
        val replay = record()
        assertEquals(11L, replay.setup.seed)
        assertEquals(GameInfo.ENGINE_VERSION, replay.engineVersion)
        assertEquals(fingerprint, replay.contentHash)
        assertEquals(LoopRig.CONTENT_VERSION, replay.contentVersion)
        assertEquals(2400L, replay.ticks)
        assertTrue(replay.commands.any { it is Command.PlaceDevice })
        assertTrue(replay.commands.any { it is Command.Fire })
        // Prüfpunkte: Tick 0 und jede 60. Tick bis zum Ende, aufsteigend
        assertEquals((0L..2400L step 60L).toList(), replay.checkpoints.map { it.tick })
        assertNull(replay.incompatibility(fingerprint))
    }

    @Test
    fun finalCheckpointIsAddedWhenTheMatchEndsBetweenIntervals() {
        val replay = record(ticks = 2430L)
        assertEquals(2430L, replay.checkpoints.last().tick)
        assertEquals(2430L, replay.ticks)
        assertIs<ReplayVerification.Ok>(verify(replay))
        // erneutes Abfragen am selben Tick doppelt nichts
        val r = LoopRig.runner()
        r.runTicks(70)
        r.toReplay(); r.toReplay()
        assertEquals(listOf(0L, 60L, 70L), r.toReplay()!!.checkpoints.map { it.tick })
    }

    @Test
    fun onlyAcceptedCommandsAreRecorded() {
        val r = LoopRig.runner(LoopRig.setup(metal = 5f))
        r.submit(Command.PlaceBeam(0, 0, aX = 26f, aY = 34f, bX = 30f, bY = 34f, materialId = RuleTables.WOOD)) // zu teuer
        r.submit(Command.PlaceBeam(0, 0, aX = 26f, aY = 34f, bX = 27.2f, bY = 34f, materialId = RuleTables.WOOD)) // 4,8 ⚙
        r.runTicks(5)
        val replay = r.toReplay()!!
        assertEquals(1, replay.commands.size)
        assertEquals(27.2f, (replay.commands.single() as Command.PlaceBeam).bX)
        assertIs<ReplayVerification.Ok>(verify(replay))
    }

    @Test
    fun jsonRoundTripIsLosslessAndPrettyPrintIsEquivalent() {
        val replay = record()
        val compact = replay.toJson()
        val pretty = replay.toJson(pretty = true)
        assertTrue(pretty.length > compact.length && pretty.contains("\n"))
        assertEquals(replay, Replay.fromJson(compact))
        assertEquals(replay, Replay.fromJson(pretty))
        assertEquals(compact, Replay.fromJson(pretty).toJson())
    }

    @Test
    fun playbackReproducesEveryCheckpointAndTheFinalHash() {
        val replay = Replay.fromJson(record().toJson()) // über JSON, wie aus einer Datei
        val v = assertIs<ReplayVerification.Ok>(verify(replay), verify(replay).message())
        assertEquals(2400L, v.ticks)
        assertEquals(replay.checkpoints.size, v.checkpointsChecked)
        assertEquals(replay.checkpoints.last().hash, v.finalHash)
    }

    @Test
    fun playbackThroughAMatchRunnerWithAScriptedSourceMatches() {
        val replay = record()
        val runner = MatchRunner(LoopRig.session(replay.setup), sources = listOf(ScriptedCommandSource(replay.commands)))
        runner.runTicks(replay.ticks)
        assertEquals(replay.checkpoints.last().hash, runner.hash())
    }

    @Test
    fun verifierNamesTheFirstDivergingCheckpointWhenTheStateDiffers() {
        val replay = record()
        val bad = replay.copy(checkpoints = replay.checkpoints.map { if (it.tick == 1200L) it.copy(hash = it.hash xor 1L) else it })
        val v = assertIs<ReplayVerification.Diverged>(verify(bad))
        assertEquals(ReplayVerification.Kind.HASH, v.kind)
        assertEquals(1200L, v.tick)
        assertEquals(1140L, v.lastGoodTick)
        assertNotEquals(v.expected, v.actual)
        assertTrue(v.message().contains("tick 1200"), v.message())
    }

    @Test
    fun verifierReportsADifferentSetupAtTheFirstCheckpoint() {
        val replay = record()
        // anderer Seed: schon Tick 0 (Wind) weicht ab
        val v = assertIs<ReplayVerification.Diverged>(verify(replay.copy(setup = replay.setup.copy(seed = 12L))))
        assertEquals(0L, v.tick)
    }

    @Test
    fun verifierFindsTheExactTickOfARejectedRecordedCommand() {
        val replay = record()
        // eine Bauaktion löschen: spätere Platzierungen auf diesem Balken scheitern (STALE_TARGET) im genauen Tick
        val firstBeam = replay.commands.first { it is Command.PlaceBeam && it.playerId == 0 }
        val tampered = replay.copy(commands = replay.commands.filter { it != firstBeam })
        val v = assertIs<ReplayVerification.Diverged>(verify(tampered))
        assertEquals(ReplayVerification.Kind.REJECTED, v.kind)
        assertEquals(2L, v.tick)
        assertTrue(v.command is Command.PlaceDevice)
        assertTrue(v.message().contains("rejected"), v.message())
    }

    @Test
    fun verifierDetectsAnExtraCommandViaTheNextCheckpoint() {
        val replay = record()
        val extra = Command.PlaceBeam(100, 0, aX = 26f, aY = 34f, bX = 27.2f, bY = 34f, materialId = RuleTables.WOOD)
        val v = verify(replay.copy(commands = (replay.commands + extra).sortedBy { it.tick }))
        val d = assertIs<ReplayVerification.Diverged>(v)
        assertEquals(120L, d.tick) // erster Prüfpunkt nach Tick 100
        assertEquals(60L, d.lastGoodTick)
    }

    @Test
    fun incompatibleReplaysAreRefusedBeforeAnythingRuns() {
        val replay = record(ticks = 120L)
        var built = false
        fun v(r: Replay, content: Long = fingerprint, engine: Int = GameInfo.ENGINE_VERSION) =
            ReplayVerifier.verify(r, content, engine) { built = true; LoopRig.session(r.setup) }
        val engine = assertIs<ReplayVerification.Incompatible>(v(replay.copy(engineVersion = 99)))
        assertTrue(engine.reason.contains("engine version"))
        val content = assertIs<ReplayVerification.Incompatible>(v(replay, content = fingerprint + 1))
        assertTrue(content.reason.contains("content hash"))
        assertIs<ReplayVerification.Incompatible>(v(replay.copy(formatVersion = 1)))
        assertTrue(!built, "no session is created for incompatible replays")
        assertIs<ReplayVerification.Ok>(v(replay))
    }

    @Test
    fun unknownJsonFieldsAreRejectedInsteadOfPlayedWrong() {
        val json = record(ticks = 60L).toJson().replaceFirst("\"ticks\"", "\"bogus\":1,\"ticks\"")
        assertFailsWith<Exception> { Replay.fromJson(json) }
    }

    @Test
    fun firstDifferenceOfTwoCheckpointLists() {
        val a = listOf(HashCheckpoint(0, 1), HashCheckpoint(60, 2), HashCheckpoint(120, 3))
        assertNull(ReplayVerifier.firstDifference(a, a.toList()))
        assertEquals(60L, ReplayVerifier.firstDifference(a, listOf(HashCheckpoint(0, 1), HashCheckpoint(60, 9), HashCheckpoint(120, 3))))
        assertEquals(120L, ReplayVerifier.firstDifference(a, a.take(2)))
        assertEquals(0L, ReplayVerifier.firstDifference(a, emptyList()))
    }

    @Test
    fun aMatchThatEndsKeepsAReplayThatReproducesTheResult() {
        val setup = LoopRig.setup(seed = 11L)
        val r = LoopRig.runner(setup, sources = listOf(DuelBot(0), DuelBot(1, fireFrom = 3000L)))
        r.runTicks(3600L)
        assertTrue(r.result is GameResult.Winner)
        val replay = r.toReplay()!!
        val player = MatchRunner(LoopRig.session(replay.setup), sources = listOf(ScriptedCommandSource(replay.commands)))
        player.runTicks(replay.ticks)
        assertEquals(r.result, player.result)
        assertEquals(r.hash(), player.hash())
    }

    // ---- Vollständigkeit der Prüfung ----

    @Test
    fun aTruncatedTicksFieldDoesNotVerifyAsOk() {
        val replay = record() // Prüfpunkte bis 2400
        val v = assertIs<ReplayVerification.Diverged>(verify(replay.copy(ticks = 120L)))
        assertEquals(ReplayVerification.Kind.INCOMPLETE, v.kind)
        assertFalse(v.ok)
        assertTrue(v.message().contains("incomplete"), v.message())
        assertTrue(v.message().contains("unchecked"), v.message())
    }

    @Test
    fun aReplayWithoutCheckpointsDoesNotVerifyAsOk() {
        val v = assertIs<ReplayVerification.Diverged>(verify(record(ticks = 300L).copy(checkpoints = emptyList())))
        assertEquals(ReplayVerification.Kind.INCOMPLETE, v.kind)
    }

    @Test
    fun aReplayWhoseLastCheckpointIsNotAtTheEndDoesNotVerifyAsOk() {
        val replay = record(ticks = 300L)
        val v = assertIs<ReplayVerification.Diverged>(verify(replay.copy(ticks = 330L)))
        assertEquals(ReplayVerification.Kind.INCOMPLETE, v.kind)
        assertTrue(v.message().contains("final tick"), v.message())
        // dagegen: ticks genau am letzten Prüfpunkt ist vollständig
        assertIs<ReplayVerification.Ok>(verify(replay))
    }

    // ---- Verspätete Commands ----

    @Test
    fun lateCommandsOfAnotherPlayerReplayInTheSameOrderTheyWereApplied() {
        val beam0 = Command.PlaceBeam(10, 0, aX = 26f, aY = 34f, bX = 31f, bY = 34f, materialId = RuleTables.WOOD)
        // Spieler 1 kommt verspätet (Tick 9, Netzwerk), Spieler 0 pünktlich: live läuft [P0, P1], das Replay sortiert ebenso
        val beam1Late = Command.PlaceBeam(9, 1, aX = 94f, aY = 34f, bX = 89f, bY = 34f, materialId = RuleTables.WOOD)
        val late = CommandSource { t, _ -> if (t == 10L) listOf(beam1Late, beam0) else emptyList() }
        val setup = LoopRig.setup(seed = 5L)
        val r = LoopRig.runner(setup, sources = listOf(late))
        val applied = ArrayList<Pair<Long, Int>>()
        r.addResultListener { if (it.accepted) applied.add(it.tick to it.command.playerId) }
        r.runTicks(130L)
        assertEquals(listOf(10L to 0, 10L to 1), applied, "stamped to the applied tick, ordered by player")
        val replay = r.toReplay()!!
        assertEquals(listOf(0, 1), replay.commands.map { it.playerId })
        assertTrue(replay.commands.all { it.tick == 10L })
        assertIs<ReplayVerification.Ok>(verify(replay))
        // auch über JSON und eine Skriptquelle im Runner
        val player = MatchRunner(LoopRig.session(replay.setup), sources = listOf(ScriptedCommandSource(Replay.fromJson(replay.toJson()).commands)))
        player.runTicks(replay.ticks)
        assertEquals(r.hash(), player.hash())
    }
}
