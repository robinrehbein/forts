package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.MapSpec

/**
 * Aus der Karte abgeleitete, statische Darstellungsdaten (einmal in `bind` berechnet): Geländehöhen,
 * Kluft/Senke (für Nebel und Felsnadeln), Sterne, Wolken.
 */
internal class WorldInfo(val map: MapSpec) {
    /** Höchste Geländelinie (kleinstes y) und tiefste. */
    val minH: Float
    val maxH: Float
    /** Plateau-Höhe für Horizont, Schichten (Mittel der Spieler-Bodenhöhen). */
    val groundY: Float
    /** Breiteste zusammenhängende Senke (Gelände ≥ [VALLEY_DEPTH] m unter dem Plateau); sonst `hasValley == false`. */
    var hasValley = false
    var valleyX0 = 0f
    var valleyX1 = 0f

    // Sterne (nur obere Bildschirmdrittel), normalisiert 0..1
    val starX = FloatArray(STARS)
    val starY = FloatArray(STARS)
    val starS = FloatArray(STARS)
    val starA = FloatArray(STARS)

    // Wolken
    val cloudX = FloatArray(CLOUDS)
    val cloudY = FloatArray(CLOUDS)
    val cloudK = IntArray(CLOUDS)
    val cloudS = FloatArray(CLOUDS)
    val cloudA = FloatArray(CLOUDS)

    // Felsnadeln in der Kluft
    val spireUp = FloatArray(SPIRES)
    val spireW = FloatArray(SPIRES)

    val mapHash: Int

    init {
        val t = map.terrain
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (h in t.heights) { if (h < lo) lo = h; if (h > hi) hi = h }
        minH = lo; maxH = hi
        var g = 0f
        val n = minOf(map.playerCount, map.baseY.size)
        for (i in 0 until n) g += map.baseY[i]
        groundY = if (n > 0) g / n else lo

        // Senke: zusammenhängender Lauf mit Höhe > groundY + VALLEY_DEPTH
        var bestW = 0f
        var runStart = -1
        val hs = t.heights
        for (i in 0..hs.size) {
            val inside = i < hs.size && hs[i] > groundY + VALLEY_DEPTH
            if (inside && runStart < 0) runStart = i
            if (!inside && runStart >= 0) {
                val w = (i - runStart) * t.step
                if (w > bestW) { bestW = w; valleyX0 = t.x0 + runStart * t.step; valleyX1 = t.x0 + (i - 1) * t.step; hasValley = true }
                runStart = -1
            }
        }

        val rs = RenderRng(5)
        for (i in 0 until STARS) {
            starX[i] = rs.next(); starY[i] = rs.next()
            val q = rs.next()
            starS[i] = 0.5f + q * rs.next() * 1.4f
            starA[i] = 0.35f + rs.next() * 0.6f
        }
        for (i in 0 until CLOUDS) {
            cloudX[i] = (i * 0.137f + (i * 7919 % 13) * 0.05f) % 1.2f
            cloudY[i] = 0.07f + ((i * 37) % 11) / 11f * 0.3f
            cloudK[i] = i % 4
            cloudS[i] = 0.55f + ((i * 13) % 7) / 10f
            cloudA[i] = 0.5f + ((i * 5) % 4) * 0.1f
        }
        val rr = RenderRng(23)
        for (i in 0 until SPIRES) {
            spireUp[i] = rr.range(-3.5f, 3.4f)
            spireW[i] = rr.range(1.8f, 3.4f)
        }
        // Schlüssel der statischen Ebenen: Karte samt Höhenprofil, Schichtbasis und Erz (gleiche Id + Breite mit
        // anderem Gelände, z. B. prozedurale/Custom-Karten oder Content-Änderungen, darf kein altes Bitmap treffen).
        var h = map.id.hashCode()
        h = h * 31 + map.width.toRawBits()
        h = h * 31 + map.height.toRawBits()
        h = h * 31 + t.x0.toRawBits()
        h = h * 31 + t.step.toRawBits()
        h = h * 31 + hs.size
        for (v in hs) h = h * 31 + v.toRawBits()
        for (o in map.ores) { h = h * 31 + o.owner; h = h * 31 + o.x.toRawBits() }
        for (v in map.baseY) h = h * 31 + v.toRawBits()
        mapHash = h
    }

    companion object {
        const val STARS = 130
        const val CLOUDS = 9
        const val SPIRES = 8
        const val VALLEY_DEPTH = 3f
    }
}
