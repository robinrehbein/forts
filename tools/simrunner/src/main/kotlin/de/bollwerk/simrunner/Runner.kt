package de.bollwerk.simrunner

import de.bollwerk.ai.AiAgent
import de.bollwerk.ai.AiFactory
import de.bollwerk.ai.Difficulty
import de.bollwerk.ai.StandardAi
import de.bollwerk.content.ContentDb
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.loop.CommandRecorder
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.physics.PhysicsSystems
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.systems.StandardSystems
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import de.bollwerk.setup.MatchBootstrap
import java.io.File

/** Was ausgeführt wird (aus Kommandozeile und Szenario zusammengeführt). */
data class RunPlan(
    val name: String,
    val map: String,
    val seed: Long,
    val ticks: Long,
    val steps: List<ScriptStep> = emptyList(),
    /** Idle-Szenario: Balkenbrüche sind ein Fehler. */
    val idle: Boolean = false,
    val aiVsAi: Boolean = false,
    val profile: Boolean = false,
    val hashEvery: Int = 60,
    val difficulty: String = "NORMAL",
) {
    companion object {
        /** Kommandozeile überschreibt Szenario, Szenario überschreibt Standard. */
        fun of(args: SimArgs, scenario: Scenario?): RunPlan {
            val ticks = args.ticks ?: scenario?.ticks ?: SimArgs.DEFAULT_TICKS
            return RunPlan(
                name = scenario?.name ?: "adhoc",
                map = args.map ?: scenario?.map ?: SimArgs.DEFAULT_MAP,
                seed = args.seed ?: scenario?.seed ?: SimArgs.DEFAULT_SEED,
                ticks = ticks,
                steps = scenario?.let { ScenarioIo.expand(it, ticks) }.orEmpty(), // Wiederholungen bis zur effektiven Lauflänge
                idle = scenario?.idle ?: false,
                aiVsAi = args.aiVsAi,
                profile = args.profile,
                difficulty = args.difficulty,
            )
        }
    }
}

/** PNG-Ausgabe: [atTicks] leer = Endzustand. */
data class RenderPlan(val path: String, val atTicks: List<Long>, val width: Int, val height: Int, val warmup: Int, val view: FloatArray? = null)

data class HashPoint(val tick: Long, val hash: Long)

/** Ein Balkenbruch (kein Abriss durch den Spieler). */
data class BreakRecord(val tick: Long, val beamUid: Int, val material: String, val x: Float, val y: Float, val cause: BreakCause)

data class RunResult(
    val plan: RunPlan,
    val ticksRun: Long,
    val hashes: List<HashPoint>,
    val finalHash: Long,
    val breaks: List<BreakRecord>,
    val beamsStart: Int,
    val beamsEnd: Int,
    val nodesEnd: Int,
    val devicesStart: Int,
    val devicesEnd: Int,
    val peakProjectiles: Int,
    val peakBeams: Int,
    /** Abgelehnte Commands je Grund (Skript und KI). */
    val rejections: Map<String, Int>,
    val peakBurning: Int,
    val shots: Int,
    val explosions: Int,
    /** Die ersten Explosionen (Tick, x, y, Radius) zum Einstellen von Szenarien. */
    val explosionLog: List<FloatArray>,
    val deviceLosses: Int,
    val result: GameResult,
    val profile: List<SlotStat>?,
    val rendered: List<File>,
    val warnings: List<String>,
    val agents: List<String>,
    val wallMs: Double,
    /** Einschläge (Explosionen + Hitscan-Treffer) und davon Treffer auf Balken/Geräte (Rest: Gelände/Luft). */
    val impacts: Int = 0,
    val structureHits: Int = 0,
    /**
     * Davon dem Schützen zugeordnet: Treffer auf **gegnerische** Balken/Geräte, auf die **eigene** Festung und auf
     * Trümmer. [structureHits] − enemy − own − debris = Treffer, deren Schütze nicht bestimmbar war.
     */
    val enemyHits: Int = 0,
    val ownHits: Int = 0,
    val debrisHits: Int = 0,
)

