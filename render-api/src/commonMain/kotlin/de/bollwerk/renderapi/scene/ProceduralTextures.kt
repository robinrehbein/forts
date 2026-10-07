package de.bollwerk.renderapi.scene

import de.bollwerk.renderapi.ImageSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Prozedurale Texturen (Stil-Bibel §4/§6), erzeugt als [ImageSpec]-Bitmaps. **Deterministisch:** gleiche
 * Parameter (Saat, [tpm], Dicke) liefern bitgleiche Pixel; es gibt keinen globalen Zustand.
 *
 * Balkentexturen entstehen in Gerätepixeln mit [tpm] Texeln je Meter (Basis [BASE_TPM] = 72, bis ×2 bei
 * hoher Pixeldichte, siehe [tpmFor]) und kacheln waagerecht (Periode [WOOD_LEN] … m); die Höhe entspricht
 * der Balkendicke. Der Renderer zeichnet sie rotiert entlang des Balkens.
 */
object ProceduralTextures {
    const val BASE_TPM: Int = 72
    /** Knotendurchmesser in m (Stil-Bibel §4: 0,42 m). */
    const val JOINT_D: Float = 0.42f
    const val WOOD_LEN: Int = 4
    const val METAL_LEN: Int = 2
    const val ARMOUR_LEN: Int = 3
    const val DOOR_LEN: Int = 1

    /** Cloud-Texturgröße. */
    const val CLOUD_W: Int = 640
    const val CLOUD_H: Int = 76

    /** Texel je Meter für die Pixeldichte [density] (1×–2×, gerundet). */
    fun tpmFor(density: Float): Int {
        val d = if (density < 1f) 1f else if (density > 2f) 2f else density
        return (BASE_TPM * d + 0.5f).toInt()
    }

    private fun c(rgb: Int, a: Float = 1f): Int = PixelCanvas.argb(rgb, a)
    private const val TAU = (2.0 * PI).toFloat()

    private fun texHeight(tpm: Int, thickness: Float): Int = (thickness * tpm + 0.5f).toInt().coerceAtLeast(4)

    /** Holz: 2 Planken, Fuge, Maserung mit Wellen, Astlöcher, Plankenstöße, AO-Kanten, Lichtkante (§4). */
    fun wood(tpm: Int, thickness: Float, seed: Int = 11): ImageSpec {
        val k = tpm / BASE_TPM.toFloat()
        val w = WOOD_LEN * tpm
        val h = texHeight(tpm, thickness)
        val hf = h.toFloat()
        val wf = w.toFloat()
        val cv = PixelCanvas(w, h)
        val r = RenderRng(seed)
        cv.rect(0f, 0f, wf, hf, c(0xa96f33))
        cv.rectV(0f, 0f, wf, hf * 0.5f, c(0xc48a4a), c(0xa96f33))
        cv.rectV(0f, hf * 0.5f, wf, hf, c(0x9c642c), c(0x7a4b1f))
        cv.rect(0f, 0f, wf, hf * 0.5f, c(0xffdca0, 0.06f))
        // Maserung: leicht gewellte Linien längs, periodisch in x (ganze Wellenzahlen)
        for (i in 0 until 30) {
            val y = r.next() * hf
            val kk = 2 + r.below(4)
            val ph = r.next() * 6f
            val amp = (0.6f + r.next() * 1.2f) * k
            val col = if (r.next() < 0.62f) c(0x46240a, 0.18f + r.next() * 0.22f) else c(0xffd696, 0.08f + r.next() * 0.12f)
            val lw = (0.5f + r.next() * 0.8f) * k
            var px = 0f
            var py = y + sin(ph) * amp + sin(ph * 2f) * amp * 0.3f
            var x = 4f * k
            while (x <= wf + 0.01f) {
                val yy = y + sin(x / wf * TAU * kk + ph) * amp + sin(x / wf * TAU * 11f + ph * 2f) * amp * 0.3f
                cv.segment(px, py, x, yy, lw, col)
                px = x; py = yy
                x += 4f * k
            }
        }
        // Astlöcher (1 je 2 m)
        for (i in 0 until 2) {
            val x = (i + 0.25f + r.next() * 0.5f) * wf / 2f
            val y = (if (i == 1) 0.72f else 0.3f) * hf
            for (q in 4 downTo 1) cv.ellipseRing(x, y, q * 3f * k, q * 1.15f * k, 0.8f * k, c(0x3c1c06, 0.12f + 0.1f * q))
            cv.ellipseGrad(x, y, 3f * k, 1.8f * k, c(0x3a1c06), c(0x5a2e0e, 0.6f))
        }
        // Plankenfuge
        cv.rect(0f, hf * 0.5f - 0.6f * k, wf, hf * 0.5f + 0.6f * k, c(0x1c0c02, 0.7f))
        cv.rect(0f, hf * 0.5f + 0.6f * k, wf, hf * 0.5f + 1.4f * k, c(0xffdcaa, 0.18f))
        // Plankenstöße mit je zwei Nägeln
        for (j in 0 until 2) {
            val x0 = wf * (if (j == 0) 0.31f else 0.78f)
            val top = j == 0
            cv.rect(x0, if (top) 0f else hf * 0.5f, x0 + 1.2f * k, if (top) hf * 0.5f else hf, c(0x1e0e02, 0.6f))
            for (xx in floatArrayOf(x0 - 4f * k, x0 + 5f * k)) {
                val y = if (top) hf * 0.25f else hf * 0.75f
                cv.disc(xx, y, 1.5f * k, c(0x2a1a0e))
                cv.disc(xx - 0.4f * k, y - 0.4f * k, 0.55f * k, c(0xffebc8, 0.55f))
            }
        }
        // Ambient Occlusion unten + Lichtkante oben
        cv.rectV(0f, hf * 0.85f, wf, hf, c(0x281204, 0f), c(0x1e0c02, 0.55f))
        cv.rect(0f, 0f, wf, 1.1f * k, c(0xffe2af, 0.55f))
        return ImageSpec(w, h, cv.pixels, "wood@$tpm#$seed")
    }

