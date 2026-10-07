package de.bollwerk.app.ui.game.hud

import de.bollwerk.app.game.TurnInfo
import de.bollwerk.engine.sim.TurnPhase

/**
 * Anzeigewerte des Zug-Chips im Hotseat („SPIELER 1 · ZUG 3 · 0:32"), rein und ohne Compose testbar.
 * [play] = Spielphase eines Spielers; sonst (Auflösung, Übergabe) zeigt der Chip „AUFLÖSUNG" ohne Teamfarbe.
 */
data class TurnChipModel(
    val play: Boolean,
    /** Nummer des Spielers am Zug, 1-basiert. */
    val playerNumber: Int,
    val turnNumber: Int,
    /** Restzeit als „m:ss". */
    val timeText: String,
    /** Letzte zehn Sekunden: Uhr in Warnfarbe. */
    val urgent: Boolean,
    /** Spieler-ID (0-basiert), dessen Teamfarbe den Chip rahmt. */
    val teamPlayer: Int,
)

/** Restzeit „m:ss" (negative Werte = 0:00). */
fun formatTurnTime(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val r = s % 60
    return "${s / 60}:${if (r < 10) "0$r" else "$r"}"
}

fun TurnInfo.toChipModel(): TurnChipModel = TurnChipModel(
    play = phase == TurnPhase.PLAY && activePlayer >= 0,
    playerNumber = activePlayer + 1,
    turnNumber = turnNumber,
    timeText = formatTurnTime(secondsLeft),
    urgent = phase == TurnPhase.PLAY && activePlayer >= 0 && secondsLeft <= 10,
    teamPlayer = activePlayer,
)
