package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.renderapi.Palette
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Gelände (Stil-Bibel §6): Graskante mit Büscheln, Erdschicht, Gesteinsschichten mit Versatz, Körnung,
 * Kluft-Nebel, Erz-Kristalle mit Glühen sowie Betonfundamente unter verankerten Knoten.
 * [drawStatic] zeichnet in **Weltmetern** (Aufrufer setzt `translate`/`scale`) und ist zwischenspeicherbar
 * ([SceneLayers.TERRAIN]); Glühen und Fundamente sind dynamisch.
 */
internal class TerrainPainter(private val c: SceneContext, private val info: WorldInfo) {
    private val terrain = info.map.terrain
    private val strata = Palette.STRATA
    private val concreteColors = intArrayOf(Palette.CONCRETE_HI, Palette.CONCRETE_MID, Palette.CONCRETE_LO)
    private val rng = RenderRng(17)

    fun heightAt(x: Float): Float = terrain.heightAt(x)

    fun drawStatic() {
        val sink = c.sink
        val xMin = maxOf(terrain.x0, c.visMinX(3f))
        val xMax = minOf(terrain.x0 + (terrain.heights.size - 1) * terrain.step, c.visMaxX(3f))
        if (xMax <= xMin) return
        val step = terrain.step
        val i0 = floor((xMin - terrain.x0) / step).toInt().coerceAtLeast(0)
        val i1 = (kotlin.math.ceil((xMax - terrain.x0) / step).toInt()).coerceAtMost(terrain.heights.size - 1)
        val cnt = i1 - i0 + 1
        if (cnt < 2) return
        val bottom = maxOf(info.maxH + 12f, c.visMaxY(3f))
        c.ensurePoly(cnt + 8)
        val p = c.poly
        val xa = terrain.x0 + i0 * step
        val xb = terrain.x0 + i1 * step

        // Grundfläche
        var n = 0
        p[0] = xa; p[1] = bottom; n = 1
        for (i in i0..i1) { p[n * 2] = terrain.x0 + i * step; p[n * 2 + 1] = terrain.heights[i]; n++ }
        p[n * 2] = xb; p[n * 2 + 1] = bottom; n++
        sink.fillPolygon(p, n, Palette.EARTH_DEEP)

        // Gesteinsschichten: waagrecht, mit Versatz an Verwerfungen; folgen der Oberfläche (kein Clip nötig)
        val gy = info.minH
        val sx = 1f
        val m = ((xb - xa) / sx).toInt() + 2
        c.ensurePoly(m + 8)
        val q = c.poly
        for (k in strata.indices) {
            val y0 = gy + 0.55f + k * 1.75f
            var w = 0
            q[0] = xa; q[1] = bottom; w = 1
            var x = xa
            for (j in 0..m) {
                val fault = if (floor((x + 7f) / 13f).toInt() % 2 != 0) 0.28f else -0.12f
                val sy = y0 + fault + sin(x * 0.21f + k * 1.7f) * 0.18f + sin(x * 0.9f + k) * 0.05f
                val h = terrain.heightAt(x)
                q[w * 2] = x; q[w * 2 + 1] = if (sy > h) sy else h; w++
                x += sx
                if (x > xb) break
            }
            q[w * 2] = xb; q[w * 2 + 1] = bottom; w++
            sink.fillPolygon(q, w, strata[k])
        }

        drawGrit(xa, xb, bottom)

        // Erdschicht unter der Grasnarbe (folgt der Oberfläche)
        c.ensurePoly(cnt * 2 + 8)
        val e = c.poly
        var k = 0
        for (i in i0..i1) { e[k * 2] = terrain.x0 + i * step; e[k * 2 + 1] = terrain.heights[i]; k++ }
        for (i in i1 downTo i0) {
            val x = terrain.x0 + i * step
            e[k * 2] = x; e[k * 2 + 1] = terrain.heights[i] + 0.55f + sin(x * 1.3f) * 0.08f; k++
        }
        sink.fillPolygon(e, k, Palette.withAlpha(Palette.EARTH, 0.851f))

        drawGrass(i0, i1, xa, xb)
        drawOre()
        if (info.hasValley) drawCanyonFog()
    }


