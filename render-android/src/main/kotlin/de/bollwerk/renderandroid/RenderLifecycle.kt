package de.bollwerk.renderandroid

/** Ein laufender Render-Takt (der [RenderThread]); Naht für JVM-Tests von [RenderLifecycle]. */
internal interface RenderLoop {
    /** Stoppt und wartet begrenzt. Wahr = der Thread ist beendet, falsch = hängt noch (z. B. in einem Treiberaufruf). */
    fun shutdown(): Boolean

    /** Nicht blockierend: wahr, sobald der Thread zu Ende ist (nach einem gescheiterten [shutdown] nur noch abfragen). */
    fun isFinished(): Boolean

    /** Führt [action] auf dem Loop-Thread aus, sobald er zu Ende ist; ist er es schon, sofort auf dem aufrufenden Thread. */
    fun runAfterExit(action: Runnable)
}

/**
 * Entscheidet, wann der Render-Thread läuft (reine Logik, vom UI-Thread benutzt): nur wenn eine Partie angehängt ist, die
 * Oberfläche existiert und weder der Host ([hostPaused], `onHostPause`) noch der Lifecycle-Beobachter der View
 * ([lifecyclePaused], ON_PAUSE; fängt Multi-Window und durchscheinende Dialoge ab, bei denen die Oberfläche bleibt)
 * pausiert ist.
 *
 * **Hängender Thread:** Gelingt [RenderLoop.shutdown] nicht in der Frist, wird er als `lingering` gemerkt statt vergessen.
 * Er wird danach nur noch nicht blockierend abgefragt ([RenderLoop.isFinished]), nie erneut gejoint. Solange er lebt,
 * startet kein zweiter Thread auf derselben Session ([update]), und Aufräumen, das Bitmaps recycelt, läuft über
 * [runWhenStopped] erst, wenn er wirklich zu Ende ist (sonst Use-after-recycle in `drawFrame`).
 */
internal class RenderLifecycle(
    private val factory: () -> RenderLoop,
    private val onTimeout: () -> Unit = {},
) {
    var hasSession = false
    var surfaceReady = false
    var hostPaused = false
    var lifecyclePaused = false

    private var loop: RenderLoop? = null
    private var lingering: RenderLoop? = null

    val isRunning: Boolean get() = loop != null
    val hasLingeringThread: Boolean get() = lingering != null
    val shouldRun: Boolean get() = hasSession && surfaceReady && !hostPaused && !lifecyclePaused

    /** Gleicht den Thread mit dem gewünschten Zustand ab. */
    fun update() {
        if (shouldRun) {
            if (loop == null && retireLingering()) loop = factory()
        } else {
            stop()
        }
    }

    /** Beendet den Thread blockierend. Wahr = danach läuft garantiert keiner mehr. */
    fun stop(): Boolean {
        val l = loop
        loop = null
        if (l != null && !l.shutdown()) {
            lingering = l
            onTimeout()
            return false
        }
        return retireLingering()
    }

    /**
     * Führt [action] aus, sobald kein Render-Thread mehr zeichnen kann: sofort, wenn keiner hängt, sonst auf dem hängenden
     * Thread nach seinem Ende (z. B. `session.release()`).
     */
    fun runWhenStopped(action: Runnable) {
        val z = lingering
        if (z == null) action.run() else z.runAfterExit(Runnable { action.run() })
    }

    /**
     * Ein hängender Thread wird **nicht** erneut gejoint (das würde den UI-Thread bei jedem Lebenszyklus-Ereignis bis zur
     * Frist blockieren und könnte sich zum ANR aufsummieren); er hat sein Ende schon angemeldet, Aufräumen läuft über
     * [RenderLoop.runAfterExit]. Hier wird nur abgefragt, ob er inzwischen fertig ist.
     */
    private fun retireLingering(): Boolean {
        val z = lingering ?: return true
        if (z.isFinished()) { lingering = null; return true }
        return false
    }
}
