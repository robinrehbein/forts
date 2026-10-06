package de.bollwerk.app.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bollwerk.app.match.EndReason
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.MatchResult
import de.bollwerk.app.match.MatchStats
import de.bollwerk.app.nav.Navigator
import de.bollwerk.app.nav.Screen
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.TurnView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Bestätigungspflichtige Aktionen im Pause-Dialog. */
enum class PauseAction { RESTART, SURRENDER, MAIN_MENU }

/**
 * Zustand der Hotseat-Übergabe (Mockup 6). Die Übergabe bleibt verdeckt, bis der nächste Spieler „Bereit" tippt;
 * erst danach läuft der Start-Countdown. [secondsLeft] ist `null`, solange noch auf „Bereit" gewartet wird.
 */
data class HandoverState(
    val player: Int,
    val turn: Int,
    val secondsLeft: Int?,
    val totalSeconds: Int,
) {
    val isCounting: Boolean get() = secondsLeft != null
}

/** Overlays über der Spielfläche. Solange eines aktiv ist, ruht die Simulation ([GameUiState.isSuspended]). */
sealed interface GameOverlay {
    data object None : GameOverlay
    data object Pause : GameOverlay
    data object Settings : GameOverlay
    data class Confirm(val action: PauseAction) : GameOverlay
    data class Handover(val state: HandoverState) : GameOverlay
}

data class GameUiState(
    val config: MatchConfig,
    val overlay: GameOverlay = GameOverlay.None,
    /** Aktiver Spieler (ID): im Hotseat der Spieler am Zug (beginnt mit 0), gegen KI immer der Mensch. */
    val activePlayer: Int = config.humanPlayerId,
    val turn: Int = 1,
    /** Erhöht sich bei jedem Neustart; WP9 baut dann Session und Surface neu auf. */
    val matchGeneration: Int = 0,
) {
    val isSuspended: Boolean get() = overlay != GameOverlay.None
    /** Die Spielfläche darf bei der Übergabe nicht sichtbar sein. */
    val boardHidden: Boolean get() = overlay is GameOverlay.Handover
}

/**
 * Spielfluss um die Spielfläche: Pause, Neustart, Aufgeben, Hotseat-Übergabe und Übergang zum Ergebnis.
 * Die eigentliche Spiellogik (Session, Eingabe, HUD) hängt WP9 an dieses ViewModel.
 *
 * Zugzustand: Quelle der Wahrheit ist später der [TurnView] der Session (Phase, aktiver Spieler, Zugnummer, Restzeit).
 * WP9 meldet ihn über [onTurnView]; `phase == HANDOVER` öffnet die Übergabe. [endTurn] ist bis dahin nur der
 * Platzhalter-Weg: WP9 ersetzt ihn durch `Command.EndTurn` über `LocalInputSource.push`, und die Übergabe folgt dann
 * allein aus dem [TurnView] (das Zeitlimit des Zugs läuft in der Engine, nicht hier).
 *
 * [entryId] ist der Navigationseintrag dieses Spiels; Rückmeldungen (Partieende, Hauptmenü) wirken nur, solange
 * dieser Eintrag noch oben liegt.
 */