    /**
     * Kluft-Nebel (Stil-Bibel §6: nach unten dunkler und neblig). Er besteht aus übereinandergelegten Flächen, die
     * jeweils **nur den Luftraum der Kluft unterhalb einer Höhenlinie** füllen (Polygon aus der Höhenlinie und der
     * Geländekontur): nichts liegt über den Kluftwänden, die Gesteinsschichten laufen ungestört bis zur Wand. Die
     * Alphas addieren sich zu dem Tiefenverlauf [fogAlpha].
     */
    internal fun drawCanyonFog() {
        val top = info.groundY + 0.5f
        val bottom = info.maxH
        if (bottom - top < 1f) return
        var prev = 0f
        for (k in 0 until FOG_LAYERS) {
            val y = top + (bottom - top) * k / FOG_LAYERS
            val depth = (k + 1f) / FOG_LAYERS
            val target = fogAlpha(depth)
            val a = 1f - (1f - target) / (1f - prev)
            prev = target
            if (a <= 0.001f) continue
            val col = PixelCanvas.lerpColor(Palette.FOG_PLUM, Palette.FOG_PLUM_DEEP, depth)
            fillCanyonAir(y, SceneContext.a(col, a))
        }
    }

    /** Kumulative Nebeldichte in Abhängigkeit von der Tiefe 0..1 (0 → 0, 0,6 → 0,45, 1 → 0,85). */
    internal fun fogAlpha(depth: Float): Float =
        if (depth < 0.6f) 0.451f * depth / 0.6f else 0.451f + (0.851f - 0.451f) * (depth - 0.6f) / 0.4f

    /**
     * Füllt den Luftraum der Kluft unterhalb der Höhenlinie [y] mit [color]: **je zusammenhängendem Becken** (Lauf von
     * Geländeproben tiefer als [y] im Bereich der Kluft) ein Polygon aus den beiden Schnittpunkten der Linie mit der
     * Geländekontur und den Konturpunkten dazwischen. Eine Felsnadel in der Kluftmitte teilt sie damit in zwei
     * Becken, und beide werden gefüllt. Läuft ein Becken aus dem Gelände (kein Rand zum Abgrenzen), bekommt es keinen Nebel.
     */
    private fun fillCanyonAir(y: Float, color: Int) {
        val hs = terrain.heights
        val step = terrain.step
        val lo = ((info.valleyX0 - terrain.x0) / step + 0.5f).toInt().coerceIn(0, hs.size - 1)
        val hi = ((info.valleyX1 - terrain.x0) / step + 0.5f).toInt().coerceIn(0, hs.size - 1)
        var i = lo
        while (i <= hi) {
            if (hs[i] <= y) { i++; continue }
            var l = i
            while (l > 0 && hs[l - 1] > y) l--
            var r = i
            while (r < hs.size - 1 && hs[r + 1] > y) r++
            i = r + 1
            if (l == 0 || r == hs.size - 1) continue
            c.ensurePoly(r - l + 5)
            val p = c.poly
            // linker Schnittpunkt zwischen Probe l-1 (≤ y) und l (> y), rechter zwischen r und r+1
            val tl = (y - hs[l - 1]) / (hs[l] - hs[l - 1])
            p[0] = terrain.x0 + (l - 1) * step + tl * step; p[1] = y
            val tr = (y - hs[r]) / (hs[r + 1] - hs[r])
            p[2] = terrain.x0 + r * step + tr * step; p[3] = y
            var n = 2
            // Kontur von rechts nach links; die Schnittpunkte liegen exakt auf der Kontur
            for (k in r downTo l) { p[n * 2] = terrain.x0 + k * step; p[n * 2 + 1] = hs[k]; n++ }
            c.sink.fillPolygon(p, n, color)
        }
    }

