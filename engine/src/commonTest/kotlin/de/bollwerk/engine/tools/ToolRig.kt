package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.rules.RuleChecks
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.rules.RulesSystems
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.MatchFactory
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.systems.StandardSystems
import kotlin.test.assertEquals

/**
 * Testaufbau der Werkzeuge: Spielzustand wie im Regel-Test (Stil-Bibel-Tabellen aus [RuleTables], Fort Spieler 0 mit
 * Ursprung x = 20, Boden y = 34; Spieler 1 gespiegelt um x = 100) plus Stepper zum Anwenden der erzeugten Commands.
 * Knoten Spieler 0: n20 (20,34), n23 (23,34), n26 (26,34), Spitze (23,31); Balken: 0 n20-n23 (Reaktor oben bei x 21,5),
 * 1 n23-n26, 2 n20-Spitze, 3 n23-Spitze, 4 n26-Spitze.
 */
class ToolRig(
    tables: SimTables = RuleTables.tables,
    metal: Float = 400f,
    energy: Float = 200f,
    config: SimConfig = SimConfig.DEFAULT,
    map: MapSpec = RuleTables.map(),
    turns: Boolean = false,
    /** `true`: vollständige Systemliste inkl. Physik (Knoten bewegen sich), sonst nur die Regeln. */
    physics: Boolean = false,
) {
    val state: GameState = MatchFactory.create(
        MatchSetup(
            7L, "tools", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN)),
            if (turns) TurnMode.TURNS else TurnMode.REALTIME, 0, metal, energy,
        ),
        tables, map, config,
    )
    private val stepper = if (physics) StandardSystems.stepper() else SimStepper(RulesSystems.all())
    private val stepCtx = StepContext()

    /** Werkzeug-Kontext für [player] mit Fang-Radius [pick] (m) und dem echten Validator. */
    fun ctx(player: Int = 0, pick: Float = 1f): ToolContext = ToolContext(state, player, pick)

    fun step() {
        stepCtx.beginTick(state.tick)
        stepper.step(state, stepCtx)
    }

    fun run(ticks: Int) { for (i in 0 until ticks) step() }

    /** Commands im selben Tick anwenden; ein Ergebnis je Command. */
    fun send(vararg cmds: Command): List<CommandResult> {
        stepCtx.beginTick(state.tick)
        for (c in cmds) stepCtx.commands.add(c.withTick(state.tick))
        stepper.step(state, stepCtx)
        return stepCtx.results.toList()
    }

    fun ok(cmd: Command?) {
        assertEquals(CommandResult.Accepted, send(cmd!!).single(), "$cmd")
    }

    fun rejects(reason: RejectReason, cmd: Command) {
        assertEquals(CommandResult.Rejected(reason), send(cmd).single(), "$cmd")
    }

    fun nref(slot: Int): Long = state.nodes.ref(slot)
    fun bref(slot: Int): Long = state.beams.ref(slot)
    fun dref(slot: Int): Long = state.devices.ref(slot)

    fun nodeAt(x: Float, y: Float, owner: Int = -1): Int {
        for (i in 0 until state.nodes.size) {
            if (!state.nodes.isAlive(i)) continue
            if (owner >= 0 && state.nodes.ownerOf[i] != owner) continue
            if (FloatMath.abs(state.nodes.x[i] - x) < 0.01f && FloatMath.abs(state.nodes.y[i] - y) < 0.01f) return i
        }
        return -1
    }

    fun beamBetween(a: Int, b: Int): Int {
        for (i in 0 until state.beams.size) {
            if (!state.beams.isAlive(i)) continue
            val x = state.beams.a[i]
            val y = state.beams.b[i]
            if ((x == a && y == b) || (x == b && y == a)) return i
        }
        return -1
    }

    val n20: Int get() = nodeAt(20f, 34f, 0)
    val n23: Int get() = nodeAt(23f, 34f, 0)
    val n26: Int get() = nodeAt(26f, 34f, 0)
    val apex: Int get() = nodeAt(23f, 31f, 0)
    val ground01: Int get() = beamBetween(n23, n26)
    val reactorBeam: Int get() = beamBetween(n20, n23)

    fun addAnchor(x: Float, y: Float, owner: Int = 0): Int {
        val n = state.nodes.alloc(x, y, owner, anchored = true)
        state.topologyDirty = true
        return n
    }

    fun addBeam(a: Int, b: Int, material: Int = RuleTables.WOOD, owner: Int = 0): Int {
        val mat = state.tables.materials[material]
        val len = RuleChecks.dist(state.nodes.x[a], state.nodes.y[a], state.nodes.x[b], state.nodes.y[b])
        val id = state.beams.alloc(a, b, material, len * mat.restLengthFactor, mat.hp, owner)
        state.topologyDirty = true
        return id
    }

    /** Fertiges Gerät ohne Kosten (Aim/Power nach Wunsch). */
    fun addDevice(type: Int, beam: Int, t: Float, sideNegative: Boolean = true, owner: Int = 0, aim: Float = 0f, power: Float = 1f): Int {
        val props = state.tables.devices[type]
        val id = state.devices.alloc(type, beam, t, props.hp, owner, 0, sideNegative, aim, power)
        state.topologyDirty = true
        return id
    }

    /** Beliebiger Balken von Spieler [owner] (erster lebender, der nicht in den Fort-Balken 0..4 von Spieler 0 liegt). */
    fun firstBeamOf(owner: Int): Int {
        for (i in 0 until state.beams.size) if (state.beams.isAlive(i) && state.beams.ownerOf[i] == owner) return i
        return -1
    }

    /** Lebende Geräte eines Typs. */
    fun devicesOf(type: Int, owner: Int = 0): List<Int> =
        (0 until state.devices.size).filter { state.devices.isAlive(it) && state.devices.typeOf[it] == type && state.devices.ownerOf[it] == owner }

    val metal: Float get() = state.players[0].metal
}

/** Down → Moves → Up über den Controller; liefert das Up-Ergebnis. */
fun ToolController.drag(ctx: ToolContext, vararg pts: Float): ToolResult {
    require(pts.size >= 4 && pts.size % 2 == 0)
    pointer(PointerPhase.DOWN, pts[0], pts[1], ctx)
    var i = 2
    while (i < pts.size - 2) { pointer(PointerPhase.MOVE, pts[i], pts[i + 1], ctx); i += 2 }
    pointer(PointerPhase.MOVE, pts[pts.size - 2], pts[pts.size - 1], ctx)
    return pointer(PointerPhase.UP, pts[pts.size - 2], pts[pts.size - 1], ctx)
}
