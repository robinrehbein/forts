package de.bollwerk.simrunner

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.view.GameView

/**
 * Wandelt die Command-Schritte eines Szenarios in echte [Command]s um. Das Gerät wird erst im Tick über
 * `Pool.ref` aufgelöst (Refs gibt es vor dem Start nicht); fehlt es (zerstört), wird der Schritt übersprungen
 * und in [skipped] gezählt.
 */
class ScriptSource(private val state: GameState, steps: List<ScriptStep>) : CommandSource {
    private val steps = steps.filter { it.action in COMMANDS }
    private var next = 0
    var skipped: Int = 0; private set

    override fun commandsFor(tick: Long, view: GameView): List<Command> {
        if (next >= steps.size || steps[next].tick > tick) return emptyList()
        val out = ArrayList<Command>()
        while (next < steps.size && steps[next].tick <= tick) {
            val s = steps[next++]
            when (s.action) {
                "surrender" -> out.add(Command.Surrender(tick, s.player))
                else -> {
                    val ref = deviceRef(s)
                    if (ref < 0) { skipped++; continue }
                    if (s.action == "aim" || s.action == "shoot") {
                        out.add(Command.SetAim(tick, s.player, ref, s.angleDeg * DEG, s.power))
                    }
                    if (s.action == "fire" || s.action == "shoot") out.add(Command.Fire(tick, s.player, ref))
                }
            }
        }
        return out
    }

    /** Ref des [ScriptStep.deviceIndex]-ten lebenden Geräts von Typ und Spieler des Schritts, sonst −1. */
    private fun deviceRef(s: ScriptStep): Long {
        val d = state.devices
        val types = state.tables.devices
        var seen = 0
        for (i in 0 until d.size) {
            if (!d.isAlive(i) || d.ownerOf[i] != s.player || types[d.typeOf[i]].key != s.device) continue
            if (seen++ == s.deviceIndex) return d.ref(i)
        }
        return -1L
    }

    companion object {
        val COMMANDS: Set<String> = setOf("aim", "fire", "shoot", "surrender")
        private const val DEG: Float = 0.017453292f
    }
}