/** Die Testbank: baut die Partie, führt sie aus, sammelt Zahlen. Keine Wall-Clock im Sim-Pfad (nur Messung drumherum). */
object Runner {
    fun run(db: ContentDb, plan: RunPlan, render: RenderPlan? = null): RunResult {
        val wallStart = System.nanoTime()
        val players = if (plan.aiVsAi) listOf(PlayerSetup(Controller.AI, plan.difficulty), PlayerSetup(Controller.AI, plan.difficulty))
        else listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN))
        val setup = MatchSetup(seed = plan.seed, mapId = plan.map, players = players)
        val state = MatchBootstrap.create(db, setup)
        PhysicsSystems.settle(state) // wie MatchBootstrap.createSession: eingeschwungene Festungen
        val warnings = ArrayList<String>()

        val profiler = if (plan.profile) Profiler() else null
        val systems = StandardSystems.all()
        val stepper = if (profiler == null) SimStepper(systems) else SimStepper(systems.mapValues { (slot, sys) -> profiler.wrap(slot, sys) })

        val script = ScriptSource(state, plan.steps)
        val agents = ArrayList<AiAgent>()
        val sources = ArrayList<CommandSource>()
        sources.add(script)
        if (plan.aiVsAi) for (p in 0 until state.players.size) {
            val ai = AiFactory.create(p, Difficulty.valueOf(plan.difficulty), state.tables, seed = plan.seed)
            agents.add(ai); sources.add(ai)
        }
        val rejections = java.util.TreeMap<String, Int>()
        val session = GameSession(state, stepper, sources = sources, recorder = CommandRecorder { _, _, result ->
            if (result is CommandResult.Rejected) rejections.merge(result.reason.name, 1, Int::plus)
        })

        val cheats = plan.steps.filter { it.action in Cheats.ACTIONS }
        var nextCheat = 0
        var nextAt = 0
        val capture = render?.let { SceneCapture(state, it.width, it.height, view = it.view) }
        val renderTicks = render?.let { if (it.atTicks.isEmpty()) listOf(plan.ticks) else it.atTicks.sorted().distinct() }.orEmpty()
        val rendered = ArrayList<File>()
        val suffixed = render != null && render.atTicks.isNotEmpty()

        var beamsStart = state.beams.aliveCount
        var devicesStart = state.devices.aliveCount
        var peakBeams = beamsStart
        var lastFrameTick = -1L
        val hashes = ArrayList<HashPoint>()
        val breaks = ArrayList<BreakRecord>()
        var peakProj = 0
        var peakBurning = 0
        var shots = 0
        var explosions = 0
        val explosionLog = ArrayList<FloatArray>()
        var deviceLosses = 0
        val tally = HitTally()
        var t = 0L

        fun saveNext() {
            val f = SceneCapture.fileFor(render!!.path, renderTicks[nextAt], suffixed)
            capture!!.save(f); rendered.add(f)
            if (capture.isUniform()) warnings.add("PNG ${f.name} is a single colour (nothing drawn or full-screen flash); do not trust it")
            nextAt++
        }

        /** Im Vorlauffenster vor dem nächsten Zeitpunkt jeden Tick zeichnen (Partikel/Flammen), am Zeitpunkt speichern. */
        fun renderDue() {
            if (capture == null || render == null || nextAt >= renderTicks.size) return
            if (state.tick < renderTicks[nextAt] - render.warmup) { session.fxBuffer.clear(); return }
            capture.frame(state, session.fxBuffer)
            lastFrameTick = state.tick
            while (nextAt < renderTicks.size && renderTicks[nextAt] <= state.tick) saveNext()
        }

        while (true) {
            if (t % plan.hashEvery == 0L) hashes.add(HashPoint(t, StateHash.of(state)))
            renderDue()
            if (t >= plan.ticks) break
            if (plan.aiVsAi && state.result != GameResult.Ongoing) break
            while (nextCheat < cheats.size && cheats[nextCheat].tick <= t) {
                Cheats.apply(state, cheats[nextCheat++])?.let { warnings.add("tick $t: $it") }
            }
            if (t == 0L) { beamsStart = state.beams.aliveCount; devicesStart = state.devices.aliveCount } // nach den Testbank-Aktionen
            tally.beforeTick(state)
            val t0 = System.nanoTime()
            if (!session.tick()) { warnings.add("tick $t: session not ready (command source blocked)"); break }
            profiler?.wholeTick?.add(System.nanoTime() - t0)
            val fx = session.ctx.fx
            tally.afterTick(state, fx)
            for (i in fx.indices) when (val e = fx[i]) {
                is FxEvent.BeamBroken -> if (e.cause != BreakCause.DELETED) {
                    val mat = state.tables.materials.getOrNull(e.materialId)?.key ?: "?"
                    breaks.add(BreakRecord(t, e.beamUid, mat, e.x, e.y, e.cause))
                }
                is FxEvent.Fired -> shots++
                is FxEvent.Explosion -> {
                    explosions++
                    if (explosionLog.size < 12) explosionLog.add(floatArrayOf(t.toFloat(), e.x, e.y, e.radius))
                }
                is FxEvent.DeviceDestroyed -> deviceLosses++
                else -> {}
            }
            if (state.beams.aliveCount > peakBeams) peakBeams = state.beams.aliveCount
            if (state.projectiles.aliveCount > peakProj) peakProj = state.projectiles.aliveCount
            var burning = 0
            val b = state.beams
            for (j in 0 until b.size) if (b.isAlive(j) && b.fireOf[j] > 0f) burning++
            if (burning > peakBurning) peakBurning = burning
            if (capture == null || nextAt >= renderTicks.size) session.fxBuffer.clear()
            t++
        }
        if (hashes.last().tick != state.tick) hashes.add(HashPoint(state.tick, StateHash.of(state)))
        if (script.skipped > 0) warnings.add("${script.skipped} scripted command(s) skipped (device not found/destroyed)")
        if (rejections.isNotEmpty()) warnings.add("commands rejected: " + rejections.entries.joinToString { "${it.key}=${it.value}" })
        if (capture != null && nextAt < renderTicks.size && lastFrameTick != state.tick) {
            capture.frame(state, session.fxBuffer) // Lauf endete vor dem Vorlauffenster: sonst wäre das Bild leer
        }
        while (capture != null && nextAt < renderTicks.size) { // Lauf endete vor dem Zeitpunkt (KI-Sieg): Endzustand
            warnings.add("render tick ${renderTicks[nextAt]} not reached (run ended at ${state.tick}); saved final state")
            saveNext()
        }

        return RunResult(
            plan = plan, ticksRun = state.tick, hashes = hashes, finalHash = StateHash.of(state), breaks = breaks,
            beamsStart = beamsStart, beamsEnd = state.beams.aliveCount, nodesEnd = state.nodes.aliveCount,
            devicesStart = devicesStart, devicesEnd = state.devices.aliveCount,
            peakProjectiles = peakProj, peakBeams = peakBeams, rejections = rejections, peakBurning = peakBurning, shots = shots, explosions = explosions, explosionLog = explosionLog, deviceLosses = deviceLosses,
            result = state.result, profile = profiler?.stats(), rendered = rendered, warnings = warnings,
            agents = agents.map { describeAgent(it) }, wallMs = (System.nanoTime() - wallStart) / 1e6,
            impacts = tally.impacts, structureHits = tally.structureHits,
            enemyHits = tally.enemyHits, ownHits = tally.ownHits, debrisHits = tally.debrisHits,
        )
    }
}

