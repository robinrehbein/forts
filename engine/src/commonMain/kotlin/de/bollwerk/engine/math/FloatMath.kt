package de.bollwerk.engine.math

/** Kleine deterministische Float-Helfer (nur +, −, ×, ÷ und Vergleiche). */
object FloatMath {
    const val PI: Float = 3.1415927f
    const val TWO_PI: Float = 6.2831855f
    const val HALF_PI: Float = 1.5707964f
    const val DEG_TO_RAD: Float = PI / 180f
    const val RAD_TO_DEG: Float = 180f / PI

    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
    fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun abs(v: Float): Float = if (v < 0f) -v else v
    fun sign(v: Float): Float = if (v > 0f) 1f else if (v < 0f) -1f else 0f
    fun min(a: Float, b: Float): Float = if (a < b) a else b
    fun max(a: Float, b: Float): Float = if (a > b) a else b

    /** Hermite-Glättung wie `smooth()` im Prototyp. */
    fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = clamp((x - a) / (b - a), 0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
