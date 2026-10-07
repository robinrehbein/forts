package de.bollwerk.app.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bollwerk.app.game.GameController
import de.bollwerk.app.game.GameRuntime
import de.bollwerk.app.game.HudPresenter
import de.bollwerk.app.game.HudUiState
import de.bollwerk.app.game.MatchResults
import de.bollwerk.app.game.ToolUiState
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.MatchResult
import de.bollwerk.app.nav.Navigator
import de.bollwerk.app.nav.Screen
import de.bollwerk.app.settings.AppSettings
import de.bollwerk.app.settings.GestureTip
import de.bollwerk.app.settings.GestureTips
import de.bollwerk.app.tutorial.TutorialCoach
import de.bollwerk.app.tutorial.TutorialDriver
import de.bollwerk.app.tutorial.TutorialEffects
import de.bollwerk.app.tutorial.TutorialUiModel
import de.bollwerk.app.ui.game.hud.HudActions
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.app.tutorial.TutorialStatus
import de.bollwerk.engine.tools.TapAction
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.engine.view.HudModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bestätigungspflichtige Aktionen im Pause-Dialog. */
enum class PauseAction { RESTART, SURRENDER, MAIN_MENU }

/**
 * Zustand der Hotseat-Übergabe (Mockup 6). Die Übergabe bleibt verdeckt (und die Sim angehalten), bis der nächste Spieler
 * „Bereit" tippt; erst dann läuft der Start-Countdown der Engine (`HudModel.handoverCountdown`). [secondsLeft] ist `null`,
 * solange noch auf „Bereit" gewartet wird.
 */
data class HandoverState(
    val player: Int,
    val turn: Int,
    val secondsLeft: Int?,
    val totalSeconds: Int,
) {
    val isCounting: Boolean get() = secondsLeft != null
}

/** Overlays über der Spielfläche. Pause, Einstellungen und Bestätigung halten die Simulation an. */
sealed interface GameOverlay {
    data object None : GameOverlay
    data object Pause : GameOverlay
    data object Settings : GameOverlay
    data class Confirm(val action: PauseAction) : GameOverlay
    data class Handover(val state: HandoverState) : GameOverlay
}

/** Kurzer Hinweis (Ablehnungsgrund) über dem HUD; [seq] unterscheidet gleiche Gründe nacheinander. */
data class GameToast(val reason: RejectReason, val seq: Int)

data class GameUiState(
    val config: MatchConfig,
    val overlay: GameOverlay = GameOverlay.None,
    /** Aktiver Spieler (ID): im Hotseat der Spieler am Zug (beginnt mit 0), gegen KI immer der Mensch. */
    val activePlayer: Int = config.humanPlayerId,
    val turn: Int = 1,
    /** Erhöht sich bei jedem Neustart; die Spielfläche wird dann neu aufgebaut. */
    val matchGeneration: Int = 0,
    /** Techbaum-Sheet offen (Mockup 5). */
    val techTreeOpen: Boolean = false,
    /** Die Partie wird gerade angelegt (Content, Einschwingen). */
    val loading: Boolean = true,
) {
    /** Ein Overlay hält die Simulation an (Ausnahme: laufender Übergabe-Countdown). */
    val isSuspended: Boolean
        get() = when (overlay) {
            GameOverlay.None -> false
            is GameOverlay.Handover -> !overlay.state.isCounting
            else -> true
        }

    /** Die Spielfläche darf bei der Übergabe nicht sichtbar sein. */
    val boardHidden: Boolean get() = overlay is GameOverlay.Handover
}

/** Ablage des Tutorial-Ergebnisses (DataStore): [completed] = alle drei Schritte gespielt, sonst übersprungen. */
fun interface TutorialStore {
    suspend fun finished(completed: Boolean)
}

/** Legt eine Partie an (teuer: Content, Einschwingen; läuft auf dem [GameViewModel.workDispatcher]). */
fun interface GameRuntimeFactory {
    fun create(config: MatchConfig): GameRuntime
}

