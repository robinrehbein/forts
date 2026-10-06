package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.renderapi.DrawSink
import de.bollwerk.renderapi.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Darstellungsklasse eines Materials (aus dem Schlüssel bzw. den Eigenschaften abgeleitet). */
internal object MatKind {
    const val WOOD = 0
    const val METAL = 1
    const val ARMOUR = 2
    const val ROPE = 3
    const val DOOR = 4

    fun of(key: String, tensionOnly: Boolean, isDoor: Boolean, flammable: Boolean): Int = when {
        key == "wood" -> WOOD
        key == "metal" -> METAL
        key == "armour" || key == "armor" -> ARMOUR
        key == "rope" -> ROPE
        key == "door" -> DOOR
        tensionOnly -> ROPE
        isDoor -> DOOR
        flammable -> WOOD
        else -> METAL
    }
}

/** Darstellungsklasse eines Geräts (kanonische Formen der Stil-Bibel §4). */
internal object DevKind {
    const val REACTOR = 0
    const val MINE = 1
    const val TURBINE = 2
    const val WORKSHOP = 3
    const val ARMOURY = 4
    const val UPGRADE = 5
    const val FACTORY = 6
    const val MG = 7
    const val SNIPER = 8
    const val MORTAR = 9
    const val CANNON = 10
    const val ROCKET = 11
    const val LASER = 12
    const val GENERIC = 13

    fun of(key: String, role: DeviceRole, hasWeapon: Boolean): Int = when {
        key == "reactor" -> REACTOR
        key == "mine" -> MINE
        key == "turbine" -> TURBINE
        key == "workshop" -> WORKSHOP
        key == "armoury" || key == "armory" -> ARMOURY
        key.startsWith("upgrade") -> UPGRADE
        key == "factory" -> FACTORY
        key == "mg" -> MG
        key == "sniper" -> SNIPER
        key == "mortar" -> MORTAR
        key == "cannon" -> CANNON
        key == "rocket" -> ROCKET
        key == "laser" -> LASER
        role == DeviceRole.REACTOR -> REACTOR
        role == DeviceRole.MINE -> MINE
        role == DeviceRole.TURBINE -> TURBINE
        role == DeviceRole.TECH -> WORKSHOP
        hasWeapon -> CANNON
        else -> GENERIC
    }
}

/** Ids der zwischenspeicherbaren Ebenen für [DrawSink.beginLayer]. */
object SceneLayers {
    /** Himmel, Sterne, Sonne, Berge, ferne Ebene und Spalten-Tiefe. */
    const val SKY: Int = 1
    /** Gelände (Erde, Schichten, Gras, Erz-Kristalle, Kluft-Nebel). */
    const val TERRAIN: Int = 2
}

/**
 * Gemeinsamer Zustand aller Maler für einen Frame: Zielsenke, Kameraabbildung, Zeit, Wind, Tabellen
 * und wiederverwendete Hilfspuffer. Weltzeichnungen laufen unter `translate(ox, oy)` + `scale(s, s)`,
 * also in **Metern**; [px] ist ein Gerätepixel in Metern, [dp] ein dp.
 */
internal class SceneContext {
    lateinit var sink: DrawSink
    var tables: SimTables = SimTables.EMPTY
    var map: MapSpec = MapSpec.flat()

    /** Bildschirmgröße in px. */
    var viewW = 1f
    var viewH = 1f
    var density = 1f
    /** Pixel pro Meter. */
    var s = 24f
    /** Welt→Bildschirm: sx = wx·s + ox. */
    var ox = 0f
    var oy = 0f
    /** Weltpunkt in der Bildschirmmitte (Kamera, ohne Shake). */
    var camX = 0f
    var camY = 0f
    /** Ein Gerätepixel in Metern. */
    var px = 1f / 24f
    /** Ein dp in Metern. */
    var dp = 1f / 24f

    /** Sim-Zeit in s (Tick + alpha, für Animationen). */
    var time = 0f
    var wind = 0f
    var alpha = 0f
    var reduced = false
    var strainView = false
    var localPlayer = 0

    // Klassifikation
    var matKind = IntArray(0)
    var matThick = FloatArray(0)
    var devKind = IntArray(0)
    /** Darstellungsklasse je Waffen-Index (`DevKind`), für Projektile. */
    var weaponKind = IntArray(0)

