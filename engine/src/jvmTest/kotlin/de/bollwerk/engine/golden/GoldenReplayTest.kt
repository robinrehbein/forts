package de.bollwerk.engine.golden

import de.bollwerk.engine.GameInfo
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.MatchRunner
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.loop.ScriptedCommandSource
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.loop.ReplayVerification
import de.bollwerk.engine.loop.ReplayVerifier
import de.bollwerk.engine.loop.LoopRig
import de.bollwerk.engine.loop.StateHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden-Replays: Aufgezeichnete Partien (Commands + Hash alle 60 Ticks) müssen auf jedem Build bitgleich nachspielbar
 * sein. Schlägt ein Test fehl, hat sich das Sim-Verhalten geändert:
 *
 * - **Unbeabsichtigt:** Determinismus-Bug (Reihenfolge, Float-Mischung, HashMap-Iteration …) beheben. Die Meldung nennt den
 *   ersten abweichenden Prüfpunkt-Tick; `ReplayVerifier` meldet eine abgelehnte aufgezeichnete Eingabe schon im genauen Tick.
 * - **Absichtlich:** `GameInfo.ENGINE_VERSION` erhöhen und die Dateien neu erzeugen, siehe [GoldenScenario] (`GOLDEN_REGEN=1`).
 */
class GoldenReplayTest {
    private val regen: Boolean =
        System.getenv("GOLDEN_REGEN") == "1" || System.getProperty("bollwerk.golden.regen") == "true"

    private fun load(s: GoldenScenario): Replay {
        val text = javaClass.getResourceAsStream("/replays/${s.fileName}")?.bufferedReader()?.readText()
            ?: fail("golden replay /replays/${s.fileName} is missing; create it with: GOLDEN_REGEN=1 ./gradlew :engine:jvmTest --tests '*GoldenReplayTest*' --rerun")
        return Replay.fromJson(text)
    }

    private fun hint(s: GoldenScenario) =
        "If this change is intentional: bump GameInfo.ENGINE_VERSION and regenerate with " +
            "`GOLDEN_REGEN=1 ./gradlew :engine:jvmTest --tests '*GoldenReplayTest*' --rerun`, then review `git diff engine/src/jvmTest/resources/replays`."

    private fun check(s: GoldenScenario) {
        if (regen) {
            val fresh = s.record()
            val f = s.sourceFile()
            f.parentFile.mkdirs()
            f.writeText(fresh.toJson(pretty = true) + "\n")
            println("GOLDEN_REGEN: wrote ${f.absolutePath} (${fresh.commands.size} commands, ${fresh.ticks} ticks, final ${StateHash.hex(fresh.checkpoints.last().hash)})")
            val verdict = ReplayVerifier.verify(fresh, LoopRig.fingerprint(s.tables)) { s.session(fresh) }
            assertTrue(verdict.ok, verdict.message())
            return
        }
        val replay = load(s)
        val verdict = ReplayVerifier.verify(replay, LoopRig.fingerprint(s.tables)) { s.session(replay) }
        if (!verdict.ok) fail("Golden replay '${s.name}' failed: ${verdict.message()}\n${hint(s)}")
        assertEquals(s.ticks, replay.ticks)
        verdict as ReplayVerification.Ok
        assertEquals(replay.checkpoints.last().hash, verdict.finalHash)
    }

    @Test fun buildFireDuel() = check(GoldenScenarios.buildFireDuel)

    @Test fun fireSpread() = check(GoldenScenarios.fireSpread)

    @Test fun hotseatTurns() = check(GoldenScenarios.hotseatTurns)

