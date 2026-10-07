package de.bollwerk.app.game

import de.bollwerk.ai.AiAgent
import de.bollwerk.ai.AiFactory
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.tutorial.TutorialDriver
import de.bollwerk.app.tutorial.TutorialGate
import de.bollwerk.content.ContentDb
import de.bollwerk.engine.loop.MatchRunner
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.setup.MatchRunners

/**
 * Eine angelegte Partie: [runner] (Session, Loop, Snapshot-Austausch, lokale Eingabe), statische Partiedaten für den
 * Renderer ([tables], [map]), die KI-Gegner und der Content-Katalog der HUD-Schicht. Entsteht ausschließlich über
 * [MatchSessions.create] (`MatchRunners` → `MatchBootstrap`, CLAUDE.md: Partie nur über `MatchBootstrap`).
 */
class MatchSession(
    val config: MatchConfig,
    val runner: MatchRunner,
    val catalog: GameCatalog,
    val ais: List<AiAgent>,
    /** Spieler-IDs der Menschen am Gerät (gegen KI einer, im Hotseat beide). */
    val humanPlayers: IntArray,
    /** Sim-seitiger Teil des Tutorials (nur beim geführten Tutorial-Gefecht, sonst `null`). */
    val tutorial: TutorialDriver? = null,
) {
    val tables: SimTables get() = runner.state.tables
    val map: MapSpec get() = runner.state.map
    val hotseat: Boolean get() = config.mode == GameMode.HOTSEAT

    fun isHuman(playerId: Int): Boolean {
        for (p in humanPlayers) if (p == playerId) return true
        return false
    }
}

/** Fabrik der Partien aus [MatchConfig] (Gefecht-Setup, Hotseat, Revanche, Neustart). */
object MatchSessions {
    /**
     * Baut die Partie: Runner über [MatchRunners] (→ `MatchBootstrap`, lokale Eingabe als erste Quelle), danach die
     * KI-Agenten über [AiFactory] mit **denselben** Tabellen wie die Session (`runner.state.tables`, identische Indizes,
     * kein zweites `SimTablesFactory.build`), als Quellen hinter der lokalen Eingabe angehängt (dieselbe Reihenfolge wie
     * `MatchRunner(sources = …)`).
     *
     * Ohne Aufnahme (`record = false`): Die App speichert (noch) keine Replays; eine Aufnahme hielte die Command-Liste
     * unbegrenzt im Speicher und rechnete alle 60 Ticks einen vollen StateHash auf dem Sim-Thread. Kommt das Speichern von
     * Replays, wird hier [record] eingeschaltet.
     *
     * Teuer (Content-Auflösung, Einschwingen der Festungen): nicht auf dem Main-Thread aufrufen.
     */
    fun create(
        db: ContentDb,
        config: MatchConfig,
        simConfig: SimConfig = SimConfig.DEFAULT,
        record: Boolean = false,
    ): MatchSession {
        val setup = config.toMatchSetup()
        // Tutorial: konstanter, schwacher Wind (Seed-Wahl: `MatchConfig.TUTORIAL_SEED`), damit die ersten Schüsse berechenbar bleiben
        val sim = if (config.tutorial) simConfig.copy(wind = simConfig.wind.copy(changeIntervalTicks = 0)) else simConfig
        val runner = MatchRunners.create(
            db = db,
            setup = setup,
            config = sim,
            localPlayer = config.humanPlayerId,
            record = record,
            maxTicksPerAdvance = MAX_TICKS_PER_ADVANCE,
        )
        val tables = runner.state.tables
        val ais = ArrayList<AiAgent>()
        val humans = ArrayList<Int>()
        // Tutorial: die KI ruht hinter einer Sperre, bis der Mörser gefeuert hat (TutorialDriver.openGates)
        val gates = ArrayList<TutorialGate>()
        for ((id, p) in setup.players.withIndex()) {
            if (p.controller == Controller.AI) {
                val ai = AiFactory.create(id, config.aiLevel.difficulty, tables, seed = setup.seed)
                if (config.tutorial) runner.session.addSource(TutorialGate(ai).also { gates.add(it) }) else runner.session.addSource(ai)
                ais.add(ai)
            } else {
                humans.add(id)
            }
        }
        val tutorial = if (config.tutorial) TutorialDriver.attach(runner, db, config, gates) else null
        return MatchSession(config, runner, GameCatalog.from(db), ais, humans.toIntArray(), tutorial)
    }

    /** Aufholgrenze je Frame (5 Ticks ≈ 83 ms); größere Rückstände werden verworfen statt nachgeholt. */
    const val MAX_TICKS_PER_ADVANCE: Int = 5
}
