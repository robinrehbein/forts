package de.bollwerk.engine.sim

/**
 * Lesender Zugriff auf einen Pool.
 *
 * **Stabile Referenzen:** Slot-IDs (`Int`) werden wiederverwendet. Alles, was eine ID über einen Tick
 * hinaus festhält (Commands, UI-Werkzeuge, KI, Render-Caches), benutzt eine **Ref** ([ref]): Generation
 * in den oberen 32 Bit, Slot in den unteren. [resolve] liefert den Slot nur, solange er noch dasselbe
 * Objekt enthält, sonst −1 (Validator: `RejectReason.STALE_TARGET`). [NO_REF] (−1) = "keine Referenz".
 */
interface PoolView {
    /** Schleifengrenze: alle IDs liegen in `0 until size`. */
    val size: Int
    /** Anzahl lebender Einträge. */
    val aliveCount: Int
    /** Lebt der Eintrag [id]? */
    fun isAlive(id: Int): Boolean
    /** Generation des Slots [id] (wird bei jeder Freigabe erhöht). */
    fun gen(id: Int): Int
    /** Monotone, nie wiederverwendete Objekt-Nummer des aktuellen Bewohners (für Render-Caches, Fx). */
    fun uid(id: Int): Int
    /** Stabile Referenz auf den aktuellen Bewohner von [id]. */
    fun ref(id: Int): Long = refOf(id, gen(id))
    /** Slot zu [ref] oder −1, wenn veraltet/ungültig. */
    fun resolve(ref: Long): Int

    companion object {
        /** "Keine Referenz". */
        const val NO_REF: Long = -1L

        /** Baut eine Referenz aus Slot und Generation (Generation auf 31 Bit maskiert, damit Refs ≥ 0 sind). */
        fun refOf(id: Int, gen: Int): Long = ((gen and 0x7fffffff).toLong() shl 32) or (id.toLong() and 0xffffffffL)

        /** Slot-Anteil einer Referenz. */
        fun refSlot(ref: Long): Int = ref.toInt()

        /** Generations-Anteil einer Referenz. */
        fun refGen(ref: Long): Int = (ref ushr 32).toInt()
    }
}

/**
 * Basis aller Structure-of-Arrays-Pools mit Int-IDs.
 *
 * - IDs sind Array-Indizes in `0 until size`. Schleifen laufen **immer** in dieser Reihenfolge.
 * - **Verzögerte Freigabe:** [release] markiert den Slot sofort als tot (ALIVE gelöscht, [gen]++), legt ihn
 *   aber erst in [endTick] (vom `SimStepper` nach allen Systemen aufgerufen) auf den LIFO-Freistapel.
 *   Ein im selben Tick freigegebener Slot wird also nie im selben Tick neu belegt.
 * - Neu belegte Slots tragen [allocTick] = [now]. Systeme, die über einen Pool iterieren, merken sich
 *   `val n = size` vor der Schleife und überspringen Slots mit `isNew(i)` (im laufenden Tick entstanden),
 *   damit z. B. eine eben entstandene Bruchhälfte nicht im selben Tick Schaden/Feuer bekommt.
 * - Ein toter Slot behält seine Daten bis zur Wiederverwendung; Leser prüfen [isAlive].
 *
 * Feld-Klassen (gilt für alle Pools, steht an jedem Feld):
 * - **PERSISTENT**: Spielzustand, geht in `StateHash` ein und wird serialisiert.
 * - **RENDER**: nur Darstellung, wird serialisiert, aber nicht gehasht (kein Einfluss auf die Sim).
 * - **DERIVED**: aus PERSISTENT-Feldern ableitbar, nicht gehasht, nach Restore per `GameState.rebuildDerived()`.
 */
abstract class Pool(initialCapacity: Int) : PoolView {
    /** PERSISTENT. Bit 0 jedes Eintrags ist [ALIVE]; die übrigen Bits definiert der konkrete Pool. */
    var flags: IntArray = IntArray(initialCapacity)
        private set

    /** PERSISTENT. Generation je Slot, siehe [PoolView.ref]. */
    var genOf: IntArray = IntArray(initialCapacity)
        private set

    /** PERSISTENT. Objekt-Nummer je Slot, siehe [PoolView.uid]. */
    var uidOf: IntArray = IntArray(initialCapacity)
        private set

