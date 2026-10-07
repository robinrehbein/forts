package de.bollwerk.renderapi.scene

import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.tools.GhostBeam
import de.bollwerk.engine.tools.GhostDevice
import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.engine.tools.Trajectory
import de.bollwerk.engine.tools.TrajectoryOutcome
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.GameRenderer
import de.bollwerk.renderapi.InteractionMode
import de.bollwerk.renderapi.Loupe
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.Palette
import de.bollwerk.renderapi.ParticleBuffers
import de.bollwerk.renderapi.ParticleSystem
import de.bollwerk.renderapi.PooledParticleSystem
import de.bollwerk.renderapi.SnapHighlight
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SceneRendererTest {
    private class Setup(val scene: SyntheticScene, val sink: RecordingDrawSink = RecordingDrawSink(), w: Int = 1280, h: Int = 576, d: Float = 1f) {
        val target = TestTarget(w, h, d, sink)
        val renderer = SceneRenderer()
        val camera = Camera(w.toFloat(), h.toFloat(), d).also { it.centerX = 60f; it.centerY = 28f; it.setZoom(0.45f) }
        val particles = PooledParticleSystem(seed = 5)
        init { renderer.bind(scene.tables, scene.map, target) }
        fun frame(alpha: Float = 0f, overlay: OverlayState = OverlayState.NONE, p: ParticleSystem = particles) {
            renderer.render(target, scene.snap, alpha, camera, overlay, p)
        }
    }

    private fun setup(): Setup = Setup(SyntheticScene().both())

    @Test
    fun implementsGameRendererWithExactName() {
        val r: GameRenderer = SceneRenderer()
        assertEquals("de.bollwerk.renderapi.scene.SceneRenderer", SceneRenderer::class.qualifiedName)
        assertTrue(r is SceneRenderer)
    }

    @Test
    fun bindRegistersTexturesAndReleaseFreesThem() {
        val s = setup()
        val registered = s.sink.count("registerImage")
        assertTrue(registered >= 13, "wood, metal, armour, door, grit, scorch, 3 joints, 4 clouds")
        s.renderer.release()
        assertEquals(registered, s.sink.released)
        assertTrue(s.sink.images.isEmpty())
    }

    @Test
    fun drawsAllElementTypesForSyntheticSnapshot() {
        val sc = SyntheticScene().both()
        val snap = sc.snap
        // Beschädigung, Feuer, Trümmer, Projektil, Laser, Bau-Fortschritt
        var burning = -1
        for (i in 0 until snap.beamCount) if (snap.beamMaterial[i] == SyntheticScene.WOOD && burning < 0) burning = i
        sc.damage(burning, 0.5f, fire = 0.9f, fuel = 0.4f)
        snap.projectileCount = 1
        snap.projX[0] = 50f; snap.projY[0] = 20f; snap.projPrevX[0] = 49f; snap.projPrevY[0] = 20.1f
        snap.projVx[0] = 30f; snap.projVy[0] = -3f; snap.projKind[0] = 2; snap.projFlags[0] = 1
        val s = Setup(sc)
        s.frame(0.5f)
        val k = s.sink
        assertEquals(0, k.depth, "save/restore balanced")
        assertTrue(k.minDepth >= 0)
        // Himmel (Verlauf), Sterne, Berge, Gelände, Balken (Texturen), Knoten, Geräte, Flaggen, Feuer
        assertTrue(k.count("gradientRect") > 5, "sky + haze + devices")
        assertTrue(k.count("fillCircle") > 100, "stars, bolts, cores")
        assertTrue(k.count("fillPolygon") > 200, "mountains, terrain, devices, flames")
        assertTrue(k.count("imageRegion") > 20, "textured beams")
        assertTrue(k.count("glow") >= 2, "sun halo + fire glow + ore")
        // Balken je Material: Holz, Metall, Panzer, Tür benutzen je ihre Textur
        val tex = k.images.values.associateBy { it.id.substringBefore('@') }
        for (name in listOf("wood", "metal", "armour", "door")) {
            val used = k.images.entries.filter { it.value.id.startsWith(name) }.sumOf { k.regionHandles[it.key] ?: 0 }
            assertTrue(used > 0, "$name beams must use their texture")
        }
        assertTrue(tex.containsKey("jointWood") && tex.containsKey("jointMetal") && tex.containsKey("jointAnchor"))
        for (name in listOf("jointWood", "jointMetal", "jointAnchor")) {
            val used = k.images.entries.filter { it.value.id.startsWith(name) }.sumOf { k.imageHandles[it.key] ?: 0 }
            assertTrue(used > 0, "$name drawn")
        }
        // Wolken (4 Varianten sichtbar), Fahnen in Teamfarben, Flammen in Token-Farben
        assertTrue(k.images.entries.filter { it.value.id.startsWith("cloud") }.any { (k.imageHandles[it.key] ?: 0) > 0 })
        assertContains(k.polygonColors, Palette.TEAM_BLUE)
        assertContains(k.polygonColors, Palette.TEAM_RED)
        for (c in listOf(Palette.FIRE_INNER, Palette.FIRE_MID, Palette.FIRE_OUTER)) assertContains(k.polygonColors, c)
        assertContains(k.fillColors, Palette.HAZARD, "Warnstreifen")
        assertContains(k.polygonColors, SceneContext.STRIPE_DARK, "Warnstreifen")
        // Seil (Linien in Faserfarbe), Projektil mit Schweif
        assertContains(k.fillColors, Palette.ROPE)
        // Fundament-Teamstreifen
        assertContains(k.fillColors, Palette.TEAM_BLUE)
        assertContains(k.fillColors, Palette.TEAM_RED)
        // Himmel in Palette-Tokens
        assertTrue(k.gradientColors.any { it.contentEquals(Palette.SKY_GRADIENT) })
    }

    @Test
    fun noOverlayMeansNoOverlayDrawing() {
        val s = setup()
        s.frame()
        val mines = (0 until s.scene.snap.deviceCount).count { s.scene.tables.devices[s.scene.snap.deviceType[it]].key == "mine" }
        assertEquals(mines, s.sink.count("dashedLine"), "only the drill rods are dashed: no ghost/selection/trajectory without overlay")
        assertEquals(0, s.sink.count("text"))
        assertEquals(0, s.sink.count("clipCircle"))
    }

    @Test
    fun ghostBeamDrawsDashedOutlineChipAndSnapRing() {
        val s = setup()
        s.frame()
        val baseRings = s.sink.count("strokeCircle")
        val baseDashes = s.sink.count("dashedLine")
        s.sink.reset()
        val ghost = GhostBeam(30f, 34f, 34.6f, 34f, SyntheticScene.WOOD, true, null, 4.6f, 18f, snapNodeRef = 3)
        s.frame(overlay = OverlayState(mode = InteractionMode.BUILD, tool = ToolSelection.Material(0), ghost = ghost))
        assertTrue(s.sink.count("dashedLine") >= baseDashes + 4, "dashed ghost outline")
        assertTrue(s.sink.count("strokeCircle") >= baseRings + 2, "snap ring (snapped) + pulse ring")
        assertContains(s.sink.texts, "4,6 m")
        assertContains(s.sink.texts, "18")
        assertContains(s.sink.fillColors, Palette.OK)
        assertEquals(0, s.sink.depth)
    }

    @Test
    fun invalidGhostShowsReasonInRed() {
        val s = setup()
        val ghost = GhostBeam(30f, 34f, 38f, 30f, SyntheticScene.METAL, false, RejectReason.TOO_LONG, 6.4f, 64f)
        s.frame(overlay = OverlayState(ghost = ghost))
        assertContains(s.sink.texts, "ZU LANG")
        assertContains(s.sink.fillColors, Palette.INVALID)
        assertFalse(s.sink.texts.contains("6,4 m"))
    }

    @Test
    fun customTextsAreUsed() {
        val s = setup()
        val r = SceneRenderer(SceneConfig(texts = object : SceneTexts() {
            override fun reason(r: RejectReason?): String = "TOO LONG"
        }))
        r.bind(s.scene.tables, s.scene.map, s.target)
        val ghost = GhostBeam(30f, 34f, 38f, 30f, SyntheticScene.METAL, false, RejectReason.TOO_LONG, 6.4f, 64f)
        r.render(s.target, s.scene.snap, 0f, s.camera, OverlayState(ghost = ghost), s.particles)
        assertContains(s.sink.texts, "TOO LONG")
    }

    @Test
    fun loupeRendersWorldAgainIntoClippedCircle() {
        val s = setup()
        s.frame()
        val plain = s.sink.count("imageRegion")
        s.sink.reset()
        val loupe = Loupe(screenX = 640f, screenY = 400f, worldX = 30f, worldY = 30f)
        s.frame(overlay = OverlayState(loupe = loupe))
        assertEquals(1, s.sink.count("clipCircle"))
        assertTrue(s.sink.count("imageRegion") > plain, "world drawn a second time (magnified)")
        assertTrue(s.sink.count("line") > 4, "crosshair")
        assertContains(s.sink.texts, "2×")
        assertEquals(0, s.sink.depth)
        // Vergrößerung 2×: der Skalierungsfaktor der Lupen-Welt ist doppelt so groß wie der der Kamera
        val mainScale = s.camera.scale
        assertTrue(s.sink.scales.any { kotlin.math.abs(it - mainScale * 2f) < 1e-3f }, "2x magnification")
    }

    @Test
    fun trajectoryDotsShrinkAndImpactCrosshairShowsSplash() {
        val s = setup()
        s.frame()
        val baseRings = s.sink.count("strokeCircle")
        val baseDots = s.sink.count("fillCircle")
        val baseDashes = s.sink.count("dashedLine")
        s.sink.reset()
        val pts = FloatArray(60)
        var n = 0
        var x = 31f; var y = 27f; var vx = 14f; var vy = -22f
        while (n < 30) { pts[n * 2] = x; pts[n * 2 + 1] = y; n++; x += vx * 0.12f; y += vy * 0.12f; vy += 9.81f * 0.12f }
        // Mörser (Slot 9 in der Synthese: erste Waffe mit Splash) auswählen
        val mortarSlot = (0 until s.scene.snap.deviceCount).first { s.scene.tables.devices[s.scene.snap.deviceType[it]].key == "mortar" }
        val ov = OverlayState(mode = InteractionMode.AIM, trajectory = Trajectory(pts, n), impactX = 80f, impactY = 34f, selectedDeviceRef = mortarSlot.toLong())
        s.frame(overlay = ov)
        assertTrue(s.sink.count("fillCircle") > baseDots + 15, "dots")
        assertTrue(s.sink.count("strokeCircle") >= baseRings + 2, "impact crosshair rings")
        assertTrue(s.sink.count("dashedLine") >= baseDashes + 4, "selection frame")
        assertTrue(s.sink.texts.any { it.startsWith("SCHEITEL") }, "apex label")
        assertContains(s.sink.fillColors, Palette.HAZARD)
        // Punkte werden zum Ende kleiner (Radien aus fillCircle, ohne Treffpunkt: nur Punkte)
        s.sink.reset()
        s.frame()
        val base = s.sink.circles.size
        val ov2 = OverlayState(trajectory = Trajectory(pts, n))
        s.sink.reset()
        s.frame(overlay = ov2)
        val dp = 1f / s.camera.scale
        val dots = s.sink.circles.drop(base).filter { it.color == Palette.WHITE || it.color == Palette.CREAM || it.color == Palette.HAZARD }
        assertTrue(dots.size > 15, "trajectory dots: ${dots.size}")
        for (k in 1 until dots.size) assertTrue(dots[k].r <= dots[k - 1].r + 1e-6f, "dot $k grows: ${dots[k - 1].r} -> ${dots[k].r}")
        assertTrue(dots.first().r > dots.last().r + 1.5f * dp, "dots shrink from ${dots.first().r} to ${dots.last().r}")
        assertTrue(dots.first().r <= 5.2f * dp + 1e-4f && dots.last().r >= 1.6f * dp - 1e-4f)
        s.sink.reset()
        s.frame(overlay = ov2)
        assertEquals(baseRings, s.sink.count("strokeCircle"), "no impact without impact point")
    }

    @Test
    fun aTrajectoryBlockedByTheOwnFortIsRedWithAWarningMarkerAtTheImpact() {
        val s = setup()
        s.frame()
        val baseCircles = s.sink.circles.size
        s.sink.reset()
        val pts = FloatArray(60)
        var n = 0
        var x = 31f; var y = 27f; var vx = 14f; var vy = -22f
        while (n < 30) { pts[n * 2] = x; pts[n * 2 + 1] = y; n++; x += vx * 0.12f; y += vy * 0.12f; vy += 9.81f * 0.12f }
        val hx = pts[(n - 1) * 2]; val hy = pts[(n - 1) * 2 + 1]
        val mortarSlot = (0 until s.scene.snap.deviceCount).first { s.scene.tables.devices[s.scene.snap.deviceType[it]].key == "mortar" }
        val blocked = OverlayState(
            mode = InteractionMode.AIM, trajectory = Trajectory(pts, n, TrajectoryOutcome.BLOCKED_OWN), impactX = hx, impactY = hy,
            selectedDeviceRef = mortarSlot.toLong(),
        )
        s.sink.recordPolygons = true
        s.frame(overlay = blocked)
        val dots = s.sink.circles.drop(baseCircles).filter { it.color == Palette.INVALID }
        assertTrue(dots.size > 15, "rote Bahnpunkte: ${dots.size}")
        // keine weißen/gelben Bahnpunkte, kein gelbes Fadenkreuz
        assertTrue(s.sink.circles.drop(baseCircles).none { it.color == Palette.HAZARD }, "kein Warngelb")
        assertTrue(Palette.INVALID in s.sink.lineColors, "rotes Kreuz am Einschlag")
        assertTrue(Palette.HAZARD !in s.sink.lineColors, "kein gelbes Fadenkreuz")
        // Warndreieck (rot) knapp über dem Einschlag
        val tri = s.sink.polygons.filter { it.color == Palette.INVALID && it.xy.size == 6 }
        assertTrue(tri.isNotEmpty(), "Warndreieck")
        val t = tri.last().xy
        val cx = (t[0] + t[2] + t[4]) / 3f; val cy = (t[1] + t[3] + t[5]) / 3f
        assertEquals(hx, cx, 0.6f)
        assertTrue(cy < hy && hy - cy < 4f, "über dem Einschlag: $cy vs $hy")
        // dieselbe Bahn ohne Eigentreffer: normales Fadenkreuz, keine roten Punkte
        s.sink.reset()
        s.frame(overlay = blocked.copy(trajectory = Trajectory(pts, n, TrajectoryOutcome.TERRAIN)))
        assertTrue(s.sink.circles.drop(baseCircles).none { it.color == Palette.INVALID })
        assertTrue(Palette.HAZARD in s.sink.lineColors)
    }

    @Test
    fun ghostDeviceAndSnapMarkers() {
        val s = setup()
        s.frame()
        val baseRings = s.sink.count("strokeCircle")
        s.sink.reset()
        val gd = GhostDevice(30f, 34f, 0f, -1f, 0, true, null, 1L, 0.5f, false, 120f, 40f)
        val ov = OverlayState(ghostDevice = gd, snaps = listOf(SnapHighlight(27f, 34f, 1L, true), SnapHighlight(30f, 31f, 2L, false)))
        s.frame(overlay = ov)
        assertTrue(s.sink.count("strokeCircle") >= baseRings + 4)
        assertTrue(s.sink.count("text") >= 2, "metal and energy cost")
        assertEquals(0, s.sink.depth)
    }

    @Test
    fun fxIsProcessedExactlyOncePerSeq() {
        val s = setup()
        val spy = SpyParticles(PooledParticleSystem(seed = 1))
        val snap = s.scene.snap
        snap.fx.clear()
        snap.fx.add(s.scene.explosionEvent(88f, 26f))
        snap.fx.add(FxEvent.Fired(1, 30f, 30f, snap.deviceUid[0], 2, 0.8f))
        snap.seq = 10
        s.frame(p = spy)
        assertEquals(2, spy.onFxCalls)
        assertEquals(10L, s.renderer.lastProcessedSeq)
        // gleicher Snapshot mehrfach gezeichnet (mehr Render- als Sim-Frames): nichts doppelt
        for (i in 0 until 5) s.frame(p = spy)
        assertEquals(2, spy.onFxCalls)
        // neuer seq mit neuen Ereignissen
        snap.fx.clear(); snap.fx.add(FxEvent.DebrisLanded(2, 40f, 34f, 4f))
        snap.seq = 11
        s.frame(p = spy)
        assertEquals(3, spy.onFxCalls)
        // gleiches seq nochmals, Liste unverändert → nicht erneut
        s.frame(p = spy)
        assertEquals(3, spy.onFxCalls)
        // Partikel wurden erzeugt und laufen (update je Frame)
        assertTrue(spy.inner.count > 0)
    }

    @Test
    fun particleFramesAdvanceWithSimTime() {
        val s = setup()
        val snap = s.scene.snap
        snap.fx.clear(); snap.fx.add(s.scene.explosionEvent(88f, 26f))
        snap.seq = 3
        s.frame()
        val n0 = s.particles.count
        assertTrue(n0 > 10)
        snap.fx.clear()
        for (i in 1..30) { snap.tick += 1; s.frame() }
        // Feuerball (0,3 s) ist nach 0,5 s Sim-Zeit abgelaufen, Rauch bleibt
        val kinds = (0 until s.particles.count).map { s.particles.buffers.kind[it] }.toSet()
        assertFalse(de.bollwerk.renderapi.ParticleKind.FIREBALL.ordinal in kinds)
        assertTrue(de.bollwerk.renderapi.ParticleKind.SMOKE.ordinal in kinds)
        // frameDtSeconds überschreibt die Ableitung aus der Sim-Zeit (Plattform-Frame-Zeit), begrenzt auf 0,1 s
        fun oldestSmoke(): Float {
            var m = -1f
            for (i in 0 until s.particles.count) if (s.particles.buffers.kind[i] == de.bollwerk.renderapi.ParticleKind.SMOKE.ordinal && s.particles.buffers.life[i] > 2f) m = maxOf(m, s.particles.buffers.age[i])
            return m
        }
        val a0 = oldestSmoke()
        assertTrue(a0 >= 0f)
        s.renderer.frameDtSeconds = 0.016f
        s.frame()
        assertEquals(a0 + 0.016f, oldestSmoke(), 1e-4f, "real frame time advances particles")
        val a1 = oldestSmoke()
        s.renderer.frameDtSeconds = 10f
        s.frame()
        assertEquals(a1 + 0.1f, oldestSmoke(), 1e-4f, "clamped to 0.1 s")
        val a2 = oldestSmoke()
        s.renderer.frameDtSeconds = 0f
        s.frame()
        assertEquals(a2, oldestSmoke(), 1e-6f, "dt 0 = frozen")
        s.renderer.frameDtSeconds = -3f
        s.frame()
        assertEquals(a2, oldestSmoke(), 1e-6f, "negative dt ignored")
    }

    @Test
    fun timeJumpBackResetsVisualStateEvenWithPlatformFrameTime() {
        val sc = SyntheticScene().both()
        val s = Setup(sc)
        s.renderer.frameDtSeconds = 1f / 60f
        sc.snap.tick = 900
        sc.snap.fx.clear(); sc.snap.fx.add(sc.explosionEvent(88f, 34f, 2.5f, 120f)); sc.snap.seq = 20
        s.frame()
        for (i in 0 until 5) { sc.snap.fx.clear(); sc.snap.tick += 1; s.frame() }
        assertTrue(s.particles.count > 20, "explosion particles of the first run")
        assertTrue(s.renderer.shakeXpx != 0f || s.renderer.shakeYpx != 0f || s.particles.count > 0)
        val scorch = s.sink.images.entries.first { it.value.id == "scorch" }.key
        s.sink.reset()
        s.frame()
        assertTrue((s.sink.imageHandles[scorch] ?: 0) > 0, "ground scorch decal of the first run")
        // Replay-Neustart: Tick springt weit zurück, `seq` beginnt von vorn
        sc.snap.tick = 60; sc.snap.seq = 1; sc.snap.fx.clear()
        s.sink.reset()
        s.frame()
        assertEquals(0, s.particles.count, "particles of the previous run are gone")
        assertEquals(0, s.sink.imageHandles[scorch] ?: 0, "decals of the previous run are gone")
        assertEquals(0f, s.renderer.shakeXpx); assertEquals(0f, s.renderer.shakeYpx)
    }

    @Test
    fun reusedParticleSystemIsClearedOnTheNextBind() {
        val sc = SyntheticScene().both()
        val s = Setup(sc)
        s.particles.emit(de.bollwerk.renderapi.ParticleKind.SMOKE, 40f, 30f, 0f, -1f, 5f, 0.5f)
        s.particles.emit(de.bollwerk.renderapi.ParticleKind.CHUNK, 40f, 30f, 1f, -1f, 5f, 0.2f)
        assertEquals(2, s.particles.count)
        // neue Partie mit demselben Partikelsystem der Plattform
        val r2 = SceneRenderer()
        r2.bind(sc.tables, sc.map, s.target)
        r2.render(s.target, sc.snap, 0f, s.camera, OverlayState.NONE, s.particles)
        assertEquals(0, s.particles.count, "smoke/debris of the previous match must not carry over")
    }

    @Test
    fun burningBeamsEmitSmokeAndEmbers() {
        val sc = SyntheticScene().both()
        val b = (0 until sc.snap.beamCount).first { sc.snap.beamMaterial[it] == SyntheticScene.WOOD }
        sc.damage(b, 0.8f, fire = 1f, fuel = 0.8f)
        val s = Setup(sc)
        s.renderer.frameDtSeconds = 1f / 60f
        for (i in 0 until 300) s.frame()
        val kinds = (0 until s.particles.count).map { s.particles.buffers.kind[it] }.toSet()
        assertTrue(de.bollwerk.renderapi.ParticleKind.SMOKE.ordinal in kinds, "Rauchsäule")
        assertTrue(de.bollwerk.renderapi.ParticleKind.EMBER.ordinal in kinds, "aufsteigende Funken")
        assertTrue(s.particles.count <= 600)
    }

    @Test
    fun cameraTransformsPositionTheWorld() {
        val s = setup()
        s.camera.setZoom(1f)
        s.camera.centerX = 60f; s.camera.centerY = 30f
        s.frame()
        val scale = s.camera.scale
        assertEquals(24f, scale, 1e-4f)
        val ox = 1280f / 2f - 60f * 24f
        val oy = 576f / 2f - 30f * 24f
        assertTrue(s.sink.translations.any { kotlin.math.abs(it[0] - ox) < 1e-2f && kotlin.math.abs(it[1] - oy) < 1e-2f }, "world origin at camera-derived offset")
        assertTrue(s.sink.scales.any { kotlin.math.abs(it - 24f) < 1e-4f })
        // Verschieben und Zoomen ändert die Abbildung
        s.sink.reset()
        s.camera.centerX = 70f
        s.camera.setZoom(2f)
        s.frame()
        val ox2 = 1280f / 2f - 70f * 48f
        val oy2 = 576f / 2f - 30f * 48f
        assertTrue(s.sink.translations.any { kotlin.math.abs(it[0] - ox2) < 1e-2f && kotlin.math.abs(it[1] - oy2) < 1e-2f })
        assertTrue(s.sink.scales.any { kotlin.math.abs(it - 48f) < 1e-4f })
    }

    @Test
    fun highDensityScalesWorldAndTextures() {
        val sc = SyntheticScene().both()
        val s = Setup(sc, d = 2f, w = 1920, h = 864)
        s.camera.setZoom(1f); s.camera.centerX = 32f; s.camera.centerY = 30f
        s.frame()
        assertTrue(s.sink.scales.any { kotlin.math.abs(it - 48f) < 1e-3f }, "24 dp/m * density 2")
        val woods = s.sink.images.values.filter { it.id.startsWith("wood") }
        assertEquals(SceneContext.MIP_TPM.size, woods.size, "one wood texture per mip level")
        for (tpm in SceneContext.MIP_TPM) assertTrue(woods.any { it.width == 4 * tpm }, "wood at $tpm texels/m")
        // gezeichnet wird die Stufe, die die Bildschirmdichte (48 px/m) abdeckt: kleinste Stufe >= 0,92 * 48
        assertEquals(listOf(4 * 54), usedWidths(s.sink, "wood"))
    }

    /** Breiten der Texturen mit Namensanfang [prefix], die im Frame tatsächlich gezeichnet wurden. */
    private fun usedWidths(sink: RecordingDrawSink, prefix: String): List<Int> =
        sink.images.entries.filter { it.value.id.startsWith(prefix) && ((sink.regionHandles[it.key] ?: 0) + (sink.imageHandles[it.key] ?: 0)) > 0 }
            .map { it.value.width }.sorted()

    @Test
    fun textureMipFollowsTheScreenScaleSoBeamsDoNotShimmer() {
        // Je Zoomstufe wird die Textur mit der nächsten ausreichenden Texeldichte gezeichnet (nie > ~1,1× herunterskaliert)
        for (zoom in floatArrayOf(0.5f, 1f, 1.5f, 2.5f)) {
            val s = Setup(SyntheticScene().both(), d = 1f, w = 1280, h = 576)
            s.camera.setZoom(zoom); s.camera.centerX = 32f; s.camera.centerY = 30f
            s.frame()
            val px = 24f * zoom
            val used = usedWidths(s.sink, "wood")
            assertEquals(1, used.size, "zoom $zoom uses one wood level: $used")
            val tpm = used[0] / 4
            val expected = SceneContext.MIP_TPM.firstOrNull { it >= px * 0.92f } ?: SceneContext.MIP_TPM.last()
            assertEquals(expected, tpm, "zoom $zoom (px/m = $px)")
            assertTrue(tpm <= px * 1.6f + 24f, "no needlessly large level: $tpm for $px px/m")
        }
    }

    @Test
    fun jointPlatesAreFortyTwoCentimetres() {
        // Stil-Bibel §4: Knotendurchmesser 0,42 m; das Sprite trägt Platte + Kontur, nicht mehr als ~0,6 m
        for (tpm in SceneContext.MIP_TPM) {
            val n = ProceduralTextures.jointSize(tpm)
            val world = n.toFloat() / tpm
            assertTrue(world >= ProceduralTextures.JOINT_D + 0.02f && world < 0.9f, "joint sprite $world m at $tpm")
            // Plattenbreite in der Mitte (Zeile cy): opake Texel * 1/tpm ≈ 0,42 m
            val t = ProceduralTextures.jointWood(tpm)
            var cnt = 0
            val row = n / 2
            for (x in 0 until n) if ((t.argb[row * n + x] ushr 24) > 128) cnt++
            val w = cnt.toFloat() / tpm
            assertTrue(w >= ProceduralTextures.JOINT_D - 0.02f && w <= ProceduralTextures.JOINT_D + (2f * maxOf(1f, 1.2f * tpm / 72f) + 1.5f) / tpm + 0.03f, "wood plate width $w at $tpm")
        }
    }

    @Test
    fun nodesAreInterpolatedWithAlpha() {
        val sc = SyntheticScene()
        val a = sc.node(10f, 20f, 0); val b = sc.node(14f, 20f, 0)
        sc.beam(a, b, SyntheticScene.WOOD)
        sc.snap.nodePrevX[a] = 8f; sc.snap.nodePrevY[a] = 18f
        sc.snap.nodePrevX[b] = 12f; sc.snap.nodePrevY[b] = 18f
        val s = Setup(sc)
        s.camera.setZoom(1f); s.camera.centerX = 12f; s.camera.centerY = 19f
        s.frame(alpha = 0.25f)
        assertTrue(s.sink.translations.any { kotlin.math.abs(it[0] - 8.5f) < 1e-3f && kotlin.math.abs(it[1] - 18.5f) < 1e-3f }, "beam start = lerp(prev, cur, 0.25)")
        s.sink.reset()
        s.frame(alpha = 1f)
        assertTrue(s.sink.translations.any { kotlin.math.abs(it[0] - 10f) < 1e-3f && kotlin.math.abs(it[1] - 20f) < 1e-3f })
        s.sink.reset()
        s.frame(alpha = 0f)
        assertTrue(s.sink.translations.any { kotlin.math.abs(it[0] - 8f) < 1e-3f && kotlin.math.abs(it[1] - 18f) < 1e-3f })
    }

    @Test
    fun staticLayersAreCachedUntilTheCameraMoves() {
        val sink = RecordingDrawSink(cacheLayers = true)
        val s = Setup(SyntheticScene().both(), sink)
        s.frame()
        assertEquals(2, sink.layerBegins, "sky + terrain recorded")
        assertEquals(listOf(SceneLayers.SKY, SceneLayers.TERRAIN), sink.layerOrder)
        val firstTotal = sink.totalCalls()
        val firstPolygons = sink.count("fillPolygon")
        sink.reset()
        s.frame()
        assertEquals(0, sink.layerBegins, "cached: not redrawn")
        assertEquals(2, sink.layerHits)
        assertTrue(sink.count("fillPolygon") < firstPolygons, "mountains/terrain polygons skipped when cached")
        assertTrue(sink.totalCalls() < firstTotal)
        // Kamera bewegt: Ebenen neu
        sink.reset()
        s.camera.centerX += 5f
        s.frame()
        assertEquals(2, sink.layerBegins)
        // Shake ändert den Schlüssel nicht
        val key = s.renderer.lastLayerKey
        sink.reset()
        s.scene.snap.fx.clear(); s.scene.snap.fx.add(s.scene.explosionEvent(88f, 26f, 3f, 200f)); s.scene.snap.seq += 1
        s.frame()
        assertEquals(key, s.renderer.lastLayerKey)
        assertEquals(0, sink.layerBegins)
        // Zoom: neu
        sink.reset()
        s.camera.setZoom(1.5f)
        s.frame()
        assertEquals(2, sink.layerBegins)
    }

    @Test
    fun explosionShakesTheViewButNeverTheCamera() {
        val s = setup()
        s.frame()
        assertEquals(0f, s.renderer.shakeXpx)
        val snap = s.scene.snap
        snap.fx.clear(); snap.fx.add(s.scene.explosionEvent(88f, 26f, 3f, 200f)); snap.seq += 1
        s.frame()
        assertTrue(s.renderer.shakeXpx != 0f || s.renderer.shakeYpx != 0f, "shake after explosion")
        assertTrue(kotlin.math.abs(s.renderer.shakeXpx) <= 6f && kotlin.math.abs(s.renderer.shakeYpx) <= 6f, "max 6 dp at density 1")
        assertEquals(0f, s.camera.shakeX); assertEquals(0f, s.camera.shakeY)
        snap.fx.clear()
        s.renderer.frameDtSeconds = 0.1f
        for (i in 0 until 30) s.frame()
        assertEquals(0f, s.renderer.shakeXpx, "decays to zero")
    }

    @Test
    fun shakeScalesWithDensityAndStaysWithinSixDp() {
        fun firstShake(d: Float): FloatArray {
            val sc = SyntheticScene().both()
            val s = Setup(sc, d = d, w = (1280 * d).toInt(), h = (576 * d).toInt())
            s.frame()
            sc.snap.fx.clear(); sc.snap.fx.add(sc.explosionEvent(88f, 26f, 3f, 400f)); sc.snap.seq += 1
            var maxAbs = 0f
            val first = FloatArray(2)
            for (i in 0 until 8) {
                s.frame()
                if (i == 0) { first[0] = s.renderer.shakeXpx; first[1] = s.renderer.shakeYpx }
                maxAbs = maxOf(maxAbs, kotlin.math.abs(s.renderer.shakeXpx), kotlin.math.abs(s.renderer.shakeYpx))
                sc.snap.fx.clear()
            }
            assertTrue(maxAbs <= 6f * d + 1e-3f, "max 6 dp at density $d: $maxAbs px")
            return first
        }
        val one = firstShake(1f)
        val two = firstShake(2f)
        assertTrue(one[0] != 0f && one[1] != 0f)
        assertEquals(one[0] * 2f, two[0], 1e-3f, "shake in px scales with density")
        assertEquals(one[1] * 2f, two[1], 1e-3f)
        assertTrue(kotlin.math.abs(two[0]) <= 12f && kotlin.math.abs(two[1]) <= 12f)
    }

    @Test
    fun reducedMotionDampsShakeAndHasNoScreenFlash() {
        val sc = SyntheticScene().both()
        val sink = RecordingDrawSink()
        val target = TestTarget(1280, 576, 1f, sink)
        val r = SceneRenderer(SceneConfig(reducedMotion = true))
        r.bind(sc.tables, sc.map, target)
        val cam = Camera(1280f, 576f, 1f).also { it.centerX = 60f; it.centerY = 28f }
        val p = PooledParticleSystem(reducedMotion = true)
        sc.snap.fx.add(sc.explosionEvent(88f, 26f, 3f, 400f)); sc.snap.seq = 77
        var maxShake = 0f
        for (i in 0 until 10) { r.render(target, sc.snap, 0f, cam, OverlayState.NONE, p); maxShake = maxOf(maxShake, kotlin.math.abs(r.shakeXpx), kotlin.math.abs(r.shakeYpx)) }
        assertTrue(maxShake <= 6f * 0.25f + 1e-3f, "reduced: $maxShake")
        assertTrue(p.count <= 260)
    }

    @Test
    fun doorsDifferBetweenOpenAndClosed() {
        fun record(open: Boolean): RecordingDrawSink {
            val sc = SyntheticScene()
            val a = sc.node(10f, 20f, 0); val b = sc.node(10f, 23f, 0)
            val d = sc.beam(a, b, SyntheticScene.DOOR)
            if (open) sc.snap.beamFlags[d] = sc.snap.beamFlags[d] or BeamFlags.DOOR_OPEN
            val s = Setup(sc)
            s.camera.centerX = 10f; s.camera.centerY = 22f
            s.frame()
            return s.sink
        }
        val closed = record(false)
        val open = record(true)
        assertNotEquals(closed.count("fillRect"), open.count("fillRect"))
        assertContains(open.fillColors, Palette.withAlpha(Palette.INK, 0.878f), "dark opening")
        assertTrue(open.count("scale") > closed.count("scale"), "swung panel is foreshortened")
    }

    @Test
    fun damageStagesAddDetail() {
        fun record(hp: Float, wood: Boolean = true): RecordingDrawSink {
            val sc = SyntheticScene()
            val a = sc.node(10f, 20f, 0); val b = sc.node(14f, 20f, 0)
            val i = sc.beam(a, b, if (wood) SyntheticScene.WOOD else SyntheticScene.METAL)
            sc.snap.beamHp01[i] = hp
            val s = Setup(sc)
            s.camera.centerX = 12f; s.camera.centerY = 20f
            s.frame()
            return s.sink
        }
        fun critical(k: RecordingDrawSink): Int =
            k.strokeRectColors.count { (it and 0xFFFFFF) == (Palette.CRITICAL and 0xFFFFFF) } + k.lineColors.count { (it and 0xFFFFFF) == (Palette.CRITICAL and 0xFFFFFF) }
        val intact = record(1f).totalCalls(); val cracks = record(0.6f).totalCalls(); val splinter = record(0.3f).totalCalls()
        assertTrue(cracks > intact, "hairline cracks")
        assertTrue(splinter > cracks, "notches and darker")
        // Stufe 3 (< 15 %): rote, pulsierende Kontur in Palette.CRITICAL; Stufen darunter haben sie nicht
        assertEquals(0, critical(record(0.3f)), "no red pulse at stage 2")
        assertTrue(critical(record(0.1f)) >= 1, "red pulse at stage 3 (wood)")
        assertTrue(record(0.1f).totalCalls() > splinter)
        assertTrue(record(0.6f, wood = false).totalCalls() > record(1f, wood = false).totalCalls(), "dents and scratches on metal")
        assertTrue(record(0.3f, wood = false).totalCalls() > record(1f, wood = false).totalCalls(), "bent metal")
        // Verbogenes Metall: Kontur läuft entlang der gebogenen Kanten (Linien je Segment, kein gerades Rechteck)
        assertEquals(0, critical(record(0.3f, wood = false)))
        val bent = record(0.1f, wood = false)
        assertTrue(bent.lineColors.count { (it and 0xFFFFFF) == (Palette.CRITICAL and 0xFFFFFF) } >= 12, "outline follows the bent body")
        assertEquals(0, bent.strokeRectColors.count { (it and 0xFFFFFF) == (Palette.CRITICAL and 0xFFFFFF) }, "no straight rectangle on a bent beam")
    }

    @Test
    fun brokenEndsAndWeaponsAndLaserAreDrawn() {
        val sc = SyntheticScene().deviceGallery()
        val laser = sc.snap.deviceCount - 1
        sc.snap.deviceFlags[laser] = sc.snap.deviceFlags[laser] or DeviceFlags.FIRING_BEAM
        sc.snap.deviceLaserEndX[laser] = 40f; sc.snap.deviceLaserEndY[laser] = 25f
        val s = Setup(sc)
        s.camera.centerX = 27f; s.camera.centerY = 18f; s.camera.setZoom(0.8f)
        s.frame()
        assertContains(s.sink.fillColors, Palette.ENERGY_HI, "laser core")
        assertEquals(0, s.sink.depth)
        // jedes Gerät erzeugt Zeichenaufrufe: Anzahl Polygone wächst mit der Gerätezahl
        assertTrue(s.sink.count("fillPolygon") > 60)
    }

    @Test
    fun rebindWithDifferentDensityRegeneratesTextures() {
        val sc = SyntheticScene().both()
        val sink = RecordingDrawSink()
        val r = SceneRenderer()
        val t1 = TestTarget(1280, 576, 1f, sink)
        r.bind(sc.tables, sc.map, t1)
        val cam = Camera(1280f, 576f, 1f).also { it.centerX = 60f; it.centerY = 28f }
        val p = PooledParticleSystem()
        r.render(t1, sc.snap, 0f, cam, OverlayState.NONE, p)
        val before = sink.count("registerImage")
        val t2 = TestTarget(1280, 576, 2f, sink)
        cam.setViewport(1280f, 576f, 2f)
        r.render(t2, sc.snap, 0f, cam, OverlayState.NONE, p)
        assertTrue(sink.count("registerImage") > before)
        assertTrue(sink.released >= 13)
    }

    @Test
    fun hugeSnapshotsDoNotThrow() {
        val sc = SyntheticScene()
        // Knoten/Balken-Grenzfälle: Länge 0, Seil mit Durchhang, verwaiste Geräte
        val a = sc.node(5f, 5f, 0); val b = sc.node(5f, 5f, 0)
        sc.beam(a, b, SyntheticScene.WOOD)
        sc.beam(a, b, SyntheticScene.ROPE)
        val s = Setup(sc)
        s.frame(alpha = 0.7f)
        s.scene.snap.beamCount = 0; s.scene.snap.nodeCount = 0
        s.frame()
        assertEquals(0, s.sink.depth)
    }

    @Test
    fun staleSelectionsAreNotDrawn() {
        val sc = SyntheticScene().both()
        val snap = sc.snap
        val mortar = (0 until snap.deviceCount).first { sc.tables.devices[snap.deviceType[it]].key == "mortar" }
        val beam = (0 until snap.beamCount).first { snap.beamMaterial[it] == SyntheticScene.WOOD }
        val s = Setup(sc)
        s.frame()
        val base = s.sink.count("dashedLine")
        fun dashes(ov: OverlayState): Int { s.sink.reset(); s.frame(overlay = ov); return s.sink.count("dashedLine") - base }
        val devSel = OverlayState(selectedDeviceRef = mortar.toLong())
        val beamSel = OverlayState(selectedBeamRef = beam.toLong())
        assertTrue(dashes(devSel) >= 4, "selection frame of the live device")
        assertTrue(dashes(beamSel) >= 1, "selection line of the live beam")
        // Objekt stirbt: keine Auswahl mehr
        snap.deviceFlags[mortar] = snap.deviceFlags[mortar] and DeviceFlags.ALIVE.inv()
        snap.beamFlags[beam] = snap.beamFlags[beam] and BeamFlags.ALIVE.inv()
        s.sink.reset(); s.frame()
        val baseDead = s.sink.count("dashedLine")
        assertEquals(baseDead, dashes(devSel) + base, "dead device: no selection frame")
        assertEquals(baseDead, dashes(beamSel) + base, "dead beam: no selection line")
        // Slot wird mit einem neuen Objekt (neue uid) belegt: die alte Auswahl springt nicht darauf über
        snap.deviceFlags[mortar] = snap.deviceFlags[mortar] or DeviceFlags.ALIVE; snap.deviceUid[mortar] += 1000
        snap.beamFlags[beam] = snap.beamFlags[beam] or BeamFlags.ALIVE; snap.beamUid[beam] += 1000
        s.sink.reset(); s.frame()
        val baseReused = s.sink.count("dashedLine")
        assertEquals(baseReused, dashes(devSel) + base, "reused device slot is not selected")
        assertEquals(baseReused, dashes(beamSel) + base, "reused beam slot is not selected")
        // Splash-Radius und Einschlag-Ring kommen ebenfalls nicht vom wiederverwendeten Slot
        val pts = FloatArray(20) { if (it % 2 == 0) 31f + it else 27f }
        val withImpact = devSel.copy(trajectory = Trajectory(pts, 10), impactX = 80f, impactY = 34f)
        s.sink.reset(); s.frame(overlay = withImpact)
        val dashedWithStaleSplash = s.sink.count("dashedLine")
        s.sink.reset(); s.frame(overlay = withImpact.copy(selectedDeviceRef = -1))
        assertEquals(s.sink.count("dashedLine"), dashedWithStaleSplash, "no splash ring for a stale device")
    }

    @Test
    fun chipWidthsComeFromTheSinkMeasurement() {
        fun panelWidth(factor: Float, reason: RejectReason): Pair<Float, RecordingDrawSink> {
            val s = setup()
            s.sink.measureFactor = factor
            s.frame(overlay = OverlayState(ghost = GhostBeam(30f, 34f, 38f, 30f, SyntheticScene.METAL, false, reason, 6.4f, 64f)))
            val panel = s.sink.polyHeads.last { it.color == Palette.PANEL }
            return Pair(panel.maxX - panel.minX, s.sink)
        }
        val (narrow, k1) = panelWidth(0.4f, RejectReason.OUT_OF_BUILD_ZONE)
        val (wide, k2) = panelWidth(0.8f, RejectReason.OUT_OF_BUILD_ZONE)
        assertContains(k1.measured, "AUSSERHALB DER BAUZONE")
        assertTrue(wide > narrow + 100f, "wider text, wider chip: $narrow vs $wide")
        // der Chip umschließt den gemessenen Text samt Innenrand (14 dp links und rechts)
        val textW = "AUSSERHALB DER BAUZONE".length * 17f * 0.8f
        assertTrue(wide >= textW + 28f - 1e-2f, "chip $wide must fit the text $textW plus padding")
        assertTrue(k2.measured.isNotEmpty())
        // gültiger Chip misst Länge und Kosten
        val s = setup()
        s.frame(overlay = OverlayState(ghost = GhostBeam(30f, 34f, 34.6f, 34f, SyntheticScene.WOOD, true, null, 4.6f, 18f)))
        assertContains(s.sink.measured, "4,6 m"); assertContains(s.sink.measured, "18")
        // gecached: derselbe Chip im nächsten Frame misst nicht erneut
        s.sink.measured.clear()
        s.frame(overlay = OverlayState(ghost = GhostBeam(30f, 34f, 34.6f, 34f, SyntheticScene.WOOD, true, null, 4.6f, 18f)))
        assertEquals(0, s.sink.measured.size, "widths are cached per text")
    }

    @Test
    fun jointShadowIsDrawnUnrotatedTwoDpDownRight() {
        val sc = SyntheticScene()
        val a = sc.node(10f, 20f, 0, anchored = true); val b = sc.node(14f, 21f, 0)
        sc.beam(a, b, SyntheticScene.WOOD)
        val s = Setup(sc)
        s.camera.centerX = 12f; s.camera.centerY = 20f; s.camera.setZoom(1f)
        s.frame()
        val dp = 1f / s.camera.scale
        val shadow = Palette.withAlpha(Palette.BLACK, 0.3f)
        // Fundament (Scheibe): Schatten 2 dp nach unten rechts
        assertTrue(s.sink.circles.any { it.color == shadow && kotlin.math.abs(it.x - (10f + 2f * dp)) < 1e-4f && kotlin.math.abs(it.y - (20f + 2f * dp)) < 1e-4f }, "anchor shadow")
        // Holz-Knoten am schrägen Balken: Schatten als gedrehtes Rechteck, verschoben um (+2 dp, +2 dp) in Weltrichtung
        assertTrue(s.sink.polyHeads.any { it.color == shadow }, "wood joint shadow polygon")
        assertTrue(s.sink.translations.any { kotlin.math.abs(it[0] - (14f + 2f * dp)) < 1e-4f && kotlin.math.abs(it[1] - (21f + 2f * dp)) < 1e-4f }, "shadow offset is down-right in world space")
    }

    @Test
    fun flagIgnoresForwardStubs() {
        fun flagHeads(stub: Boolean): List<RecordingDrawSink.PolyHead> {
            val sc = SyntheticScene().both()
            if (stub) {
                // vorgeschobener, nicht als Trümmer markierter Balken weit vor der Festung (Spieler 0, höher als alles)
                val a = sc.node(44f, 20f, 0); val b = sc.node(46f, 14f, 0)
                sc.beam(a, b, SyntheticScene.WOOD, 0)
            }
            val s = Setup(sc)
            s.frame()
            // Fahnentuch: einziges Polygon in Teamfarbe mit 18 Punkten; Stangenfuß = erste Ecke
            return s.sink.polyHeads.filter { it.color == Palette.TEAM_BLUE || it.color == Palette.TEAM_RED }
        }
        val plain = flagHeads(false)
        val withStub = flagHeads(true)
        assertTrue(plain.isNotEmpty())
        // Alle Teamfarb-Polygone (Geräte-Wimpel, Fahnen) der Szene bleiben unverändert: die Fahne springt nicht
        for (h in plain) assertTrue(withStub.any { kotlin.math.abs(it.x - h.x) < 1e-4f && kotlin.math.abs(it.y - h.y) < 1e-4f && it.color == h.color }, "flag/pennant at ${h.x},${h.y} moved")
    }

    @Test
    fun layerKeyCoversTheTerrainShape() {
        fun key(canyon: FloatArray): Long {
            val s = Setup(SyntheticScene(120f, canyon))
            s.frame()
            return s.renderer.lastLayerKey
        }
        val k1 = key(floatArrayOf(40f, 47.5f, 72.5f, 80f))
        assertEquals(k1, key(floatArrayOf(40f, 47.5f, 72.5f, 80f)), "same map, same key")
        assertNotEquals(k1, key(floatArrayOf(40f, 48.5f, 72.5f, 80f)), "same id and size, different heights: new key")
    }

    @Test
    fun layersAreInvalidatedOnBindAndRelease() {
        val sink = object : DrawSinkProbe() {}
        val sc = SyntheticScene().both()
        val r = SceneRenderer()
        val t = TestTarget(1280, 576, 1f, sink)
        r.bind(sc.tables, sc.map, t)
        assertTrue(sink.invalidations >= 1, "bind drops cached layers of a previous match")
        val before = sink.invalidations
        r.release()
        assertTrue(sink.invalidations > before)
    }

    /** Aufzeichnende Senke, die nur [invalidateLayers] zählt. */
    private open class DrawSinkProbe : DrawSinkDelegate() {
        var invalidations = 0
        override fun invalidateLayers() { invalidations++ }
    }

    private abstract class DrawSinkDelegate(private val inner: RecordingDrawSink = RecordingDrawSink()) : de.bollwerk.renderapi.DrawSink by inner

    private class SpyParticles(val inner: PooledParticleSystem) : ParticleSystem {
        var onFxCalls = 0
        override val count: Int get() = inner.count
        override val buffers: ParticleBuffers get() = inner.buffers
        override fun emit(kind: de.bollwerk.renderapi.ParticleKind, x: Float, y: Float, vx: Float, vy: Float, life: Float, size: Float, color: Int) =
            inner.emit(kind, x, y, vx, vy, life, size, color)
        override fun onFx(event: FxEvent, wind: Float) { onFxCalls++; inner.onFx(event, wind) }
        override fun update(dt: Float, wind: Float) = inner.update(dt, wind)
        override fun clear() = inner.clear()
        override fun bindWorld(materialColors: IntArray, terrain: de.bollwerk.engine.sim.Terrain?) = inner.bindWorld(materialColors, terrain)
        override fun bindWorld(materialColors: IntArray, materialKinds: IntArray, weaponKinds: IntArray, terrain: de.bollwerk.engine.sim.Terrain?) =
            inner.bindWorld(materialColors, materialKinds, weaponKinds, terrain)
    }
}
