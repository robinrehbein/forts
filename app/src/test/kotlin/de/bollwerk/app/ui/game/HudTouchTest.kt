package de.bollwerk.app.ui.game

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.HudPresenter
import de.bollwerk.app.game.HudUiState
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.ui.game.hud.HudActions
import de.bollwerk.app.ui.theme.BollwerkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Berührungen auf HUD-Flächen dürfen die Spielfläche darunter (`AndroidView` → `InputController` → Werkzeug) nicht
 * erreichen; Berührungen auf freier Fläche schon. Echte Compose-Hit-Tests in Layoutlib (Paparazzi): eine Mess-View als
 * Spielfläche unter dem HUD, Tipps über `dispatchTouchEvent` an die Wurzel, nachdem das Layout steht.
 * Gleiches Querformat wie [HudSnapshotTest] (800 × 360 dp, Dichte 2).
 */
class HudTouchTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1600,
            screenHeight = 720,
            xdpi = 320,
            ydpi = 320,
            density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE,
            softButtons = false,
        ),
        theme = "android:Theme.Material.NoActionBar.Fullscreen",
        maxPercentDifference = 0.5,
    )

    /** Zählt, welche Tipps (dp-Koordinaten) bei der „Spielfläche" angekommen sind. */
    private class SurfaceProbe(context: Context) : View(context) {
        var downs = 0
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) downs++
            return true
        }
    }

    /** Wurzel, die beim ersten Zeichnen (Layout fertig, Fenster angehängt) die Tipps ausführt. */
    private class TapRoot(context: Context, private val taps: () -> Unit) : FrameLayout(context) {
        private var done = false
        override fun dispatchDraw(canvas: Canvas) {
            if (!done) {
                done = true
                taps()
            }
            super.dispatchDraw(canvas)
        }
    }

    private fun View.tap(xDp: Float, yDp: Float) {
        val d = resources.displayMetrics.density
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, xDp * d, yDp * d, 0)
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, xDp * d, yDp * d, 0)
        dispatchTouchEvent(down)
        dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /** Führt [taps] über dem HUD [hud] aus; liefert je Tipp, ob er die Spielfläche erreicht hat. */
    private fun reachedSurface(hud: HudUiState, taps: List<Pair<Float, Float>>): List<Boolean> {
        val ctx = paparazzi.context
        val probe = SurfaceProbe(ctx)
        val result = ArrayList<Boolean>()
        lateinit var root: TapRoot
        root = TapRoot(ctx) {
            for ((x, y) in taps) {
                val before = probe.downs
                root.tap(x, y)
                result += probe.downs > before
            }
        }
        val compose = ComposeView(ctx).apply {
            setContent {
                BollwerkTheme {
                    GameContent(
                        state = GameUiState(MatchConfig(), loading = false), hud = hud, toast = null, leftHanded = false,
                        actions = HudActions.NONE, onResume = {}, onRequestConfirm = {}, onOpenSettings = {}, onConfirm = {},
                        onCancelConfirm = {}, onHandoverReady = {}, settingsOverlay = {},
                        surface = { AndroidView(factory = { probe }, modifier = Modifier.fillMaxSize()) },
                    )
                }
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        paparazzi.snapshot(root)
        assertEquals("all taps ran", taps.size, result.size)
        return result
    }

    @Test
    fun buildModeHudSwallowsTouchesAndFreeAreasReachTheSurface() {
        val hud = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.buildTools(), HudFixtures.catalog, hotseat = false)
        val reached = reachedSurface(
            hud,
            listOf(
                400f to 180f, // freie Spielfläche (Mitte)
                40f to 26f, // Metall-Chip
                40f to 320f, // Kostenbox „HOLZ 4/m"
                73f to 320f, // Lücke zwischen Kostenbox und Werkzeugleiste
                760f to 90f, // Gegner-Chip
                600f to 120f, // freie Spielfläche (rechts oben)
            ),
        )
        assertEquals(listOf(true, false, false, false, false, true), reached)
    }

    @Test
    fun aimModeHudSwallowsTouchesOnThePanelAndTheAngleCard() {
        val hud = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.aimTools(), HudFixtures.catalog, hotseat = false)
        val reached = reachedSurface(
            hud,
            listOf(
                400f to 180f, // freie Spielfläche: Zielgeste erlaubt
                240f to 125f, // WINKEL · KRAFT-Karte
                300f to 322f, // Zielleiste (Kraft-Beschriftung)
                740f to 322f, // FEUER (Waffe lädt nach: deaktiviert, trotzdem kein Durchgriff)
            ),
        )
        assertEquals(listOf(true, false, false, false), reached)
    }
}
