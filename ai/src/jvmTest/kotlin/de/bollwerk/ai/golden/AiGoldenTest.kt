package de.bollwerk.ai.golden

import de.bollwerk.ai.AiFactory
import de.bollwerk.ai.Difficulty
import de.bollwerk.content.ClasspathContent
import de.bollwerk.content.ContentDb
import de.bollwerk.engine.GameInfo
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.setup.MatchBootstrap
import de.bollwerk.setup.MatchRunners
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * KI-gegen-KI auf echtem Content: [record] spielt zwei [AiFactory]-Agenten bis zum Sieg ([maxTicks] als Obergrenze) plus Nachlauf.
 *
 * Welcher Seed aufgezeichnet wird, steht im Golden (`replay.setup.seed`). Beim Neuerzeugen ([recordFirstSeedThatEndsWithAWinner])
 * wird aus [seeds] der erste genommen, dessen Partie binnen [maxTicks] mit einem zerstörten Reaktor endet: so hängt das Golden
 * nicht an einem Glücksseed, der nach jedem KI-Tuning kippen kann.
 */
class AiGoldenScenario(
    val name: String,
    val mapId: String,
    val seeds: List<Long>,
    val difficulty: Difficulty,
    val maxTicks: Long,
    val tailTicks: Long,
) {
    fun setup(seed: Long) = MatchSetup(
        seed, mapId, listOf(PlayerSetup(Controller.AI, difficulty.name), PlayerSetup(Controller.AI, difficulty.name)),
    )

    fun record(db: ContentDb, seed: Long, maxTicks: Long = this.maxTicks, tailTicks: Long = this.tailTicks): Replay {
        val setup = setup(seed)
        val tables = MatchBootstrap.create(db, setup).tables
        val agents = listOf(0, 1).map { AiFactory.create(it, difficulty, tables, seed = seed) }
        val runner = MatchRunners.create(db, setup, sources = agents, record = true)
        runner.runTicks(maxTicks, stopOnResult = true)
        // der Nachlauf nach dem Sieg (Reaktor-Explosion, Einsturz, Brände) gehört mit zum Golden
        if (runner.finished) runner.runTicks(tailTicks)
        return runner.toReplay()!!
    }

    /** Für das Neuerzeugen: erster Seed aus [seeds], dessen Aufnahme [AiGoldenTest.checkRealMatch] besteht. */
    fun recordFirstSeedThatEndsWithAWinner(db: ContentDb, check: (AiGoldenScenario, Replay) -> Unit): Replay {
        val problems = ArrayList<String>()
        for (seed in seeds) {
            val replay = record(db, seed)
            try {
                check(this, replay)
                return replay
            } catch (e: AssertionError) {
                problems.add("seed $seed: ${e.message}")
            }
        }
        throw AssertionError("no seed of $name in $seeds ends with a destroyed reactor within $maxTicks ticks:\n" + problems.joinToString("\n"))
    }
}

/**
 * Golden-Replays der Gegner-KI (Stufe HARD, beide Seiten) auf beiden Karten. Zwei Prüfungen je Golden (siehe [GoldenSupport]):
 * Die Aufnahme muss bitgleich abspielen (Sim/Regeln/Content), und die KI muss genau diese Commands wieder erzeugen
 * (KI-Verhalten). Beide Partien enden mit einem zerstörten Reaktor.
 *
 * Regenerieren nach absichtlicher Änderung von Sim, Content **oder KI-Tuning** (erst, wenn die KI-Änderungen eingespielt sind;
 * der Seed wird neu gewählt, falls der bisherige keinen Sieg mehr liefert):
 * `BOLLWERK_REGEN_GOLDENS=1 ./gradlew :ai:jvmTest --tests '*AiGoldenTest*' --rerun`
 */
class AiGoldenTest {
    private val db = ClasspathContent.load()
    private val golden = GoldenSupport("ai", db)

    private val seeds = (101L..112L).toList()
    private val schlucht = AiGoldenScenario("ai_hard_schlucht", "schlucht", seeds, Difficulty.HARD, maxTicks = 9_000L, tailTicks = 120L)
    private val huegel = AiGoldenScenario("ai_hard_huegel", "huegel", seeds, Difficulty.HARD, maxTicks = 9_000L, tailTicks = 120L)

    /** Der Seed des eingecheckten Goldens (er steht im Replay) und das daraus abgeleitete erwartete Setup. */
    private fun storedSeed(s: AiGoldenScenario): Long {
        val seed = golden.load(s.name).setup.seed
        assertTrue(seed in s.seeds, "golden '${s.name}' was recorded with seed $seed which is not in the scenario's seed list ${s.seeds}; regenerate goldens: `${golden.regenCommand}`")
        return seed
    }

