package de.bollwerk.engine.loop

/**
 * Fixed-Step-Akkumulator: wandelt reale Frame-Zeit in eine ganze Anzahl fester Sim-Ticks um.
 * Die reale Zeit beeinflusst nur, **wie viele** Ticks laufen, nie deren Inhalt.
 *
 * @param dt fester Tick (s).
 * @param maxTicksPerAdvance Obergrenze pro Aufruf gegen die "Spirale des Todes"; Überschuss wird verworfen.
 * @param inputDelayTicks Verzögerung lokaler Eingaben in Ticks (0 offline, später ~6 für Lockstep).
 * @param canTick Darf der nächste Tick laufen? Lockstep-Quellen melden hier `false`, solange Eingaben des Gegners fehlen
 *   (`GameSession.isReady`); [advance] hält dann an und verwirft die aufgelaufene Zeit (Stillstand statt Aufholjagd).
 * @param onTick führt genau einen Sim-Tick aus.
 */
class GameLoop(
    val dt: Float = 1f / 60f,
    val maxTicksPerAdvance: Int = 5,
    val inputDelayTicks: Int = 0,
    private val canTick: () -> Boolean = ALWAYS,
    private val onTick: () -> Unit,
) {
    private var accumulator = 0f

    /** Interpolationsfaktor 0..1 zwischen vorletztem und letztem Tick (für das Rendering). */
    var alpha: Float = 0f
        private set

    /** Gesamtzahl ausgeführter Ticks. */
    var ticksRun: Long = 0L
        private set

    /** Der letzte [advance] ist stehen geblieben, weil [canTick] `false` meldete. */
    var stalled: Boolean = false
        private set

    /** Pausiert: [advance] lässt keine Ticks laufen. */
    var paused: Boolean = false

    /**
     * Schiebt die Uhr um [realDeltaSeconds] weiter und führt die fälligen Ticks aus.
     * @param maxTicks Obergrenze für diesen Aufruf (Standard [maxTicksPerAdvance]); der `MatchRunner` skaliert sie mit der
     *   Geschwindigkeit, damit ein Tempo > 1 nicht ständig Ticks verwirft.
     * @return Anzahl in diesem Aufruf ausgeführter Ticks.
     */
    fun advance(realDeltaSeconds: Float, maxTicks: Int = maxTicksPerAdvance): Int {
        // `!(x > 0)` fängt auch NaN ab (ein NaN im Akkumulator würde die Sim für immer einfrieren)
        if (paused || !(realDeltaSeconds > 0f)) {
            alpha = accumulator / dt
            return 0
        }
        // Große Sprünge (App im Hintergrund, Debugger) begrenzen
        val d = if (realDeltaSeconds > MAX_FRAME_SECONDS) MAX_FRAME_SECONDS else realDeltaSeconds
        accumulator += d
        var n = 0
        while (accumulator >= dt && n < maxTicks) {
            if (!canTick()) {
                // Stillstand (Lockstep wartet): keine Zeit ansammeln, sonst läuft die Sim danach im Zeitraffer
                accumulator = 0f
                stalled = true
                break
            }
            stalled = false
            onTick()
            accumulator -= dt
            n++
        }
        if (n == maxTicks && accumulator >= dt) accumulator = 0f
        ticksRun += n
        alpha = accumulator / dt
        return n
    }

    /** Tick, den eine jetzt erzeugte lokale Eingabe tragen muss. */
    fun commandTickFor(currentTick: Long): Long = currentTick + inputDelayTicks

    /** Setzt Akkumulator und Interpolation zurück (z. B. nach Pause/Resume). */
    fun reset() {
        accumulator = 0f
        alpha = 0f
    }

    companion object {
        /** Maximal berücksichtigte Frame-Zeit pro Aufruf (s). */
        const val MAX_FRAME_SECONDS: Float = 0.25f

        private val ALWAYS: () -> Boolean = { true }
    }
}
