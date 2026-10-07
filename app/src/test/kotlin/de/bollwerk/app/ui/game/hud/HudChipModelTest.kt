package de.bollwerk.app.ui.game.hud

import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.HudPresenter
import de.bollwerk.app.game.TurnInfo
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.tools.ToolSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Abbildung des Hotseat-Zug-Chips („SPIELER 1 · ZUG 3 · 0:32") und der neuen Werkzeug-Zustände. */
class HudChipModelTest {
    @Test
    fun playPhaseShowsPlayerTurnAndClock() {
        val m = TurnInfo(TurnPhase.PLAY, secondsLeft = 32, activePlayer = 0, turnNumber = 3).toChipModel()
        assertTrue(m.play)
        assertEquals(1, m.playerNumber)
        assertEquals(3, m.turnNumber)
        assertEquals("0:32", m.timeText)
        assertFalse(m.urgent)
        assertEquals(0, m.teamPlayer)
    }

    @Test
    fun lastTenSecondsAreUrgentAndPlayerTwoIsPlayerTwo() {
        val m = TurnInfo(TurnPhase.PLAY, secondsLeft = 9, activePlayer = 1, turnNumber = 4).toChipModel()
        assertEquals(2, m.playerNumber)
        assertTrue(m.urgent)
        assertEquals("0:09", m.timeText)
    }

    @Test
    fun resolvePhaseHasNoActivePlayerChip() {
        val m = TurnInfo(TurnPhase.RESOLVE, secondsLeft = 4, activePlayer = -1, turnNumber = 2).toChipModel()
        assertFalse(m.play)
        assertFalse(m.urgent, "kurze Auflösungs-Uhr ist keine Zeitnot")
    }

    @Test
    fun clockFormat() {
        assertEquals("0:00", formatTurnTime(-5))
        assertEquals("1:05", formatTurnTime(65))
        assertEquals("2:00", formatTurnTime(120))
    }

    @Test
    fun presenterMarksDeleteAndDoorTools() {
        val catalog = HudFixtures.catalog
        fun ui(sel: ToolSelection) = HudPresenter.present(
            HudFixtures.mockupHud(), HudFixtures.buildTools().copy(selection = sel), catalog, hotseat = false,
        )
        assertTrue(ui(ToolSelection.Delete).deleteSelected)
        assertFalse(ui(ToolSelection.Delete).doorSelected)
        assertTrue(ui(ToolSelection.Door).doorSelected)
        assertTrue(ui(ToolSelection.Repair).repairSelected)
        assertFalse(ui(ToolSelection.None).deleteSelected)
    }

    @Test
    fun moreToolsTapSelectsAndTapOnSelectedDeselects() {
        val calls = ArrayList<ToolSelection>()
        val actions = object : HudActions { override fun selectTool(selection: ToolSelection) { calls += selection } }
        actions.pickMoreTool(selected = false, tool = ToolSelection.Delete)
        actions.pickMoreTool(selected = true, tool = ToolSelection.Delete)
        actions.pickMoreTool(selected = false, tool = ToolSelection.Door)
        actions.pickMoreTool(selected = false, tool = ToolSelection.Repair)
        assertEquals(listOf(ToolSelection.Delete, ToolSelection.None, ToolSelection.Door, ToolSelection.Repair), calls)
    }

    @Test
    fun moreToolsBarIsNeverOpenWithoutCommandRights() {
        assertTrue(moreToolsVisible(open = true, canCommand = true))
        assertFalse(moreToolsVisible(open = true, canCommand = false))
        assertFalse(moreToolsVisible(open = false, canCommand = true))
    }
}