    /** PERSISTENT. Tick, in dem der Slot zuletzt belegt wurde (= Bauzeitpunkt). */
    var allocTick: LongArray = LongArray(initialCapacity)
        private set

    /** PERSISTENT. Nächste zu vergebende [uidOf]. */
    var nextUid: Int = 0
        private set

    /** Aktueller Sim-Tick für [allocTick]; setzt `GameState.beginTick()`. Vor Spielbeginn −1. */
    var now: Long = -1L

    /** Aktuelle Array-Kapazität. */
    var capacity: Int = initialCapacity
        private set

    /** PERSISTENT. Hochwassermarke: Anzahl jemals belegter Slots (Schleifengrenze). */
    final override var size: Int = 0
        private set

    /** Anzahl lebender Einträge. */
    final override var aliveCount: Int = 0
        private set

    private var freeStack = IntArray(16)
    private var freeTop = 0
    private var pending = IntArray(16)
    private var pendingTop = 0

    /** Lebt der Eintrag [id]? */
    final override fun isAlive(id: Int): Boolean = id in 0 until size && (flags[id] and ALIVE) != 0

    final override fun gen(id: Int): Int = genOf[id]
    final override fun uid(id: Int): Int = uidOf[id]

    final override fun resolve(ref: Long): Int {
        if (ref < 0L) return -1
        val id = PoolView.refSlot(ref)
        return if (isAlive(id) && (genOf[id] and 0x7fffffff) == PoolView.refGen(ref)) id else -1
    }

    /** Wurde [id] im laufenden Tick belegt? (Systeme überspringen solche Slots in ihrer Schleife.) */
    fun isNew(id: Int): Boolean = allocTick[id] == now

    /** Belegt einen Slot und gibt seine ID zurück; die Felder des Slots setzt der Aufrufer. */
    protected fun allocSlot(): Int {
        val id = if (freeTop > 0) {
            freeStack[--freeTop]
        } else {
            if (size == capacity) growTo(if (capacity < 8) 16 else capacity * 2)
            size++
        }
        flags[id] = ALIVE
        uidOf[id] = nextUid++
        allocTick[id] = now
        aliveCount++
        return id
    }

    /**
     * Gibt [id] frei (idempotent): sofort tot und [gen] erhöht (alle Refs werden ungültig), wiederverwendbar
     * erst nach [endTick].
     */
    open fun release(id: Int) {
        if (!isAlive(id)) return
        flags[id] = 0
        genOf[id] = (genOf[id] + 1) and 0x7fffffff
        aliveCount--
        if (pendingTop == pending.size) pending = pending.copyOf(pending.size * 2)
        pending[pendingTop++] = id
    }

    /** Übergibt die in diesem Tick freigegebenen Slots in Freigabe-Reihenfolge an den Freistapel. */
    fun endTick() {
        for (i in 0 until pendingTop) {
            if (freeTop == freeStack.size) freeStack = freeStack.copyOf(freeStack.size * 2)
            freeStack[freeTop++] = pending[i]
        }
        pendingTop = 0
    }

    /** Leert den Pool vollständig (Kapazität bleibt, Generationen/UIDs laufen weiter). */
    open fun clear() {
        for (i in 0 until size) {
            if ((flags[i] and ALIVE) != 0) genOf[i] = (genOf[i] + 1) and 0x7fffffff
            flags[i] = 0
        }
        size = 0
        aliveCount = 0
        freeTop = 0
        pendingTop = 0
    }

    private fun growTo(newCapacity: Int) {
        flags = flags.copyOf(newCapacity)
        genOf = genOf.copyOf(newCapacity)
        uidOf = uidOf.copyOf(newCapacity)
        allocTick = allocTick.copyOf(newCapacity)
        resize(newCapacity)
        capacity = newCapacity
    }

    /** Konkrete Pools vergrößern hier alle ihre Arrays auf [newCapacity]. */
    protected abstract fun resize(newCapacity: Int)

    /** Freistapel in Reihenfolge (für Hash/Serialisierung, damit Wiederverwendung reproduzierbar ist). */
    fun freeListSnapshot(): IntArray = freeStack.copyOf(freeTop)

    /** Im laufenden Tick freigegebene, noch nicht wiederverwendbare Slots (für Hash/Serialisierung). */
    fun pendingSnapshot(): IntArray = pending.copyOf(pendingTop)

    companion object {
        /** Gemeinsames Lebend-Bit in allen Pools. */
        const val ALIVE: Int = 1
    }
}
