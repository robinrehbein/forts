package de.bollwerk.setup.golden

import de.bollwerk.content.ClasspathContent
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.loop.ReplayVerification
import de.bollwerk.setup.MatchRunners
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Die Golden-Prüfung scheitert verständlich: Wir verfälschen eine **selbst aufgezeichnete** Kurzpartie (nicht die
 * eingecheckten Goldens) und prüfen die Meldungen (erster abweichender Tick, Weg zum Neuerzeugen, Hinweis auf
 * Content/ENGINE_VERSION). Dadurch sind diese Tests unabhängig vom Zustand der Goldens: Sie laufen auch beim Neuerzeugen
 * und fügen einem echten Golden-Bruch keine irreführenden Folgefehler hinzu.
 */
class GoldenFailureMessageTest {
    private val db = ClasspathContent.load()
    private val golden = GoldenSupport("setup", db)
    private val name = "message_test_match"

    /** Eine kurze Hotseat-Partie mit Schüssen, frisch aufgezeichnet (Fire-Commands und Prüfpunkte sind vorhanden). */
    private val base: Replay by lazy { GoldenScenarios.hotseat.record(db) }

    private fun failure(replay: Replay): String =
        assertFailsWith<AssertionError> { golden.verifyReplay(name, replay) }.message.orEmpty()

    @Test
    fun theFreshRecordingIsAValidBaseline() {
        golden.verifyReplay(name, base)
    }

    @Test
    fun hashMismatchNamesTheFirstDivergingTickAndHowToRegenerate() {
        val bad = base.copy(checkpoints = base.checkpoints.map { if (it.tick == 600L) it.copy(hash = it.hash xor 1L) else it })
        val msg = failure(bad)
        assertTrue("First diverging tick: 600" in msg, msg)
        assertTrue("cause in ticks 541..600" in msg, msg)
        assertTrue("BOLLWERK_REGEN_GOLDENS=1 ./gradlew :setup:jvmTest" in msg && "ENGINE_VERSION" in msg, msg)
    }

    @Test
    fun changedCommandIsReportedAtTheTickItBreaksReplay() {
        val i = base.commands.indexOfFirst { it is Command.Fire }
        assertTrue(i >= 0, "the baseline has a Fire command")
        val removed = base.commands[i]
        val changed = base.copy(commands = base.commands.filterIndexed { k, _ -> k != i })
        // das Ergebnis der Prüfung selbst: wo wird die Abweichung festgestellt?
        val v = MatchRunners.verify(db, changed)
        assertTrue(v is ReplayVerification.Diverged, "removing a Fire must be detected: $v")
        val interval = Replay.CHECKPOINT_INTERVAL.toLong()
        val firstCheckpointAfter = (removed.tick / interval + 1) * interval
        assertTrue(
            v.tick >= removed.tick && v.tick <= firstCheckpointAfter,
            "divergence reported at tick ${v.tick}, expected within ${removed.tick}..$firstCheckpointAfter (removed Fire at tick ${removed.tick})",
        )
        // und die Meldung nennt genau diesen Tick samt Weg zum Neuerzeugen
        val msg = failure(changed)
        assertTrue("First diverging tick: ${v.tick}" in msg, msg)
        assertTrue("regenerate goldens" in msg && "ENGINE_VERSION" in msg, msg)
    }

    @Test
    fun contentOrEngineVersionChangeAsksToRegenerate() {
        val content = failure(base.copy(contentHash = base.contentHash + 1))
        assertTrue("content hash" in content && "regenerate goldens" in content, content)
        val engine = failure(base.copy(engineVersion = base.engineVersion + 1))
        assertTrue("engine version" in engine && "regenerate goldens" in engine, engine)
    }

    @Test
    fun checkpointsOffTheSixtyTickGridAreRejected() {
        val msg = failure(base.copy(checkpoints = base.checkpoints.filterIndexed { k, _ -> k % 2 == 0 || k == base.checkpoints.size - 1 }))
        assertTrue("60-tick grid" in msg, msg)
    }

    @Test
    fun aMissingGoldenFileAsksToCreateIt() {
        val msg = assertFailsWith<AssertionError> { golden.load("does_not_exist") }.message.orEmpty()
        assertTrue("is missing" in msg && "BOLLWERK_REGEN_GOLDENS=1" in msg, msg)
    }

    @Test
    fun aGoldenWithTheWrongSetupIsRejectedWithTheRegenerationHint() {
        if (golden.regen) return // dieser Test liest die eingecheckte Datei, die beim Neuerzeugen gerade ersetzt wird
        val other = GoldenScenarios.allCommands.setup
        val msg = assertFailsWith<AssertionError> { golden.verifyStored(GoldenScenarios.hotseat.name, other) }.message.orEmpty()
        assertTrue("another setup" in msg && "BOLLWERK_REGEN_GOLDENS=1" in msg, msg)
    }
}
