package de.bollwerk.app.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import de.bollwerk.app.game.FakeClock
import de.bollwerk.app.game.GameController
import de.bollwerk.app.game.GameRuntime
import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.MatchSessions
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.nav.Navigator
import de.bollwerk.app.nav.Screen
import de.bollwerk.app.tutorial.TutorialHint
import de.bollwerk.app.tutorial.TutorialStatus
import de.bollwerk.app.tutorial.TutorialStep
import de.bollwerk.app.tutorial.TutorialUiModel
import de.bollwerk.app.ui.ViewModelTestBase
import de.bollwerk.engine.tools.PointerPhase
import de.bollwerk.engine.tools.ToolSelection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GameViewModel] im Tutorial-Gefecht mit echter Sim: Coach-Zustand, Überspringen, Abschluss und Ablage, Neustart. */
class TutorialGameViewModelTest : ViewModelTestBase() {
    private val nav = Navigator()
    private val clock = FakeClock()
    private val created = ArrayList<GameRuntime>()
    private val factory = GameRuntimeFactory { cfg ->
        GameRuntime(GameController(MatchSessions.create(HudFixtures.db, cfg), clock)).also { synchronized(created) { created += it } }
    }
    private val store = ViewModelStore()
    private val finished = ArrayList<Boolean>()
    private var vmCount = 0

    @AfterTest
    fun cleanUp() {
        store.clear()
        synchronized(created) { created.forEach { it.stop() } }
    }

    private fun vm(c: MatchConfig = MatchConfig.tutorial()): GameViewModel {
        nav.push(Screen.Game(c))
        val f = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GameViewModel(
                c, nav, runtimeFactory = factory, endDelayMillis = 0L, workDispatcher = dispatcher,
                tutorialStore = TutorialStore { completed -> finished += completed },
            ) as T
        }
        val vm = ViewModelProvider(store, f)["game-${++vmCount}", GameViewModel::class.java]
        idle()
        return vm
    }

    private fun runUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timeout: $what")
            clock.advanceMs(16.0)
            Thread.sleep(4)
            idle()
        }
    }

    private fun tutorial(vm: GameViewModel): TutorialUiModel = assertNotNull(vm.tutorial.value, "tutorial model")

    @Test
    fun ordinaryMatchesHaveNoCoach() {
        val vm = vm(MatchConfig(seed = 3))
        assertNull(vm.tutorial.value)
    }

    @Test
    fun tutorialMatchStartsAtStepOne() {
        val vm = vm()
        val model = tutorial(vm)
        assertEquals(TutorialStep.PLACE_BEAM, model.state.step)
        assertEquals(TutorialHint.PICK_WOOD, model.state.hint)
        assertFalse(assertNotNull(vm.runtime.value).session.tutorial!!.gatesOpen)
    }

    @Test
    fun toolSelectionAndHudFlowIntoTheHint() {
        val vm = vm()
        vm.selectTool(ToolSelection.Material(HudFixtures.material("wood")))
        runUntil("draw-beam hint") { tutorial(vm).state.hint == TutorialHint.DRAW_BEAM }
        vm.enterAimMode()
        runUntil("back-to-build hint") { tutorial(vm).state.hint == TutorialHint.BACK_TO_BUILD }
    }

    @Test
    fun skipEndsTheCoachReleasesTheEnemyAndStoresASkip() {
        val vm = vm()
        val driver = assertNotNull(vm.runtime.value).session.tutorial!!
        vm.skipTutorial()
        idle()
        assertEquals(TutorialStatus.SKIPPED, tutorial(vm).state.status)
        assertTrue(driver.gatesOpen)
        assertEquals(listOf(false), finished)
        vm.skipTutorial()
        idle()
        assertEquals(listOf(false), finished, "stored once")
    }

    @Test
    fun theMortarShotCompletesTheTutorialAndStoresIt() {
        val vm = vm()
        val rt = assertNotNull(vm.runtime.value)
        val c = rt.controller
        val t = rt.session.tutorial!!.targets
        val wood = HudFixtures.material("wood")
        val mine = HudFixtures.device("mine")

        vm.selectTool(ToolSelection.Material(wood))
        runUntil("hint") { tutorial(vm).state.hint == TutorialHint.DRAW_BEAM }
        c.pointer(PointerPhase.DOWN, t.beamA.x, t.beamA.y, 1f, 1L)
        runUntil("down handled") { c.pendingInputs == 0 }
        c.pointer(PointerPhase.MOVE, t.beamB.x, t.beamB.y, 1f, 1L)
        c.pointer(PointerPhase.UP, t.beamB.x, t.beamB.y, 1f, 1L)
        runUntil("step 2") { tutorial(vm).state.step == TutorialStep.BUILD_MINE }

        vm.selectTool(ToolSelection.Device(mine))
        runUntil("place-mine hint") { tutorial(vm).state.hint == TutorialHint.PLACE_MINE }
        c.pointer(PointerPhase.DOWN, t.mineSpot.x, t.mineSpot.y, 1f, 2L)
        c.pointer(PointerPhase.UP, t.mineSpot.x, t.mineSpot.y, 1f, 2L)
        runUntil("step 3") { tutorial(vm).state.step == TutorialStep.FIRE_MORTAR }
        assertFalse(rt.session.tutorial!!.gatesOpen)
        assertTrue(finished.isEmpty())

        vm.enterAimMode()
        runUntil("drag hint with the mortar chosen") {
            tutorial(vm).state.hint == TutorialHint.AIM_AND_FIRE && vm.hud.value.aim.weaponDeviceId == "mortar"
        }
        vm.fire()
        runUntil("completed") { tutorial(vm).state.status == TutorialStatus.COMPLETED }
        assertTrue(rt.session.tutorial!!.gatesOpen)
        runUntil("stored") { finished.isNotEmpty() }
        assertEquals(listOf(true), finished)
        vm.closeTutorial()
        idle()
        assertEquals(TutorialStatus.CLOSED, tutorial(vm).state.status)
        assertEquals(listOf(true), finished)
    }

    @Test
    fun restartBuildsAFreshTutorialWithTheEnemyAtRest() {
        val vm = vm()
        vm.skipTutorial()
        idle()
        assertEquals(TutorialStatus.SKIPPED, tutorial(vm).state.status)
        vm.pause()
        vm.requestConfirm(PauseAction.RESTART)
        vm.confirm()
        idle()
        val fresh = assertNotNull(vm.runtime.value)
        assertFalse(fresh.session.tutorial!!.gatesOpen)
        assertEquals(TutorialStatus.RUNNING, tutorial(vm).state.status)
        assertEquals(TutorialStep.PLACE_BEAM, tutorial(vm).state.step)
    }

    @Test
    fun leavingToTheMainMenuClearsTheCoach() {
        val vm = vm()
        vm.pause()
        vm.requestConfirm(PauseAction.MAIN_MENU)
        vm.confirm()
        assertNull(vm.tutorial.value)
    }
}
