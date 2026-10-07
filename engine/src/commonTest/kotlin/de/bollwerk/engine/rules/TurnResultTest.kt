package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.rules.RuleTables.WOOD
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.TurnConfig
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.view.FxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hotseat: 45-s-Züge, nur der aktive Spieler, Nachlauf, Übergabe, gleiche Zugzahl. */
class TurnTest {
    private val turn = TurnConfig.DEFAULT_PLAY_TICKS // 45 s

    private fun hotseat(config: SimConfig = SimConfig.DEFAULT) = RulesRig(turns = true, turnTicks = turn, config = config)

    private fun RulesRig.build(player: Int, from: Int, x: Float, y: Float) =
        Command.PlaceBeam(tick, player, aNodeRef = nref(from), bX = x, bY = y, materialId = WOOD)

    @Test
    fun startsWithPlayerZeroAndFortyFiveSeconds() {
        val rig = hotseat()
        assertEquals(0, rig.state.turn.activePlayer)
        assertEquals(1, rig.state.turn.turnNumber)
        assertEquals(TurnPhase.PLAY, rig.state.turn.phase)
        assertEquals(2700, rig.state.turn.ticksLeft)
        assertEquals(45 * 60, turn)
    }

    @Test
    fun onlyTheActivePlayersCommandsAreAccepted() {
        val rig = hotseat()
        val enemyApex = rig.nodeAt(97f, 31f)
        rig.rejects(RejectReason.NOT_YOUR_TURN, Command.PlaceBeam(rig.tick, 1, aNodeRef = rig.nref(enemyApex), bX = 97f, bY = 27f, materialId = WOOD))
        rig.ok(rig.build(0, rig.apex, 23f, 27f))
        rig.rejects(RejectReason.NOT_YOUR_TURN, Command.EndTurn(rig.tick, 1))
        rig.rejects(RejectReason.NOT_YOUR_TURN, Command.Undo(rig.tick, 1))
    }

    @Test
    fun endTurnRunsResolveThenHandoverThenNextPlayersTurn() {
        val rig = hotseat()
        rig.ok(Command.EndTurn(rig.tick, 0))
        val t = rig.state.turn
        assertEquals(TurnPhase.RESOLVE, t.phase)
        assertEquals(0, t.activePlayer, "während des Nachlaufs bleibt der bisherige Spieler aktiv")
        // niemand darf im Nachlauf Commands geben
        rig.rejects(RejectReason.NOT_YOUR_TURN, rig.build(0, rig.apex, 23f, 27f))
        rig.rejects(RejectReason.NOT_YOUR_TURN, Command.EndTurn(rig.tick, 0))
        // die Simulation läuft weiter: Reaktor-Energie fließt
        val e = rig.player().energy
        while (t.ticksLeft > 1) rig.step()
        assertEquals(TurnPhase.RESOLVE, t.phase)
        assertTrue(rig.player().energy > e)
        rig.run(1)
        assertEquals(TurnPhase.HANDOVER, t.phase)
        assertEquals(1, t.activePlayer, "Übergabe an den nächsten Spieler")
        assertEquals(2, t.turnNumber)
        assertEquals(180, t.ticksLeft)
        rig.rejects(RejectReason.NOT_YOUR_TURN, Command.EndTurn(rig.tick, 1))
        rig.run(178)
        assertEquals(TurnPhase.HANDOVER, t.phase)
        rig.run(1)
        assertEquals(TurnPhase.PLAY, t.phase)
        assertEquals(1, t.activePlayer)
        assertEquals(2700, t.ticksLeft)
        // jetzt darf Spieler 1 bauen, Spieler 0 nicht
        rig.rejects(RejectReason.NOT_YOUR_TURN, rig.build(0, rig.apex, 23f, 27f))
        rig.ok(Command.PlaceBeam(rig.tick, 1, aNodeRef = rig.nref(rig.nodeAt(97f, 31f)), bX = 97f, bY = 27f, materialId = WOOD))
    }

    @Test
    fun turnEndsByItselfAfterFortyFiveSeconds() {
        val rig = hotseat()
        rig.run(2699)
        assertEquals(TurnPhase.PLAY, rig.state.turn.phase)
        assertEquals(1, rig.state.turn.ticksLeft)
        rig.run(1)
        assertEquals(TurnPhase.RESOLVE, rig.state.turn.phase)
        rig.run(360 + 180)
        assertEquals(TurnPhase.PLAY, rig.state.turn.phase)
        assertEquals(1, rig.state.turn.activePlayer)
    }

