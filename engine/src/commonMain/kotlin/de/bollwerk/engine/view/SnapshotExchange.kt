package de.bollwerk.engine.view

/** Minimaler atomarer Int (JVM: `AtomicInteger`; iOS bekommt ein eigenes `actual`). */
expect class AtomicInt(initial: Int) {
    fun get(): Int
    fun getAndSet(value: Int): Int
    /** Setzt [value], wenn der aktuelle Wert [expected] ist. @return ob gesetzt wurde. */
    fun compareAndSet(expected: Int, value: Int): Boolean
}

/**
 * Lock-freier Dreifachpuffer für [FrameSnapshot]s zwischen genau einem Schreiber (Sim-Thread) und genau
 * einem Leser (Render-Thread). Lese-, Mittel- und Schreibpuffer sind jederzeit eine Permutation der drei Puffer.
 *
 * Sim-Thread: `val s = beginWrite(); builder.build(state, s, fxBuffer); publish()`.
 * Render-Thread: `val s = latest()`; ist `s.seq` neu, sind `s.fx` frisch und werden genau einmal verarbeitet.
 *
 * **Keine Ereignisse gehen verloren, keine kommen doppelt, alle in Reihenfolge:** Liegt im Mittelpuffer noch ein
 * ungelesener Snapshot, holt [beginWrite] ihn per Compare-and-Set **zurück** (der Leser behält seinen bisherigen Puffer)
 * und der Schreiber baut den nächsten Snapshot in genau diesen Puffer; [FrameSnapshot.carryFx] sorgt dafür, dass der
 * Builder dessen Ereignisse behält und die neuen anhängt. Ein ungelesener Puffer trägt somit immer **alle** noch
 * nicht zugestellten Ereignisse (auch am Ende eines Stroms, ohne Nachveröffentlichen). Das Zurückholen ist
 * race-frei: Gewinnt der Leser das CAS, wurde der Snapshot zugestellt und der Schreiber beginnt mit einem leeren Puffer.
 * Liest niemand (Renderer noch nicht gestartet, Surface weg), wächst nichts unbegrenzt: Der Builder begrenzt Anzahl und
 * Alter übernommener Fx; [discardPendingEvents] verwirft sie ganz.
 */
class SnapshotExchange(factory: () -> FrameSnapshot = { FrameSnapshot() }) {
    private val buffers = Array(3) { factory() }
    private var writeIdx = 0
    private var readIdx = 1
    /** Index des mittleren Puffers, Bit [FRESH] = veröffentlicht und ungelesen. */
    private val middle = AtomicInt(2)
    private var everRead = false

    /**
     * Sim-Thread: Puffer zum Befüllen. Ein noch ungelesener Snapshot wird zurückgeholt und mit [FrameSnapshot.carryFx]
     * markiert, damit seine Ereignisse in den nächsten Snapshot übergehen.
     */
    fun beginWrite(): FrameSnapshot {
        reclaimUnread()?.carryFx = true
        return buffers[writeIdx]
    }

    /** Sim-Thread: macht den befüllten Puffer für den Leser sichtbar. */
    fun publish() {
        val old = middle.getAndSet(writeIdx or FRESH)
        writeIdx = old and INDEX_MASK
        // Nur bei publish ohne vorheriges beginWrite: der überholte, ungelesene Snapshot bleibt dann beim Schreiber liegen
        if ((old and FRESH) != 0) buffers[writeIdx].carryFx = true
    }

    /**
     * Sim-Thread: verwirft alle noch nicht zugestellten Ereignisse (Fx, Command-Ergebnisse), z. B. wenn der Renderer
     * abgehängt wird (Surface zerstört, App im Hintergrund), damit er später nicht Minuten alte Explosionen auf einmal
     * abspielt. Der Leser behält seinen bisherigen Snapshot; ein ungelesener wird verworfen.
     */
    fun discardPendingEvents() {
        reclaimUnread()
        val w = buffers[writeIdx]
        w.fx.clear(); w.commandResults.clear(); w.carryFx = false
    }

    /** Render-Thread: neuester veröffentlichter Snapshot oder null, solange noch nichts veröffentlicht wurde. */
    fun latest(): FrameSnapshot? {
        val m = middle.get()
        if ((m and FRESH) != 0 && middle.compareAndSet(m, readIdx)) {
            readIdx = m and INDEX_MASK
            everRead = true
        }
        return if (everRead) buffers[readIdx] else null
    }

    /** Holt einen ungelesenen Mittelpuffer als neuen Schreibpuffer zurück (der alte Schreibpuffer wird Platzhalter). */
    private fun reclaimUnread(): FrameSnapshot? {
        val m = middle.get()
        if ((m and FRESH) == 0 || !middle.compareAndSet(m, writeIdx)) return null
        writeIdx = m and INDEX_MASK
        return buffers[writeIdx]
    }

    private companion object {
        const val FRESH = 4
        const val INDEX_MASK = 3
    }
}
