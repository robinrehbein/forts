package de.bollwerk.app.ui.game.tutorial

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.HudPresenter
import de.bollwerk.app.game.ToolUiState
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.tutorial.TutorialHint
import de.bollwerk.app.tutorial.TutorialState
import de.bollwerk.app.tutorial.TutorialStatus
import de.bollwerk.app.tutorial.TutorialStep
import de.bollwerk.app.tutorial.TutorialTargets
import de.bollwerk.app.tutorial.TutorialUiModel
import de.bollwerk.app.tutorial.WorldPoint
import de.bollwerk.app.ui.game.GameContent
import de.bollwerk.app.ui.game.GameUiState
import de.bollwerk.app.ui.game.hud.HudActions
import de.bollwerk.app.ui.game.hud.PreviewBattlefield
import de.bollwerk.app.ui.game.hud.WorldToScreen
import de.bollwerk.app.ui.theme.BollwerkTheme
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolSelection
import org.junit.Rule
import org.junit.Test

/**
 * Snapshot-Tests des Coach-Marks, ein Bild je Hinweis der drei Schritte (plus Rückweg zum Baumodus und Abschlusskarte) auf der
 * HUD-Kulisse; die Animation steht dabei auf einer festen Phase. Querformat 800 × 360 dp.
 * Aufnehmen: `./gradlew :app:recordPaparazziDebug`; geprüft wird in jedem `testDebugUnitTest` (siehe `app/build.gradle.kts`).
 */
class TutorialSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1600, screenHeight = 720, xdpi = 320, ydpi = 320, density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        theme = "android:Theme.Material.NoActionBar.Fullscreen",
        maxPercentDifference = 0.01,
    )

    private val catalog = HudFixtures.catalog

    /**
     * „Welt" des Tests = Bildpunkte der 1000 px breiten Mockup-Ansicht (die Vorschau-Kulisse ist keine echte Spielwelt):
     * Skalierung auf die 1600 px Bildschirmbreite. Die Ziele liegen auf Knoten der gezeichneten Festung.
     */
    private val toScreen = WorldToScreen { x, y -> Offset(x * 1.6f, y * 1.6f) }

    private val targets = TutorialTargets(
        beamA = WorldPoint(136f, 218f), beamB = WorldPoint(224f, 285f),
        ore = WorldPoint(105f, 351f), mineSpot = WorldPoint(105f, 328f), facing = 1,
    )

    private fun model(step: TutorialStep, hint: TutorialHint, status: TutorialStatus = TutorialStatus.RUNNING) =
        TutorialUiModel(TutorialState(status, step, hint), targets)

    private fun build(selection: ToolSelection = ToolSelection.None) =
        ToolUiState(mode = ToolMode.BUILD, selection = selection, canUndo = false)

    private fun shot(tools: ToolUiState, model: TutorialUiModel, phase: Float) = paparazzi.snapshot {
        val hud = HudPresenter.present(HudFixtures.mockupHud(), tools, catalog, hotseat = false)
        BollwerkTheme {
            Frame {
                GameContent(
                    state = GameUiState(MatchConfig.tutorial(), loading = false), hud = hud, toast = null, leftHanded = false,
                    actions = HudActions.NONE, onResume = {}, onRequestConfirm = {}, onOpenSettings = {}, onConfirm = {},
                    onCancelConfirm = {}, onHandoverReady = {}, settingsOverlay = {}, worldToScreen = toScreen,
                    tutorial = model, tutorialPhase = phase, surface = {},
                )
            }
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            PreviewBattlefield(Modifier.fillMaxSize())
            content()
        }
    }

    @Test
    fun step1PickWood() = shot(build(), model(TutorialStep.PLACE_BEAM, TutorialHint.PICK_WOOD), 0.35f)

    @Test
    fun step1DrawBeam() = shot(
        build(ToolSelection.Material(HudFixtures.material("wood"))), model(TutorialStep.PLACE_BEAM, TutorialHint.DRAW_BEAM), 0.45f,
    )

    @Test
    fun step2PickMine() = shot(build(), model(TutorialStep.BUILD_MINE, TutorialHint.PICK_MINE), 0.35f)

    @Test
    fun step2PlaceMine() = shot(
        build(ToolSelection.Device(HudFixtures.device("mine"))), model(TutorialStep.BUILD_MINE, TutorialHint.PLACE_MINE), 0.25f,
    )

    @Test
    fun step3EnterAim() = shot(build(), model(TutorialStep.FIRE_MORTAR, TutorialHint.ENTER_AIM), 0.35f)

    @Test
    fun step3DragToAim() = shot(HudFixtures.aimTools(), model(TutorialStep.FIRE_MORTAR, TutorialHint.AIM_AND_FIRE), 0.45f)

    @Test
    fun backToBuildFromAimMode() = shot(HudFixtures.aimTools(), model(TutorialStep.PLACE_BEAM, TutorialHint.BACK_TO_BUILD), 0.35f)

    @Test
    fun completionCard() = shot(
        build(), model(TutorialStep.FIRE_MORTAR, TutorialHint.AIM_AND_FIRE, TutorialStatus.COMPLETED), 0.35f,
    )
}
