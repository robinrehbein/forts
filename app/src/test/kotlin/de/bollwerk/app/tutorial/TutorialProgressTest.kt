package de.bollwerk.app.tutorial

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import de.bollwerk.app.settings.DataStoreSettingsRepository
import de.bollwerk.app.settings.PrefKeys
import de.bollwerk.app.settings.TutorialProgress
import de.bollwerk.app.settings.toTutorialProgress
import de.bollwerk.app.settings.write
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ablage des Tutorial-Fortschritts: reine Abbildung Preferences ⇄ [TutorialProgress] und echter DataStore. */
class TutorialProgressTest {
    @Test
    fun emptyPreferencesMeanNeverOffered() {
        val p = mutablePreferencesOf().toTutorialProgress()
        assertEquals(TutorialProgress(), p)
        assertTrue(p.shouldOffer)
    }

    @Test
    fun finishingMapsToOfferedAndCompleted() {
        val done = TutorialProgress().afterFinish(completed = true)
        assertEquals(TutorialProgress(offered = true, completed = true), done)
        assertFalse(done.shouldOffer)
        val skipped = TutorialProgress().afterFinish(completed = false)
        assertEquals(TutorialProgress(offered = true, completed = false), skipped)
        assertFalse(skipped.shouldOffer, "a skipped tutorial is not offered again")
    }

    @Test
    fun skippingNeverRevokesACompletion() {
        val again = TutorialProgress(offered = true, completed = true).afterFinish(completed = false)
        assertTrue(again.completed)
    }

    @Test
    fun answeringTheOfferOnlySetsOffered() {
        assertEquals(TutorialProgress(offered = true, completed = false), TutorialProgress().afterOffer())
    }

    @Test
    fun roundTripThroughPreferences() {
        for (p in listOf(
            TutorialProgress(), TutorialProgress(offered = true), TutorialProgress(offered = true, completed = true),
        )) {
            assertEquals(p, mutablePreferencesOf().apply { write(p) }.toTutorialProgress())
        }
    }

    @Test
    fun completedImpliesOfferedEvenIfOnlyTheCompletionKeyExists() {
        val prefs = mutablePreferencesOf().apply { this[PrefKeys.TUTORIAL_COMPLETED] = true }
        assertEquals(TutorialProgress(offered = true, completed = true), prefs.toTutorialProgress())
        // und beim Schreiben bleibt die Invariante erhalten
        val written = mutablePreferencesOf().apply { write(TutorialProgress(offered = false, completed = true)) }
        assertEquals(true, written[PrefKeys.TUTORIAL_OFFERED])
    }

    @Test
    fun dataStoreRepositoryPersistsTheTutorialAcrossInstances() = runBlocking {
        val dir = Files.createTempDirectory("bollwerk-tutorial").toFile()
        val file = dir.resolve("settings.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            assertEquals(TutorialProgress(), first.tutorial.first())
            first.updateTutorial { it.afterOffer() }
            assertEquals(TutorialProgress(offered = true), first.tutorial.first())
            first.updateTutorial { it.afterFinish(completed = true) }
            // Tutorial-Fortschritt berührt die übrigen Einstellungen nicht
            assertEquals(de.bollwerk.app.settings.AppSettings(), first.settings.first())
            scope.coroutineContext[Job]!!.cancelAndJoin()
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val second = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope2, produceFile = { file }))
                assertEquals(TutorialProgress(offered = true, completed = true), second.tutorial.first())
            } finally {
                scope2.cancel()
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
