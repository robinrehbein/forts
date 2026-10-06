package de.bollwerk.engine

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.loop.GameLoop
import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.loop.LocalInputSource
import de.bollwerk.engine.loop.ReplayRecorder
import de.bollwerk.engine.loop.ScriptedCommandSource
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.SystemSlot
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LoopTest {
    @Test
    fun accumulatorRunsWholeTicksAndReportsAlpha() {
        var ticks = 0
        val loop = GameLoop(dt = 1f / 60f) { ticks++ }
        assertEquals(0, loop.advance(0.010f))
        assertTrue(loop.alpha > 0.5f && loop.alpha < 0.7f)
        assertEquals(1, loop.advance(0.010f))
        assertEquals(1, ticks)
        repeat(60) { loop.advance(1f / 60f) }
        assertTrue(ticks in 60..62, "ticks=$ticks")
    }

    @Test
    fun accumulatorCapsSpiralOfDeath() {
        var ticks = 0
        val loop = GameLoop(maxTicksPerAdvance = 5) { ticks++ }
        assertEquals(5, loop.advance(10f))
        assertTrue(loop.alpha < 1f)
        loop.paused = true
        assertEquals(0, loop.advance(1f))
    }

    @Test
    fun inputDelay() {
        val loop = GameLoop(inputDelayTicks = 6) {}
        assertEquals(106L, loop.commandTickFor(100L))
    }

    private fun build(): GameState {
        val s = TestWorld.state(seed = 7L)
        val a = s.nodes.alloc(10f, 30f, 0, anchored = true)
        val b = s.nodes.alloc(14f, 30f, 0)
        s.beams.alloc(a, b, 0, 4f, 100f, 0)
        s.devices.alloc(0, 0, 0.5f, 300f, 0, buildTicks = 0, sideNegative = false, aimAngle = 0.9f, power = 0.75f)
        s.projectiles.alloc(1f, 2f, 3f, 4f, 0, 1, 100, sourceDevice = 0)
        return s
    }

    @Test
    fun hashIsStableAndSensitive() {
        val h1 = StateHash.of(build())
        assertEquals(h1, StateHash.of(build()))
        assertNotEquals(h1, StateHash.of(build().also { it.nodes.x[1] = 14.000001f }))
        assertNotEquals(h1, StateHash.of(build().also { it.beams.hpOf[0] = 99f }))
        assertNotEquals(h1, StateHash.of(build().also { it.players[1].metal += 1f }))
        assertNotEquals(h1, StateHash.of(build().also { it.rng.nextLong() }))
        assertNotEquals(h1, StateHash.of(build().also { it.rngFire.nextLong() }))
        assertNotEquals(h1, StateHash.of(build().also { it.projectiles.sourceDevice[0] = -1 }))
        assertNotEquals(h1, StateHash.of(build().also { it.beams.doorTimerTicks[0] = 3 }))
        assertNotEquals(h1, StateHash.of(build().also { it.nodes.debrisTicks[0] = 1 }))
        assertNotEquals(h1, StateHash.of(build().also { it.players[0].undoJournal.push(de.bollwerk.engine.sim.UndoEntry(de.bollwerk.engine.sim.UndoKind.BEAM, 0, 0, 1f, 0f)) }))
        // Generation: freigeben + neu belegen mit identischen Daten ergibt anderen Hash
        val regen = build().also {
            it.beams.release(0); it.beams.endTick(); it.beams.alloc(0, 1, 0, 4f, 100f, 0)
        }
        assertNotEquals(h1, StateHash.of(regen))
        // RENDER/DERIVED-Felder gehen nicht ein
        assertEquals(h1, StateHash.of(build().also { it.nodes.tickX[0] = 99f; it.beams.texOffset[0] = 3f; it.beams.strainOf[0] = 0.5f }))
        assertEquals(16, StateHash.hex(h1).length)
    }

    @Test
    fun fnvMatchesReferenceForKnownBytes() {
        val h = StateHash.Hasher()
        h.byte('a'.code)
        assertEquals(0xaf63dc4c8601ec8cuL.toLong(), h.value)
    }

    @Test
    fun stepperRunsSystemsInSlotOrderAndDefersFrees() {
        val order = ArrayList<SystemSlot>()
        val s = TestWorld.state()
        val n0 = s.nodes.alloc(0f, 0f, 0)
        val systems = mapOf(
            SystemSlot.WIND to SimSystem { _, _ -> order.add(SystemSlot.WIND) },
            SystemSlot.COMMANDS to SimSystem { st, _ ->
                order.add(SystemSlot.COMMANDS)
                st.nodes.release(n0)
                val n = st.nodes.alloc(1f, 1f, 0)
                assertTrue(st.nodes.isNew(n))
                assertNotEquals(n0, n, "freigegebener Slot darf im selben Tick nicht neu belegt werden")
            },
            SystemSlot.PHYSICS to SimSystem { _, _ -> order.add(SystemSlot.PHYSICS) },
        )
        val stepper = SimStepper(systems)
        stepper.step(s, de.bollwerk.engine.sim.StepContext())
        assertEquals(listOf(SystemSlot.COMMANDS, SystemSlot.PHYSICS, SystemSlot.WIND), order)
        assertEquals(1L, s.tick)
        assertEquals(n0, s.nodes.freeListSnapshot().single())
    }

    @Test
    fun beginTickRecordsInterpolationStart() {
        val s = TestWorld.state()
        val n = s.nodes.alloc(1f, 2f, 0)
        val move = SimSystem { st, _ -> st.nodes.x[n] += 0.5f }
        SimStepper(mapOf(SystemSlot.PHYSICS to move)).step(s, de.bollwerk.engine.sim.StepContext())
        assertEquals(1f, s.nodes.tickX[n])
        assertEquals(1.5f, s.nodes.x[n])
    }

    @Test
    fun sessionDeliversCommandsInDeterministicOrderAndRecordsReplay() {
        val seen = ArrayList<Command>()
        val sys = SimSystem { state, ctx ->
            seen.addAll(ctx.commands)
            state.wind = state.rngWind.nextFloat() * 4f - 2f
        }
        fun run(): Pair<Long, List<Command>> {
            seen.clear()
            val state = TestWorld.state(seed = 1L)
            val recorder = ReplayRecorder()
            val p1 = ScriptedCommandSource(listOf(Command.Undo(2, 1), Command.EndTurn(4, 1)))
            val p0 = CommandSource { tick, _ -> if (tick == 2L) listOf(Command.Undo(2, 0)) else emptyList() }
            val s = GameSession(state, SimStepper(mapOf(SystemSlot.COMMANDS to sys)), sources = listOf(p1, p0), recorder = recorder)
            assertEquals(10L, s.run(10))
            assertEquals(10L, state.tick)
            assertEquals(3, recorder.commands.size)
            return s.hash() to seen.toList()
        }
        val (h1, cmds) = run()
        val (h2, _) = run()
        assertEquals(h1, h2)
        assertEquals(listOf(0, 1), cmds.filter { it.tick == 2L }.map { it.playerId })
    }

    @Test
    fun applyTimeRejectionsComeFromCtxResultsAndBecomeFx() {
        val state = TestWorld.state()
        val results = ArrayList<CommandResult>()
        // Command-System lehnt das zweite Undo ab (z. B. Budget nach dem ersten erschöpft)
        val commands = SimSystem { _, ctx ->
            for (i in ctx.commands.indices) {
                ctx.results.add(if (i == 0) CommandResult.Accepted else CommandResult.Rejected(RejectReason.NOT_ENOUGH_METAL))
            }
            ctx.fx.add(FxEvent.Hit(ctx.tick, 1f, 2f, HitTarget.TERRAIN, -1, 5f, 0))
        }
        val s = GameSession(
            state, SimStepper(mapOf(SystemSlot.COMMANDS to commands)),
            sources = listOf(ScriptedCommandSource(listOf(Command.Undo(0, 0), Command.Undo(0, 0), Command.Fire(0, 0, 77)))),
            recorder = { _, _, r -> results.add(r) },
        )
        assertTrue(s.tick())
        // Fire mit veralteter Ref scheitert schon in der Vorprüfung
        assertEquals(
            listOf(
                CommandResult.Rejected(RejectReason.STALE_TARGET),
                CommandResult.Accepted,
                CommandResult.Rejected(RejectReason.NOT_ENOUGH_METAL),
            ),
            results,
        )
        val fx = ArrayList<FxEvent>()
        s.fxBuffer.drainTo(fx)
        assertEquals(1, fx.count { it is FxEvent.Hit })
        assertEquals(
            setOf(RejectReason.STALE_TARGET, RejectReason.NOT_ENOUGH_METAL),
            fx.filterIsInstance<FxEvent.CommandRejected>().map { it.reason }.toSet(),
        )
    }

    @Test
    fun fxFromSeveralTicksAccumulateInBuffer() {
        val state = TestWorld.state()
        val sys = SimSystem { _, ctx -> ctx.fx.add(FxEvent.Hit(ctx.tick, 0f, 0f, HitTarget.TERRAIN, -1, 1f, 0)) }
        val s = GameSession(state, SimStepper(mapOf(SystemSlot.PROJECTILES to sys)))
        s.run(3)
        val out = ArrayList<FxEvent>()
        s.fxBuffer.drainTo(out)
        assertEquals(listOf(0L, 1L, 2L), out.map { it.tick })
        assertEquals(0, s.fxBuffer.size)
    }

    @Test
    fun sessionStallsWhileSourceIsNotReady() {
        var ready = false
        val lockstep = object : CommandSource {
            override fun commandsFor(tick: Long, view: de.bollwerk.engine.view.GameView): List<Command> = emptyList()
            override fun isReady(tick: Long): Boolean = ready
        }
        val state = TestWorld.state()
        val s = GameSession(state, sources = listOf(lockstep))
        assertFalse(s.tick())
        assertEquals(0L, s.run(5))
        assertEquals(0L, state.tick)
        ready = true
        assertEquals(5L, s.run(5))
    }

    @Test
    fun lateCommandsAreRestampedToAppliedTick() {
        val state = TestWorld.state()
        val rec = ReplayRecorder()
        val s = GameSession(state, recorder = rec)
        s.run(3)
        s.queue.add(Command.Undo(1, 0)) // verspätet
        s.tick()
        assertEquals(3L, rec.commands.single().tick)
    }

    @Test
    fun localInputSourceStampsWithDelay() {
        val src = LocalInputSource(inputDelayTicks = 6)
        src.push(Command.Undo(0, 0))
        src.push(Command.EndTurn(0, 0))
        val state = TestWorld.state()
        val out = src.commandsFor(10, state)
        assertEquals(listOf(16L, 16L), out.map { it.tick })
        assertTrue(src.commandsFor(11, state).isEmpty())
    }
}