/**
 * Trefferbilanz je Schütze (nur Messung, außerhalb des Sim-Pfads): Einschläge = Explosionen + Hitscan-Treffer (MG,
 * Scharfschütze; ballistische Treffer zählen über ihre Explosion, der Laser gar nicht).
 * - Schütze einer Explosion: das vor dem Tick fliegende Geschoss derselben Waffe, das dem Einschlag am nächsten war.
 * - Schütze eines Hitscan-Treffers: das Gerät aus dem `Fired`-Ereignis, das das Waffen-System direkt nach den Treffern
 *   desselben Schusses meldet.
 * - Getroffener: Besitzer des Balkens (Explosion: `hitBeamUid`, Hitscan: Ziel-uid) bzw. des Geräts (Explosion mit
 *   `hitMaterialId == -2`: nächstes Gerät); Trümmer zählen extra. Besitz wird vor jedem Tick erfasst, damit auch im
 *   Tick zerstörte Ziele noch zugeordnet werden.
 */
class HitTally {
    var impacts = 0; private set
    var structureHits = 0; private set
    var enemyHits = 0; private set
    var ownHits = 0; private set
    var debrisHits = 0; private set

    private val beamOwner = HashMap<Int, Int>()
    private val debrisBeams = HashSet<Int>()
    private val deviceOwner = HashMap<Int, Int>()
    private var devX = FloatArray(0); private var devY = FloatArray(0); private var devOwner = IntArray(0); private var devCount = 0
    private var prX = FloatArray(0); private var prY = FloatArray(0); private var prKind = IntArray(0); private var prOwner = IntArray(0); private var prCount = 0
    /** Hitscan-Treffer, deren `Fired` noch aussteht: (Waffe, Ziel-Besitzer, Trümmer?). */
    private val pendingWeapon = ArrayList<Int>()
    private val pendingOwner = ArrayList<Int>()
    private val pendingDebris = ArrayList<Boolean>()

