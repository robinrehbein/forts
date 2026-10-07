package de.bollwerk.engine.golden

import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.loop.DuelBot
import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.loop.LoopRig
import de.bollwerk.engine.loop.MatchRunner
import de.bollwerk.engine.loop.Replay
import de.bollwerk.engine.loop.TurnBot
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.SimTables
import java.io.File

/**
 * Die drei Golden-Replays. Jedes Szenario besteht aus Setup, Tabellen und **Skript-Spielern** ([DuelBot], [TurnBot]), die
 * nur aus dem Zustand lesen. [record] spielt das Skript einmal durch und liefert das Replay (angenommene Commands +
 * Hash-Prüfpunkte); die eingecheckten Dateien unter `src/jvmTest/resources/replays` sind genau diese Aufnahmen.
 *
 * **Regenerieren** (nur, wenn sich das Sim-Verhalten absichtlich geändert hat; dann `GameInfo.ENGINE_VERSION` erhöhen):
 * ```
 * GOLDEN_REGEN=1 ./gradlew :engine:jvmTest --tests '*GoldenReplayTest*' --rerun
 * git diff engine/src/jvmTest/resources/replays      # Änderung prüfen und mit Begründung committen
 * ```
 * (alternativ `-Dbollwerk.golden.regen=true` an die JVM, falls der Test-Task es durchreicht). Ohne die Variable
 * schlägt der Test mit dem ersten abweichenden Tick fehl, statt Dateien zu überschreiben.
 */
class GoldenScenario(
    val name: String,
    val description: String,
    val setup: MatchSetup,
    val tables: SimTables,
    val ticks: Long,
    val sources: () -> List<CommandSource>,
) {
    val fileName: String get() = "$name.replay.json"

    /** Frische Session für ein Replay (gleicher Anfangszustand wie bei der Aufnahme). */
    fun session(replay: Replay): GameSession = LoopRig.session(replay.setup, tables, config = replay.config)

    /** Skript durchspielen und aufzeichnen. */
    fun record(): Replay {
        val runner = MatchRunner(
            LoopRig.session(setup, tables), replayMeta = LoopRig.meta(setup, tables), sources = sources(),
            hotseat = setup.turnMode == de.bollwerk.engine.sim.TurnMode.TURNS,
        )
        runner.runTicks(ticks)
        return runner.toReplay()!!
    }

    /** Verzeichnis der Dateien im Quellbaum (Arbeitsverzeichnis des Test-Tasks = Modulverzeichnis `engine`). */
    fun sourceFile(): File = File("src/jvmTest/resources/replays/$fileName")
}

object GoldenScenarios {
    /** Mörser-Duell: beide bauen Mine, Turbine, Werkstatt, dann Mörser; Spieler 0 schießt zuerst und gewinnt. */
    val buildFireDuel = GoldenScenario(
        name = "build_fire_duel",
        description = "Both players build mine, turbine, workshop, mortar and shell each other's reactor; player 0 wins.",
        setup = LoopRig.setup(seed = 11L),
        tables = RuleTables.tables,
        ticks = 3600L,
        sources = { listOf(DuelBot(0), DuelBot(1, fireFrom = 3000L)) },
    )

    /** Brandmörser auf die Holzfestung von Spieler 1; das Feuer breitet sich über gemeinsame Knoten aus und zerstört sie. */
    val fireSpread = GoldenScenario(
        name = "fire_spread",
        description = "Player 0 shells the wooden fort of player 1 with an incendiary mortar; fire spreads along shared nodes and burns the fort down.",
        setup = LoopRig.setup(seed = 23L),
        tables = incendiaryTables(),
        ticks = 5400L,
        sources = { listOf(DuelBot(0, target = 92f to 33.6f, power = 0.9f), DuelBot(1, fireFrom = Long.MAX_VALUE)) },
    )

    /** Hotseat: Zugwechsel mit EndTurn (Spieler 0) und Zeitablauf (Spieler 1), Aufbau über mehrere Züge, Schüsse. */
    val hotseatTurns = GoldenScenario(
        name = "hotseat_turns",
        description = "Hotseat turns: player 0 ends turns early, player 1 runs out the clock; build over several turns, then shots.",
        setup = LoopRig.setup(seed = 5L, turns = true, turnTicks = 300),
        tables = RuleTables.tables,
        ticks = 6000L,
        sources = { listOf(TurnBot(0, endEarly = true), TurnBot(1, endEarly = false)) },
    )

    val all: List<GoldenScenario> = listOf(buildFireDuel, fireSpread, hotseatTurns)

    /** Test-Tabellen mit Brandmörser (Zündradius 3 m) statt des normalen Mörsers. */
    fun incendiaryTables(): SimTables {
        val t = RuleTables.tables
        return t.copy(weapons = t.weapons.mapIndexed { i, w -> if (i == RuleTables.W_MORTAR) w.copy(igniteRadius = 3f) else w })
    }
}