    @Test
    fun playersAlternateWithEqualNumberOfTurns() {
        val rig = hotseat()
        val played = IntArray(2)
        var lastKey = -1
        for (i in 0 until 12) { // 6 volle Runden
            // Zug beginnt
            while (rig.state.turn.phase != TurnPhase.PLAY) rig.step()
            val p = rig.state.turn.activePlayer
            val key = rig.state.turn.turnNumber
            assertTrue(key != lastKey)
            lastKey = key
            played[p]++
            rig.ok(Command.EndTurn(rig.tick, p))
            while (rig.state.turn.phase != TurnPhase.PLAY) rig.step()
        }
        assertEquals(listOf(6, 6), played.toList())
        assertEquals(13, rig.state.turn.turnNumber)
        assertEquals(0, rig.state.turn.activePlayer)
    }

    @Test
    fun unlimitedTurnsDoNotEndByTime() {
        val rig = RulesRig(turns = true, turnTicks = 0)
        rig.run(10_000)
        assertEquals(TurnPhase.PLAY, rig.state.turn.phase)
        assertEquals(0, rig.state.turn.activePlayer)
        rig.ok(Command.EndTurn(rig.tick, 0))
        assertEquals(TurnPhase.RESOLVE, rig.state.turn.phase)
    }

    @Test
    fun handoverCanBeSkippedByConfig() {
        val rig = hotseat(SimConfig(turn = TurnConfig(resolveTicks = 10, handoverTicks = 0)))
        rig.ok(Command.EndTurn(rig.tick, 0))
        rig.run(9)
        assertEquals(TurnPhase.PLAY, rig.state.turn.phase)
        assertEquals(1, rig.state.turn.activePlayer)
        assertEquals(2700, rig.state.turn.ticksLeft)
    }

    @Test
    fun surrenderIsAllowedForTheInactivePlayerToo() {
        val rig = hotseat()
        val r = rig.send(Command.Surrender(rig.tick, 1)).single()
        assertEquals(CommandResult.Accepted, r)
        assertEquals(GameResult.Winner(0, WinReason.SURRENDER), rig.state.result)
    }

    @Test
    fun endTurnIsMeaninglessInRealtime() {
        val rig = RulesRig()
        rig.rejects(RejectReason.INVALID_TARGET, Command.EndTurn(rig.tick, 0))
        // beide Spieler dürfen gleichzeitig bauen
        rig.ok(Command.PlaceBeam(rig.tick, 1, aNodeRef = rig.nref(rig.nodeAt(97f, 31f)), bX = 97f, bY = 27f, materialId = WOOD))
        rig.ok(rig.build(0, rig.apex, 23f, 27f))
    }

    @Test
    fun turnStateIsHashed() {
        val a = hotseat()
        val b = hotseat()
        a.run(100); b.run(100)
        assertEquals(de.bollwerk.engine.loop.StateHash.of(a.state), de.bollwerk.engine.loop.StateHash.of(b.state))
        b.ok(Command.EndTurn(b.tick, 0))
        a.step()
        assertFalse(de.bollwerk.engine.loop.StateHash.of(a.state) == de.bollwerk.engine.loop.StateHash.of(b.state))
    }
}

/** Ergebnis: Reaktor zerstört → anderer gewinnt, beide → Unentschieden, Aufgeben. */
class ResultTest {
    @Test
    fun destroyedReactorMakesTheOtherPlayerWin() {
        val rig = RulesRig()
        rig.run(5)
        assertEquals(GameResult.Ongoing, rig.state.result)
        rig.killReactor(1)
        rig.run(1)
        assertEquals(GameResult.Winner(0, WinReason.REACTOR_DESTROYED), rig.state.result)
        assertFalse(rig.state.players[1].alive)
        assertTrue(rig.state.players[0].alive)
        val ev = rig.fx.filterIsInstance<FxEvent.ReactorDestroyed>().single()
        assertEquals(1, ev.playerId)
    }

    @Test
    fun theOtherReactorMeansTheOtherWinner() {
        val rig = RulesRig()
        rig.killReactor(0)
        rig.run(1)
        assertEquals(GameResult.Winner(1, WinReason.REACTOR_DESTROYED), rig.state.result)
    }

    @Test
    fun bothReactorsInTheSameTickIsADraw() {
        val rig = RulesRig()
        rig.killReactor(0)
        rig.killReactor(1)
        rig.run(1)
        assertEquals(GameResult.Draw, rig.state.result)
        assertEquals(2, rig.fx.filterIsInstance<FxEvent.ReactorDestroyed>().size)
    }

