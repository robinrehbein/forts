package de.bollwerk.renderandroid

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Reine Hilfsrechnungen der Canvas-Senke (ohne Android-Klassen, daher JVM-testbar und allokationsfrei).
 */
internal object DrawMath {
    const val RAD_TO_DEG: Float = 57.29578f

    /** Alpha 0..1 → 0..255 (gerundet, begrenzt). */
    fun alpha255(a: Float): Int {
        val v = (a * 255f + 0.5f).toInt()
        return if (v < 0) 0 else if (v > 255) 255 else v
    }

    /**
     * Skaliert die Alphawerte von [colors] so, dass der größte 255 wird, und schreibt das Ergebnis nach [out].
     * Liefert den größten Alphawert (0..255); 0 = alles durchsichtig. Dadurch teilen sich Verläufe, die nur durch
     * ein gemeinsames Alpha schwanken (Feuerschein: Alpha je Frame anders), denselben gecachten Shader; das Alpha
     * wird stattdessen am Paint gesetzt.
     */
    fun normalizeAlpha(colors: IntArray, n: Int, out: IntArray): Int {
        var maxA = 0
        for (i in 0 until n) {
            val a = colors[i] ushr 24
            if (a > maxA) maxA = a
        }
        if (maxA == 0) return 0
        for (i in 0 until n) {
            val c = colors[i]
            val a = ((c ushr 24) * 255 + maxA / 2) / maxA
            out[i] = (a shl 24) or (c and 0xFFFFFF)
        }
        return maxA
    }

    /**
     * Abbildung eines Textur-Ausschnitts auf ein Zielrechteck. Der Shader der Textur liegt im Texel-Raum
     * (lokale Matrix = Einheit, Kachelung in x); das Canvas wird um ([tx], [ty]) verschoben und mit ([sx], [sy])
     * skaliert, sodass Texel (u0·W, v0·H) auf ([x], [y]) und (u1·W, v1·H) auf ([x]+[w], [y]+[h]) fallen. Die
     * Drehung des Balkens steckt bereits in der Canvas-Matrix, die Textur folgt ihr deshalb. [out] = tx, ty, sx, sy.
     */
    fun regionTransform(
        u0: Float, v0: Float, u1: Float, v1: Float, x: Float, y: Float, w: Float, h: Float,
        texW: Int, texH: Int, out: FloatArray,
    ): Boolean {
        val du = u1 - u0
        val dv = v1 - v0
        if (du <= 0f || dv <= 0f || w <= 0f || h <= 0f || texW <= 0 || texH <= 0) return false
        val sx = w / (du * texW)
        val sy = h / (dv * texH)
        out[0] = x - u0 * texW * sx
        out[1] = y - v0 * texH * sy
        out[2] = sx
        out[3] = sy
        return true
    }

    /**
     * Zerlegt die Strecke (x0,y0)–(x1,y1) in Striche der Länge [dash] mit Lücke [gap] (Phase 0, wie `BasicStroke`)
     * als Folge von x0,y0,x1,y1-Vierern in [out] (ab Index 0, höchstens `out.size / 4` Striche). Gibt die Zahl der
     * Striche zurück. Kein Objekt entsteht; der Aufrufer vergrößert [out] bei Bedarf (siehe [segmentsNeeded]).
     */
    fun dashSegments(x0: Float, y0: Float, x1: Float, y1: Float, dash: Float, gap: Float, out: FloatArray): Int {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 1e-6f) return 0
        val d = if (dash < 1e-3f) 1e-3f else dash
        val g = if (gap < 1e-3f) 1e-3f else gap
        val ux = dx / len
        val uy = dy / len
        val cap = out.size / 4
        var n = 0
        var pos = 0f
        while (pos < len && n < cap) {
            val end = if (pos + d < len) pos + d else len
            val o = n * 4
            out[o] = x0 + ux * pos
            out[o + 1] = y0 + uy * pos
            out[o + 2] = x0 + ux * end
            out[o + 3] = y0 + uy * end
            n++
            pos += d + g
        }
        return n
    }

    /** Obergrenze der Strichzahl für [dashSegments] (begrenzt auf [MAX_DASHES], um Ausreißer abzufangen). */
    fun segmentsNeeded(x0: Float, y0: Float, x1: Float, y1: Float, dash: Float, gap: Float): Int {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        val period = (if (dash < 1e-3f) 1e-3f else dash) + (if (gap < 1e-3f) 1e-3f else gap)
        val n = floor(len / period).toInt() + 1
        return if (n > MAX_DASHES) MAX_DASHES else n
    }

    const val MAX_DASHES: Int = 1024

    fun hashFloat(h: Int, v: Float): Int = h * 31 + v.toRawBits()
}

