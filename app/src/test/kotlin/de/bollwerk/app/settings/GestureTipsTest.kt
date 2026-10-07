package de.bollwerk.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Einmalige Gesten-Hinweise: reine Logik, Preferences-Abbildung und echter DataStore über einen Neustart hinweg. */
class GestureTipsTest {
    @Test
    fun tipsAreOfferedInOrderAndOnlyOnce() {
        var t = GestureTips()
        assertEquals(GestureTip.PINCH_ZOOM, t.next())
        t = t.markSeen(GestureTip.PINCH_ZOOM)
        assertTrue(t.isSeen(GestureTip.PINCH_ZOOM))
        assertFalse(t.isSeen(GestureTip.DOUBLE_TAP))
        assertEquals(GestureTip.DOUBLE_TAP, t.next())
        t = t.markSeen(GestureTip.DOUBLE_TAP).markSeen(GestureTip.DOUBLE_TAP)
        assertEquals(GestureTip.LONG_PRESS, t.next())
        t = t.markSeen(GestureTip.LONG_PRESS)
        assertNull(t.next())
    }

    @Test
    fun preferencesMappingRoundTripsAndIgnoresUnknownBits() {
        assertEquals(GestureTips(), mutablePreferencesOf().toGestureTips())
        val seen = GestureTips().markSeen(GestureTip.DOUBLE_TAP)
        val prefs = mutablePreferencesOf().apply { write(seen) }
        assertEquals(seen, prefs.toGestureTips())
        prefs[PrefKeys.GESTURE_TIPS_SEEN] = -1
        assertNull(prefs.toGestureTips().next())
        assertEquals((1 shl GestureTip.entries.size) - 1, prefs.toGestureTips().seenMask)
    }

    @Test
    fun seenTipsSurviveARestartOfTheDataStore() = runBlocking {
        val file = Files.createTempFile("tips", ".preferences_pb").also { Files.delete(it) }.toFile()
        try {
            suspend fun <T> withRepo(body: suspend (DataStoreSettingsRepository) -> T): T {
                val job = Job()
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob(job))
                try {
                    return body(DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file }))
                } finally {
                    job.cancelAndJoin()
                }
            }
            withRepo { repo ->
                assertEquals(GestureTip.PINCH_ZOOM, repo.gestureTips.first().next())
                repo.updateGestureTips { it.markSeen(GestureTip.PINCH_ZOOM) }
                repo.updateGestureTips { it.markSeen(GestureTip.LONG_PRESS) }
            }
            withRepo { repo ->
                val tips = repo.gestureTips.first()
                assertTrue(tips.isSeen(GestureTip.PINCH_ZOOM))
                assertTrue(tips.isSeen(GestureTip.LONG_PRESS))
                assertEquals(GestureTip.DOUBLE_TAP, tips.next())
            }
        } finally {
            file.delete()
        }
    }
}
