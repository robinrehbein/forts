package de.bollwerk.engine.loop

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason

/**
 * Ergebnis eines Commands für UI-Rückmeldung (roter Ghost, Toast, Ressourcen-Chip blinkt) und KI.
 *
 * [command] ist die **angewendete** Fassung: `tick` = Tick, in dem es lief (bei Input-Delay oder verspäteter Zustellung
 * nicht der Tick des `push`), Werte bereits geklemmt (`CommandChecks.sanitize`). Um ein selbst gesendetes Command
 * wiederzuerkennen, vergleicht die UI `outcome.command.withTick(0) == sent.withTick(0)` (bei geklemmten Werten ggf. nur
 * Typ, Spieler und Referenzen).
 */
data class CommandOutcome(val tick: Long, val command: Command, val result: CommandResult) {
    val accepted: Boolean get() = result == CommandResult.Accepted

    /** Ablehnungsgrund oder null, wenn angenommen. */
    val reason: RejectReason? get() = (result as? CommandResult.Rejected)?.reason

    val playerId: Int get() = command.playerId
}

/** Beobachter der Command-Ergebnisse (Sim-Thread, einmal je Command, in Anwendungsreihenfolge). */
fun interface CommandResultListener {
    fun onOutcome(outcome: CommandOutcome)
}

/**
 * Sammelt Ergebnisse über mehrere Ticks (Sim-Thread), bis der nächste Snapshot sie abholt (wie `FxBuffer`).
 * Bei mehr als [capacity] Einträgen fallen die ältesten heraus.
 */
class OutcomeBuffer(val capacity: Int = 256) {
    private val items = ArrayDeque<CommandOutcome>()

    val size: Int get() = items.size

    fun add(outcome: CommandOutcome) {
        if (items.size == capacity) items.removeFirst()
        items.addLast(outcome)
    }

    /** Hängt alle Einträge in Reihenfolge an [out] an und leert den Puffer. */
    fun drainTo(out: MutableList<CommandOutcome>) {
        while (items.isNotEmpty()) out.add(items.removeFirst())
    }

    fun clear() = items.clear()
}
