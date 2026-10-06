package de.bollwerk.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import de.bollwerk.app.match.AiLevel
import de.bollwerk.app.match.MapOption
import de.bollwerk.app.match.StartResources
import de.bollwerk.app.match.TeamColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsMappingTest {
    @Test
    fun emptyPreferencesYieldDefaults() {
        assertEquals(AppSettings(), mutablePreferencesOf().toAppSettings())
        assertEquals(SetupPrefs(), mutablePreferencesOf().toSetupPrefs())
    }

    @Test
    fun settingsRoundTrip() {
        val s = AppSettings(soundVolume = 0.25f, musicVolume = 1f, leftHanded = true, releaseToFire = true, reducedEffects = true)
        val prefs = mutablePreferencesOf().apply { write(s) }
        assertEquals(s, prefs.toAppSettings())
    }

    @Test
    fun volumesAreClamped() {
        val prefs = mutablePreferencesOf().apply {
            this[PrefKeys.SOUND] = 7f
            this[PrefKeys.MUSIC] = -3f
        }
        val s = prefs.toAppSettings()
        assertEquals(1f, s.soundVolume)
        assertEquals(0f, s.musicVolume)
        val written = mutablePreferencesOf().apply { write(AppSettings(soundVolume = 2f, musicVolume = -1f)) }
        assertEquals(1f, written[PrefKeys.SOUND])
        assertEquals(0f, written[PrefKeys.MUSIC])
    }

    @Test
    fun setupPrefsRoundTripAndUnknownEnumFallsBack() {
        val p = SetupPrefs(MapOption.HUEGEL, AiLevel.HARD, StartResources.RICH, TeamColor.RED)
        assertEquals(p, mutablePreferencesOf().apply { write(p) }.toSetupPrefs())
        val broken = mutablePreferencesOf().apply {
            this[stringPreferencesKey("setup_map")] = "gibtsnicht"
            this[PrefKeys.SETUP_AI] = AiLevel.EASY.name
        }
        val s = broken.toSetupPrefs()
        assertEquals(MapOption.SCHLUCHT, s.map)
        assertEquals(AiLevel.EASY, s.aiLevel)
    }

    @Test
    fun dataStoreRepositoryPersistsAcrossInstances() = runBlocking {
        val dir = Files.createTempDirectory("bollwerk-ds").toFile()
        val file = dir.resolve("settings.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            assertEquals(AppSettings(), first.settings.first())
            first.update { it.copy(leftHanded = true, soundVolume = 0.3f) }
            first.update { it.copy(reducedEffects = true) }
            first.saveSetup(SetupPrefs(map = MapOption.HUEGEL, team = TeamColor.RED))
            val expected = AppSettings(soundVolume = 0.3f, leftHanded = true, reducedEffects = true)
            assertEquals(expected, first.settings.first())
            scope.coroutineContext[Job]!!.cancelAndJoin()

            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val second = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope2, produceFile = { file }))
                assertEquals(expected, second.settings.first())
                assertEquals(SetupPrefs(map = MapOption.HUEGEL, team = TeamColor.RED), second.setupPrefs.first())
            } finally {
                scope2.cancel()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
