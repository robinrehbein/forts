package de.bollwerk.renderandroid

import de.bollwerk.engine.view.SnapshotExchange
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.InteractionMode
import de.bollwerk.renderapi.Loupe
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.scene.SyntheticScene
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Misst die Allokation des gesamten Frame-Pfads der App (Quelle → Timing → [SceneRenderer] → [CanvasDrawSink] →
 * Debug-Anzeige → Statistik) im Dauerbetrieb. Die Canvas ist eine Attrappe ohne Aufzeichnung, es zählt also nur Kotlin-Code
 * (inkl. Render-API). Ohne Escape-Analyse (siehe build.gradle.kts), wie ART sie nicht hat.
 */
class SessionAllocationTest {
    private class NoopCanvas : android.graphics.Canvas() {
        override fun getWidth(): Int = 1920
        override fun getHeight(): Int = 866
    }

    /** `com.sun.management.ThreadMXBean` steht beim Kompilieren gegen android.jar nicht zur Verfügung: per Reflexion. */
    private val bean: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)!!
    private val allocMethod = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    private fun allocatedBytes(threadId: Long): Long = allocMethod.invoke(bean, threadId) as Long

    /**
     * [moving] = Kamera schwenkt und zoomt in jedem Frame (Ebenen laufen im DIRECT-Modus, jeder Verlauf liegt woanders)
     * und die Lupe wird mit dem Finger gezogen; vorberechnete Überlagerungen, damit der Test selbst nichts allokiert.
     */
    private fun measure(debugOverlay: Boolean, moving: Boolean = false): Long {
        val sc = SyntheticScene.narrow().both()
        val snap = sc.snap
        val ex = SnapshotExchange { snap }
        val source = SnapshotSource.of(ex, sc.tables, sc.map, simMillis = { 1.2f })
        val camera = Camera(1920f, 866f, 1f).also { it.fitRect(0f, 20f, 92f, 45f) }
        val overlay = OverlayState(mode = InteractionMode.BUILD)
        val drag = Array(97) { k ->
            overlay.copy(loupe = Loupe(300f + k * 13f, 400f + (k % 7) * 9f, 30f + k * 0.3f, 32f + (k % 11) * 0.2f))
        }
        var dragIndex = 0
        val overlaySource = OverlaySource { if (moving) drag[dragIndex] else overlay }
        val session = RenderSession(source, overlaySource, camera, RenderSettings(debugOverlay = debugOverlay), 1f)
        val zooms = FloatArray(64) { 1f + 0.15f * kotlin.math.sin(it * 0.3f) }
        val canvas = NoopCanvas()
        session.onThreadStart()
        var now = 1_000_000_000L
        var i = 0
        fun frame() {
            if (i % 2 == 0) { snap.tick++; snap.seq++; ex.publish() }
            if (moving) {
                camera.edit {
                    pan(if (i % 2 == 0) 7f else -5f, if (i % 3 == 0) 3f else -2f)
                    setZoom(zooms[i % 64])
                }
                dragIndex = (dragIndex + 1) % drag.size
            }
            i++
            now += 16_666_667L
            val w = System.nanoTime()
            session.drawFrame(canvas, now)
            session.recordFrame(now, System.nanoTime() - w)
        }
        for (k in 0 until 600) frame() // Aufwärmen: JIT, Texturen, Puffer
        val id = Thread.currentThread().id
        val before = allocatedBytes(id)
        val n = 1500
        for (k in 0 until n) frame()
        return (allocatedBytes(id) - before) / n
    }

    @Test
    fun steadyStateFramesAllocateNothingNotable() {
        measure(false) // verworfen: Klassenladen
        val runs = LongArray(3) { measure(false) }
        println("session allocation (bytes/frame): ${runs.joinToString()}")
        for ((i, b) in runs.withIndex()) assertTrue(b < 48, "run $i: $b bytes/frame (alle: ${runs.joinToString()})")
    }

    @Test
    fun movingCameraAndDraggedLoupeAllocateNothingNotableEither() {
        // Sky/Dunst/Nebel-Verläufe liegen bei jedem Pan/Zoom woanders; der lageunabhängige Einheitsverlauf trifft den Cache.
        // (Früher: ~8 neue LinearGradient je Frame, also ~480 Shader/s samt Cleaner-Objekten.)
        measure(false, moving = true)
        val runs = LongArray(3) { measure(false, moving = true) }
        println("session allocation while panning/zooming and dragging the loupe (bytes/frame): ${runs.joinToString()}")
        for ((i, b) in runs.withIndex()) assertTrue(b < 48, "run $i: $b bytes/frame (alle: ${runs.joinToString()})")
    }

    @Test
    fun debugOverlayAddsOnlyItsTwoHertzTextRefresh() {
        measure(true)
        val runs = LongArray(3) { measure(true) }
        println("session allocation with debug overlay (bytes/frame): ${runs.joinToString()}")
        for ((i, b) in runs.withIndex()) assertTrue(b < 96, "run $i: $b bytes/frame (alle: ${runs.joinToString()})")
    }
}
