package de.bollwerk.app.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.bollwerk.app.BuildConfig
import de.bollwerk.app.R
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.ui.components.ButtonStyle
import de.bollwerk.app.ui.components.IndustrialButton
import de.bollwerk.app.ui.components.IndustrialIconButton
import de.bollwerk.app.ui.components.SteelPanel
import de.bollwerk.app.ui.components.screenPadding
import de.bollwerk.app.ui.handover.HotseatHandoverScreen
import de.bollwerk.app.ui.settings.SettingsScreen
import de.bollwerk.app.ui.settings.SettingsViewModel
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkTheme
import de.bollwerk.app.ui.theme.BollwerkType
import de.bollwerk.renderapi.Palette
import de.bollwerk.renderandroid.GameSurfaceView

/**
 * Spiel-Host: Spielfläche plus Overlays (Pause, Bestätigung, Einstellungen, Hotseat-Übergabe).
 * Die Spielfläche ist bis WP9 ein Platzhalter; [GameSurfaceView] wird bereits gehostet. System-Zurück
 * pausiert bzw. geht in den Overlays eine Ebene zurück.
 */
@Composable
fun GameScreen(viewModel: GameViewModel, settingsViewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { viewModel.onSystemBack() }
    // Hinweg aus dem Vordergrund: Spiel pausieren, Hotseat-Übergabe verdeckt lassen (kein Countdown im Hintergrund).
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onAppBackgrounded() }
    GameContent(
        state = state,
        onPause = viewModel::pause,
        onEndTurn = viewModel::endTurn,
        onDebugFinish = viewModel::finishWithSampleStats,
        onResume = viewModel::resume,
        onRequestConfirm = viewModel::requestConfirm,
        onOpenSettings = viewModel::openSettings,
        onConfirm = viewModel::confirm,
        onCancelConfirm = viewModel::cancelConfirm,
        onHandoverReady = viewModel::onHandoverReady,
        settingsOverlay = { SettingsScreen(settingsViewModel, onBack = viewModel::closeSettings) },
    )
}

@Composable
fun GameContent(
    state: GameUiState,
    onPause: () -> Unit,
    onEndTurn: () -> Unit,
    onDebugFinish: (Boolean) -> Unit,
    onResume: () -> Unit,
    onRequestConfirm: (PauseAction) -> Unit,
    onOpenSettings: () -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    onHandoverReady: () -> Unit,
    settingsOverlay: @Composable () -> Unit,
    surface: @Composable () -> Unit = { GameSurface(state.matchGeneration) },
) {
    Box(Modifier.fillMaxSize().background(Color(Palette.SKY_1))) {
        surface()
        PlaceholderHud(state, onPause, onEndTurn, onDebugFinish)
        when (val overlay = state.overlay) {
            GameOverlay.None -> Unit
            GameOverlay.Pause -> PauseDialog(onResume, onRequestConfirm, onOpenSettings)
            is GameOverlay.Confirm -> ConfirmDialog(overlay.action, onConfirm, onCancelConfirm)
            GameOverlay.Settings -> settingsOverlay()
            is GameOverlay.Handover -> HotseatHandoverScreen(overlay.state, state.config.turnSeconds, onHandoverReady)
        }
    }
}

/** Hostet die [GameSurfaceView]; bei Neustart ([generation]) wird sie neu aufgebaut. */
@Composable
private fun GameSurface(generation: Int) {
    key(generation) {
        AndroidView(factory = { GameSurfaceView(it) }, modifier = Modifier.fillMaxSize())
    }
}

/** Platzhalter-HUD bis WP9: Pause-Knopf, Hinweis; im Hotseat „Zug beenden"; im Debug-Build Ergebnis-Abkürzungen. */
@Composable
private fun PlaceholderHud(
    state: GameUiState,
    onPause: () -> Unit,
    onEndTurn: () -> Unit,
    onDebugFinish: (Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize().screenPadding(16.dp)) {
        IndustrialIconButton(
            painterResource(R.drawable.ic_pause), stringResource(R.string.game_pause_cd), onPause,
            Modifier.align(Alignment.TopEnd),
        )
        SteelPanel(Modifier.align(Alignment.Center)) {
            Text(stringResource(R.string.game_placeholder), style = BollwerkType.BodyStrong, color = BollwerkColors.Text)
        }
        Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.config.mode == GameMode.HOTSEAT) {
                IndustrialButton(stringResource(R.string.game_end_turn), onEndTurn, style = ButtonStyle.Primary, rivets = false, hazard = false)
            }
            if (BuildConfig.DEBUG) {
                IndustrialButton(stringResource(R.string.game_debug_victory), { onDebugFinish(true) }, Modifier.padding(0.dp), rivets = false)
                IndustrialButton(stringResource(R.string.game_debug_defeat), { onDebugFinish(false) }, rivets = false)
            }
        }
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun GamePreview() {
    BollwerkTheme {
        GameContent(
            GameUiState(MatchConfig(mode = GameMode.HOTSEAT)), {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            surface = {},
        )
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun GamePausePreview() {
    BollwerkTheme {
        GameContent(
            GameUiState(MatchConfig(), overlay = GameOverlay.Pause), {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            surface = {},
        )
    }
}
