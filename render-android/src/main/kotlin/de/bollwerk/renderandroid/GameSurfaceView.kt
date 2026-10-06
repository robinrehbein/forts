package de.bollwerk.renderandroid

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import de.bollwerk.renderapi.Palette

/**
 * Spielfläche: eigener Render-Thread zeichnet per `lockHardwareCanvas()` (GPU-beschleunigtes Canvas).
 *
 * WP0-Stub: löscht nur auf die Himmelsfarbe. Der echte `CanvasRenderer` (WP7) wird über
 * [frameCallback] eingehängt; Sim-Thread, Snapshots und Interpolation folgen in WP5/WP9.
 */
class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** Zeichnet einen Frame; [frameTimeNanos] = monotone Zeit für Interpolation/Partikel. */
    fun interface FrameCallback {
        fun drawFrame(canvas: Canvas, frameTimeNanos: Long)
    }

    /** Optionaler Zeichner; ohne ihn wird nur der Himmel gelöscht. */
    @Volatile
    var frameCallback: FrameCallback? = null

    private var renderThread: RenderThread? = null

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderThread = RenderThread(holder).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderThread?.shutdown()
        renderThread = null
    }

    private inner class RenderThread(private val surfaceHolder: SurfaceHolder) : Thread("BollwerkRender") {
        @Volatile
        private var running = true

        fun shutdown() {
            running = false
            interrupt()
            try {
                join(500)
            } catch (_: InterruptedException) {
                currentThread().interrupt()
            }
        }

        override fun run() {
            val frameNanos = 16_666_667L
            while (running) {
                val start = System.nanoTime()
                val surface = surfaceHolder.surface
                if (surface != null && surface.isValid) {
                    val canvas = try {
                        surfaceHolder.lockHardwareCanvas()
                    } catch (_: IllegalStateException) {
                        null
                    }
                    if (canvas != null) {
                        try {
                            canvas.drawColor(SKY_COLOR)
                            frameCallback?.drawFrame(canvas, start)
                        } finally {
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        }
                    }
                }
                // Einfaches Pacing für den Stub; WP9 stellt auf Choreographer um.
                val sleepMs = (frameNanos - (System.nanoTime() - start)) / 1_000_000L
                if (sleepMs > 0) {
                    try {
                        sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }
    }

    private companion object {
        /** Himmel-Grundton am Horizont-Übergang (Stil-Bibel §2). */
        val SKY_COLOR: Int = Palette.SKY_1
    }
}
