package de.bollwerk.engine.command

/**
 * Tick-indizierte Warteschlange mit deterministischer Ausgabe-Reihenfolge:
 * nach `tick`, dann `playerId`, dann Einfüge-Reihenfolge (`seq`).
 *
 * Intern eine sortierte Liste (keine Hash-Strukturen). Für die erwarteten Mengen (wenige Commands
 * pro Sekunde) ist Einfügen per Binärsuche völlig ausreichend.
 */
class CommandQueue {
    private class Entry(val cmd: Command, val seq: Long)

    private val entries = ArrayList<Entry>()
    private var nextSeq = 0L

    /** Anzahl wartender Commands. */
    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    /** Reiht [cmd] ein. */
    fun add(cmd: Command) {
        val e = Entry(cmd, nextSeq++)
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(entries[mid], e) <= 0) lo = mid + 1 else hi = mid
        }
        entries.add(lo, e)
    }

    fun addAll(cmds: List<Command>) { for (c in cmds) add(c) }

    /**
     * Entnimmt alle Commands mit `cmd.tick <= tick` in deterministischer Reihenfolge.
     * Verspätete Commands (tick < aktueller Tick) werden damit im nächsten Tick angewendet; im
     * Lockstep-Modus verhindert der Input-Delay, dass das vorkommt.
     */
    fun drain(tick: Long, into: MutableList<Command> = ArrayList()): MutableList<Command> {
        var n = 0
        while (n < entries.size && entries[n].cmd.tick <= tick) n++
        if (n == 0) return into
        for (i in 0 until n) into.add(entries[i].cmd)
        entries.subList(0, n).clear()
        return into
    }

    /** Wartende Commands (Kopie, sortiert). */
    fun pending(): List<Command> = entries.map { it.cmd }

    fun clear() { entries.clear() }

    private fun compare(x: Entry, y: Entry): Int {
        if (x.cmd.tick != y.cmd.tick) return if (x.cmd.tick < y.cmd.tick) -1 else 1
        if (x.cmd.playerId != y.cmd.playerId) return if (x.cmd.playerId < y.cmd.playerId) -1 else 1
        return if (x.seq < y.seq) -1 else if (x.seq > y.seq) 1 else 0
    }
}
