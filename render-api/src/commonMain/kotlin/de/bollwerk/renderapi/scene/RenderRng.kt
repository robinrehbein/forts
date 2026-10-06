package de.bollwerk.renderapi.scene

/**
 * Kleiner deterministischer Zufallsgenerator (Mulberry32, wie `mulberry` im Prototyp) für Darstellung:
 * Texturen, Partikel, Wolken, Risse. **Nie** im Sim-Pfad benutzen (der hat `SplitMix64`). Gleiche Saat
 * liefert auf jeder Plattform dieselbe Folge (nur Int-Arithmetik).
 */
class RenderRng(seed: Int = 1) {
    private var s: Int = seed

    fun reseed(seed: Int) { s = seed }

    fun nextInt(): Int {
        s += 0x6D2B79F5
        var t = (s xor (s ushr 15)) * (1 or s)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return t xor (t ushr 14)
    }

    /** Gleichverteilt in [0, 1). */
    fun next(): Float = (nextInt() ushr 8) * (1f / 16777216f)

    /** Gleichverteilt in [a, b). */
    fun range(a: Float, b: Float): Float = a + (b - a) * next()

    /** Ganzzahl in [0, n). */
    fun below(n: Int): Int = ((next() * n).toInt()).coerceIn(0, n - 1)
}