    /** Das Skript ist dasselbe wie bei der Aufnahme: erneutes Aufzeichnen ergibt exakt die eingecheckte Datei. */
    @Test
    fun goldenFilesAreReproducibleFromTheScripts() {
        if (regen) return
        for (s in GoldenScenarios.all) {
            val fresh = s.record()
            val stored = load(s)
            val diff = ReplayVerifier.firstDifference(stored.checkpoints, fresh.checkpoints)
            assertEquals(null, diff, "scripted recording of '${s.name}' differs from the stored golden replay at tick $diff. ${hint(s)}")
            assertEquals(stored.commands, fresh.commands, "commands of '${s.name}' differ. ${hint(s)}")
            assertEquals(stored.toJson(pretty = true), fresh.toJson(pretty = true), "'${s.name}' differs. ${hint(s)}")
        }
    }

    /** Die Szenarien tun, was ihre Beschreibung sagt (sonst wären die Golden-Hashes wertlos). */
    @Test
    fun scenariosExerciseWhatTheyClaim() {
        if (regen) return
        val duel = playBack(GoldenScenarios.buildFireDuel)
        assertEquals(GameInfo.ENGINE_VERSION, duel.replay.engineVersion)
        assertEquals(GameResult.Winner(0, WinReason.REACTOR_DESTROYED), duel.result)
        assertTrue(duel.replay.commands.count { it is Command.Fire } >= 3, "duel fires")
        assertTrue(duel.replay.commands.any { it is Command.PlaceDevice })
        assertTrue(duel.count<FxEvent.Explosion>() >= 3, "shells explode")
        assertTrue(duel.count<FxEvent.DeviceDestroyed>() >= 1 || duel.count<FxEvent.ReactorDestroyed>() >= 1)

        val fire = playBack(GoldenScenarios.fireSpread)
        assertTrue(fire.count<FxEvent.Ignited>() >= 3, "several beams catch fire: ${fire.count<FxEvent.Ignited>()}")
        assertTrue(fire.peakBurning >= 4, "fire spreads over shared nodes: peak ${fire.peakBurning}")
        assertTrue(fire.count<FxEvent.BeamBroken>() >= 1)

        val hot = playBack(GoldenScenarios.hotseatTurns)
        assertTrue(hot.replay.commands.count { it is Command.EndTurn } >= 3, "hotseat ends turns")
        assertTrue(hot.replay.commands.any { it is Command.Fire })
        assertEquals(setOf(0, 1), hot.replay.commands.map { it.playerId }.toSet())
        assertTrue(hot.maxTurn >= 7, "turn ${hot.maxTurn}")
        assertEquals(setOf(TurnPhase.PLAY, TurnPhase.RESOLVE, TurnPhase.HANDOVER), hot.phases)
        assertTrue(hot.count<FxEvent.Explosion>() >= 2)
    }

    private class Played(val replay: Replay, val result: GameResult) {
        val fx = ArrayList<FxEvent>()
        var peakBurning = 0
        var maxTurn = 0
        val phases = HashSet<TurnPhase>()
        inline fun <reified T : FxEvent> count(): Int = fx.count { it is T }
    }

    private fun playBack(s: GoldenScenario): Played {
        val replay = load(s)
        val runner = MatchRunner(s.session(replay), sources = listOf(ScriptedCommandSource(replay.commands)))
        val out = Played(replay, GameResult.Ongoing)
        runner.exchange.latest()
        for (i in 0 until replay.ticks) {
            runner.runTicks(1)
            out.fx.addAll(runner.exchange.latest()!!.fx)
            val st = runner.state
            var burning = 0
            for (b in 0 until st.beams.size) if (st.beams.isAlive(b) && st.beams.fireOf[b] > 0f) burning++
            if (burning > out.peakBurning) out.peakBurning = burning
            if (st.turn.turnNumber > out.maxTurn) out.maxTurn = st.turn.turnNumber
            if (st.turn.mode == TurnMode.TURNS) out.phases.add(st.turn.phase)
        }
        return Played(replay, runner.result).also { r -> r.fx.addAll(out.fx); r.peakBurning = out.peakBurning; r.maxTurn = out.maxTurn; r.phases.addAll(out.phases) }
    }
}
