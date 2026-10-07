package de.bollwerk.renderandroid

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import de.bollwerk.renderapi.Camera

/**
 * Spielfläche: eigener Render-Thread ([RenderThread], Choreographer-Takt) zeichnet per `lockHardwareCanvas()` den
 * neuesten Snapshot der Sim ([SnapshotSource], Dreifachpuffer der Engine) mit Interpolation ([SnapshotTiming]).
 * Die Steuerung durch die App läuft über [GameViewHost].
 *
 * **Lebenszyklus** ([RenderLifecycle]): Der Thread läuft genau dann, wenn eine Partie angehängt ist, die Oberfläche existiert
 * und weder der Host ([onHostPause]) noch der Lifecycle des Fensters (ON_PAUSE des `LifecycleOwner` im View-Baum, fängt
 * Multi-Window und durchscheinende Dialoge ab, bei denen die Oberfläche bleibt) pausiert ist. `surfaceDestroyed` und
 * Pausen beenden ihn *blockierend* (die Oberfläche darf danach freigegeben werden); die Session mit Texturen/Ebenen
 * überlebt das und wird beim Fortsetzen weiterverwendet. [detach] baut sie ab, bei einem hängenden Thread erst nach dessen
 * Ende. Speicherdruck (`onTrimMemory`) meldet sich die View über `ComponentCallbacks2` selbst.
 *
 * **Oberflächenformat:** `RGBA_8888`. Eine SurfaceView fordert sonst `RGB_565`: der Abendhimmel, Dunst/Nebel und die
 * additiven Scheine würden sichtbar Streifen bilden.
 *
 * Alle Methoden gehören dem UI-Thread.
 */
class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback, GameViewHost, ComponentCallbacks2 {

    private var session: RenderSession? = null
    private var paused = false
    private var settings = RenderSettings()
    private var registeredCallbacks = false
    private val emptyStats = FrameStats()
    private var lifecycleOwner: LifecycleOwner? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_PAUSE -> { lifecycleState.lifecyclePaused = true; lifecycleState.update() }
            Lifecycle.Event.ON_RESUME -> { lifecycleState.lifecyclePaused = false; lifecycleState.update() }
            else -> Unit
        }
    }
    private val lifecycleState = RenderLifecycle(
        factory = { RenderThread(holder, checkNotNull(session)).also { it.start() } },
        onTimeout = { Log.w(TAG, "render thread did not stop in time; waiting for it before releasing the session") },
    )

    override var viewportListener: ViewportListener? = null

    init {
        holder.setFormat(SURFACE_FORMAT)
        holder.addCallback(this)
        isFocusable = true
    }

    // ---------------------------------------------------------------------------------------------
    // GameViewHost
    // ---------------------------------------------------------------------------------------------

    override fun attach(snapshotSource: SnapshotSource, overlaySource: OverlaySource, camera: Camera, settings: RenderSettings?) {
        detach()
        if (settings != null) this.settings = settings
        val s = RenderSession(snapshotSource, overlaySource, camera, this.settings, currentDensity())
        s.paused = paused
        s.vsyncPeriodMs = vsyncPeriodMs()
        session = s
        lifecycleState.hasSession = true
        if (width > 0 && height > 0) synchronized(camera) { camera.setViewport(width.toFloat(), height.toFloat(), s.density) }
        updateThread()
    }

    override fun detach() {
        val s = session
        session = null
        lifecycleState.hasSession = false
        lifecycleState.stop()
        // Hängt der Render-Thread noch im Treiber, würde release() Bitmaps unter seinen Händen recyceln: erst nach seinem Ende
        if (s != null) lifecycleState.runWhenStopped { s.release() }
    }

    override fun setReducedMotion(reduced: Boolean) = updateSettings(settings.copy(reducedMotion = reduced))

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        session?.paused = paused
    }

    override fun setDebugOverlay(enabled: Boolean) = updateSettings(settings.copy(debugOverlay = enabled))

    override fun updateSettings(settings: RenderSettings) {
        this.settings = settings
        session?.settings = settings
    }

    override fun onHostPause() {
        lifecycleState.hostPaused = true
        updateThread()
    }

    override fun onHostResume() {
        lifecycleState.hostPaused = false
        updateThread()
    }

    override fun trimMemory(level: Int) {
        val s = session ?: return
        // Ohne laufenden Thread sofort freigeben (im Hintergrund samt Renderer/Texturen), sonst zu Beginn des nächsten Frames.
        // Hängt ein alter Thread noch, zählt der Zeichner als aktiv.
        s.trim(level, lifecycleState.isRunning || lifecycleState.hasLingeringThread)
    }

    override val frameStats: FrameStats get() = session?.stats ?: emptyStats

    // ---------------------------------------------------------------------------------------------
    // Thread
    // ---------------------------------------------------------------------------------------------

    private fun updateThread() = lifecycleState.update()

    private fun stopThread() { lifecycleState.stop() }

    private fun vsyncPeriodMs(): Float {
        val rate = display?.refreshRate ?: 0f
        return if (rate >= 20f) 1000f / rate else 1000f / 60f
    }

    private fun currentDensity(): Float = resources.displayMetrics.density

    // ---------------------------------------------------------------------------------------------
    // SurfaceHolder.Callback
    // ---------------------------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        lifecycleState.surfaceReady = true
        updateThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val d = currentDensity()
        val s = session
        if (s != null) {
            s.density = d
            s.vsyncPeriodMs = vsyncPeriodMs()
            synchronized(s.camera) { s.camera.setViewport(width.toFloat(), height.toFloat(), d) }
        }
        viewportListener?.onViewportChanged(width, height, d)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        lifecycleState.surfaceReady = false
        stopThread()
    }

    // ---------------------------------------------------------------------------------------------
    // View / ComponentCallbacks2
    // ---------------------------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!registeredCallbacks) {
            context.applicationContext.registerComponentCallbacks(this)
            registeredCallbacks = true
        }
        val owner = findViewTreeLifecycleOwner()
        if (owner != null && owner !== lifecycleOwner) {
            lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
            lifecycleOwner = owner
            owner.lifecycle.addObserver(lifecycleObserver)
        }
    }

    override fun onDetachedFromWindow() {
        if (registeredCallbacks) {
            context.applicationContext.unregisterComponentCallbacks(this)
            registeredCallbacks = false
        }
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = null
        lifecycleState.lifecyclePaused = false
        stopThread()
        super.onDetachedFromWindow()
    }

    override fun onTrimMemory(level: Int) = trimMemory(level)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() = trimMemory(TrimPolicy.RUNNING_CRITICAL)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        session?.density = currentDensity()
    }

    companion object {
        private const val TAG = "GameSurfaceView"

        /** 32 Bit statt des SurfaceView-Standards RGB_565 (Streifen in Verläufen und Scheinen). */
        const val SURFACE_FORMAT: Int = PixelFormat.RGBA_8888
    }
}
