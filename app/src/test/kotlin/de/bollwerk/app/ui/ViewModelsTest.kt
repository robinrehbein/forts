package de.bollwerk.app.ui

import de.bollwerk.app.FakeSettingsRepository
import de.bollwerk.app.match.AiLevel
import de.bollwerk.app.match.EndReason
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MapOption
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.MatchResult
import de.bollwerk.app.match.StartResources
import de.bollwerk.app.match.TeamColor
import de.bollwerk.app.nav.Navigator
import de.bollwerk.app.nav.Screen
import de.bollwerk.app.settings.AppSettings
import de.bollwerk.app.settings.SetupPrefs
import de.bollwerk.app.ui.components.SliderDragState
import de.bollwerk.app.ui.game.GameOverlay
import de.bollwerk.app.ui.game.HandoverState
import de.bollwerk.app.ui.handover.handoverTipIndex
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.TurnView
import de.bollwerk.app.ui.game.GameViewModel
import de.bollwerk.app.ui.game.PauseAction
import de.bollwerk.app.ui.menu.MainMenuViewModel
import de.bollwerk.app.ui.menu.MenuDialog
import de.bollwerk.app.ui.result.ResultViewModel
import de.bollwerk.app.ui.settings.SettingsViewModel
import de.bollwerk.app.ui.setup.SetupViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainMenuViewModelTest : ViewModelTestBase() {
    private val nav = Navigator()
    private val repo = FakeSettingsRepository()
    private val vm by lazy { MainMenuViewModel(nav, repo) }

    @Test
    fun battleAndHotseatOpenSetupWithMode() {
        vm.onBattle()
        assertEquals(Screen.Setup(GameMode.VS_AI), nav.current.screen)
        nav.back()
        vm.onHotseat()
        assertEquals(Screen.Setup(GameMode.HOTSEAT), nav.current.screen)
    }

    @Test
    fun doubleTapOpensSetupOnlyOnce() {
        vm.onBattle()
        vm.onBattle()
        vm.onHotseat()
        assertEquals(listOf(Screen.MainMenu, Screen.Setup(GameMode.VS_AI)), nav.stack.value.map { it.screen })
    }

    @Test
    fun reducedEffectsSettingTurnsSceneAnimationOff() {
        vm.state
        idle()
        assertFalse(vm.state.value.reducedEffects)
        repo.settingsState.value = AppSettings(reducedEffects = true)
        idle()
        assertTrue(vm.state.value.reducedEffects)
    }

    @Test
    fun settingsOpensSettingsScreen() {
        vm.onSettings()
        assertEquals(Screen.Settings, nav.current.screen)
    }

    @Test
    fun tutorialAndCreditsShowDialogsThatCanBeDismissed() {
        vm.onTutorial()
        assertEquals(MenuDialog.TUTORIAL_SOON, vm.state.value.dialog)
        assertEquals(Screen.MainMenu, nav.current.screen)
        vm.onCredits()
        assertEquals(MenuDialog.CREDITS, vm.state.value.dialog)
        vm.onDismissDialog()
        assertNull(vm.state.value.dialog)
    }
}

class SetupViewModelTest : ViewModelTestBase() {
    private val nav = Navigator()
    private val repo = FakeSettingsRepository()
    private var seed = 100L

    private fun vm(mode: GameMode = GameMode.VS_AI) = SetupViewModel(mode, repo, nav) { seed++ }

    @Test
    fun defaultsMatchMockup() {
        val vm = vm()
        idle()
        val s = vm.state.value
        assertEquals(MapOption.SCHLUCHT, s.map)
        assertEquals(AiLevel.NORMAL, s.aiLevel)
        assertEquals(StartResources.NORMAL, s.resources)
        assertEquals(TeamColor.BLUE, s.team)
    }

