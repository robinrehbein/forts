package de.bollwerk.engine.sim

import kotlinx.serialization.Serializable

/** Spielausgang. */
sealed class GameResult {
    /** Partie läuft. */
    data object Ongoing : GameResult()
    /** [playerId] hat gewonnen (gegnerischer Reaktor zerstört oder Aufgabe). */
    data class Winner(val playerId: Int, val reason: WinReason) : GameResult()
    /** Unentschieden (z. B. beide Reaktoren im selben Tick zerstört). */
    data object Draw : GameResult()
}

enum class WinReason { REACTOR_DESTROYED, SURRENDER, TIMEOUT }

/** Zugmodus. Echtzeit für V1-KI-Gefechte, Züge für Hotseat. */
@Serializable
enum class TurnMode { REALTIME, TURNS }

/** Phase im Zugmodus (Hotseat, Mockup 6). */
enum class TurnPhase {
    /** Aktiver Spieler plant/baut/zielt. */
    PLAY,
    /** Zug beendet, Simulation läuft aus (Projektile fliegen, Einsturz). */
    RESOLVE,
    /** Übergabe-Bildschirm: wartet auf den nächsten Spieler (kein Sim-Fortschritt außer Tick). */
    HANDOVER,
}

/** Lesende Sicht auf den Zugzustand. */
interface TurnView {
    val mode: TurnMode
    /** Aktiver Spieler (−1 = alle gleichzeitig). */
    val activePlayer: Int
    val turnNumber: Int
    /** Restliche Ticks der Phase (0 = unbegrenzt). */
    val ticksLeft: Int
    val phase: TurnPhase
}

/** Zuginformation (nur relevant bei [TurnMode.TURNS]); PERSISTENT, wird gehasht. */
class TurnState(
    override var mode: TurnMode = TurnMode.REALTIME,
    override var activePlayer: Int = -1,
    override var turnNumber: Int = 0,
    override var ticksLeft: Int = 0,
    override var phase: TurnPhase = TurnPhase.PLAY,
) : TurnView {
    /**
     * Länge eines Zugs in Ticks (0 = unbegrenzt), von `MatchFactory` aus `MatchSetup.turnTicks` gesetzt (WP3, additiv).
     * Konstant für die ganze Partie und Teil des Setups (steht im Replay), deshalb nicht im `StateHash`.
     */
    var lengthTicks: Int = 0
}