    /** Metall: Doppel-T-Anmutung, helle Oberkante, dunkler Mittelsteg, Nieten alle 0,5 m (§4). */
    fun metal(tpm: Int, thickness: Float, seed: Int = 12): ImageSpec {
        val k = tpm / BASE_TPM.toFloat()
        val w = METAL_LEN * tpm
        val h = texHeight(tpm, thickness)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val cv = PixelCanvas(w, h)
        val r = RenderRng(seed)
        val f1 = hf * 0.26f
        val f2 = hf * 0.74f
        cv.rect(0f, 0f, wf, hf, c(0x3a4552))
        cv.rectV(0f, 0f, wf, f1, c(0xc9d3de), c(0x8a97a8))
        cv.rectV(0f, f1, wf, f2, c(0x2a333e), c(0x3a4552), c(0x232b35))
        cv.rectV(0f, f2, wf, hf, c(0x7c8899), c(0x4b5664))
        cv.rect(0f, f1, wf, f1 + 0.9f * k, c(0x000000, 0.45f))
        cv.rect(0f, f2 - 0.9f * k, wf, f2, c(0x000000, 0.45f))
        for (i in 0 until 26) {
            val col = if (r.next() < 0.5f) c(0xffffff, 0.10f) else c(0x000000, 0.14f)
            val x = r.next() * wf
            val y = f1 + r.next() * (f2 - f1)
            cv.rect(x, y, minOf(x + (6f + r.next() * 30f) * k, wf), y + 0.5f * k, col)
        }
        for (i in 0 until 5) {
            val x = r.next() * wf
            val y = r.next() * hf
            cv.segment(x, y, x + (4f + r.next() * 8f) * k, y + (r.next() - 0.5f) * 3f * k, 0.5f * k, c(0xffffff, 0.12f))
        }
        var x = tpm * 0.25f
        while (x < wf) {
            for (y in floatArrayOf(f1 * 0.52f, f2 + (hf - f2) * 0.5f)) {
                cv.disc(x + 0.5f * k, y + 0.7f * k, 1.9f * k, c(0x080c12, 0.55f))
                cv.sphere(x, y, 1.7f * k, -0.6f * k, -0.6f * k, c(0xf2f6fa), c(0x7d8a9a))
            }
            x += tpm * 0.5f
        }
        cv.rect(0f, 0f, wf, 0.9f * k, c(0xf5faff, 0.75f))
        return ImageSpec(w, h, cv.pixels, "metal@$tpm#$seed")
    }

