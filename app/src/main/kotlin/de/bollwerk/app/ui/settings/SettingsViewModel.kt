package de.bollwerk.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bollwerk.app.settings.AppSettings
import de.bollwerk.app.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Einstellungen: liest und schreibt über das [SettingsRepository] (DataStore). */
class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    /** `null`, bis der Speicher die ersten Werte geliefert hat (statt kurz Standardwerte zu zeigen). */
    val state: StateFlow<AppSettings?> =
        repo.settings.stateIn<AppSettings?>(viewModelScope, SharingStarted.Eagerly, null)

    fun setSoundVolume(v: Float) = update { it.copy(soundVolume = v.coerceIn(0f, 1f)) }
    fun setMusicVolume(v: Float) = update { it.copy(musicVolume = v.coerceIn(0f, 1f)) }
    fun setLeftHanded(v: Boolean) = update { it.copy(leftHanded = v) }
    fun setReleaseToFire(v: Boolean) = update { it.copy(releaseToFire = v) }
    fun setReducedEffects(v: Boolean) = update { it.copy(reducedEffects = v) }
    fun setHaptics(v: Boolean) = update { it.copy(haptics = v) }

    /** Das Repository wendet [transform] atomar auf den gespeicherten Stand an, nicht auf die Anzeige. */
    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }
}
