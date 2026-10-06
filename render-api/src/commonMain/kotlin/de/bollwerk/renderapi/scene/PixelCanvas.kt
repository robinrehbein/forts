package de.bollwerk.renderapi.scene

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Minimaler Software-Rasterizer für prozedurale Texturen (reines Kotlin, deterministisch, nur +, −, ×, ÷
 * und `sqrt`). Farben ARGB (nicht vormultipliziert), Quellüberlagerung "source-over". Koordinaten in Pixeln,
 * Pixelmitte bei (i + 0,5). Kanten sind per Abdeckung geglättet.
 */
class PixelCanvas(val width: Int, val height: Int) {
    val pixels = IntArray(width * height)

    /** Überlagert Pixel [i] mit [color] bei Abdeckung [cov]. */
    fun over(i: Int, color: Int, cov: Float) {
        val sa = (color ushr 24) * cov
        if (sa < 0.5f) return
        val d = pixels[i]
        val da = d ushr 24
        if (da == 0) {
            pixels[i] = ((sa + 0.5f).toInt().coerceAtMost(255) shl 24) or (color and 0xFFFFFF)
            return
        }
        val a = sa / 255f
        val ia = da / 255f * (1f - a)
        val oa = a + ia
        val r = (((color shr 16) and 0xFF) * a + ((d shr 16) and 0xFF) * ia) / oa
        val g = (((color shr 8) and 0xFF) * a + ((d shr 8) and 0xFF) * ia) / oa
        val b = ((color and 0xFF) * a + (d and 0xFF) * ia) / oa
        pixels[i] = ((oa * 255f + 0.5f).toInt().coerceAtMost(255) shl 24) or
            ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or (b + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * Füllt die Pixel im Rechteck [x0,x1)×[y0,y1) (geklemmt): Abdeckung aus [cover] (Pixelmitte), Farbe aus
     * [paint]; `inline`, damit beim Erzeugen keine Lambda-Objekte entstehen.
     */
    inline fun shape(x0: Float, y0: Float, x1: Float, y1: Float, cover: (Float, Float) -> Float, paint: (Float, Float) -> Int) {
        val ix0 = floor(x0).toInt().coerceAtLeast(0)
        val iy0 = floor(y0).toInt().coerceAtLeast(0)
        val ix1 = ceil(x1).toInt().coerceAtMost(width)
        val iy1 = ceil(y1).toInt().coerceAtMost(height)
        for (iy in iy0 until iy1) {
            val py = iy + 0.5f
            for (ix in ix0 until ix1) {
                val px = ix + 0.5f
                val c = cover(px, py)
                if (c > 0f) over(iy * width + ix, paint(px, py), if (c > 1f) 1f else c)
            }
        }
    }

    // ---- Primitive ----

    /** Achsenparalleles Rechteck mit exakter Randabdeckung. */
    fun rect(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        shape(x0, y0, x1, y1, { px, py -> overlap(px, x0, x1) * overlap(py, y0, y1) }, { _, _ -> color })
    }

    /** Rechteck mit senkrechtem Verlauf (Farben gleichmäßig verteilt von [y0] bis [y1]). */
    fun rectV(x0: Float, y0: Float, x1: Float, y1: Float, vararg colors: Int) {
        val h = y1 - y0
        shape(x0, y0, x1, y1, { px, py -> overlap(px, x0, x1) * overlap(py, y0, y1) }, { _, py -> ramp(colors, (py - y0) / h) })
    }

    /** Rechteck mit waagerechtem Verlauf. */
    fun rectH(x0: Float, y0: Float, x1: Float, y1: Float, vararg colors: Int) {
        val w = x1 - x0
        shape(x0, y0, x1, y1, { px, py -> overlap(px, x0, x1) * overlap(py, y0, y1) }, { px, _ -> ramp(colors, (px - x0) / w) })
    }

    /** Gefüllte Scheibe. */
    fun disc(cx: Float, cy: Float, r: Float, color: Int) {
        shape(cx - r - 1f, cy - r - 1f, cx + r + 1f, cy + r + 1f, { px, py -> r - dist(px - cx, py - cy) + 0.5f }, { _, _ -> color })
    }

    /** Scheibe mit Kugel-Verlauf: Farbe von [c0] im Lichtpunkt (cx+fx, cy+fy) nach [c1] am Rand. */
    fun sphere(cx: Float, cy: Float, r: Float, fx: Float, fy: Float, c0: Int, c1: Int) {
        shape(cx - r - 1f, cy - r - 1f, cx + r + 1f, cy + r + 1f, { px, py -> r - dist(px - cx, py - cy) + 0.5f },
            { px, py -> lerpColor(c0, c1, dist(px - cx - fx, py - cy - fy) / (r + dist(fx, fy))) })
    }

    /** Radialer Verlauf (Farben von innen nach außen) in einer Scheibe ohne Randglättung (letzte Farbe = Rand). */
    fun radial(cx: Float, cy: Float, r: Float, vararg colors: Int) {
        shape(cx - r, cy - r, cx + r, cy + r, { px, py -> r - dist(px - cx, py - cy) + 0.5f }, { px, py -> ramp(colors, dist(px - cx, py - cy) / r) })
    }

    /** Ring der Breite [w]. */
    fun ring(cx: Float, cy: Float, r: Float, w: Float, color: Int) {
        shape(cx - r - w, cy - r - w, cx + r + w, cy + r + w, { px, py -> w * 0.5f - abs(dist(px - cx, py - cy) - r) + 0.5f }, { _, _ -> color })
    }

    /** Gefüllte Ellipse (Randabstand näherungsweise). */
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        val m = if (rx < ry) rx else ry
        shape(cx - rx - 1f, cy - ry - 1f, cx + rx + 1f, cy + ry + 1f,
            { px, py -> (1f - dist((px - cx) / rx, (py - cy) / ry)) * m + 0.5f }, { _, _ -> color })
    }

    /** Ellipse mit Verlauf von innen ([c0]) nach außen ([c1]). */
    fun ellipseGrad(cx: Float, cy: Float, rx: Float, ry: Float, c0: Int, c1: Int) {
        val m = if (rx < ry) rx else ry
        shape(cx - rx - 1f, cy - ry - 1f, cx + rx + 1f, cy + ry + 1f,
            { px, py -> (1f - dist((px - cx) / rx, (py - cy) / ry)) * m + 0.5f },
            { px, py -> lerpColor(c0, c1, dist((px - cx) / rx, (py - cy) / ry)) })
    }

    /** Elliptischer Ring. */
    fun ellipseRing(cx: Float, cy: Float, rx: Float, ry: Float, w: Float, color: Int) {
        val m = if (rx < ry) rx else ry
        shape(cx - rx - w, cy - ry - w, cx + rx + w, cy + ry + w,
            { px, py -> w * 0.5f - abs(1f - dist((px - cx) / rx, (py - cy) / ry)) * m + 0.5f }, { _, _ -> color })
    }

    /** Strecke mit runden Enden der Breite [w]. */
    fun segment(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
        val h = w * 0.5f
        val minX = (if (x0 < x1) x0 else x1) - h - 1f
        val maxX = (if (x0 < x1) x1 else x0) + h + 1f
        val minY = (if (y0 < y1) y0 else y1) - h - 1f
        val maxY = (if (y0 < y1) y1 else y0) + h + 1f
        val dx = x1 - x0
        val dy = y1 - y0
        val l2 = dx * dx + dy * dy
        shape(minX, minY, maxX, maxY, { px, py ->
            var t = if (l2 > 0f) ((px - x0) * dx + (py - y0) * dy) / l2 else 0f
            if (t < 0f) t = 0f else if (t > 1f) t = 1f
            h - dist(px - x0 - dx * t, py - y0 - dy * t) + 0.5f
        }, { _, _ -> color })
    }

    /** Abgerundetes Rechteck mit Verlauf entlang der Diagonalen von [c0] (oben links) nach [c1]. */
    fun roundRectDiag(x0: Float, y0: Float, x1: Float, y1: Float, r: Float, c0: Int, c1: Int) {
        val cx = (x0 + x1) * 0.5f
        val cy = (y0 + y1) * 0.5f
        val hx = (x1 - x0) * 0.5f - r
        val hy = (y1 - y0) * 0.5f - r
        val span = (x1 - x0) + (y1 - y0)
        shape(x0 - 1f, y0 - 1f, x1 + 1f, y1 + 1f, { px, py ->
            val qx = abs(px - cx) - hx
            val qy = abs(py - cy) - hy
            val ox = if (qx > 0f) qx else 0f
            val oy = if (qy > 0f) qy else 0f
            val inner = if (qx > qy) qx else qy
            r - (dist(ox, oy) + (if (inner < 0f) inner else 0f)) + 0.5f
        }, { px, py -> lerpColor(c0, c1, ((px - x0) + (py - y0)) / span) })
    }

    /** Regelmäßiges Sechseck (Spitze oben/unten) mit Verlauf. */
    fun hexagon(cx: Float, cy: Float, r: Float, color: Int, color2: Int = color) {
        val ap = r * 0.8660254f // Inkreis
        shape(cx - r - 1f, cy - r - 1f, cx + r + 1f, cy + r + 1f, { px, py ->
            val dx = abs(px - cx)
            val dy = abs(py - cy)
            // Abstand zu den drei Kantenpaaren des Sechsecks mit Spitze oben/unten (wie im Prototyp)
            val d1 = dx
            val d2 = dy * 0.8660254f + dx * 0.5f
            val m = if (d1 > d2) d1 else d2
            ap - m + 0.5f
        }, { px, py -> lerpColor(color, color2, ((px - cx) + (py - cy)) / (2f * r) + 0.5f) })
    }

    // ---- Hilfen ----
    companion object {
        fun overlap(p: Float, a: Float, b: Float): Float {
            val lo = if (p - 0.5f > a) p - 0.5f else a
            val hi = if (p + 0.5f < b) p + 0.5f else b
            return if (hi > lo) hi - lo else 0f
        }

        fun dist(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

        /** Lineare Mischung zweier ARGB-Farben (kanalweise, inkl. Alpha). */
        fun lerpColor(a: Int, b: Int, t0: Float): Int {
            val t = if (t0 < 0f) 0f else if (t0 > 1f) 1f else t0
            val aa = (a ushr 24) + ((b ushr 24) - (a ushr 24)) * t
            val ar = ((a shr 16) and 0xFF) + (((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * t
            val ag = ((a shr 8) and 0xFF) + (((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * t
            val ab = (a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * t
            return ((aa + 0.5f).toInt() shl 24) or ((ar + 0.5f).toInt() shl 16) or ((ag + 0.5f).toInt() shl 8) or (ab + 0.5f).toInt()
        }

        /** Farbverlauf über gleichmäßig verteilte Stützfarben, [t] 0..1. */
        fun ramp(colors: IntArray, t0: Float): Int {
            val n = colors.size
            if (n == 1) return colors[0]
            val t = if (t0 < 0f) 0f else if (t0 > 1f) 1f else t0
            val f = t * (n - 1)
            var i = f.toInt()
            if (i >= n - 1) i = n - 2
            return lerpColor(colors[i], colors[i + 1], f - i)
        }

        /** ARGB aus 0xRRGGBB und Alpha 0..1. */
        fun argb(rgb: Int, alpha: Float): Int = (rgb and 0xFFFFFF) or ((alpha * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24)
    }
}
