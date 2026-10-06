package de.bollwerk.simrunner

import de.bollwerk.ai.IdleAi
import de.bollwerk.content.ClasspathContent
import de.bollwerk.engine.GameInfo
import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.setup.MatchBootstrap
import kotlin.system.exitProcess

/** Geparste Kommandozeile des Headless-Runners. */
data class SimArgs(
    val scenario: String? = null,
    val map: String = "schlucht",
    val ticks: Long = 600L,
    val seed: Long = 1L,
    val hash: Boolean = false,
    val profile: Boolean = false,
    val aiVsAi: Boolean = false,
    val renderPng: String? = null,
    val help: Boolean = false,
) {
    companion object {
        const val USAGE: String = """Usage: simrunner [options]
  --scenario <name>   Szenario laden (folgt mit WP12)
  --map <id>          Karte (Standard schlucht)
  --ticks <n>         Anzahl Ticks (Standard 600 = 10 s)
  --seed <n>          RNG-Seed (Standard 1)
  --hash              StateHash am Ende ausgeben
  --profile           Zeit pro Tick messen
  --ai-vs-ai          KI gegen KI spielen lassen
  --render <png>      Endzustand als PNG rendern (folgt mit WP7/WP12)
  --help              Diese Hilfe"""

        /** @throws IllegalArgumentException bei unbekannten oder unvollständigen Argumenten. */
        fun parse(args: Array<String>): SimArgs {
            var a = SimArgs()
            var i = 0
            fun value(name: String): String {
                require(i + 1 < args.size) { "missing value for $name" }
                return args[++i]
            }
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--scenario" -> a = a.copy(scenario = value(arg))
                    "--map" -> a = a.copy(map = value(arg))
                    "--ticks" -> a = a.copy(ticks = value(arg).toLongOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("--ticks needs a non-negative number"))
                    "--seed" -> a = a.copy(seed = value(arg).toLongOrNull() ?: throw IllegalArgumentException("--seed needs a number"))
                    "--hash" -> a = a.copy(hash = true)
                    "--profile" -> a = a.copy(profile = true)
                    "--ai-vs-ai" -> a = a.copy(aiVsAi = true)
                    "--render" -> a = a.copy(renderPng = value(arg))
                    "--help", "-h" -> a = a.copy(help = true)
                    else -> throw IllegalArgumentException("unknown argument '$arg'")
                }
                i++
            }
            return a
        }
    }
}

fun main(args: Array<String>) {
    val a = try {
        SimArgs.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        System.err.println(SimArgs.USAGE)
        exitProcess(2)
    }
    if (a.help) {
        println(SimArgs.USAGE)
        return
    }
    println("${GameInfo.TITLE} simrunner (WP0-Stub) - $a")

    val db = ClasspathContent.load()
    println("content ${db.version} (fingerprint ${StateHash.hex(db.fingerprint)}): ${db.materials.size} materials, " +
        "${db.devices.size} devices, ${db.weapons.size} weapons, ${db.techs.size} techs, ${db.maps.size} maps, " +
        "${db.blueprints.size} blueprints")

    val controller = if (a.aiVsAi) Controller.AI else Controller.HUMAN
    val setup = MatchSetup(seed = a.seed, mapId = a.map, players = listOf(PlayerSetup(controller), PlayerSetup(Controller.AI, "NORMAL")))
    val state = try {
        MatchBootstrap.create(db, setup)
    } catch (e: RuntimeException) {
        System.err.println("error: ${e.message}")
        exitProcess(2)
    }
    val sources = if (a.aiVsAi) listOf(IdleAi(0), IdleAi(1)) else emptyList()
    val session = GameSession(state, SimStepper.NONE, sources = sources)
    val t0 = System.nanoTime()
    session.run(a.ticks)
    val ms = (System.nanoTime() - t0) / 1e6
    println("map ${state.map.id}: ${state.nodes.aliveCount} nodes, ${state.beams.aliveCount} beams, ${state.devices.aliveCount} devices")
    println("ran ${a.ticks} ticks (no systems yet) -> tick=${state.tick}")
    if (a.profile) println("profile: %.3f ms total, %.4f ms/tick".format(ms, if (a.ticks > 0) ms / a.ticks else 0.0))
    if (a.hash) println("hash=${StateHash.hex(StateHash.of(state))}")
    if (a.scenario != null) println("note: scenarios arrive with WP12")
    if (a.renderPng != null) println("note: PNG rendering arrives with WP7/WP12 (requested '${a.renderPng}')")
}