    private fun verify(s: AiGoldenScenario) {
        if (golden.regen) {
            val fresh = s.recordFirstSeedThatEndsWithAWinner(db, ::checkRealMatch)
            golden.regenerate(s.name, fresh) { checkRealMatch(s, it) }
            return
        }
        val replay = golden.verifyStored(s.name, s.setup(storedSeed(s)))
        assertEquals(db.fingerprint, replay.contentHash, "golden '${s.name}' must be recorded with the shipping content")
        assertEquals(GameInfo.ENGINE_VERSION, replay.engineVersion)
    }

    private fun reRecord(s: AiGoldenScenario) {
        if (golden.regen) return
        golden.reRecordMatches(s.name, s.record(db, storedSeed(s)), "AI")
    }

    @Test fun hardAiOnSchluchtReplaysBitIdentical() = verify(schlucht)

    @Test fun hardAiOnSchluchtIsReproducedByTheAi() = reRecord(schlucht)

    @Test fun hardAiOnHuegelReplaysBitIdentical() = verify(huegel)

    @Test fun hardAiOnHuegelIsReproducedByTheAi() = reRecord(huegel)

    /**
     * Das Golden ist eine echte Partie: beide Seiten bauen, schießen und stellen Türen, und am Ende fällt ein Reaktor.
     * Wird auch beim Neuerzeugen vor dem Schreiben geprüft (und wählt dort den Seed).
     */
    fun checkRealMatch(s: AiGoldenScenario, replay: Replay) {
        val runner = MatchRunners.play(db, replay)
        var reactors = 0
        var explosions = 0
        for (i in 0 until replay.ticks) {
            runner.runTicks(1)
            val fx = runner.exchange.latest()!!.fx
            reactors += fx.count { it is FxEvent.ReactorDestroyed }
            explosions += fx.count { it is FxEvent.Explosion }
        }
        val result = runner.result
        assertTrue(result is GameResult.Winner && result.reason == WinReason.REACTOR_DESTROYED, "${s.name}: $result after ${replay.ticks} ticks")
        assertEquals(1, reactors, "${s.name}: exactly one reactor falls")
        assertTrue(explosions >= 10, "${s.name}: $explosions explosions")
        assertTrue(replay.ticks in 4_000L..s.maxTicks + s.tailTicks, "${s.name}: ${replay.ticks} ticks")
        val cmds = replay.commands
        assertEquals(setOf(0, 1), cmds.map { it.playerId }.toSet(), "${s.name}: both AIs act")
        assertTrue(cmds.count { it is Command.Fire } >= 20 && cmds.count { it is Command.PlaceBeam } >= 10 && cmds.any { it is Command.PlaceDevice }, "${s.name}: fire/beam/device counts")
        assertTrue(cmds.any { it is Command.SetAim } && cmds.any { it is Command.ToggleDoor }, "${s.name}: SetAim and ToggleDoor")
    }

    @Test
    fun goldensAreRealMatchesThatEndWithADestroyedReactor() {
        if (golden.regen) return
        for (s in listOf(schlucht, huegel)) checkRealMatch(s, golden.load(s.name))
    }

    /** Selbst aufgezeichnete Kurzpartie statt des eingecheckten Goldens: die Meldung hängt nicht am Zustand der Goldens. */
    @Test
    fun tamperedAiMatchFailsWithTheFirstDivergingTickAndTheRegenerationHint() {
        val stored = schlucht.record(db, seed = seeds.first(), maxTicks = 1_300L, tailTicks = 0L)
        assertTrue(stored.checkpoints.any { it.tick == 1200L }, "the short match reaches tick 1200")
        val bad = stored.copy(checkpoints = stored.checkpoints.map { if (it.tick == 1200L) it.copy(hash = it.hash xor 1L) else it })
        val msg = assertFailsWith<AssertionError> { golden.verifyReplay(schlucht.name, bad) }.message.orEmpty()
        assertTrue("First diverging tick: 1200" in msg && "BOLLWERK_REGEN_GOLDENS=1 ./gradlew :ai:jvmTest" in msg, msg)
        val old = assertFailsWith<AssertionError> { golden.verifyReplay(schlucht.name, stored.copy(contentHash = stored.contentHash xor 1L)) }.message.orEmpty()
        assertTrue("regenerate goldens" in old, old)
    }
}
