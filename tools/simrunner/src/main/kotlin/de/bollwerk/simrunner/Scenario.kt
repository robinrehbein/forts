package de.bollwerk.simrunner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Szenario-Datei (JSON). Beispiel: `tools/simrunner/scenarios/`.
 *
 * `commands` sind nach [ScriptStep.tick] geordnet auszuführende Schritte; Schritte mit gleichem Tick laufen in
 * Dateireihenfolge. Mit [ScriptStep.every] wiederholt sich ein Schritt (`count` mal).
 *
 * Aktionen (`action`):
 * - echte Commands (gehen durch `GameSession`/Regeln wie Spielereingaben):
 *   `aim` (`SetAim`), `fire` (`Fire`), `shoot` (`SetAim` + `Fire` im selben Tick),
 *   jeweils für das [ScriptStep.deviceIndex]-te lebende Gerät des Typs [ScriptStep.device] von [ScriptStep.player];
 *   `surrender`.
 * - Testbank-Eingriffe (direkt am `GameState`, deterministisch nach Tick, nicht Teil der Spiellogik):
 *   `ignite` (setzt `fire` auf passenden brennbaren Balken), `spawnProjectiles` (Projektile ohne Waffe),
 *   `addLattice` (Gitter aus Balken, unten am Gelände verankert).
 */
@Serializable
data class Scenario(
    val name: String = "unnamed",
    val description: String = "",
    val map: String = SimArgs.DEFAULT_MAP,
    val seed: Long = SimArgs.DEFAULT_SEED,
    val ticks: Long = SimArgs.DEFAULT_TICKS,
    /** Reiner Idle-Test: Bruch eines Balkens gilt als Fehler (zusätzlich zu `--assert-stable`). */
    val idle: Boolean = false,
    val commands: List<ScriptStep> = emptyList(),
)

@Serializable
data class ScriptStep(
    val tick: Long,
    val action: String,
    val player: Int = 0,
    /** Gerätetyp-Schlüssel (`mortar`, `cannon`, `mg` …). */
    val device: String = "mortar",
    val deviceIndex: Int = 0,
    /** Zielwinkel in Grad in Weltrichtung (0 = rechts, 90 = oben, 180 = links). */
    val angleDeg: Float = 45f,
    val power: Float = 1f,
    /** Wiederholung alle `every` Ticks (0 = einmalig), insgesamt `count` Ausführungen. */
    val every: Int = 0,
    val count: Int = 1,
    // ---- ignite ----
    val material: String = "wood",
    /** Anzahl zu entzündender Balken (ab dem `skip`-ten passenden Balken des Spielers). */
    val beams: Int = 1,
    /** Anzahl Projektile (spawnProjectiles). */
    val projectiles: Int = 1,
    val skip: Int = 0,
    val fire: Float = 1f,
    // ---- spawnProjectiles ----
    val weapon: String = "mortar",
    val incendiary: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val vx: Float = 0f,
    val vy: Float = 0f,
    /** Abstand der Startpunkte in x je Projektil (spawnProjectiles). */
    val dx: Float = 0f,
    /** Geschwindigkeitsstreuung je Projektil: `vx += k · dvx`. */
    val dvx: Float = 0f,
    val dvy: Float = 0f,
    // ---- addLattice ----
    val cols: Int = 0,
    val rows: Int = 0,
    /** Zellgröße in m; `x`/`y` = linke untere Ecke (y = Geländehöhe, unterste Knotenreihe verankert). */
    val cell: Float = 1f,
)

object ScenarioIo {
    val json: Json = Json { ignoreUnknownKeys = false; isLenient = false }

    private val ACTIONS = setOf("aim", "fire", "shoot", "surrender", "ignite", "spawnProjectiles", "addLattice")

    fun parse(text: String): Scenario {
        val s = json.decodeFromString(Scenario.serializer(), text)
        validate(s)
        return s
    }

    fun validate(s: Scenario) {
        require(s.ticks >= 0) { "scenario '${s.name}': ticks must be >= 0" }
        for ((i, c) in s.commands.withIndex()) {
            require(c.action in ACTIONS) { "scenario '${s.name}': command #$i has unknown action '${c.action}' (known: ${ACTIONS.sorted()})" }
            require(c.tick >= 0) { "scenario '${s.name}': command #$i has negative tick" }
            require(c.count >= 1 && c.every >= 0) { "scenario '${s.name}': command #$i needs count >= 1 and every >= 0" }
            require(c.count == 1 || c.every > 0) { "scenario '${s.name}': command #$i repeats (count > 1) but has every = 0" }
        }
    }

    /**
     * Lädt ein Szenario: Pfad, sonst `scenarios/<name>.json` bzw. `tools/simrunner/scenarios/<name>.json`
     * relativ zum Arbeitsverzeichnis.
     */
    fun load(ref: String): Scenario = parse(resolve(ref).readText())

    fun resolve(ref: String): File {
        val candidates = listOf(File(ref), File("$ref.json"), File("scenarios/$ref.json"), File("tools/simrunner/scenarios/$ref.json"))
        return candidates.firstOrNull { it.isFile } ?: throw IllegalArgumentException("scenario '$ref' not found (tried ${candidates.joinToString { it.path }})")
    }

    /** Alle Ausführungen aller Schritte (Wiederholungen aufgelöst), stabil nach Tick, dann Dateireihenfolge geordnet. */
    fun expand(s: Scenario, maxTick: Long = s.ticks): List<ScriptStep> {
        val out = ArrayList<ScriptStep>()
        for (c in s.commands) {
            for (k in 0 until c.count) {
                val t = c.tick + k.toLong() * c.every
                if (t > maxTick) break
                out.add(c.copy(tick = t, every = 0, count = 1))
            }
        }
        return out.sortedBy { it.tick } // sortedBy ist stabil
    }
}
