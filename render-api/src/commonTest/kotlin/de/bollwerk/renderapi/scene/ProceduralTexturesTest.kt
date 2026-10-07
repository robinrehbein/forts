package de.bollwerk.renderapi.scene

import de.bollwerk.renderapi.ImageSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProceduralTexturesTest {
    private fun same(a: ImageSpec, b: ImageSpec): Boolean = a.width == b.width && a.height == b.height && a.argb.contentEquals(b.argb)

    @Test
    fun sameSeedGivesSamePixels() {
        assertTrue(same(ProceduralTextures.wood(72, 0.32f, 11), ProceduralTextures.wood(72, 0.32f, 11)))
        assertTrue(same(ProceduralTextures.metal(72, 0.26f), ProceduralTextures.metal(72, 0.26f)))
        assertTrue(same(ProceduralTextures.armour(72, 0.42f), ProceduralTextures.armour(72, 0.42f)))
        assertTrue(same(ProceduralTextures.door(72, 0.40f), ProceduralTextures.door(72, 0.40f)))
        assertTrue(same(ProceduralTextures.grit(), ProceduralTextures.grit()))
        for (v in 0 until 4) assertTrue(same(ProceduralTextures.cloud(v), ProceduralTextures.cloud(v)))
        assertTrue(same(ProceduralTextures.jointWood(72), ProceduralTextures.jointWood(72)))
        assertTrue(same(ProceduralTextures.jointMetal(72), ProceduralTextures.jointMetal(72)))
        assertTrue(same(ProceduralTextures.jointAnchor(72), ProceduralTextures.jointAnchor(72)))
        assertTrue(same(ProceduralTextures.scorch(), ProceduralTextures.scorch()))
    }

    @Test
    fun differentSeedsDiffer() {
        assertFalse(same(ProceduralTextures.wood(72, 0.32f, 11), ProceduralTextures.wood(72, 0.32f, 12)))
        assertFalse(same(ProceduralTextures.metal(72, 0.26f, 12), ProceduralTextures.metal(72, 0.26f, 99)))
        assertFalse(same(ProceduralTextures.cloud(0), ProceduralTextures.cloud(1)))
    }

    @Test
    fun densityAwareSizes() {
        assertEquals(72, ProceduralTextures.tpmFor(1f))
        assertEquals(72, ProceduralTextures.tpmFor(0.5f))
        assertEquals(144, ProceduralTextures.tpmFor(2f))
        assertEquals(144, ProceduralTextures.tpmFor(3.5f))
        val lo = ProceduralTextures.wood(72, 0.32f)
        val hi = ProceduralTextures.wood(144, 0.32f)
        assertEquals(4 * 72, lo.width)
        assertEquals(4 * 144, hi.width)
        assertEquals(23, lo.height) // 0,32 m · 72 Texel
        assertEquals(46, hi.height)
        assertEquals(2 * 72, ProceduralTextures.metal(72, 0.26f).width)
        assertEquals(3 * 72, ProceduralTextures.armour(72, 0.42f).width)
        assertEquals(72, ProceduralTextures.door(72, 0.40f).width)
    }

    @Test
    fun beamTexturesAreOpaqueAndStyled() {
        for (t in listOf(ProceduralTextures.wood(72, 0.32f), ProceduralTextures.metal(72, 0.26f), ProceduralTextures.armour(72, 0.42f), ProceduralTextures.door(72, 0.40f))) {
            var opaque = 0
            for (i in 0 until t.width * t.height) if ((t.argb[i] ushr 24) == 255) opaque++
            assertTrue(opaque > t.width * t.height * 0.99, "beam texture must be opaque: ${t.id}")
        }
        // Holz: braun (R > B), Lichtkante in der obersten Zeile heller als die Mitte
        val w = ProceduralTextures.wood(72, 0.32f)
        val top = avg(w, 0)
        val mid = avg(w, w.height / 4)
        assertTrue(top > mid, "top highlight edge")
        val px = w.argb[w.height / 3 * w.width + 5]
        assertTrue(((px shr 16) and 0xFF) > (px and 0xFF), "wood is warm")
        // Metall: bläulich-grau
        val m = ProceduralTextures.metal(72, 0.26f)
        val mp = m.argb[m.height / 2 * m.width + 11]
        assertTrue((mp and 0xFF) >= ((mp shr 16) and 0xFF) - 2, "metal is neutral/cool")
    }

    private fun avg(t: ImageSpec, row: Int): Int {
        var s = 0
        for (x in 0 until t.width) { val p = t.argb[row * t.width + x]; s += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF) }
        return s / t.width
    }

    @Test
    fun cloudEndsFadeOut() {
        val c = ProceduralTextures.cloud(0)
        var edge = 0
        var middle = 0
        for (y in 0 until c.height) {
            edge = maxOf(edge, c.argb[y * c.width] ushr 24)
            middle = maxOf(middle, c.argb[y * c.width + c.width / 2] ushr 24)
        }
        assertTrue(edge < 10, "left edge faded")
        assertEquals(255, middle)
    }

    @Test
    fun jointsAndDecalsHaveTransparentCornersAndOpaqueCentre() {
        for (t in listOf(ProceduralTextures.jointWood(72), ProceduralTextures.jointMetal(72), ProceduralTextures.jointAnchor(72), ProceduralTextures.scorch(64))) {
            assertEquals(0, t.argb[0] ushr 24, "corner transparent: ${t.id}")
            assertTrue((t.argb[(t.height / 2) * t.width + t.width / 2] ushr 24) > 100, "centre painted: ${t.id}")
        }
        val n = ProceduralTextures.jointSize(72)
        assertEquals(n, ProceduralTextures.jointWood(72).width)
        // Knoten 0,42 m: Sprite deutlich größer als der Knoten (Schatten), aber < 0,6 m
        assertTrue(n / 72f in 0.5f..0.65f)
    }

    @Test
    fun gritTilesSeamlessly() {
        val g = ProceduralTextures.grit()
        assertEquals(256, g.width)
        // Nahtlos: gegenüberliegende Randspalten haben ähnliche Dichte an Körnern
        var left = 0
        var right = 0
        for (y in 0 until g.height) { if ((g.argb[y * g.width] ushr 24) > 0) left++; if ((g.argb[y * g.width + g.width - 1] ushr 24) > 0) right++ }
        assertTrue(kotlin.math.abs(left - right) < 40, "left=$left right=$right")
    }

    @Test
    fun pixelCanvasBlendsStraightAlpha() {
        val c = PixelCanvas(4, 4)
        c.rect(0f, 0f, 4f, 4f, 0xFFFF0000.toInt())
        c.rect(0f, 0f, 2f, 4f, 0x800000FF.toInt())
        val over = c.pixels[0]
        assertEquals(255, over ushr 24)
        assertTrue(((over shr 16) and 0xFF) in 120..135 && (over and 0xFF) in 120..135, "half blue over red: ${over.toString(16)}")
        assertEquals(0xFFFF0000.toInt(), c.pixels[3])
        val d = PixelCanvas(2, 2)
        d.rect(0f, 0f, 1.5f, 2f, 0xFFFFFFFF.toInt())
        assertEquals(255, d.pixels[0] ushr 24)
        val partial = d.pixels[1] ushr 24
        assertTrue(partial in 120..135, "half-covered pixel: $partial")
        assertNotEquals(0, PixelCanvas.lerpColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test
    fun jointSpritesCarryNoBakedShadow() {
        // Der Schlagschatten (2 dp, 30 %, nach unten rechts) wird ungedreht vom JointPainter gezeichnet. Die Sprites
        // selbst sind punktsymmetrisch (Sechseck, Scheibe, Lasche): gleich viel Deckkraft unten rechts und oben links.
        for (t in listOf(ProceduralTextures.jointWood(72), ProceduralTextures.jointMetal(72), ProceduralTextures.jointAnchor(72))) {
            val c = t.width / 2f
            var lowerRight = 0L
            var upperLeft = 0L
            for (y in 0 until t.height) for (x in 0 until t.width) {
                val d = (x + 0.5f - c) + (y + 0.5f - c)
                val a = (t.argb[y * t.width + x] ushr 24).toLong()
                if (d > 2f) lowerRight += a else if (d < -2f) upperLeft += a
            }
            val diff = kotlin.math.abs(lowerRight - upperLeft).toFloat() / maxOf(1L, upperLeft)
            assertTrue(diff < 0.03f, "${t.id}: asymmetric opacity (baked shadow?) $lowerRight vs $upperLeft")
        }
    }
}
