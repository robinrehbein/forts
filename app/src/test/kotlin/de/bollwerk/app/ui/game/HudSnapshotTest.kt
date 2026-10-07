package de.bollwerk.app.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.HudPresenter
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.ui.game.hud.HudActions
import de.bollwerk.app.ui.game.hud.PreviewBattlefield
import de.bollwerk.app.ui.theme.BollwerkTheme
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.TurnPhase
import org.junit.Rule
import org.junit.Test

/**
 * Snapshot-Tests des Spiel-HUD (Mockups 3, 4, 5, 6) mit festen HUD-Ständen (Stil-Bibel-Werte). Querformat 800 × 360 dp.
 * Aufnehmen: `./gradlew :app:recordPaparazziDebug`; geprüft wird in jedem `testDebugUnitTest` (siehe `app/build.gradle.kts`).
 */
class HudSnapshotTest {
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
        // Gleiche Layoutlib, gleiche Schriften: jede Abweichung ist eine echte Änderung. Zwei vertauschte Toolbar-Einträge
        // ergeben nur ~0,1 % (Goldens sind auf 1000 px Breite skaliert), 0,5 % ließ sie durch.
        maxPercentDifference = 0.01,
    )

    private val catalog = HudFixtures.catalog

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        BollwerkTheme {
            Box(Modifier.fillMaxSize()) {
                PreviewBattlefield(Modifier.fillMaxSize())
                content()
            }
        }
    }

    private fun content(state: GameUiState, hud: de.bollwerk.app.game.HudUiState, toast: GameToast? = null) = paparazzi.snapshot {
        Frame {
            GameContent(
                state = state, hud = hud, toast = toast, leftHanded = false, actions = HudActions.NONE,
                onResume = {}, onRequestConfirm = {}, onOpenSettings = {}, onConfirm = {}, onCancelConfirm = {}, onHandoverReady = {},
                settingsOverlay = {}, surface = {},
            )
        }
    }

    @Test
    fun buildMode() {
        val hud = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.buildTools(), catalog, hotseat = false)
        content(GameUiState(MatchConfig(), loading = false), hud)
    }

    @Test
    fun aimMode() {
        val hud = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.aimTools(), catalog, hotseat = false)
        content(GameUiState(MatchConfig(), loading = false), hud)
    }

    @Test
    fun techTreeSheet() {
        val hud = HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.buildTools(), catalog, hotseat = false)
        content(GameUiState(MatchConfig(), loading = false, techTreeOpen = true), hud)
    }

    @Test
    fun hotseatHandover() {
        val hud = HudPresenter.present(HudFixtures.hotseatHud(), HudFixtures.buildTools(), catalog, hotseat = true)
        val state = GameUiState(
            MatchConfig(mode = GameMode.HOTSEAT), loading = false, activePlayer = 1, turn = 2,
            overlay = GameOverlay.Handover(HandoverState(player = 1, turn = 2, secondsLeft = null, totalSeconds = 3)),
        )
        content(state, hud)
    }

    @Test
    fun buildModeHotseatWithToast() {
        val hud = HudPresenter.present(HudFixtures.hotseatHud(), HudFixtures.buildTools(), catalog, hotseat = true)
        content(GameUiState(MatchConfig(mode = GameMode.HOTSEAT), loading = false), hud, GameToast(RejectReason.NOT_ENOUGH_METAL, 1))
    }

    /** Hotseat, Auflösungsphase: kein Befehlsrecht → Leiste ausgegraut, ohne ZUG-ENDE-Button. */
    @Test
    fun buildModeHotseatResolvePhase() {
        val hud = HudPresenter.present(HudFixtures.hotseatHud(phase = TurnPhase.RESOLVE), HudFixtures.buildTools(), catalog, hotseat = true)
        content(GameUiState(MatchConfig(mode = GameMode.HOTSEAT), loading = false), hud)
    }
}
