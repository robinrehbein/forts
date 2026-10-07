package de.bollwerk.renderapi.scene

import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.PooledParticleSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prüft das Ebenen-Protokoll (beginLayer/endLayer) gegen eine echte Rastersenke. */
class AwtSinkTest {
    @Test
    fun cachedLayersGiveIdenticalPixelsAndAreReused() {
        val sc = SyntheticScene.narrow().both()
        val target = AwtTarget(960, 432, 1f)
        val r = SceneRenderer()
        r.bind(sc.tables, sc.map, target)
        val cam = Camera(960f, 432f, 1f).also { it.setZoom(0.75f); it.centerX = 46f; it.centerY = 26f }
        val p = PooledParticleSystem()
        r.render(target, sc.snap, 0f, cam, OverlayState.NONE, p)
        val first = target.image.getRGB(0, 0, 960, 432, null, 0, 960)
        assertEquals(2, target.awt.layerRecords)
        assertEquals(0, target.awt.cacheHits)
        // zweiter Frame, gleicher Zustand (Sim-Zeit unverändert): Ebenen kommen aus dem Cache, Pixel identisch
        val g = target.image.createGraphics(); g.composite = java.awt.AlphaComposite.Clear; g.fillRect(0, 0, 960, 432); g.dispose()
        r.render(target, sc.snap, 0f, cam, OverlayState.NONE, p)
        val second = target.image.getRGB(0, 0, 960, 432, null, 0, 960)
        assertEquals(2, target.awt.layerRecords, "no re-record")
        assertEquals(2, target.awt.cacheHits)
        var diff = 0
        for (i in first.indices) if (first[i] != second[i]) diff++
        assertEquals(0, diff, "cached frame must equal the freshly drawn one")
        // Kamera bewegt: neu aufnehmen
        cam.centerX = 47f
        r.render(target, sc.snap, 0f, cam, OverlayState.NONE, p)
        assertEquals(4, target.awt.layerRecords)
        assertTrue(first.any { (it ushr 24) != 0 })
    }
}