    @Test
    fun restoresLastSavedChoices() {
        repo.setupState.value = SetupPrefs(MapOption.HUEGEL, AiLevel.HARD, StartResources.RICH, TeamColor.RED)
        val vm = vm()
        idle()
        assertEquals(MapOption.HUEGEL, vm.state.value.map)
        assertEquals(AiLevel.HARD, vm.state.value.aiLevel)
        assertEquals(StartResources.RICH, vm.state.value.resources)
        assertEquals(TeamColor.RED, vm.state.value.team)
    }

    @Test
    fun userChoiceBeforeRestoreIsNotOverwritten() {
        repo.setupState.value = SetupPrefs(map = MapOption.HUEGEL)
        val vm = vm()
        vm.selectMap(MapOption.SCHLUCHT)
        idle()
        assertEquals(MapOption.SCHLUCHT, vm.state.value.map)
    }

    @Test
    fun savedPrefsAreMergedPerFieldWhenUserAlreadyChangedOne() {
        repo.setupState.value = SetupPrefs(MapOption.HUEGEL, AiLevel.HARD, StartResources.RICH, TeamColor.RED)
        val vm = vm()
        vm.selectAiLevel(AiLevel.EASY)
        idle()
        val s = vm.state.value
        assertEquals(AiLevel.EASY, s.aiLevel, "edited field must survive")
        assertEquals(MapOption.HUEGEL, s.map, "untouched fields come from the saved prefs")
        assertEquals(StartResources.RICH, s.resources)
        assertEquals(TeamColor.RED, s.team)
    }

    @Test
    fun doubleTapOnStartPushesOnlyOneGame() {
        val vm = vm()
        idle()
        vm.onStart()
        vm.onStart()
        idle()
        assertEquals(2, nav.stack.value.size)
        assertEquals(1, repo.saveSetupCalls)
    }

    @Test
    fun startPushesGameWithConfigAndPersistsChoices() {
        val vm = vm()
        idle()
        vm.selectMap(MapOption.HUEGEL)
        vm.selectAiLevel(AiLevel.EASY)
        vm.selectResources(StartResources.SCARCE)
        vm.selectTeam(TeamColor.RED)
        vm.onStart()
        idle()
        val game = assertIs<Screen.Game>(nav.current.screen)
        assertEquals(
            MatchConfig(MapOption.HUEGEL, GameMode.VS_AI, AiLevel.EASY, StartResources.SCARCE, TeamColor.RED, seed = 100L),
            game.config,
        )
        assertEquals(SetupPrefs(MapOption.HUEGEL, AiLevel.EASY, StartResources.SCARCE, TeamColor.RED), repo.setupState.value)
        assertEquals(1, repo.saveSetupCalls)
    }

    @Test
    fun hotseatSetupProducesHotseatConfig() {
        val vm = vm(GameMode.HOTSEAT)
        idle()
        vm.onStart()
        assertEquals(GameMode.HOTSEAT, assertIs<Screen.Game>(nav.current.screen).config.mode)
    }

    @Test
    fun eachStartGetsAFreshSeed() {
        val vm = vm()
        idle()
        val a = vm.buildConfig().seed
        val b = vm.buildConfig().seed
        assertTrue(a != b)
    }

    @Test
    fun backPopsSetup() {
        nav.push(Screen.Setup(GameMode.VS_AI))
        vm().onBack()
        assertEquals(Screen.MainMenu, nav.current.screen)
    }

    @Test
    fun staleBackDoesNotPopTheScreenAbove() {
        nav.push(Screen.Setup(GameMode.VS_AI))
        val vm = vm()
        vm.onStart()
        vm.onBack()
        assertIs<Screen.Game>(nav.current.screen)
        assertEquals(3, nav.stack.value.size)
    }
}

class SettingsViewModelTest : ViewModelTestBase() {
    private val repo = FakeSettingsRepository()
    private val vm by lazy { SettingsViewModel(repo) }

