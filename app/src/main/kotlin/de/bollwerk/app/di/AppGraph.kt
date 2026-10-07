package de.bollwerk.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import de.bollwerk.app.audio.AndroidGameAudio
import de.bollwerk.app.game.GameAudio
import de.bollwerk.app.settings.DataStoreSettingsRepository
import de.bollwerk.app.settings.SettingsRepository
import de.bollwerk.content.ClasspathContent
import de.bollwerk.content.ContentDb

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "bollwerk_settings")

/**
 * Manueller DI-Halter (kein Framework). Lebt in der [de.bollwerk.app.BollwerkApp]: Einstellungen, Seed-Quelle, Content
 * (einmal je Prozess geladen) und Audio.
 */
class AppGraph(
    val settings: SettingsRepository,
    /** Zufallsquelle für Match-Seeds (App-Schicht, nicht Sim-Pfad); injizierbar für Tests. */
    val seedSource: () -> Long = { System.nanoTime() xor System.currentTimeMillis() },
    private val contentLoader: () -> ContentDb = { ClasspathContent.load() },
    /** Audio/Haptik (null in Tests und Vorschauen). */
    val audio: GameAudio? = null,
) {
    @Volatile
    private var db: ContentDb? = null
    private val contentLock = Any()

    /** Content der App (einmal geladen und zwischengespeichert). Blockiert beim ersten Aufruf: nicht auf dem Main-Thread. */
    fun content(): ContentDb {
        db?.let { return it }
        return synchronized(contentLock) { db ?: contentLoader().also { db = it } }
    }

    companion object {
        fun create(context: Context): AppGraph = AppGraph(
            settings = DataStoreSettingsRepository(context.applicationContext.settingsDataStore),
            audio = AndroidGameAudio(context),
        )
    }
}
