package de.bollwerk.engine.sim

import kotlin.math.floor

/**
 * Gelände als gleichmäßig abgetastetes Höhenprofil (wie `terrH` im Prototyp, Schritt 0,5 m).
 * y wächst nach unten: größere Höhe-Werte liegen tiefer.
 */
class Terrain(
    /** x der ersten Stützstelle in m. */
    val x0: Float,
    /** Abstand der Stützstellen in m. */
    val step: Float,
    /** Oberflächen-y je Stützstelle. */
    val heights: FloatArray,
) {
    init { require(heights.size >= 2) { "terrain needs at least 2 samples" } }

    /** Oberflächen-y bei [x] (linear interpoliert, außerhalb geklemmt). */
    fun heightAt(x: Float): Float {
        val n = heights.size
        var f = (x - x0) / step
        if (f < 0f) f = 0f
        val max = (n - 1).toFloat() - 0.001f
        if (f > max) f = max
        val fl = floor(f)
        val i = fl.toInt()
        val t = f - fl
        return heights[i] + (heights[i + 1] - heights[i]) * t
    }

    companion object {
        /** Flaches Gelände in Höhe [y] über [x0, x1]. */
        fun flat(x0: Float, x1: Float, y: Float, step: Float = 0.5f): Terrain {
            val n = ((x1 - x0) / step).toInt() + 1
            return Terrain(x0, step, FloatArray(if (n < 2) 2 else n) { y })
        }

        /** Tastet einen Polygonzug (x aufsteigend) mit Schritt [step] ab. */
        fun fromPolyline(xs: FloatArray, ys: FloatArray, step: Float = 0.5f): Terrain {
            require(xs.size == ys.size && xs.size >= 2) { "polyline needs >= 2 points" }
            val x0 = xs[0]
            val n = ((xs[xs.size - 1] - x0) / step).toInt() + 1
            var seg = 0
            val h = FloatArray(if (n < 2) 2 else n) { i ->
                val x = x0 + i * step
                while (seg < xs.size - 2 && x > xs[seg + 1]) seg++
                val dx = xs[seg + 1] - xs[seg]
                val t = if (dx <= 0f) 0f else ((x - xs[seg]) / dx).coerceIn(0f, 1f)
                ys[seg] + (ys[seg + 1] - ys[seg]) * t
            }
            return Terrain(x0, step, h)
        }
    }
}
