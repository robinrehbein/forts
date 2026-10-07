package de.bollwerk.renderandroid

import java.util.Arrays

/**
 * Frame-Statistik über die letzten [window] Frames: Frame-Abstand (Vsync-zu-Vsync, zeigt ausgelassene Frames),
 * reine Zeichenzeit des Render-Threads und optional die Sim-Zeit je Tick.
 *
 * Schreiber ist der Render-Thread ([record], allokationsfrei: Ringpuffer + Arbeitsarray). Die Kennzahlen werden alle
 * [refreshEvery] Frames neu berechnet und stehen als `@Volatile`-Felder jedem Thread (Debug-Overlay, UI) zur Verfügung.
 */
class FrameStats(val window: Int = 240, private val refreshEvery: Int = 30, val budgetMs: Float = 1000f / 60f) {
    private val interval = FloatArray(window)
    private val work = FloatArray(window)
    private val sim = FloatArray(window)
    private val scratch = FloatArray(window)
    private var head = 0
    private var filled = 0
    private var sinceRefresh = 0

    /** Gesamtzahl aufgezeichneter Frames seit [reset]. */
    @Volatile var frames: Long = 0; private set
    /** Frames, deren Abstand das 1,5-fache des **erwarteten** Abstands überschritt (ausgelassene Vsyncs). */
    @Volatile var droppedFrames: Long = 0; private set

    @Volatile var p50Ms: Float = 0f; private set
    @Volatile var p95Ms: Float = 0f; private set
    @Volatile var workP50Ms: Float = 0f; private set
    @Volatile var workP95Ms: Float = 0f; private set
    /** Mittlere Sim-Zeit je Tick im Fenster; NaN, solange die Quelle keine liefert. */
    @Volatile var simMeanMs: Float = Float.NaN; private set
    @Volatile var fps: Float = 0f; private set

    /**
     * Zeichnet einen Frame auf. [frameIntervalMs] = Abstand zum vorigen gezeichneten Frame (der erste Frame nach einem
     * Thread-Start wird gar nicht aufgezeichnet, siehe `RenderSession.recordFrame`), [workMs] = Dauer von Zeichnen und
     * Absenden, [simMs] = Sim-Zeit des letzten Ticks oder NaN. [expectedIntervalMs] = der Abstand, den dieser Frame
     * haben soll: `max(Vsync-Periode, Drosselung)`. Pausiert das Spiel (30 fps) oder begrenzt `maxFps`, ist ein 33-ms-
     * Abstand Soll und kein ausgelassener Frame; Standard ist das Budget des Displays.
     */
    fun record(frameIntervalMs: Float, workMs: Float, simMs: Float = Float.NaN, expectedIntervalMs: Float = budgetMs) {
        interval[head] = frameIntervalMs
        work[head] = workMs
        val hasSim = !simMs.isNaN()
        sim[head] = if (hasSim) simMs else -1f
        head = if (head + 1 == window) 0 else head + 1
        if (filled < window) filled++
        frames++
        if (frameIntervalMs > expectedIntervalMs * 1.5f) droppedFrames++
        if (++sinceRefresh >= refreshEvery) refresh()
    }

    /** Berechnet die Kennzahlen sofort (Tests, Debug-Anzeige beim Einschalten). */
    fun refresh() {
        sinceRefresh = 0
        val n = filled
        if (n == 0) return
        p50Ms = percentile(interval, n, 0.50f)
        p95Ms = percentile(interval, n, 0.95f)
        workP50Ms = percentile(work, n, 0.50f)
        workP95Ms = percentile(work, n, 0.95f)
        var sum = 0f
        var k = 0
        for (i in 0 until n) if (sim[i] >= 0f) { sum += sim[i]; k++ }
        simMeanMs = if (k > 0) sum / k else Float.NaN
        fps = if (p50Ms > 0f) 1000f / p50Ms else 0f
    }

    fun reset() {
        head = 0; filled = 0; sinceRefresh = 0
        frames = 0; droppedFrames = 0
        p50Ms = 0f; p95Ms = 0f; workP50Ms = 0f; workP95Ms = 0f; simMeanMs = Float.NaN; fps = 0f
    }

    /** Perzentil [q] (0..1) der ersten [n] Werte von [src] (Nächster-Rang, ohne Interpolation). */
    private fun percentile(src: FloatArray, n: Int, q: Float): Float {
        System.arraycopy(src, 0, scratch, 0, n)
        Arrays.sort(scratch, 0, n)
        // Nächster Rang: ceil(q·n) − 1
        val rank = Math.ceil((q * n).toDouble()).toInt() - 1
        val idx = if (rank < 0) 0 else if (rank >= n) n - 1 else rank
        return scratch[idx]
    }
}
