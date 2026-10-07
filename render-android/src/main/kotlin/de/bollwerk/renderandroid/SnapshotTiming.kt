package de.bollwerk.renderandroid

/**
 * Zeitliche Abbildung Render-Frame ↔ Sim-Tick (reine Logik, allokationsfrei).
 *
 * Jeder [de.bollwerk.engine.view.FrameSnapshot] enthält beide Endpunkte des letzten Ticks (`*Prev*`, aktuell).
 * Der Render-Thread zeichnet `lerp(prev, cur, alpha)`, wobei alpha der Anteil der seit Eintreffen des Snapshots
 * vergangenen Zeit an einem Tick-Abstand ist. Der Abstand wird aus den beobachteten Ankunftszeiten geglättet
 * (Ruckeln des Sim-Threads wird so nicht in die Bewegung übernommen). Liefert die Quelle selbst einen Faktor
 * (z. B. `GameLoop.alpha`), hat dieser Vorrang.
 *
 * Ein Snapshot wird mit dem ersten Frame „gesehen“, der ihn vorfindet; alpha beginnt dort bei 0. Der Versatz
 * (im Mittel ein halber Vsync) ist die übliche Latenz der Interpolation.
 */
class SnapshotTiming(private val tickSeconds: Float = 1f / 60f) {
    private var lastSeq = Long.MIN_VALUE
    private var arrivalNanos = 0L
    private var intervalSeconds = tickSeconds

    /** Interpolationsfaktor des zuletzt berechneten Frames (0..1). */
    var alpha: Float = 0f; private set

    /** Reale Frame-Dauer in Sekunden (begrenzt auf [MAX_DT]); 0 direkt nach [reset] (kein Sprung nach Pause). */
    var dtSeconds: Float = 0f; private set

    private var lastFrameNanos = 0L
    private var haveFrame = false

    /** Geglätteter Abstand zweier Snapshots in Sekunden (Diagnose, Tests). */
    val snapshotIntervalSeconds: Float get() = intervalSeconds

    /**
     * Rechnet einen Frame: [frameNanos] = Choreographer-Frame-Zeit, [seq] = `FrameSnapshot.seq`,
     * [hint] = Faktor der Sim oder NaN.
     */
    fun advance(frameNanos: Long, seq: Long, hint: Float) {
        if (haveFrame) {
            val d = (frameNanos - lastFrameNanos) * 1e-9f
            dtSeconds = if (d < 0f) 0f else if (d > MAX_DT) MAX_DT else d
        } else {
            dtSeconds = 0f
        }
        lastFrameNanos = frameNanos
        haveFrame = true

        if (seq != lastSeq) {
            if (lastSeq != Long.MIN_VALUE && seq > lastSeq) {
                // Abstand pro Tick: überspringt die Folge Snapshots, enthält der Abstand mehrere Veröffentlichungen
                val observed = (frameNanos - arrivalNanos) * 1e-9f / (seq - lastSeq).coerceAtMost(4L).toFloat()
                if (observed > tickSeconds * 0.25f && observed < tickSeconds * 4f) {
                    val blended = intervalSeconds + (observed - intervalSeconds) * SMOOTHING
                    intervalSeconds = blended.coerceIn(tickSeconds * 0.5f, tickSeconds * 2f)
                }
            }
            lastSeq = seq
            arrivalNanos = frameNanos
        }

        alpha = if (!hint.isNaN()) {
            if (hint < 0f) 0f else if (hint > 1f) 1f else hint
        } else {
            val a = (frameNanos - arrivalNanos) * 1e-9f / intervalSeconds
            if (a < 0f) 0f else if (a > 1f) 1f else a
        }
    }

    /** Nach Pause/Resume oder neuer Quelle: nächster Frame beginnt ohne Zeitsprung. */
    fun reset() {
        lastSeq = Long.MIN_VALUE
        haveFrame = false
        dtSeconds = 0f
        alpha = 0f
        intervalSeconds = tickSeconds
    }

    companion object {
        const val MAX_DT: Float = 0.1f
        private const val SMOOTHING = 0.1f
    }
}
