package de.bollwerk.renderandroid

import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.scene.SyntheticScene
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verhalten der [RenderSession] ohne Gerät: Pause, Neuaufbau des Renderers, Speicherdruck, Kamera, Statistik. */
class SessionBehaviorTest {
    private class FixedSource(val sc: SyntheticScene) : SnapshotSource {
        override val tables: SimTables = sc.tables
        override val map: MapSpec = sc.map
        override fun latest(): FrameSnapshot = sc.snap
    }

    private val sc = SyntheticScene.narrow().both()
    private val gfx = FakeGraphics()
    private val camera = Camera(1920f, 1080f, 1f).also { it.fitRect(0f, 20f, 92f, 45f) }
    private val canvas = RecordingCanvas()
    private var settings = RenderSettings()
    private val session = RenderSession(FixedSource(sc), OverlaySource.NONE, camera, settings, 1f, gfx)
    private var now = 1_000_000_000L
    private var frames = 0

    /** Zeichnet einen Frame im Abstand [dtMs] und liefert, wie oft die Vollbild-Fläche (Bildschirmblitz) gefüllt wurde. */
    private fun frame(dtMs: Float = 16.667f): Int {
        canvas.clear()
        now += (dtMs * 1e6f).toLong()
        session.drawFrame(canvas, now)
        frames++
        return canvas.calls.count { it == "drawRect(0.0,0.0,1920.0,1080.0)" }
    }

    private fun explosion(damage: Float = 80f) {
        sc.snap.fx.add(FxEvent.Explosion(sc.snap.tick, 20f, 30f, 4f, damage, 0, 0, false, -1, -1f))
        sc.snap.seq++
    }

    // ---- Pause ----

    @Test
    fun shakeAndScreenFlashDecayWhilePausedAndParticlesStayFrozen() {
        session.onThreadStart()
        repeat(5) { frame() }
        val baseline = frame()
        assertEquals(0f, session.shakeXpx)

        explosion(80f) // Schaden >= 40: Shake und Bildschirmblitz
        assertEquals(baseline + 1, frame(), "Blitz im Explosionsframe")
        assertTrue(abs(session.shakeXpx) + abs(session.shakeYpx) > 0f)
        val particles = session.particleCount
        assertTrue(particles > 0)

        session.paused = true
        assertTrue(abs(frame(33.3f) - baseline) <= 1)
        assertTrue(abs(session.shakeXpx) + abs(session.shakeYpx) > 0f, "klingt noch aus, ist nicht schon weg")
        var settledAt = -1
        for (i in 1..60) {
            val full = frame(33.3f)
            assertEquals(particles, session.particleCount, "Partikel stehen in der Pause still (Frame $i)")
            if (settledAt < 0 && session.shakeXpx == 0f && session.shakeYpx == 0f && full == baseline) settledAt = i
        }
        assertTrue(settledAt in 1..40, "Shake und Blitz klingen binnen ~1 s aus: $settledAt")
        // danach bleibt alles ruhig: kein Zittern, kein Blitz
        repeat(20) {
            assertEquals(baseline, frame(33.3f))
            assertEquals(0f, session.shakeXpx)
            assertEquals(0f, session.shakeYpx)
        }
    }

    @Test
    fun lastSnapshotWithExplosionPublishedAfterPauseAlsoSettles() {
        session.onThreadStart()
        repeat(5) { frame() }
        val baseline = frame()
        session.paused = true
        repeat(60) { frame(33.3f) } // Pause eingeschwungen
        explosion(90f) // die Sim veröffentlicht noch einmal
        val flash = frame(33.3f)
        assertEquals(baseline + 1, flash)
        repeat(60) { frame(33.3f) }
        assertEquals(baseline, frame(33.3f), "Blitz weg")
        assertEquals(0f, session.shakeXpx)
    }

    @Test
    fun unpausingResumesNormalTimeStepAndParticles() {
        session.onThreadStart()
        repeat(3) { frame() }
        explosion(80f)
        frame()
        session.paused = true
        repeat(70) { frame(33.3f) }
        val frozen = session.particleCount
        session.paused = false
        repeat(40) { frame() }
        assertTrue(session.particleCount != frozen || frozen == 0, "Partikel laufen wieder (altern/verschwinden)")
    }

    // ---- Renderer-Neuaufbau ----

    @Test
    fun rebuildingTheRendererDoesNotReplayTheCurrentSnapshotsFx() {
        session.onThreadStart()
        repeat(3) { frame() }
        explosion(80f)
        frame()
        assertTrue(session.particleCount > 0)
        // Einstellung ändert sich → Renderer und Partikelsystem werden neu gebaut, derselbe Snapshot liegt weiter an
        session.settings = settings.copy(reducedMotion = true)
        val full = frame()
        assertEquals(0, session.particleCount, "Explosion nicht erneut abgespielt")
        assertEquals(0f, session.shakeXpx)
        assertEquals(0, full, "kein Blitz")
        // ein wirklich neuer Snapshot mit Fx wird dagegen verarbeitet
        explosion(80f)
        frame()
        assertTrue(session.particleCount > 0)
    }

