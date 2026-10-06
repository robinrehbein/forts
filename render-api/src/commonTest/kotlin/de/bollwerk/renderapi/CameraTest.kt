package de.bollwerk.renderapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraTest {
    private val eps = 1e-3f

    private fun cam() = Camera(viewportWidthPx = 2400f, viewportHeightPx = 1080f, density = 2.5f)

    @Test
    fun worldScreenRoundTrip() {
        val c = cam()
        c.centerX = 60f; c.centerY = 30f
        assertEquals(60f * 1f, c.screenToWorldX(1200f), eps)
        assertEquals(1200f, c.worldToScreenX(60f), eps)
        assertEquals(60f, c.scale, eps) // 24 dp · 2,5
        for (x in listOf(-10f, 0f, 33.3f, 119f)) assertEquals(x, c.screenToWorldX(c.worldToScreenX(x)), eps)
        for (y in listOf(-5f, 34f, 52f)) assertEquals(y, c.screenToWorldY(c.worldToScreenY(y)), eps)
    }

    @Test
    fun panFollowsFinger() {
        val c = cam()
        val before = c.screenToWorldX(500f)
        c.pan(120f, 0f)
        assertEquals(before, c.screenToWorldX(620f), eps)
    }

    @Test
    fun pinchKeepsFocusPointFixed() {
        val c = cam()
        c.centerX = 50f; c.centerY = 30f
        val fx = 700f; val fy = 300f
        val wx = c.screenToWorldX(fx); val wy = c.screenToWorldY(fy)
        c.pinch(fx, fy, 1.7f)
        assertEquals(1.7f, c.zoom, eps)
        assertEquals(wx, c.screenToWorldX(fx), eps)
        assertEquals(wy, c.screenToWorldY(fy), eps)
        c.pinch(fx, fy, 100f)
        assertEquals(c.maxZoom, c.zoom)
    }

    @Test
    fun clampsToBounds() {
        val c = cam()
        c.setBounds(0f, 0f, 120f, 64f)
        c.setZoom(2f) // sichtbar 2400/120 = 20 m breit
        c.pan(1e6f, 0f) // weit nach links schieben
        assertTrue(c.visibleMinX >= -eps)
        c.pan(-1e7f, 0f)
        assertTrue(c.visibleMaxX <= 120f + eps)
        // Größerer Sichtbereich als Grenzen → zentriert
        c.setZoom(0.25f)
        assertEquals(60f, c.centerX, eps)
        assertEquals(32f, c.centerY, eps)
    }

    @Test
    fun fitRectShowsWholeRect() {
        val c = cam()
        c.fitRect(20f, 20f, 40f, 36f, paddingPx = 40f)
        assertTrue(c.visibleMinX <= 20f + eps && c.visibleMaxX >= 40f - eps)
        assertTrue(c.visibleMinY <= 20f + eps && c.visibleMaxY >= 36f - eps)
        assertEquals(30f, c.centerX, eps)
    }

    @Test
    fun paletteTokens() {
        assertEquals(0xFF2B3440.toInt(), Palette.STEEL)
        assertEquals(0xFFD2622A.toInt(), Palette.RUST)
        assertEquals(5, Palette.SKY_GRADIENT.size)
        assertEquals(0x80, (Palette.withAlpha(Palette.TEXT, 0.5f) ushr 24) and 0xFF)
    }
}
