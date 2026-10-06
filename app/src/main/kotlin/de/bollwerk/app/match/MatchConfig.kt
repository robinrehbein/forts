package de.bollwerk.app.match

import de.bollwerk.ai.Difficulty
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.TurnMode

/** Spielmodus: Einzelspieler gegen KI oder zwei Spieler an einem Gerät. */
enum class GameMode { VS_AI, HOTSEAT }

/** KI-Stärke im Setup (Mockup 2); [difficulty] ist der Vertrag zu `:ai`. */
enum class AiLevel(val difficulty: Difficulty) {
    EASY(Difficulty.EASY),
    NORMAL(Difficulty.NORMAL),
    HARD(Difficulty.HARD),
}

/** Startressourcen: Normal = Stil-Bibel §1 (400 ⚙ / 200 ⚡). */
enum class StartResources(val metal: Int, val energy: Int) {
    SCARCE(250, 100),
    NORMAL(400, 200),
    RICH(700, 350),
}

/** Teamfarbe des Menschen im Spiel gegen KI; Blau = Spieler 1 (ID 0), Rot = Spieler 2 (ID 1). */
enum class TeamColor(val playerId: Int) {
    BLUE(0),
    RED(1);

    val opponent: TeamColor get() = if (this == BLUE) RED else BLUE
}

/** Wählbare Karten; [id] ist die Karten-ID aus `:content` (Verzeichnis maps). */
enum class MapOption(val id: String, val widthMeters: Int, val strongWind: Boolean, val depthMeters: Int) {
    SCHLUCHT("schlucht", widthMeters = 120, strongWind = false, depthMeters = 18),
    HUEGEL("huegel", widthMeters = 160, strongWind = true, depthMeters = 8),
}

/**
 * Alles, was das Setup (oder die Revanche) an das Spiel übergibt. Reine App-Datenklasse;
 * [toMatchSetup] ist der einzige Weg zum Engine-Vertrag.
 */
data class MatchConfig(
    val map: MapOption = MapOption.SCHLUCHT,
    val mode: GameMode = GameMode.VS_AI,
    val aiLevel: AiLevel = AiLevel.NORMAL,
    val resources: StartResources = StartResources.NORMAL,
    val team: TeamColor = TeamColor.BLUE,
    val seed: Long = 1L,
) {
    /** Spieler-ID des Menschen (im Hotseat: Spieler 1, der als erster zieht). */
    val humanPlayerId: Int get() = if (mode == GameMode.VS_AI) team.playerId else 0

    /** Zuglänge im Hotseat (Mockup 6: „ZUG 45 S"). */
    val turnSeconds: Int get() = HOTSEAT_TURN_SECONDS

    fun toMatchSetup(): MatchSetup {
        val players = when (mode) {
            GameMode.HOTSEAT -> listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN))
            GameMode.VS_AI -> List(2) { id ->
                if (id == team.playerId) PlayerSetup(Controller.HUMAN)
                else PlayerSetup(Controller.AI, aiLevel.difficulty.name)
            }
        }
        return MatchSetup(
            seed = seed,
            mapId = map.id,
            players = players,
            turnMode = if (mode == GameMode.HOTSEAT) TurnMode.TURNS else TurnMode.REALTIME,
            turnTicks = if (mode == GameMode.HOTSEAT) HOTSEAT_TURN_SECONDS * TICKS_PER_SECOND else 0,
            startMetal = resources.metal.toFloat(),
            startEnergy = resources.energy.toFloat(),
        )
    }

    companion object {
        const val HOTSEAT_TURN_SECONDS = 45
        const val TICKS_PER_SECOND = 60
    }
}
