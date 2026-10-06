package de.bollwerk.app

import de.bollwerk.app.settings.AppSettings
import de.bollwerk.app.settings.SettingsRepository
import de.bollwerk.app.settings.SetupPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-Memory-Ersatz für das DataStore-Repository. */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
    initialSetup: SetupPrefs = SetupPrefs(),
) : SettingsRepository {
    val settingsState = MutableStateFlow(initial)
    val setupState = MutableStateFlow(initialSetup)
    var saveSetupCalls = 0

    override val settings: StateFlow<AppSettings> get() = settingsState
    override val setupPrefs: StateFlow<SetupPrefs> get() = setupState

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        settingsState.value = transform(settingsState.value)
    }

    override suspend fun saveSetup(prefs: SetupPrefs) {
        saveSetupCalls++
        setupState.value = prefs
    }
}
