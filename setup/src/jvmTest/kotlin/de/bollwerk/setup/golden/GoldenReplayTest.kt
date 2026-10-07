package de.bollwerk.setup.golden

import de.bollwerk.content.ClasspathContent
import de.bollwerk.engine.GameInfo
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.GameView
import de.bollwerk.setup.MatchRunners
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regressions-Goldens für das ausgelieferte Spiel: echte Partien mit dem echten Content ([ClasspathContent]) über
 * [MatchRunners.create] (`record = true`), eingecheckt unter `src/jvmTest/resources/goldens/`, Hash-Prüfpunkt alle 60 Ticks.
 *
 * - `schlucht_all_commands`: jede Befehlsart außer `EndTurn` und jede der sechs Waffen ([AllCommandsScript]).
 * - `schlucht_hotseat_endturn`: Zugmodus mit `EndTurn` ([HotseatScript]).
 * - KI-gegen-KI-Goldens (HARD, Schlucht und Hügel) liegen im Modul `:ai` (`AiGoldenTest`).
 *
 * Schlägt ein Test fehl, hat sich das Sim-Verhalten, die Regeln, der Content oder das Skript geändert; die Meldung nennt den
 * ersten abweichenden Tick und den Weg zum Neuerzeugen (siehe [GoldenSupport], README "Golden-Replays").
 */
class GoldenReplayTest {
    private val db = ClasspathContent.load()
    private val golden = GoldenSupport("setup", db)

    private fun verify(s: GoldenScenario) {
        if (golden.regen) {
            val fresh = s.record(db)
            // das Golden wird nur geschrieben, wenn es hält, was sein Name sagt (sonst hängt das Skript still)
            golden.regenerate(s.name, fresh) { r ->
                try {
                    coverage(s, r)
                } catch (e: AssertionError) {
                    throw AssertionError("${e.message} [script status: ${s.lastStatus}]", e)
                }
            }
            return
        }
        val replay = golden.verifyStored(s.name, s.setup)
        assertEquals(db.fingerprint, replay.contentHash, "golden '${s.name}' must be recorded with the shipping content")
        assertEquals(db.version, replay.contentVersion)
        assertEquals(GameInfo.ENGINE_VERSION, replay.engineVersion)
    }

    private fun reRecord(s: GoldenScenario) {
        if (golden.regen) return // das Verify-Beispiel hat die Datei gerade erst geschrieben
        golden.reRecordMatches(s.name, s.record(db), "scripted players")
    }

    private fun coverage(s: GoldenScenario, replay: Replay) = when (s.name) {
        GoldenScenarios.allCommands.name -> checkAllCommands(replay)
        GoldenScenarios.hotseat.name -> checkHotseat(replay)
        else -> error("no coverage check for golden '${s.name}'")
    }

    @Test fun allCommandsGoldenReplaysBitIdentical() = verify(GoldenScenarios.allCommands)

    @Test fun allCommandsGoldenIsReproducedByItsScript() = reRecord(GoldenScenarios.allCommands)

    @Test fun hotseatGoldenReplaysBitIdentical() = verify(GoldenScenarios.hotseat)

    @Test fun hotseatGoldenIsReproducedByItsScript() = reRecord(GoldenScenarios.hotseat)

    /** Beobachtungen aus der Wiedergabe: Fx je Tick (Schüsse, Teilungen, Türen) und Endergebnis. */
    private class Played(val replay: Replay) {
        var result: GameResult = GameResult.Ongoing
        val fx = ArrayList<FxEvent>()
        /** Zustand beim Eintritt in den Tick `t` (vor dessen Commands) für die gewünschten Ticks. */
        val probes = HashMap<Long, Probe>()
        inline fun <reified T : FxEvent> all(): List<T> = fx.filterIsInstance<T>()
    }

    /** Die Balken/Knoten von Spieler 0 an der Teilungsstelle der Skripte (siehe [AllCommandsScript]). */
    private class Probe(val beamsOnGround: Int, val splitNodes: Int)

    private fun probe(v: GameView): Probe {
        val b = v.beamView
        val n = v.nodeView
        fun near(node: Int, x: Float, y: Float): Boolean {
            val dx = n.x(node) - x; val dy = n.y(node) - y
            return dx * dx + dy * dy <= 0.3f * 0.3f
        }
        var beams = 0
        for (i in 0 until b.size) {
            if (!b.isAlive(i) || b.owner(i) != 0 || (b.flags(i) and BeamFlags.DEBRIS) != 0) continue
            val a = b.nodeA(i); val c = b.nodeB(i)
            // alle Teilstücke des Bodenbalkens (3,34)..(6,34): beide Enden liegen auf y = 34 zwischen x 3 und 6
            if (n.y(a) in 33.7f..34.3f && n.y(c) in 33.7f..34.3f && n.x(a) in 2.7f..6.3f && n.x(c) in 2.7f..6.3f) beams++
        }
        var splitNodes = 0
        for (i in 0 until n.size) {
            if (n.isAlive(i) && n.owner(i) == 0 && (n.flags(i) and NodeFlags.DEBRIS) == 0 && near(i, 4.5f, 29.5f)) splitNodes++
        }
        return Probe(beams, splitNodes)
    }

    private fun play(replay: Replay, probeTicks: Set<Long> = emptySet()): Played {
        val runner = MatchRunners.play(db, replay)
        val played = Played(replay)
        for (i in 0 until replay.ticks) {
            if (runner.tick in probeTicks) played.probes[runner.tick] = probe(runner.state)
            runner.runTicks(1)
            played.fx.addAll(runner.exchange.latest()!!.fx)
        }
        played.result = runner.result
        return played
    }

