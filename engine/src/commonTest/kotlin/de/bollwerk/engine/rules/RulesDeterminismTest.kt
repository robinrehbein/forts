package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.loop.ReplayRecorder
import de.bollwerk.engine.loop.ScriptedCommandSource
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.physics.PhysicsSystems
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MatchFactory
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.systems.StandardSystems
import de.bollwerk.engine.view.GameView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Determinismus der Regeln: ein skriptierter Bau (Balken, Teilen, Gerät, Zurück, Abriss, Tech, Waffe) über die echte
 * Session mit Physik muss für gleichen Seed bitgleiche Hashes liefern, und ein Replay der angenommenen Commands muss
 * zum selben Endzustand führen.
 */
class RulesDeterminismTest {
    /** Baut deterministisch aus dem Zustand heraus (Refs werden aus der View gelesen, nicht festverdrahtet). */
    private class Builder(private val skipUndo: Boolean = false) : CommandSource {
        private fun firstDevice(v: GameView, owner: Int, type: Int): Int {
            for (i in 0 until v.deviceView.size) {
                if (v.deviceView.isAlive(i) && v.deviceView.owner(i) == owner && v.deviceView.type(i) == type) return i
            }
            return -1
        }

        // Slots der ersten neuen Balken: 5 Fort-Balken je Spieler → 10, 11, 12
        override fun commandsFor(tick: Long, view: GameView): List<Command> = when (tick) {
            0L -> listOf(
                Command.PlaceBeam(0, 0, aX = 26f, aY = 34f, bX = 30f, bY = 34f, materialId = RuleTables.WOOD),
                Command.PlaceBeam(0, 0, aX = 30f, aY = 34f, bX = 30f, bY = 30f, materialId = RuleTables.METAL),
                Command.PlaceBeam(0, 0, aX = 26f, aY = 34f, bX = 28f, bY = 30f, materialId = RuleTables.WOOD),
            )
            3L -> listOf(Command.PlaceDevice(3, 0, RuleTables.TURBINE, view.beamView.ref(10), 0.15f, true))
            5L -> {
                // Teilen: Reaktorbalken des Forts (Slot 0) in der Mitte anbauen
                listOf(Command.PlaceBeam(5, 0, aBeamRef = view.beamView.ref(0), aBeamT = 0.5f, bX = 18f, bY = 30f, materialId = RuleTables.WOOD))
            }
            8L -> if (skipUndo) emptyList() else listOf(Command.Undo(8, 0))
            10L -> listOf(Command.DeleteBeam(10, 0, view.beamView.ref(12)))
            12L -> listOf(Command.RepairBeam(12, 0, view.beamView.ref(10)))
            20L -> listOf(Command.PlaceDevice(20, 0, RuleTables.WORKSHOP, view.beamView.ref(10), 0.85f, true))
            2000L -> listOf(Command.PlaceDevice(2000, 0, RuleTables.MORTAR, view.beamView.ref(11), 0.5f, true))
            2300L -> {
                val m = firstDevice(view, 0, RuleTables.MORTAR)
                if (m < 0) emptyList() else listOf(
                    Command.SetAim(2300, 0, view.deviceView.ref(m), 0.9f, 0.8f),
                    Command.Fire(2300, 0, view.deviceView.ref(m)),
                )
            }
            else -> emptyList()
        }
    }

    private class Run(val hashes: List<Long>, val finalHash: Long, val accepted: List<Command>, val state: GameState)

    private fun run(source: CommandSource, seed: Long = 11L, ticks: Int = 2500): Run {
        val setup = MatchSetup(seed, "rules", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN)), startMetal = 600f, startEnergy = 400f)
        val state = MatchFactory.create(setup, RuleTables.tables, RuleTables.map())
        PhysicsSystems.settle(state)
        val rec = ReplayRecorder()
        val session = GameSession(state, StandardSystems.stepper(), sources = listOf(source), recorder = rec)
        val hashes = ArrayList<Long>()
        for (i in 0 until ticks) {
            session.tick()
            if (i % 250 == 0) hashes.add(session.hash())
        }
        return Run(hashes, session.hash(), rec.commands.toList(), state)
    }

    @Test
    fun scriptedBuildIsBitIdenticalAcrossRuns() {
        val a = run(Builder())
        val b = run(Builder())
        assertEquals(a.hashes, b.hashes)
        assertEquals(StateHash.hex(a.finalHash), StateHash.hex(b.finalHash))
        assertTrue(a.accepted.size >= 8, "das Skript soll wirklich bauen: ${a.accepted.size} Commands angenommen")
        assertTrue(a.accepted.any { it is Command.Undo })
        assertTrue(a.accepted.any { it is Command.PlaceDevice })
        assertTrue(a.accepted.any { it is Command.Fire }, "Schuss angefordert")
    }

    @Test
    fun replayOfTheAcceptedCommandsReachesTheSameState() {
        val a = run(Builder())
        val replay = run(ScriptedCommandSource(a.accepted))
        assertEquals(a.hashes, replay.hashes)
        assertEquals(a.finalHash, replay.finalHash)
        assertEquals(a.accepted, replay.accepted)
    }

    @Test
    fun aDifferentCommandChangesTheHash() {
        val a = run(Builder())
        val b = run(Builder(skipUndo = true))
        assertNotEquals(a.finalHash, b.finalHash)
        assertEquals(a.hashes.first(), b.hashes.first(), "bis zum ersten Messpunkt (Tick 0) gleich")
    }

    @Test
    fun aDifferentSeedChangesTheHash() {
        assertNotEquals(run(Builder(), seed = 11L).finalHash, run(Builder(), seed = 12L).finalHash)
    }

    @Test
    fun sequentialApplicationIsOrderSensitiveButDeterministic() {
        // zwei Commands im selben Tick: Reihenfolge Tick → Spieler → Eingang; Wiederholung ergibt denselben Hash
        fun go(): Long {
            val rig = RulesRig(metal = 30f, physics = true)
            val c1 = Command.PlaceBeam(0, 0, aNodeRef = rig.nref(rig.apex), bX = 23f, bY = 27f, materialId = RuleTables.WOOD) // 16 ⚙
            val c2 = Command.PlaceBeam(0, 0, aNodeRef = rig.nref(rig.apex), bX = 26f, bY = 27f, materialId = RuleTables.WOOD) // 5 m = 20 ⚙
            val r = rig.send(c1, c2)
            assertEquals(listOf(true, false), r.map { it == de.bollwerk.engine.command.CommandResult.Accepted })
            rig.run(100)
            return StateHash.of(rig.state)
        }
        assertEquals(go(), go())
    }
}
