package de.bollwerk.ai

import de.bollwerk.engine.sim.BlueprintProps
import de.bollwerk.engine.sim.SimTables

/**
 * Einstieg für App, Simrunner und Tests: baut die Gegner-KI eines Spielers.
 * Die Bauvorlage kommt aus dem Content (`SimTables.blueprints`, Schlüssel `ai_easy`/`ai_normal`/`ai_hard`, siehe
 * [planKey]); ein Aufrufer (Setup, Tests) kann eine eigene Vorlage übergeben oder `null` für "nur kämpfen" (die KI
 * baut dann nichts und baut nichts wieder auf). Die Standard-Vorlage wird hier aufgelöst, nie in der KI selbst.
 */
object AiFactory {
    /**
     * @param playerId gesteuerter Spieler.
     * @param difficulty Stufe (Zielfehler, Denk-Intervall, Entscheidungsqualität).
     * @param tables Content-Tabellen der Partie (für die Standard-Bauvorlage).
     * @param blueprints Bauvorlage mit Bauschritten; Standard: [planFor] aus [tables]; `null` = nur kämpfen.
     * @param seed Partie-Seed; die KI zieht daraus ihren eigenen Strom `RngStreams.ai(playerId)`.
     */
    fun create(
        playerId: Int,
        difficulty: Difficulty,
        tables: SimTables,
        blueprints: BlueprintProps? = planFor(tables, difficulty),
        seed: Long,
    ): AiAgent = StandardAi(playerId, difficulty, seed, blueprints)

    /** Wie [create] mit der Standard-Bauvorlage der Stufe. */
    fun create(playerId: Int, difficulty: Difficulty, tables: SimTables, seed: Long): AiAgent =
        create(playerId, difficulty, tables, planFor(tables, difficulty), seed)

    /** Schlüssel der KI-Bauvorlage einer Stufe (`ai_normal` …). */
    fun planKey(difficulty: Difficulty): String = "ai_" + difficulty.name.lowercase()

    /** KI-Bauvorlage der Stufe, sonst irgendeine mit Tag `ai`, sonst null. */
    fun planFor(tables: SimTables, difficulty: Difficulty): BlueprintProps? {
        tables.blueprint(planKey(difficulty))?.let { return it }
        for (bp in tables.blueprints) if ("ai" in bp.tags && bp.steps.isNotEmpty()) return bp
        return null
    }
}
