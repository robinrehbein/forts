package de.bollwerk.engine

import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.FoundationSpec
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.MatchFactory
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.StartFortSpec
import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.ZoneSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MatchFactoryTest {
    private val map = MapSpec(
        id = "test", width = 120f, height = 64f,
        terrain = Terrain.flat(-40f, 160f, 34f),
        buildZones = listOf(ZoneSpec(0, 0f, 39f), ZoneSpec(1, 81f, 120f)),
        ores = emptyList(),
        foundations = listOf(FoundationSpec(0, 20f, 34f), FoundationSpec(1, 100f, 34f)),
        startForts = listOf(StartFortSpec(0, "mini", 20f, false), StartFortSpec(1, "mini", 100f, true)),
        baseY = floatArrayOf(34f, 34f),
        windMin = -6f, windMax = 6f,
        killMinX = -40f, killMaxX = 160f, killMinY = -120f, killMaxY = 84f,
    )

    private fun setup(seed: Long = 5L, mode: TurnMode = TurnMode.REALTIME) =
        MatchSetup(seed, "test", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.AI, "NORMAL")), mode, turnTicks = 1800, startMetal = 450f, startEnergy = 999f)

    @Test
    fun placesFoundationsStartFortsAndReactors() {
        val s = MatchFactory.create(setup(), TestWorld.tables, map)
        // 2 Fundamente + je Spieler 2 neue Knoten (ein Vorlagenknoten verschmilzt mit dem Fundament)
        assertEquals(6, s.nodes.aliveCount)
        assertEquals(6, s.beams.aliveCount)
        assertEquals(4, s.devices.aliveCount)
        for (p in s.players) {
            val r = p.reactorDeviceId
            assertTrue(s.devices.isAlive(r))
            assertEquals(p.id, s.devices.owner(r))
            assertEquals(450f, p.metal)
            assertEquals(400f, p.energy) // auf Lager gekappt
        }
        assertEquals(1, s.players[0].facing)
        assertEquals(-1, s.players[1].facing)
        // Spieler 1 gespiegelt: Knoten links vom Ursprung, Zielwinkel gespiegelt, Seite getauscht
        val n1 = (0 until s.nodes.size).filter { s.nodes.owner(it) == 1 }
        assertTrue(n1.all { s.nodes.x(it) <= 100f })
        val mortar1 = (0 until s.devices.size).first { s.devices.owner(it) == 1 && s.devices.type(it) == TestWorld.MORTAR }
        assertEquals(FloatMath.PI - 52f * FloatMath.DEG_TO_RAD, s.devices.aimAngle(mortar1), 1e-6f)
        assertTrue((s.devices.flags(mortar1) and DeviceFlags.SIDE_NEG) != 0)
        assertTrue(s.wind >= -6f && s.wind < 6f)
        assertEquals(2, s.nodes.beamCount(0)) // Fundament-Knoten trägt 2 Balken (Adjazenz gebaut)
    }

    @Test
    fun isDeterministicAndSeedSensitive() {
        val h1 = StateHash.of(MatchFactory.create(setup(5L), TestWorld.tables, map))
        assertEquals(h1, StateHash.of(MatchFactory.create(setup(5L), TestWorld.tables, map)))
        assertTrue(h1 != StateHash.of(MatchFactory.create(setup(6L), TestWorld.tables, map)))
    }

    @Test
    fun turnModeSetup() {
        val s = MatchFactory.create(setup(mode = TurnMode.TURNS), TestWorld.tables, map)
        assertEquals(0, s.turn.activePlayer)
        assertEquals(1, s.turn.turnNumber)
        assertEquals(1800, s.turn.ticksLeft)
        assertEquals(TurnPhase.PLAY, s.turn.phase)
    }

    @Test
    fun rejectsWrongPlayerCountAndUnknownBlueprint() {
        assertFailsWith<IllegalArgumentException> {
            MatchFactory.create(setup().copy(players = listOf(PlayerSetup(Controller.HUMAN))), TestWorld.tables, map)
        }
        assertFailsWith<IllegalArgumentException> {
            MatchFactory.create(setup().copy(startFortBlueprint = "nope"), TestWorld.tables, map)
        }
    }

    @Test
    fun mapHelpers() {
        assertTrue(map.inBuildZone(0, 10f))
        assertTrue(!map.inBuildZone(0, 60f))
        assertTrue(map.isOutOfBounds(-50f, 0f))
        assertTrue(!map.isOutOfBounds(60f, 40f))
    }
}
