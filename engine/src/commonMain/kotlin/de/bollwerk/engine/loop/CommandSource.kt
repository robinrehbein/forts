package de.bollwerk.engine.loop

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.view.GameView

/**
 * Liefert Commands für einen Tick: lokale Eingabe, KI, Replay oder (später) Netzwerk.
 * Wird von [GameSession] einmal pro Tick in fester Reihenfolge der Quellen auf dem **Sim-Thread** gefragt.
 * Zurückgegebene Commands dürfen einen späteren `tick` tragen (Input-Delay).
 */
fun interface CommandSource {
    fun commandsFor(tick: Long, view: GameView): List<Command>

    /**
     * Liegen alle Eingaben für [tick] vor? Lockstep-Quellen melden `false`, bis die Commands des Gegners
     * für diesen Tick angekommen sind; [GameSession.tick] simuliert dann nicht (Stillstand statt Desync).
     */
    fun isReady(tick: Long): Boolean = true
}

/** Quelle für vorab aufgezeichnete Commands (Replays, Tests). */
class ScriptedCommandSource(commands: List<Command>) : CommandSource {
    private val sorted = commands.sortedWith(compareBy<Command>({ it.tick }, { it.playerId }))
    private var next = 0

    override fun commandsFor(tick: Long, view: GameView): List<Command> {
        if (next >= sorted.size || sorted[next].tick > tick) return emptyList()
        val out = ArrayList<Command>()
        while (next < sorted.size && sorted[next].tick <= tick) out.add(sorted[next++])
        return out
    }
}

/**
 * Thread-sichere Mehr-Erzeuger/Ein-Verbraucher-Warteschlange (UI-Thread → Sim-Thread).
 * JVM/Android: `ConcurrentLinkedQueue`; iOS bekommt ein eigenes `actual`.
 */
expect class CommandInbox() {
    /** Von beliebigem Thread. */
    fun offer(cmd: Command)
    /** Nur Sim-Thread: entnimmt alle wartenden Commands in Eingangsreihenfolge. */
    fun drainTo(out: MutableList<Command>)
}

/**
 * Lokale Eingabe (Touch-Werkzeuge, HUD-Buttons). [push] darf vom UI-Thread kommen; der Tick wird beim
 * Abholen auf `tick + inputDelayTicks` gesetzt (offline 0, Lockstep ~6), damit Commands nie in der
 * Vergangenheit liegen.
 */
class LocalInputSource(
    val inputDelayTicks: Int = 0,
    /** `false` (z. B. Replay-Wiedergabe): [push] verwirft die Eingabe, nichts mischt sich in den Ablauf. */
    val acceptInput: Boolean = true,
) : CommandSource {
    private val inbox = CommandInbox()
    private val scratch = ArrayList<Command>()

    /** Thread-sicher. Der `tick` des Commands wird ignoriert und beim Abholen gesetzt. */
    fun push(cmd: Command) { if (acceptInput) inbox.offer(cmd) }

    override fun commandsFor(tick: Long, view: GameView): List<Command> {
        scratch.clear()
        inbox.drainTo(scratch)
        if (scratch.isEmpty()) return emptyList()
        val target = tick + inputDelayTicks
        return scratch.map { it.withTick(target) }
    }
}