    private fun drawGrit(xa: Float, xb: Float, bottom: Float) {
        val tex = c.texGrit
        if (tex < 0) return
        val sink = c.sink
        val tile = 6f
        var gx = floor(xa / tile) * tile
        while (gx < xb) {
            // tiefste Geländelinie in dieser Spalte
            var top = terrain.heightAt(gx)
            var xx = gx
            while (xx <= gx + tile) { val h = terrain.heightAt(xx); if (h > top) top = h; xx += 1f }
            top += 0.6f
            var ry = floor(top / tile) * tile
            while (ry < bottom) {
                val v0 = if (top > ry) (top - ry) / tile else 0f
                val y = ry + v0 * tile
                sink.imageRegion(tex, 0f, v0, 1f, 1f, gx, y, tile, (ry + tile) - y, 0.9f)
                ry += tile
            }
            gx += tile
        }
    }

    private fun drawGrass(i0: Int, i1: Int, xa: Float, xb: Float) {
        val sink = c.sink
        val step = terrain.step
        val dark = Palette.GROUND_DARK
        val green = Palette.GRASS_DARK
        val light = Palette.GRASS_LIGHT
        for (i in i0 until i1) {
            val x0 = terrain.x0 + i * step
            val x1 = x0 + step
            val y0 = terrain.heights[i]
            val y1 = terrain.heights[i + 1]
            sink.line(x0, y0, x1, y1, 0.34f, dark, true)
        }
        for (i in i0 until i1) {
            val x0 = terrain.x0 + i * step
            val x1 = x0 + step
            val y0 = terrain.heights[i]
            val y1 = terrain.heights[i + 1]
            if (abs(y1 - y0) > step * STEEP) {
                // Kluftwand: nackter Fels statt Gras, oben vom Abendlicht gestreift
                sink.line(x0, y0, x1, y1, 0.24f, Palette.DIRT, true)
                sink.line(x0 - 0.1f, y0, x1 - 0.1f, y1, 0.07f, Palette.withAlpha(Palette.GLOW_PEACH, 0.3f), true)
            } else {
                sink.line(x0, y0, x1, y1, 0.24f, green, true)
                sink.line(x0, y0 - 0.08f, x1, y1 - 0.08f, 0.07f, light, true)
            }
        }
        // Büschel (nur auf flachen Stücken)
        val tp = c.poly2
        var idx = floor(xa / 0.32f).toInt()
        var x = idx * 0.32f
        while (x < xb) {
            rng.reseed(idx * 7919 + 17)
            val y = terrain.heightAt(x)
            val sl = abs(terrain.heightAt(x + 0.3f) - y)
            rng.next()
            if (!(sl > 0.18f || rng.next() < 0.25f)) {
                val hgt = 0.12f + rng.next() * 0.26f
                val w = 0.07f + rng.next() * 0.06f
                val col = if (rng.next() < 0.5f) Palette.GRASS_MID else Palette.GRASS_TIP
                tp[0] = x - w; tp[1] = y
                tp[2] = x - w * 0.3f + (rng.next() - 0.5f) * 0.1f; tp[3] = y - hgt
                tp[4] = x; tp[5] = y - hgt * 0.4f
                tp[6] = x + w * 0.4f + (rng.next() - 0.5f) * 0.1f; tp[7] = y - hgt * 0.85f
                tp[8] = x + w; tp[9] = y
                sink.fillPolygon(tp, 5, col)
            }
            idx++
            x = idx * 0.32f
        }
        // Kantenschatten an der Kluftkante
        if (info.hasValley) {
            for (k in 0 until 2) {
                val ex = if (k == 0) info.valleyX0 else info.valleyX1
                sink.fillRect(ex - 0.5f, terrain.heightAt(ex) - 0.04f, 1f, 0.12f, Palette.withAlpha(Palette.SOOT, 0.2f))
            }
        }
    }