    @Test
    fun stateIsNullUntilTheStoreHasEmitted() {
        assertNull(vm.state.value, "no default values may flash before the first emission")
        idle()
        assertEquals(AppSettings(), vm.state.value)
    }

    @Test
    fun storedValuesAreShownFromTheFirstEmission() {
        repo.settingsState.value = AppSettings(soundVolume = 0.2f, leftHanded = true)
        vm.state // ViewModel anlegen (startet das Sammeln), dann den ersten Wert abwarten
        idle()
        assertEquals(AppSettings(soundVolume = 0.2f, leftHanded = true), vm.state.value)
    }

    @Test
    fun reflectsRepositoryAndPersistsEveryChange() {
        vm.state
        idle()
        assertEquals(AppSettings(), vm.state.value)
        vm.setSoundVolume(0.1f)
        vm.setMusicVolume(0.9f)
        vm.setLeftHanded(true)
        vm.setReleaseToFire(true)
        vm.setReducedEffects(true)
        idle()
        val expected = AppSettings(soundVolume = 0.1f, musicVolume = 0.9f, leftHanded = true, releaseToFire = true, reducedEffects = true)
        assertEquals(expected, repo.settingsState.value)
        assertEquals(expected, vm.state.value)
    }

    @Test
    fun volumesAreClamped() {
        vm.setSoundVolume(5f)
        vm.setMusicVolume(-1f)
        idle()
        assertEquals(1f, repo.settingsState.value.soundVolume)
        assertEquals(0f, repo.settingsState.value.musicVolume)
    }

    @Test
    fun externalChangesFlowIntoState() {
        vm.state.value
        idle()
        repo.settingsState.value = AppSettings(leftHanded = true)
        idle()
        assertEquals(true, vm.state.value?.leftHanded)
    }
}

class GameViewModelTest : ViewModelTestBase() {
    private val nav = Navigator()
    private val config = MatchConfig(seed = 7)
    private val hotseat = MatchConfig(mode = GameMode.HOTSEAT, seed = 8)

    private val red = MatchConfig(team = TeamColor.RED, seed = 9)

    private fun vm(c: MatchConfig = config, seconds: Int = 3): GameViewModel {
        nav.push(Screen.Game(c))
        return GameViewModel(c, nav, handoverSeconds = seconds, tickMillis = 1000)
    }

    private fun turnView(player: Int, turn: Int, phase: TurnPhase, mode: TurnMode = TurnMode.TURNS) = object : TurnView {
        override val mode = mode
        override val activePlayer = player
        override val turnNumber = turn
        override val ticksLeft = 0
        override val phase = phase
    }

    @Test
    fun pauseResumeAndSuspendedFlag() {
        val vm = vm()
        assertFalse(vm.state.value.isSuspended)
        vm.pause()
        assertEquals(GameOverlay.Pause, vm.state.value.overlay)
        assertTrue(vm.state.value.isSuspended)
        vm.resume()
        assertEquals(GameOverlay.None, vm.state.value.overlay)
    }

    @Test
    fun systemBackPausesThenResumes() {
        val vm = vm()
        vm.onSystemBack()
        assertEquals(GameOverlay.Pause, vm.state.value.overlay)
        vm.onSystemBack()
        assertEquals(GameOverlay.None, vm.state.value.overlay)
        assertEquals(Screen.Game(config), nav.current.screen)
    }

    @Test
    fun systemBackWalksOutOfNestedOverlays() {
        val vm = vm()
        vm.pause()
        vm.openSettings()
        assertEquals(GameOverlay.Settings, vm.state.value.overlay)
        vm.onSystemBack()
        assertEquals(GameOverlay.Pause, vm.state.value.overlay)
        vm.requestConfirm(PauseAction.SURRENDER)
        assertEquals(GameOverlay.Confirm(PauseAction.SURRENDER), vm.state.value.overlay)
        vm.onSystemBack()
        assertEquals(GameOverlay.Pause, vm.state.value.overlay)
    }