class GameViewModel(
    config: MatchConfig,
    private val navigator: Navigator,
    private val handoverSeconds: Int = HANDOVER_SECONDS,
    private val tickMillis: Long = 1000L,
    private val entryId: Long = navigator.current.id,
) : ViewModel() {
    private val _state = MutableStateFlow(GameUiState(config))
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    @Volatile
    private var countdown: Job? = null

    fun pause() = _state.update { if (it.overlay == GameOverlay.None) it.copy(overlay = GameOverlay.Pause) else it }
    fun resume() = _state.update { if (it.overlay == GameOverlay.Pause) it.copy(overlay = GameOverlay.None) else it }
    fun openSettings() = _state.update { if (it.overlay == GameOverlay.Pause) it.copy(overlay = GameOverlay.Settings) else it }
    fun closeSettings() = _state.update { if (it.overlay == GameOverlay.Settings) it.copy(overlay = GameOverlay.Pause) else it }
    fun requestConfirm(action: PauseAction) =
        _state.update { if (it.overlay == GameOverlay.Pause) it.copy(overlay = GameOverlay.Confirm(action)) else it }
    fun cancelConfirm() = _state.update { if (it.overlay is GameOverlay.Confirm) it.copy(overlay = GameOverlay.Pause) else it }

    /** Systemrücktaste: im Spiel pausieren, in Overlays eine Ebene zurück, bei der Übergabe nichts. */
    fun onSystemBack() {
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
                finishMatch(MatchResult.surrender(s.config, loserPlayerId = loser))
            }
            PauseAction.MAIN_MENU -> {
                countdown?.cancel()
                navigator.popToRoot()
            }
        }
    }

    private fun restart() {
        countdown?.cancel()
        _state.update {
            GameUiState(it.config, matchGeneration = it.matchGeneration + 1)
        }
    }

    /**
     * Platzhalter (bis WP9): beendet den Zug und übergibt an den anderen Spieler. Später sendet dieser Aufruf nur
     * `Command.EndTurn`; die Übergabe öffnet dann [onTurnView].
     */
    fun endTurn() {
        val s = _state.value
        if (s.config.mode != GameMode.HOTSEAT || s.overlay != GameOverlay.None) return
        beginHandover(player = 1 - s.activePlayer, turn = s.turn + 1)
    }

    /**
     * Abgleich mit dem Zugzustand der Session (nur Hotseat). Eine Übergabe-Phase öffnet die Übergabe für den
     * angegebenen Spieler, sonst werden aktiver Spieler und Zugnummer übernommen.
     */
    fun onTurnView(view: TurnView) {
        val s = _state.value
        if (s.config.mode != GameMode.HOTSEAT || view.mode != TurnMode.TURNS || view.activePlayer < 0) return
        if (view.phase == TurnPhase.HANDOVER) {
            val open = s.overlay as? GameOverlay.Handover
            if (open == null || open.state.player != view.activePlayer || open.state.turn != view.turnNumber) {
                beginHandover(view.activePlayer, view.turnNumber)
            }
        } else {
            _state.update { it.copy(activePlayer = view.activePlayer, turn = view.turnNumber) }
        }
    }

    private fun beginHandover(player: Int, turn: Int) {
        countdown?.cancel()
        _state.update {
            it.copy(
                activePlayer = player,
                turn = turn,
                overlay = GameOverlay.Handover(HandoverState(player, turn, secondsLeft = null, totalSeconds = handoverSeconds)),
            )
        }
    }

    /**
     * „Bereit": erst jetzt läuft der Start-Countdown (Spielfläche bleibt verdeckt); bei 0 wird sie freigegeben.
     * Ohne Eingabe bleibt die Übergabe beliebig lange stehen.
     */
    fun onHandoverReady() {
        val open = _state.value.overlay as? GameOverlay.Handover ?: return
        if (open.state.isCounting) return
        if (handoverSeconds <= 0) {
            finishHandover()
            return
        }
        setCountdown(handoverSeconds)
        countdown?.cancel()
        countdown = viewModelScope.launch {
            var left = handoverSeconds
            while (left > 0) {
                delay(tickMillis)
                left--
                if (left > 0) setCountdown(left)
            }
            finishHandover()
        }
    }

    private fun setCountdown(seconds: Int?) {
        _state.update { st ->
            val o = st.overlay
            if (o is GameOverlay.Handover) st.copy(overlay = GameOverlay.Handover(o.state.copy(secondsLeft = seconds))) else st
        }
    }

    private fun finishHandover() {
        countdown?.cancel()
        _state.update { if (it.overlay is GameOverlay.Handover) it.copy(overlay = GameOverlay.None) else it }
    }

    /**
     * Die App verlässt den Vordergrund (Home, Anruf, Benachrichtigungen): ein laufendes Spiel pausiert; in der
     * Hotseat-Übergabe bleibt die Spielfläche verdeckt und ein laufender Start-Countdown wird abgebrochen
     * (der Spieler muss erneut „Bereit" tippen).
     */
    fun onAppBackgrounded() {
        when (val overlay = _state.value.overlay) {
            GameOverlay.None -> pause()
            is GameOverlay.Handover -> if (overlay.state.isCounting) {
                countdown?.cancel()
                setCountdown(null)
            }
            else -> Unit
        }
    }

    /** Wird von der Spielschleife (WP9) bei Partieende aufgerufen; wirkt nur, solange dieses Spiel oben liegt. */
    fun finishMatch(result: MatchResult) {
        if (navigator.replaceTop(Screen.Result(result), from = entryId)) countdown?.cancel()
    }

    /** Für den Platzhalter-Screen: schließt die Partie mit Beispielwerten ab. */
    fun finishWithSampleStats(humanWins: Boolean) {
        val config = _state.value.config
        val winner = if (humanWins) config.humanPlayerId else 1 - config.humanPlayerId
        val reason = if (humanWins) EndReason.ENEMY_REACTOR_DESTROYED else EndReason.OWN_REACTOR_DESTROYED
        finishMatch(MatchResult(config, winner, reason, SAMPLE_STATS))
    }

    override fun onCleared() {
        countdown?.cancel()
    }

    companion object {
        const val HANDOVER_SECONDS = 3
        val SAMPLE_STATS = MatchStats(durationSeconds = 8 * 60 + 42, shots = 23, hits = 17, beamsBuilt = 64, beamsLost = 19)
    }
}