    /** Panzer: Plattensegmente à 1 m mit Stoßfugen, 4 Bolzen je Segment, matter Stahl, Rost an den Fugen (§4). */
    fun armour(tpm: Int, thickness: Float, seed: Int = 13): ImageSpec {
        val k = tpm / BASE_TPM.toFloat()
        val w = ARMOUR_LEN * tpm
        val h = texHeight(tpm, thickness)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val cv = PixelCanvas(w, h)
        val r = RenderRng(seed)
        cv.rect(0f, 0f, wf, hf, c(0x444d58))
        cv.rectV(0f, 0f, wf, hf * 0.12f, c(0x5a6470), c(0x444d58))
        cv.rectV(0f, hf * 0.12f, wf, hf * 0.85f, c(0x444d58), c(0x323942))
        cv.rectV(0f, hf * 0.85f, wf, hf, c(0x323942), c(0x22282f))
        for (i in 0 until 60) {
            val col = if (r.next() < 0.5f) c(0xffffff, 0.035f) else c(0x000000, 0.08f)
            val x = r.next() * wf
            val y = r.next() * hf
            cv.rect(x, y, x + (2f + r.next() * 6f) * k, y + (1f + r.next() * 3f) * k, col)
        }
        for (s in 0 until ARMOUR_LEN) {
            val x = (s * tpm).toFloat()
            for (q in 0 until 3) {
                val rx = x + (if (r.next() < 0.5f) r.next() * 8f else tpm - r.next() * 10f) * k
                val ry = r.next() * hf
                cv.rectV(rx, ry, rx + (1.5f + r.next() * 3f) * k, ry + 10f * k, c(0xa05022, 0.35f), c(0xa05022, 0f))
            }
            cv.rect(x, 0f, x + 1.6f * k, hf, c(0x000000, 0.65f))
            cv.rect(x + 1.6f * k, 0f, x + 2.6f * k, hf, c(0xffffff, 0.13f))
            cv.rectH(x + 2.6f * k, 0f, x + 8.6f * k, hf, c(0x96481e, 0.35f), c(0x96481e, 0f))
            val bxs = floatArrayOf(x + 0.14f * tpm, x + 0.86f * tpm)
            val bys = floatArrayOf(hf * 0.24f, hf * 0.76f)
            for (bx in bxs) for (by in bys) {
                cv.disc(bx + 0.6f * k, by + 0.8f * k, 2.3f * k, c(0x000000, 0.55f))
                cv.sphere(bx, by, 2.1f * k, -0.7f * k, -0.7f * k, c(0xaab4c0), c(0x4a5360))
            }
        }
        cv.rect(0f, 0f, wf, 1.1f * k, c(0xffffff, 0.3f))
        cv.rect(0f, hf - 1.6f * k, wf, hf, c(0x000000, 0.55f))
        return ImageSpec(w, h, cv.pixels, "armour@$tpm#$seed")
    }

    /** Tür: Panzerplatte mit Warnstreifen-Rand, Nieten, Scharnier-Kante (§4). */
    fun door(tpm: Int, thickness: Float): ImageSpec {
        val k = tpm / BASE_TPM.toFloat()
        val w = DOOR_LEN * tpm
        val h = texHeight(tpm, thickness)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val cv = PixelCanvas(w, h)
        val sb = hf * 0.33f
        val st = tpm * 0.2f
        cv.rect(0f, 0f, wf, hf, c(0x3e4650))
        cv.rectV(0f, 0f, wf, hf * 0.5f, c(0x68727e), c(0x3e4650))
        cv.rectV(0f, hf * 0.5f, wf, hf, c(0x3e4650), c(0x2a3038))
        for (y0 in floatArrayOf(0f, hf - sb)) {
            cv.rect(0f, y0, wf, y0 + sb, c(0xe8b73a))
            // schräge dunkle Streifen (Periode 2·st), pro Pixel per Phase bestimmt
            cv.shape(0f, y0, wf, y0 + sb, { px, py -> if (py >= y0 && py < y0 + sb) 1f else 0f }, { px, py ->
                val u = (px + (py - y0) - 2f * st) / (2f * st)
                val f = u - kotlin.math.floor(u)
                if (f < 0.5f) c(0x161b21) else 0
            })
        }
        cv.rect(0f, sb, wf, sb + 1.2f * k, c(0x000000, 0.6f))
        cv.rect(0f, hf - sb - 1.2f * k, wf, hf - sb, c(0x000000, 0.6f))
        cv.rect(0f, sb + 1.2f * k, wf, sb + 2.2f * k, c(0xffffff, 0.18f))
        for (x in floatArrayOf(wf * 0.2f, wf * 0.8f)) {
            val y = hf * 0.5f
            cv.disc(x + 0.5f * k, y + 0.6f * k, 1.8f * k, c(0x000000, 0.5f))
            cv.disc(x, y, 1.5f * k, c(0xc3ccd6))
        }
        cv.rect(wf - 1.4f * k, 0f, wf, hf, c(0x000000, 0.65f))
        return ImageSpec(w, h, cv.pixels, "door@$tpm")
    }