    /** Erz-Kristalle (statisch; Glühen siehe [drawOreGlow]). */
    private fun drawOre() {
        val sink = c.sink
        val ores = info.map.ores
        val tp = c.poly2
        for (o in ores.indices) {
            val ox = ores[o].x
            val y = terrain.heightAt(ox)
            rng.reseed(Math_round(ox * 10f))
            for (i in 0 until 7) {
                val x = ox + (i - 3) * 0.36f + (rng.next() - 0.5f) * 0.2f
                val h = 0.45f + rng.next() * 0.75f * (1f - abs(i - 3) / 5f)
                val w = 0.12f + rng.next() * 0.08f
                val lean = (rng.next() - 0.5f) * 0.25f
                val yb = y + 0.32f
                tp[0] = x - w - 0.03f; tp[1] = yb; tp[2] = x + lean - 0.03f; tp[3] = yb - h - 0.05f; tp[4] = x + w + 0.03f; tp[5] = yb
                sink.fillPolygon(tp, 3, Palette.ORE_DARK)
                tp[0] = x - w; tp[1] = yb; tp[2] = x + lean; tp[3] = yb - h; tp[4] = x + w; tp[5] = yb
                sink.fillPolygon(tp, 3, Palette.ORE_MID)
                tp[0] = x - w * 0.7f; tp[1] = yb; tp[2] = x + lean; tp[3] = yb - h; tp[4] = x + lean * 0.3f; tp[5] = yb
                sink.fillPolygon(tp, 3, Palette.ENERGY_LIGHT)
            }
        }
    }

    private fun Math_round(v: Float): Int = floor(v + 0.5f).toInt()

    /** Leichtes cyanfarbenes Glühen der Erzfelder (dynamisch, pulsiert sacht). */
    fun drawOreGlow() {
        val ores = info.map.ores
        val pulse = 0.85f + 0.15f * sin(c.time * 1.8f)
        for (o in ores.indices) {
            val x = ores[o].x
            c.sink.glow(x, terrain.heightAt(x) - 0.2f, 2.1f, SceneContext.a(Palette.ENERGY, 0.4f * pulse))
        }
    }

