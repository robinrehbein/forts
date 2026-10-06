package de.bollwerk.renderapi.scene

import de.bollwerk.renderapi.Palette
import kotlin.math.abs
import kotlin.math.sin

/**
 * Hintergrund (Stil-Bibel §6): Abendhimmel, Sterne im oberen Drittel, Sonne mit Halo, 3 Bergebenen mit
 * Parallaxe (0,2 / 0,4 / 0,6) und Dunst, ferne Ebene, Kluft-Tiefe mit Felsnadeln, Wolkenbänder.
 * [drawSky] zeichnet die statische Ebene in **Bildschirm-Pixeln** (zwischenspeicherbar, [SceneLayers.SKY]);
 * [drawClouds] ist dynamisch (driftet mit dem Wind).
 */
internal class BackgroundPainter(private val c: SceneContext, private val info: WorldInfo) {
    private val skyColors = Palette.SKY_GRADIENT
    private val skyStops = floatArrayOf(0f, 0.38f, 0.64f, 0.86f, 1f)
    private val hazeColors = IntArray(2)
    private val hazeStops = floatArrayOf(0f, 1f)
    private val plColors = intArrayOf(Palette.PLATEAU_0, Palette.PLATEAU_1, Palette.PLATEAU_2)
    private val plStops = floatArrayOf(0f, 0.35f, 1f)
    private val deepColors = intArrayOf(Palette.withAlpha(Palette.ABYSS_0, 0f), Palette.withAlpha(Palette.ABYSS_0, 0.651f), Palette.ABYSS_1)
    private val deepStops = floatArrayOf(0f, 0.25f, 1f)
    private val spireCol = Palette.SPIRE
    private val fogColors = intArrayOf(Palette.withAlpha(Palette.SKY_3, 0f), Palette.withAlpha(Palette.FOG_ROSE, 0.278f), Palette.withAlpha(Palette.FOG_MAUVE, 0f))
    private val fogStops = floatArrayOf(0f, 0.45f, 1f)
    private val spireRng = RenderRng(23)
    private val cfColors = intArrayOf(Palette.withAlpha(Palette.CLIFF_0, 0f), Palette.withAlpha(Palette.CLIFF_1, 0.749f))

    fun horizonY(): Float = c.viewH * 0.5f + (info.groundY - 2.6f - c.camY) * c.s * 0.92f

    private fun ridge(u: Float, seed: Float): Float =
        sin(u * 0.9f + seed) * 0.42f + sin(u * 2.3f + seed * 2f) * 0.26f +
            (1f - abs(sin(u * 3.1f + seed * 3f))) * 0.16f + sin(u * 7.7f + seed) * 0.05f + 0.12f

    /** Himmel + Berge + ferne Ebene (alles in Bildschirm-Pixeln, Identitäts-Transformation). */
    fun drawSky() {
        val sink = c.sink
        val vw = c.viewW
        val vh = c.viewH
        val d = c.density
        val s = c.s
        val hY = horizonY()
        val top = hY - vh
        sink.gradientRect(0f, 0f, vw, vh, 0f, top, 0f, hY, skyColors, skyStops)

        // Sterne nur im oberen Drittel
        val sx0 = -c.camX * s * 0.02f
        val third = vh / 3f
        val span = vw * 1.3f
        for (i in 0 until WorldInfo.STARS) {
            val y = info.starY[i] * third
            var x = (info.starX[i] * span + sx0) % span
            if (x < 0f) x += span
            x -= vw * 0.15f
            val f = 1f - y / third
            sink.fillCircle(x, y, info.starS[i] * d, SceneContext.a(Palette.STAR, info.starA[i] * f * f))
        }

        // Sonne tief am Horizont
        val sR = SceneContext.clamp(vw / d * 0.028f, 26f, 48f) * d
        val sunX = vw * 0.5f + (info.map.width * 0.5f - c.camX) * s * 0.14f
        val sunY = hY - sR * 0.45f
        sink.glow(sunX, sunY, sR * 7f, SceneContext.a(Palette.SPARK, 0.4f))
        sink.fillCircle(sunX, sunY, sR, Palette.SUN_CORE)
        sink.fillCircle(sunX, sunY - sR * 0.12f, sR * 0.86f, Palette.SUN)
        sink.fillCircle(sunX - sR * 0.1f, sunY - sR * 0.26f, sR * 0.6f, Palette.CREAM)

        // 3 Bergebenen: weiter weg = heller und violetter
        for (l in 0 until 3) {
            val par = M_PAR[l]
            val col = M_COL[l]
            val hh = M_H[l]
            val seed = M_SEED[l]
            val f = M_F[l]
            val base = hY + M_BASE[l] * d
            val amp = vh * hh
            // Dunstschleier vor der Ebene
            hazeColors[0] = Palette.withAlpha(Palette.SKY_4, 0f); hazeColors[1] = Palette.withAlpha(Palette.HAZE_ROSE, 0.302f)
            sink.gradientRect(0f, base - amp * 1.2f, vw, amp * 1.2f, 0f, base - amp * 1.2f, 0f, base, hazeColors, hazeStops)
            val step = 12f * d
            val steps = ((vw + 30f * d) / step).toInt() + 2
            c.ensurePoly(steps + 4)
            val p = c.poly
            p[0] = -10f * d; p[1] = vh + 10f * d
            var n = 1
            var x = -10f * d
            for (i in 0 until steps) {
                val u = ((x - vw / 2f) / maxOf(8f * d, s) + c.camX * par) * f * 24f
                p[n * 2] = x; p[n * 2 + 1] = base - ridge(u, seed) * amp; n++
                x += step
            }
            p[n * 2] = x; p[n * 2 + 1] = vh + 10f * d; n++
            sink.fillPolygon(p, n, col)
            // Abendlicht-Kante auf dem Grat
            for (i in 1 until n - 2) sink.line(p[i * 2], p[i * 2 + 1], p[(i + 1) * 2], p[(i + 1) * 2 + 1], 1.2f * d, Palette.withAlpha(Palette.GLOW_PEACH, 0.118f), false)
        }

        // ferne Hochebene am Horizont, darunter die Tiefe der Schlucht
        val pl = hY + 6f * d
        val gyS = c.viewH * 0.5f + (info.groundY - c.camY) * s
        sink.gradientRect(0f, pl, vw, vh - pl, 0f, pl, 0f, maxOf(pl + 40f * d, gyS + 30f * d), plColors, plStops)
        sink.fillRect(0f, pl, vw, 1.2f * d, Palette.withAlpha(Palette.GLOW_PEACH, 0.349f))
        if (gyS < vh) {
            val deepH = 18f * s
            sink.gradientRect(0f, gyS, vw, vh - gyS + 2f, 0f, gyS, 0f, gyS + deepH, deepColors, deepStops)
        }

        // Felsnadeln in der Kluft (Parallaxe 0,85), oben vom Abendlicht gestreift
        if (info.hasValley) drawSpires(gyS)

        val cfTop = gyS + 2f * s
        if (cfTop < vh) sink.gradientRect(0f, cfTop, vw, vh - cfTop, 0f, cfTop, 0f, gyS + 14f * s, cfColors, hazeStops)
        sink.gradientRect(0f, pl - 26f * d, vw, 76f * d, 0f, pl - 26f * d, 0f, pl + 50f * d, fogColors, fogStops)
    }

