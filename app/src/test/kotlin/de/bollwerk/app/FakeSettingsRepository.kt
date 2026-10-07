package de.bollwerk.app

import de.bollwerk.app.settings.AppSettings
import de.bollwerk.app.settings.GestureTips
import de.bollwerk.app.settings.SettingsRepository
import de.bollwerk.app.settings.SetupPrefs
import de.bollwerk.app.settings.TutorialProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-Memory-Ersatz für das DataStore-Repository. */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
    initialSetup: SetupPrefs = SetupPrefs(),
    /** Standard: Tutorial schon angeboten (kein Erststart-Dialog); Erststart-Tests übergeben `TutorialProgress()`. */
    initialTutorial: TutorialProgress = TutorialProgress(offered = true),
) : SettingsRepository {
    val settingsState = MutableStateFlow(initial)
    val setupState = MutableStateFlow(initialSetup)
    val tutorialState = MutableStateFlow(initialTutorial)
    val tipsState = MutableStateFlow(GestureTips())
    var saveSetupCalls = 0

    override val settings: StateFlow<AppSettings> get() = settingsState
    override val setupPrefs: StateFlow<SetupPrefs> get() = setupState
    override val tutorial: StateFlow<TutorialProgress> get() = tutorialState
    override val gestureTips: StateFlow<GestureTips> get() = tipsState

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        settingsState.value = transform(settingsState.value)
    }

    override suspend fun saveSetup(prefs: SetupPrefs) {
        saveSetupCalls++
        setupState.value = prefs
    }

    override suspend fun updateTutorial(transform: (TutorialProgress) -> TutorialProgress) {
        tutorialState.value = transform(tutorialState.value)
    }

    override suspend fun updateGestureTips(transform: (GestureTips) -> GestureTips) {
        tipsState.value = transform(tipsState.value)
    }
}
