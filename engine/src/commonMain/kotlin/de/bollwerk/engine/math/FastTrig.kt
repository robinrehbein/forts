package de.bollwerk.engine.math

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Deterministische Trigonometrie über Tabellen (Determinismus-Regel 3).
 *
 * Die Tabellen werden beim Laden **ohne** Plattform-`sin`/`atan` berechnet (Taylor-Reihen in Double,
 * nur +, −, ×, ÷ und `sqrt`), sind also auf JVM, Android und später iOS bitgleich.
 * Lookups interpolieren linear; Fehler: `sin`/`cos` < 5e-7, `atan2` < 2e-6 rad.
 */
object FastTrig {
    /** Einträge der Sinustabelle über eine volle Periode (Zweierpotenz). */
    const val SIN_SIZE: Int = 4096
    private const val SIN_MASK: Int = SIN_SIZE - 1
    private const val SIN_SCALE: Float = SIN_SIZE / FloatMath.TWO_PI

    /** Einträge der Arcustangens-Tabelle für Argumente in [0, 1]. */
    const val ATAN_SIZE: Int = 2048

    private val sinTable = FloatArray(SIN_SIZE + 1)
    private val atanTable = FloatArray(ATAN_SIZE + 2)

    init {
        val twoPi = 6.283185307179586
        for (i in 0..SIN_SIZE) sinTable[i] = taylorSin(twoPi * i / SIN_SIZE).toFloat()
        for (i in 0..ATAN_SIZE + 1) atanTable[i] = taylorAtan(i.toDouble() / ATAN_SIZE).toFloat()
    }

    /** Sinus von [rad] (Bogenmaß). */
    fun sin(rad: Float): Float {
        val f = rad * SIN_SCALE
        val fl = floor(f)
        val frac = f - fl
        val i = fl.toInt() and SIN_MASK
        val a = sinTable[i]
        return a + (sinTable[i + 1] - a) * frac
    }

    /** Kosinus von [rad] (Bogenmaß). */
    fun cos(rad: Float): Float = sin(rad + FloatMath.HALF_PI)

    /** Winkel von (x, y) in (−π, π], gleiche Konvention wie `kotlin.math.atan2(y, x)`. */
    fun atan2(y: Float, x: Float): Float {
        val ax = if (x < 0f) -x else x
        val ay = if (y < 0f) -y else y
        if (ax == 0f && ay == 0f) return 0f
        // Oktant-Reduktion: Argument immer in [0, 1]
        val r: Float
        val swap = ay > ax
        r = if (swap) ax / ay else ay / ax
        var a = atanUnit(r)
        if (swap) a = FloatMath.HALF_PI - a
        if (x < 0f) a = FloatMath.PI - a
        return if (y < 0f) -a else a
    }

    /** Arcustangens von [v] (beliebiger Wertebereich). */
    fun atan(v: Float): Float = atan2(v, 1f)

    private fun atanUnit(r: Float): Float {
        val f = r * ATAN_SIZE
        val i = f.toInt()
        val frac = f - i
        val a = atanTable[i]
        return a + (atanTable[i + 1] - a) * frac
    }

    // ---- Tabellenaufbau (nur Grundrechenarten, deterministisch) ----

    private fun taylorSin(x0: Double): Double {
        // Reduktion auf [-π, π]
        val pi = 3.141592653589793
        var x = x0
        while (x > pi) x -= 2 * pi
        while (x < -pi) x += 2 * pi
        var term = x
        var sum = x
        val x2 = x * x
        var n = 1
        while (n < 40) {
            term *= -x2 / ((2 * n) * (2 * n + 1))
            sum += term
            n++
        }
        return sum
    }

    private fun taylorAtan(v: Double): Double {
        // Halbierung des Winkels zweimal: atan(v) = 2·atan(v / (1 + sqrt(1 + v²)))
        var x = v
        var mult = 1.0
        repeat(2) {
            x /= (1.0 + sqrt(1.0 + x * x))
            mult *= 2.0
        }
        // |x| <= tan(π/16) ≈ 0.199 → schnelle Konvergenz
        val x2 = x * x
        var term = x
        var sum = x
        var k = 1
        while (k < 30) {
            term *= -x2
            sum += term / (2 * k + 1)
            k++
        }
        return sum * mult
    }
}
