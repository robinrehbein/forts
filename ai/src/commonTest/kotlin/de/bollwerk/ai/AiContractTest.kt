package de.bollwerk.ai

import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiContractTest {
    @Test
    fun difficultyValuesMatchSpec() {
        assertEquals(6f, Difficulty.EASY.aimErrorDeg)
        assertEquals(3f, Difficulty.NORMAL.aimErrorDeg)
        assertEquals(1f, Difficulty.HARD.aimErrorDeg)
        assertTrue(Difficulty.HARD.thinkIntervalTicks < Difficulty.EASY.thinkIntervalTicks)
    }

    @Test
    fun idleAiPlugsIntoSessionAndDoesNothing() {
        val state = GameState(seed = 11L, tables = SimTables.EMPTY, map = MapSpec.flat())
        val ai = IdleAi(playerId = 1, difficulty = Difficulty.HARD)
        val s = GameSession(state, sources = listOf(ai))
        s.run(120)
        assertEquals(120L, state.tick)
        assertTrue(ai.commandsFor(state.tick, state).isEmpty())
    }
}
