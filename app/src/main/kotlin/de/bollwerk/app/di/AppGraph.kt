package de.bollwerk.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import de.bollwerk.app.settings.DataStoreSettingsRepository
import de.bollwerk.app.settings.SettingsRepository

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "bollwerk_settings")

/**
 * Manueller DI-Halter (kein Framework). Lebt in der [de.bollwerk.app.BollwerkApp]; später hängen hier
 * Content-DB, Audio und Sim-Session-Fabrik (WP9) ein.
 */
class AppGraph(
    val settings: SettingsRepository,
    /** Zufallsquelle für Match-Seeds (App-Schicht, nicht Sim-Pfad); injizierbar für Tests. */
    val seedSource: () -> Long = { System.nanoTime() xor System.currentTimeMillis() },
) {
    companion object {
        fun create(context: Context): AppGraph =
            AppGraph(DataStoreSettingsRepository(context.applicationContext.settingsDataStore))
    }
}
