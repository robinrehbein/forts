package de.bollwerk.engine.loop

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.physics.PhysicsSystems
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.MatchFactory
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.systems.StandardSystems
import de.bollwerk.engine.view.GameView

/**
 * Gemeinsame Testwelt für Loop-, Runner- und Golden-Replay-Tests: Tabellen und Karte der Regel-Tests
 * ([RuleTables]: Stil-Bibel-Zahlen, zwei Festungen mit Reaktor) und die volle Systemliste mit eingeschwungenen Festungen,
 * also derselbe Ablauf wie `MatchBootstrap.createSession`.
 */
object LoopRig {
    const val CONTENT_VERSION = "rule-tables-test"

    fun setup(
        seed: Long = 11L,
        turns: Boolean = false,
        turnTicks: Int = 0,
        metal: Float = 600f,
        energy: Float = 400f,
    ) = MatchSetup(
        seed, "rules", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN)),
        if (turns) TurnMode.TURNS else TurnMode.REALTIME, turnTicks, metal, energy,
    )

    fun session(
        setup: MatchSetup = setup(),
        tables: SimTables = RuleTables.tables,
        map: MapSpec = RuleTables.map(),
        config: SimConfig = SimConfig.DEFAULT,
        sources: List<CommandSource> = emptyList(),
    ): GameSession {
        val state = MatchFactory.create(setup, tables, map, config)
        PhysicsSystems.settle(state)
        return GameSession(state, StandardSystems.stepper(), sources = sources)
    }

    fun meta(setup: MatchSetup, tables: SimTables = RuleTables.tables, config: SimConfig = SimConfig.DEFAULT) =
        ReplayMeta(setup, config, CONTENT_VERSION, fingerprint(tables))

    fun runner(
        setup: MatchSetup = setup(),
        tables: SimTables = RuleTables.tables,
        sources: List<CommandSource> = emptyList(),
        record: Boolean = true,
        hotseat: Boolean = false,
        inputDelayTicks: Int = 0,
        localPlayer: Int = 0,
        config: SimConfig = SimConfig.DEFAULT,
    ): MatchRunner = MatchRunner(
        session(setup, tables, config = config),
        localPlayer = localPlayer, hotseat = hotseat, inputDelayTicks = inputDelayTicks,
        replayMeta = if (record) meta(setup, tables, config) else null,
        sources = sources,
    )

    /**
     * Fingerabdruck der Test-Tabellen (FNV-1a über die kanonische Textform der Datenklassen; Float-Ausgabe ist auf der
     * JVM deterministisch). Entspricht `ContentDb.fingerprint` in den echten Replays.
     */
    fun fingerprint(tables: SimTables): Long {
        val h = StateHash.Hasher()
        for (c in tables.toString()) { h.byte(c.code); h.byte(c.code ushr 8) }
        return h.value
    }
}

/**
 * Skript-Spieler für Gefechts-Tests: baut Mine, Turbine, Werkstatt und Mörser auf einer Bodenplatte neben der Startfestung
 * und beschießt den gegnerischen Reaktor (Zielwinkel über [Ballistics.solveAngle], Zufall nur aus Zustand und Tick).
 * Spieler 1 spiegelt alles um x = 120. Liest nur die [GameView] (Refs aus dem Zustand), deshalb bitgleich wiederholbar.
 */
