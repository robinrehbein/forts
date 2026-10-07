package de.bollwerk.app.ui.result

import androidx.lifecycle.ViewModel
import de.bollwerk.app.match.MatchResult
import de.bollwerk.app.nav.Navigator
import de.bollwerk.app.nav.Screen

/** Ergebnis-Screen (Mockup 7): Revanche startet dieselbe Konfiguration mit neuem Seed (nach dem Tutorial als normales Gefecht). */
class ResultViewModel(
    val result: MatchResult,
    private val navigator: Navigator,
    private val entryId: Long = navigator.current.id,
    private val seedSource: () -> Long,
) : ViewModel() {
    fun onRematch() {
        navigator.replaceTop(Screen.Game(result.config.copy(seed = seedSource(), tutorial = false)), from = entryId)
    }

    fun onMainMenu() = navigator.popToRoot()
}
