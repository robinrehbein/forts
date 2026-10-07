package de.bollwerk.renderandroid

import android.graphics.Canvas
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.Palette
import de.bollwerk.renderapi.PooledParticleSystem
import de.bollwerk.renderapi.scene.SceneConfig
import de.bollwerk.renderapi.scene.SceneRenderer

/**
 * Alles, was zu einer angehängten Partie gehört und Pausen/Neuanlegen der Oberfläche übersteht: Sink (Texturen,
 * Ebenen), [SceneRenderer], Partikel, Statistik. Es wird immer nur von **einem** Thread benutzt (dem Render-Thread,
 * oder – bei gestopptem Thread – dem UI-Thread); der Wechsel läuft über `Thread.start`/`join` (happens-before).
 * Einzige Ausnahmen sind die `@Volatile`-Felder [settings], [paused], [density], [vsyncPeriodMs], `sink.trimMemory` und die
 * unter `synchronized(camera)` gelesene geteilte Kamera.
 */
internal class RenderSession(
    val source: SnapshotSource,
    val overlaySource: OverlaySource,
    /** Die geteilte Kamera der App (UI-Thread, Änderungen unter `synchronized(camera)`, siehe [edit]). */
    val camera: Camera,
    settings: RenderSettings,
    density: Float,
    graphics: GraphicsFactory = AndroidGraphics,
) {
    @Volatile var settings: RenderSettings = settings
    @Volatile var paused: Boolean = false
    @Volatile var density: Float = density

    /** Vsync-Periode des Displays in ms (für die Zahl ausgelassener Frames); die View setzt sie aus `Display.refreshRate`. */
    @Volatile var vsyncPeriodMs: Float = 1000f / 60f

    val sink = CanvasDrawSink(settings.typefaces, settings.maxIdleTextureBytes, graphics)
    val target = CanvasRenderTarget(sink)
    val stats = FrameStats()

    private val timing = SnapshotTiming()
    private val debug = DebugOverlay()
    private val settle = PauseSettle()
    /** Private Kopie der geteilten Kamera: der Renderer liest sie mehrfach je Frame und braucht dabei einen festen Stand. */
    private val frameCamera = Camera(dpPerMeter = camera.dpPerMeter)
    private var renderer: SceneRenderer? = null
    private var particles: FreezableParticles? = null
    private var builtSettings: RenderSettings? = null
    private var lastVsyncNanos = 0L
    /** Zuletzt gezeichnete Snapshot-Nummer; ein neu aufgebauter Renderer spielt deren Fx nicht noch einmal ab. */
    private var lastDrawnSeq = Long.MIN_VALUE

    /** Shake-Offset des letzten Frames in px (Tests, Diagnose). */
    internal val shakeXpx: Float get() = renderer?.shakeXpx ?: 0f
    internal val shakeYpx: Float get() = renderer?.shakeYpx ?: 0f
    internal val particleCount: Int get() = particles?.count ?: 0

    /** Neu gestarteter Render-Thread: kein Zeitsprung über die Pause hinweg. */
    fun onThreadStart() {
        timing.reset()
        settle.reset()
        lastVsyncNanos = 0L
    }

    /** Zeichnet einen Frame in [canvas]. Aufruf zwischen `lockHardwareCanvas` und `unlockCanvasAndPost`. */
    fun drawFrame(canvas: Canvas, frameTimeNanos: Long) {
        val s = settings
        if (sink.typefaces !== s.typefaces) sink.typefaces = s.typefaces
        sink.maxIdleTextureBytes = s.maxIdleTextureBytes
        val d = density
        target.update(canvas.width, canvas.height, d)
        val r = ensureRenderer(s)
        val snap = source.latest()
        sink.begin(canvas)
        try {
            if (snap == null) {
                canvas.drawColor(Palette.SKY_1)
            } else {
                timing.advance(frameTimeNanos, snap.seq, source.alphaHint())
                val fx = particles!!
                r.frameDtSeconds = settle.dt(paused, timing.dtSeconds, snap.seq, r.shakeXpx != 0f || r.shakeYpx != 0f)
                fx.frozen = settle.frozen
                CameraSync.copy(camera, frameCamera)
                r.render(target, snap, timing.alpha, frameCamera, overlaySource.current(), fx)
                lastDrawnSeq = snap.seq
            }
            if (s.debugOverlay) {
                debug.draw(
                    sink, stats, frameTimeNanos, d, s.hudTopInsetDp * d,
                    particles?.count ?: 0, sink.textureBytes, sink.layerBytes,
                )
            }
        } finally {
            sink.end()
        }
    }

    /**
     * Verbucht einen gezeichneten Frame ([workNanos] = Zeichnen + Absenden, [minIntervalNanos] = Drosselung des Threads:
     * Pause oder `maxFps`, 0 = keine). Der erste Frame nach einem Thread-Start hat keinen Vorgänger und wird nicht
     * gezählt (ein Abstand 0 würde p50/fps verfälschen). Erwartet wird `max(Vsync-Periode, Drosselung)`, damit 30 fps
     * in der Pause nicht als ausgelassene Frames zählen.
     */
    fun recordFrame(frameTimeNanos: Long, workNanos: Long, minIntervalNanos: Long = 0L) {
        val prev = lastVsyncNanos
        lastVsyncNanos = frameTimeNanos
        if (prev == 0L) return
        val interval = (frameTimeNanos - prev) * 1e-6f
        val throttleMs = minIntervalNanos * 1e-6f
        val v = vsyncPeriodMs
        stats.record(interval, workNanos * 1e-6f, source.lastSimMillis(), if (throttleMs > v) throttleMs else v)
    }

    private fun ensureRenderer(s: RenderSettings): SceneRenderer {
        val cur = renderer
        val b = builtSettings
        if (cur != null && b != null && b.reducedMotion == s.reducedMotion && b.texts === s.texts &&
            b.hudTopInsetDp == s.hudTopInsetDp && b.seed == s.seed
        ) return cur
        cur?.release()
        val nr = SceneRenderer(SceneConfig(reducedMotion = s.reducedMotion, texts = s.texts, hudTopInsetDp = s.hudTopInsetDp, seed = s.seed))
        nr.bind(source.tables, source.map, target)
        // Der aktuelle Snapshot wurde schon gezeichnet (Einstellungswechsel, Wiedereinstieg nach Speicherdruck):
        // seine Fx (Explosion, Shake, Blitz, Decals) nicht noch einmal abspielen
        if (lastDrawnSeq != Long.MIN_VALUE) nr.markSeqProcessed(lastDrawnSeq)
        renderer = nr
        particles = FreezableParticles(PooledParticleSystem(reducedMotion = s.reducedMotion, seed = s.seed * 31 + 7))
        builtSettings = s
        timing.reset()
        settle.reset()
        return nr
    }

    /**
     * Gibt Texturen und Partikel frei, behält die Quellen (Speicherdruck im Hintergrund); beim nächsten Frame neu gebunden.
     * Der Renderer schiebt seine Texturen dabei in den Leerlauf-Cache der Senke; die werden danach **unbedingt** verworfen
     * (nicht über eine gemerkte Trim-Stufe, die zu diesem Zeitpunkt längst verbraucht sein kann).
     */
    fun unbind() {
        renderer?.release()
        renderer = null
        particles = null
        builtSettings = null
        sink.applyTrim(TrimAction.EVERYTHING)
    }

    /**
     * Speicherdruck ([level] = `ComponentCallbacks2`-Stufe). Läuft der Render-Thread ([renderThreadRunning]), wird die
     * Stufe nur gemerkt und am Anfang des nächsten Frames angewendet; sonst sofort, ab Hintergrund-Stufe samt Renderer.
     */
    fun trim(level: Int, renderThreadRunning: Boolean) {
        val action = sink.trimMemory(level)
        if (renderThreadRunning) return
        if (action == TrimAction.EVERYTHING) unbind()
        sink.applyPendingTrim() // verbraucht die gemerkte Stufe (sonst träfe sie den ersten Frame nach dem Wiedereinstieg)
    }

    /** Endgültig abbauen. */
    fun release() {
        unbind()
        sink.release()
    }
}
