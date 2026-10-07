package de.bollwerk.renderandroid

/** Entscheidung der Ebenen-Zwischenspeicherung für einen Frame. */
internal enum class LayerDecision {
    /** Ebene ist gültig: gespeichertes Bitmap aufblenden, der Renderer zeichnet nichts. */
    BLIT,
    /** Ebene in ein Offscreen-Bitmap aufzeichnen und danach aufblenden. */
    RECORD,
    /** Schlüssel ändert sich gerade (Kamera in Bewegung): direkt zeichnen, nichts speichern. */
    DIRECT,
}

/**
 * Zustandsautomat einer statischen Ebene (reine Logik; die Bitmaps hält [CanvasDrawSink]).
 *
 * - Erste Anfrage nach [invalidate] (neue Partie/`bind`) → [LayerDecision.RECORD] sofort.
 * - Gültig und gleicher Schlüssel/gleiche Größe → [LayerDecision.BLIT].
 * - Sonst wird gezählt, wie viele **aufeinanderfolgende** Anfragen denselben Schlüssel hatten. Erst ab
 *   [stableFramesToRecord] gleichen Anfragen (Standard 6, ca. 100 ms) wird aufgezeichnet; bis dahin gilt
 *   [LayerDecision.DIRECT]. Grund: Touch-Ereignisse und Vsync sind nicht phasengekoppelt, bei langsamem Pan/Zoom gibt es
 *   dauernd Einzelframes ohne Änderung, und jede Aufzeichnung rastert zwei Vollbilder auf der CPU (plus Upload) –
 *   genau dann, wenn der Spieler die Kamera bedient. Eine Pause von ~100 ms ist eine echte Ruhe.
 */
internal class LayerState(private val stableFramesToRecord: Int = DEFAULT_STABLE_FRAMES) {
    private var key = 0L
    private var width = 0
    private var height = 0
    var valid = false
        private set
    private var seen = false
    private var seenKey = 0L
    private var seenWidth = 0
    private var seenHeight = 0
    /** Anzahl aufeinanderfolgender Anfragen mit demselben Schlüssel (inklusive der aktuellen). */
    private var stable = 0

    init { require(stableFramesToRecord >= 1) }

    fun decide(key: Long, width: Int, height: Int, hasBitmap: Boolean): LayerDecision {
        val fresh = !seen
        val same = seen && seenKey == key && seenWidth == width && seenHeight == height
        seen = true
        seenKey = key; seenWidth = width; seenHeight = height
        stable = if (same) (if (stable < Int.MAX_VALUE) stable + 1 else stable) else 1
        if (valid && hasBitmap && this.key == key && this.width == width && this.height == height) return LayerDecision.BLIT
        valid = false
        return if (fresh || stable >= stableFramesToRecord) LayerDecision.RECORD else LayerDecision.DIRECT
    }

    /** Aufzeichnung beginnt (die Ebene gilt erst mit [completed]). */
    fun recording(key: Long, width: Int, height: Int) {
        this.key = key; this.width = width; this.height = height
        valid = false
    }

    fun completed() { valid = true }

    /** Bitmap wurde verworfen (Speicherdruck) oder ist ungültig. */
    fun dropped() { valid = false }

    /** Karte/Partie wechselt: nächste Anfrage zeichnet sofort auf. */
    fun invalidate() { valid = false; seen = false; stable = 0 }

    companion object {
        const val DEFAULT_STABLE_FRAMES = 6
    }
}
