package de.bollwerk.renderandroid

import de.bollwerk.renderapi.Camera
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraSyncTest {
    @Test
    fun copyReproducesTheCameraExactly() {
        val src = Camera(1920f, 1080f, 2.5f)
        src.setBounds(0f, 0f, 120f, 70f)
        src.fitRect(10f, 10f, 90f, 50f, 20f)
        src.pan(37f, -12f)
        src.shakeX = 3f; src.shakeY = -2f
        val dst = Camera(dpPerMeter = src.dpPerMeter)
        CameraSync.copy(src, dst)
        assertEquals(src.centerX, dst.centerX); assertEquals(src.centerY, dst.centerY)
        assertEquals(src.zoom, dst.zoom); assertEquals(src.scale, dst.scale)
        assertEquals(src.viewportWidth, dst.viewportWidth); assertEquals(src.viewportHeight, dst.viewportHeight)
        assertEquals(src.density, dst.density)
        assertEquals(src.boundsMaxX, dst.boundsMaxX); assertEquals(src.boundsMinY, dst.boundsMinY)
        assertEquals(src.worldToScreenX(5f), dst.worldToScreenX(5f))
        assertEquals(src.worldToScreenY(5f), dst.worldToScreenY(5f))
    }

    @Test
    fun copyIsExactEvenForUnboundedCameras() {
        val src = Camera(800f, 480f, 1f)
        src.centerX = 123.4f; src.centerY = -5f
        src.setZoom(2f)
        val dst = Camera(dpPerMeter = src.dpPerMeter)
        CameraSync.copy(src, dst)
        assertEquals(123.4f, dst.centerX); assertEquals(-5f, dst.centerY); assertEquals(2f, dst.zoom)
    }

    /**
     * `pan`/`pinch` schreiben erst die ungeklemmte Mitte und klemmen danach. Der Leser darf nie einen solchen Zwischenstand
     * sehen, sonst würde er eine statische Ebene mit falschem Versatz aufzeichnen.
     */
    @Test
    fun readerNeverSeesTheUnclampedIntermediateStateOfPanAndPinch() {
        val shared = Camera(1000f, 600f, 1f)
        shared.setBounds(0f, 0f, 100f, 60f)
        shared.fitRect(0f, 0f, 100f, 60f)
        shared.setZoom(2f)
        shared.edit { centerX = 50f; centerY = 30f; clampToBounds() }
        val stop = AtomicBoolean(false)
        val writer = Thread {
            var i = 0
            while (!stop.get()) {
                val s = if (i++ % 2 == 0) 1f else -1f
                shared.edit { pan(s * 9000f, s * 5000f) } // weit über den Rand hinaus: Zwischenstand liegt außerhalb
                shared.edit { pinch(500f, 300f, if (s > 0) 1.2f else 1f / 1.2f) }
            }
        }
        writer.start()
        val dst = Camera(dpPerMeter = shared.dpPerMeter)
        var bad = 0
        val end = System.nanoTime() + 400_000_000L
        var n = 0
        while (System.nanoTime() < end) {
            CameraSync.copy(shared, dst)
            val hx = dst.viewportWidth * 0.5f / dst.scale
            val hy = dst.viewportHeight * 0.5f / dst.scale
            val okX = if (100f <= 2f * hx) dst.centerX == 50f else dst.centerX >= hx - 1e-3f && dst.centerX <= 100f - hx + 1e-3f
            val okY = if (60f <= 2f * hy) dst.centerY == 30f else dst.centerY >= hy - 1e-3f && dst.centerY <= 60f - hy + 1e-3f
            if (!okX || !okY) bad++
            n++
        }
        stop.set(true)
        writer.join()
        assertTrue(n > 100, "genug Kopien: $n")
        assertEquals(0, bad, "Zwischenzustände gelesen")
    }
}
