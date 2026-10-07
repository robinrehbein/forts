package de.bollwerk.setup

import de.bollwerk.content.ClasspathContent
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.loop.CommandRecorder
import de.bollwerk.engine.loop.ScriptedCommandSource
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.rules.Economy
import de.bollwerk.engine.rules.RulesValidator
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.TurnConfig
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.SystemSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `MatchBootstrap` mit der vollständigen Systemliste und dem echten Content (Stil-Bibel-Zahlen). */
class StandardSystemsTest {
    private val db = ClasspathContent.load()

    private fun setup(map: String = "schlucht", seed: Long = 5L) =
        MatchSetup(seed, map, listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.AI, "NORMAL")))

    @Test
    fun sessionRunsEverySystemSlot() {
        val s = MatchBootstrap.createSession(db, setup())
        for (slot in SystemSlot.entries) assertNotNull(s.stepper[slot], "kein System für $slot")
    }

    @Test
    fun startFortEconomyMatchesTheStyleGuide() {
        val s = MatchBootstrap.createSession(db, setup())
        s.run(1)
        val p = s.state.players[0]
        assertEquals(12f, p.metalRate, 1e-4f, "2 Minen à 6 ⚙/s")
        // Reaktor 2 ⚡/s + Windturbine 6 ⚡/s × Höhenfaktor aus der echten Montagehöhe der Turbine
        val state = s.state
        val geo = FloatArray(DeviceGeometry.SIZE)
        var expected = 0f
        var turbines = 0
        for (i in 0 until state.devices.size) {
            if (!state.devices.isAlive(i) || state.devices.owner(i) != 0) continue
            val props = state.tables.devices[state.devices.type(i)]
            when (props.role) {
                DeviceRole.REACTOR -> expected += props.energyPerSecond
                DeviceRole.TURBINE -> {
                    DeviceGeometry.mount(state, i, geo)
                    expected += props.energyPerSecond * Economy.turbineFactor(state.map.baseY[0], geo[DeviceGeometry.Y])
                    turbines++
                }
                else -> {}
            }
        }
        assertEquals(1, turbines, "die Startfestung hat genau eine Turbine")
        assertEquals(expected, p.energyRate, 1e-3f, "Energie-Rate = 2 + 6 × Höhenfaktor")
        // Die echte Startfestung steht hoch (Turbine ~9 m über dem Grund, Faktor ~1,38): ~10,3 ⚡/s, nicht das "+8" des HUD-Beispiels
        assertEquals(10.28f, p.energyRate, 0.05f, "Energie-Rate ${p.energyRate}")
        val m0 = p.metal
        val e0 = p.energy
        s.run(600)
        assertEquals(m0 + 12f * 10f, p.metal, 0.1f)
        assertEquals(e0 + p.energyRate * 10f, p.energy, 0.1f)
        assertEquals(1000f, p.metalCap); assertEquals(400f, p.energyCap)
        assertEquals(GameResult.Ongoing, s.state.result)
    }

    @Test
    fun commandsRunThroughTheRulesAndAreDeterministic() {
        val rec = ArrayList<Pair<Command, CommandResult>>()
        fun run(): Long {
            val bp = db.map("schlucht")
            // Fundament des Forts (24, 34) → freier Punkt nach oben
            val cmds = listOf(
                Command.PlaceBeam(5, 0, aX = 24f, aY = 34f, bX = 24f, bY = 28.5f, materialId = db.materialIndex("wood")),
                Command.PlaceBeam(5, 0, aX = 24f, aY = 28.5f, bX = 28f, bY = 28.5f, materialId = db.materialIndex("wood")),
                Command.Undo(40, 0),
                Command.EndTurn(41, 0),
            )
            assertTrue(bp.foundations.any { it.x == 24f })
            val s = MatchBootstrap.createSession(db, setup(), sources = listOf(ScriptedCommandSource(cmds)))
            s.recorder = CommandRecorder { _, c, r -> rec.add(c to r) }
            s.run(120)
            return StateHash.of(s.state)
        }
        val h1 = run()
        val first = rec.toList()
        rec.clear()
        val h2 = run()
        assertEquals(h1, h2)
        assertEquals(first, rec.toList())
        assertTrue(first.any { it.first is Command.PlaceBeam && it.second == CommandResult.Accepted })
        assertTrue(first.any { it.first is Command.Undo && it.second == CommandResult.Accepted })
        // EndTurn im Echtzeitmodus ist sinnlos
        assertTrue(first.any { it.first is Command.EndTurn && it.second is CommandResult.Rejected })
    }

    @Test
    fun rulesValidatorAgreesWithTheCommandSystemOnTheRealFort() {
        val s = MatchBootstrap.create(db, setup())
        val cmd = Command.PlaceBeam(0, 0, aX = 24f, aY = 34f, bX = 24f, bY = 28.5f, materialId = db.materialIndex("wood"))
        assertEquals(null, RulesValidator.validate(s, cmd))
        val tooLong = Command.PlaceBeam(0, 0, aX = 24f, aY = 34f, bX = 24f, bY = 27f, materialId = db.materialIndex("wood"))
        assertEquals(de.bollwerk.engine.command.RejectReason.TOO_LONG, RulesValidator.validate(s, tooLong))
    }

    @Test
    fun hotseatSessionPlaysTheConfiguredTurnLength() {
        val setup = MatchSetup(
            5L, "schlucht", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN)),
            TurnMode.TURNS, TurnConfig.DEFAULT_PLAY_TICKS,
        )
        val s = MatchBootstrap.createSession(db, setup)
        assertEquals(2700, s.state.turnState.lengthTicks)
        s.run(2699)
        assertEquals(TurnPhase.PLAY, s.state.turnState.phase)
        assertEquals(0, s.state.turnState.activePlayer)
        s.run(2)
        assertEquals(TurnPhase.RESOLVE, s.state.turnState.phase)
    }

    @Test
    fun turnTicksZeroMeansUnlimitedTurns() {
        val setup = MatchSetup(5L, "schlucht", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN)), TurnMode.TURNS, 0)
        val s = MatchBootstrap.createSession(db, setup)
        s.run(3000)
        assertEquals(TurnPhase.PLAY, s.state.turnState.phase)
        assertEquals(0, s.state.turnState.activePlayer)
    }

    @Test
    fun replaySessionSettlesLikeTheOriginal() {
        val cmds = listOf(
            Command.PlaceBeam(5, 0, aX = 24f, aY = 34f, bX = 24f, bY = 28.5f, materialId = db.materialIndex("wood")),
        )
        val live = MatchBootstrap.createSession(db, setup(), sources = listOf(ScriptedCommandSource(cmds)))
        live.run(120)
        val replay = Replay(contentVersion = db.version, contentHash = db.fingerprint, setup = setup(), commands = cmds, ticks = 120)
        val again = MatchBootstrap.sessionForReplay(db, replay, sources = listOf(ScriptedCommandSource(replay.commands)))
        again.run(120)
        assertEquals(StateHash.of(live.state), StateHash.of(again.state))
    }
}