    /** Erde/Gestein-Körnung (256×256, nahtlos kachelbar). */
    fun grit(seed: Int = 31): ImageSpec {
        val n = 256
        val cv = PixelCanvas(n, n)
        val r = RenderRng(seed)
        for (i in 0 until 900) {
            val x = r.next() * n
            val y = r.next() * n
            val s = 0.5f + r.next() * 2.2f
            val col = if (r.next() < 0.55f) c(0x0a060a, 0.1f + r.next() * 0.18f) else c(0xe6beaa, 0.04f + r.next() * 0.07f)
            for (ox in intArrayOf(0, -n, n)) for (oy in intArrayOf(0, -n, n)) cv.ellipse(x + ox, y + oy, s, s * 0.7f, col)
        }
        for (i in 0 until 40) {
            val x = r.next() * n
            val y = r.next() * n
            val s = 2f + r.next() * 4f
            val rr = 120 + (r.next() * 40).toInt()
            val gg = 90 + (r.next() * 30).toInt()
            val bb = 96 + (r.next() * 30).toInt()
            for (ox in intArrayOf(0, -n, n)) for (oy in intArrayOf(0, -n, n)) {
                cv.ellipse(x + 0.8f + ox, y + 1f + oy, s, s * 0.7f, c(0x000000, 0.25f))
                cv.ellipse(x + ox, y + oy, s, s * 0.7f, c((rr shl 16) or (gg shl 8) or bb, 0.5f))
            }
        }
        return ImageSpec(n, n, cv.pixels, "grit")
    }

    /** Wolkenband (4 Varianten): lang, flach, 2 Töne + helle Oberkante, Enden ausgeblendet (§6). */
    fun cloud(variant: Int): ImageSpec {
        val w = CLOUD_W
        val h = CLOUD_H
        val cv = PixelCanvas(w, h)
        val r = RenderRng(40 + variant)
        // (x0, y, x1, höhe)
        val caps = ArrayList<FloatArray>(3)
        caps.add(floatArrayOf(16f + r.next() * 30f, 46f, w - 20f - r.next() * 50f, 22f))
        caps.add(floatArrayOf(90f + r.next() * 90f, 31f, 380f + r.next() * 170f, 20f))
        if (variant % 2 == 0) caps.add(floatArrayOf(170f + r.next() * 80f, 18f, 300f + r.next() * 90f, 17f))
        else caps.add(floatArrayOf(330f + r.next() * 60f, 19f, 470f + r.next() * 80f, 15f))
        for (cp in caps) {
            val x0 = cp[0]; val y = cp[1]; val x1 = cp[2]; val hh = cp[3]
            cv.roundRectDiag(x0 + 3f, y - 2.2f, x1 - 3f, y - 2.2f + hh, hh / 2f, c(0xf2b89a), c(0xf2b89a))
            cv.roundRectDiag(x0, y, x1, y + hh, hh / 2f, c(0x74496f), c(0x74496f))
            // obere Hälfte heller (nur innerhalb der Kapsel)
            val hx = (x1 - x0) / 2f - hh / 2f
            val cx = (x0 + x1) / 2f
            cv.shape(x0, y, x1, y + hh * 0.5f, { px, py ->
                val qx = abs(px - cx) - hx
                val dx = if (qx > 0f) qx else 0f
                val dy = py - (y + hh / 2f)
                val d = PixelCanvas.dist(dx, dy)
                (hh / 2f - d + 0.5f) * (if (py < y + hh * 0.5f) 1f else 0f)
            }, { _, _ -> c(0xa8677f) })
        }
        // Enden ausblenden
        for (iy in 0 until h) for (ix in 0 until w) {
            val u = (ix + 0.5f) / w
            val f = if (u < 0.1f) u / 0.1f else if (u > 0.9f) (1f - u) / 0.1f else 1f
            if (f < 1f) {
                val p = cv.pixels[iy * w + ix]
                val a = ((p ushr 24) * f + 0.5f).toInt()
                cv.pixels[iy * w + ix] = (a shl 24) or (p and 0xFFFFFF)
            }
        }
        return ImageSpec(w, h, cv.pixels, "cloud$variant")
    }