    @Test
    fun overlaysOnlyOpenFromPause() {
        val vm = vm()
        vm.openSettings()
        vm.requestConfirm(PauseAction.RESTART)
        assertEquals(GameOverlay.None, vm.state.value.overlay)
    }

    @Test
    fun restartBumpsGenerationAndResumes() {
        val vm = vm()
        vm.pause()
        vm.requestConfirm(PauseAction.RESTART)
        vm.confirm()
        assertEquals(1, vm.state.value.matchGeneration)
        assertEquals(GameOverlay.None, vm.state.value.overlay)
        assertEquals(1, vm.state.value.turn)
    }

    @Test
    fun surrenderLeadsToDefeatResult() {
        val vm = vm()
        vm.pause()
        vm.requestConfirm(PauseAction.SURRENDER)
        vm.confirm()
        val result = assertIs<Screen.Result>(nav.current.screen).result
        assertFalse(result.isVictory)
        assertEquals(EndReason.SURRENDER, result.reason)
        assertEquals(config, result.config)
        assertEquals(2, nav.stack.value.size, "Game must be replaced, not stacked")
    }

    @Test
    fun redTeamStartsAsPlayerOneAndSurrenderIsADefeat() {
        val vm = vm(red)
        assertEquals(1, vm.state.value.activePlayer)
        vm.pause()
        vm.requestConfirm(PauseAction.SURRENDER)
        vm.confirm()
        val result = assertIs<Screen.Result>(nav.current.screen).result
        assertFalse(result.isVictory)
        assertEquals(0, result.winnerPlayerId)
        assertEquals(EndReason.SURRENDER, result.reason)
        assertEquals(1, result.bannerPlayerId)
    }

    @Test
    fun restartKeepsTheHumanSideForRedTeam() {
        val vm = vm(red)
        vm.pause()
        vm.requestConfirm(PauseAction.RESTART)
        vm.confirm()
        assertEquals(1, vm.state.value.activePlayer)
        vm.pause()
        vm.requestConfirm(PauseAction.SURRENDER)
        vm.confirm()
        assertFalse(assertIs<Screen.Result>(nav.current.screen).result.isVictory)
    }

    @Test
    fun finishMatchAfterLeavingToMainMenuDoesNotTouchTheRoot() {
        val vm = vm()
        vm.pause()
        vm.requestConfirm(PauseAction.MAIN_MENU)
        vm.confirm()
        vm.finishMatch(MatchResult(config, 0, EndReason.ENEMY_REACTOR_DESTROYED))
        assertEquals(listOf(Screen.MainMenu), nav.stack.value.map { it.screen })
    }

    @Test
    fun finishMatchForAPoppedGameDoesNotReplaceTheScreenAbove() {
        val vm = vm()
        nav.push(Screen.Settings)
        vm.finishMatch(MatchResult(config, 0, EndReason.ENEMY_REACTOR_DESTROYED))
        assertEquals(Screen.Settings, nav.current.screen)
        assertEquals(3, nav.stack.value.size)
    }

    @Test
    fun finishMatchTwiceYieldsOneResult() {
        val vm = vm()
        val r = MatchResult(config, 0, EndReason.ENEMY_REACTOR_DESTROYED)
        vm.finishMatch(r)
        vm.finishMatch(r.copy(winnerPlayerId = 1))
        assertEquals(Screen.Result(r), nav.current.screen)
        assertEquals(2, nav.stack.value.size)
    }

    @Test
    fun mainMenuConfirmPopsToRoot() {
        val vm = vm()
        vm.pause()
        vm.requestConfirm(PauseAction.MAIN_MENU)
        vm.confirm()
        assertEquals(Screen.MainMenu, nav.current.screen)
        assertEquals(1, nav.stack.value.size)
    }

    @Test
    fun cancelConfirmReturnsToPause() {
        val vm = vm()
        vm.pause()
        vm.requestConfirm(PauseAction.MAIN_MENU)
        vm.cancelConfirm()
        assertEquals(GameOverlay.Pause, vm.state.value.overlay)
    }

