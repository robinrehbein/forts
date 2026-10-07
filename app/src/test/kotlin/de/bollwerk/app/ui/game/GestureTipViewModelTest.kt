package de.bollwerk.app.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import de.bollwerk.app.game.FakeClock
import de.bollwerk.app.game.GameController
import de.bollwerk.app.game.GameRuntime
import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.MatchSessions
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.nav.Navigator
import de.bollwerk.app.nav.Screen
import de.bollwerk.app.settings.GestureTip
import de.bollwerk.app.settings.GestureTips
import de.bollwerk.app.ui.ViewModelTestBase
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.view.HudModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Einmalige Gesten-Hinweise im [GameViewModel]: Reihenfolge, Dauerhaftigkeit, nie im Tutorial-Gefecht. */
class GestureTipViewModelTest : ViewModelTestBase() {
    private val nav = Navigator()
    private val clock = FakeClock()
    private val created = ArrayList<GameRuntime>()
    private val factory = GameRuntimeFactory { cfg ->
        GameRuntime(GameController(MatchSessions.create(HudFixtures.db, cfg), clock)).also { synchronized(created) { created += it } }
    }
    private val store = ViewModelStore()
    private val persisted = MutableStateFlow(GestureTips())
    private val saved = ArrayList<GestureTip>()
    private var vmCount = 0

    @AfterTest
    fun cleanUp() {
        store.clear()
        synchronized(created) { created.forEach { it.stop() } }
    }

    private fun vm(c: MatchConfig = MatchConfig(seed = 5)): GameViewModel {
        nav.push(Screen.Game(c))
        val f = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GameViewModel(
                c, nav, runtimeFactory = factory, endDelayMillis = 0L, workDispatcher = dispatcher,
                gestureTips = persisted,
                onTipSeen = { tip -> saved += tip; persisted.value = persisted.value.markSeen(tip) },
                tipDelayMillis = 1_000L, tipMillis = 5_000L,
            ) as T
        }
        val vm = ViewModelProvider(store, f)["game-${++vmCount}", GameViewModel::class.java]
        // nur fällige Aufgaben (Partie anlegen); advanceUntilIdle würde die Hinweis-Verzögerungen überspringen
        dispatcher.scheduler.runCurrent()
        return vm
    }

    @Test
    fun firstTipAppearsAfterAShortDelayAndDismissingPersistsIt() {
        val vm = vm()
        assertNull(vm.tip.value, "nicht sofort beim Start")
        advanceMillis(1_100)
        assertEquals(GestureTip.PINCH_ZOOM, vm.tip.value)
        vm.dismissTip()
        dispatcher.scheduler.runCurrent()
        assertNull(vm.tip.value)
        assertEquals(listOf(GestureTip.PINCH_ZOOM), saved)
        // der nächste folgt nach der Pause
        advanceMillis(1_100)
        assertEquals(GestureTip.DOUBLE_TAP, vm.tip.value)
    }

    @Test
    fun tipsFadeOutByThemselvesAndCountAsSeen() {
        val vm = vm()
        advanceMillis(1_100)
        assertEquals(GestureTip.PINCH_ZOOM, vm.tip.value)
        advanceMillis(5_100)
        assertEquals(listOf(GestureTip.PINCH_ZOOM), saved)
        assertNull(vm.tip.value)
    }

    @Test
    fun seenTipsNeverReturnInANewMatch() {
        persisted.value = GestureTips().markSeen(GestureTip.PINCH_ZOOM).markSeen(GestureTip.DOUBLE_TAP).markSeen(GestureTip.LONG_PRESS)
        val vm = vm()
        advanceMillis(20_000)
        assertNull(vm.tip.value)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun noTipsInTheTutorialMatch() {
        val vm = vm(MatchConfig.tutorial())
        advanceMillis(20_000)
        assertNull(vm.tip.value)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun backgroundingDuringTheCountdownDoesNotBurnTheTip() {
        val vm = vm()
        advanceMillis(500)
        vm.onAppBackgrounded()
        advanceMillis(20_000)
        assertNull(vm.tip.value)
        assertEquals(emptyList(), saved, "nie gezeigt, also nicht gesehen")
        vm.onAppForegrounded()
        vm.resume()
        advanceMillis(300)
        assertNull(vm.tip.value, "Restzeit läuft weiter: erst nach der vollen Verzögerung (noch 500 ms)")
        advanceMillis(300)
        assertEquals(GestureTip.PINCH_ZOOM, vm.tip.value)
    }

    @Test
    fun pauseFreezesAVisibleTipAndItReturnsWithItsRemainingTime() {
        val vm = vm()
        advanceMillis(1_100)
        assertEquals(GestureTip.PINCH_ZOOM, vm.tip.value)
        advanceMillis(3_000)
        vm.pause()
        advanceMillis(20_000)
        assertEquals(GestureTip.PINCH_ZOOM, vm.tip.value, "Pause hält den Countdown an")
        assertEquals(emptyList(), saved)
        vm.resume()
        advanceMillis(1_000)
        assertEquals(emptyList(), saved, "noch rund 2 s übrig")
        advanceMillis(1_500)
        assertEquals(listOf(GestureTip.PINCH_ZOOM), saved)
    }

    @Test
    fun techTreeOpenPausesTheCountdown() {
        val vm = vm()
        advanceMillis(500)
        vm.openTechTree()
        advanceMillis(20_000)
        assertNull(vm.tip.value)
        assertEquals(emptyList(), saved)
        vm.closeTechTree()
        advanceMillis(1_100)
        assertEquals(GestureTip.PINCH_ZOOM, vm.tip.value)
    }

    @Test
    fun hotseatHandoverDoesNotBurnTheTip() {
        val vm = vm(MatchConfig(mode = GameMode.HOTSEAT, seed = 5))
        advanceMillis(500)
        // Zug endet früh: Übergabe-Overlay ist offen, bis „Bereit" getippt wird; der Chip ist dort nicht zu sehen
        vm.onHud(HudModel(turnMode = TurnMode.TURNS, activePlayer = 1, turnNumber = 2, turnPhase = TurnPhase.HANDOVER, handover = true))
        assertTrue(vm.state.value.overlay is GameOverlay.Handover)
        advanceMillis(20_000)
        assertNull(vm.tip.value)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun tipsAppearAfterTheTutorialIsSkippedInTheSameMatch() {
        val vm = vm(MatchConfig.tutorial())
        advanceMillis(20_000)
        assertNull(vm.tip.value)
        vm.skipTutorial()
        dispatcher.scheduler.runCurrent()
        advanceMillis(1_100)
        assertEquals(GestureTip.PINCH_ZOOM, vm.tip.value)
    }
}
