package de.bollwerk.renderapi.scene

import de.bollwerk.engine.view.FxEvent
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.Loupe
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.PooledParticleSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** WP14a-Prüfungen auf echten Pixeln (java.awt-Senke): Lupen-Hintergrund und Blitz-Helligkeit. */
class PolishRasterTest {
    private val w = 1280
    private val h = 576

    private fun frame(scene: SyntheticScene, camera: Camera, overlay: OverlayState = OverlayState.NONE): AwtTarget {
        val target = AwtTarget(w, h, 1f)
        val r = SceneRenderer()
        r.bind(scene.tables, scene.map, target)
        r.render(target, scene.snap, 0f, camera, overlay, PooledParticleSystem(seed = 5))
        return target
    }

    private fun rgb(t: AwtTarget, x: Int, y: Int): Int = t.image.getRGB(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))

    private fun maxChannelDiff(a: Int, b: Int): Int =
        maxOf(abs(((a shr 16) and 255) - ((b shr 16) and 255)), abs(((a shr 8) and 255) - ((b shr 8) and 255)), abs((a and 255) - (b and 255)))

    private fun luma(t: AwtTarget): Double {
        var sum = 0.0
        val px = t.image.getRGB(0, 0, w, h, null, 0, w)
        for (p in px) sum += 0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)
        return sum / px.size
    }

    private fun camera(cx: Float, cy: Float, zoom: Float) = Camera(w.toFloat(), h.toFloat(), 1f).also { it.centerX = cx; it.centerY = cy; it.setZoom(zoom) }

    /**
     * Die Lupe zeigt den **echten** vergrößerten Ausschnitt (Stil-Bibel §7): Das Hintergrundbild innerhalb der Lupe
     * stimmt mit dem 2×-vergrößerten Hauptbild um den Finger überein (Horizont, Himmelsverlauf, Sonne, Berge). Der
     * Fingerpunkt liegt im freien Himmel, die Lupe daneben (Seitenplatzierung unter der HUD-Leiste).
     */
    @Test
    fun loupeBackgroundIsTheMagnifiedMainViewAroundTheFinger() {
        val sc = SyntheticScene().both()
        for (worldY in floatArrayOf(14f, 21f)) {
            val cam = camera(60f, 20f, 0.45f)
            val plain = frame(sc, cam)
            val wx = 60f; val wy = worldY
            val fx = cam.worldToScreenX(wx); val fy = cam.worldToScreenY(wy)
            val loupe = Loupe(fx, fy, wx, wy)
            val withLoupe = frame(sc, camera(60f, 20f, 0.45f), OverlayState(loupe = loupe))
            // Lupenmitte wie der Renderer: über die Overlay-Geometrie (Seitenplatzierung, wenn oben kein Platz ist)
            val lg = FloatArray(4)
            val c = SceneContext().also { it.setView(w.toFloat(), h.toFloat(), 1f, cam.scale, 0f, 0f) }
            OverlayPainter(c, DevicePainter(c, FxState(false), sc.map.terrain), SceneTexts()).loupeGeometry(fx, fy, loupe.radiusDp, loupe.offsetDp, 76f, lg)
            val cx = lg[0]; val cy = lg[1]
            var close = 0; var total = 0
            var worst = 0
            for (dx in intArrayOf(-30, -18, 18, 30)) for (dy in intArrayOf(-30, -18, 18, 30)) {
                // Lupenpixel (cx+dx, cy+dy) ↔ Hauptbild-Pixel am Fingerpunkt + (dx, dy) / 2
                val inLoupe = rgb(withLoupe, (cx + dx).toInt(), (cy + dy).toInt())
                val inMain = rgb(plain, (fx + dx / 2f).toInt(), (fy + dy / 2f).toInt())
                val d = maxChannelDiff(inLoupe, inMain)
                worst = maxOf(worst, d)
                total++; if (d <= 14) close++
            }
            assertTrue(close >= total - 3, "worldY=$worldY: loupe background matches the magnified main view at the finger ($close/$total close, worst diff $worst)")
        }
    }

    /** Eine Explosion hellt das Bild nur örtlich auf (kein weißer Vollbild-Blitz): mittlere Helligkeit steigt kaum. */
    @Test
    fun explosionFrameBrightnessRisesOnlyLocally() {
        for (reactor in booleanArrayOf(false, true)) {
            val quiet = SyntheticScene().both()
            val loud = SyntheticScene().both()
            val ev: FxEvent = if (reactor) FxEvent.ReactorDestroyed(1, 88f, 28f, 1) else loud.explosionEvent(88f, 26f, 30f, 600f)
            loud.snap.fx.add(ev); loud.snap.seq = 90
            val base = luma(frame(quiet, camera(88f, 28f, 1f)))
            val boom = luma(frame(loud, camera(88f, 28f, 1f)))
            assertTrue(boom >= base - 0.5, "an explosion never darkens the frame (reactor=$reactor)")
            assertTrue(boom - base <= 14.0, "mean brightness $base -> $boom (+${boom - base}) must stay a local flash, not a white-out (reactor=$reactor)")
        }
    }
}