    /**
     * Betonsockel unter verankerten Knoten (Stil-Bibel §4): trapezförmig, zu 60 % im Boden versenkt (der versenkte Teil
     * wird als Querschnitt in dunklerem Beton gezeigt, nicht als dunkler Block), Stahl-Ankerplatte mit 2 Ankerbolzen
     * oben, schmaler Teamstreifen. Nur an Fundament-Knoten ([NodeFlags.ANCHORED]); Knoten, an denen nur ein Seil hängt
     * (Seil-Anker), bekommen statt des Sockels einen kleinen Ankerstein.
     */
    fun drawFoundations(snap: FrameSnapshot) {
        val sink = c.sink
        val tp = c.poly2
        val xmin = c.visMinX(2f)
        val xmax = c.visMaxX(2f)
        for (i in 0 until snap.nodeCount) {
            val f = snap.nodeFlags[i]
            if ((f and NodeFlags.ALIVE) == 0 || (f and NodeFlags.ANCHORED) == 0) continue
            val x = snap.nodeX[i]
            if (x < xmin || x > xmax) continue
            val y = terrain.heightAt(x)
            if (c.solidBeams[i] == 0) {
                if (c.ropeBeams[i] > 0) drawRopeAnchor(x, y, snap.nodeOwner[i]) else drawFreeSlot(x, y)
                continue
            }
            val top = y - FOUND_UP
            val bot = y + FOUND_DOWN
            // Schlagschatten (nur über dem Boden)
            tp[0] = x - 0.5f; tp[1] = top + 0.05f; tp[2] = x + 0.62f; tp[3] = top + 0.05f; tp[4] = x + 0.74f; tp[5] = y; tp[6] = x - 0.68f; tp[7] = y
            sink.fillPolygon(tp, 4, Palette.withAlpha(Palette.BLACK, 0.3f))
            // versenkter Teil: Querschnitt, mit Erde abgedunkelt (Betonton bleibt erkennbar)
            val wy = FOUND_HALF_TOP + (FOUND_HALF_BOT - FOUND_HALF_TOP) * (FOUND_UP / (FOUND_UP + FOUND_DOWN))
            tp[0] = x - wy; tp[1] = y; tp[2] = x + wy; tp[3] = y; tp[4] = x + FOUND_HALF_BOT; tp[5] = bot; tp[6] = x - FOUND_HALF_BOT; tp[7] = bot
            sink.fillPolygon(tp, 4, sunkConcrete)
            c.polyStroke(tp, 4, Palette.withAlpha(Palette.SOOT, 0.55f), 0.04f)
            for (k in 0 until 3) sink.fillRect(x - 0.4f + k * 0.3f, y + 0.18f + (k % 2) * 0.22f, 0.07f, 0.04f, Palette.withAlpha(Palette.BLACK, 0.2f))
            // sichtbarer Teil über dem Boden: hell links → dunkel rechts (drei Streifen statt Verlauf im Polygon)
            tp[0] = x - FOUND_HALF_TOP; tp[1] = top; tp[2] = x + FOUND_HALF_TOP; tp[3] = top; tp[4] = x + wy; tp[5] = y; tp[6] = x - wy; tp[7] = y
            sink.fillPolygon(tp, 4, concreteColors[1])
            tp[0] = x - FOUND_HALF_TOP; tp[1] = top; tp[2] = x - 0.1f; tp[3] = top; tp[4] = x - 0.16f; tp[5] = y; tp[6] = x - wy; tp[7] = y
            sink.fillPolygon(tp, 4, concreteColors[0])
            tp[0] = x + 0.25f; tp[1] = top; tp[2] = x + FOUND_HALF_TOP; tp[3] = top; tp[4] = x + wy; tp[5] = y; tp[6] = x + 0.3f; tp[7] = y
            sink.fillPolygon(tp, 4, concreteColors[2])
            tp[0] = x - FOUND_HALF_TOP; tp[1] = top; tp[2] = x + FOUND_HALF_TOP; tp[3] = top; tp[4] = x + wy; tp[5] = y; tp[6] = x - wy; tp[7] = y
            c.polyStroke(tp, 4, Palette.STEEL_DEEP, 0.045f)
            for (k in 0 until 4) sink.fillRect(x - 0.36f + k * 0.2f, top + 0.12f + (k % 2) * 0.1f, 0.05f, 0.035f, Palette.withAlpha(Palette.BLACK, 0.14f))
            // Teamstreifen (schmal) direkt unter der Ankerplatte: Dort deckt weder der Bodenbalken (±halbe Dicke um den
            // Knoten) noch die Knotenplatte ihn ab; am Boden selbst wäre er fast ganz verdeckt.
            val sy0 = top + STRIPE_OFF
            val sh = FOUND_HALF_TOP + (wy - FOUND_HALF_TOP) * ((STRIPE_OFF + STRIPE_H * 0.5f) / FOUND_UP)
            sink.fillRect(x - sh + 0.04f, sy0, 2f * sh - 0.08f, STRIPE_H, Palette.team(snap.nodeOwner[i]))
            // Erdkante: dunkle Linie am Boden, wo der Beton in die Erde taucht
            sink.fillRect(x - wy - 0.08f, y - 0.03f, 2f * wy + 0.16f, 0.07f, Palette.GROUND_DARK)
            // Stahl-Ankerplatte mit zwei Bolzen
            sink.gradientRect(x - 0.46f, top - 0.12f, 0.92f, 0.12f, 0f, top - 0.12f, 0f, top, plateColors, SceneContext.STOPS2)
            sink.strokeRect(x - 0.46f, top - 0.12f, 0.92f, 0.12f, 0.035f, Palette.STEEL_DEEP)
            sink.fillCircle(x - 0.32f, top - 0.06f, 0.05f, Palette.STEEL)
            sink.fillCircle(x + 0.32f, top - 0.06f, 0.05f, Palette.STEEL)
            sink.fillCircle(x - 0.332f, top - 0.075f, 0.02f, Palette.STEEL_PALE)
            sink.fillCircle(x + 0.308f, top - 0.075f, 0.02f, Palette.STEEL_PALE)
        }
    }

