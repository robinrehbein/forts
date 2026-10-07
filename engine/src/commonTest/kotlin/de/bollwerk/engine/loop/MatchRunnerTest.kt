package de.bollwerk.engine.loop

import de.bollwerk.engine.TestWorld
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.SystemSlot
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.GameView
import de.bollwerk.engine.view.HitTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [MatchRunner]: Takt, Fx-Sammlung über Aufhol-Ticks, Interpolation, Pause/Tempo, Input-Delay, Lockstep-Stillstand. */
class MatchRunnerTest {
    private val dt = 1f / 60f

    /** Welt ohne Regeln: jeder Tick erzeugt ein Fx-Ereignis und schiebt einen Knoten um 1 m. */
    private fun probeRunner(maxTicks: Int = 5, sources: List<CommandSource> = emptyList()): MatchRunner {
        val state = TestWorld.state()
        state.nodes.alloc(0f, 0f, 0)
        val fxSys = SimSystem { _, ctx -> ctx.fx.add(FxEvent.Hit(ctx.tick, 0f, 0f, HitTarget.TERRAIN, -1, 1f, 0)) }
        val move = SimSystem { st, _ -> st.nodes.x[0] += 1f }
        val session = GameSession(state, SimStepper(mapOf(SystemSlot.PROJECTILES to fxSys, SystemSlot.PHYSICS to move)))
        return MatchRunner(session, maxTicksPerAdvance = maxTicks, sources = sources)
    }

    @Test
    fun publishesAnInitialSnapshotAtConstruction() {
        val r = probeRunner()
        val snap = assertNotNull(r.exchange.latest())
        assertEquals(0L, snap.tick)
        assertTrue(snap.seq >= 1)
    }