    /** Weicher dunkler Brandfleck (Decal), quadratisch. */
    fun scorch(size: Int = 64): ImageSpec {
        val cv = PixelCanvas(size, size)
        val half = size / 2f
        cv.radial(half, half, half, c(0x0c0806, 0.85f), c(0x140c08, 0.55f), c(0x140c08, 0f))
        return ImageSpec(size, size, cv.pixels, "scorch")
    }

    /** Kantenlänge des Knoten-Sprites in Texeln; Weltgröße = `jointSize(tpm) / tpm`. */
    fun jointSize(tpm: Int): Int = (0.26f * 2f * tpm).toInt() + 7

    private fun bolt(cv: PixelCanvas, x: Float, y: Float, r: Float) {
        cv.disc(x + r * 0.25f, y + r * 0.3f, r, c(0x000000, 0.45f))
        cv.sphere(x, y, r, -r * 0.4f, -r * 0.4f, c(0xf0f4f8), c(0x7d8898))
    }

    // Der Schlagschatten (2 dp, 30 %) steckt nicht in den Sprites: `JointPainter` zeichnet ihn ungedreht nach unten rechts.

    /** Holz-Knoten: Eisenlasche mit 3 Bolzen (waagrecht, wird mit dem Balken gedreht). */
    fun jointWood(tpm: Int): ImageSpec {
        val n = jointSize(tpm)
        val cv = PixelCanvas(n, n)
        val m = tpm.toFloat()
        val cx = n / 2f
        val cy = n / 2f
        val w = JOINT_D * 0.5f * m
        val h = 0.16f * m
        val o = maxOf(1f, 1.2f * tpm / BASE_TPM)
        cv.roundRectDiag(cx - w - o, cy - h - o, cx + w + o, cy + h + o, 0.05f * m, c(0x0f1318), c(0x0f1318))
        cv.roundRectDiag(cx - w, cy - h, cx + w, cy + h, 0.045f * m, c(0x6c7682), c(0x343c46))
        cv.rect(cx - w + 0.02f * m, cy - h + 0.012f * m, cx + w - 0.02f * m, cy - h + 0.032f * m, c(0xffffff, 0.25f))
        bolt(cv, cx - 0.11f * m, cy - 0.06f * m, 0.038f * m)
        bolt(cv, cx + 0.11f * m, cy - 0.06f * m, 0.038f * m)
        bolt(cv, cx, cy + 0.07f * m, 0.038f * m)
        return ImageSpec(n, n, cv.pixels, "jointWood@$tpm")
    }

    /** Metall-Knoten: sechseckiges Knotenblech mit Mittelbolzen. */
    fun jointMetal(tpm: Int): ImageSpec {
        val n = jointSize(tpm)
        val cv = PixelCanvas(n, n)
        val m = tpm.toFloat()
        val cx = n / 2f
        val cy = n / 2f
        val r = JOINT_D * 0.5f * m
        cv.hexagon(cx, cy, r + maxOf(1.1f, 1.3f * tpm / BASE_TPM), c(0x0f1318))
        cv.hexagon(cx, cy, r, c(0xb4c0cd), c(0x4d5866))
        cv.ring(cx, cy, r * 0.74f, 0.015f * m + 0.5f, c(0xffffff, 0.3f))
        bolt(cv, cx, cy, 0.065f * m)
        cv.disc(cx, cy, 0.022f * m, c(0x2b3440))
        return ImageSpec(n, n, cv.pixels, "jointMetal@$tpm")
    }

    /** Ankerbolzen-Kopf auf dem Fundament. */
    fun jointAnchor(tpm: Int): ImageSpec {
        val n = jointSize(tpm)
        val cv = PixelCanvas(n, n)
        val m = tpm.toFloat()
        val cx = n / 2f
        val cy = n / 2f
        val r = JOINT_D * 0.5f * m
        cv.disc(cx, cy, r + maxOf(1f, 1.2f * tpm / BASE_TPM), c(0x0f1318))
        cv.sphere(cx, cy, r, -r * 0.4f, -r * 0.4f, c(0xd5dde6), c(0x5d6876))
        cv.disc(cx, cy, r * 0.42f, c(0x2b3440))
        cv.disc(cx - r * 0.1f, cy - r * 0.12f, r * 0.16f, c(0xffffff, 0.4f))
        return ImageSpec(n, n, cv.pixels, "jointAnchor@$tpm")
    }
}