    @Test
    fun endTurnIsIgnoredAgainstAi() {
        val vm = vm()
        vm.endTurn()
        assertEquals(GameOverlay.None, vm.state.value.overlay)
    }

    @Test
    fun hotseatHandoverStaysHiddenUntilReadyIsTapped() {
        val vm = vm(hotseat)
        vm.endTurn()
        val first = assertIs<GameOverlay.Handover>(vm.state.value.overlay).state
        assertEquals(1, first.player)
        assertEquals(2, first.turn)
        assertEquals(null, first.secondsLeft, "no countdown before Bereit")
        assertTrue(vm.state.value.boardHidden)
        advanceMillis(60_000)
        val later = assertIs<GameOverlay.Handover>(vm.state.value.overlay).state
        assertEquals(null, later.secondsLeft)
        assertTrue(vm.state.value.boardHidden, "board must stay covered without input")
        assertEquals(1, vm.state.value.activePlayer)
    }

    @Test
    fun readyStartsCountdownAndRevealsBoardAtZero() {
        val vm = vm(hotseat)
        vm.endTurn()
        vm.onHandoverReady()
        assertEquals(3, assertIs<GameOverlay.Handover>(vm.state.value.overlay).state.secondsLeft)
        advanceMillis(1000)
        assertEquals(2, assertIs<GameOverlay.Handover>(vm.state.value.overlay).state.secondsLeft)
        advanceMillis(1000)
        assertEquals(1, assertIs<GameOverlay.Handover>(vm.state.value.overlay).state.secondsLeft)
        assertTrue(vm.state.value.boardHidden)
        advanceMillis(1000)
        assertEquals(GameOverlay.None, vm.state.value.overlay)
        assertEquals(1, vm.state.value.activePlayer)
    }

    @Test
    fun secondReadyTapDuringCountdownDoesNotRestartIt() {
        val vm = vm(hotseat)
        vm.endTurn()
        vm.onHandoverReady()
        advanceMillis(1000)
        vm.onHandoverReady()
        assertEquals(2, assertIs<GameOverlay.Handover>(vm.state.value.overlay).state.secondsLeft)
        advanceMillis(2000)
        assertEquals(GameOverlay.None, vm.state.value.overlay)
    }

    @Test
    fun nextTurnHandsBackToPlayerZero() {
        val vm = vm(hotseat)
        vm.endTurn()
        vm.onHandoverReady()
        advanceMillis(3000)
        vm.endTurn()
        val h = assertIs<GameOverlay.Handover>(vm.state.value.overlay).state
        assertEquals(0, h.player)
        assertEquals(3, h.turn)
    }

    @Test
    fun backgroundingDuringPlayPausesTheMatch() {
        val vm = vm()
        vm.onAppBackgrounded()
        assertEquals(GameOverlay.Pause, vm.state.value.overlay)
        vm.onAppBackgrounded()
        assertEquals(GameOverlay.Pause, vm.state.value.overlay)
    }

    @Test
    fun backgroundingKeepsOtherOverlays() {
        val vm = vm()
        vm.pause()
        vm.requestConfirm(PauseAction.SURRENDER)
        vm.onAppBackgrounded()
        assertEquals(GameOverlay.Confirm(PauseAction.SURRENDER), vm.state.value.overlay)
    }

    @Test
    fun backgroundingWhileHandoverWaitsKeepsBoardHidden() {
        val vm = vm(hotseat)
        vm.endTurn()
        vm.onAppBackgrounded()
        advanceMillis(10_000)
        assertTrue(vm.state.value.boardHidden)
        assertEquals(null, assertIs<GameOverlay.Handover>(vm.state.value.overlay).state.secondsLeft)
    }