    @Test
    fun firstFrameOfASessionStillProcessesFxOfTheFirstSnapshot() {
        explosion(80f)
        session.onThreadStart()
        frame()
        assertTrue(session.particleCount > 0)
    }

    // ---- Speicherdruck ----

    @Test
    fun backgroundTrimWithStoppedThreadReleasesEveryTextureAndLayer() {
        session.onThreadStart()
        repeat(3) { frame() }
        assertTrue(gfx.liveTextures > 0)
        assertTrue(gfx.liveLayers > 0, "Himmel und Gelände aufgezeichnet")
        session.trim(40, renderThreadRunning = false)
        assertEquals(0, gfx.liveTextures, "keine Textur bleibt im Leerlauf-Cache zurück")
        assertEquals(0L, session.sink.textureBytes)
        assertEquals(0, session.sink.textureCount)
        assertEquals(0, gfx.liveLayers)
        assertEquals(0L, session.sink.layerBytes)
        // beim Wiedereinstieg wird neu gebunden und gezeichnet; die Explosion von vorhin wird nicht noch einmal gespielt
        repeat(2) { frame() }
        assertTrue(gfx.liveTextures > 0)
        assertEquals(0, session.particleCount)
    }

    @Test
    fun trimWhileThreadRunsOnlyRemembersTheLevelAndAppliesAtNextFrame() {
        session.onThreadStart()
        repeat(3) { frame() }
        val textures = gfx.liveTextures
        session.trim(40, renderThreadRunning = true)
        assertEquals(textures, gfx.liveTextures)
        assertTrue(gfx.liveLayers > 0)
        frame()
        assertEquals(textures, gfx.liveTextures, "benutzte Texturen bleiben (der Renderer läuft)")
        assertTrue(gfx.recycleCount > 0, "Ebenen verworfen")
    }

    @Test
    fun trimLevelsBelowThresholdDoNothing() {
        session.onThreadStart()
        repeat(3) { frame() }
        session.trim(5, renderThreadRunning = false)
        assertEquals(0, gfx.recycleCount)
    }

    @Test
    fun releaseFreesEverything() {
        repeat(3) { frame() }
        session.release()
        assertEquals(0, gfx.liveTextures)
        assertEquals(0, gfx.liveLayers)
    }

    // ---- Kamera ----

    @Test
    fun sessionRendersFromAPrivateCopyOfTheCamera() {
        repeat(3) { frame() }
        val cx = camera.centerX
        camera.edit { pan(50f, 0f) }
        assertTrue(camera.centerX != cx)
        frame()
        // Die geteilte Kamera bleibt unangetastet (der Renderer schreibt nicht hinein, Shake nur Darstellung)
        assertEquals(camera.shakeX, 0f)
    }

    // ---- Statistik ----

    @Test
    fun firstFrameAfterThreadStartIsNotRecorded() {
        session.onThreadStart()
        session.recordFrame(1_000_000_000L, 2_000_000L)
        assertEquals(0L, session.stats.frames)
        session.recordFrame(1_016_666_667L, 2_000_000L)
        assertEquals(1L, session.stats.frames)
        session.stats.refresh()
        assertEquals(16.67f, session.stats.p50Ms, 0.01f, "kein Abstand 0 im Fenster")
        session.onThreadStart() // Pause/Resume: wieder ohne Vorgänger
        session.recordFrame(9_000_000_000L, 2_000_000L)
        assertEquals(1L, session.stats.frames)
    }

    @Test
    fun pausedOrCappedFramesAtThrottleIntervalAreNotDrops() {
        session.onThreadStart()
        var t = 1_000_000_000L
        session.recordFrame(t, 1_000_000L, 33_333_333L)
        repeat(30) { t += 33_333_333L; session.recordFrame(t, 1_000_000L, 33_333_333L) }
        assertEquals(30L, session.stats.frames)
        assertEquals(0L, session.stats.droppedFrames, "30 fps bei Drosselung auf 30 fps")
        // Ohne Drosselung wäre derselbe Abstand ein ausgelassener Frame
        t += 33_333_333L; session.recordFrame(t, 1_000_000L, 0L)
        assertEquals(1L, session.stats.droppedFrames)
        // 120-Hz-Display: Soll 8,3 ms, 20 ms ist ausgelassen
        session.vsyncPeriodMs = 1000f / 120f
        t += 20_000_000L; session.recordFrame(t, 1_000_000L, 0L)
        assertEquals(2L, session.stats.droppedFrames)
    }
}
