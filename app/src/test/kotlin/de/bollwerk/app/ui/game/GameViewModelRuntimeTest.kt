package de.bollwerk.app.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import de.bollwerk.app.game.FakeClock
import de.bollwerk.app.game.GameController
import de.bollwerk.app.game.GameRuntime
import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.MatchSessions
import de.bollwerk.app.match.EndReason
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.nav.Navigator
import de.bollwerk.app.nav.Screen
import de.bollwerk.app.ui.ViewModelTestBase
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolSelection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GameViewModel] mit echter Verdrahtung: [GameRuntimeFactory] baut Partien über `MatchSessions` (→ `MatchBootstrap`)
 * mit echtem Sim-Thread ([GameController]), nur die Uhr ist falsch ([FakeClock]: Sim-Zeit läuft, wenn der Test sie
 * weiterschiebt). Prüft Anlegen, Pause bis in den Thread, Neustart/Hauptmenü/`onCleared` ohne übrig bleibende Sim-Threads,
 * Partieende mit Kennzahlen aus dem Zähler und das Befehlsrecht im Hotseat.
 */
class GameViewModelRuntimeTest : ViewModelTestBase() {
    private val nav = Navigator()
    private val clock = FakeClock()
    private val created = ArrayList<GameRuntime>()
    private val factory = GameRuntimeFactory { cfg ->
        GameRuntime(GameController(MatchSessions.create(HudFixtures.db, cfg), clock)).also { synchronized(created) { created += it } }
    }
    private val store = ViewModelStore()
    private var vmCount = 0

    @AfterTest
    fun cleanUp() {
        store.clear()
        synchronized(created) { created.forEach { it.stop() } }
    }

    private fun vm(c: MatchConfig): GameViewModel {
        nav.push(Screen.Game(c))
        val f = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GameViewModel(c, nav, runtimeFactory = factory, endDelayMillis = 0L, workDispatcher = dispatcher) as T
        }
        val vm = ViewModelProvider(store, f)["game-${++vmCount}", GameViewModel::class.java]
        idle()
        return vm
    }

    /** Sim-Zeit weiterschieben und Main-Dispatcher (HUD-Flows, Partieende) abarbeiten, bis [cond] gilt. */
    private fun runUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timeout: $what")
            clock.advanceMs(16.0)
            Thread.sleep(4)
            idle()
        }
    }

    private fun runtimeOf(vm: GameViewModel): GameRuntime = assertNotNull(vm.runtime.value, "runtime after load")

    private fun noRunningSimThreads() = synchronized(created) { created.none { it.controller.isRunning } }

    @Test
    fun loadStartsTheSimThreadAndPauseParksIt() {
        val vm = vm(MatchConfig(seed = 21))
        assertFalse(vm.state.value.loading)
        val c = runtimeOf(vm).controller
        assertTrue(c.isRunning)
        assertTrue(vm.simRunning.value)
        runUntil("sim runs") { c.runner.tick > 5 }
        vm.pause()
        runUntil("thread waits while paused") { c.threadState == Thread.State.WAITING }
        val tick = c.runner.tick
        clock.advanceMs(5_000.0)
        Thread.sleep(40)
        assertEquals(tick, c.runner.tick, "no ticks while paused")
        vm.resume()
        runUntil("runs again") { c.runner.tick > tick }
    }

    @Test
    fun restartStopsTheOldSimThreadAndStartsANewOne() {
        val vm = vm(MatchConfig(seed = 22))
        val old = runtimeOf(vm)
        vm.pause()
        vm.requestConfirm(PauseAction.RESTART)
        vm.confirm()
        assertFalse(old.controller.isRunning, "old sim thread stopped on restart")
        idle()
        val fresh = runtimeOf(vm)
        assertNotSame(old, fresh)
        assertTrue(fresh.controller.isRunning)
        assertTrue(vm.simRunning.value)
        assertEquals(1, vm.state.value.matchGeneration)
    }

    @Test
    fun mainMenuAndOnClearedLeaveNoSimThread() {
        val vm = vm(MatchConfig(seed = 23))
        val rt = runtimeOf(vm)
        vm.pause()
        vm.requestConfirm(PauseAction.MAIN_MENU)
        vm.confirm()
        assertFalse(rt.controller.isRunning)
        assertNull(vm.runtime.value)
        assertEquals(Screen.MainMenu, nav.current.screen)

        val other = vm(MatchConfig(seed = 24)).let { runtimeOf(it) }
        assertTrue(other.controller.isRunning)
        store.clear() // onCleared
        assertFalse(other.controller.isRunning, "onCleared stops the sim thread")
        assertTrue(noRunningSimThreads())
    }

    @Test
    fun simSurrenderEndsInTheResultWithStatsFromTheCounter() {
        val vm = vm(MatchConfig(seed = 25))
        val c = runtimeOf(vm).controller
        // Ein echter Schuss des Menschen, sobald eine Waffe bereit ist
        var ref = -1L
        runUntil("a weapon is ready") {
            ref = c.hud.value.weapons.firstOrNull { it.ready }?.deviceRef ?: -1L
            ref >= 0L
        }
        c.runner.input.push(Command.Fire(0L, 0, ref))
        runUntil("shot counted") { c.stats.statsFor(0, 0).shots == 1 }
        c.runner.input.push(Command.Surrender(0L, 0))
        runUntil("result screen") { nav.current.screen is Screen.Result }
        val result = assertIs<Screen.Result>(nav.current.screen).result
        assertEquals(EndReason.SURRENDER, result.reason)
        assertEquals(1, result.winnerPlayerId)
        assertFalse(result.isVictory)
        val end = assertNotNull(c.end.value)
        assertEquals(c.stats.statsFor(0, end.durationSeconds), result.stats)
        assertEquals(1, result.stats.shots)
        assertFalse(c.isRunning, "finishing the match stops the sim thread")
        assertTrue(noRunningSimThreads())
    }

    @Test
    fun hotseatResolvePhaseGreysOutTheHudAndSendsNothing() {
        val vm = vm(MatchConfig(mode = GameMode.HOTSEAT, seed = 26))
        val c = runtimeOf(vm).controller
        runUntil("hud sampled") { vm.hud.value.turn != null }
        assertTrue(vm.hud.value.canCommand)
        vm.endTurn()
        runUntil("resolve phase") { vm.hud.value.turn?.phase == TurnPhase.RESOLVE && !vm.hud.value.canCommand }
        val before = c.toolState.value
        vm.selectTool(ToolSelection.Material(HudFixtures.material("wood")))
        vm.enterAimMode()
        vm.setPower(0.4f)
        vm.fire()
        vm.undo()
        runUntil("inputs drained") { c.pendingInputs == 0 }
        clock.advanceMs(200.0)
        Thread.sleep(30)
        idle()
        assertEquals(ToolMode.NONE, c.toolState.value.mode, "no tool selection outside the own play phase")
        assertEquals(before.selection, c.toolState.value.selection)
        assertNull(vm.toast.value, "nothing is sent, so nothing is rejected")
    }
}
