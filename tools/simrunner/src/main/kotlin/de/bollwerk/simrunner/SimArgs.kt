package de.bollwerk.simrunner

/**
 * Geparste Kommandozeile des Headless-Runners. `null` = nicht angegeben; ein angegebener Wert überschreibt
 * den Wert des Szenarios, dieser wiederum den Standard ([DEFAULT_MAP], [DEFAULT_TICKS], [DEFAULT_SEED]).
 */
data class SimArgs(
    val scenario: String? = null,
    val map: String? = null,
    val ticks: Long? = null,
    val seed: Long? = null,
    val hash: Boolean = false,
    val profile: Boolean = false,
    val assertStable: Boolean = false,
    val aiVsAi: Boolean = false,
    val renderPng: String? = null,
    /** Ticks, zu denen gerendert wird (`--at`, mehrfach erlaubt); leer = nur der letzte Tick. */
    val renderAt: List<Long> = emptyList(),
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    /** Ticks vor dem Render-Tick, in denen der Renderer mitläuft (Partikel, Flammen, Explosionen). */
    val warmup: Int = DEFAULT_WARMUP,
    /** Sichtfenster in Weltmetern (minX, minY, maxX, maxY); null = ganze Karte. */
    val view: List<Float>? = null,
    /** KI-Stufe für `--ai-vs-ai` (EASY/NORMAL/HARD). */
    val difficulty: String = "NORMAL",
    val help: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAP: String = "schlucht"
        const val DEFAULT_TICKS: Long = 600L
        const val DEFAULT_SEED: Long = 1L
        const val DEFAULT_WIDTH: Int = 1600
        const val DEFAULT_HEIGHT: Int = 740
        const val DEFAULT_WARMUP: Int = 120
        const val WARMUP_ALL: Int = Int.MAX_VALUE
        val DIFFICULTIES: List<String> = listOf("EASY", "NORMAL", "HARD")

        const val USAGE: String = """Usage: simrunner [options]
  --scenario <file|name>  Szenario (JSON) laden; Name ohne Endung sucht in tools/simrunner/scenarios/
  --map <schlucht|huegel> Karte (Standard schlucht, überschreibt das Szenario)
  --ticks <n>             Anzahl Ticks (Standard 600 = 10 s, überschreibt das Szenario)
  --seed <n>              RNG-Seed (Standard 1, überschreibt das Szenario)
  --hash                  StateHash alle 60 Ticks und am Ende ausgeben
  --profile               ms/Tick (Mittel, p95, max) je SystemSlot
  --assert-stable         Exit-Code 1, wenn ein Balken bricht (Bruchursache ausser Abriss)
  --ai-vs-ai              Beide Spieler von der KI steuern lassen (ai-Modul; sonst IdleAi), Ergebnis melden
  --render <png>          Szene als PNG rendern (Endzustand oder bei --at)
  --at <tick>             Render-Zeitpunkt, mehrfach erlaubt; Dateien erhalten den Suffix _t<tick>
  --width <px>            Bildbreite (Standard 1600)
  --height <px>           Bildhöhe (Standard 740)
  --warmup <n|all>        Render-Vorlauf in Ticks vor dem Zeitpunkt (Standard 120). Nur im Vorlauf laufen Partikel,
                          Flammen und Brandspur-Decals mit; aeltere Treffer fehlen im Bild. `all` zeichnet ab Tick 0
                          (langsamer, aber mit allen Decals)
  --difficulty <d>        KI-Stufe fuer --ai-vs-ai: EASY, NORMAL (Standard), HARD
  --view <x0,y0,x1,y1>    Render-Ausschnitt in Metern (Standard: aus Karte und Festungen berechnet)
  --help                  Diese Hilfe"""

        fun parseView(text: String): List<Float> {
            val v = text.split(',').map { it.trim().toFloatOrNull() ?: throw IllegalArgumentException("--view needs x0,y0,x1,y1 (numbers)") }
            require(v.size == 4 && v[2] > v[0] && v[3] > v[1]) { "--view needs x0,y0,x1,y1 with x1 > x0 and y1 > y0" }
            return v
        }

        /** @throws IllegalArgumentException bei unbekannten oder unvollständigen Argumenten. */
        fun parse(args: Array<String>): SimArgs {
            var a = SimArgs()
            var i = 0
            fun value(name: String): String {
                require(i + 1 < args.size) { "missing value for $name" }
                return args[++i]
            }
            fun long(name: String, min: Long): Long =
                value(name).toLongOrNull()?.takeIf { it >= min } ?: throw IllegalArgumentException("$name needs a number >= $min")
            fun int(name: String, min: Int): Int =
                value(name).toIntOrNull()?.takeIf { it >= min } ?: throw IllegalArgumentException("$name needs a number >= $min")
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--scenario" -> a = a.copy(scenario = value(arg))
                    "--map" -> a = a.copy(map = value(arg))
                    "--ticks" -> a = a.copy(ticks = long(arg, 0))
                    "--seed" -> a = a.copy(seed = value(arg).toLongOrNull() ?: throw IllegalArgumentException("--seed needs a number"))
                    "--hash" -> a = a.copy(hash = true)
                    "--profile" -> a = a.copy(profile = true)
                    "--assert-stable" -> a = a.copy(assertStable = true)
                    "--ai-vs-ai" -> a = a.copy(aiVsAi = true)
                    "--render" -> a = a.copy(renderPng = value(arg))
                    "--at" -> a = a.copy(renderAt = a.renderAt + long(arg, 0))
                    "--width" -> a = a.copy(width = int(arg, 16))
                    "--height" -> a = a.copy(height = int(arg, 16))
                    "--warmup" -> a = a.copy(warmup = if (i + 1 < args.size && args[i + 1] == "all") { i++; WARMUP_ALL } else int(arg, 0))
                    "--difficulty" -> a = a.copy(difficulty = value(arg).uppercase().also { require(it in DIFFICULTIES) { "--difficulty needs one of $DIFFICULTIES" } })
                    "--view" -> a = a.copy(view = parseView(value(arg)))
                    "--help", "-h" -> a = a.copy(help = true)
                    else -> throw IllegalArgumentException("unknown argument '$arg'")
                }
                i++
            }
            require(a.renderAt.isEmpty() || a.renderPng != null) { "--at needs --render <png>" }
            return a
        }
    }
}
