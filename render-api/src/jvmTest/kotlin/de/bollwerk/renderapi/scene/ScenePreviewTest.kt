package de.bollwerk.renderapi.scene

import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.tools.GhostBeam
import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.engine.tools.Trajectory
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.InteractionMode
import de.bollwerk.renderapi.Loupe
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.PooledParticleSystem
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Erzeugt Vorschau-PNGs unter `render-api/build/preview/` (zum Anschauen/Vergleichen mit
 * `docs/design/mockups/3-spiel-bauen.webp`). Die Senke ist die minimale java.awt-Implementierung aus
 * [AwtDrawSink]; der Test prüft nur, dass das Bild nicht leer ist.
 */
class ScenePreviewTest {
    private val outDir = File(System.getProperty("user.dir"), "build/preview").also { it.mkdirs() }

    private fun render(
        scene: SyntheticScene, w: Int, h: Int, density: Float, camera: (Camera) -> Unit, overlay: OverlayState = OverlayState.NONE,
        warmup: Float = 1.2f, particles: PooledParticleSystem = PooledParticleSystem(seed = 3), events: List<FxEvent> = emptyList(),
        name: String, overlayOf: ((Camera) -> OverlayState)? = null,
    ): AwtTarget {
        val target = AwtTarget(w, h, density)
        val r = SceneRenderer()
        r.bind(scene.tables, scene.map, target)
        val cam = Camera(w.toFloat(), h.toFloat(), density)
        camera(cam)
        cam.setBounds(-20f, -60f, 140f, 80f)
        val finalOverlay = overlayOf?.invoke(cam) ?: overlay
        // Fx einspeisen, dann ein paar Frames Zeit vergehen lassen (Partikel/Flammen laufen)
        scene.snap.fx.clear(); scene.snap.fx.addAll(events)
        var seq = scene.snap.seq
        val baseTick = scene.snap.tick
        val frames = (warmup * 60).toInt().coerceAtLeast(1)
        for (f in 0 until frames) {
            scene.snap.tick = baseTick + f
            if (f > 0) scene.snap.fx.clear()
            if (f == 0) scene.snap.seq = ++seq
            // Frames vor dem letzten überspringen das Zeichnen nicht: die Partikel müssen fortgeschrieben werden
            r.render(target, scene.snap, 0.5f, cam, if (f == frames - 1) finalOverlay else OverlayState.NONE, particles)
            if (f != frames - 1) clear(target)
        }
        File(outDir, "$name.png").also { ImageIO.write(target.image, "png", it) }
        return target
    }

    private fun clear(t: AwtTarget) {
        val g = t.image.createGraphics(); g.composite = java.awt.AlphaComposite.Clear; g.fillRect(0, 0, t.widthPx, t.heightPx); g.dispose()
    }

