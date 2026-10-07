package de.bollwerk.engine.loop

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.view.AtomicInt
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.SnapshotBuilder
import de.bollwerk.engine.view.SnapshotExchange

/** Angaben, die ein [Replay] zusätzlich zum Spielverlauf braucht (Aufnahme ist an, wenn sie gesetzt sind). */
class ReplayMeta(
    val setup: MatchSetup,
    val config: SimConfig,
    /** Anzeige-Version des Contents (`ContentDb.version`). */
    val contentVersion: String,
    /** Maßgeblicher Fingerabdruck (`ContentDb.fingerprint`). */
    val contentHash: Long,
)

/**
 * Eine laufende Partie als **ein** deterministischer Ablauf: besitzt [GameSession] (Zustand, Systeme, Quellen), [GameLoop]
 * (Fixed-Step-Akkumulator), [SnapshotBuilder] und [SnapshotExchange]. App (WP9) und Simrunner treiben nur noch diese Klasse.
 *
 * ## Ablauf von [advance]
 * 1. Eingaben der anderen Threads übernehmen (Pause, Geschwindigkeit).
 * 2. `GameLoop.advance(realDelta · speed)` führt 0..`maxTicksPerAdvance · speed` Ticks aus (die Obergrenze skaliert mit dem
 *    Tempo); jeder Tick fragt die Quellen in fester Reihenfolge ([input] immer zuerst, dann die übergebenen: KI, Skript,
 *    Netzwerk), wendet die Commands an und legt Fx und Command-Ergebnisse in Sammelpuffer. **Nichts davon geht beim
 *    Aufholen verloren**: Die Puffer werden erst beim Veröffentlichen geleert, also fließen die Explosionen aller seit
 *    dem letzten Snapshot gelaufenen Ticks in den nächsten.
 * 3. Ein [FrameSnapshot] mit Interpolations-Startpunkten (`*Prev*`), [FrameSnapshot.alpha], HUD, Fx und Command-Ergebnissen
 *    wird in den [exchange] veröffentlicht (nur wenn sich etwas geändert hat: Tick gelaufen, Alpha geändert, Pause umgeschaltet).
 *    Hat der Render-Thread einen Snapshot noch nicht gelesen, holt der Schreiber ihn zurück und baut den nächsten in
 *    denselben Puffer: Ereignisse (Fx, Command-Ergebnisse) kommen so vollständig, einmal und in Reihenfolge an, auch ohne
 *    weiteren Tick. Ohne Leser altern Fx nach [SnapshotBuilder.MAX_CARRIED_FX_AGE_TICKS] aus.
 *
 * ## Threading (commonMain kennt keine Threads; die Plattform legt sie an)
 * ```
 * // Sim-Thread (genau einer; besitzt runner, GameState und alle Quellen/KI):
 * val runner = MatchRunner(session, ...)
 * var last = monotonicNanos()
 * while (running) {
 *     val now = monotonicNanos()
 *     runner.advance((now - last) * 1e-9f)      // Wall-Clock nur hier, nur als Zeitgeber – nie im Sim-Pfad
 *     last = now
 *     sleepUntilNextFrame()                      // ~ 4..8 ms; Ticks laufen nach Bedarf (Aufholen begrenzt)
 * }
 *
 * // UI-Thread (Touch, HUD-Buttons): ausschließlich thread-sichere Eingänge
 * runner.input.push(Command.Fire(0, playerId, deviceRef))   // CommandInbox
 * runner.setPaused(true); runner.setSpeed(1f)               // atomar
 *
 * // Render-Thread: nur lesen
 * val snap = runner.exchange.latest() ?: return
 * if (snap.seq != lastSeq) { lastSeq = snap.seq; processFx(snap.fx); showToasts(snap.commandResults) }
 * draw(snap, snap.alpha)        // lerp(prev, cur, alpha)
 * ```
 * Renderer abgehängt (Surface zerstört, Lockscreen, `onPause`): UI-Thread ruft `runner.setRendererAttached(false)`; die Sim
 * verwirft dann Fx/Ergebnisse für den Leser und veröffentlicht nichts, bis `setRendererAttached(true)` (dann sofort ein frischer
 * Snapshot, ohne Minuten alte Explosionen auf einmal).
 *
 * Alles andere ([state], [session], [advance], [runTicks], Listener, [publishedSeq]) gehört dem Sim-Thread.
 *
 * ## Zugmodus (Hotseat)
 * Phasen und Zeiten liefert das TURN-System der Regeln (`PLAY → RESOLVE → HANDOVER → PLAY`); die Sim läuft in allen Phasen
 * weiter. Mit [hotseat] zeigt das HUD die Sicht des aktiven Spielers; `HudModel.handover`/`handoverCountdown` steuern den
 * Übergabe-Bildschirm. Commands außerhalb der eigenen Spielphase lehnt die Vorprüfung mit `NOT_YOUR_TURN` ab ([CommandOutcome]).
 *
 * ## Replay
 * Mit [replayMeta] zeichnet der Runner angenommene Commands und alle [Replay.CHECKPOINT_INTERVAL] Ticks einen StateHash auf
 * ([toReplay]).
 *
 * @param inputDelayTicks Verzögerung lokaler Eingaben (offline 0, Lockstep ~6); gilt für [input] und den Loop.
 * @param acceptInput `false`: [input] verwirft Eingaben (Replay-Wiedergabe: nichts mischt sich in den aufgezeichneten Ablauf).
 * @param endTick Ab diesem Tick läuft die Sim nicht weiter ([reachedEnd]; Replay-Wiedergabe: `replay.ticks`).
 */
