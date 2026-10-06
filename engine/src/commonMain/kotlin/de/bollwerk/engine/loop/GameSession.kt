package de.bollwerk.engine.loop

import de.bollwerk.engine.command.BasicValidator
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandChecks
import de.bollwerk.engine.command.CommandQueue
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.CommandValidator
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.FxBuffer
import de.bollwerk.engine.view.FxEvent

/** Beobachter für aufgezeichnete Commands (Replay-Recorder, Netzwerk, Debug-Log). */
fun interface CommandRecorder {
    fun onCommand(tick: Long, cmd: Command, result: CommandResult)
}

/**
 * Bündelt eine laufende Partie: Zustand (inkl. Tabellen und Karte), Queue, Quellen, Vorprüfung, Stepper.
 * Läuft vollständig auf dem Sim-Thread.
 *
 * Ablauf pro [tick]:
 * 1. Sind alle [CommandSource]s für den Tick bereit (`isReady`)? Sonst kein Tick (Lockstep-Stillstand).
 * 2. Quellen in fester Reihenfolge abfragen → Queue → fällige Commands in deterministischer Reihenfolge.
 * 3. Je Command: Tick angleichen, [CommandChecks.sanitize], billige Vorprüfung mit [precheck]
 *    (Standard: Spielende, Zug, endliche Floats, veraltete Refs). Abgelehnte → Recorder + `FxEvent.CommandRejected`.
 * 4. Übrige in `ctx.commands` → [SimStepper.step]. Das Command-System (WP3) prüft und wendet sie
 *    **sequenziell** gegen den sich ändernden Zustand an und schreibt `ctx.results`.
 * 5. Recorder bekommt das tatsächliche Ergebnis aus `ctx.results`; Ablehnungen zusätzlich als Fx.
 * 6. `ctx.fx` wandert in den [fxBuffer] (abgeholt vom `SnapshotBuilder`).
 */
class GameSession(
    val state: GameState,
    val stepper: SimStepper = SimStepper.NONE,
    val precheck: CommandValidator = BasicValidator,
    sources: List<CommandSource> = emptyList(),
    var recorder: CommandRecorder? = null,
    val fxBuffer: FxBuffer = FxBuffer(),
) {
    val ctx: StepContext = StepContext()
    val queue: CommandQueue = CommandQueue()
    private val sources = sources.toMutableList()
    private val due = ArrayList<Command>()
    private val rejectedFx = ArrayList<FxEvent>()

    /** Weitere Quelle anhängen (Reihenfolge = Abfragereihenfolge). */
    fun addSource(source: CommandSource) { sources.add(source) }

    /** Sind alle Quellen für den aktuellen Tick bereit? */
    fun isReady(): Boolean {
        val t = state.tick
        for (i in sources.indices) if (!sources[i].isReady(t)) return false
        return true
    }

    /**
     * Einen Sim-Tick ausführen.
     * @return `false`, wenn eine Quelle noch nicht bereit ist (kein Tick ausgeführt).
     */
    fun tick(): Boolean {
        if (!isReady()) return false
        val t = state.tick
        for (i in sources.indices) queue.addAll(sources[i].commandsFor(t, state))
        ctx.beginTick(t)
        rejectedFx.clear()
        due.clear()
        queue.drain(t, due)
        for (raw in due) {
            // Verspätete Commands wirken jetzt; Tick angleichen, damit das Replay identisch abläuft.
            val stamped = if (raw.tick == t) raw else raw.withTick(t)
            val reason = precheck.validate(state, stamped)
            if (reason != null) {
                reject(t, stamped, reason)
                continue
            }
            ctx.commands.add(CommandChecks.sanitize(stamped, state.config))
        }
        stepper.step(state, ctx)
        for (i in ctx.commands.indices) {
            val cmd = ctx.commands[i]
            val result = if (i < ctx.results.size) ctx.results[i] else CommandResult.Accepted
            if (result is CommandResult.Rejected) reject(t, cmd, result.reason)
            else recorder?.onCommand(t, cmd, result)
        }
        fxBuffer.addAll(ctx.fx)
        fxBuffer.addAll(rejectedFx)
        return true
    }

    /** [n] Ticks ausführen (headless); bricht ab, wenn eine Quelle nicht bereit ist. @return ausgeführte Ticks. */
    fun run(n: Long): Long {
        var done = 0L
        while (done < n && tick()) done++
        return done
    }

    /** Aktueller StateHash. */
    fun hash(): Long = StateHash.of(state)

    private fun reject(t: Long, cmd: Command, reason: RejectReason) {
        recorder?.onCommand(t, cmd, CommandResult.Rejected(reason))
        rejectedFx.add(FxEvent.CommandRejected(t, Float.NaN, Float.NaN, cmd.playerId, reason))
    }
}

/** Zeichnet angenommene Commands für ein [Replay] auf. */
class ReplayRecorder : CommandRecorder {
    private val accepted = ArrayList<Command>()

    override fun onCommand(tick: Long, cmd: Command, result: CommandResult) {
        if (result == CommandResult.Accepted) accepted.add(cmd)
    }

    /** Erzeugt das Replay; [ticks] = bisher simulierte Ticks. */
    fun toReplay(
        setup: MatchSetup,
        config: SimConfig,
        contentVersion: String,
        contentHash: Long,
        ticks: Long,
    ): Replay = Replay(
        contentVersion = contentVersion,
        contentHash = contentHash,
        setup = setup,
        config = config,
        commands = accepted.toList(),
        ticks = ticks,
    )

    /** Bisher angenommene Commands. */
    val commands: List<Command> get() = accepted
}
