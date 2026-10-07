package de.bollwerk.app.game

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.loop.CommandOutcome
import de.bollwerk.engine.loop.MatchRunner
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.tools.AimInfo
import de.bollwerk.engine.tools.ContextMenu
import de.bollwerk.engine.tools.PointerPhase
import de.bollwerk.engine.tools.TapAction
import de.bollwerk.engine.tools.ToolContext
import de.bollwerk.engine.tools.ToolController
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolOverlay
import de.bollwerk.engine.tools.ToolResult
import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.engine.view.HudModel
import de.bollwerk.engine.view.SnapshotBuilder
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Monotone Uhr in Nanosekunden (injizierbar: Tests treiben die Sim mit einer falschen Uhr). Nur Zeitgeber, nie Sim-State. */
fun interface MonotonicClock {
    fun nanos(): Long

    companion object {
        val SYSTEM: MonotonicClock = MonotonicClock { System.nanoTime() }
    }
}

/**
 * Werkzeugzustand für die Compose-HUD-Schicht (Toolbar-Auswahl, Modusschalter, Waffenkarte, Kontextmenü). Unveränderlich;
 * Gleichheit nach Inhalt, damit der StateFlow unveränderte Stände nicht neu meldet.
 */
data class ToolUiState(
    val mode: ToolMode = ToolMode.NONE,
    val selection: ToolSelection = ToolSelection.None,
    /** Gewählte Waffe (Ref) oder −1. */
    val weaponRef: Long = -1L,
    val aim: AimInfo? = null,
    val contextMenu: ContextMenu? = null,
    /** Grund, warum die laufende Vorschau ungültig ist. */
    val previewRejected: RejectReason? = null,
    val canUndo: Boolean = false,
    /** Eigene Türen (gesamt / davon offen). */
    val doorCount: Int = 0,
    val doorsOpen: Int = 0,
    /** Spieler, für den die Werkzeuge arbeiten (Hotseat: der aktive). */
    val localPlayer: Int = 0,
)

/** Partieende, einmal gemeldet. */
data class MatchEnd(val result: GameResult, val tick: Long, val durationSeconds: Int)

/** Welche Command-Ergebnisse als Hinweis (Toast) erscheinen. */
object OutcomeMessages {
    /** Ablehnungen ohne Hinweis: Spielende (der Ergebnis-Screen folgt ohnehin). */
    private val SILENT = setOf(RejectReason.GAME_OVER)

    /** Grund für einen Hinweis zu [outcome] oder `null` (angenommen, fremder Spieler, stiller Grund). */
    fun messageFor(outcome: CommandOutcome, isLocalHuman: (Int) -> Boolean): RejectReason? {
        val r = outcome.reason ?: return null
        if (!isLocalHuman(outcome.playerId)) return null
        return if (r in SILENT) null else r
    }
}

/**
 * Besitzt den **Sim-Thread** einer Partie (CLAUDE.md „Threads und Datenfluss"): fester 60-Hz-Takt über
 * [MatchRunner.advance] mit realer, begrenzter Frame-Zeit ([maxFrameSeconds], dazu die Tick-Obergrenze des Runners), die
 * Werkzeuge ([ToolController], lesen den Zustand als `GameView`) und die KI (Quellen des Runners). Eingaben kommen vom UI-Thread
 * ausschließlich über die thread-sicheren Methoden dieser Klasse ([pointer], [selectTool], [fire] …), die in einer
 * allokationsfreien [SimInbox] landen; Commands gehen nur über `runner.input` (= `LocalInputSource.push`) in die Sim.
 *
 * Ausgaben: Snapshots über den `SnapshotExchange` des Runners (Render-Thread), [toolOverlay] (Render-Thread, via
 * `OverlayBridge`), [hud] und [tools] als StateFlows für Compose (HUD mit ~10 Hz abgetastet, Werkzeugauswahl sofort),
 * [messages] (lokalisierbare Ablehnungsgründe), [end] (Partieende).
 *
 * Lebenszyklus: [start] startet den Thread, [setPaused] hält ihn an (der Thread **wartet** blockierend, keine Arbeit, keine
 * Ticks; nach dem Fortsetzen wird die Pausenzeit nicht nachgeholt), [stop] beendet ihn und wartet darauf. Ohne Thread
 * (Tests) treibt [stepOnce] die Schleife mit der injizierten [clock].
 */