class MatchRunner(
    val session: GameSession,
    /** Spieler, dessen Sicht das HUD zeigt (im Hotseat: der aktive). */
    val localPlayer: Int = 0,
    /** HUD folgt dem aktiven Spieler (nur Hotseat, mehrere Menschen an einem Gerät). */
    val hotseat: Boolean = false,
    val inputDelayTicks: Int = 0,
    maxTicksPerAdvance: Int = 5,
    val exchange: SnapshotExchange = SnapshotExchange(),
    val replayMeta: ReplayMeta? = null,
    /** Zusätzliche Quellen (KI, Skript, Netzwerk) hinter [input]. */
    sources: List<CommandSource> = emptyList(),
    acceptInput: Boolean = true,
    val endTick: Long = Long.MAX_VALUE,
) {
    /** Lokale Eingabe: [LocalInputSource.push] ist von jedem Thread aus erlaubt. */
    val input: LocalInputSource = LocalInputSource(inputDelayTicks, acceptInput)

    val state: GameState get() = session.state

    private val outcomes = OutcomeBuffer()
    /** Copy-on-write: Listener dürfen sich während der Zustellung selbst entfernen oder weitere anmelden. */
    private var listeners: Array<CommandResultListener> = emptyArray()
    private val recorder: ReplayRecorder? = if (replayMeta != null) ReplayRecorder() else null
    private val builder = SnapshotBuilder(localPlayer).also { it.followActivePlayer = hotseat }

    val loop: GameLoop = GameLoop(
        dt = session.state.config.dt,
        maxTicksPerAdvance = maxTicksPerAdvance,
        inputDelayTicks = inputDelayTicks,
        canTick = { !reachedEnd && session.isReady() },
        onTick = { tickOnce() },
    )

    val paused: Boolean get() = loop.paused
    var speed: Float = 1f
        private set

    private val pauseRequest = AtomicInt(0)
    private val speedBits = AtomicInt(1f.toRawBits())
    private val detachedRequest = AtomicInt(0)
    private var detached = false
    private var dirty = true
    private var lastAlpha = -1f
    private var ticksInAdvance = 0

    /** Letzte veröffentlichte Snapshot-Nummer (0 = noch keiner); nur vom Sim-Thread lesen (andere Threads brauchen eigene Synchronisation). */
    var publishedSeq: Long = 0L
        private set

    init {
        require(replayMeta == null || session.state.tick == 0L) { "recording needs a session at tick 0" }
        session.addSourceFirst(input) // Eingabe wird immer zuerst abgefragt, auch wenn die Session bereits Quellen hat
        for (s in sources) session.addSource(s)
        val chained = session.recorder
        session.recorder = CommandRecorder { tick, cmd, result ->
            chained?.onCommand(tick, cmd, result)
            recorder?.onCommand(tick, cmd, result)
            val outcome = CommandOutcome(tick, cmd, result)
            outcomes.add(outcome)
            val snapshot = listeners // Array-Referenz einmal lesen: Änderungen während der Zustellung wirken erst danach
            for (l in snapshot) l.onOutcome(outcome)
        }
        mark()
        publish(1f) // vor dem ersten Tick gibt es keine Zwischenposition
    }

    // ---- Steuerung (thread-sicher) ----

    /** Pause an/aus; von jedem Thread, wirkt beim nächsten [advance]. */
    fun setPaused(paused: Boolean) { pauseRequest.getAndSet(if (paused) 1 else 0) }

    /** Geschwindigkeit (1 = Echtzeit, 0,25..4); von jedem Thread, wirkt beim nächsten [advance]. */
    fun setSpeed(speed: Float) {
        val v = if (!speed.isFinite()) 1f else if (speed < MIN_SPEED) MIN_SPEED else if (speed > MAX_SPEED) MAX_SPEED else speed
        speedBits.getAndSet(v.toRawBits())
    }

    /**
     * Renderer an-/abhängen; von jedem Thread (UI: `surfaceDestroyed`/`onPause` → `false`, `surfaceCreated`/`onResume` → `true`),
     * wirkt beim nächsten [advance]. Abgehängt verwirft die Sim Fx und Ergebnisse für den Leser und veröffentlicht nichts;
     * beim Anhängen folgt sofort ein frischer Snapshot.
     */
    fun setRendererAttached(attached: Boolean) { detachedRequest.getAndSet(if (attached) 0 else 1) }

    // ---- Sim-Thread ----

    /** Ab [endTick] läuft die Sim nicht mehr (Replay-Wiedergabe am Ende); das Spiel selbst ist damit nicht beendet ([finished]). */
    val reachedEnd: Boolean get() = state.tick >= endTick

    /** Interpolationsfaktor des letzten [advance]. */
    val alpha: Float get() = loop.alpha

    /** Aktueller Tick (Anzahl simulierter Ticks). */
    val tick: Long get() = state.tick

    val result: GameResult get() = state.result

    val finished: Boolean get() = state.result != GameResult.Ongoing

    /** Fordert das Ergebnis jedes Commands an (Sim-Thread, einmal je Command, Anwendungsreihenfolge). */
    fun addResultListener(listener: CommandResultListener) { listeners = listeners + listener }

    /** Darf auch aus einem Listener heraus aufgerufen werden (wirkt ab dem nächsten Command). */
    fun removeResultListener(listener: CommandResultListener) {
        val i = listeners.indexOf(listener)
        if (i < 0) return
        listeners = Array(listeners.size - 1) { k -> if (k < i) listeners[k] else listeners[k + 1] }
    }

    /** Convenience für lokale Eingaben (wie `input.push`). */
    fun submit(cmd: Command) = input.push(cmd)

    /**
     * Schiebt die Uhr um [realDeltaSeconds] weiter, führt die fälligen Ticks aus und veröffentlicht bei Änderung einen Snapshot.
     * @return Anzahl in diesem Aufruf ausgeführter Ticks.
     */
    fun advance(realDeltaSeconds: Float): Int {
        val wantPause = pauseRequest.get() != 0
        if (wantPause != loop.paused) { loop.paused = wantPause; dirty = true }
        val wantSpeed = Float.fromBits(speedBits.get())
        if (wantSpeed != speed) { speed = wantSpeed; dirty = true }

        val wantDetached = detachedRequest.get() != 0
        if (wantDetached != detached) {
            detached = wantDetached
            if (detached) dropEvents() else dirty = true
        }

        ticksInAdvance = 0
        // Obergrenze skaliert mit dem Tempo: Tempo 4 braucht bei 30 fps 8 Ticks je Frame
        val cap = ceilToInt(loop.maxTicksPerAdvance * speed)
        loop.advance(realDeltaSeconds * speed, if (cap < 1) 1 else cap)
        val n = ticksInAdvance
        if (detached) {
            dropEvents()
            return n
        }
        if (n > 0 || dirty || renderAlpha() != lastAlpha) publish()
        return n
    }

    /**
     * Headless: bis zu [n] Ticks ohne Uhr (Simrunner, Tests, Replay-Wiedergabe), danach ein Snapshot. Hält an, wenn eine
     * Quelle nicht bereit ist, und mit [stopOnResult] beim Spielende.
     * @return ausgeführte Ticks.
     */
    fun runTicks(n: Long, stopOnResult: Boolean = false): Long {
        var done = 0L
        while (done < n) {
            if (stopOnResult && finished) break
            if (reachedEnd || !session.isReady()) break
            tickOnce()
            done++
        }
        loop.reset()
        publish(1f) // headless gibt es kein Zwischenbild: Renderer/Tools zeichnen den Stand nach dem letzten Tick
        return done
    }

    /**
     * Veröffentlicht sofort einen Snapshot (z. B. nach Pause-Wechsel ohne Zeitfluss).
     * @param alpha Interpolationsfaktor; Standard: der des Loops (am [endTick] 1).
     */
    fun publish(alpha: Float = renderAlpha()) {
        val snap = exchange.beginWrite()
        builder.paused = loop.paused
        builder.speed = speed
        builder.build(state, snap, session.fxBuffer, outcomes, alpha)
        exchange.publish()
        publishedSeq = snap.seq
        lastAlpha = alpha
        dirty = false
    }

    /** StateHash des aktuellen Zustands. */
    fun hash(): Long = session.hash()

    // ---- Replay ----

    /** Zeichnet der Runner auf? */
    val isRecording: Boolean get() = recorder != null

    /** Aufgezeichnete Commands bisher (leer ohne Aufnahme). */
    val recordedCommands: List<Command> get() = recorder?.commands ?: emptyList()

    /** Aufgezeichnete Prüfpunkte bisher. */
    val recordedCheckpoints: List<HashCheckpoint> get() = recorder?.checkpoints ?: emptyList()

    /**
     * Replay der bisherigen Partie (inkl. Prüfpunkt am aktuellen Tick) oder null ohne [replayMeta]. Danach darf weitergespielt
     * und erneut abgefragt werden.
     */
    fun toReplay(): Replay? {
        val rec = recorder ?: return null
        val meta = replayMeta ?: return null
        rec.checkpoint(state.tick, session.hash())
        return rec.toReplay(meta.setup, meta.config, meta.contentVersion, meta.contentHash, state.tick)
    }

    // ---- intern ----

    /** Alpha fürs Rendering: am Ende (Replay) gibt es keinen nächsten Tick zum Interpolieren, also 1. */
    private fun renderAlpha(): Float = if (reachedEnd) 1f else loop.alpha

    /** Renderer abgehängt: nichts, was nur für den Leser bestimmt ist, soll sich ansammeln. */
    private fun dropEvents() {
        session.fxBuffer.clear()
        outcomes.clear()
        exchange.discardPendingEvents()
    }

    private fun ceilToInt(v: Float): Int {
        val i = v.toInt()
        return if (v > i) i + 1 else i
    }

    private fun tickOnce() {
        if (!session.tick()) return
        ticksInAdvance++
        mark()
    }

    private fun mark() {
        val rec = recorder ?: return
        if (state.tick % Replay.CHECKPOINT_INTERVAL == 0L) rec.checkpoint(state.tick, session.hash())
    }

    companion object {
        const val MIN_SPEED: Float = 0.25f
        const val MAX_SPEED: Float = 4f
    }
}
