package de.bollwerk.renderandroid

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.scene.SyntheticScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RenderThread] ohne Looper: `onLooperPrepared`/`doFrame` direkt aufgerufen (wie es der Looper bzw. der Choreographer
 * auf dem Render-Thread täte), gegen eine gültige Attrappen-Oberfläche.
 */
class RenderThreadTest {
    private class FixedSource(val sc: SyntheticScene) : SnapshotSource {
        override val tables: SimTables = sc.tables
        override val map: MapSpec = sc.map
        override fun latest(): FrameSnapshot = sc.snap
    }

    /** Surface der android.jar-Attrappe, die sich als gültig meldet. */
    private class ValidSurface : Surface(SurfaceTexture(0)) {
        override fun isValid(): Boolean = true
    }

    private class FakeHolder : SurfaceHolder {
        val canvas = RecordingCanvas()
        private val surface = ValidSurface()
        var posted = 0
        override fun getSurface(): Surface = surface
        override fun lockHardwareCanvas(): Canvas = canvas.also { it.clear() }
        override fun unlockCanvasAndPost(canvas: Canvas) { posted++ }
        override fun lockCanvas(): Canvas = canvas
        override fun lockCanvas(dirty: Rect?): Canvas = canvas
        override fun addCallback(callback: SurfaceHolder.Callback?) = Unit
        override fun removeCallback(callback: SurfaceHolder.Callback?) = Unit
        override fun isCreating(): Boolean = false
        @Deprecated("deprecated in SurfaceHolder") override fun setType(type: Int) = Unit
        override fun setFixedSize(width: Int, height: Int) = Unit
        override fun setSizeFromLayout() = Unit
        override fun setFormat(format: Int) = Unit
        override fun setKeepScreenOn(screenOn: Boolean) = Unit
        override fun getSurfaceFrame(): Rect = Rect()
    }

    private val sc = SyntheticScene.narrow().both()
    private val holder = FakeHolder()
    private val session = RenderSession(
        FixedSource(sc), OverlaySource.NONE, Camera(1920f, 1080f, 1f).also { it.fitRect(0f, 20f, 92f, 45f) },
        RenderSettings(), 1f, FakeGraphics(),
    )

    @Test
    fun restartedThreadDoesNotRecordAnIntervalAcrossThePause() {
        val first = RenderThread(holder, session)
        first.onLoopStart()
        var t = 1_000_000_000L
        first.doFrame(t)
        assertEquals(0L, session.stats.frames, "erster Frame ohne Vorgänger")
        repeat(3) { t += 16_666_667L; first.doFrame(t) }
        assertEquals(3L, session.stats.frames)
        assertEquals(0L, session.stats.droppedFrames)
        assertTrue(first.shutdown())
        assertTrue(first.isFinished())

        // Fünf Minuten im Hintergrund, dann ein neuer Thread auf derselben Session
        t += 300_000_000_000L
        val second = RenderThread(holder, session)
        second.onLoopStart()
        second.doFrame(t)
        assertEquals(5, holder.posted, "Frame wurde gezeichnet")
        assertEquals(3L, session.stats.frames, "kein Abstand über die Pause verbucht")
        assertEquals(0L, session.stats.droppedFrames, "Pause zählt nicht als ausgelassener Frame")
        t += 16_666_667L
        second.doFrame(t)
        assertEquals(4L, session.stats.frames)
        assertEquals(0L, session.stats.droppedFrames)
        session.stats.refresh()
        assertTrue(session.stats.p95Ms < 20f, "p95 ohne Ausreißer über die Pause: ${session.stats.p95Ms}")
    }
}