    private fun drawSpires(gyS: Float) {
        val sink = c.sink
        val s = c.s
        val sp = 0.85f
        val vw = c.viewW
        val vh = c.viewH
        val x0 = info.valleyX0
        val w = info.valleyX1 - info.valleyX0
        c.ensurePoly(24)
        val p = c.poly
        val rr = spireRng
        rr.reseed(23)
        for (i in 0 until WorldInfo.SPIRES) {
            val wx = x0 + (i + 0.5f) / WorldInfo.SPIRES * w
            val up = info.spireUp[i]
            val ww = info.spireW[i] * s * sp
            val h = 18f + up
            val x = c.viewW * 0.5f + (wx - c.camX) * s * sp
            val topY = gyS - up * s * 0.9f
            if (x + ww < -50f || x - ww > vw + 50f) continue
            var n = 0
            p[0] = x - ww * 1.25f; p[1] = vh + 10f; n = 1
            val m = 7
            for (k in 0..m) {
                val u = k.toFloat() / m
                p[n * 2] = x - ww + 2f * ww * u
                val e = abs(u - 0.5f) * 2f
                p[n * 2 + 1] = topY + kotlin.math.sqrt(e * e * e) * h * s * 0.35f + (rr.next() - 0.5f) * s * 0.5f
                n++
            }
            p[n * 2] = x + ww * 1.25f; p[n * 2 + 1] = vh + 10f; n++
            sink.fillPolygon(p, n, spireCol)
            // Lichtkante oben
            for (k in 1 until n - 2) sink.line(p[k * 2], p[k * 2 + 1], p[(k + 1) * 2], p[(k + 1) * 2 + 1], 1.2f * c.density, Palette.withAlpha(Palette.GLOW_PEACH, 0.22f), false)
        }
    }

    /** Wolkenbänder (driften mit dem Wind), nach der Himmelsebene und vor dem Gelände. */
    fun drawClouds(phase: Float) {
        val sink = c.sink
        val vw = c.viewW
        val vh = c.viewH
        val hY = horizonY()
        val refX = info.map.width * 0.5f
        for (i in 0 until WorldInfo.CLOUDS) {
            val sc = info.cloudS[i]
            val w = vw * 0.34f * sc
            val h = w * ProceduralTextures.CLOUD_H / ProceduralTextures.CLOUD_W
            var cx = (info.cloudX[i] + phase * (0.6f + sc * 0.5f)) % 1.6f
            if (cx < -0.3f) cx += 1.6f
            if (cx > 1.3f) cx -= 1.6f
            val x = cx * vw * 1.2f - vw * 0.15f - (c.camX - refX) * c.s * 0.1f
            val y = hY - vh * 0.1f - info.cloudY[i] * vh * 0.95f
            if (y > hY - 40f * c.density || x > vw || x + w < 0f) continue
            val tex = c.texCloud[info.cloudK[i]]
            if (tex >= 0) sink.image(tex, x, y, w, h, info.cloudA[i] * 0.8f)
        }
    }

    private companion object {
        val M_PAR = floatArrayOf(0.2f, 0.4f, 0.6f)
        val M_COL = intArrayOf(Palette.MOUNTAIN_FAR, Palette.MOUNTAIN_MID, Palette.MOUNTAIN_NEAR)
        val M_H = floatArrayOf(0.13f, 0.085f, 0.05f)
        val M_SEED = floatArrayOf(1.3f, 4.1f, 7.7f)
        val M_F = floatArrayOf(0.016f, 0.028f, 0.045f)
        val M_BASE = floatArrayOf(-3f, 1f, 5f)
    }
}