    /**
     * Freier Fundament-Platz (Knoten ohne einen einzigen Balken, z. B. die nicht genutzten Plätze der Karte): nur ein
     * flaches, durchscheinendes Betonpodest auf Bodenhöhe, ohne Ankerplatte, Bolzen, Schatten und Teamstreifen. Er
     * soll als möglicher Bauplatz erkennbar sein, aber nicht wie ein benutztes Fundament neben der Festung stehen.
     */
    private fun drawFreeSlot(x: Float, y: Float) {
        val sink = c.sink
        val tp = c.poly2
        tp[0] = x - 0.46f; tp[1] = y - FREE_H; tp[2] = x + 0.46f; tp[3] = y - FREE_H; tp[4] = x + 0.58f; tp[5] = y; tp[6] = x - 0.58f; tp[7] = y
        sink.fillPolygon(tp, 4, Palette.withAlpha(concreteColors[1], FREE_ALPHA))
        c.polyStroke(tp, 4, Palette.withAlpha(Palette.STEEL_DEEP, FREE_ALPHA), 0.03f)
    }

    /** Kleiner Ankerstein für ein reines Seil-Ende: halb versenkter Betonblock mit Öse. */
    private fun drawRopeAnchor(x: Float, y: Float, owner: Int) {
        val sink = c.sink
        sink.fillRect(x - 0.3f, y - 0.2f, 0.6f, 0.2f, concreteColors[1])
        sink.fillRect(x - 0.3f, y - 0.2f, 0.18f, 0.2f, concreteColors[0])
        sink.strokeRect(x - 0.3f, y - 0.2f, 0.6f, 0.2f, 0.04f, Palette.STEEL_DEEP)
        sink.fillRect(x - 0.3f, y, 0.6f, 0.3f, sunkConcrete)
        sink.fillRect(x - 0.3f, y - 0.03f, 0.6f, 0.06f, Palette.GROUND_DARK)
        sink.fillRect(x - 0.3f, y - 0.2f, 0.6f, 0.04f, Palette.team(owner))
    }

    private val sunkConcrete = PixelCanvas.lerpColor(Palette.CONCRETE_LO, Palette.DIRT_DARK, 0.4f)

    private val plateColors = intArrayOf(Palette.STEEL_PALE, Palette.STEEL_BODY)

    private companion object {
        const val FOG_LAYERS = 24
        /** Neigung (Höhe je Breite), ab der ein Geländestück als Felswand ohne Gras gilt. */
        const val STEEP = 1.1f
        /** Sockel: 40 % über, 60 % unter dem Boden. */
        const val FOUND_UP = 0.4f
        const val FOUND_DOWN = 0.6f
        const val FOUND_HALF_TOP = 0.46f
        const val FOUND_HALF_BOT = 0.7f
        /** Teamstreifen: Abstand unter der Oberkante des Sockels (unter der Ankerplatte) und Höhe. */
        const val STRIPE_OFF = 0.04f
        const val STRIPE_H = 0.07f
        /** Freier Fundament-Platz: Höhe des Podests (m) und Deckkraft. */
        const val FREE_H = 0.13f
        const val FREE_ALPHA = 0.55f
    }
}
