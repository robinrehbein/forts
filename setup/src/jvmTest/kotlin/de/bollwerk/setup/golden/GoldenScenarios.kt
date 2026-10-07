package de.bollwerk.setup.golden

import de.bollwerk.content.ContentDb
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.setup.MatchRunners

/**
 * Ein aufgezeichnetes Spielszenario mit echtem Content: [record] spielt [sources] über [MatchRunners.create] (mit
 * `record = true`) bis zum Spielende ([maxTicks] als Obergrenze) plus [tailTicks] danach und liefert das [Replay].
 */
class GoldenScenario(
    val name: String,
    val setup: MatchSetup,
    val maxTicks: Long,
    val tailTicks: Long,
    val sources: (ContentDb) -> List<CommandSource>,
) {
    /** Fortschritt des Skripts der letzten Aufnahme (für Fehlermeldungen, wenn ein Skript hängen bleibt). */
    var lastStatus: String = ""
        private set

    fun record(db: ContentDb): Replay {
        val srcs = sources(db)
        val runner = MatchRunners.create(db, setup, sources = srcs, record = true)
        runner.runTicks(maxTicks, stopOnResult = true)
        // der Nachlauf nach dem Ergebnis (Einsturz, Trümmer, Feuer) gehört mit zum Golden
        if (runner.finished) runner.runTicks(tailTicks)
        lastStatus = srcs.filterIsInstance<AllCommandsScript>().joinToString { it.status() } + " ticks=${runner.tick} result=${runner.result}"
        return runner.toReplay()!!
    }
}

object GoldenScenarios {
    private val humans = listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN))

    /**
     * Schlucht, Echtzeit: alle Befehlsarten außer `EndTurn` und alle sechs Waffen, Aufbau über die echten Regeln
     * (Waffenkammer, Upgrade-Zentrum, Fabrik mit Bauzeit), am Ende die Aufgabe von Spieler 0.
     */
    val allCommands = GoldenScenario(
        name = "schlucht_all_commands",
        setup = MatchSetup(seed = 31L, mapId = "schlucht", players = humans),
        maxTicks = 20_000L,
        tailTicks = 120L,
        sources = { db -> listOf(AllCommandsScript(db, me = 0), RaiderScript(db, me = 1)) },
    )

    /**
     * Schlucht, Zugmodus wie im ausgelieferten Hotseat (Standard-Zuglänge 45 s, [MatchRunners.hotseatSetup]): `EndTurn`,
     * Zugwechsel mit Auflösung und Übergabe, Aufgabe im vierten Zug.
     */
    val hotseat = GoldenScenario(
        name = "schlucht_hotseat_endturn",
        setup = MatchRunners.hotseatSetup(seed = 7L, mapId = "schlucht"),
        maxTicks = 20_000L,
        tailTicks = 120L,
        sources = { db -> listOf(HotseatScript(db)) },
    )

    val all: List<GoldenScenario> = listOf(allCommands, hotseat)
}
