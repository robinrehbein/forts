package de.bollwerk.app.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.nav.Navigator
import de.bollwerk.app.nav.Screen
import de.bollwerk.app.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Dialoge über dem Hauptmenü. */
enum class MenuDialog { CREDITS, TUTORIAL_SOON }

/** [reducedEffects] schaltet die Windrad-Animation der Szene ab (Einstellung „Reduzierte Effekte"). */
data class MainMenuUiState(val dialog: MenuDialog? = null, val reducedEffects: Boolean = false)

class MainMenuViewModel(
    private val navigator: Navigator,
    settings: SettingsRepository,
    private val entryId: Long = navigator.current.id,
) : ViewModel() {
    private val _state = MutableStateFlow(MainMenuUiState())
    val state: StateFlow<MainMenuUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(reducedEffects = s.reducedEffects) } }
        }
    }

    // `from = entryId`: ein zweiter Tipp, während das Ziel schon oben liegt, bewirkt nichts.
    fun onBattle() = navigator.push(Screen.Setup(GameMode.VS_AI), from = entryId)
    fun onHotseat() = navigator.push(Screen.Setup(GameMode.HOTSEAT), from = entryId)
    fun onSettings() = navigator.push(Screen.Settings, from = entryId)

    /** Das Tutorial kommt mit WP14; bis dahin ein Hinweis-Dialog. */
    fun onTutorial() = _state.update { it.copy(dialog = MenuDialog.TUTORIAL_SOON) }
    fun onCredits() = _state.update { it.copy(dialog = MenuDialog.CREDITS) }
    fun onDismissDialog() = _state.update { it.copy(dialog = null) }
}
