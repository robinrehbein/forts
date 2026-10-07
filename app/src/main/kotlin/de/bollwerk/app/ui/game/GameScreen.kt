package de.bollwerk.app.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.bollwerk.app.game.GameAudio
import de.bollwerk.app.game.GameRuntime
import de.bollwerk.app.game.HudUiState
import de.bollwerk.app.settings.GestureTip
import de.bollwerk.app.tutorial.TutorialUiModel
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.ui.game.hud.GameHud
import de.bollwerk.app.ui.game.hud.HudActions
import de.bollwerk.app.ui.game.hud.LoadingOverlay
import de.bollwerk.app.ui.game.hud.TechTreeSheet
import de.bollwerk.app.ui.game.hud.WorldToScreen
import de.bollwerk.app.ui.game.tutorial.LocalTutorialAnchors
import de.bollwerk.app.ui.game.tutorial.TutorialAnchors
import de.bollwerk.app.ui.game.tutorial.TutorialOverlay
import de.bollwerk.app.ui.handover.HotseatHandoverScreen
import de.bollwerk.app.ui.settings.SettingsScreen
import de.bollwerk.app.ui.settings.SettingsViewModel
import de.bollwerk.app.ui.theme.BollwerkTheme
import de.bollwerk.renderapi.Palette

/**
 * Spiel-Host: Spielfläche ([GameSurface], Render-Thread), Compose-HUD darüber und die Overlays (Pause, Bestätigung,
 * Einstellungen, Techbaum, Hotseat-Übergabe). System-Zurück schließt das Sheet bzw. pausiert. Lebenszyklus: Hintergrund
 * pausiert Sim und Audio; Audio folgt dem Laufzustand der Sim (`simRunning`), auch bei Pause-Overlays.
 */
@Composable
fun GameScreen(viewModel: GameViewModel, settingsViewModel: SettingsViewModel, audio: GameAudio?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hud by viewModel.hud.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val runtime by viewModel.runtime.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val simRunning by viewModel.simRunning.collectAsStateWithLifecycle()
    val tutorial by viewModel.tutorial.collectAsStateWithLifecycle()
    val tip by viewModel.tip.collectAsStateWithLifecycle()
    BackHandler { viewModel.onSystemBack() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.onAppBackgrounded()
        audio?.onPause()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onAppForegrounded() }
    // Audio läuft genau dann, wenn die Sim läuft: Pause-Dialog, Einstellungen und wartende Übergabe halten es an
    // (Hintergrund ebenso, siehe oben); nach der Rückkehr setzt erst „Fortsetzen" das Audio fort.
    LaunchedEffect(simRunning, audio) { if (simRunning) audio?.onResume() else audio?.onPause() }
    LaunchedEffect(settings, audio) { audio?.apply(settings) }
    DisposableEffect(audio) { onDispose { audio?.stopAll() } }
    GameContent(
        state = state,
        hud = hud,
        toast = toast,
        leftHanded = settings.leftHanded,
        actions = viewModel,
        onResume = viewModel::resume,
        onRequestConfirm = viewModel::requestConfirm,
        onOpenSettings = viewModel::openSettings,
        onConfirm = viewModel::confirm,
        onCancelConfirm = viewModel::cancelConfirm,
        onHandoverReady = viewModel::onHandoverReady,
        settingsOverlay = { SettingsScreen(settingsViewModel, onBack = viewModel::closeSettings) },
        worldToScreen = runtime?.let { worldToScreenOf(it) } ?: NO_SCREEN,
        tutorial = tutorial,
        releaseToFire = settings.releaseToFire,
        tip = tip,
        onTipDismiss = viewModel::dismissTip,
        onTutorialSkip = viewModel::skipTutorial,
        onTutorialClose = viewModel::closeTutorial,
        surface = {
            Box(Modifier.fillMaxSize().background(Color(Palette.SKY_1))) {
                GameSurface(runtime, state.matchGeneration, settings.reducedEffects, simRunning, Modifier.fillMaxSize())
            }
        },
    )
}

private val NO_SCREEN = WorldToScreen { _, _ -> null }

private fun worldToScreenOf(rt: GameRuntime) = WorldToScreen { x, y ->
    val cam = rt.camera
    synchronized(cam) { Offset(cam.worldToScreenX(x), cam.worldToScreenY(y)) }
}

@Composable
fun GameContent(
    state: GameUiState,
    hud: HudUiState,
    toast: GameToast?,
    leftHanded: Boolean,
    actions: HudActions,
    onResume: () -> Unit,
    onRequestConfirm: (PauseAction) -> Unit,
    onOpenSettings: () -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    onHandoverReady: () -> Unit,
    settingsOverlay: @Composable () -> Unit,
    worldToScreen: WorldToScreen = NO_SCREEN,
    /** Coach-Mark des Tutorial-Gefechts (null = normale Partie). */
    tutorial: TutorialUiModel? = null,
    onTutorialSkip: () -> Unit = {},
    onTutorialClose: () -> Unit = {},
    /** Feste Animationsphase des Coach-Marks (Snapshot-Tests); null = läuft mit der Uhr. */
    tutorialPhase: Float? = null,
    /** Einstellung „Loslassen = Feuern" (Tutorial-Hinweis zum Schießen). */
    releaseToFire: Boolean = false,
    /** Einmaliger Gesten-Hinweis (nicht blockierender Chip), `null` = keiner. */
    tip: GestureTip? = null,
    onTipDismiss: () -> Unit = {},
    surface: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color(Palette.SKY_1))) },
) {
    val anchors = remember { TutorialAnchors() }
    Box(Modifier.fillMaxSize()) {
        surface()
        if (state.loading) {
            LoadingOverlay()
        } else if (!state.boardHidden) {
            // Das HUD meldet die Flächen der Toolbar-Einträge, die der Coach-Mark hervorhebt (nur im Tutorial gesetzt)
            CompositionLocalProvider(LocalTutorialAnchors provides if (tutorial != null) anchors else null) {
                GameHud(hud, toast, leftHanded, actions, worldToScreen = worldToScreen, tip = tip, onTipDismiss = onTipDismiss)
                if (state.techTreeOpen) TechTreeSheet(hud, actions)
            }
            // Coach-Mark über dem HUD; Pause, Einstellungen und Bestätigung liegen darüber, der Techbaum verdeckt ihn
            if (tutorial != null && !state.techTreeOpen) {
                TutorialOverlay(tutorial, anchors, worldToScreen, onTutorialSkip, onTutorialClose, phase = tutorialPhase, releaseToFire = releaseToFire)
            }
        }
        when (val overlay = state.overlay) {
            GameOverlay.None -> Unit
            GameOverlay.Pause -> PauseDialog(onResume, onRequestConfirm, onOpenSettings)
            is GameOverlay.Confirm -> ConfirmDialog(overlay.action, onConfirm, onCancelConfirm)
            GameOverlay.Settings -> settingsOverlay()
            is GameOverlay.Handover -> HotseatHandoverScreen(overlay.state, state.config.turnSeconds, onHandoverReady)
        }
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun GamePreview() {
    BollwerkTheme {
        GameContent(
            GameUiState(MatchConfig(mode = GameMode.HOTSEAT), loading = false), HudUiState(), null, false, HudActions.NONE,
            {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun GamePausePreview() {
    BollwerkTheme {
        GameContent(
            GameUiState(MatchConfig(), overlay = GameOverlay.Pause, loading = false), HudUiState(), null, false, HudActions.NONE,
            {}, {}, {}, {}, {}, {}, {},
        )
    }
}