    @Test
    fun fxFromEveryCatchUpTickReachesTheNextSnapshot() {
        val r = probeRunner(maxTicks = 5)
        r.exchange.latest() // Initial-Snapshot lesen
        // Ein langer Frame: 5 Ticks (Obergrenze) in einem advance
        assertEquals(5, r.advance(0.25f))
        val snap = r.exchange.latest()!!
        assertEquals(5L, snap.tick)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), snap.fx.map { it.tick })
    }

    @Test
    fun unreadEventsAccumulateInOneSnapshotInOrderAndSurviveTheEndOfTheStream() {
        val r = probeRunner()
        r.exchange.latest()
        repeat(3) { r.advance(dt) } // drei Snapshots, der Leser liest keinen
        // Ende des Stroms / Pause: kein weiterer Tick, trotzdem trägt der ungelesene Snapshot alle Ereignisse, in Reihenfolge
        r.setPaused(true)
        r.advance(0f)
        val s = r.exchange.latest()!!
        assertEquals(listOf(0L, 1L, 2L), s.fx.map { it.tick })
        assertEquals(3L, s.tick)
        // danach kommt nichts mehr nach
        r.advance(0f)
        assertTrue(r.exchange.latest()!!.fx.isEmpty() || r.exchange.latest()!!.seq == s.seq)
    }

    @Test
    fun deliveryAcrossSnapshotsStaysInOrderAndWithoutDuplicatesWhenTheReaderLags() {
        val r = probeRunner()
        r.exchange.latest()
        val delivered = ArrayList<Long>()
        var lastSeq = -1L
        // Leser liest nur jeden dritten Snapshot (abwechselnd Schreiber schneller)
        for (i in 0 until 60) {
            r.advance(dt)
            if (i % 3 == 2) {
                val s = r.exchange.latest()!!
                if (s.seq != lastSeq) { lastSeq = s.seq; delivered.addAll(s.fx.map { it.tick }) }
            }
        }
        r.advance(0f)
        val s = r.exchange.latest()!!
        if (s.seq != lastSeq) delivered.addAll(s.fx.map { it.tick })
        assertEquals((0L until 60L).toList(), delivered, "complete, once, in tick order")
    }

    @Test
    fun carriedFxOlderThanTheAgeLimitAgesOutWhenNobodyReads() {
        val r = probeRunner(maxTicks = 5)
        r.exchange.latest()
        repeat(40) { r.advance(0.25f) } // 200 Ticks ohne Leser
        val s = r.exchange.latest()!!
        val oldest = s.fx.minOf { it.tick }
        assertTrue(oldest >= r.tick - 5 - de.bollwerk.engine.view.SnapshotBuilder.MAX_CARRIED_FX_AGE_TICKS, "oldest $oldest at tick ${r.tick}")
        assertTrue(s.fx.size <= 5 + de.bollwerk.engine.view.SnapshotBuilder.MAX_CARRIED_FX_AGE_TICKS + 5)
    }

    @Test
    fun detachedRendererGetsNoPileOfOldEventsAndNoPublishUntilReattached() {
        val r = probeRunner(maxTicks = 5)
        r.exchange.latest()
        r.setRendererAttached(false)
        val before = r.publishedSeq
        repeat(30) { r.advance(0.1f) } // 3 s Spielzeit, Surface weg
        assertEquals(before, r.publishedSeq, "nothing published while detached")
        assertTrue(r.tick > 100)
        r.setRendererAttached(true)
        r.advance(0f)
        assertTrue(r.publishedSeq > before, "fresh snapshot right after attaching")
        val s = r.exchange.latest()!!
        assertEquals(r.tick, s.tick)
        assertTrue(s.fx.isEmpty(), "no old explosions replayed: ${s.fx.size}")
        r.advance(dt)
        assertEquals(1, r.exchange.latest()!!.fx.size)
    }

    @Test
    fun discardPendingEventsDropsUnreadFxAndResults() {
        val r = probeRunner()
        r.exchange.latest()
        repeat(3) { r.advance(dt) }
        r.exchange.discardPendingEvents()
        r.advance(dt)
        assertEquals(listOf(3L), r.exchange.latest()!!.fx.map { it.tick })
    }

    @Test
    fun noPublishWithoutChangeEvenWhenNobodyHasEverRead() {
        val r = probeRunner()
        r.setPaused(true)
        r.advance(0f) // Pause-Wechsel veröffentlicht einmal
        val seq = r.publishedSeq
        repeat(50) { r.advance(0.016f) }
        assertEquals(seq, r.publishedSeq, "paused + nobody reading: no rebuilds")
    }

    @Test
    fun fxSurvivesFramesTheRenderThreadNeverReadExactlyOnce() {
        val r = probeRunner()
        r.exchange.latest()
        // Der Sim-Thread veröffentlicht dreimal, ohne dass der Render-Thread liest.
        repeat(3) { r.advance(dt * 2f) } // Ticks 0..5
        val delivered = ArrayList<Long>()
        var lastSeq = -1L
        fun read() {
            val snap = r.exchange.latest()!!
            if (snap.seq == lastSeq) return
            lastSeq = snap.seq
            val ticks = snap.fx.map { it.tick }
            assertEquals(ticks.sorted(), ticks, "events inside one snapshot are ordered")
            delivered.addAll(ticks)
        }
        read()
        // Weiter veröffentlichen und lesen: verspätet zugestellte Ereignisse kommen mit dem nächsten Snapshot
        repeat(3) { r.advance(dt); read() }
        assertEquals((0L until 9L).toList(), delivered.sorted(), "no event lost, none delivered twice")
    }

    @Test
    fun carriedFxStaysBoundedWhenNobodyReads() {
        val r = probeRunner(maxTicks = 5)
        r.exchange.latest()
        repeat(3000) { r.advance(0.25f) } // 15 000 Ereignisse, niemand liest
        val snap = r.exchange.latest()!!
        assertTrue(snap.fx.size <= de.bollwerk.engine.view.SnapshotBuilder.MAX_CARRIED_FX, "carried ${snap.fx.size}")
        assertTrue(snap.fx.isNotEmpty())
    }

    @Test
    fun partialFrameCarriesAlphaAndNoNewFx() {
        val r = probeRunner()
        r.exchange.latest()
        assertEquals(1, r.advance(dt * 1.5f))
        val a = r.exchange.latest()!!
        assertEquals(0.5f, a.alpha, 1e-4f)
        // Zwischenframe ohne Tick: gleicher Stand, größerer Alpha, keine neuen Fx
        assertEquals(0, r.advance(dt * 0.25f))
        val b = r.exchange.latest()!!
        assertTrue(b.seq > a.seq)
        assertEquals(a.tick, b.tick)
        assertEquals(0.75f, b.alpha, 1e-3f)
        assertTrue(b.fx.isEmpty())
    }

    @Test
    fun snapshotCarriesPrevPositionOfTheLastTickForInterpolation() {
        val r = probeRunner()
        r.advance(dt * 3.1f) // 3 Ticks in einem Frame
        val s = r.exchange.latest()!!
        assertEquals(3f, s.nodeX[0])
        assertEquals(2f, s.nodePrevX[0]) // Start des *letzten* Ticks, nicht des Frames
        assertEquals(0.1f, s.alpha, 1e-2f)
    }

    @Test
    fun pauseFreezesTheSimAndShowsInTheHud() {
        val r = probeRunner()
        r.advance(dt * 2f)
        r.setPaused(true)
        assertEquals(0, r.advance(1f))
        assertTrue(r.paused)
        assertEquals(2L, r.tick)
        val paused = r.exchange.latest()!!
        assertTrue(paused.hud.paused)
        r.setPaused(false)
        assertEquals(1, r.advance(dt))
        assertFalse(r.exchange.latest()!!.hud.paused)
        assertEquals(3L, r.tick)
    }

    @Test
    fun speedScalesTicksPerFrameWithoutChangingTheirContent() {
        val r = probeRunner()
        assertEquals(1f, r.speed)
        r.setSpeed(2f)
        assertEquals(2, r.advance(dt)) // ein Frame x2 = 2 Ticks
        assertEquals(2f, r.exchange.latest()!!.hud.speed)
        r.setSpeed(0.5f)
        assertEquals(0, r.advance(dt))
        assertEquals(1, r.advance(dt))
        r.setSpeed(100f) // geklemmt
        r.advance(dt)
        assertEquals(MatchRunner.MAX_SPEED, r.speed)
        r.setSpeed(Float.NaN)
        r.advance(dt)
        assertEquals(1f, r.speed)
    }

    @Test
    fun runTicksIsHeadlessAndStopsOnResult() {
        val r = LoopRig.runner()
        assertEquals(100L, r.runTicks(100))
        assertEquals(100L, r.tick)
        val d = r.state.players[1].reactorDeviceId
        r.state.devices.hpOf[d] = 0f
        r.state.devices.release(d)
        val ran = r.runTicks(1000, stopOnResult = true)
        assertTrue(ran < 5, "stops right after the result, ran $ran")
        assertTrue(r.finished)
    }

    @Test
    fun inputDelayStampsLocalCommandsAndRespectsAppliedTick() {
        val setup = LoopRig.setup()
        val r = LoopRig.runner(setup, inputDelayTicks = 6)
        r.runTicks(10)
        val seen = ArrayList<CommandOutcome>()
        r.addResultListener { seen.add(it) }
        r.submit(Command.PlaceBeam(0, 0, aX = 26f, aY = 34f, bX = 29f, bY = 34f, materialId = RuleTables.WOOD))
        r.runTicks(1) // Tick 10 holt das Command ab, es trägt tick 16
        assertTrue(seen.isEmpty(), "not applied before its tick")
        r.runTicks(6)
        val o = seen.single()
        assertEquals(16L, o.tick)
        assertEquals(16L, o.command.tick)
        assertTrue(o.accepted)
        // Replay trägt den Anwendungstick
        assertEquals(16L, r.recordedCommands.single().tick)
        assertEquals(6, r.loop.inputDelayTicks)
    }

    @Test
    fun lockstepStallStopsTheSimWithoutBuildingUpTime() {
        var ready = false
        val lock = object : CommandSource {
            override fun commandsFor(tick: Long, view: GameView): List<Command> = emptyList()
            override fun isReady(tick: Long): Boolean = ready
        }
        val r = probeRunner(sources = listOf(lock))
        assertEquals(0, r.advance(0.2f))
        assertTrue(r.loop.stalled)
        assertEquals(0L, r.tick)
        ready = true
        assertEquals(1, r.advance(dt)) // keine aufgestaute Zeit, kein Zeitraffer
        assertFalse(r.loop.stalled)
    }

    @Test
    fun sourcesAreAskedInFixedOrderLocalInputFirst() {
        val order = ArrayList<String>()
        val a = CommandSource { _, _ -> order.add("a"); emptyList() }
        val b = CommandSource { _, _ -> order.add("b"); emptyList() }
        val r = probeRunner(sources = listOf(a, b))
        r.advance(dt)
        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun inputCommandIsAppliedBeforeCommandsOfTheSameTickAndPlayerFromOtherSources() {
        val first = Command.PlaceBeam(0, 0, aX = 26f, aY = 34f, bX = 27.2f, bY = 34f, materialId = RuleTables.WOOD)
        val second = Command.PlaceBeam(0, 0, aX = 27.2f, aY = 34f, bX = 28.4f, bY = 34f, materialId = RuleTables.WOOD)
        val script = CommandSource { t, _ -> if (t == 0L) listOf(second) else emptyList() }
        // Die Quelle hängt schon an der Session, bevor der Runner sie übernimmt (wie MatchBootstrap.createSession(sources = …))
        val r = MatchRunner(LoopRig.session(sources = listOf(script)))
        val applied = ArrayList<Command>()
        r.addResultListener { applied.add(it.command) }
        r.submit(first) // pushed (UI), polled before the script although the script was configured at construction
        r.runTicks(1)
        assertEquals(listOf<Command>(first, second), applied.map { it.withTick(0) })
    }

    @Test
    fun commandResultsAreExposedToListenersAndSnapshot() {
        val r = LoopRig.runner(LoopRig.setup(metal = 5f))
        val heard = ArrayList<CommandOutcome>()
        r.addResultListener { heard.add(it) }
        r.exchange.latest()
        // zu teuer (4 m Holz = 16 ⚙ bei 5 ⚙), veraltete Ref (Vorprüfung), angenommen (Zurück auf leerem Journal → abgelehnt)
        r.submit(Command.PlaceBeam(0, 0, aX = 26f, aY = 34f, bX = 30f, bY = 34f, materialId = RuleTables.WOOD))
        r.submit(Command.DeleteBeam(0, 0, 9999L))
        r.submit(Command.Undo(0, 0))
        r.submit(Command.PlaceBeam(0, 0, aX = 26f, aY = 34f, bX = 27.2f, bY = 34f, materialId = RuleTables.WOOD)) // 4,8 ⚙
        r.advance(1f / 60f + 1e-4f)
        assertEquals(4, heard.size)
        // Vorprüfung (veraltete Ref) meldet vor dem Anwenden, die Regeln danach in Eingangsreihenfolge
        assertEquals(
            listOf(
                CommandResult.Rejected(RejectReason.STALE_TARGET),
                CommandResult.Rejected(RejectReason.NOT_ENOUGH_METAL),
                CommandResult.Rejected(RejectReason.NOTHING_TO_UNDO),
                CommandResult.Accepted,
            ),
            heard.map { it.result },
        )
        val snap = r.exchange.latest()!!
        assertEquals(heard, snap.commandResults.toList(), "same outcomes, same order")
        val reasons = snap.commandResults.mapNotNull { it.reason }.toSet()
        assertEquals(setOf(RejectReason.NOT_ENOUGH_METAL, RejectReason.STALE_TARGET, RejectReason.NOTHING_TO_UNDO), reasons)
        // Fx derselben Ablehnungen (roter Ghost/Toast), je Ablehnung eines
        assertEquals(3, snap.fx.count { it is FxEvent.CommandRejected })
        assertEquals(0, heard.count { it.command.playerId != 0 })
        assertTrue(snap.commandResults.any { it.accepted && it.command is Command.PlaceBeam })
    }

    @Test
    fun runTicksPublishesWithAlphaOneAndLoopAdvanceDoesNot() {
        val r = probeRunner()
        assertEquals(1f, r.exchange.latest()!!.alpha, "initial snapshot")
        r.runTicks(3)
        val s = r.exchange.latest()!!
        assertEquals(1f, s.alpha, "headless snapshots show the state after the last tick, not one tick behind")
        assertEquals(3f, s.nodeX[0])
        // danach zählt wieder die Loop-Uhr
        r.advance(dt * 1.5f)
        assertEquals(0.5f, r.exchange.latest()!!.alpha, 1e-4f)
    }

    @Test
    fun aListenerMayRemoveItselfWhileResultsAreDelivered() {
        val r = LoopRig.runner(record = false)
        val calls = ArrayList<String>()
        lateinit var once: CommandResultListener
        once = CommandResultListener { calls.add("once"); r.removeResultListener(once) }
        r.addResultListener(once)
        r.addResultListener { calls.add("second") }
        r.addResultListener { calls.add("third") }
        r.submit(Command.Undo(0, 0))
        r.submit(Command.Undo(0, 0))
        r.runTicks(1)
        // erstes Ergebnis: alle drei (Entfernen wirkt erst danach), zweites: nur noch die beiden anderen
        assertEquals(listOf("once", "second", "third", "second", "third"), calls)
    }

    @Test
    fun aListenerAddedDuringDeliveryHearsOnlyLaterResults() {
        val r = LoopRig.runner(record = false)
        val late = ArrayList<Int>()
        var added = false
        r.addResultListener {
            if (!added) { added = true; r.addResultListener { o -> late.add(o.command.playerId) } }
        }
        r.submit(Command.Undo(0, 0)); r.submit(Command.Undo(0, 0))
        r.runTicks(1)
        assertEquals(1, late.size)
    }

    @Test
    fun theTickCapScalesWithTheSpeed() {
        val r = probeRunner(maxTicks = 5)
        r.setSpeed(4f)
        // 30 fps bei Tempo 4: 8 Ticks je Frame, alle laufen (Obergrenze 20)
        var total = 0
        repeat(10) { total += r.advance(1f / 30f) }
        assertEquals(80, total)
        // Bei Tempo 1 gilt weiter die einfache Obergrenze (Spirale des Todes)
        val slow = probeRunner(maxTicks = 5)
        assertEquals(5, slow.advance(0.2f))
    }

    @Test
    fun aNaNFrameTimeDoesNotPoisonTheClock() {
        val r = probeRunner()
        assertEquals(0, r.advance(Float.NaN))
        assertEquals(1, r.advance(dt))
        assertEquals(0, r.advance(Float.NEGATIVE_INFINITY))
        assertEquals(1, r.advance(dt))
        assertTrue(r.alpha in 0f..1f)
    }

    @Test
    fun aRunnerWithoutInputIgnoresPushesAndStopsAtTheEndTick() {
        val state = TestWorld.state()
        state.nodes.alloc(0f, 0f, 0)
        val move = SimSystem { st, _ -> st.nodes.x[0] += 1f }
        val r = MatchRunner(GameSession(state, SimStepper(mapOf(SystemSlot.PHYSICS to move))), acceptInput = false, endTick = 10L)
        val heard = ArrayList<CommandOutcome>()
        r.addResultListener { heard.add(it) }
        r.submit(Command.Undo(0, 0))
        assertEquals(10L, r.runTicks(100))
        assertTrue(r.reachedEnd)
        assertEquals(10L, r.tick)
        assertTrue(heard.isEmpty(), "pushed input never reaches the match")
        assertEquals(0, r.advance(1f))
        assertEquals(10L, r.tick)
        assertEquals(1f, r.exchange.latest()!!.alpha, "no next tick to interpolate towards")
    }

    @Test
    fun hudModelIsReusedWithinATickAndRebuiltWhenAnythingItShowsChanges() {
        val r = LoopRig.runner(record = false)
        r.advance(1f / 60f + 1e-4f)
        val a = r.exchange.latest()!!.hud
        r.advance(0.004f) // kein Tick, nur Alpha
        val b = r.exchange.latest()!!.hud
        assertTrue(a === b, "same instance while tick, pause and speed are unchanged")
        r.setPaused(true)
        r.advance(0f)
        assertTrue(r.exchange.latest()!!.hud.paused)
        assertTrue(r.exchange.latest()!!.hud !== b)
        r.setPaused(false); r.setSpeed(2f)
        r.advance(0f)
        assertEquals(2f, r.exchange.latest()!!.hud.speed)
        val c = r.exchange.latest()!!.hud
        r.advance(1f / 30f)
        assertTrue(r.exchange.latest()!!.hud !== c)
        assertEquals(r.tick * r.state.config.dt, r.exchange.latest()!!.hud.timeSeconds, 1e-4f)
    }
}
