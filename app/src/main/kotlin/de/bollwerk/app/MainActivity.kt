package de.bollwerk.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.bollwerk.app.ui.AppRoot

/**
 * Einzige Activity. Querformat (Manifest), immersives Edge-to-Edge-Vollbild; Inhalt komplett in Compose.
 * Navigation und ViewModels: siehe [AppViewModel] und [AppRoot].
 */
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels {
        viewModelFactory { initializer { AppViewModel((application as BollwerkApp).graph) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Kein FLAG_KEEP_SCREEN_ON für die ganze Activity: wach bleibt der Bildschirm nur, solange eine Partie läuft
        // (GameSurface setzt keepScreenOn an der Spielfläche); Menüs, Pause und Ergebnis dürfen ausgehen.
        hideSystemBars()
        setContent { AppRoot(appViewModel) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
