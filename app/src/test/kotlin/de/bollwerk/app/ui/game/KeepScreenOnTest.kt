package de.bollwerk.app.ui.game

import android.view.View
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Der Bildschirm bleibt nur wach, solange die Sim läuft (vorher: `FLAG_KEEP_SCREEN_ON` für die ganze Activity, auch in
 * Menüs, Pause und Ergebnis). Paparazzi liefert hier nur den Android-Kontext (Layoutlib) für eine echte View.
 */
class KeepScreenOnTest {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun surfaceKeepsTheScreenOnOnlyWhileTheSimRuns() {
        val view = View(paparazzi.context)
        assertFalse(view.keepScreenOn)
        view.keepScreenOnWhile(simRunning = true)
        assertTrue(view.keepScreenOn)
        view.keepScreenOnWhile(simRunning = false) // Pause-Dialog, Einstellungen, Übergabe wartet
        assertFalse(view.keepScreenOn)
    }

    @Test
    fun activityDoesNotPinTheScreenOnForEveryScreen() {
        val activity = java.io.File("src/main/kotlin/de/bollwerk/app/MainActivity.kt").readText()
        assertFalse(Regex("""addFlags\([^)]*FLAG_KEEP_SCREEN_ON""").containsMatchIn(activity), "kein Fenster-Flag für alle Screens")
    }
}
