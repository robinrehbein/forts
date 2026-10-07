package de.bollwerk.renderandroid

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.SurfaceHolder

/**
 * Render-Thread mit eigener Looper und [Choreographer]-Pacing: je Vsync genau ein Frame, gezeichnet mit
 * `lockHardwareCanvas`. Der nächste Callback wird **vor** dem Zeichnen angemeldet, damit ein langsamer Frame nur
 * Vsyncs auslässt (die [FrameStats] zeigen sie) und der Takt nicht aufläuft.
 *
 * Beenden: [shutdown] blockiert (begrenzt), bis kein Zeichenaufruf mehr läuft; danach darf die Oberfläche freigegeben
 * werden (Vertrag von `SurfaceHolder.Callback.surfaceDestroyed`). Hängt der Thread über die Frist hinaus, meldet es das
 * (falsch), und [runAfterExit] erlaubt, Aufräumarbeit auf den Thread-Ausgang zu legen. Der Thread hält weder View noch Activity.
 */
internal class RenderThread(
    private val holder: SurfaceHolder,
    private val session: RenderSession,
) : HandlerThread("BollwerkRender", Process.THREAD_PRIORITY_DISPLAY), Choreographer.FrameCallback, RenderLoop {

    @Volatile private var running = true
    private val exitLock = Any()
    private var ended = false
    private var exitAction: Runnable? = null
    private var choreographer: Choreographer? = null
    private var lastDrawnNanos = 0L

    override fun onLooperPrepared() {
        val c = Choreographer.getInstance()
        choreographer = c
        if (running) c.postFrameCallback(this)
    }

    override fun run() {
        try {
            super.run()
        } finally {
            val a = synchronized(exitLock) { ended = true; exitAction.also { exitAction = null } }
            a?.run()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer?.postFrameCallback(this)

        val s = session.settings
        var minInterval = if (s.maxFps > 0) 1_000_000_000L / s.maxFps else 0L
        if (session.paused && minInterval < PAUSED_INTERVAL_NANOS) minInterval = PAUSED_INTERVAL_NANOS
        if (minInterval > 0L && lastDrawnNanos != 0L && frameTimeNanos - lastDrawnNanos < minInterval - TOLERANCE_NANOS) return
        if (!holder.surface.isValid) return

        val t0 = System.nanoTime()
        val canvas = try {
            holder.lockHardwareCanvas()
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return
        try {
            session.drawFrame(canvas, frameTimeNanos)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        lastDrawnNanos = frameTimeNanos
        session.recordFrame(frameTimeNanos, System.nanoTime() - t0, minInterval)
    }

    /**
     * Stoppt den Thread und wartet höchstens [JOIN_TIMEOUT_MS] auf sein Ende (UI-Thread). Falsch = er hängt noch in
     * `drawFrame`/`unlockCanvasAndPost`; der Aufrufer darf dann nichts freigeben, was der Thread benutzt
     * (siehe [RenderLifecycle]).
     */
    override fun shutdown(): Boolean {
        running = false
        val l = looper
        if (l != null) Handler(l).post {
            choreographer?.removeFrameCallback(this)
            quit()
        }
        try {
            join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !isAlive
    }

    override fun runAfterExit(action: Runnable) {
        val now = synchronized(exitLock) {
            if (ended) true else { exitAction = action; false }
        }
        if (now) action.run()
    }

    private companion object {
        /** Pausiertes Spiel: 30 fps reichen (Kamera/Overlay bleiben bedienbar). */
        const val PAUSED_INTERVAL_NANOS = 33_333_333L
        /** Vsync-Jitter, damit z. B. 60 Hz bei maxFps = 60 nicht jeden zweiten Frame ausfallen. */
        const val TOLERANCE_NANOS = 3_000_000L
        const val JOIN_TIMEOUT_MS = 2000L
    }
}