/**
 * Zweifach assoziativer Cache für Verläufe: der Schlüssel (Start-/Endpunkt, Farben, Stützstellen) wird beim Treffer Wert
 * für Wert verglichen, es entsteht kein Objekt. Nur bei einem Fehlgriff ([GradientFactory.create]) wird neu gebaut und
 * ein Platz des Satzes (abwechselnd) überschrieben. [V] ist der plattformeigene Shader.
 */
internal class GradientCache<V : Any>(sets: Int, private val factory: GradientFactory<V>) {
    private val ways = 2
    private val setMask = sets - 1
    private val total = sets * ways
    private val values = arrayOfNulls<Any>(total)
    private val gx0 = FloatArray(total)
    private val gy0 = FloatArray(total)
    private val gx1 = FloatArray(total)
    private val gy1 = FloatArray(total)
    private val colors = arrayOfNulls<IntArray>(total)
    private val stops = arrayOfNulls<FloatArray>(total)
    private val victim = BooleanArray(sets)

    var hits = 0; private set
    var misses = 0; private set

    init { require(sets > 0 && (sets and (sets - 1)) == 0) { "sets must be a power of two" } }

    @Suppress("UNCHECKED_CAST")
    fun get(x0: Float, y0: Float, x1: Float, y1: Float, cols: IntArray, n: Int, st: FloatArray): V {
        var h = 17
        h = DrawMath.hashFloat(h, x0); h = DrawMath.hashFloat(h, y0)
        h = DrawMath.hashFloat(h, x1); h = DrawMath.hashFloat(h, y1)
        for (i in 0 until n) { h = h * 31 + cols[i]; h = DrawMath.hashFloat(h, st[i]) }
        val set = (h xor (h ushr 15) xor (h ushr 7)) and setMask
        val base = set * ways
        for (w in 0 until ways) {
            val slot = base + w
            val v = values[slot]
            if (v != null && matches(slot, x0, y0, x1, y1, cols, n, st)) { hits++; return v as V }
        }
        misses++
        val created = factory.create(x0, y0, x1, y1, cols, n, st)
        val slot = base + if (victim[set]) 1 else 0
        victim[set] = !victim[set]
        var c = colors[slot]
        if (c == null || c.size != n) { c = IntArray(n); colors[slot] = c }
        var s = stops[slot]
        if (s == null || s.size != n) { s = FloatArray(n); stops[slot] = s }
        for (i in 0 until n) { c[i] = cols[i]; s[i] = st[i] }
        gx0[slot] = x0; gy0[slot] = y0; gx1[slot] = x1; gy1[slot] = y1
        values[slot] = created
        return created
    }

    private fun matches(slot: Int, x0: Float, y0: Float, x1: Float, y1: Float, cols: IntArray, n: Int, st: FloatArray): Boolean {
        if (gx0[slot] != x0 || gy0[slot] != y0 || gx1[slot] != x1 || gy1[slot] != y1) return false
        val c = colors[slot] ?: return false
        val s = stops[slot] ?: return false
        if (c.size != n) return false
        for (i in 0 until n) if (c[i] != cols[i] || s[i] != st[i]) return false
        return true
    }

    fun clear() { values.fill(null) }
}

internal fun interface GradientFactory<V : Any> {
    fun create(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, n: Int, stops: FloatArray): V
}

/** Kleiner Cache Int → Wert (offene Adressierung); läuft er voll, wird er geleert (selten: wenige Schein-Farben). */
internal class IntValueCache<V : Any>(capacity: Int, private val factory: IntFactory<V>) {
    private val cap = capacity
    private val keys = IntArray(capacity)
    private val used = BooleanArray(capacity)
    private val values = arrayOfNulls<Any>(capacity)
    var count = 0; private set
    var misses = 0; private set

    init { require(capacity > 0 && (capacity and (capacity - 1)) == 0) { "capacity must be a power of two" } }

    @Suppress("UNCHECKED_CAST")
    fun get(key: Int): V {
        var i = (key * -1640531535 ushr 7) and (cap - 1)
        var probes = 0
        while (used[i]) {
            if (keys[i] == key) return values[i] as V
            i = (i + 1) and (cap - 1)
            if (++probes >= cap) break
        }
        misses++
        if (count >= cap / 2) {
            clear()
            i = (key * -1640531535 ushr 7) and (cap - 1)
        }
        val v = factory.create(key)
        keys[i] = key; used[i] = true; values[i] = v
        count++
        return v
    }

    fun clear() { used.fill(false); values.fill(null); count = 0 }
}

internal fun interface IntFactory<V : Any> {
    fun create(key: Int): V
}
