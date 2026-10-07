package de.bollwerk.app.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.HudPresenter
import de.bollwerk.app.game.HudUiState
import de.bollwerk.app.match.EndReason
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.MatchResult
import de.bollwerk.app.match.MatchStats
import de.bollwerk.app.settings.AppSettings
import de.bollwerk.app.ui.components.LocalTextFitReporter
import de.bollwerk.app.ui.components.TextFitReporter
import de.bollwerk.app.ui.game.GameContent
import de.bollwerk.app.ui.game.GameUiState
import de.bollwerk.app.ui.game.hud.HudActions
import de.bollwerk.app.ui.game.hud.PreviewBattlefield
import de.bollwerk.app.ui.menu.MainMenuContent
import de.bollwerk.app.ui.menu.MainMenuUiState
import de.bollwerk.app.ui.result.ResultContent
import de.bollwerk.app.ui.settings.SettingsContent
import de.bollwerk.app.ui.setup.SetupContent
import de.bollwerk.app.ui.setup.SetupUiState
import de.bollwerk.app.ui.theme.BollwerkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertTrue

/** Querformat-Gerät mit [widthDp] × 360 dp (xhdpi) in [locale]. */
internal fun narrowPaparazzi(widthDp: Int, locale: String) = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_5.copy(
        screenWidth = widthDp * 2, screenHeight = 720, xdpi = 320, ydpi = 320, density = Density.XHIGH,
        orientation = ScreenOrientation.LANDSCAPE, softButtons = false, locale = locale,
    ),
    theme = "android:Theme.Material.NoActionBar.Fullscreen",
    maxPercentDifference = 0.01,
)

/**
 * Rendert [content] und prüft, dass kein anpassbarer Text (FitText: Toolbar-Labels, Buttons, Abschnittslabels …) selbst in
 * der kleinsten erlaubten Größe abgeschnitten werden musste. Ohne diese Prüfung landeten abgeschnittene Labels („PANZE",
 * „EINSTELLUN", „REMAT") stillschweigend im Golden.
 */
internal fun Paparazzi.snapshotWithoutClipping(content: @Composable () -> Unit) {
    val clipped = LinkedHashSet<String>()
    snapshot {
        CompositionLocalProvider(LocalTextFitReporter provides TextFitReporter { clipped += it }) { content() }
    }
    assertTrue(clipped.isEmpty(), "abgeschnittene Texte: $clipped")
}

/**
 * Spiel-HUD auf schmalen Geräten (640 und 720 dp, die 800-dp-Goldens liegen in `HudSnapshotTest`), DE und EN, rechts- und
 * linkshändig, Hotseat. Toolbar-Einträge behalten 48 dp und ihre vollen Labels (die Leiste scrollt stattdessen).
 */
@RunWith(Parameterized::class)
class NarrowHudSnapshotTest(widthDp: String, locale: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}dp_{1}")
        fun params() = listOf(arrayOf("640", "de"), arrayOf("640", "en"), arrayOf("720", "de"), arrayOf("720", "en"))
    }

    @get:Rule
    val paparazzi = narrowPaparazzi(widthDp.toInt(), locale)

    private val catalog = HudFixtures.catalog

    private fun game(hud: HudUiState, leftHanded: Boolean = false, mode: GameMode = GameMode.VS_AI) = paparazzi.snapshotWithoutClipping {
        BollwerkTheme {
            Box(Modifier.fillMaxSize()) {
                PreviewBattlefield(Modifier.fillMaxSize())
                GameContent(
                    state = GameUiState(MatchConfig(mode = mode), loading = false), hud = hud, toast = null, leftHanded = leftHanded,
                    actions = HudActions.NONE, onResume = {}, onRequestConfirm = {}, onOpenSettings = {}, onConfirm = {},
                    onCancelConfirm = {}, onHandoverReady = {}, settingsOverlay = {}, surface = {},
                )
            }
        }
    }

    private fun buildHud(hotseat: Boolean = false) = HudPresenter.present(
        if (hotseat) HudFixtures.hotseatHud() else HudFixtures.mockupHud(), HudFixtures.buildTools(), catalog, hotseat = hotseat,
    )

    private fun aimHud(hotseat: Boolean = false) = HudPresenter.present(
        if (hotseat) HudFixtures.hotseatHud() else HudFixtures.mockupHud(), HudFixtures.aimTools(), catalog, hotseat = hotseat,
    )

    @Test fun build() = game(buildHud())

    @Test fun buildLeftHanded() = game(buildHud(), leftHanded = true)

    @Test fun aim() = game(aimHud())

    @Test fun aimLeftHanded() = game(aimHud(), leftHanded = true)

    @Test fun hotseatBuild() = game(buildHud(hotseat = true), mode = GameMode.HOTSEAT)

    @Test fun hotseatAim() = game(aimHud(hotseat = true), mode = GameMode.HOTSEAT)
}

/** Menü, Setup, Ergebnis und Einstellungen auf 640 dp (DE/EN): keine abgeschnittenen Labels. */
@RunWith(Parameterized::class)
class NarrowScreensSnapshotTest(locale: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "640dp_{0}")
        fun params() = listOf(arrayOf("de"), arrayOf("en"))
    }

    @get:Rule
    val paparazzi = narrowPaparazzi(640, locale)

    @Test
    fun menu() = paparazzi.snapshotWithoutClipping {
        BollwerkTheme { MainMenuContent(MainMenuUiState(), {}, {}, {}, {}, {}, {}, animate = false) }
    }

    @Test
    fun setup() = paparazzi.snapshotWithoutClipping {
        BollwerkTheme { SetupContent(SetupUiState(GameMode.VS_AI), {}, {}, {}, {}, {}, {}) }
    }

    @Test
    fun setupHotseat() = paparazzi.snapshotWithoutClipping {
        BollwerkTheme { SetupContent(SetupUiState(GameMode.HOTSEAT), {}, {}, {}, {}, {}, {}) }
    }

    @Test
    fun result() = paparazzi.snapshotWithoutClipping {
        BollwerkTheme {
            ResultContent(MatchResult(MatchConfig(seed = 1), 0, EndReason.ENEMY_REACTOR_DESTROYED, MatchStats(522, 23, 17, 64, 19)), {}, {})
        }
    }

    @Test
    fun settings() = paparazzi.snapshotWithoutClipping {
        BollwerkTheme { SettingsContent(AppSettings(leftHanded = true), {}, {}, {}, {}, {}) }
    }
}