class DuelBot(
    private val p: Int,
    /** Ab diesem Tick darf geschossen werden. */
    private val fireFrom: Long = 0L,
    /** Zielpunkt statt Reaktor (null = gegnerischer Reaktor). */
    private val target: Pair<Float, Float>? = null,
    private val power: Float = 0.9f,
    private val buildDevices: Boolean = true,
) : CommandSource {
    private fun mx(x: Float) = if (p == 0) x else 120f - x
    private val top get() = p == 0 // sideNegative = Oberseite (Spieler 1 läuft gespiegelt)

    private fun newestBeam(v: GameView, owner: Int): Int {
        var best = -1
        for (i in 0 until v.beamView.size) if (v.beamView.isAlive(i) && v.beamView.owner(i) == owner) best = i
        return best
    }

    private fun deviceOf(v: GameView, type: Int): Int {
        for (i in 0 until v.deviceView.size) {
            if (v.deviceView.isAlive(i) && v.deviceView.owner(i) == p && v.deviceView.type(i) == type) return i
        }
        return -1
    }

    /** Balken zwischen zwei Weltpunkten (Toleranz 0,05) oder −1. */
    private fun beamAt(v: GameView, x0: Float, x1: Float): Int {
        for (i in 0 until v.beamView.size) {
            if (!v.beamView.isAlive(i) || v.beamView.owner(i) != p) continue
            val ax = v.nodeView.x(v.beamView.nodeA(i)); val bx = v.nodeView.x(v.beamView.nodeB(i))
            val ay = v.nodeView.y(v.beamView.nodeA(i)); val by = v.nodeView.y(v.beamView.nodeB(i))
            if (FloatMath.abs(ay - 34f) > 0.2f || FloatMath.abs(by - 34f) > 0.2f) continue
            if ((FloatMath.abs(ax - x0) < 0.05f && FloatMath.abs(bx - x1) < 0.05f) || (FloatMath.abs(ax - x1) < 0.05f && FloatMath.abs(bx - x0) < 0.05f)) return i
        }
        return -1
    }

    private fun tAlong(v: GameView, beam: Int, x: Float): Float {
        val ax = v.nodeView.x(v.beamView.nodeA(beam)); val bx = v.nodeView.x(v.beamView.nodeB(beam))
        return (x - ax) / (bx - ax)
    }

    override fun commandsFor(tick: Long, view: GameView): List<Command> {
        if (buildDevices) {
            when (tick) {
                0L -> return listOf(Command.PlaceBeam(tick, p, aX = mx(26f), aY = 34f, bX = mx(31f), bY = 34f, materialId = RuleTables.WOOD))
                2L -> {
                    val plate = newestBeam(view, p)
                    val ground = beamAt(view, mx(23f), mx(26f))
                    return listOf(
                        Command.PlaceDevice(tick, p, RuleTables.WORKSHOP, view.beamView.ref(plate), 0.1f, top),
                        Command.PlaceDevice(tick, p, RuleTables.TURBINE, view.beamView.ref(plate), 0.5f, top),
                        Command.PlaceDevice(tick, p, RuleTables.MINE, view.beamView.ref(ground), tAlong(view, ground, mx(24.4f)), top),
                    )
                }
            }
            // Mörser, sobald die Werkstatt fertig ist (alle 30 Ticks erneut, bis er steht)
            if (tick >= 1810L && tick % 30L == 0L && deviceOf(view, RuleTables.MORTAR) < 0 && view.player(p).hasTech(RuleTables.T_WORKSHOP)) {
                val plate = beamAt(view, mx(26f), mx(31f))
                if (plate >= 0) return listOf(Command.PlaceDevice(tick, p, RuleTables.MORTAR, view.beamView.ref(plate), 0.9f, top))
            }
        }
        if (tick < fireFrom || tick % 10L != 0L) return emptyList()
        val m = deviceOf(view, RuleTables.MORTAR)
        if (m < 0 || view.deviceView.buildTicks(m) > 0 || view.deviceView.reloadTicks(m) > 0) return emptyList()
        val out = FloatArray(DeviceGeometry.SIZE)
        DeviceGeometry.mount(view, m, out)
        var tx: Float; var ty: Float
        if (target != null) { tx = target.first; ty = target.second } else {
            val r = view.player(1 - p).reactorDeviceId
            if (r < 0 || !view.deviceView.isAlive(r)) return emptyList()
            val g = FloatArray(DeviceGeometry.SIZE)
            DeviceGeometry.mount(view, r, g)
            tx = g[DeviceGeometry.X]; ty = g[DeviceGeometry.Y]
        }
        val weapon = view.tables.weapons[view.tables.devices[RuleTables.MORTAR].weapon]
        val angle = Ballistics.solveAngle(
            out[DeviceGeometry.MUZZLE_X], out[DeviceGeometry.MUZZLE_Y], tx, ty, power, weapon, view.wind, view.simConfig, highArc = true,
        )
        if (angle.isNaN()) return emptyList()
        val ref = view.deviceView.ref(m)
        return listOf(Command.SetAim(tick, p, ref, angle, power), Command.Fire(tick, p, ref))
    }
}

/**
 * Skript-Spieler für den Zugmodus: handelt nur in der eigenen Spielphase, beim ersten eigenen Zug Aufbau, danach Mörser
 * (sobald die Werkstatt steht) und Schüsse auf den gegnerischen Reaktor. [endEarly] beendet den Zug nach [endAfterTicks]
 * Ticks mit `EndTurn`, sonst läuft die Zugzeit ab (beide Wege des TURN-Systems).
 */
class TurnBot(private val p: Int, private val endEarly: Boolean, private val endAfterTicks: Long = 30L) : CommandSource {
    private var seenTurn = -1
    private var turnStart = 0L
    private var ownTurns = 0
    private val duel = DuelBot(p, fireFrom = 0L, buildDevices = false)
    private val builder = DuelBot(p, fireFrom = Long.MAX_VALUE, buildDevices = true)

    override fun commandsFor(tick: Long, view: GameView): List<Command> {
        val t = view.turn
        if (t.activePlayer != p || t.phase != de.bollwerk.engine.sim.TurnPhase.PLAY) return emptyList()
        if (t.turnNumber != seenTurn) { seenTurn = t.turnNumber; turnStart = tick; ownTurns++ }
        val off = tick - turnStart
        val out = ArrayList<Command>()
        if (ownTurns == 1) {
            // Aufbau wie im Duell, aber relativ zum Zugbeginn (DuelBot-Ticks 0 und 2)
            if (off == 0L || off == 2L) out.addAll(builder.commandsFor(off, view).map { it.withTick(tick) })
        } else {
            if (off == 0L) out.addAll(builder.commandsFor(1830L, view).map { it.withTick(tick) })
            if (off == 5L) out.addAll(duel.commandsFor(10L, view).map { it.withTick(tick) })
        }
        if (endEarly && off == endAfterTicks) out.add(Command.EndTurn(tick, p))
        return out
    }
}
