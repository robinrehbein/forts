package de.bollwerk.app.game

import de.bollwerk.engine.tools.PointerPhase
import de.bollwerk.renderapi.Camera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Gestenführung mit synthetischen Ereignissen (ohne Android): Werkzeug, Schwenk, Pinch, Langdruck, Doppeltipp. */
class InputControllerTest {
    private class FakeSink : GestureSink {
        val events = ArrayList<String>()
        var consumed: Boolean? = true
        var lastPick = 0f
        var doubleTaps = 0

        override fun pointer(phase: PointerPhase, worldX: Float, worldY: Float, pickRadiusM: Float, gestureId: Long) {
            events += "${phase.name}#$gestureId"
            lastPick = pickRadiusM
        }

        override fun longPress(worldX: Float, worldY: Float, pickRadiusM: Float, gestureId: Long) { events += "LONG#$gestureId" }
        override fun cancelGesture() { events += "CANCEL" }
        override fun gestureConsumed(gestureId: Long): Boolean? = consumed
        override fun doubleTap(worldX: Float, worldY: Float) { doubleTaps++ }
    }

    private val camera = Camera(800f, 400f, density = 2f).also { it.centerX = 50f; it.centerY = 20f }
    private val sink = FakeSink()
    private val input = InputController(camera, sink).also { it.density = 2f }
    private val e = TouchSample()

    private fun down(x: Float, y: Float, t: Long = 0) = input.onTouch(e.set(TouchSample.DOWN, t, 1, x, y))
    private fun move(x: Float, y: Float, t: Long = 0) = input.onTouch(e.set(TouchSample.MOVE, t, 1, x, y))
    private fun up(x: Float, y: Float, t: Long = 0) = input.onTouch(e.set(TouchSample.UP, t, 0, x, y))

    @Test
    fun oneFingerDrivesTheToolWithWorldCoordinatesAndA24dpRadius() {
        down(400f, 200f)
        move(450f, 200f)
        up(450f, 200f)
        assertEquals(listOf("DOWN#1", "MOVE#1", "UP#1"), sink.events)
        // 24 dp · 2 px/dp = 48 px; 1 m = 24 dp · 2 = 48 px → 1 m
        assertEquals(1f, sink.lastPick, 1e-4f)
    }

    @Test
    fun unconsumedGestureBecomesACameraPan() {
        sink.consumed = false
        val cx = camera.centerX
        down(400f, 200f)
        move(496f, 200f) // 96 px = 2 m
        assertEquals(cx - 2f, camera.centerX, 1e-3f)
        assertEquals(listOf("DOWN#1"), sink.events, "panning moves are not sent to the tool")
    }

    @Test
    fun unknownFeedbackWaitsInsteadOfPanning() {
        sink.consumed = null
        val cx = camera.centerX
        down(400f, 200f)
        move(496f, 200f)
        assertEquals(cx, camera.centerX)
        assertEquals(listOf("DOWN#1", "MOVE#1"), sink.events)
    }

    @Test
    fun secondFingerCancelsTheToolAndPinchZooms() {
        down(300f, 200f)
        input.onTouch(e.set(TouchSample.POINTER_DOWN, 10, 2, 300f, 200f, 500f, 200f))
        assertEquals(listOf("DOWN#1", "CANCEL"), sink.events)
        val z = camera.zoom
        input.onTouch(e.set(TouchSample.MOVE, 20, 2, 200f, 200f, 600f, 200f)) // Abstand 200 → 400
        assertEquals(z * 2f, camera.zoom, 1e-3f)
        // Nach dem Pinch startet der verbliebene Finger keine Werkzeug-Geste
        input.onTouch(e.set(TouchSample.POINTER_UP, 30, 1, 200f, 200f))
        input.onTouch(e.set(TouchSample.MOVE, 40, 1, 260f, 200f))
        up(260f, 200f, 50)
        assertEquals(listOf("DOWN#1", "CANCEL"), sink.events)
    }

    @Test
    fun twoFingerDragPansTheCamera() {
        down(300f, 200f)
        input.onTouch(e.set(TouchSample.POINTER_DOWN, 10, 2, 300f, 200f, 400f, 200f))
        val cy = camera.centerY
        input.onTouch(e.set(TouchSample.MOVE, 20, 2, 300f, 248f, 400f, 248f)) // +48 px nach unten = 1 m
        assertEquals(cy - 1f, camera.centerY, 1e-3f)
    }

    @Test
    fun longPressWithoutMovementOpensTheContextMenu() {
        down(400f, 200f, t = 1000)
        assertTrue(input.singleFingerActive)
        assertEquals(false, input.onLongPressTimeout(1200), "too early")
        assertEquals(true, input.onLongPressTimeout(1000 + input.longPressMs))
        move(402f, 201f, 1600)
        up(402f, 201f, 1700)
        assertEquals(listOf("DOWN#1", "LONG#1", "UP#1"), sink.events)
        assertEquals(0, sink.doubleTaps)
    }

    @Test
    fun movingCancelsTheLongPress() {
        down(400f, 200f, t = 0)
        move(460f, 200f, 100)
        assertEquals(false, input.onLongPressTimeout(1000))
    }

    @Test
    fun doubleTapTogglesTheViewOnlyWhenNoToolTookTheTaps() {
        sink.consumed = false
        down(400f, 200f, 0); up(400f, 200f, 80)
        down(404f, 202f, 200); up(404f, 202f, 260)
        assertEquals(1, sink.doubleTaps)
        // Ein drittes Tippen startet keinen weiteren Doppeltipp mit dem zweiten
        down(404f, 202f, 400); up(404f, 202f, 450)
        assertEquals(1, sink.doubleTaps)

        sink.consumed = true // z. B. Waffe angetippt
        down(400f, 200f, 2000); up(400f, 200f, 2050)
        down(400f, 200f, 2150); up(400f, 200f, 2200)
        assertEquals(1, sink.doubleTaps)
    }

    @Test
    fun slowOrDistantTapsAreNoDoubleTap() {
        sink.consumed = false
        down(400f, 200f, 0); up(400f, 200f, 50)
        down(400f, 200f, 600); up(400f, 200f, 650)
        down(400f, 200f, 700); up(400f, 200f, 750)
        assertEquals(1, sink.doubleTaps, "only the last pair is close enough in time")
        down(100f, 100f, 2000); up(100f, 100f, 2050)
        down(600f, 300f, 2100); up(600f, 300f, 2150)
        assertEquals(1, sink.doubleTaps)
    }

    @Test
    fun cancelFromTheSystemCancelsTheToolGesture() {
        down(400f, 200f)
        input.onTouch(e.set(TouchSample.CANCEL, 10, 0, 400f, 200f))
        assertEquals(listOf("DOWN#1", "CANCEL"), sink.events)
    }
}
