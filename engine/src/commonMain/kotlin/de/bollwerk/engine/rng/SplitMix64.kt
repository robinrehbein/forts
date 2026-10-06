package de.bollwerk.engine.rng

/**
 * Deterministischer Zufallsgenerator (SplitMix64, Steele/Lea/Flood 2014). Einzige erlaubte
 * Zufallsquelle im Sim-Pfad (Determinismus-Regel 2). Der Zustand ist ein einzelnes [Long] und
 * damit trivial hash- und serialisierbar.
 */
class SplitMix64(seed: Long) {
    /** Aktueller interner Zustand (geht in den StateHash ein). */
    var state: Long = seed
        private set

    /** Nächster 64-Bit-Wert. */
    fun nextLong(): Long {
        state += GOLDEN
        return mix(state)
    }

    /** Gleichverteilt in [0, bound); [bound] muss > 0 sein. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive: $bound" }
        // Multiply-high auf 32 Bit: deterministisch, Verzerrung < 2^-32
        val r = nextLong() ushr 32
        return ((r * bound.toLong()) ushr 32).toInt()
    }

    /** Gleichverteilt in [from, until). */
    fun nextInt(from: Int, until: Int): Int = from + nextInt(until - from)

    /** Gleichverteilt in [0, 1) mit 24 Bit Auflösung. */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() * FLOAT_UNIT

    /** Gleichverteilt in [from, until). */
    fun nextFloat(from: Float, until: Float): Float = from + (until - from) * nextFloat()

    /** `true` mit Wahrscheinlichkeit [p]. */
    fun chance(p: Float): Boolean = nextFloat() < p

    /**
     * Unabhängiger Unterstrom (z. B. KI pro Spieler, Feuer, Trümmer). Verändert den Elternzustand **nicht**,
     * gleiche (Zustand, [streamId]) liefern immer denselben Unterstrom.
     */
    fun derive(streamId: Long): SplitMix64 = SplitMix64(mix(state xor mix(streamId * GOLDEN + STREAM_SALT)))

    /** Kopie mit identischem Zustand. */
    fun copy(): SplitMix64 = SplitMix64(state)

    /** Setzt den Zustand (für Snapshots/Rollback). */
    fun restore(state: Long) { this.state = state }

    companion object {
        private val GOLDEN: Long = 0x9E3779B97F4A7C15uL.toLong()
        private val M1: Long = 0xBF58476D1CE4E5B9uL.toLong()
        private val M2: Long = 0x94D049BB133111EBuL.toLong()
        private const val STREAM_SALT: Long = 0x5EED_B011_7E4BL
        private const val FLOAT_UNIT: Float = 1f / 16777216f

        /** SplitMix64-Finalizer (auch als Hash-Mischer nutzbar). */
        fun mix(z0: Long): Long {
            var z = z0
            z = (z xor (z ushr 30)) * M1
            z = (z xor (z ushr 27)) * M2
            return z xor (z ushr 31)
        }
    }
}
