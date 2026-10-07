package de.bollwerk.app.game

/** Art eines Eintrags in der [SimInbox] (UI-Thread → Sim-Thread). */
internal object InboxKind {
    const val POINTER = 1
    const val LONG_PRESS = 2
    const val SELECT_TOOL = 3
    const val ENTER_AIM = 4
    const val FIRE = 5
    const val UNDO = 6
    const val SELECT_WEAPON = 7
    const val CYCLE_WEAPON = 8
    const val CHOOSE_CONTEXT = 9
    const val DISMISS_CONTEXT = 10
    const val SET_POWER = 11
    const val SET_DOORS = 12
    const val END_TURN = 13
    const val RELEASE_TO_FIRE = 14
    const val CANCEL_GESTURE = 15
}

/** Empfänger beim Abarbeiten der [SimInbox] (Sim-Thread). Primitive Parameter: keine Allokation je Ereignis. */
internal fun interface InboxHandler {
    fun handle(kind: Int, i0: Int, f0: Float, f1: Float, f2: Float, l0: Long, obj: Any?)
}

/**
 * Warteschlange fester Größe vom UI-Thread zum Sim-Thread für Zeigerereignisse und Werkzeug-Aktionen, ohne Allokation je
 * Ereignis (Struktur aus Arrays, Doppelpuffer). Aufeinanderfolgende MOVE-Ereignisse derselben Geste werden zusammengefasst
 * (nur der neueste Fingerstand zählt). Ist der Puffer voll, wird das Ereignis verworfen (Rückgabe `false`); bei
 * [CAPACITY] Einträgen zwischen zwei Sim-Schritten kommt das praktisch nicht vor.
 */
internal class SimInbox(private val capacity: Int = CAPACITY) {
    private class Buf(n: Int) {
        val kind = IntArray(n)
        val i0 = IntArray(n)
        val f0 = FloatArray(n)
        val f1 = FloatArray(n)
        val f2 = FloatArray(n)
        val l0 = LongArray(n)
        val obj = arrayOfNulls<Any>(n)
        var size = 0
    }

    private var front = Buf(capacity)
    private var back = Buf(capacity)
    private val lock = Any()

    /** Von jedem Thread. */
    fun offer(kind: Int, i0: Int = 0, f0: Float = 0f, f1: Float = 0f, f2: Float = 0f, l0: Long = 0L, obj: Any? = null): Boolean =
        synchronized(lock) {
            val b = front
            var k = b.size
            if (kind == InboxKind.POINTER && i0 == PHASE_MOVE && k > 0) {
                val last = k - 1
                if (b.kind[last] == InboxKind.POINTER && b.i0[last] == PHASE_MOVE && b.l0[last] == l0) k = last
            }
            if (k >= capacity) return false
            b.kind[k] = kind; b.i0[k] = i0; b.f0[k] = f0; b.f1[k] = f1; b.f2[k] = f2; b.l0[k] = l0; b.obj[k] = obj
            if (k == b.size) b.size = k + 1
            true
        }

    /** Sim-Thread: arbeitet alle wartenden Einträge in Eingangsreihenfolge ab (außerhalb der Sperre). @return Anzahl. */
    fun drain(handler: InboxHandler): Int {
        val b: Buf
        synchronized(lock) {
            b = front
            front = back
            back = b
        }
        val n = b.size
        for (i in 0 until n) {
            handler.handle(b.kind[i], b.i0[i], b.f0[i], b.f1[i], b.f2[i], b.l0[i], b.obj[i])
            b.obj[i] = null
        }
        b.size = 0
        return n
    }

    val pending: Int get() = synchronized(lock) { front.size }

    companion object {
        const val CAPACITY = 256

        /** `PointerPhase.MOVE.ordinal`, hier als Konstante, damit die Zusammenfassung ohne Enum-Zugriff auskommt. */
        const val PHASE_MOVE = 1
    }
}
