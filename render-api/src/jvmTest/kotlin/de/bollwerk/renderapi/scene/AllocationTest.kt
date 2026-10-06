package de.bollwerk.renderapi.scene

import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.tools.GhostBeam
import de.bollwerk.engine.tools.GhostDevice
import de.bollwerk.engine.tools.Trajectory
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.DrawSink
import de.bollwerk.renderapi.ImageSpec
import de.bollwerk.renderapi.Loupe
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.PooledParticleSystem
import de.bollwerk.renderapi.RenderTarget
import de.bollwerk.renderapi.TextAlign
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/** Misst die Allokation des Render-Threads im Dauerbetrieb (Ziel: keine Objekte je Frame). */
class AllocationTest {
    private object NullSink : DrawSink {
        private var h = 1
        override fun save() {}
        override fun restore() {}
        override fun translate(dx: Float, dy: Float) {}
        override fun rotate(rad: Float) {}
        override fun scale(sx: Float, sy: Float) {}
        override fun clipRect(x: Float, y: Float, w: Float, h: Float) {}
        override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) {}
        override fun strokeRect(x: Float, y: Float, w: Float, h: Float, width: Float, color: Int) {}
        override fun fillPolygon(xy: FloatArray, count: Int, color: Int) {}
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, roundCap: Boolean) {}
        override fun dashedLine(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, dash: Float, gap: Float) {}
        override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int) {}
        override fun gradientRect(x: Float, y: Float, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray) {}
        override fun glow(cx: Float, cy: Float, r: Float, color: Int) {}
        override fun text(text: String, x: Float, y: Float, sizePx: Float, color: Int, align: TextAlign, bold: Boolean) {}
        override fun image(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) {}
        override fun registerImage(spec: ImageSpec): Int = h++
        override fun releaseImage(handle: Int) {}
    }

    private class Target : RenderTarget {
        override val widthPx = 1920
        override val heightPx = 866
        override val density = 1f
        override val sink: DrawSink = NullSink
    }

    @Test
    fun steadyStateRenderingAllocatesNothingNotable() {
        // Die Messung läuft ohne Escape-Analyse (siehe build.gradle.kts, `-XX:-DoEscapeAnalysis`): HotSpot C2 entfernt
        // sonst Boxen und Iteratoren (z. B. Function1<Float, Float>, IntProgression), die ART auf Android tatsächlich
        // allokiert. Jeder Durchlauf (frischer Renderer, eigene Aufwärmphase) muss unter der Schwelle liegen, nicht
        // nur der beste. Zwei Szenen: die Festungen mit allen Overlays, und die Galerien (alle Material-/Geräte-Zustände).
        for (gallery in listOf(false, true)) {
            measure(gallery) // verworfen: Klassenladen der JVM
            val runs = LongArray(3) { measure(gallery) }
            println("allocation runs gallery=$gallery (bytes/frame): ${runs.joinToString()}")
            for ((i, perFrame) in runs.withIndex()) assertTrue(perFrame < 32, "gallery=$gallery run $i: steady-state allocation per frame was $perFrame bytes (all runs: ${runs.joinToString()})")
        }
    }

    private fun measure(gallery: Boolean): Long {
        val sc = if (gallery) SyntheticScene().materialGallery().deviceGallery() else SyntheticScene.narrow().both()
        val snap = sc.snap
        if (gallery) {
            // beschädigte, im Bau befindliche und feuernde Geräte; Dehnung, Feuer, Türen, gebrochene Enden sind in der Galerie
            for (i in 0 until snap.deviceCount) if (i % 3 == 0) snap.deviceHp01[i] = 0.3f
            snap.deviceBuild01[2] = 0.4f; snap.deviceFlags[2] = snap.deviceFlags[2] or DeviceFlags.BUILDING
            val laser = snap.deviceCount - 1
            snap.deviceFlags[laser] = snap.deviceFlags[laser] or DeviceFlags.FIRING_BEAM
            snap.deviceLaserEndX[laser] = 60f; snap.deviceLaserEndY[laser] = 28f
        }
        // Feuer, Beschädigung, Projektil: die teuren Pfade
        var burning = 0
        for (i in 0 until snap.beamCount) if (snap.beamMaterial[i] == SyntheticScene.WOOD && burning < 3) { sc.damage(i, 0.3f, 0.9f, 0.5f); burning++ }
        snap.projectileCount = 1
        snap.projX[0] = 46f; snap.projY[0] = 14f; snap.projPrevX[0] = 45.4f; snap.projPrevY[0] = 14.1f
        snap.projVx[0] = 22f; snap.projVy[0] = 6f; snap.projKind[0] = 2; snap.projFlags[0] = 1
        val target = Target()
        val r = SceneRenderer()
        r.bind(sc.tables, sc.map, target)
        val cam = Camera(1920f, 866f, 1f).also {
            if (gallery) { it.setZoom(0.75f); it.centerX = 30f; it.centerY = 14f } else { it.setZoom(1.5f); it.centerX = 46f; it.centerY = 26f }
        }
        val particles = PooledParticleSystem(seed = 3)
        val pts = FloatArray(60)
        for (i in 0 until 30) { pts[i * 2] = 31f + i; pts[i * 2 + 1] = 27f - i * 0.4f + i * i * 0.02f }
        // Mörser (Splash-Radius → gestrichelter Einschlagring) und ein Balken als Auswahl
        val mortar = (0 until snap.deviceCount).first { sc.tables.devices[snap.deviceType[it]].key == "mortar" }
        val overlay = OverlayState(
            ghost = GhostBeam(30f, 34f, 34.6f, 34f, 0, true, null, 4.6f, 18f, snapNodeRef = 1),
            loupe = Loupe(300f, 500f, 34.6f, 34f), trajectory = Trajectory(pts, 30), impactX = 60f, impactY = 34f,
            selectedDeviceRef = mortar.toLong(), selectedBeamRef = 2L, strainView = gallery,
            ghostDevice = GhostDevice(30f, 34f, 0f, -1f, 0, true, null, 1L, 0.5f, false, 120f, 40f),
        )
        val invalid = overlay.copy(
            ghost = GhostBeam(30f, 34f, 38f, 30f, 1, false, RejectReason.TOO_LONG, 6.5f, 65f),
            ghostDevice = GhostDevice(36f, 34f, 0f, -1f, 1, false, RejectReason.NOT_ENOUGH_METAL, 1L, 0.5f, false, 120f, 40f),
        )
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        var next = 0
        fun frame() {
            val i = next++
            snap.tick = 600L + i
            if (i % 120 == 5) { snap.fx.clear(); snap.fx.add(sc.explosionEvent(56f, 26f)); snap.seq++ } else if (i % 120 == 6) snap.fx.clear()
            r.render(target, snap, (i % 4) / 4f, cam, if (i % 2 == 0) overlay else invalid, particles)
        }
        for (i in 0 until 600) frame() // warm-up (JIT, Textur-Erzeugung, Puffer wachsen)
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        val n = 1500
        for (i in 0 until n) frame()
        return (bean.getThreadAllocatedBytes(id) - before) / n
    }
}
