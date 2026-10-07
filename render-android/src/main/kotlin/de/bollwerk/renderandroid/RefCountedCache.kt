package de.bollwerk.renderandroid

/**
 * Zählender Cache mit LRU für ungenutzte Einträge. Reine Logik (kein Android), damit sie per JVM-Test prüfbar ist.
 *
 * Ein Eintrag ist **in Benutzung**, solange [acquire]/[insert] öfter als [release] aufgerufen wurden. Danach
 * wandert er in die LRU der ungenutzten Einträge (bleibt für den nächsten Partiestart erhalten, z. B. gleiche
 * Texturen bei gleicher Pixeldichte) und wird verworfen, wenn die ungenutzten Bytes [maxIdleBytes] übersteigen
 * oder [trimIdle] aufgerufen wird. Benutzte Einträge werden nie verworfen (sie liegen in Zeichenaufrufen).
 *
 * Nicht thread-sicher: gehört dem Render-Thread (bzw. dem Thread, der gerade die Session hält).
 */
internal class RefCountedCache<T : Any>(
    var maxIdleBytes: Long,
    private val dispose: (T) -> Unit,
) {
    private class Entry<T>(val key: String, val value: T, val bytes: Long) {
        var refs = 1
    }

    private val inUse = HashMap<String, Entry<T>>()
    private val idle = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)

    var hits = 0; private set
    var misses = 0; private set
    var inUseBytes = 0L; private set
    var idleBytes = 0L; private set
    val size: Int get() = inUse.size + idle.size
    val inUseCount: Int get() = inUse.size
    val idleCount: Int get() = idle.size

    /** Holt den Eintrag für [key] und zählt eine Referenz hoch; null, wenn es keinen gibt. */
    fun acquire(key: String): T? {
        val live = inUse[key]
        if (live != null) { live.refs++; hits++; return live.value }
        val cold = idle.remove(key)
        if (cold != null) {
            idleBytes -= cold.bytes
            inUseBytes += cold.bytes
            cold.refs = 1
            inUse[key] = cold
            hits++
            return cold.value
        }
        misses++
        return null
    }

    /** Legt einen neuen Eintrag mit einer Referenz an. [key] darf noch nicht vorhanden sein (erst [acquire]). */
    fun insert(key: String, value: T, bytes: Long): T {
        val old = inUse[key] ?: idle.remove(key)?.also { idleBytes -= it.bytes }
        if (old != null) { // Doppelanlage: alten Wert verwerfen, Referenzen übernehmen
            inUse.remove(key)?.let { inUseBytes -= it.bytes }
            dispose(old.value)
        }
        inUse[key] = Entry(key, value, bytes)
        inUseBytes += bytes
        return value
    }

    /** Gibt eine Referenz frei; der letzte Release schiebt den Eintrag in die LRU und kürzt sie auf das Budget. */
    fun release(key: String) {
        val e = inUse[key] ?: return
        if (--e.refs > 0) return
        inUse.remove(key)
        inUseBytes -= e.bytes
        idle[key] = e
        idleBytes += e.bytes
        evictToBudget()
    }

    /** Verwirft alle ungenutzten Einträge (Speicherdruck). */
    fun trimIdle() {
        for (e in idle.values) dispose(e.value)
        idle.clear()
        idleBytes = 0
    }

    /** Verwirft alles inklusive benutzter Einträge (Sink wird zerstört). */
    fun clear() {
        trimIdle()
        for (e in inUse.values) dispose(e.value)
        inUse.clear()
        inUseBytes = 0
    }

    private fun evictToBudget() {
        if (idleBytes <= maxIdleBytes) return
        val it = idle.values.iterator()
        while (idleBytes > maxIdleBytes && it.hasNext()) {
            val e = it.next()
            it.remove()
            idleBytes -= e.bytes
            dispose(e.value)
        }
    }
}

/** Was bei einem `onTrimMemory`-Stufenwert freigegeben wird (Werte von `ComponentCallbacks2`, hier ohne Android-Bezug). */
internal enum class TrimAction {
    NONE,
    /** Ungenutzte Texturen verwerfen. */
    IDLE_TEXTURES,
    /** Zusätzlich die Ebenen-Bitmaps (Himmel/Gelände, je ein Vollbild); sie werden bei Bedarf neu gezeichnet. */
    LAYERS,
    /** Zusätzlich auch benutzte Texturen und den Renderer abbauen, falls der Render-Thread gerade nicht läuft. */
    EVERYTHING,
}

internal object TrimPolicy {
    const val RUNNING_LOW = 10
    const val RUNNING_CRITICAL = 15
    const val UI_HIDDEN = 20
    const val BACKGROUND = 40

    fun actionFor(level: Int): TrimAction = when {
        level >= BACKGROUND -> TrimAction.EVERYTHING
        level >= RUNNING_CRITICAL -> TrimAction.LAYERS
        level >= RUNNING_LOW -> TrimAction.IDLE_TEXTURES
        else -> TrimAction.NONE
    }
}
