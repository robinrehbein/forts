package de.bollwerk.ai

import de.bollwerk.engine.rng.SplitMix64
import de.bollwerk.engine.sim.RngStreams

/**
 * Eigener Zufallsstrom der KI: `SplitMix64(seed).derive(RngStreams.ai(playerId))`. Unabhängig vom Sim-Zustand, damit
 * KI-Entscheidungen die Sim-Ströme nie verschieben (Determinismus-Regeln 2 und 8).
 */
class AiRng(seed: Long, playerId: Int) {
    private val rng: SplitMix64 = SplitMix64(seed).derive(RngStreams.ai(playerId))

    /** Interner Zustand (für Tests/Debug). */
    val state: Long get() = rng.state

    /** Gleichverteilt in [0, 1). */
    fun nextFloat(): Float = rng.nextFloat()

    /** Gleichverteilt in [0, bound). */
    fun nextInt(bound: Int): Int = rng.nextInt(bound)

    /** `true` mit Wahrscheinlichkeit [p]. */
    fun chance(p: Float): Boolean = if (p >= 1f) true else if (p <= 0f) false else rng.chance(p)

    /**
     * Näherungsweise standardnormalverteilt (Mittel 0, Standardabweichung 1): Irwin-Hall-Summe aus 4 Gleichverteilungen,
     * zentriert und auf Varianz 1 skaliert. Nur +, −, × (keine Bibliotheks-Transzendenten, Determinismus-Regel 3);
     * Werte liegen in ±2√3.
     */
    fun gaussian(): Float {
        val s = rng.nextFloat() + rng.nextFloat() + rng.nextFloat() + rng.nextFloat()
        return (s - 2f) * SQRT3
    }

    private companion object {
        const val SQRT3: Float = 1.7320508f
    }
}