    // Texturen (Handles der Sink)
    var texWood = -1
    var texMetal = -1
    var texArmour = -1
    var texDoor = -1
    var texGrit = -1
    var texScorch = -1
    var texJointWood = -1
    var texJointMetal = -1
    var texJointAnchor = -1
    val texCloud = IntArray(4) { -1 }
    var tpm = ProceduralTextures.BASE_TPM
    var jointWorld = 0.5f

    /** Polygonpuffer (xy-Paare). */
    var poly = FloatArray(2048)
    val poly2 = FloatArray(64)

    // Interpolierte Knoten (pool-indiziert)
    var ix = FloatArray(0)
    var iy = FloatArray(0)

    fun ensurePoly(points: Int) {
        if (poly.size < points * 2) poly = poly.copyOf(maxOf(points * 2, poly.size * 2))
    }

    fun ensureNodes(n: Int) {
        if (ix.size < n) { ix = FloatArray(maxOf(n, ix.size * 2, 64)); iy = FloatArray(ix.size) }
    }

    fun setView(viewW: Float, viewH: Float, density: Float, scale: Float, ox: Float, oy: Float) {
        this.viewW = viewW; this.viewH = viewH; this.density = density
        s = scale; this.ox = ox; this.oy = oy
        px = 1f / scale
        dp = density / scale
        cullL = 0f; cullT = 0f; cullR = viewW; cullB = viewH
    }

    /** Cull-Rechteck in Bildschirm-Pixeln (Standard: ganzer Bildschirm; Lupe: Kreis-Begrenzung). */
    var cullL = 0f
    var cullT = 0f
    var cullR = 1f
    var cullB = 1f

    /** Sichtbarer Weltbereich mit Rand [margin] m. */
    fun visMinX(margin: Float): Float = (cullL - ox) / s - margin
    fun visMaxX(margin: Float): Float = (cullR - ox) / s + margin
    fun visMinY(margin: Float): Float = (cullT - oy) / s - margin
    fun visMaxY(margin: Float): Float = (cullB - oy) / s + margin

    // ---- Zeichenhilfen (Welt-Meter) ----

    /** Gefülltes Polygon + dunkle Kontur (1 dp). */
    fun polyOutlined(n: Int, fill: Int, outline: Int = OUTLINE, width: Float = px * 1.4f) {
        sink.fillPolygon(poly, n, fill)
        polyStroke(poly, n, outline, width)
    }