/**
 * Spielfluss um die Spielfläche: Partie anlegen (über [runtimeFactory] → `MatchSessions`/`MatchBootstrap`), Pause und
 * Lebenszyklus (Hintergrund = Pause, die Sim arbeitet dann nicht), Neustart, Aufgeben, Hotseat-Übergabe (aus dem
 * Zugzustand des HUD) und Übergang zum Ergebnis mit echten Kennzahlen.
 *
 * HUD und Werkzeugzustand kommen als StateFlows vom Sim-Thread (HUD ~10 Hz) und werden hier mit dem [HudPresenter] zu
 * [hud] zusammengeführt. Alle Eingaben gehen über den `GameController` (thread-sicher) an die Sim.
 *
 * [entryId] ist der Navigationseintrag dieses Spiels; Rückmeldungen (Partieende, Hauptmenü) wirken nur, solange
 * dieser Eintrag noch oben liegt.
 */
class GameViewModel(
    config: MatchConfig,
    private val navigator: Navigator,
    private val runtimeFactory: GameRuntimeFactory? = null,
    settings: Flow<AppSettings> = emptyFlow(),
    private val endDelayMillis: Long = END_DELAY_MS,
    private val toastMillis: Long = TOAST_MS,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val decimalSeparator: Char = ',',
    private val entryId: Long = navigator.current.id,
    private val tutorialStore: TutorialStore? = null,
    /** Bereits gezeigte Gesten-Hinweise (DataStore); ohne Quelle erscheinen keine Hinweise. */
    gestureTips: Flow<GestureTips> = emptyFlow(),
    /** Merkt einen gezeigten Hinweis dauerhaft (DataStore). */
    private val onTipSeen: (suspend (GestureTip) -> Unit)? = null,
    private val tipDelayMillis: Long = TIP_DELAY_MS,
    private val tipMillis: Long = TIP_MS,
) : ViewModel(), HudActions {
    private val _state = MutableStateFlow(GameUiState(config, loading = runtimeFactory != null))
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    private val _runtime = MutableStateFlow<GameRuntime?>(null)
    /** Laufende Partie (null während des Anlegens). */
    val runtime: StateFlow<GameRuntime?> = _runtime.asStateFlow()

    private val _hud = MutableStateFlow(HudUiState(hotseat = config.mode == GameMode.HOTSEAT))
    /** Fertig aufbereitete HUD-Werte für Compose. */
    val hud: StateFlow<HudUiState> = _hud.asStateFlow()

    private val _tutorial = MutableStateFlow<TutorialUiModel?>(null)
    /** Coach-Mark des Tutorial-Gefechts (null in normalen Partien und solange die Partie angelegt wird). */
    val tutorial: StateFlow<TutorialUiModel?> = _tutorial.asStateFlow()

    private var coach: TutorialCoach? = null

    private val _tip = MutableStateFlow<GestureTip?>(null)
    /** Aktueller einmaliger Gesten-Hinweis (nicht blockierender Chip), `null` = keiner. */
    val tip: StateFlow<GestureTip?> = _tip.asStateFlow()

    private var storedTips = GestureTips()
    private var tipsLoaded = false
    private var tipJob: Job? = null
    /** Restzeit bis der nächste Hinweis erscheint bzw. bis er sich ausblendet; läuft nur, solange der Chip sichtbar sein kann. */
    private var tipWaitLeft = tipDelayMillis
    private var tipShowLeft = tipMillis

    private val _toast = MutableStateFlow<GameToast?>(null)
    val toast: StateFlow<GameToast?> = _toast.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Läuft die Simulation gerade (für die Spielfläche: Render-Pause, Audio)? */
    private val _simRunning = MutableStateFlow(false)
    val simRunning: StateFlow<Boolean> = _simRunning.asStateFlow()

    private var backgrounded = false
    private var finished = false
    private var runtimeJobs: List<Job> = emptyList()
    private var toastJob: Job? = null
    private var toastSeq = 0
    /** Letzter HUD-Stand der Sim (Übergabe-Countdown, Befehlsrecht); null vor dem ersten. */
    private var lastHud: HudModel? = null

    init {
        viewModelScope.launch {
            gestureTips.collect {
                val merged = GestureTips(storedTips.seenMask or it.seenMask)
                val changed = merged != storedTips || !tipsLoaded
                storedTips = merged
                tipsLoaded = true
                if (changed) syncTip()
            }
        }
        // Der Hinweis-Countdown läuft nur, solange der Chip wirklich zu sehen sein kann (siehe tipCanRun)
        viewModelScope.launch {
            combine(_runtime, _state, _hud, _tutorial, combine(_simRunning, _toast) { r, t -> r to t }) { _, _, _, _, _ -> tipCanRun() }
                .distinctUntilChanged()
                .collect { syncTip() }
        }
        viewModelScope.launch {
            settings.collect { s ->
                _settings.value = s
                _runtime.value?.controller?.setReleaseToFire(s.releaseToFire)
            }
        }
        startRuntime()
    }

    // =============================================================================================
    // Partie anlegen / abbauen
    // =============================================================================================

    private fun startRuntime() {
        val factory = runtimeFactory ?: return
        val generation = _state.value.matchGeneration
        val config = _state.value.config
        _state.update { it.copy(loading = true) }
        val job = viewModelScope.launch {
            val rt = withContext(workDispatcher) { factory.create(config) }
            if (_state.value.matchGeneration != generation || finished) {
                rt.stop()
                return@launch
            }
            rt.controller.setReleaseToFire(_settings.value.releaseToFire)
            _runtime.value = rt
            _state.update { it.copy(loading = false) }
            syncPause()
            rt.start()
            observe(rt)
        }
        runtimeJobs = listOf(job)
    }

    private fun observe(rt: GameRuntime) {
        val c = rt.controller
        val hotseat = c.session.hotseat
        val jobs = ArrayList<Job>(runtimeJobs)
        val coach = rt.session.tutorial?.let { createCoach(rt, it) }
        this.coach = coach
        if (coach != null) {
            jobs += viewModelScope.launch { coach.model.collect { _tutorial.value = it } }
            jobs += viewModelScope.launch { rt.session.tutorial?.signals?.collect { coach.onSignal(it) } }
        }
        jobs += viewModelScope.launch {
            combine(c.hud, c.toolState) { h, t -> h to t }.collect { (h, t) ->
                val ui = HudPresenter.present(h, t, c.session.catalog, hotseat, decimalSeparator)
                _hud.value = ui
                coach?.onHud(ui, h.weapons)
                onHud(h)
            }
        }
        jobs += viewModelScope.launch { c.messages.collect { showToast(it) } }
        jobs += viewModelScope.launch {
            val end = c.end.filterNotNull().first()
            delay(endDelayMillis)
            val result = MatchResults.from(_state.value.config, end.result) { p -> c.stats.statsFor(p, end.durationSeconds) }
            if (result != null) finishMatch(result)
        }
        runtimeJobs = jobs
    }

    private fun createCoach(rt: GameRuntime, driver: TutorialDriver) = TutorialCoach(
        ids = driver.ids,
        targets = driver.targets,
        effects = object : TutorialEffects {
            override fun selectWeapon(ref: Long) = rt.controller.selectWeapon(ref)
            override fun openGates() = driver.openGates()
            override fun finished(completed: Boolean) {
                viewModelScope.launch { tutorialStore?.finished(completed) }
            }
        },
    )

    private fun stopRuntime() {
        runtimeJobs.forEach { it.cancel() }
        runtimeJobs = emptyList()
        coach = null
        _tutorial.value = null
        _runtime.value?.stop()
        _runtime.value = null
        resetTip()
    }

    // =============================================================================================
    // Einmalige Gesten-Hinweise
    // =============================================================================================

    /**
     * Darf der Hinweis-Countdown laufen? Nur wenn der Chip wirklich zu sehen ist: normale Partie (im Tutorial-Gefecht erst nach
     * Überspringen/Schließen), App im Vordergrund, kein Overlay (Pause, Einstellungen, Hotseat-Übergabe), kein Techbaum, kein
     * Kontextmenü, kein Toast, nicht im Zielmodus. Sonst steht die Restzeit still und der Hinweis zählt nicht als gesehen.
     */
    private fun tipCanRun(): Boolean {
        val rt = _runtime.value ?: return false
        val s = _state.value
        val tutorialActive = rt.session.tutorial != null &&
            _tutorial.value?.state?.status.let { it == null || it == TutorialStatus.RUNNING || it == TutorialStatus.COMPLETED }
        val hud = _hud.value
        return tipsLoaded && !finished && !tutorialActive && _simRunning.value && !backgrounded && !s.loading &&
            s.overlay == GameOverlay.None && !s.techTreeOpen && hud.contextMenu == null && hud.mode != ToolMode.AIM && _toast.value == null
    }

    /** Hinweis-Aufgabe neu aufsetzen: läuft nur, wenn der Countdown laufen darf und noch ein Hinweis aussteht. */
    private fun syncTip() {
        tipJob?.cancel()
        tipJob = null
        if (!tipCanRun()) return
        val next = _tip.value ?: storedTips.next() ?: return
        tipJob = viewModelScope.launch {
            if (_tip.value == null) {
                tipWaitLeft = countDown(tipWaitLeft)
                _tip.value = next
            }
            tipShowLeft = countDown(tipShowLeft)
            dismissTip()
        }
    }

    /** Zählt [left] in kurzen Schritten herunter und gibt den Rest zurück (bei Abbruch bleibt der Rest in der Variablen erhalten). */
    private suspend fun countDown(left: Long): Long {
        var rest = left
        while (rest > 0) {
            val step = minOf(TIP_TICK_MS, rest)
            delay(step)
            rest -= step
            if (_tip.value == null) tipWaitLeft = rest else tipShowLeft = rest
        }
        return 0
    }

    private fun resetTip() {
        tipJob?.cancel()
        tipJob = null
        _tip.value = null
        tipWaitLeft = tipDelayMillis
        tipShowLeft = tipMillis
    }

    /** Hinweis weggetippt oder abgelaufen: dauerhaft als gesehen merken, der nächste folgt nach einer Pause. */
    fun dismissTip() {
        val t = _tip.value ?: return
        resetTip()
        storedTips = storedTips.markSeen(t)
        viewModelScope.launch { onTipSeen?.invoke(t) }
        syncTip()
    }

    // =============================================================================================
    // Pause und Lebenszyklus
    // =============================================================================================

    /** Sim läuft genau dann, wenn kein anhaltendes Overlay offen ist und die App im Vordergrund ist. */
    private fun syncPause() {
        val run = !backgrounded && !_state.value.isSuspended && !_state.value.loading
        _simRunning.value = run
        _runtime.value?.controller?.setPaused(!run)
    }

    private inline fun updateState(f: (GameUiState) -> GameUiState) {
        _state.update(f)
        syncPause()
    }

    override fun pause() = updateState { if (it.overlay == GameOverlay.None) it.copy(overlay = GameOverlay.Pause, techTreeOpen = false) else it }
    fun resume() = updateState { if (it.overlay == GameOverlay.Pause) it.copy(overlay = GameOverlay.None) else it }
    fun openSettings() = updateState { if (it.overlay == GameOverlay.Pause) it.copy(overlay = GameOverlay.Settings) else it }
    fun closeSettings() = updateState { if (it.overlay == GameOverlay.Settings) it.copy(overlay = GameOverlay.Pause) else it }
    fun requestConfirm(action: PauseAction) =
        updateState { if (it.overlay == GameOverlay.Pause) it.copy(overlay = GameOverlay.Confirm(action)) else it }
    fun cancelConfirm() = updateState { if (it.overlay is GameOverlay.Confirm) it.copy(overlay = GameOverlay.Pause) else it }

    /** Systemrücktaste: Sheet schließen, im Spiel pausieren, in Overlays eine Ebene zurück, bei der Übergabe nichts. */
    fun onSystemBack() {
        if (_state.value.techTreeOpen) {
            closeTechTree()
            return
        }
        when (_state.value.overlay) {
            GameOverlay.None -> pause()
            GameOverlay.Pause -> resume()
            GameOverlay.Settings -> closeSettings()
            is GameOverlay.Confirm -> cancelConfirm()
            is GameOverlay.Handover -> Unit
        }
    }

    fun confirm() {
        val overlay = _state.value.overlay as? GameOverlay.Confirm ?: return
        when (overlay.action) {
            PauseAction.RESTART -> restart()
            PauseAction.SURRENDER -> {
                val s = _state.value
                // Gegen KI gibt immer der Mensch auf (egal ob Blau oder Rot), im Hotseat der Spieler am Zug.
                val loser = if (s.config.mode == GameMode.VS_AI) s.config.humanPlayerId else s.activePlayer
                val rt = _runtime.value
                val duration = rt?.controller?.durationSeconds ?: 0
                val base = MatchResult.surrender(s.config, loserPlayerId = loser)
                val stats = rt?.controller?.stats?.statsFor(base.bannerPlayerId, duration)
                finishMatch(if (stats != null) base.copy(stats = stats) else base)
            }
            PauseAction.MAIN_MENU -> {
                stopRuntime()
                navigator.popToRoot()
            }
        }
    }

    private fun restart() {
        stopRuntime()
        _state.update {
            GameUiState(it.config, matchGeneration = it.matchGeneration + 1, loading = runtimeFactory != null)
        }
        _hud.value = HudUiState(hotseat = _state.value.config.mode == GameMode.HOTSEAT)
        syncPause()
        startRuntime()
    }

    /**
     * Die App verlässt den Vordergrund (Home, Anruf, Benachrichtigungen): Sim anhalten, ein laufendes Spiel öffnet die Pause;
     * in der Hotseat-Übergabe bleibt die Spielfläche verdeckt und ein laufender Start-Countdown wird abgebrochen (der Spieler
     * muss erneut „Bereit" tippen).
     */
    fun onAppBackgrounded() {
        backgrounded = true
        // Renderer abgehängt: die Sim sammelt keine Fx/Ergebnisse für einen Leser an, der nicht zeichnet
        _runtime.value?.controller?.setRendererAttached(false)
        when (val overlay = _state.value.overlay) {
            GameOverlay.None -> pause()
            is GameOverlay.Handover -> if (overlay.state.isCounting) setHandover(overlay.state.copy(secondsLeft = null))
            else -> Unit
        }
        syncPause()
    }

    /** Zurück im Vordergrund: die Sim bleibt angehalten, solange ein Overlay (Pause) offen ist. */
    fun onAppForegrounded() {
        backgrounded = false
        _runtime.value?.controller?.setRendererAttached(true)
        syncPause()
    }

    // =============================================================================================
    // Hotseat
    // =============================================================================================

    /** Zug beenden (Hotseat): `Command.EndTurn` über die lokale Eingabe; die Übergabe folgt aus dem Zugzustand. */
    override fun endTurn() {
        val s = _state.value
        if (s.config.mode != GameMode.HOTSEAT || s.overlay != GameOverlay.None) return
        commanding?.endTurn()
    }

    /**
     * Abgleich mit dem Zugzustand der Sim (nur Hotseat): Phase HANDOVER öffnet die Übergabe für den neuen aktiven Spieler
     * (Sim hält an, bis „Bereit"); während des Countdowns folgt die Zahl `handoverCountdown`; die Spielphase schließt sie.
     */
    fun onHud(h: HudModel) {
        lastHud = h
        val s = _state.value
        if (s.config.mode != GameMode.HOTSEAT || h.turnMode != TurnMode.TURNS || h.activePlayer < 0) return
        val open = s.overlay as? GameOverlay.Handover
        if (h.handover) {
            if (open == null || open.state.player != h.activePlayer || open.state.turn != h.turnNumber) {
                if (s.overlay == GameOverlay.None || open != null) beginHandover(h.activePlayer, h.turnNumber)
            } else if (open.state.isCounting && h.handoverCountdown > 0 && h.handoverCountdown != open.state.secondsLeft) {
                setHandover(open.state.copy(secondsLeft = h.handoverCountdown))
            }
        } else {
            if (open != null && open.state.isCounting) {
                updateState { it.copy(overlay = GameOverlay.None, activePlayer = h.activePlayer, turn = h.turnNumber) }
            } else if (open == null && (s.activePlayer != h.activePlayer || s.turn != h.turnNumber)) {
                _state.update { it.copy(activePlayer = h.activePlayer, turn = h.turnNumber) }
            }
        }
    }

    private fun beginHandover(player: Int, turn: Int) {
        updateState {
            it.copy(
                activePlayer = player,
                turn = turn,
                techTreeOpen = false,
                overlay = GameOverlay.Handover(HandoverState(player, turn, secondsLeft = null, totalSeconds = handoverTotalSeconds())),
            )
        }
    }

    private fun setHandover(h: HandoverState) {
        updateState { st -> if (st.overlay is GameOverlay.Handover) st.copy(overlay = GameOverlay.Handover(h)) else st }
    }

    /**
     * „Bereit": die Sim läuft weiter, der Countdown der Engine zählt 3-2-1, danach gibt die Spielphase die Fläche frei.
     * Die Startzahl ist der Rest der Engine (`HudModel.handoverCountdown`; nach abgebrochenem Countdown z. B. 1), sonst die
     * volle Übergabezeit.
     */
    fun onHandoverReady() {
        val open = _state.value.overlay as? GameOverlay.Handover ?: return
        if (open.state.isCounting) return
        val h = lastHud
        val left = if (h != null && h.handover && h.activePlayer == open.state.player && h.handoverCountdown > 0) {
            h.handoverCountdown
        } else {
            open.state.totalSeconds
        }
        setHandover(open.state.copy(secondsLeft = left.coerceAtLeast(1)))
    }

    /** Volle Übergabezeit in s aus der Sim-Konfiguration der Partie (`turn.handoverTicks · dt`), nicht als App-Konstante. */
    private fun handoverTotalSeconds(): Int {
        val cfg = _runtime.value?.controller?.runner?.state?.config ?: SimConfig.DEFAULT
        return handoverSecondsOf(cfg)
    }

    // =============================================================================================
    // HUD-Aktionen (UI-Thread → GameController)
    // =============================================================================================

    private val controller get() = _runtime.value?.controller

    /**
     * Werkzeug und Befehle nur mit Befehlsrecht (`HudModel.canCommand`: Spiel läuft; im Hotseat eigener Zug in der
     * Spielphase). In der Auflösungs-/Übergabephase oder nach Spielende sendet das HUD nichts (die Leisten sind ausgegraut).
     */
    private val commanding: GameController? get() = controller?.takeIf { _hud.value.canCommand }

    override fun selectTool(selection: ToolSelection) { commanding?.selectTool(selection) }
    override fun enterAimMode() { commanding?.enterAimMode() }
    override fun enterBuildMode() { commanding?.selectTool(ToolSelection.None) }
    override fun fire() { commanding?.fire() }
    override fun undo() { commanding?.undo() }
    override fun cycleWeapon() { commanding?.cycleWeapon(1) }
    override fun setPower(power: Float) { commanding?.setPower(power) }
    override fun setDoorsOpen(open: Boolean) { commanding?.setDoorsOpen(open) }
    override fun chooseContext(action: TapAction) {
        val c = commanding
        if (c != null) c.chooseContext(action) else controller?.dismissContext()
    }
    override fun dismissContext() { controller?.dismissContext() }

    /** „Überspringen" im Coach-Mark: das Tutorial endet, die Partie läuft als normales Gefecht weiter. */
    fun skipTutorial() { coach?.skip() }

    /** Abschlusskarte bestätigen. */
    fun closeTutorial() { coach?.close() }

    override fun openTechTree() = _state.update { if (it.overlay == GameOverlay.None) it.copy(techTreeOpen = true) else it }
    override fun closeTechTree() = _state.update { it.copy(techTreeOpen = false) }

    /** „Bauen" im Techbaum: Sheet schließen, Geräte-Werkzeug für das Gebäude wählen (Platzieren wie jedes Gerät). */
    override fun buildTech(deviceIndex: Int) {
        closeTechTree()
        selectTool(ToolSelection.Device(deviceIndex))
    }

    private fun showToast(reason: RejectReason) {
        toastJob?.cancel()
        _toast.value = GameToast(reason, ++toastSeq)
        toastJob = viewModelScope.launch {
            delay(toastMillis)
            _toast.value = null
        }
    }

    // =============================================================================================
    // Ende
    // =============================================================================================

    /** Partieende (Sim-Ergebnis, Aufgeben); wirkt nur, solange dieses Spiel oben liegt. */
    fun finishMatch(result: MatchResult) {
        if (finished) return
        if (navigator.replaceTop(Screen.Result(result), from = entryId)) {
            finished = true
            stopRuntime()
        }
    }

    /** Werkzeugzustand (Tests/Vorschau). */
    val toolState: ToolUiState? get() = controller?.toolState?.value

    override fun onCleared() {
        stopRuntime()
    }

    companion object {
        /** Übergabe-Countdown in ganzen Sekunden (aufgerundet, Float-Toleranz: 180 · (1/60 f) ist 3,0000002). */
        fun handoverSecondsOf(cfg: SimConfig): Int {
            val s = cfg.turn.handoverTicks * cfg.dt
            val i = s.toInt()
            return (if (s - i > 1e-3f) i + 1 else i).coerceAtLeast(1)
        }

        /** Nach dem Sieg/Niederlage-Moment noch kurz die Explosion zeigen. */
        const val END_DELAY_MS = 1800L
        const val TOAST_MS = 2200L
        const val TIP_TICK_MS = 100L
        const val TIP_DELAY_MS = 2500L
        const val TIP_MS = 12_000L
    }
}