    /** Wie das Spiel-Mockup 3: links Bauen mit Ghost/Lupe, rechts brennende, beschädigte Festung. */
    private fun mockupScene(narrow: Boolean = true): SyntheticScene {
        val s = (if (narrow) SyntheticScene.narrow() else SyntheticScene()).both()
        val mx = s.width
        val snap = s.snap
        // Gegnerische Festung (Team 1): brennendes Dach, Schäden, Trümmer
        val enemyBeams = (0 until snap.beamCount).filter { snap.beamOwner[it] == 1 }
        // Schrägbalken oben (Holz) brennt
        var burning = 0
        for (b in enemyBeams) {
            val a = snap.beamA[b]; val e = snap.beamB[b]
            if (snap.beamMaterial[b] == SyntheticScene.WOOD && snap.nodeY[a] < 29f && snap.nodeY[e] < 29f && burning < 2) {
                s.damage(b, 0.55f, fire = 0.9f, fuel = 0.5f); burning++
            }
        }
        var k = 0
        for (b in enemyBeams) if (snap.beamMaterial[b] == SyntheticScene.WOOD && snap.beamFire01[b] == 0f && k < 6) {
            val hp = floatArrayOf(0.62f, 0.33f, 0.12f, 0.85f, 0.38f, 0.5f)[k]
            s.damage(b, hp); k++
        }
        // Zerbrochene Metallbalken (Scherenden) am Dach
        var m = 0
        for (b in enemyBeams) if (snap.beamMaterial[b] == SyntheticScene.METAL && m < 3) {
            if (m == 1) { s.damage(b, 0.3f); m++; continue }
            if (m == 2) { s.damage(b, 0.1f); m++; continue }
            m++
        }
        // Trümmer: zwei Holzhälften mit Splitterenden am Boden vor der Festung
        val ex = mx - 40f
        val d1 = s.node(ex - 0.5f, 33.2f, 1); val d2 = s.node(ex + 1.5f, 33.6f, 1)
        val d3 = s.node(ex + 0.2f, 33.9f, 1); val d4 = s.node(ex + 2.4f, 33.3f, 1)
        for (dn in intArrayOf(d1, d2, d3, d4)) snap.nodeFlags[dn] = snap.nodeFlags[dn] or de.bollwerk.engine.sim.NodeFlags.DEBRIS
        val h1 = s.beam(d1, d2, SyntheticScene.WOOD); val h2 = s.beam(d3, d4, SyntheticScene.WOOD)
        snap.beamFlags[h1] = snap.beamFlags[h1] or de.bollwerk.engine.sim.BeamFlags.DEBRIS or de.bollwerk.engine.sim.BeamFlags.JAG_B
        snap.beamFlags[h2] = snap.beamFlags[h2] or de.bollwerk.engine.sim.BeamFlags.DEBRIS or de.bollwerk.engine.sim.BeamFlags.JAG_A
        snap.nodeFlags[d1] = snap.nodeFlags[d1] or de.bollwerk.engine.sim.NodeFlags.DEBRIS
        // Projektil im Flug
        snap.projectileCount = 1
        snap.projX[0] = 46f; snap.projY[0] = 14f; snap.projPrevX[0] = 45.4f; snap.projPrevY[0] = 14.1f
        snap.projVx[0] = 22f; snap.projVy[0] = 6f; snap.projKind[0] = 2; snap.projFlags[0] = 1
        return s
    }

    @Test
    fun mockupBuildScreen() {
        val s = mockupScene()
        // Wie im Mockup: Ghost-Balken schräg von einem oberen Knoten der linken Festung nach vorn oben (4,6 m, 18 ⚙, Holz);
        // der Finger steht am Ghost-Ende, die Lupe darüber zeigt genau diese Stelle (Zeiger und Inhalt stimmen überein).
        val ghost = GhostBeam(33f, 25f, 37f, 22.7f, SyntheticScene.WOOD, true, null, 4.6f, 18f, snapNodeRef = 1)
        val ev = listOf(s.explosionEvent(s.width - 33f, 26f, 2.5f, 120f))
        // Dichte 2 (typisches Telefon-Landscape-Fenster 1280 × 576 dp), Standard-Zoom 24 dp/m: Chips und Lupe in Mockup-Größe
        val t = render(s, 2560, 1152, 2f, { it.setZoom(1f); it.centerX = 46f; it.centerY = 26f }, events = ev, warmup = 2.5f, name = "scene",
            overlayOf = { cam ->
                OverlayState(mode = InteractionMode.BUILD, tool = ToolSelection.Material(0), ghost = ghost,
                    loupe = Loupe(cam.worldToScreenX(ghost.bx), cam.worldToScreenY(ghost.by), ghost.bx, ghost.by))
            })
        assertTrue(nonEmpty(t))
        // der Finger (Lupen-Zeiger) liegt am Bildschirmpunkt des Ghost-Endes
        val cam = Camera(2560f, 1152f, 2f).also { it.setZoom(1f); it.centerX = 46f; it.centerY = 26f }
        assertTrue(cam.worldToScreenX(ghost.bx) in 0f..2560f && cam.worldToScreenY(ghost.by) in 0f..1152f)
    }