    fun beforeTick(state: de.bollwerk.engine.sim.GameState) {
        val b = state.beams
        for (j in 0 until b.size) if (b.isAlive(j)) {
            beamOwner[b.uid(j)] = b.owner(j)
            if ((b.flags(j) and de.bollwerk.engine.sim.BeamFlags.DEBRIS) != 0) debrisBeams.add(b.uid(j))
        }
        val d = state.devices
        if (devX.size < d.size) { devX = FloatArray(d.size * 2); devY = FloatArray(d.size * 2); devOwner = IntArray(d.size * 2) }
        devCount = 0
        for (i in 0 until d.size) if (d.isAlive(i)) {
            deviceOwner[d.uid(i)] = d.owner(i)
            devX[devCount] = d.x(i); devY[devCount] = d.y(i); devOwner[devCount] = d.owner(i); devCount++
        }
        val p = state.projectiles
        if (prX.size < p.size) { prX = FloatArray(p.size * 2); prY = FloatArray(p.size * 2); prKind = IntArray(p.size * 2); prOwner = IntArray(p.size * 2) }
        prCount = 0
        val dt = state.config.dt
        for (i in 0 until p.size) if (p.isAlive(i)) {
            prX[prCount] = p.x[i] + p.vx[i] * dt; prY[prCount] = p.y[i] + p.vy[i] * dt
            prKind[prCount] = p.kindOf[i]; prOwner[prCount] = p.ownerOf[i]; prCount++
        }
    }

    fun afterTick(state: de.bollwerk.engine.sim.GameState, fx: List<FxEvent>) {
        pendingWeapon.clear(); pendingOwner.clear(); pendingDebris.clear()
        for (e in fx) when (e) {
            is FxEvent.Explosion -> {
                impacts++
                var owner = -1
                var debris = false
                if (e.hitBeamUid >= 0) {
                    owner = beamOwner[e.hitBeamUid] ?: -1
                    debris = e.hitBeamUid in debrisBeams
                } else if (e.hitMaterialId == -2) {
                    owner = nearestDeviceOwner(e.x, e.y)
                }
                if (e.hitBeamUid >= 0 || e.hitMaterialId == -2) structureHits++ else continue
                credit(shooterOfExplosion(e.weaponId, e.x, e.y), owner, debris)
            }
            is FxEvent.Hit -> if (!e.splash && e.weaponId >= 0 && state.tables.weapons[e.weaponId].mode != WeaponMode.BALLISTIC) {
                impacts++
                if (e.target == HitTarget.TERRAIN) continue
                structureHits++
                val owner = (if (e.target == HitTarget.BEAM) beamOwner[e.targetUid] else deviceOwner[e.targetUid]) ?: -1
                pendingWeapon.add(e.weaponId); pendingOwner.add(owner); pendingDebris.add(e.target == HitTarget.BEAM && e.targetUid in debrisBeams)
            }
            is FxEvent.Fired -> {
                val shooter = deviceOwner[e.deviceUid] ?: -1
                var k = 0
                while (k < pendingWeapon.size) {
                    if (pendingWeapon[k] == e.weaponId) {
                        credit(shooter, pendingOwner[k], pendingDebris[k])
                        pendingWeapon.removeAt(k); pendingOwner.removeAt(k); pendingDebris.removeAt(k)
                    } else k++
                }
            }
            else -> {}
        }
    }

    private fun credit(shooter: Int, targetOwner: Int, debris: Boolean) {
        if (shooter < 0 || targetOwner < 0) return
        when {
            debris -> debrisHits++
            targetOwner == shooter -> ownHits++
            else -> enemyHits++
        }
    }

    private fun shooterOfExplosion(weaponId: Int, x: Float, y: Float): Int {
        var best = -1
        var bd = MAX_MATCH_DIST * MAX_MATCH_DIST
        for (k in 0 until prCount) {
            if (prKind[k] != weaponId) continue
            val dx = prX[k] - x; val dy = prY[k] - y
            val d2 = dx * dx + dy * dy
            if (d2 < bd) { bd = d2; best = prOwner[k] }
        }
        return best
    }

    private fun nearestDeviceOwner(x: Float, y: Float): Int {
        var best = -1
        var bd = MAX_MATCH_DIST * MAX_MATCH_DIST
        for (k in 0 until devCount) {
            val dx = devX[k] - x; val dy = devY[k] - y
            val d2 = dx * dx + dy * dy
            if (d2 < bd) { bd = d2; best = devOwner[k] }
        }
        return best
    }

    private companion object {
        /** Höchstabstand (m) zwischen vorhergesagter Geschoss- bzw. Geräteposition und Einschlag. */
        const val MAX_MATCH_DIST = 4f
    }
}

/** Kurzbeschreibung einer KI für den Bericht (Klasse, Spieler, Stufe, Zähler). */
fun describeAgent(a: AiAgent): String {
    val name = a::class.simpleName ?: "?"
    val stats = (a as? StandardAi)?.stats?.toString()
    return if (stats == null) name else "$name(p${a.playerId} ${a.difficulty}: $stats)"
}
