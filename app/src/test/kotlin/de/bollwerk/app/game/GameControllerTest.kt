package de.bollwerk.app.game

import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.tools.PointerPhase
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolSelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Falsche Uhr: Zeit läuft nur, wenn der Test sie weiterschiebt. */
class FakeClock(@Volatile var now: Long = 1_000_000_000L) : MonotonicClock {
    override fun nanos(): Long = now
    fun advanceMs(ms: Double) { now += (ms * 1_000_000.0).toLong() }
}

class GameControllerTest {
    private val clock = FakeClock()
    private val controllers = ArrayList<GameController>()

    private fun controller(config: MatchConfig = MatchConfig(seed = 3)): GameController =
        GameController(MatchSessions.create(HudFixtures.db, config), clock).also { controllers += it }

    @AfterTest
    fun tearDown() = controllers.forEach { it.stop() }

    /** Ein Sim-Frame von [ms] Millisekunden. */
    private fun GameController.frame(ms: Double = 1000.0 / 60.0): Int {
        clock.advanceMs(ms)
        return stepOnce()
    }

    @Test
    fun ticksFollowTheRealClockAtSixtyHertz() {
        val c = controller()
        assertEquals(0, c.stepOnce(), "first step only starts the clock")
        var ticks = 0
        repeat(60) { ticks += c.frame() }
        assertTrue(ticks in 59..60, "one second = 60 ticks, got $ticks")
        assertEquals(ticks.toLong(), c.runner.tick)
    }

    @Test
    fun catchUpIsCappedAfterAStall() {
        val c = controller()
        c.stepOnce()
        val n = c.frame(5_000.0)
        assertTrue(n <= MatchSessions.MAX_TICKS_PER_ADVANCE, "a 5 s stall must not be replayed, got $n ticks")
        assertTrue(n > 0)
    }

    @Test
    fun noWorkAndNoTicksWhilePaused() {
        val c = controller()
        c.stepOnce()
        repeat(10) { c.frame() }
        val tick = c.runner.tick
        val work = c.workSteps
        c.setPaused(true)
        repeat(100) { c.frame(1000.0) }
        assertEquals(tick, c.runner.tick, "no ticks while paused")
        assertEquals(work, c.workSteps, "no work while paused")
        assertTrue(c.hud.value.paused, "pause is published once")
        c.setPaused(false)
        assertEquals(0, c.frame(10_000.0), "the paused time is not caught up")
        assertTrue(c.frame() > 0)
        assertFalse(c.hud.value.paused || c.runner.paused)
    }

    /**
     * Regression: App in den Hintergrund (Renderer abgehängt, dann Pause), zurück (Renderer angehängt). Die Pause wurde
     * übernommen, während der Runner nichts veröffentlichte; danach muss trotzdem ein Snapshot mit `hud.paused` erscheinen,
     * sonst zeichnen Renderer und Audio weiter den alten, laufenden Stand (Feuerknistern im Pause-Dialog).
     */
    @Test
    fun pauseTakenOverWhileDetachedIsPublishedAfterReattach() {
        val c = controller()
        c.stepOnce()
        repeat(10) { c.frame() }
        assertFalse(c.runner.exchange.latest()!!.hud.paused)

        // onAppBackgrounded: erst abhängen, dann pausieren
        c.setRendererAttached(false)
        c.setPaused(true)
        repeat(5) { c.frame() }
        assertFalse(c.runner.exchange.latest()!!.hud.paused, "abgehängt wird nichts veröffentlicht")

        // onAppForegrounded: Pause-Dialog bleibt offen, nur der Renderer kommt zurück
        c.setRendererAttached(true)
        c.frame()
        assertTrue(c.runner.exchange.latest()!!.hud.paused, "nach dem Anhängen steht die Pause im Snapshot")
        val seq = c.runner.exchange.latest()!!.seq
        repeat(5) { c.frame() }
        assertEquals(seq, c.runner.exchange.latest()!!.seq, "danach ruht die pausierte Sim wieder (kein Snapshot je Schritt)")
    }

    /** Wie oben, aber mit dem echten Sim-Thread: der wartende Thread wird vom Anhängen geweckt und veröffentlicht die Pause. */
    @Test
    fun waitingSimThreadPublishesPauseAfterReattach() {
        val c = controller()
        c.start()
        waitUntil { c.runner.tick > 2 && c.runner.exchange.latest() != null }
        c.setRendererAttached(false)
        c.setPaused(true)
        waitUntil { c.threadState == Thread.State.WAITING }
        assertFalse(c.runner.exchange.latest()!!.hud.paused)
        c.setRendererAttached(true)
        waitUntil { c.runner.exchange.latest()!!.hud.paused }
        waitUntil { c.threadState == Thread.State.WAITING }
        c.stop()
    }

    @Test
    fun hudIsSampledAtTenHertz() {
        val c = controller()
        c.stepOnce()
        val first = c.hud.value
        repeat(5) { c.frame() } // 83 ms
        assertEquals(first, c.hud.value, "no new HUD before 100 ms")
        c.frame(20.0)
        assertTrue(c.hud.value.timeSeconds > first.timeSeconds, "HUD after 100 ms")
    }

