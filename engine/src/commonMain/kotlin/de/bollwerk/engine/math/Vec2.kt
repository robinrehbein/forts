package de.bollwerk.engine.math

import kotlin.math.sqrt

/**
 * Unveränderlicher 2D-Vektor (Meter, y zeigt nach **unten** wie im Prototyp und auf dem Bildschirm).
 *
 * Für API-Grenzen und Werkzeuge gedacht; die Simulation selbst arbeitet auf Structure-of-Arrays-Pools
 * und erzeugt im Hot-Path keine [Vec2]-Instanzen.
 */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2): Vec2 = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2): Vec2 = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float): Vec2 = Vec2(x * s, y * s)
    operator fun div(s: Float): Vec2 = Vec2(x / s, y / s)
    operator fun unaryMinus(): Vec2 = Vec2(-x, -y)

    /** Skalarprodukt. */
    infix fun dot(o: Vec2): Float = x * o.x + y * o.y

    /** z-Komponente des Kreuzprodukts (2D-"cross"). */
    infix fun cross(o: Vec2): Float = x * o.y - y * o.x

    /** Quadrierte Länge (ohne Wurzel). */
    val lengthSq: Float get() = x * x + y * y

    /** Länge (IEEE-`sqrt`, deterministisch). */
    val length: Float get() = sqrt(lengthSq)

    /** Normierter Vektor; der Nullvektor bleibt [ZERO]. */
    fun normalized(): Vec2 {
        val l = length
        return if (l < 1e-12f) ZERO else Vec2(x / l, y / l)
    }

    /** Linke Senkrechte (`-y, x`), wie die Balkennormale im Prototyp. */
    fun perp(): Vec2 = Vec2(-y, x)

    /** Abstand zu [o]. */
    fun distanceTo(o: Vec2): Float {
        val dx = o.x - x
        val dy = o.y - y
        return sqrt(dx * dx + dy * dy)
    }

    /** Lineare Interpolation zu [o] mit Parameter [t]. */
    fun lerp(o: Vec2, t: Float): Vec2 = Vec2(x + (o.x - x) * t, y + (o.y - y) * t)

    companion object {
        val ZERO: Vec2 = Vec2(0f, 0f)
    }
}