    @Test
    fun aimScreen() {
        val s = mockupScene()
        val snap = s.snap
        // Mörser des linken Spielers: Mündung aus der echten Gerätegeometrie, Flugbahn aus der echten Ballistik
        val slot = (0 until snap.deviceCount).first { s.tables.devices[snap.deviceType[it]].key == "mortar" && snap.deviceOwner[it] == 0 }
        val props = s.tables.devices[snap.deviceType[slot]]
        val bm = snap.deviceBeam[slot]
        val a = snap.beamA[bm]; val b = snap.beamB[bm]
        val geo = FloatArray(de.bollwerk.engine.math.DeviceGeometry.SIZE)
        val angle = 52f * 0.017453292f
        de.bollwerk.engine.math.DeviceGeometry.mountAt(snap.nodeX[a], snap.nodeY[a], snap.nodeX[b], snap.nodeY[b], snap.deviceT[slot],
            (snap.deviceFlags[slot] and de.bollwerk.engine.sim.DeviceFlags.SIDE_NEG) != 0, 0.26f, props.mountOffset, props.pivotOffset, props.barrelLength, angle, geo)
        snap.deviceAim[slot] = angle
        val pts = FloatArray(2 * 400)
        val n = de.bollwerk.engine.math.Ballistics.predict(geo[de.bollwerk.engine.math.DeviceGeometry.MUZZLE_X], geo[de.bollwerk.engine.math.DeviceGeometry.MUZZLE_Y],
            angle, 0.78f, 33f, snap.wind, de.bollwerk.engine.sim.SimConfig(), pts, 400, s.map.terrain, s.map)
        val overlay = OverlayState(mode = InteractionMode.AIM, trajectory = Trajectory(pts, n), impactX = pts[(n - 1) * 2], impactY = pts[(n - 1) * 2 + 1], selectedDeviceRef = slot.toLong())
        val t = render(s, 1920, 866, 1f, { it.setZoom(1.1f); it.centerX = 46f; it.centerY = 20f }, overlay, name = "scene-aim", warmup = 2.0f,
            events = listOf(s.explosionEvent(s.width - 33f, 26f, 2.5f, 120f)))
        assertTrue(nonEmpty(t))
        assertTrue(n > 10)
    }

    @Test
    fun detailHighDensity() {
        val s = mockupScene()
        val ghost = GhostBeam(30f, 28f, 34f, 31f, SyntheticScene.METAL, false, RejectReason.NOT_ENOUGH_METAL, 5f, 50f)
        val t = render(s, 1280, 720, 2f, { it.setZoom(1.6f); it.centerX = 30f; it.centerY = 29f }, OverlayState(ghost = ghost), name = "scene-detail")
        assertTrue(nonEmpty(t))
        val t2 = render(s, 1280, 720, 2f, { it.setZoom(1.6f); it.centerX = 90f; it.centerY = 27f }, name = "scene-detail-enemy",
            events = listOf(s.explosionEvent(88f, 26f, 2.5f, 120f)), warmup = 0.25f)
        assertTrue(nonEmpty(t2))
    }

    @Test
    fun materialsAndDamageGallery() {
        val s = SyntheticScene().materialGallery()
        val t = render(s, 1700, 800, 2f, { it.setZoom(0.92f); it.centerX = 14f; it.centerY = 11.5f }, warmup = 1.5f, name = "gallery-beams")
        assertTrue(nonEmpty(t))
    }

