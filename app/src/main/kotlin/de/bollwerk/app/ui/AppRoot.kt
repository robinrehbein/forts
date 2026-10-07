package de.bollwerk.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalConfiguration
import de.bollwerk.app.AppViewModel
import de.bollwerk.app.di.AppGraph
import de.bollwerk.app.game.GameController
import de.bollwerk.app.game.GameRuntime
import de.bollwerk.app.game.MatchSessions
import de.bollwerk.app.ui.game.GameRuntimeFactory
import java.text.DecimalFormatSymbols
import java.util.Locale
import de.bollwerk.app.nav.Screen
import de.bollwerk.app.ui.game.GameScreen
import de.bollwerk.app.ui.game.GameViewModel
import de.bollwerk.app.ui.game.TutorialStore
import de.bollwerk.app.ui.menu.MainMenuScreen
import de.bollwerk.app.ui.menu.MainMenuViewModel
import de.bollwerk.app.ui.result.ResultScreen
import de.bollwerk.app.ui.result.ResultViewModel
import de.bollwerk.app.ui.settings.SettingsScreen
import de.bollwerk.app.ui.settings.SettingsViewModel
import de.bollwerk.app.ui.setup.SetupScreen
import de.bollwerk.app.ui.setup.SetupViewModel
import de.bollwerk.app.ui.theme.BollwerkTheme

/**
 * Wurzel-Composable: zeigt das oberste Ziel des [de.bollwerk.app.nav.Navigator]. Jeder Eintrag hat einen
 * eigenen ViewModel-Speicher (Lebensdauer = Zeit auf dem Rückstapel).
 */
@Composable
fun AppRoot(app: AppViewModel) {
    BollwerkTheme {
        val stack by app.navigator.stack.collectAsStateWithLifecycle()
        val entry = stack.last()
        val nav = app.navigator
        val graph = app.graph
        // Der gelesene Stapel kann hinter dem Navigator herhinken; dann ist der Speicher des Eintrags schon
        // weg und der nächste Stand zeichnet neu (nie einen Speicher in der Komposition anlegen).
        val owner = app.ownerFor(entry.id)
        if (owner != null) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            when (val screen = entry.screen) {
                Screen.MainMenu -> {
                    val vm = viewModel<MainMenuViewModel>(
                        factory = viewModelFactory { initializer { MainMenuViewModel(nav, graph.settings, entry.id) } },
                    )
                    val state by vm.state.collectAsStateWithLifecycle()
                    BackHandler(enabled = state.dialog != null) { vm.onDismissDialog() }
                    MainMenuScreen(vm)
                }
                is Screen.Setup -> {
                    val vm = viewModel<SetupViewModel>(
                        factory = viewModelFactory {
                            initializer { SetupViewModel(screen.mode, graph.settings, nav, entry.id, graph.seedSource) }
                        },
                    )
                    BackHandler { vm.onBack() }
                    SetupScreen(vm)
                }
                Screen.Settings -> {
                    val vm = settingsViewModel(app)
                    BackHandler { nav.back(entry.id) }
                    SettingsScreen(vm, onBack = { nav.back(entry.id) })
                }
                is Screen.Game -> {
                    val separator = decimalSeparator()
                    val vm = viewModel<GameViewModel>(
                        factory = viewModelFactory {
                            initializer {
                                GameViewModel(
                                    screen.config, nav,
                                    runtimeFactory = gameRuntimeFactory(graph),
                                    settings = graph.settings.settings,
                                    decimalSeparator = separator,
                                    entryId = entry.id,
                                    gestureTips = graph.settings.gestureTips,
                                    onTipSeen = { tip -> graph.settings.updateGestureTips { it.markSeen(tip) } },
                                    tutorialStore = TutorialStore { completed -> graph.settings.updateTutorial { it.afterFinish(completed) } },
                                )
                            }
                        },
                    )
                    GameScreen(vm, settingsViewModel(app), graph.audio)
                }
                is Screen.Result -> {
                    val vm = viewModel<ResultViewModel>(
                        factory = viewModelFactory {
                            initializer { ResultViewModel(screen.result, nav, entry.id, graph.seedSource) }
                        },
                    )
                    BackHandler { nav.back(entry.id) }
                    ResultScreen(vm)
                }
            }
        }
    }
}

/** Partie anlegen: Content (einmal geladen) → `MatchSessions` → Sim-Thread-Controller, Audio-Hörer je Partie. */
private fun gameRuntimeFactory(graph: AppGraph) = GameRuntimeFactory { config ->
    val session = MatchSessions.create(graph.content(), config)
    GameRuntime(GameController(session), graph.audio?.listenerFor(session))
}

/** Dezimalzeichen der Oberflächensprache („3,2" bzw. „3.2"). */
@Composable
private fun decimalSeparator(): Char {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    return DecimalFormatSymbols.getInstance(locale).decimalSeparator
}

@Composable
private fun settingsViewModel(app: AppViewModel): SettingsViewModel =
    viewModel(factory = viewModelFactory { initializer { SettingsViewModel(app.graph.settings) } })
