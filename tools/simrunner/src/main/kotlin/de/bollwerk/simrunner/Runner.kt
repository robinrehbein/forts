package de.bollwerk.simrunner

import de.bollwerk.ai.AiAgent
import de.bollwerk.ai.Difficulty
import de.bollwerk.ai.IdleAi
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
import de.bollwerk.engine.view.FxEvent
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
            val ai = AiProvider.create(p, Difficulty.valueOf(plan.difficulty), plan.seed)
            agents.add(ai.agent); sources.add(ai.agent)
            if (ai.fallback && p == 0) warnings.add("no real AI found in :ai (tried ${AiProvider.CANDIDATES.joinToString()}); using IdleAi for both players")
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
            val t0 = System.nanoTime()
            if (!session.tick()) { warnings.add("tick $t: session not ready (command source blocked)"); break }
            profiler?.wholeTick?.add(System.nanoTime() - t0)
            val fx = session.ctx.fx
            for (i in fx.indices) when (val e = fx[i]) {
                is FxEvent.BeamBroken -> if (e.cause != BreakCause.DELETED) {
                    val mat = state.tables.materials.getOrNull(e.materialId)?.key ?: "?"
                    breaks.add(BreakRecord(t, e.beamUid, mat, e.x, e.y, e.cause))
                }
                is FxEvent.Fired -> shots++
                is FxEvent.Explosion -> { explosions++; if (explosionLog.size < 12) explosionLog.add(floatArrayOf(t.toFloat(), e.x, e.y, e.radius)) }
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
            agents = agents.map { it::class.simpleName ?: "?" }, wallMs = (System.nanoTime() - wallStart) / 1e6,
        )
    }
}

/** Eine beschaffte KI; [fallback] = Platzhalter [IdleAi], weil keine echte Implementierung gefunden wurde. */
class ProvidedAi(val agent: AiAgent, val fallback: Boolean)

/**
 * KI-Beschaffung: nimmt eine echte Implementierung aus dem `ai`-Modul, wenn vorhanden (Klasse mit Konstruktor
 * `(Int, Difficulty, Long seed)` oder `(Int, Difficulty)`, Namen siehe [CANDIDATES] oder `-Dsimrunner.ai=<Klasse>`),
 * sonst [IdleAi] (mit Warnung im Bericht). Sobald `:ai` eine Factory anbietet, hier ersetzen.
 */
object AiProvider {
    val CANDIDATES: List<String> = listOf("de.bollwerk.ai.StandardAi", "de.bollwerk.ai.HeuristicAi", "de.bollwerk.ai.RuleBasedAi", "de.bollwerk.ai.BollwerkAi")

    fun create(playerId: Int, difficulty: Difficulty, seed: Long): ProvidedAi {
        val names = listOfNotNull(System.getProperty("simrunner.ai")) + CANDIDATES
        for (n in names) {
            val cls = try { Class.forName(n) } catch (_: ClassNotFoundException) { continue }
            if (!AiAgent::class.java.isAssignableFrom(cls)) continue
            val withSeed = try { cls.getConstructor(Int::class.javaPrimitiveType, Difficulty::class.java, Long::class.javaPrimitiveType) } catch (_: NoSuchMethodException) { null }
            if (withSeed != null) return ProvidedAi(withSeed.newInstance(playerId, difficulty, seed) as AiAgent, false)
            val plain = try { cls.getConstructor(Int::class.javaPrimitiveType, Difficulty::class.java) } catch (_: NoSuchMethodException) { continue }
            return ProvidedAi(plain.newInstance(playerId, difficulty) as AiAgent, false)
        }
        return ProvidedAi(IdleAi(playerId, difficulty), true)
    }
}