    @Test
    fun deviceGallery() {
        val s = SyntheticScene().deviceGallery()
        s.snap.deviceFlags[10] = s.snap.deviceFlags[10] // Kanone
        val laser = s.snap.deviceCount - 1
        s.snap.deviceFlags[laser] = s.snap.deviceFlags[laser] or de.bollwerk.engine.sim.DeviceFlags.FIRING_BEAM
        s.snap.deviceLaserEndX[laser] = 44f; s.snap.deviceLaserEndY[laser] = 22f
        val t = render(s, 2000, 560, 1f, { it.setZoom(1.35f); it.centerX = 27f; it.centerY = 17.5f }, warmup = 0.5f, name = "gallery-devices")
        assertTrue(nonEmpty(t))
        // Nahaufnahmen (2×) der beiden Hälften zum Vergleich mit dem Asset-Sheet
        val a = render(s, 1800, 700, 2f, { it.setZoom(1.5f); it.centerX = 12f; it.centerY = 17.2f }, warmup = 0.5f, name = "gallery-devices-a")
        val b = render(s, 1800, 700, 2f, { it.setZoom(1.5f); it.centerX = 40f; it.centerY = 17.2f }, warmup = 0.5f, name = "gallery-devices-b")
        assertTrue(nonEmpty(a) && nonEmpty(b))
    }

    @Test
    fun overviewFullMap() {
        val s = mockupScene(narrow = false)
        val t = render(s, 1920, 866, 1f, { it.fitRect(-5f, 8f, 125f, 56f) }, warmup = 1.0f, name = "scene-overview")
        assertTrue(nonEmpty(t))
    }

    /** Explosion in 5 Phasen (Blitz, Feuerball, Schockwelle, Trümmer, Rauch) + Brandspur, wie `9-effekte`. */
    @Test
    fun explosionSequenceStrip() {
        val times = floatArrayOf(0.0f, 0.1f, 0.22f, 0.6f, 1.6f, 3.5f)
        val w = 420; val h = 420
        val strip = java.awt.image.BufferedImage(w * times.size, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = strip.createGraphics()
        for ((k, tm) in times.withIndex()) {
            val s = SyntheticScene()
            val a = s.node(22f, 33.7f, 0, true); val b = s.node(30f, 33.7f, 0, true)
            s.beam(a, b, SyntheticScene.WOOD)
            val target = AwtTarget(w, h, 1f)
            val r = SceneRenderer()
            r.bind(s.tables, s.map, target)
            val cam = Camera(w.toFloat(), h.toFloat(), 1f).also { it.setZoom(2.6f); it.centerX = 26f; it.centerY = 31f }
            val p = PooledParticleSystem(seed = 11)
            s.snap.fx.add(FxEvent.Explosion(1, 26f, 33f, 2.5f, 120f, 2, SyntheticScene.WOOD, false, s.snap.beamUid[0], 0.5f))
            s.snap.seq = 1
            r.frameDtSeconds = 1f / 60f
            val frames = maxOf(1, (tm * 60).toInt() + 1)
            for (f in 0 until frames) {
                if (f == 1) s.snap.fx.clear()
                s.snap.tick = 100L + f
                r.render(target, s.snap, 0f, cam, OverlayState.NONE, p)
                if (f != frames - 1) clear(target)
            }
            g.drawImage(target.image, k * w, 0, null)
        }
        g.dispose()
        ImageIO.write(strip, "png", File(outDir, "fx-explosion.png"))
        assertTrue(strip.getRGB(10, 10) != 0)
    }

    /** Nahaufnahme Fundament, Erz, Gras, Brandspur und Staub. */
    @Test
    fun groundDetail() {
        val s = SyntheticScene()
        s.fort(0)
        s.snap.fx.add(FxEvent.Explosion(1, 28f, 34f, 2.2f, 90f, 3, -1, false, -1, -1f))
        s.snap.fx.add(FxEvent.DebrisLanded(1, 31f, 34f, 6f))
        val t = render(s, 1200, 600, 2f, { it.setZoom(2.2f); it.centerX = 27f; it.centerY = 33f }, warmup = 0.6f, name = "ground-detail", events = s.snap.fx.toList())
        assertTrue(nonEmpty(t))
    }

    private fun nonEmpty(t: AwtTarget): Boolean {
        var colored = 0
        for (y in 0 until t.heightPx step 16) for (x in 0 until t.widthPx step 16) if ((t.image.getRGB(x, y) ushr 24) != 0) colored++
        return colored > 100
    }
}
