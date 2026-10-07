package de.bollwerk.engine.loop

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.TurnConfig
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.view.FxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hotseat über den [MatchRunner]: Phasen aus dem TURN-System, Command-Ergebnisse je Phase, Ende der Partie. */
class HotseatTest {
    private val cfg = SimConfig.DEFAULT.copy(turn = TurnConfig(resolveTicks = 60, handoverTicks = 90))

    private fun runner() = LoopRig.runner(LoopRig.setup(turns = true, turnTicks = 150), hotseat = true, config = cfg)

    private fun beam(player: Int, x: Float) =
        Command.PlaceBeam(0, player, aX = x, aY = 34f, bX = x + 3f, bY = 34f, materialId = RuleTables.WOOD)

    private fun MatchRunner.reasonsAfter(vararg cmds: Command): List<RejectReason?> {
        val got = ArrayList<CommandOutcome>()
        val l = CommandResultListener { got.add(it) }
        addResultListener(l)
        cmds.forEach { submit(it) }
        runTicks(1)
        removeResultListener(l)
        return got.map { it.reason }
    }

    @Test
    fun onlyTheActivePlayerMayActAndOthersGetNotYourTurn() {
        val r = runner()
        r.runTicks(3)
        // Spieler 1 baut in Spieler 0s Zug; Spieler 0 baut
        assertEquals(listOf(RejectReason.NOT_YOUR_TURN, null), r.reasonsAfter(beam(1, 91f), beam(0, 26f)))
        // Aufgeben ist auch außerhalb des eigenen Zugs möglich (Regel: Surrender ausgenommen), hier nicht ausgelöst
        val snap = r.exchange.latest()!!
        assertTrue(snap.fx.any { it is FxEvent.CommandRejected && it.reason == RejectReason.NOT_YOUR_TURN && it.playerId == 1 })
    }

    @Test
    fun nobodyMayActWhileTheShotsResolveOrDuringHandover() {
        val r = runner()
        r.runTicks(2)
        r.submit(Command.EndTurn(0, 0))
        r.runTicks(2)
        assertEquals(TurnPhase.RESOLVE, r.state.turn.phase)
        assertEquals(listOf(RejectReason.NOT_YOUR_TURN, RejectReason.NOT_YOUR_TURN), r.reasonsAfter(beam(0, 26f), beam(1, 91f)))
        while (r.state.turn.phase != TurnPhase.HANDOVER) r.runTicks(1)
        assertEquals(1, r.state.turn.activePlayer)
        assertEquals(listOf(RejectReason.NOT_YOUR_TURN), r.reasonsAfter(beam(1, 91f)))
        while (r.state.turn.phase != TurnPhase.PLAY) r.runTicks(1)
        assertEquals(listOf(null), r.reasonsAfter(beam(1, 91f)))
        assertEquals(2, r.state.turn.turnNumber)
    }

    @Test
    fun theClockEndsATurnAndTheRunnerKeepsSimulatingThroughEveryPhase() {
        val r = runner()
        val phases = ArrayList<TurnPhase>()
        var lastTick = 0L
        for (i in 0 until 700) {
            r.advance(1f / 60f)
            assertTrue(r.tick >= lastTick)
            lastTick = r.tick
            val p = r.state.turn.phase
            if (phases.lastOrNull() != p) phases.add(p)
        }
        assertEquals(listOf(TurnPhase.PLAY, TurnPhase.RESOLVE, TurnPhase.HANDOVER, TurnPhase.PLAY, TurnPhase.RESOLVE, TurnPhase.HANDOVER, TurnPhase.PLAY), phases.take(7))
        assertTrue(r.tick >= 600L, "time passes in every phase: ${r.tick}")
    }

    @Test
    fun surrenderEndsTheMatchFromAnyTurn() {
        val r = runner()
        r.runTicks(2)
        // Spieler 1 gibt in Spieler 0s Zug auf
        assertEquals(listOf<RejectReason?>(null), r.reasonsAfter(Command.Surrender(0, 1)))
        r.runTicks(2)
        assertEquals(GameResult.Winner(0, WinReason.SURRENDER), r.result)
        assertEquals(listOf<RejectReason?>(RejectReason.GAME_OVER), r.reasonsAfter(beam(0, 26f)))
        val h = r.exchange.latest()!!.hud
        assertTrue(!h.canCommand)
        assertEquals(GameResult.Winner(0, WinReason.SURRENDER), h.result)
        assertTrue(!h.handover)
    }
}
