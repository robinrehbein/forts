package de.bollwerk.app.match

import java.util.Locale

/** Warum die Partie endete (bestimmt die Unterzeile im Ergebnis-Screen). */
enum class EndReason { ENEMY_REACTOR_DESTROYED, OWN_REACTOR_DESTROYED, SURRENDER }

/** Kennzahlen des Gefechtsberichts (Mockup 7). */
data class MatchStats(
    val durationSeconds: Int = 0,
    val shots: Int = 0,
    val hits: Int = 0,
    val beamsBuilt: Int = 0,
    val beamsLost: Int = 0,
)

/**
 * Ergebnis einer Partie. [winnerPlayerId] ist die Spieler-ID des Siegers; im Spiel gegen KI
 * zeigt der Screen „SIEG", wenn der Mensch gewonnen hat, sonst „NIEDERLAGE"; im Hotseat immer „SIEG"
 * für den Gewinner.
 */
data class MatchResult(
    val config: MatchConfig,
    val winnerPlayerId: Int,
    val reason: EndReason,
    val stats: MatchStats = MatchStats(),
) {
    val isVictory: Boolean
        get() = config.mode == GameMode.HOTSEAT || winnerPlayerId == config.humanPlayerId

    /** Spieler, dessen Teamfarbe im Banner steht (Sieger im Hotseat, sonst der Mensch). */
    val bannerPlayerId: Int
        get() = if (config.mode == GameMode.HOTSEAT) winnerPlayerId else config.humanPlayerId

    companion object {
        fun surrender(config: MatchConfig, loserPlayerId: Int, stats: MatchStats = MatchStats()) =
            MatchResult(config, winnerPlayerId = 1 - loserPlayerId, reason = EndReason.SURRENDER, stats = stats)
    }
}

/** „mm:ss" (Mockup 7: 08:42); locale-unabhängig. */
fun formatDuration(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
}

/** Teamfarbe eines Spielers (ID 0 = Blau, ID 1 = Rot). */
fun teamOf(playerId: Int): TeamColor = if (playerId == 0) TeamColor.BLUE else TeamColor.RED