    @Test
    fun backgroundingDuringHandoverCountdownAbortsItAndNeverRevealsTheBoard() {
        val vm = vm(hotseat)
        vm.endTurn()
        vm.onHandoverReady()
        advanceMillis(1000)
        vm.onAppBackgrounded()
        assertEquals(null, assertIs<GameOverlay.Handover>(vm.state.value.overlay).state.secondsLeft)
        advanceMillis(30_000)
        assertTrue(vm.state.value.boardHidden, "countdown must not tick in the background")
        // Nach der Rückkehr muss erneut „Bereit" getippt werden.
        vm.onHandoverReady()
        assertEquals(3, assertIs<GameOverlay.Handover>(vm.state.value.overlay).state.secondsLeft)
        advanceMillis(3000)
        assertEquals(GameOverlay.None, vm.state.value.overlay)
    }

    @Test
    fun zeroSecondHandoverRevealsOnReady() {
        val vm = vm(hotseat, seconds = 0)
        vm.endTurn()
        vm.onHandoverReady()
        assertEquals(GameOverlay.None, vm.state.value.overlay)
    }

    @Test
    fun turnViewHandoverPhaseOpensHandoverForThatPlayer() {
        val vm = vm(hotseat)
        vm.onTurnView(turnView(player = 1, turn = 4, phase = TurnPhase.HANDOVER))
        val h = assertIs<GameOverlay.Handover>(vm.state.value.overlay).state
        assertEquals(HandoverState(player = 1, turn = 4, secondsLeft = null, totalSeconds = 3), h)
        assertEquals(1, vm.state.value.activePlayer)
        assertEquals(4, vm.state.value.turn)
        // Wiederholte Meldung derselben Übergabe setzt einen laufenden Countdown nicht zurück.
        vm.onHandoverReady()
        advanceMillis(1000)
        vm.onTurnView(turnView(player = 1, turn = 4, phase = TurnPhase.HANDOVER))
        assertEquals(2, assertIs<GameOverlay.Handover>(vm.state.value.overlay).state.secondsLeft)
    }

    @Test
    fun turnViewPlayPhaseOnlySyncsPlayerAndTurn() {
        val vm = vm(hotseat)
        vm.onTurnView(turnView(player = 1, turn = 2, phase = TurnPhase.PLAY))
        assertEquals(GameOverlay.None, vm.state.value.overlay)
        assertEquals(1, vm.state.value.activePlayer)
        assertEquals(2, vm.state.value.turn)
    }

    @Test
    fun turnViewIsIgnoredAgainstAiAndInRealtime() {
        val vm = vm()
        vm.onTurnView(turnView(player = 1, turn = 2, phase = TurnPhase.HANDOVER))
        assertEquals(GameOverlay.None, vm.state.value.overlay)
        val hs = vm(hotseat)
        hs.onTurnView(turnView(player = -1, turn = 1, phase = TurnPhase.HANDOVER, mode = TurnMode.REALTIME))
        assertEquals(GameOverlay.None, hs.state.value.overlay)
    }

    @Test
    fun tipsStartWithTheFirstTipAndCycle() {
        assertEquals(0, handoverTipIndex(turn = 2, tipCount = 5))
        assertEquals(1, handoverTipIndex(turn = 3, tipCount = 5))
        assertEquals(0, handoverTipIndex(turn = 7, tipCount = 5))
        assertEquals(4, handoverTipIndex(turn = 1, tipCount = 5))
    }

    @Test
    fun backDuringHandoverIsIgnoredAndPauseCannotOpen() {
        val vm = vm(hotseat)
        vm.endTurn()
        vm.onSystemBack()
        vm.pause()
        assertIs<GameOverlay.Handover>(vm.state.value.overlay)
    }

    @Test
    fun hotseatSurrenderMakesActivePlayerLose() {
        val vm = vm(hotseat)
        vm.endTurn()
        vm.onHandoverReady()
        advanceMillis(3000)
        vm.pause()
        vm.requestConfirm(PauseAction.SURRENDER)
        vm.confirm()
        val result = assertIs<Screen.Result>(nav.current.screen).result
        assertEquals(0, result.winnerPlayerId)
        assertTrue(result.isVictory)
    }

