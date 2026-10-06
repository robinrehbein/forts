package de.bollwerk.ai

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.CommandSource
import de.bollwerk.engine.view.GameView

/**
 * Schwierigkeitsgrad der KI.
 * @property aimErrorDeg Standardabweichung des Zielfehlers in Grad.
 * @property thinkIntervalTicks Ticks zwischen zwei Entscheidungen.
 */
enum class Difficulty(val aimErrorDeg: Float, val thinkIntervalTicks: Int) {
    EASY(aimErrorDeg = 6f, thinkIntervalTicks = 90),
    NORMAL(aimErrorDeg = 3f, thinkIntervalTicks = 45),
    HARD(aimErrorDeg = 1f, thinkIntervalTicks = 20),
}

/**
 * Gegner-KI. Sie ist eine [CommandSource] wie jeder Spieler: Sie liest den Zustand nur über
 * [GameView] (inkl. `view.tables`, `view.map`, `Ballistics`, `DeviceGeometry`) und handelt ausschließlich über
 * Commands mit stabilen Refs. Zufall nur über einen eigenen Strom
 * `SplitMix64(seed).derive(RngStreams.ai(playerId))`, damit KI-Partien deterministisch bleiben und die
 * Sim-Ströme nicht verschieben. Läuft auf dem Sim-Thread.
 */
interface AiAgent : CommandSource {
    /** Spieler, den die KI steuert. */
    val playerId: Int
    val difficulty: Difficulty
}

/** WP0-Platzhalter: tut nichts. */
class IdleAi(
    override val playerId: Int,
    override val difficulty: Difficulty = Difficulty.NORMAL,
) : AiAgent {
    override fun commandsFor(tick: Long, view: GameView): List<Command> = emptyList()
}
