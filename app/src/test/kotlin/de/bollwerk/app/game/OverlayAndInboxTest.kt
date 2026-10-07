package de.bollwerk.app.game

import de.bollwerk.engine.tools.GhostBeam
import de.bollwerk.engine.tools.LoupeRequest
import de.bollwerk.engine.tools.SnapMark
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolOverlay
import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.engine.tools.Trajectory
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.InteractionMode
import de.bollwerk.renderapi.SnapHighlight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OverlayConverterTest {
    private val ghost = GhostBeam(1f, 2f, 3f, 4f, 0, valid = true, reason = null, lengthM = 4.6f, cost = 18f)
    private val build = ToolOverlay(
        mode = ToolMode.BUILD, tool = ToolSelection.Material(0), ghost = ghost,
        snaps = listOf(SnapMark(3f, 4f, 9L, true)), loupe = LoupeRequest(3.5f, 4.5f, 3f, 4f),
        selectedBeamRef = 5L, localPlayer = 1,
    )

    @Test
    fun mapsEveryFieldAndPlacesTheLoupeOverTheFinger() {
        val c = OverlayConverter()
        // Kamera: Mitte (0,0), 48 px/m, Viewport 800 × 400
        val o = c.convert(build, 0f, 0f, 48f, 800f, 400f)
        assertEquals(InteractionMode.BUILD, o.mode)
        assertEquals(ToolSelection.Material(0), o.tool)
        assertSame(ghost, o.ghost)
        assertEquals(listOf(SnapHighlight(3f, 4f, 9L, true)), o.snaps)
        assertEquals(5L, o.selectedBeamRef)
        assertEquals(1, o.localPlayer)
        val l = o.loupe!!
        assertEquals(400f + 3.5f * 48f, l.screenX, 1e-3f)
        assertEquals(200f + 4.5f * 48f, l.screenY, 1e-3f)
        assertEquals(3f, l.worldX)
        assertEquals(4f, l.worldY)
        assertEquals(2f, l.magnification)
        assertEquals(80f, l.offsetDp)
    }

    @Test
    fun unchangedInputReturnsTheSameInstanceWithoutAllocating() {
        val c = OverlayConverter()
        val a = c.convert(build, 0f, 0f, 48f, 800f, 400f)
        repeat(100) { assertSame(a, c.convert(build, 0f, 0f, 48f, 800f, 400f)) }
        assertEquals(1, c.builds)
        // Kamerabewegung baut nur bei offener Lupe neu
        c.convert(build, 1f, 0f, 48f, 800f, 400f)
        assertEquals(2, c.builds)
        val aim = ToolOverlay(mode = ToolMode.AIM, trajectory = Trajectory(floatArrayOf(0f, 0f, 1f, 1f), 2), impactX = 1f, impactY = 1f)
        val b = c.convert(aim, 0f, 0f, 48f, 800f, 400f)
        assertSame(b, c.convert(aim, 5f, 5f, 30f, 800f, 400f))
        assertEquals(3, c.builds)
        assertNull(b.loupe)
        assertEquals(InteractionMode.AIM, b.mode)
        assertEquals(1f, b.impactX)
    }

    @Test
    fun bridgeReadsTheSharedCamera() {
        val cam = Camera(800f, 400f, density = 2f).also { it.centerX = 10f; it.centerY = 5f }
        val bridge = OverlayBridge(cam) { build }
        val o = bridge.current()
        assertEquals(cam.worldToScreenX(3.5f), o.loupe!!.screenX, 1e-3f)
        assertSame(o, bridge.current())
    }

    @Test
    fun everyToolModeHasAnInteractionMode() {
        for (m in ToolMode.entries) assertEquals(m.name, OverlayConverter.modeOf(m).name)
    }
}

class SimInboxTest {
    private data class Ev(val kind: Int, val i0: Int, val f0: Float, val l0: Long)

    private fun drain(inbox: SimInbox): List<Ev> {
        val out = ArrayList<Ev>()
        inbox.drain { kind, i0, f0, _, _, l0, _ -> out += Ev(kind, i0, f0, l0) }
        return out
    }

    @Test
    fun consecutiveMovesOfOneGestureAreCoalesced() {
        val inbox = SimInbox()
        inbox.offer(InboxKind.POINTER, 0, 1f, l0 = 7L)
        inbox.offer(InboxKind.POINTER, SimInbox.PHASE_MOVE, 2f, l0 = 7L)
        inbox.offer(InboxKind.POINTER, SimInbox.PHASE_MOVE, 3f, l0 = 7L)
        inbox.offer(InboxKind.POINTER, 2, 4f, l0 = 7L)
        assertEquals(
            listOf(Ev(InboxKind.POINTER, 0, 1f, 7L), Ev(InboxKind.POINTER, 1, 3f, 7L), Ev(InboxKind.POINTER, 2, 4f, 7L)),
            drain(inbox),
        )
        assertTrue(drain(inbox).isEmpty())
    }

    @Test
    fun fullInboxDropsInsteadOfGrowing() {
        val inbox = SimInbox(capacity = 4)
        repeat(4) { assertTrue(inbox.offer(InboxKind.FIRE)) }
        assertEquals(false, inbox.offer(InboxKind.UNDO))
        assertEquals(4, drain(inbox).size)
        assertTrue(inbox.offer(InboxKind.UNDO), "space again after draining")
    }
}