    /** Geschlossene Polylinie. */
    fun polyStroke(xy: FloatArray, n: Int, color: Int, width: Float) {
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            sink.line(xy[i * 2], xy[i * 2 + 1], xy[j * 2], xy[j * 2 + 1], width, color, true)
        }
    }

    /** Wie [polyOutlined], aber für das kleine Puffer-Array [poly2]. */
    fun polyOutlined2(n: Int, fill: Int, outline: Int = OUTLINE, width: Float = px * 1.4f) {
        sink.fillPolygon(poly2, n, fill)
        polyStroke(poly2, n, outline, width)
    }

    /** Rechteck als Polygon in [poly] (Ecken abgerundet mit Radius [r]). */
    fun roundRectPoly(x: Float, y: Float, w: Float, h: Float, r: Float): Int {
        val p = poly
        var n = 0
        val rr = minOf(r, w * 0.5f, h * 0.5f)
        val seg = 3
        // Ecken im Uhrzeigersinn: oben links, oben rechts, unten rechts, unten links
        for (corner in 0 until 4) {
            val cx = if (corner == 0 || corner == 3) x + rr else x + w - rr
            val cy = if (corner < 2) y + rr else y + h - rr
            val a0 = (PI.toFloat() * 0.5f) * (corner + 2)
            for (k in 0..seg) {
                val a = a0 + (PI.toFloat() * 0.5f) * k / seg
                p[n * 2] = cx + cos(a) * rr
                p[n * 2 + 1] = cy + sin(a) * rr
                n++
            }
        }
        return n
    }

    fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Int, outline: Int = OUTLINE) {
        val n = roundRectPoly(x, y, w, h, r)
        polyOutlined(n, fill, outline)
    }

    /**
     * Abgerundetes Rechteck mit diagonalem Verlauf: Polygon in der Mittelfarbe, darüber zwei Verlaufsrechtecke
     * (Kreuzform) – die Ecken bleiben einfarbig (kein Polygon-Clip nötig).
     */
    fun roundRectGrad(x: Float, y: Float, w: Float, h: Float, r: Float, c0: Int, c1: Int, c2: Int) {
        val n = roundRectPoly(x, y, w, h, r)
        sink.fillPolygon(poly, n, c1)
        grad3[0] = c0; grad3[1] = c1; grad3[2] = c2
        sink.gradientRect(x + r, y, w - 2f * r, h, x, y, x + w, y + h, grad3, STOPS3)
        sink.gradientRect(x, y + r, w, h - 2f * r, x, y, x + w, y + h, grad3, STOPS3)
        polyStroke(poly, n, OUTLINE, px * 1.4f)
    }

    /** Wie [roundRectPoly], aber ohne Weltabhängigkeit (Bildschirm-Pixel); identisch implementiert. */
    fun roundRectPolyScreen(x: Float, y: Float, w: Float, h: Float, r: Float): Int = roundRectPoly(x, y, w, h, r)

    /** Zahnrad in Bildschirm-Pixeln. */
    fun gearPolyScreen(cx: Float, cy: Float, r: Float, teeth: Int): Int = gearPoly(cx, cy, r, teeth)

    /** Zahnrad-Polygon (n Zähne) in [poly]; liefert die Punktzahl. */
    fun gearPoly(cx: Float, cy: Float, r: Float, teeth: Int, rot: Float = 0f): Int {
        val n = teeth * 2
        for (i in 0 until n) {
            val a = rot + i.toFloat() / n * TAU
            val rr = if (i % 2 == 1) r else r * 0.74f
            poly[i * 2] = cx + cos(a) * rr
            poly[i * 2 + 1] = cy + sin(a) * rr
        }
        return n
    }

    /** Kreis mit dunkler Kontur. */
    fun disc(cx: Float, cy: Float, r: Float, fill: Int) {
        sink.fillCircle(cx, cy, r, fill)
        sink.strokeCircle(cx, cy, r, px * 1.2f, OUTLINE)
    }

    /** Rechteck mit dunkler Kontur. */
    fun box(x: Float, y: Float, w: Float, h: Float, fill: Int) {
        sink.fillRect(x, y, w, h, fill)
        sink.strokeRect(x, y, w, h, px * 1.2f, OUTLINE)
    }

    /** Senkrecht verlaufendes Rechteck mit Kontur. */
    fun boxV(x: Float, y: Float, w: Float, h: Float, c0: Int, c1: Int) {
        grad2[0] = c0; grad2[1] = c1
        sink.gradientRect(x, y, w, h, x, y, x, y + h, grad2, STOPS2)
        sink.strokeRect(x, y, w, h, px * 1.2f, OUTLINE)
    }

    fun boxV3(x: Float, y: Float, w: Float, h: Float, c0: Int, c1: Int, c2: Int) {
        grad3[0] = c0; grad3[1] = c1; grad3[2] = c2
        sink.gradientRect(x, y, w, h, x, y, x, y + h, grad3, STOPS3)
        sink.strokeRect(x, y, w, h, px * 1.2f, OUTLINE)
    }

    /** Diagonal verlaufendes Rechteck (oben links → unten rechts) mit Kontur. */
    fun boxD(x: Float, y: Float, w: Float, h: Float, c0: Int, c1: Int, c2: Int = c1) {
        grad3[0] = c0; grad3[1] = if (c2 == c1) lerp(c0, c1, 0.5f) else c1; grad3[2] = c2
        sink.gradientRect(x, y, w, h, x, y, x + w, y + h, grad3, STOPS3)
        sink.strokeRect(x, y, w, h, px * 1.2f, OUTLINE)
    }

    private val grad2 = IntArray(2)
    private val grad3 = IntArray(3)

    /** Warnstreifen-Fläche (gelb/dunkel, 45°) ohne Clip: Streifen werden analytisch beschnitten. */
    fun hazard(x: Float, y: Float, w: Float, h: Float, stripe: Float = 0.22f) {
        sink.fillRect(x, y, w, h, Palette.HAZARD)
        var i = x - h * 2f
        val p = poly2
        while (i < x + w + h) {
            // Parallelogramm (i,y+h) (i+s,y+h) (i+s+h,y) (i+h,y), links/rechts auf [x, x+w] beschneiden
            val n = clipParallelogram(i, y, h, stripe, x, x + w, p)
            if (n >= 3) sink.fillPolygon(p, n, STRIPE_DARK)
            i += stripe * 2f
        }
        sink.strokeRect(x, y, w, h, px, OUTLINE)
    }

    /** Beschneidet das Streifen-Parallelogramm an x-Grenzen; Ergebnis in [out] (max. 6 Punkte). */
    private fun clipParallelogram(i: Float, y: Float, h: Float, s: Float, xmin: Float, xmax: Float, out: FloatArray): Int {
        val a = tmpA
        a[0] = i; a[1] = y + h
        a[2] = i + s; a[3] = y + h
        a[4] = i + s + h; a[5] = y
        a[6] = i + h; a[7] = y
        var n = 4
        n = clipX(a, n, xmin, true, tmpB)
        n = clipX(tmpB, n, xmax, false, out)
        return n
    }

    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)

    /** Sutherland–Hodgman gegen eine senkrechte Gerade; [keepRight] = behält x ≥ [lim]. */
    private fun clipX(src: FloatArray, n: Int, lim: Float, keepRight: Boolean, dst: FloatArray): Int {
        var m = 0
        for (k in 0 until n) {
            val ax = src[k * 2]; val ay = src[k * 2 + 1]
            val j = if (k + 1 == n) 0 else k + 1
            val bx = src[j * 2]; val by = src[j * 2 + 1]
            val ain = if (keepRight) ax >= lim else ax <= lim
            val bin = if (keepRight) bx >= lim else bx <= lim
            if (ain) { dst[m * 2] = ax; dst[m * 2 + 1] = ay; m++ }
            if (ain != bin) {
                val t = (lim - ax) / (bx - ax)
                dst[m * 2] = lim; dst[m * 2 + 1] = ay + (by - ay) * t; m++
            }
        }
        return m
    }

    /** Fuß-Platten mit Bolzen, mittig bei ±[w] (Gerätekoordinaten, y nach oben negativ). */
    fun feet(w: Float, col: Int = Palette.STEEL) {
        for (k in 0 until 2) {
            val x = if (k == 0) -w else w
            sink.fillRect(x - 0.16f, -0.14f, 0.32f, 0.14f, col)
            sink.strokeRect(x - 0.16f, -0.14f, 0.32f, 0.14f, px * 1.2f, OUTLINE)
            sink.fillCircle(x - 0.08f, -0.07f, 0.025f, BOLT)
            sink.fillCircle(x + 0.08f, -0.07f, 0.025f, BOLT)
        }
    }

    companion object {
        val OUTLINE: Int = Palette.OUTLINE
        val OUTLINE_BEAM: Int = Palette.OUTLINE_BEAM
        val STRIPE_DARK: Int = Palette.STEEL_DEEP
        val BOLT: Int = Palette.BOLT
        const val TAU: Float = (2.0 * PI).toFloat()
        val STOPS2 = floatArrayOf(0f, 1f)
        val STOPS3 = floatArrayOf(0f, 0.5f, 1f)

        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        fun lerp(a: Int, b: Int, t: Float): Int = de.bollwerk.renderapi.scene.PixelCanvas.lerpColor(a, b, t)

        fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

        fun fract(v: Float): Float = v - floor(v)

        /** Farbe mit Alpha 0..1. */
        fun a(rgb: Int, alpha: Float): Int = (rgb and Palette.WHITE) or ((clamp(alpha, 0f, 1f) * 255f + 0.5f).toInt() shl 24)

        /** Ersetzt den Alphakanal von [argb] durch [alpha] (0..1). */
        fun withAlpha(argb: Int, alpha: Float): Int = a(argb, alpha)

        /** hsl(h°, 82 %, l%) → ARGB (Dehnungsansicht, Prototyp `heatColor`). */
        fun heat(ratio: Float): Int {
            val r = clamp(ratio, 0f, 1.15f)
            val hue = 140f - 140f * minOf(1f, r)
            val l = (46f + 6f * r) / 100f
            return hsl(hue, 0.82f, l)
        }

        fun hsl(h: Float, s: Float, l: Float): Int {
            val c = (1f - abs(2f * l - 1f)) * s
            val hp = h / 60f
            val x = c * (1f - abs(hp - 2f * floor(hp / 2f) - 1f))
            var r = 0f; var g = 0f; var b = 0f
            when {
                hp < 1f -> { r = c; g = x }
                hp < 2f -> { r = x; g = c }
                hp < 3f -> { g = c; b = x }
                hp < 4f -> { g = x; b = c }
                hp < 5f -> { r = x; b = c }
                else -> { r = c; b = x }
            }
            val m = l - c * 0.5f
            return (0xFF shl 24) or (((r + m) * 255f + 0.5f).toInt() shl 16) or (((g + m) * 255f + 0.5f).toInt() shl 8) or ((b + m) * 255f + 0.5f).toInt()
        }
    }
}
