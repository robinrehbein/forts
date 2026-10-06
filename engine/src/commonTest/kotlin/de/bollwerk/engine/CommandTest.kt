package de.bollwerk.engine

import de.bollwerk.engine.command.BasicValidator
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandChecks
import de.bollwerk.engine.command.CommandJson
import de.bollwerk.engine.command.CommandQueue
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.WinReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandTest {
    private val all: List<Command> = listOf(
        Command.PlaceBeam(1, 0, aNodeRef = 3, bX = 2.5f, bY = 30f, materialId = 0),
        Command.PlaceBeam(1, 0, aBeamRef = (7L shl 32) or 2L, aBeamT = 0.25f, bNodeRef = 5, materialId = 0),
        Command.DeleteBeam(2, 1, beamRef = 4),
        Command.DeleteDevice(2, 1, deviceRef = 6),
        Command.RepairBeam(3, 0, beamRef = 5),
        Command.PlaceDevice(4, 0, deviceTypeId = 2, beamRef = 1, t = 0.5f, sideNegative = true),
        Command.ToggleDoor(5, 1, beamRef = 9),
        Command.SetAim(6, 0, deviceRef = 1, angle = 0.9f, power = 0.78f),
        Command.Fire(7, 0, deviceRef = 1),
        Command.Undo(8, 1),
        Command.EndTurn(9, 0),
        Command.Surrender(10, 1),
    )

    private val setup = MatchSetup(
        seed = 123L, mapId = "schlucht",
        players = listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.AI, "HARD")),
        turnMode = TurnMode.TURNS, startMetal = 500f,
    )

    @Test
    fun allCommandsRoundTripThroughJson() {
        for (c in all) {
            val text = CommandJson.encodeToString(Command.serializer(), c)
            assertTrue(text.contains("\"type\""), text)
            assertEquals(c, CommandJson.decodeFromString(Command.serializer(), text))
        }
    }

    @Test
    fun replayRoundTripCarriesSetupConfigAndContentHash() {
        val cfg = SimConfig(substeps = 5)
        val r = Replay(contentVersion = "0.1.0", contentHash = 0x1234L, setup = setup, config = cfg, commands = all, ticks = 600)
        val back = Replay.fromJson(r.toJson())
        assertEquals(r, back)
        assertEquals(5, back.config.substeps)
        assertEquals(0.55f, back.config.fire.growthPerSec)
        assertNull(back.incompatibility(0x1234L))
        assertTrue(back.incompatibility(0x9999L)!!.contains("content hash"))
        assertTrue(back.incompatibility(0x1234L, engineVersion = 99)!!.contains("engine"))
    }

    @Test
    fun nonFiniteFloatsCannotBeSerialized() {
        assertFailsWith<IllegalArgumentException> {
            CommandJson.encodeToString(Command.serializer(), Command.SetAim(1, 0, 1, Float.NaN, 1f))
        }
    }

    @Test
    fun withTickKeepsPayload() {
        for (c in all) {
            val moved = c.withTick(99)
            assertEquals(99, moved.tick)
            assertEquals(c, moved.withTick(c.tick))
        }
    }

    @Test
    fun queueOrdersByTickThenPlayerThenInsertion() {
        val q = CommandQueue()
        q.add(Command.Fire(5, 1, 10))
        q.add(Command.Fire(3, 1, 11))
        q.add(Command.Fire(5, 0, 12))
        q.add(Command.Fire(5, 0, 13))
        q.add(Command.Fire(3, 0, 14))
        q.add(Command.Fire(8, 0, 15))
        assertEquals(emptyList(), q.drain(2).map { (it as Command.Fire).deviceRef })
        assertEquals(listOf(14L, 11L), q.drain(3).map { (it as Command.Fire).deviceRef })
        assertEquals(listOf(12L, 13L, 10L), q.drain(5).map { (it as Command.Fire).deviceRef })
        assertEquals(1, q.size)
        assertEquals(listOf(15L), q.drain(100).map { (it as Command.Fire).deviceRef })
        assertTrue(q.isEmpty())
    }

    @Test
    fun rejectReasonsHaveUniqueDisplayKeys() {
        val keys = RejectReason.entries.map { it.displayKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun precheckRejectsNonFiniteStaleAndUnknown() {
        val s = TestWorld.state()
        val a = s.nodes.alloc(1f, 30f, 0)
        val b = s.nodes.alloc(4f, 30f, 0)
        val beam = s.beams.alloc(a, b, TestWorld.WOOD, 3f, 100f, 0)
        val ref = s.beams.ref(beam)
        assertNull(BasicValidator.validate(s, Command.RepairBeam(0, 0, ref)))
        assertEquals(RejectReason.INVALID_TARGET, BasicValidator.validate(s, Command.SetAim(0, 0, 0, Float.NaN, 1f)))
        assertEquals(RejectReason.INVALID_TARGET, BasicValidator.validate(s, Command.PlaceBeam(0, 0, bX = Float.POSITIVE_INFINITY, materialId = 0)))
        assertEquals(RejectReason.INVALID_TARGET, BasicValidator.validate(s, Command.EndTurn(0, 7)))
        assertEquals(RejectReason.UNKNOWN_CONTENT, BasicValidator.validate(s, Command.PlaceBeam(0, 0, materialId = 5)))
        assertEquals(RejectReason.UNKNOWN_CONTENT, BasicValidator.validate(s, Command.PlaceDevice(0, 0, 9, ref, 0.5f)))
        // Balken bricht, Slot wird im nächsten Tick neu belegt: alte Ref ist veraltet
        s.beams.release(beam)
        s.beams.endTick()
        val reused = s.beams.alloc(a, b, TestWorld.WOOD, 3f, 100f, 0)
        assertEquals(beam, reused)
        assertEquals(RejectReason.STALE_TARGET, BasicValidator.validate(s, Command.RepairBeam(0, 0, ref)))
        assertEquals(RejectReason.STALE_TARGET, BasicValidator.validate(s, Command.PlaceBeam(0, 0, aBeamRef = ref, materialId = 0)))
        assertNull(BasicValidator.validate(s, Command.RepairBeam(0, 0, s.beams.ref(reused))))
        assertEquals(RejectReason.INVALID_TARGET, BasicValidator.validate(s, Command.Fire(0, 0, -1)))
        assertEquals(RejectReason.STALE_TARGET, BasicValidator.validate(s, Command.Fire(0, 0, 0)))
    }

    @Test
    fun precheckTurnAndGameOver() {
        val s = TestWorld.state()
        s.turnState.mode = TurnMode.TURNS
        s.turnState.activePlayer = 1
        assertEquals(RejectReason.NOT_YOUR_TURN, BasicValidator.validate(s, Command.EndTurn(0, 0)))
        assertNull(BasicValidator.validate(s, Command.EndTurn(0, 1)))
        assertNull(BasicValidator.validate(s, Command.Surrender(0, 0)))
        s.turnState.phase = TurnPhase.HANDOVER
        assertEquals(RejectReason.NOT_YOUR_TURN, BasicValidator.validate(s, Command.EndTurn(0, 1)))
        s.result = GameResult.Winner(0, WinReason.SURRENDER)
        assertEquals(RejectReason.GAME_OVER, BasicValidator.validate(s, Command.Surrender(0, 0)))
    }

    @Test
    fun sanitizeClampsPowerAndParameters() {
        val cfg = SimConfig.DEFAULT
        assertEquals(0.3f, (CommandChecks.sanitize(Command.SetAim(0, 0, 1, 1f, 0.01f), cfg) as Command.SetAim).power)
        assertEquals(1f, (CommandChecks.sanitize(Command.SetAim(0, 0, 1, 1f, 7f), cfg) as Command.SetAim).power)
        assertEquals(1f, (CommandChecks.sanitize(Command.PlaceDevice(0, 0, 0, 1, 1.5f), cfg) as Command.PlaceDevice).t)
        val pb = CommandChecks.sanitize(Command.PlaceBeam(0, 0, aBeamRef = 1, aBeamT = -2f, bBeamRef = 2, bBeamT = 3f, materialId = 0), cfg) as Command.PlaceBeam
        assertEquals(0f, pb.aBeamT); assertEquals(1f, pb.bBeamT)
        val ok = Command.SetAim(0, 0, 1, 1f, 0.5f)
        assertTrue(CommandChecks.sanitize(ok, cfg) === ok)
    }
}