    @Test
    fun finishMatchReplacesGameWithResult() {
        val vm = vm()
        val r = MatchResult(config, winnerPlayerId = 0, reason = EndReason.ENEMY_REACTOR_DESTROYED)
        vm.finishMatch(r)
        assertEquals(Screen.Result(r), nav.current.screen)
    }

    @Test
    fun sampleFinishRespectsHumanSide() {
        val red = MatchConfig(team = TeamColor.RED)
        val vm = vm(red)
        vm.finishWithSampleStats(humanWins = true)
        val result = assertIs<Screen.Result>(nav.current.screen).result
        assertEquals(1, result.winnerPlayerId)
        assertTrue(result.isVictory)
    }
}

class ResultViewModelTest : ViewModelTestBase() {
    private val nav = Navigator()
    private val config = MatchConfig(map = MapOption.HUEGEL, aiLevel = AiLevel.HARD, seed = 1)
    private val result = MatchResult(config, 0, EndReason.ENEMY_REACTOR_DESTROYED)

    @Test
    fun rematchKeepsConfigWithNewSeed() {
        nav.push(Screen.Result(result))
        var seed = 99L
        ResultViewModel(result, nav) { seed++ }.onRematch()
        val game = assertIs<Screen.Game>(nav.current.screen)
        assertEquals(config.copy(seed = 99L), game.config)
        assertEquals(2, nav.stack.value.size)
    }

    @Test
    fun doubleRematchStartsOnlyOneGame() {
        nav.push(Screen.Result(result))
        var seed = 1L
        val vm = ResultViewModel(result, nav) { seed++ }
        vm.onRematch()
        vm.onRematch()
        assertEquals(2, nav.stack.value.size)
        assertIs<Screen.Game>(nav.current.screen)
    }

    @Test
    fun mainMenuPopsToRoot() {
        nav.push(Screen.Setup(GameMode.VS_AI))
        nav.push(Screen.Result(result))
        ResultViewModel(result, nav) { 0L }.onMainMenu()
        assertEquals(Screen.MainMenu, nav.current.screen)
        assertEquals(1, nav.stack.value.size)
    }
}

class SliderDragStateTest {
    @Test
    fun dragUpdatesValueAndFinishReturnsIt() {
        val st = SliderDragState(0.8f)
        st.onDrag(0.3f)
        assertEquals(0.3f, st.value)
        assertEquals(0.3f, st.onFinish())
    }

    @Test
    fun valueIsClamped() {
        val st = SliderDragState(2f)
        assertEquals(1f, st.value)
        st.onDrag(-1f)
        assertEquals(0f, st.value)
    }

    @Test
    fun externalEmissionsDuringDragDoNotMoveTheThumb() {
        val st = SliderDragState(0.8f) // Standardwert vor dem ersten DataStore-Wert
        st.syncExternal(0.2f) // gespeicherter Wert trifft ein
        assertEquals(0.2f, st.value)
        st.onDrag(0.5f)
        st.syncExternal(0.9f) // Emission mitten in der Geste
        assertEquals(0.5f, st.value)
        assertEquals(0.5f, st.onFinish())
        st.syncExternal(0.5f) // gespeicherter Wert nach dem Loslassen
        assertEquals(0.5f, st.value)
    }

    @Test
    fun secondDragInTheSameSessionStillFollowsTheFinger() {
        val st = SliderDragState(0.8f)
        st.syncExternal(0.4f)
        st.onDrag(0.6f); st.onFinish(); st.syncExternal(0.6f)
        st.onDrag(0.7f)
        assertEquals(0.7f, st.value)
        assertTrue(st.dragging)
        st.onFinish()
        assertFalse(st.dragging)
    }
}
