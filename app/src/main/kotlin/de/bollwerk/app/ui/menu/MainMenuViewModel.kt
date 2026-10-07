package de.bollwerk.app.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.nav.Navigator
import de.bollwerk.app.nav.Screen
import de.bollwerk.app.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Dialoge über dem Hauptmenü. */
enum class MenuDialog {
    CREDITS,

    /** Erststart: das Tutorial wird einmal angeboten. */
    TUTORIAL_OFFER,
}

/** [reducedEffects] schaltet die Windrad-Animation der Szene ab (Einstellung „Reduzierte Effekte"). */
data class MainMenuUiState(val dialog: MenuDialog? = null, val reducedEffects: Boolean = false)

class MainMenuViewModel(
    private val navigator: Navigator,
    private val settings: SettingsRepository,
    private val entryId: Long = navigator.current.id,
) : ViewModel() {
    private val _state = MutableStateFlow(MainMenuUiState())
    val state: StateFlow<MainMenuUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(reducedEffects = s.reducedEffects) } }
        }
        // Erststart: das Tutorial genau einmal anbieten (nur nach dem ersten gelesenen Stand, nie über einem anderen Dialog)
        viewModelScope.launch {
            val progress = settings.tutorial.first()
            if (progress.shouldOffer) _state.update { if (it.dialog == null) it.copy(dialog = MenuDialog.TUTORIAL_OFFER) else it }
        }
    }

    // `from = entryId`: ein zweiter Tipp, während das Ziel schon oben liegt, bewirkt nichts.
    fun onBattle() = navigator.push(Screen.Setup(GameMode.VS_AI), from = entryId)
    fun onHotseat() = navigator.push(Screen.Setup(GameMode.HOTSEAT), from = entryId)
    fun onSettings() = navigator.push(Screen.Settings, from = entryId)

    /** Startet das geführte Tutorial-Gefecht (Menü-Button). */
    fun onTutorial() {
        navigator.push(Screen.Game(MatchConfig.tutorial()), from = entryId)
    }

    /** Erststart-Angebot angenommen: als beantwortet merken und das Tutorial starten. */
    fun onTutorialOfferStart() {
        answerOffer()
        onTutorial()
    }

    /** Erststart-Angebot abgelehnt („Später"): wird nicht noch einmal angeboten, der Menü-Button bleibt. */
    fun onTutorialOfferLater() = onDismissDialog()

    fun onCredits() = _state.update { it.copy(dialog = MenuDialog.CREDITS) }

    fun onDismissDialog() {
        if (_state.value.dialog == MenuDialog.TUTORIAL_OFFER) answerOffer()
        _state.update { it.copy(dialog = null) }
    }

    private fun answerOffer() {
        _state.update { if (it.dialog == MenuDialog.TUTORIAL_OFFER) it.copy(dialog = null) else it }
        viewModelScope.launch { settings.updateTutorial { it.afterOffer() } }
    }
}
