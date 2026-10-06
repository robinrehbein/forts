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
    private val fogColors = intArrayOf(Palette.withAlpha(Palette.FOG_PLUM, 0f), Palette.withAlpha(Palette.FOG_PLUM, 0.451f), Palette.withAlpha(Palette.FOG_PLUM_DEEP, 0.851f))
    private val fogStops = floatArrayOf(0f, 0.6f, 1f)
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

        // Kluft-Nebel (auch vor dem Hintergrund): nach unten dunkler
        if (info.hasValley) {
            val vx0 = info.valleyX0 - 1f
            val vw = info.valleyX1 - info.valleyX0 + 2f
            val top = info.groundY + 2f
            sink.gradientRect(vx0, top, vw, info.maxH + 1f - top, 0f, top, 0f, info.maxH + 1f, fogColors, fogStops)
        }

        drawGrass(i0, i1, xa, xb)
        drawOre()
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
            sink.line(x0, y0, x1, y1, 0.24f, green, true)
            sink.line(x0, y0 - 0.08f, x1, y1 - 0.08f, 0.07f, light, true)
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

    /** Betonsockel unter verankerten Knoten (zu 60 % im Boden, Ankerplatte, Teamstreifen). */
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
            val top = y - 0.42f
            val bot = y + 0.64f
            // Schlagschatten
            tp[0] = x - 0.5f; tp[1] = top + 0.05f; tp[2] = x + 0.62f; tp[3] = top + 0.05f; tp[4] = x + 0.82f; tp[5] = bot + 0.05f; tp[6] = x - 0.68f; tp[7] = bot
            sink.fillPolygon(tp, 4, Palette.withAlpha(Palette.BLACK, 0.349f))
            // Betonkörper: Trapez, links hell → rechts dunkel (drei Streifen statt Verlauf im Polygon)
            tp[0] = x - 0.5f; tp[1] = top; tp[2] = x + 0.5f; tp[3] = top; tp[4] = x + 0.74f; tp[5] = bot; tp[6] = x - 0.74f; tp[7] = bot
            sink.fillPolygon(tp, 4, concreteColors[1])
            tp[0] = x - 0.5f; tp[1] = top; tp[2] = x - 0.1f; tp[3] = top; tp[4] = x - 0.2f; tp[5] = bot; tp[6] = x - 0.74f; tp[7] = bot
            sink.fillPolygon(tp, 4, concreteColors[0])
            tp[0] = x + 0.25f; tp[1] = top; tp[2] = x + 0.5f; tp[3] = top; tp[4] = x + 0.74f; tp[5] = bot; tp[6] = x + 0.32f; tp[7] = bot
            sink.fillPolygon(tp, 4, concreteColors[2])
            tp[0] = x - 0.5f; tp[1] = top; tp[2] = x + 0.5f; tp[3] = top; tp[4] = x + 0.74f; tp[5] = bot; tp[6] = x - 0.74f; tp[7] = bot
            c.polyStroke(tp, 4, Palette.STEEL_DEEP, 0.05f)
            for (k in 0 until 6) sink.fillRect(x - 0.4f + k * 0.15f, top + 0.2f + (k % 3) * 0.2f, 0.05f, 0.04f, Palette.withAlpha(Palette.BLACK, 0.122f))
            // Teamstreifen
            val owner = snap.nodeOwner[i]
            sink.fillRect(x - 0.54f, top + 0.2f, 1.08f, 0.1f, Palette.team(owner))
            // Versenkung: Erde mit Körnung über dem unteren Teil
            sink.fillRect(x - 1f, y - 0.02f, 2f, 1f, SceneContext.a(Palette.DIRT_DARK, 0.78f))
            sink.fillRect(x - 0.9f, y - 0.04f, 1.8f, 0.08f, Palette.GROUND_DARK)
            // Stahl-Ankerplatte mit zwei Bolzen
            sink.gradientRect(x - 0.46f, top - 0.14f, 0.92f, 0.14f, 0f, top - 0.14f, 0f, top, plateColors, SceneContext.STOPS2)
            sink.strokeRect(x - 0.46f, top - 0.14f, 0.92f, 0.14f, 0.035f, Palette.STEEL_DEEP)
            sink.fillCircle(x - 0.32f, top - 0.07f, 0.055f, Palette.STEEL)
            sink.fillCircle(x + 0.32f, top - 0.07f, 0.055f, Palette.STEEL)
            sink.fillCircle(x - 0.332f, top - 0.085f, 0.022f, Palette.STEEL_PALE)
            sink.fillCircle(x + 0.308f, top - 0.085f, 0.022f, Palette.STEEL_PALE)
        }
    }

    private val plateColors = intArrayOf(Palette.STEEL_PALE, Palette.STEEL_BODY)
}
