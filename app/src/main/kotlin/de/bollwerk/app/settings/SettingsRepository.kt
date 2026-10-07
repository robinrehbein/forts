package de.bollwerk.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Zugriff auf persistierte Einstellungen; Fake-fähig für ViewModel-Tests. */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    val setupPrefs: Flow<SetupPrefs>

    /** Tutorial-Fortschritt (Erststart-Angebot, Abschluss). */
    val tutorial: Flow<TutorialProgress>

    /** Ändert die Einstellungen atomar anhand der aktuellen Werte. */
    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun saveSetup(prefs: SetupPrefs)

    /** Ändert den Tutorial-Fortschritt atomar anhand des aktuellen Werts. */
    suspend fun updateTutorial(transform: (TutorialProgress) -> TutorialProgress)
}

/** Preferences-DataStore-Implementierung. Lesefehler (IO) fallen auf die Standardwerte zurück. */
class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e
    }

    override val settings: Flow<AppSettings> = data.map { it.toAppSettings() }.distinctUntilChanged()
    override val setupPrefs: Flow<SetupPrefs> = data.map { it.toSetupPrefs() }.distinctUntilChanged()

    override val tutorial: Flow<TutorialProgress> = data.map { it.toTutorialProgress() }.distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toAppSettings())) }
    }

    override suspend fun saveSetup(prefs: SetupPrefs) {
        store.edit { it.write(prefs) }
    }

    override suspend fun updateTutorial(transform: (TutorialProgress) -> TutorialProgress) {
        store.edit { prefs -> prefs.write(transform(prefs.toTutorialProgress())) }
    }
}