    @Test
    fun resultIsStickyAndCommandsAreRejectedAfterwards() {
        val rig = RulesRig()
        rig.killReactor(1)
        rig.run(1)
        val result = rig.state.result
        rig.rejects(RejectReason.GAME_OVER, Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(rig.apex), bX = 23f, bY = 27f, materialId = WOOD))
        rig.killReactor(0)
        rig.run(5)
        assertEquals(result, rig.state.result)
        assertEquals(1, rig.fx.filterIsInstance<FxEvent.ReactorDestroyed>().size)
    }

    @Test
    fun surrenderMakesTheOtherPlayerWin() {
        val rig = RulesRig()
        rig.ok(Command.Surrender(rig.tick, 0))
        assertEquals(GameResult.Winner(1, WinReason.SURRENDER), rig.state.result)
        assertFalse(rig.state.players[0].alive)
        rig.rejects(RejectReason.GAME_OVER, Command.Surrender(rig.tick, 1))
    }

    @Test
    fun deletedOrSilentlyRemovedReactorAlsoCounts() {
        // Reaktor "still" entfernt (Kill-Grenzen, Trümmer): gleiche Wirkung
        val rig = RulesRig()
        rig.state.devices.release(rig.state.players[0].reactorDeviceId)
        rig.run(1)
        assertEquals(GameResult.Winner(1, WinReason.REACTOR_DESTROYED), rig.state.result)
    }

    @Test
    fun aReusedSlotIsNotMistakenForTheReactor() {
        val rig = RulesRig()
        val r = rig.state.players[0].reactorDeviceId
        rig.state.devices.release(r)
        rig.state.devices.endTick() // Slot wird frei
        val reused = rig.addDevice(RuleTables.TURBINE, rig.ground01, 0.5f)
        assertEquals(r, reused)
        rig.run(1)
        assertEquals(GameResult.Winner(1, WinReason.REACTOR_DESTROYED), rig.state.result)
    }

    // ---- Zugmodus: erst am Rundenende, damit beide gleich viele Züge hatten ----

    private fun hotseat() = RulesRig(turns = true, turnTicks = 2700)

    @Test
    fun inTurnModeTheResultWaitsForTheEndOfTheRound() {
        val rig = hotseat()
        // Spieler 0 zerstört im ersten Zug den Reaktor von Spieler 1
        rig.killReactor(1)
        rig.run(10)
        assertEquals(GameResult.Ongoing, rig.state.result, "Spieler 1 bekommt noch seinen Zug")
        assertFalse(rig.state.players[1].alive)
        rig.ok(Command.EndTurn(rig.tick, 0))
        rig.run(360 + 180) // Nachlauf + Übergabe
        assertEquals(1, rig.state.turn.activePlayer)
        assertEquals(TurnPhase.PLAY, rig.state.turn.phase)
        assertEquals(GameResult.Ongoing, rig.state.result)
        rig.ok(Command.EndTurn(rig.tick, 1))
        rig.run(360)
        assertEquals(GameResult.Winner(0, WinReason.REACTOR_DESTROYED), rig.state.result)
        assertEquals(2, rig.state.turn.turnNumber, "kein weiterer Zug beginnt")
    }

    @Test
    fun inTurnModeAReturnStrikeInTheLastTurnIsADraw() {
        val rig = hotseat()
        rig.killReactor(1)
        rig.ok(Command.EndTurn(rig.tick, 0))
        rig.run(360 + 180)
        rig.killReactor(0) // Spieler 1 schlägt in seinem Zug zurück
        rig.ok(Command.EndTurn(rig.tick, 1))
        rig.run(360)
        assertEquals(GameResult.Draw, rig.state.result)
    }

    @Test
    fun inTurnModeTheSecondPlayerWinningEndsTheGameRightAfterHisTurn() {
        val rig = hotseat()
        rig.ok(Command.EndTurn(rig.tick, 0))
        rig.run(360 + 180)
        rig.killReactor(0)
        rig.run(10)
        assertEquals(GameResult.Ongoing, rig.state.result)
        rig.ok(Command.EndTurn(rig.tick, 1))
        rig.run(360)
        assertEquals(GameResult.Winner(1, WinReason.REACTOR_DESTROYED), rig.state.result)
    }

    @Test
    fun surrenderInTurnModeEndsTheGameImmediately() {
        val rig = hotseat()
        rig.ok(Command.Surrender(rig.tick, 0))
        assertEquals(GameResult.Winner(1, WinReason.SURRENDER), rig.state.result)
    }
}
