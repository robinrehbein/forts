package de.bollwerk.app.match

import de.bollwerk.ai.Difficulty
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.TurnMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchConfigTest {
    @Test
    fun vsAiBlueMakesPlayerZeroHumanAndPlayerOneAi() {
        val setup = MatchConfig(map = MapOption.HUEGEL, aiLevel = AiLevel.HARD, team = TeamColor.BLUE, seed = 42).toMatchSetup()
        assertEquals("huegel", setup.mapId)
        assertEquals(42L, setup.seed)
        assertEquals(Controller.HUMAN, setup.players[0].controller)
        assertEquals(Controller.AI, setup.players[1].controller)
        assertEquals(Difficulty.HARD.name, setup.players[1].difficulty)
        assertNull(setup.players[0].difficulty)
        assertEquals(TurnMode.REALTIME, setup.turnMode)
        assertEquals(0, setup.turnTicks)
    }

    @Test
    fun vsAiRedSwapsSides() {
        val config = MatchConfig(aiLevel = AiLevel.EASY, team = TeamColor.RED)
        val setup = config.toMatchSetup()
        assertEquals(Controller.AI, setup.players[0].controller)
        assertEquals(Difficulty.EASY.name, setup.players[0].difficulty)
        assertEquals(Controller.HUMAN, setup.players[1].controller)
        assertEquals(1, config.humanPlayerId)
    }

    @Test
    fun hotseatHasTwoHumansAndTurnMode() {
        val config = MatchConfig(mode = GameMode.HOTSEAT, team = TeamColor.RED)
        val setup = config.toMatchSetup()
        assertTrue(setup.players.all { it.controller == Controller.HUMAN })
        assertEquals(TurnMode.TURNS, setup.turnMode)
        assertEquals(45 * 60, setup.turnTicks)
        assertEquals(0, config.humanPlayerId)
    }

    @Test
    fun resourcesMapToEngineValuesAndNormalMatchesStyleGuide() {
        assertEquals(400f, MatchConfig(resources = StartResources.NORMAL).toMatchSetup().startMetal)
        assertEquals(200f, MatchConfig(resources = StartResources.NORMAL).toMatchSetup().startEnergy)
        val scarce = MatchConfig(resources = StartResources.SCARCE).toMatchSetup()
        val rich = MatchConfig(resources = StartResources.RICH).toMatchSetup()
        assertTrue(scarce.startMetal < 400f && scarce.startEnergy < 200f)
        assertTrue(rich.startMetal > 400f && rich.startEnergy > 200f)
        assertTrue(rich.startMetal <= 1000f && rich.startEnergy <= 400f, "must fit the storage caps")
    }

    @Test
    fun mapIdsMatchContent() {
        assertEquals(listOf("schlucht", "huegel"), MapOption.entries.map { it.id })
        assertEquals(listOf(120, 160), MapOption.entries.map { it.widthMeters })
    }

    @Test
    fun teamOpponentAndIds() {
        assertEquals(TeamColor.RED, TeamColor.BLUE.opponent)
        assertEquals(TeamColor.BLUE, TeamColor.RED.opponent)
        assertEquals(TeamColor.BLUE, teamOf(0))
        assertEquals(TeamColor.RED, teamOf(1))
    }

    @Test
    fun resultVictoryDependsOnHumanInVsAi() {
        val config = MatchConfig(team = TeamColor.RED)
        assertTrue(MatchResult(config, winnerPlayerId = 1, reason = EndReason.ENEMY_REACTOR_DESTROYED).isVictory)
        assertFalse(MatchResult(config, winnerPlayerId = 0, reason = EndReason.OWN_REACTOR_DESTROYED).isVictory)
        assertEquals(1, MatchResult(config, 0, EndReason.OWN_REACTOR_DESTROYED).bannerPlayerId)
    }

    @Test
    fun hotseatAlwaysShowsWinnerBanner() {
        val config = MatchConfig(mode = GameMode.HOTSEAT)
        val r = MatchResult(config, winnerPlayerId = 1, reason = EndReason.ENEMY_REACTOR_DESTROYED)
        assertTrue(r.isVictory)
        assertEquals(1, r.bannerPlayerId)
    }

    @Test
    fun surrenderMakesOpponentWinner() {
        val r = MatchResult.surrender(MatchConfig(), loserPlayerId = 0)
        assertEquals(1, r.winnerPlayerId)
        assertEquals(EndReason.SURRENDER, r.reason)
        assertFalse(r.isVictory)
    }

    @Test
    fun durationFormatting() {
        assertEquals("08:42", formatDuration(8 * 60 + 42))
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:00", formatDuration(-5))
        assertEquals("75:03", formatDuration(75 * 60 + 3))
    }
}