class GameController(
    val session: MatchSession,
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
    /** Abtastintervall der HUD-Werte für Compose (10 Hz). */
    private val hudIntervalNanos: Long = 100_000_000L,
    /** Periode der Sim-Schleife (Ticks laufen nach Bedarf, das Alpha für den Renderer wird ~120× je s veröffentlicht). */
    private val loopPeriodNanos: Long = 8_000_000L,
    /** Größte berücksichtigte Frame-Zeit (s): längere Aussetzer werden nicht nachgeholt. */
    private val maxFrameSeconds: Float = 0.1f,
) {
    val runner: MatchRunner = session.runner
    val tools: ToolController = ToolController()
    val stats: MatchStatsCounter = MatchStatsCounter(runner.state.playerCount)

    private val inbox = SimInbox()
    private val hudBuilder = SnapshotBuilder(session.config.humanPlayerId).also { it.followActivePlayer = runner.hotseat }

    private val _hud = MutableStateFlow(hudBuilder.buildHud(runner.state))
    /** HUD-Werte, mit ~10 Hz vom Sim-Thread abgetastet. */
    val hud: StateFlow<HudModel> = _hud.asStateFlow()

    private val _tools = MutableStateFlow(ToolUiState(localPlayer = session.config.humanPlayerId))
    val toolState: StateFlow<ToolUiState> = _tools.asStateFlow()

    private val _messages = MutableSharedFlow<RejectReason>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Ablehnungsgründe der eigenen Aktionen (Werkzeug-Ablehnung oder abgelehntes Command). */
    val messages: SharedFlow<RejectReason> = _messages.asSharedFlow()

    private val _end = MutableStateFlow<MatchEnd?>(null)
    val end: StateFlow<MatchEnd?> = _end.asStateFlow()

    /** Werkzeug-Überlagerung für den Render-Thread (unveränderlich, nur bei Änderung neu). */
    @Volatile
    var toolOverlay: ToolOverlay = ToolOverlay.NONE
        private set

    /** Rückmeldung zur laufenden Geste: `gestureId * 2 + (übernommen ? 1 : 0)`, −1 = noch keine. */
    @Volatile
    private var gestureFeedback: Long = -1L

    // ---- Thread ----
    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var stopped = false
    /** Pause nach einem Renderer-Wechsel erneut übernehmen (siehe [setRendererAttached]). */
    @Volatile private var reapplyPause = false

    // ---- nur Sim-Thread (bzw. Aufrufer von stepOnce) ----
    private var lastNanos = UNSET
    private var lastHudNanos = UNSET
    private var pauseApplied = false
    private var pickRadius = 1f
    private var ctxCache: ToolContext? = null
    private var ctxPlayer = -1
    private var ctxRadius = -1f
    private var lastToolOverlay: ToolOverlay? = null
    private var toolDirty = true
    private var lastPhase: TurnPhase? = null
    private var lastActive = Int.MIN_VALUE

    /** Ausgeführte Sim-Schritte mit Arbeit (Diagnose/Tests). */
    @Volatile
    var workSteps: Long = 0L
        private set

    private val handler = InboxHandler { kind, i0, f0, f1, f2, l0, obj -> onInbox(kind, i0, f0, f1, f2, l0, obj) }

    /** Dauer des letzten Sim-Schritts mit Ticks in ms (Profiling für `FrameStats`), NaN vor dem ersten. */
    @Volatile
    var lastSimMillis: Float = Float.NaN
        private set

    init {
        // Kennzahlen auf dem Sim-Thread: je Tick Fx und Zustand (unabhängig vom Renderer)
        stats.attachTo(runner.session)
        runner.addResultListener { o ->
            stats.onOutcome(o)
            val m = OutcomeMessages.messageFor(o, session::isHuman)
            if (m != null) _messages.tryEmit(m)
        }
    }

    // =============================================================================================
    // Lebenszyklus (UI-Thread)
    // =============================================================================================

    /** Startet den Sim-Thread (einmalig; nach [stop] nicht wieder startbar). */
    fun start() {
        synchronized(lock) {
            if (thread != null || stopped) return
            running = true
            thread = Thread({ loop() }, "bollwerk-sim").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Pause an/aus (Pause-Dialog, App im Hintergrund, Hotseat-Übergabe wartet auf „Bereit"). Von jedem Thread. */
    fun setPaused(paused: Boolean) {
        runner.setPaused(paused)
        synchronized(lock) {
            this.paused = paused
            lock.notifyAll()
        }
    }

    val isPaused: Boolean get() = paused

    /**
     * Renderer an-/abhängen (App in den Hintergrund / zurück). Von jedem Thread. Abgehängt veröffentlicht der Runner keine
     * Snapshots; eine währenddessen übernommene Pause stünde sonst nie im Snapshot (Renderer und Audio sähen nach der Rückkehr
     * weiter `paused = false`, das Feuerknistern liefe im Pause-Dialog). Deshalb übernimmt der Sim-Schritt die Pause nach
     * jedem Wechsel erneut und veröffentlicht sie.
     */
    fun setRendererAttached(attached: Boolean) {
        runner.setRendererAttached(attached)
        synchronized(lock) {
            reapplyPause = true
            lock.notifyAll()
        }
    }

    /** Beendet den Sim-Thread und wartet (begrenzt) auf ihn. Idempotent. */
    fun stop() {
        val t: Thread?
        synchronized(lock) {
            stopped = true
            running = false
            t = thread
            thread = null
            lock.notifyAll()
        }
        if (t != null && t !== Thread.currentThread()) t.join(STOP_TIMEOUT_MS)
    }

    /** Läuft der Sim-Thread? */
    val isRunning: Boolean get() = synchronized(lock) { thread?.isAlive == true }

    /** Thread-Zustand (Tests: im Pausenfall `WAITING`). */
    internal val threadState: Thread.State? get() = synchronized(lock) { thread?.state }

    private fun loop() {
        while (running) {
            if (paused) {
                stepOnce() // übernimmt die Pause einmal (Snapshot + HUD mit „pausiert")
                synchronized(lock) {
                    while (paused && running && !reapplyPause) lock.wait()
                }
                continue
            }
            val begin = System.nanoTime()
            stepOnce()
            val left = loopPeriodNanos - (System.nanoTime() - begin)
            if (left > 0L) {
                // wait() gibt die Sperre frei: setPaused/stop wecken den Thread sofort
                synchronized(lock) { if (running && !paused) lock.wait(left / 1_000_000L, (left % 1_000_000L).toInt()) }
            }
        }
    }

    // =============================================================================================
    // Ein Schritt (Sim-Thread)
    // =============================================================================================

    /**
     * Ein Durchlauf der Sim-Schleife: Eingaben abarbeiten, Uhr um die reale (begrenzte) Zeit weiterschieben, Werkzeuge
     * nachführen, Overlay/HUD veröffentlichen, Partieende prüfen. Pausiert: nur einmal die Pause übernehmen, sonst nichts.
     * @return ausgeführte Sim-Ticks.
     */
    fun stepOnce(): Int {
        if (paused) {
            if (!pauseApplied || reapplyPause) {
                // Erst den Wunsch löschen, dann übernehmen: ein gleichzeitiger neuer Wechsel bleibt so nicht liegen
                reapplyPause = false
                pauseApplied = true
                runner.advance(0f)
                emitHud(clock.nanos())
            }
            lastNanos = UNSET
            return 0
        }
        pauseApplied = false
        reapplyPause = false // laufend veröffentlicht advance ohnehin (nach dem Anhängen sofort)
        workSteps++
        val now = clock.nanos()
        val dt = if (lastNanos == UNSET) 0f else ((now - lastNanos) * 1e-9).toFloat().coerceIn(0f, maxFrameSeconds)
        lastNanos = now
        inbox.drain(handler)
        val t0 = System.nanoTime()
        val n = runner.advance(dt)
        if (n > 0) {
            lastSimMillis = ((System.nanoTime() - t0) / 1000L).toFloat() / 1000f
            // Fx des letzten Ticks sofort zählen (sonst erst zu Beginn des nächsten Ticks)
            stats.sync(runner.state, runner.session.ctx.fx)
            tools.refresh(ctx())
        }
        publishTools()
        // Zugwechsel (Hotseat-Übergabe) sofort melden, damit die Übergabe die Spielfläche ohne Verzug verdeckt
        val turn = runner.state.turn
        val turnChanged = turn.phase != lastPhase || turn.activePlayer != lastActive
        if (turnChanged) { lastPhase = turn.phase; lastActive = turn.activePlayer }
        if (turnChanged || lastHudNanos == UNSET || now - lastHudNanos >= hudIntervalNanos) emitHud(now)
        checkEnd()
        return n
    }

    private fun localPlayer(): Int {
        if (session.hotseat) {
            val a = runner.state.turn.activePlayer
            return if (a >= 0) a else 0
        }
        return session.config.humanPlayerId
    }

    private fun ctx(): ToolContext {
        val p = localPlayer()
        val c = ctxCache
        if (c != null && ctxPlayer == p && ctxRadius == pickRadius) return c
        val nc = ToolContext(runner.state, p, pickRadius)
        ctxCache = nc; ctxPlayer = p; ctxRadius = pickRadius
        return nc
    }

    private fun onInbox(kind: Int, i0: Int, f0: Float, f1: Float, f2: Float, l0: Long, obj: Any?) {
        when (kind) {
            InboxKind.POINTER -> {
                if (f2 > 0f) pickRadius = f2
                val r = tools.pointer(PHASES[i0], f0, f1, ctx())
                apply(r, gesture = l0, report = i0 == PointerPhase.UP.ordinal)
            }
            InboxKind.LONG_PRESS -> {
                if (f2 > 0f) pickRadius = f2
                apply(tools.longPress(f0, f1, ctx()), gesture = l0, report = false)
            }
            InboxKind.CANCEL_GESTURE -> tools.cancel()
            InboxKind.SELECT_TOOL -> tools.selectTool(obj as ToolSelection)
            InboxKind.ENTER_AIM -> {
                tools.enterAimMode()
                if (tools.aim.selectedRef < 0L) tools.cycleWeapon(ctx())
            }
            InboxKind.FIRE -> apply(tools.fire(ctx()), gesture = -1L, report = true)
            InboxKind.UNDO -> apply(tools.undo(ctx()), gesture = -1L, report = true)
            InboxKind.SELECT_WEAPON -> tools.selectWeapon(l0, ctx())
            InboxKind.CYCLE_WEAPON -> tools.cycleWeapon(ctx(), i0)
            InboxKind.CHOOSE_CONTEXT -> apply(tools.chooseContext(TAP_ACTIONS[i0], ctx()), gesture = -1L, report = true)
            InboxKind.DISMISS_CONTEXT -> tools.dismissContext()
            InboxKind.SET_POWER -> applyPower(f0)
            InboxKind.SET_DOORS -> setDoors(i0 != 0)
            InboxKind.END_TURN -> runner.input.push(Command.EndTurn(0L, localPlayer()))
            InboxKind.RELEASE_TO_FIRE -> tools.settings.releaseToFire = i0 != 0
        }
        // Zeigerereignisse melden nur Strukturänderungen sofort (publishTools), Buttons immer
        if (kind != InboxKind.POINTER) toolDirty = true
    }

    private fun apply(r: ToolResult, gesture: Long, report: Boolean) {
        for (i in r.commands.indices) runner.input.push(r.commands[i])
        val rej = r.rejected
        if (report && rej != null) _messages.tryEmit(rej)
        if (gesture >= 0L) gestureFeedback = gesture * 2 + if (r.consumed) 1 else 0
    }

    private fun applyPower(power: Float) {
        val ref = tools.aim.selectedRef
        if (ref < 0L) return
        val view = runner.state
        val id = view.deviceView.resolve(ref)
        if (id < 0) return
        val cfg = view.simConfig
        val p = power.coerceIn(cfg.minPower, cfg.maxPower)
        runner.input.push(Command.SetAim(0L, localPlayer(), ref, view.deviceView.aimAngle(id), p))
    }

    private fun setDoors(open: Boolean) {
        val view = runner.state
        val p = localPlayer()
        val beams = view.beamView
        val mats = view.tables.materials
        for (i in 0 until beams.size) {
            if (!beams.isAlive(i) || beams.owner(i) != p) continue
            val m = beams.material(i)
            if (m < 0 || m >= mats.size || !mats[m].isDoor) continue
            val isOpen = (beams.flags(i) and BeamFlags.DOOR_OPEN) != 0
            if (isOpen != open) runner.input.push(Command.ToggleDoor(0L, p, beams.ref(i)))
        }
    }

    /** Werkzeugzustand an Render-Thread (Overlay) und Compose (Auswahl sofort, Zielwerte mit dem HUD-Takt) melden. */
    private fun publishTools() {
        val o = tools.overlay
        var emit = toolDirty
        if (o !== lastToolOverlay) {
            lastToolOverlay = o
            toolOverlay = o
            val cur = _tools.value
            // Strukturänderungen (Modus, Werkzeug, Waffe, Kontextmenü) sofort, Zielwerte im HUD-Takt
            if (cur.mode != o.mode || cur.selection != o.tool || cur.weaponRef != o.selectedDeviceRefForAim() ||
                cur.contextMenu != o.contextMenu || cur.localPlayer != o.localPlayer
            ) emit = true
        }
        if (emit) emitTools()
        toolDirty = false
    }

    private fun ToolOverlay.selectedDeviceRefForAim(): Long = if (mode == ToolMode.AIM) selectedDeviceRef else -1L

    private fun emitTools() {
        val o = tools.overlay
        val c = ctx()
        var doors = 0
        var open = 0
        val beams = runner.state.beamView
        val mats = runner.state.tables.materials
        for (i in 0 until beams.size) {
            if (!beams.isAlive(i) || beams.owner(i) != c.playerId) continue
            val m = beams.material(i)
            if (m < 0 || m >= mats.size || !mats[m].isDoor) continue
            doors++
            if ((beams.flags(i) and BeamFlags.DOOR_OPEN) != 0) open++
        }
        _tools.value = ToolUiState(
            mode = o.mode,
            selection = o.tool,
            weaponRef = o.selectedDeviceRefForAim(),
            aim = o.aim,
            contextMenu = o.contextMenu,
            previewRejected = o.rejected,
            canUndo = tools.undoPreview(c).canUndo,
            doorCount = doors,
            doorsOpen = open,
            localPlayer = c.playerId,
        )
    }

    private fun emitHud(now: Long) {
        lastHudNanos = now
        hudBuilder.paused = runner.paused
        hudBuilder.speed = runner.speed
        _hud.value = hudBuilder.buildHud(runner.state)
        emitTools()
    }

    private fun checkEnd() {
        if (_end.value != null) return
        val r = runner.result
        if (r == GameResult.Ongoing) return
        val tick = runner.tick
        _end.value = MatchEnd(r, tick, (tick * runner.state.config.dt).toInt())
    }

    /** Spielzeit in ganzen Sekunden (für Aufgeben/Bericht); Sim-Thread oder bei angehaltener Sim. */
    val durationSeconds: Int get() = (_hud.value.timeSeconds).toInt()

    // =============================================================================================
    // Eingänge (UI-Thread, thread-sicher)
    // =============================================================================================

    /** Zeigerereignis in Welt-Metern; [pickRadiusM] = Fangradius beim aktuellen Zoom; [gestureId] je Geste eindeutig. */
    fun pointer(phase: PointerPhase, worldX: Float, worldY: Float, pickRadiusM: Float, gestureId: Long): Boolean =
        inbox.offer(InboxKind.POINTER, phase.ordinal, worldX, worldY, pickRadiusM, gestureId)

    fun longPress(worldX: Float, worldY: Float, pickRadiusM: Float, gestureId: Long): Boolean =
        inbox.offer(InboxKind.LONG_PRESS, 0, worldX, worldY, pickRadiusM, gestureId)

    /** Bricht die laufende Werkzeug-Geste ab (zweiter Finger). */
    fun cancelGesture(): Boolean = inbox.offer(InboxKind.CANCEL_GESTURE)

    /**
     * Hat das Werkzeug die Geste [gestureId] übernommen? `null`, solange der Sim-Thread sie noch nicht verarbeitet hat.
     * `false` erlaubt dem Eingabe-Controller, die Geste als Kamera-Schwenk zu behandeln.
     */
    fun gestureConsumed(gestureId: Long): Boolean? {
        val f = gestureFeedback
        if (f < 0L || f / 2 != gestureId) return null
        return f % 2 == 1L
    }

    fun selectTool(selection: ToolSelection) { inbox.offer(InboxKind.SELECT_TOOL, obj = selection) }
    fun enterAimMode() { inbox.offer(InboxKind.ENTER_AIM) }
    fun fire() { inbox.offer(InboxKind.FIRE) }
    fun undo() { inbox.offer(InboxKind.UNDO) }
    fun selectWeapon(ref: Long) { inbox.offer(InboxKind.SELECT_WEAPON, l0 = ref) }
    fun cycleWeapon(step: Int = 1) { inbox.offer(InboxKind.CYCLE_WEAPON, i0 = step) }
    fun chooseContext(action: TapAction) { inbox.offer(InboxKind.CHOOSE_CONTEXT, i0 = action.ordinal) }
    fun dismissContext() { inbox.offer(InboxKind.DISMISS_CONTEXT) }
    /** Kraft der gewählten Waffe (0..1, auf `minPower..maxPower` geklemmt) beim aktuellen Zielwinkel. */
    fun setPower(power: Float) { inbox.offer(InboxKind.SET_POWER, f0 = power) }
    /** Alle eigenen Türen öffnen bzw. schließen. */
    fun setDoorsOpen(open: Boolean) { inbox.offer(InboxKind.SET_DOORS, i0 = if (open) 1 else 0) }
    fun endTurn() { inbox.offer(InboxKind.END_TURN) }
    fun setReleaseToFire(enabled: Boolean) { inbox.offer(InboxKind.RELEASE_TO_FIRE, i0 = if (enabled) 1 else 0) }

    /** Wartende Eingaben (Tests). */
    internal val pendingInputs: Int get() = inbox.pending

    private companion object {
        const val UNSET = Long.MIN_VALUE
        const val STOP_TIMEOUT_MS = 2000L
        val PHASES = PointerPhase.entries.toTypedArray()
        val TAP_ACTIONS = TapAction.entries.toTypedArray()
    }
}
