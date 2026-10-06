package de.bollwerk.engine.view

/** Minimaler atomarer Int (JVM: `AtomicInteger`; iOS bekommt ein eigenes `actual`). */
expect class AtomicInt(initial: Int) {
    fun get(): Int
    fun getAndSet(value: Int): Int
}

/**
 * Lock-freier Dreifachpuffer für [FrameSnapshot]s zwischen genau einem Schreiber (Sim-Thread) und genau
 * einem Leser (Render-Thread).
 *
 * Sim-Thread: `val s = beginWrite(); builder.build(state, s, fxBuffer); publish()`.
 * Render-Thread: `val s = latest()`; ist `s.seq` neu, sind `s.fx` frisch und werden genau einmal verarbeitet.
 *
 * Kein Snapshot geht mit seinen Fx verloren: Veröffentlicht der Sim-Thread zweimal, bevor gelesen wurde,
 * bekommt er den ungelesenen Puffer zurück und markiert ihn mit [FrameSnapshot.carryFx], sodass der nächste
 * Build dessen Ereignisse behält und neue anhängt.
 */
class SnapshotExchange(factory: () -> FrameSnapshot = { FrameSnapshot() }) {
    private val buffers = Array(3) { factory() }
    private var writeIdx = 0
    private var readIdx = 1
    /** Index des mittleren Puffers, Bit [FRESH] = veröffentlicht und ungelesen. */
    private val middle = AtomicInt(2)
    private var everRead = false

    /** Sim-Thread: Puffer zum Befüllen. */
    fun beginWrite(): FrameSnapshot = buffers[writeIdx]

    /** Sim-Thread: macht den befüllten Puffer für den Leser sichtbar. */
    fun publish() {
        val old = middle.getAndSet(writeIdx or FRESH)
        writeIdx = old and INDEX_MASK
        if ((old and FRESH) != 0) buffers[writeIdx].carryFx = true
    }

    /** Render-Thread: neuester veröffentlichter Snapshot oder null, solange noch nichts veröffentlicht wurde. */
    fun latest(): FrameSnapshot? {
        if ((middle.get() and FRESH) != 0) {
            val old = middle.getAndSet(readIdx)
            readIdx = old and INDEX_MASK
            everRead = true
        }
        return if (everRead) buffers[readIdx] else null
    }

    private companion object {
        const val FRESH = 4
        const val INDEX_MASK = 3
    }
}
