package de.bollwerk.renderapi

import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.engine.tools.Trajectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OverlayStateTest {
    @Test
    fun overlaysWithEqualTrajectoriesAreEqual() {
        // Compose vergleicht State per equals: gleiche Flugbahn in neuem Array darf keine Neuzeichnung erzwingen
        val a = OverlayState(mode = InteractionMode.AIM, tool = ToolSelection.Material(0), trajectory = Trajectory(floatArrayOf(1f, 2f, 3f, 4f, 9f), 2))
        val b = OverlayState(mode = InteractionMode.AIM, tool = ToolSelection.Material(0), trajectory = Trajectory(floatArrayOf(1f, 2f, 3f, 4f), 2))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b.copy(trajectory = Trajectory(floatArrayOf(1f, 2f, 3f, 5f), 2)))
        assertEquals(OverlayState.NONE, OverlayState())
    }
}
