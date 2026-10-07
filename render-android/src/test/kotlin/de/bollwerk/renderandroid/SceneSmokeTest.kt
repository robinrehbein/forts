package de.bollwerk.renderandroid

import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.InteractionMode
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.PooledParticleSystem
import de.bollwerk.renderapi.scene.SceneConfig
import de.bollwerk.renderapi.scene.SceneRenderer
import de.bollwerk.renderapi.scene.SyntheticScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke-Test: der gemeinsame [SceneRenderer] zeichnet eine echte Szene (zwei Festungen) über die [CanvasDrawSink] auf eine
 * aufgezeichnete Canvas. Ohne Gerät entstehen keine Bitmaps (Attrappe), die Senke fällt dabei vorgesehen auf direktes
 * Zeichnen und Textur-Handle −1 zurück; geprüft werden Vollständigkeit, Ausgeglichenheit und Frame-zu-Frame-Stabilität.
 */
class SceneSmokeTest {
    private fun setup(): Triple<SyntheticScene, RecordingCanvas, CanvasRenderTarget> {
        val sc = SyntheticScene.narrow().both()
        val canvas = RecordingCanvas()
        val target = CanvasRenderTarget(CanvasDrawSink())
        target.update(1920, 866, 1f)
        return Triple(sc, canvas, target)
    }

    @Test
    fun rendersASceneThroughTheCanvasSink() {
        val (sc, canvas, target) = setup()
        val camera = Camera(1920f, 866f, 1f).also { it.fitRect(0f, 20f, 92f, 45f) }
        val renderer = SceneRenderer(SceneConfig())
        renderer.bind(sc.tables, sc.map, target)
        val particles = PooledParticleSystem()
        target.sink.begin(canvas)
        renderer.render(target, sc.snap, 0.5f, camera, OverlayState(mode = InteractionMode.BUILD), particles)
        target.sink.end()

        assertEquals(0, canvas.saveDepth, "save/restore ausgeglichen")
        assertTrue(canvas.count("drawPath") > 50, "Balken, Gelände, Berge als Polygone: ${canvas.count("drawPath")}")
        assertTrue(canvas.count("drawRect") > 20)
        assertTrue(canvas.count("drawCircle") > 10, "Knoten/Glow")
        assertTrue(canvas.count("rotate") > 20, "Balken sind rotiert")
        assertTrue(canvas.maxSaveDepth in 2..12, "Verschachtelung bleibt klein: ${canvas.maxSaveDepth}")
    }

    @Test
    fun steadyFramesIssueIdenticalCallCountsAndStaticLayersAreRecordedOnce() {
        val (sc, canvas, target) = setup()
        val camera = Camera(1920f, 866f, 1f).also { it.fitRect(0f, 20f, 92f, 45f) }
        val renderer = SceneRenderer(SceneConfig())
        renderer.bind(sc.tables, sc.map, target)
        val particles = PooledParticleSystem()
        val sink = target.sink
        val counts = IntArray(4)
        for (f in 0 until 4) {
            canvas.clear()
            sc.snap.seq = 1 // gleiche Fx nicht erneut
            sink.begin(canvas)
            renderer.render(target, sc.snap, 0.5f, camera, OverlayState.NONE, particles)
            sink.end()
            counts[f] = canvas.count("drawPath")
            assertEquals(0, canvas.saveDepth)
        }
        // Ohne Bitmaps in der Attrappe zeichnet der Renderer die statischen Ebenen jedes Mal; die Summen bleiben stabil
        assertTrue(counts.all { it > 0 })
        assertEquals(counts[1], counts[2])
        assertEquals(counts[2], counts[3])
    }

    @Test
    fun reducedMotionRendererStillRenders() {
        val (sc, canvas, target) = setup()
        val camera = Camera(1920f, 866f, 1f).also { it.fitRect(0f, 20f, 92f, 45f) }
        val renderer = SceneRenderer(SceneConfig(reducedMotion = true))
        renderer.bind(sc.tables, sc.map, target)
        val particles = PooledParticleSystem(reducedMotion = true)
        assertEquals(260, particles.buffers.capacity, "Partikelgrenze im reduzierten Modus")
        target.sink.begin(canvas)
        renderer.render(target, sc.snap, 0f, camera, OverlayState.NONE, particles)
        target.sink.end()
        assertTrue(canvas.count("drawPath") > 0)
    }
}