    /** Das Golden tut, was sein Name sagt (sonst wären seine Hashes wertlos). Auch beim Neuerzeugen vor dem Schreiben. */
    private fun checkAllCommands(replay: Replay) {
        val cmds = replay.commands
        val kinds: Set<KClass<out Command>> = cmds.map { it::class }.toSet()
        val required: Set<KClass<out Command>> = setOf(
            Command.PlaceBeam::class, Command.DeleteBeam::class, Command.DeleteDevice::class, Command.RepairBeam::class,
            Command.PlaceDevice::class, Command.ToggleDoor::class, Command.SetAim::class, Command.Fire::class,
            Command.Undo::class, Command.Surrender::class,
        )
        assertTrue(kinds.containsAll(required), "missing command types: ${required - kinds}")
        // Balken-Teilung über beide Enden (aBeamRef und bBeamRef), die erste wird per Undo zurückgenommen
        val splitsA = cmds.filterIsInstance<Command.PlaceBeam>().filter { it.aBeamRef >= 0 }
        val splitsB = cmds.filterIsInstance<Command.PlaceBeam>().filter { it.bBeamRef >= 0 }
        assertTrue(splitsA.isNotEmpty() && splitsB.isNotEmpty(), "needs beam splits via aBeamRef and bBeamRef")
        val splitA = splitsA.first()
        val undo = cmds.first { it is Command.Undo }
        assertTrue(undo.tick > splitA.tick && undo.tick < splitsB.first().tick, "the first split is undone before the second one")
        // zwischen Teilung und Undo sendet Spieler 0 nichts: das Undo kann nur die Teilung betreffen
        val between = cmds.filter { it.playerId == splitA.playerId && it.tick > splitA.tick && it.tick < undo.tick }
        assertTrue(between.isEmpty(), "nothing else may be sent between the split (tick ${splitA.tick}) and its Undo (tick ${undo.tick}): $between")
        assertEquals(1, cmds.count { it is Command.Undo }, "exactly one Undo")
        val p = play(replay, probeTicks = setOf(undo.tick, undo.tick + 1))
        // der Zustand belegt es: vor dem Undo zwei Teilstücke samt Knoten (4,5 | 29,5), danach wieder ein Balken ohne Knoten
        val before = p.probes.getValue(undo.tick)
        assertEquals(2, before.beamsOnGround, "the split halves exist before the Undo")
        assertEquals(1, before.splitNodes, "the split's new node exists before the Undo")
        val after = p.probes.getValue(undo.tick + 1)
        assertEquals(1, after.beamsOnGround, "the Undo merges the split back into one beam")
        assertEquals(0, after.splitNodes, "the Undo removes the split's node")
        assertEquals(2, p.all<FxEvent.BeamSplit>().size, "one split each; the undone one merges back without a split event")
        assertTrue(p.all<FxEvent.DoorToggled>().size >= 4, "doors open and close")
        // Tech-Kette und Waffen über die echten Regeln gebaut
        val built = cmds.filterIsInstance<Command.PlaceDevice>().map { db.devices[it.deviceTypeId].id }.toSet()
        assertTrue(built.containsAll(listOf("armoury", "upgrade_center", "factory", "sniper", "rocket", "laser", "turbine")), "built: $built")
        // jede ausgelieferte Waffe hat gefeuert
        val fired = p.all<FxEvent.Fired>().map { db.weapons[it.weaponId].id }.toSet()
        assertEquals(db.weapons.map { it.id }.toSet(), fired, "every shipping weapon fires")
        assertEquals(6, db.weapons.size, "the golden knows six shipping weapons; extend AllCommandsScript when content adds one")
        // SetAim vor jedem Fire desselben Geräts
        val aimed = HashSet<Long>()
        for (c in cmds) {
            if (c is Command.SetAim) aimed.add(c.deviceRef)
            if (c is Command.Fire) assertTrue(c.deviceRef in aimed, "Fire without SetAim at tick ${c.tick}")
        }
        assertTrue(p.all<FxEvent.Explosion>().size >= 5, "shells explode")
        // Aufgabe als letztes Command; die Partie war bis dahin offen (kein Reaktor zerstört)
        assertTrue(cmds.last() is Command.Surrender, "Surrender is the last command (script stalled?)")
        assertEquals(GameResult.Winner(1, WinReason.SURRENDER), p.result)
        assertTrue(p.all<FxEvent.ReactorDestroyed>().isEmpty())
        assertTrue(replay.ticks > cmds.last().tick, "the aftermath of the surrender is part of the golden")
    }

    private fun checkHotseat(replay: Replay) {
        val p = play(replay)
        val cmds = replay.commands
        assertTrue(cmds.count { it is Command.EndTurn } >= 3, "EndTurn count")
        assertTrue(cmds.any { it is Command.Fire } && cmds.any { it is Command.PlaceBeam } && cmds.any { it is Command.Surrender }, "needs Fire, PlaceBeam and Surrender")
        assertEquals(setOf(0, 1), cmds.map { it.playerId }.toSet())
        assertEquals(GameResult.Winner(0, WinReason.SURRENDER), p.result)
        assertTrue(p.all<FxEvent.Explosion>().size >= 2, "shells explode")
        assertEquals(45 * 60, replay.setup.turnTicks, "the golden uses the shipping turn length")
    }

    @Test
    fun allCommandsGoldenExercisesEveryCommandTypeAndEveryWeapon() {
        if (golden.regen) return
        checkAllCommands(golden.load(GoldenScenarios.allCommands.name))
    }

    @Test
    fun hotseatGoldenExercisesEndTurnAndTurnChanges() {
        if (golden.regen) return
        checkHotseat(golden.load(GoldenScenarios.hotseat.name))
    }
}