    @Test
    fun threadLifecycleWaitsWhilePausedAndStopsCleanly() {
        val c = controller()
        c.start()
        assertTrue(c.isRunning)
        waitUntil { c.workSteps > 3 }
        c.setPaused(true)
        waitUntil { c.threadState == Thread.State.WAITING }
        val work = c.workSteps
        clock.advanceMs(10_000.0)
        Thread.sleep(60)
        assertEquals(work, c.workSteps, "paused thread does no work")
        assertEquals(Thread.State.WAITING, c.threadState)
        c.setPaused(false)
        waitUntil { c.workSteps > work + 3 }
        c.stop()
        assertFalse(c.isRunning)
        c.stop() // idempotent
        c.start() // nach stop nicht wieder startbar
        assertFalse(c.isRunning)
    }

    @Test
    fun toolSelectionIsAppliedOnTheSimThreadAndReportedImmediately() {
        val c = controller()
        c.stepOnce()
        val wood = HudFixtures.material("wood")
        c.selectTool(ToolSelection.Material(wood))
        assertEquals(ToolMode.NONE, c.toolState.value.mode, "nothing happens before the sim step")
        assertEquals(1, c.pendingInputs)
        c.frame()
        assertEquals(ToolMode.BUILD, c.toolState.value.mode)
        assertEquals(ToolSelection.Material(wood), c.toolState.value.selection)
        assertEquals(ToolSelection.Material(wood), c.toolOverlay.tool)
    }

    @Test
    fun aimModeSelectsAnOwnWeaponAndPowerGoesThroughTheLocalInput() {
        val c = controller()
        c.stepOnce()
        c.enterAimMode()
        c.frame()
        val ts = c.toolState.value
        assertEquals(ToolMode.AIM, ts.mode)
        assertTrue(ts.weaponRef >= 0L, "the start fort has a weapon")
        c.setPower(0.5f)
        c.frame()
        c.frame()
        val id = c.runner.state.deviceView.resolve(ts.weaponRef)
        assertEquals(0.5f, c.runner.state.deviceView.power(id), 1e-4f)
    }

    @Test
    fun gestureFeedbackTellsTheInputControllerWhetherToPan() {
        val c = controller()
        c.stepOnce()
        assertNull(c.gestureConsumed(1L))
        // Kein Werkzeug: die Geste gehört der Kamera
        c.pointer(PointerPhase.DOWN, 10f, 10f, 1f, 1L)
        c.frame()
        assertEquals(false, c.gestureConsumed(1L))
        assertNull(c.gestureConsumed(2L))
    }

    @Test
    fun rejectedToolActionsAreReportedAsMessages() = runTest {
        val c = controller()
        c.stepOnce()
        val got = ArrayList<RejectReason>()
        @OptIn(ExperimentalCoroutinesApi::class)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { c.messages.toList(got) }
        c.undo()
        c.frame()
        assertEquals(listOf(RejectReason.NOTHING_TO_UNDO), got)
        job.cancel()
    }

    @Test
    fun matchEndIsReportedOnceWithTheResult() {
        val c = controller()
        c.stepOnce()
        assertNull(c.end.value)
        c.runner.input.push(Command.Surrender(0L, 0))
        c.frame()
        c.frame()
        val end = assertNotNull(c.end.value)
        val w = assertIs<GameResult.Winner>(end.result)
        assertEquals(1, w.playerId)
        assertEquals(WinReason.SURRENDER, w.reason)
        c.frame()
        assertTrue(c.end.value === end)
    }

    @Test
    fun acceptedCommandsAreCountedForTheReport() = runTest {
        val c = controller()
        val got = ArrayList<RejectReason>()
        @OptIn(ExperimentalCoroutinesApi::class)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { c.messages.toList(got) }
        c.stepOnce()
        c.enterAimMode()
        c.frame()
        val ref = c.toolState.value.weaponRef
        assertTrue(ref >= 0L, "the start fort has a weapon")
        // Bis die Waffe gebaut und geladen ist (HUD im 10-Hz-Takt)
        var n = 0
        while (c.hud.value.weapons.firstOrNull { it.deviceRef == ref }?.ready != true) {
            c.frame()
            assertTrue(++n < 60 * 60, "weapon gets ready within a minute")
        }
        assertEquals(0, c.stats.statsFor(0, 1).shots)
        c.fire()
        repeat(3) { c.frame() }
        assertEquals(1, c.stats.statsFor(0, 1).shots, "accepted Fire counts")
        assertTrue(got.isEmpty(), "first shot accepted, got $got")
        // Sofort nochmal: das Werkzeug lehnt ab (Nachladen); ein direkt gesendetes Fire lehnt die Sim ab → keine Zählung
        c.fire()
        c.runner.input.push(Command.Fire(0L, 0, ref))
        repeat(3) { c.frame() }
        assertEquals(1, c.stats.statsFor(0, 1).shots, "rejected Fire does not count")
        assertTrue(got.isNotEmpty() && got.all { it == RejectReason.RELOADING }, "reloading reported, got $got")
        assertEquals(0, c.stats.statsFor(0, 1).beamsBuilt)
        job.cancel()
    }

    @Test
    fun hotseatToolsFollowTheActivePlayerAndEndTurnStartsTheResolvePhase() {
        val c = controller(MatchConfig(mode = GameMode.HOTSEAT, seed = 5))
        c.stepOnce()
        assertEquals(0, c.hud.value.activePlayer)
        c.endTurn()
        c.frame()
        c.frame()
        assertEquals(TurnPhase.RESOLVE, c.hud.value.turnPhase, "turn change is reported without waiting for the 10 Hz sample")
    }

    private fun waitUntil(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met within $timeoutMs ms")
            clock.advanceMs(16.0)
            Thread.sleep(5)
        }
    }
}
