package de.bollwerk.renderandroid

import de.bollwerk.renderapi.ImageSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bitmap-Pfade der [CanvasDrawSink] über [FakeGraphics]: Texturen (Handles, Referenzzählung, Leerlauf-Cache), statische
 * Ebenen (Aufzeichnen → Aufblenden → direkt, Hysterese) und Speicherdruck mit echten Einträgen.
 */
class SinkBitmapPathsTest {
    private val gfx = FakeGraphics()
    private val canvas = RecordingCanvas()
    private val sink = CanvasDrawSink(TypefaceProvider.SYSTEM, CanvasDrawSink.DEFAULT_IDLE_TEXTURE_BYTES, gfx)

    private fun spec(id: String = "wood@72", w: Int = 4, h: Int = 4) = ImageSpec(w, h, IntArray(w * h), id)

    // ---- Texturen ----

    @Test
    fun registeredTextureYieldsHandleAndIsDrawnThroughShaderWithCanvasTransform() {
        sink.begin(canvas)
        val h = sink.registerImage(spec(w = 8, h = 4))
        assertTrue(h > 0)
        assertEquals(1, sink.textureCount)
        assertEquals(8L * 4 * 4, sink.textureBytes)
        sink.imageRegion(h, 0f, 0f, 1f, 1f, 10f, 20f, 16f, 8f, 1f)
        assertEquals(listOf("save()", "translate(10.0,20.0)", "scale(2.0,2.0)", "drawRect(0.0,0.0,8.0,4.0)", "restore()"), canvas.calls)
        canvas.clear()
        sink.image(h, 1f, 2f, 3f, 4f, 0.5f)
        assertEquals(listOf("drawBitmapRect()"), canvas.calls)
    }

    @Test
    fun sameSpecSharesOneBitmapAndIdleTexturesComeBackWithoutRecreation() {
        val a = sink.registerImage(spec())
        val b = sink.registerImage(spec())
        assertTrue(a != b, "zwei Handles")
        assertEquals(1, gfx.textures.size, "ein Bitmap")
        sink.releaseImage(a)
        sink.releaseImage(b)
        assertEquals(1, sink.textureCount, "im Leerlauf-Cache")
        assertEquals(0, gfx.recycleCount)
        val c = sink.registerImage(spec())
        assertTrue(c > 0)
        assertEquals(1, gfx.textures.size, "aus dem Leerlauf-Cache, nicht neu erzeugt")
    }

    @Test
    fun releasedHandlesAreReused() {
        val a = sink.registerImage(spec("a"))
        sink.releaseImage(a)
        val b = sink.registerImage(spec("b"))
        assertEquals(a, b)
    }

    @Test
    fun failingTextureAllocationYieldsMinusOne() {
        gfx.failTextures = true
        assertEquals(-1, sink.registerImage(spec()))
    }

    @Test
    fun idleBudgetEvictsOldestTexturesAndRecyclesThem() {
        val small = CanvasDrawSink(TypefaceProvider.SYSTEM, 64L, gfx) // 64 Bytes = eine 4×4-Textur
        val hs = IntArray(3) { small.registerImage(spec("t$it")) }
        for (h in hs) small.releaseImage(h)
        assertEquals(1, small.textureCount, "nur eine passt ins Leerlauf-Budget")
        assertEquals(2, gfx.recycleCount)
    }

    // ---- Ebenen ----

    private fun drawLayerFrame(key: Long, offX: Float = 3f, offY: Float = 4f): Boolean {
        sink.begin(canvas)
        val rec = sink.beginLayer(0, key, 100, 50, offX, offY)
        if (rec) {
            sink.fillRect(0f, 0f, 10f, 10f, -1)
            sink.endLayer(0)
        }
        sink.end()
        return rec
    }

    @Test
    fun layerIsRecordedOnceThenBlitted() {
        canvas.clear()
        assertTrue(drawLayerFrame(7L), "erster Frame: aufzeichnen")
        assertEquals(1, gfx.layerBitmaps.size)
        assertEquals(1, gfx.layerCanvases[0].count("drawRect"), "Inhalt landet im Ebenen-Bitmap")
        assertEquals(0, canvas.count("drawRect"), "nicht in der Oberfläche")
        assertEquals("drawBitmap(3.0,4.0)", canvas.last("drawBitmap"), "danach aufgeblendet")
        canvas.clear()
        repeat(5) { assertFalse(drawLayerFrame(7L), "gültig: nur aufblenden") }
        assertEquals(5, canvas.count("drawBitmap"))
        assertEquals(1, sink.layerRecords)
        assertEquals(5, sink.layerBlits)
        assertEquals(100L * 50 * 4, sink.layerBytes)
    }

    @Test
    fun layerBlitUsesRoundedOffsetsNotShakeFractions() {
        drawLayerFrame(7L)
        canvas.clear()
        drawLayerFrame(7L, 2.6f, -1.4f)
        assertEquals("drawBitmap(3.0,-1.0)", canvas.last("drawBitmap"))
    }

    @Test
    fun movingKeyDrawsDirectlyWithoutRecordingAndHysteresisDelaysTheNextRecording() {
        drawLayerFrame(1L)
        assertEquals(1, sink.layerRecords)
        for (k in 2L..20L) {
            canvas.clear()
            assertTrue(drawLayerFrame(k), "Schlüssel $k: direkt zeichnen (true)")
            assertEquals(1, canvas.count("drawRect"), "direkt in die Oberfläche")
            assertEquals(0, canvas.saveDepth)
        }
        assertEquals(1, sink.layerRecords, "während der Bewegung wird nichts aufgezeichnet")
        // Ruhe: erst nach sechs gleichen Schlüsseln wird aufgezeichnet; davor bleibt es direkt
        for (n in 1..5) { canvas.clear(); assertTrue(drawLayerFrame(99L)); assertEquals(1, canvas.count("drawRect"), "Frame $n") }
        assertEquals(1, sink.layerRecords)
        canvas.clear()
        assertTrue(drawLayerFrame(99L), "sechster Frame: aufzeichnen")
        assertEquals(2, sink.layerRecords)
        assertEquals(0, canvas.count("drawRect"))
        assertFalse(drawLayerFrame(99L), "danach aufblenden")
    }

    @Test
    fun sizeChangeReallocatesTheLayerBitmapAndRecyclesTheOldOne() {
        drawLayerFrame(1L)
        sink.begin(canvas)
        repeat(7) {
            val rec = sink.beginLayer(0, 1L, 120, 60, 0f, 0f)
            if (rec) sink.endLayer(0)
        }
        sink.end()
        assertEquals(2, gfx.layerBitmaps.size)
        assertEquals(1, gfx.recycleCount)
        assertEquals(120L * 60 * 4, sink.layerBytes)
    }

    @Test
    fun invalidateLayersMakesNextFrameRecordImmediately() {
        drawLayerFrame(1L)
        assertFalse(drawLayerFrame(1L))
        sink.invalidateLayers()
        assertTrue(drawLayerFrame(1L))
    }

    @Test
    fun failingLayerAllocationFallsBackToDirectDrawing() {
        gfx.failLayers = true
        sink.begin(canvas)
        assertTrue(sink.beginLayer(0, 1L, 100, 50, 0f, 0f))
        sink.fillRect(0f, 0f, 1f, 1f, -1)
        sink.endLayer(0)
        sink.end()
        assertEquals(1, canvas.count("drawRect"))
        assertEquals(0, canvas.saveDepth)
    }

    // ---- Speicherdruck ----

    @Test
    fun trimMemoryIsAppliedAtNextFrameStartNotImmediately() {
        drawLayerFrame(1L)
        val h = sink.registerImage(spec())
        sink.releaseImage(h) // Leerlauf
        assertEquals(TrimAction.LAYERS, sink.trimMemory(15))
        assertTrue(sink.layerBytes > 0, "noch nicht angewendet")
        assertEquals(0, gfx.recycleCount)
        sink.begin(canvas) // Anfang des nächsten Frames
        assertEquals(0L, sink.layerBytes, "Ebenen verworfen")
        assertEquals(0, sink.textureCount, "Leerlauf-Textur verworfen")
        assertEquals(2, gfx.recycleCount)
        assertEquals(0, gfx.liveLayers)
        sink.end()
    }

    @Test
    fun idleTrimLevelKeepsLayersAndTexturesInUse() {
        drawLayerFrame(1L)
        val used = sink.registerImage(spec("used"))
        val idle = sink.registerImage(spec("idle"))
        sink.releaseImage(idle)
        sink.trimMemory(10)
        sink.begin(canvas)
        assertTrue(sink.layerBytes > 0)
        assertEquals(1, sink.textureCount, "nur die ungenutzte Textur ist weg")
        assertTrue(used > 0)
        sink.end()
    }

    @Test
    fun droppedLayerIsRerecordedAtOnceWhenTheCameraWasStill() {
        repeat(8) { drawLayerFrame(5L) } // ruhig: viele Blits
        sink.trimMemory(15)
        assertTrue(drawLayerFrame(5L), "neu aufzeichnen, nicht erst nach Hysterese")
        assertEquals(2, sink.layerRecords)
    }

    @Test
    fun applyTrimEverythingDropsIdleTexturesUnconditionally() {
        val h = sink.registerImage(spec())
        sink.releaseImage(h) // wie nach Renderer-Abbau: alles im Leerlauf, weit unter dem Budget
        assertEquals(1, sink.textureCount)
        sink.applyTrim(TrimAction.EVERYTHING) // ohne gemerkte Stufe
        assertEquals(0, sink.textureCount)
        assertEquals(0L, sink.textureBytes)
        assertEquals(1, gfx.recycleCount)
    }

    @Test
    fun releaseRecyclesEverything() {
        drawLayerFrame(1L)
        sink.registerImage(spec("a"))
        val b = sink.registerImage(spec("b"))
        sink.releaseImage(b)
        sink.release()
        assertEquals(0, gfx.liveLayers)
        assertEquals(0, gfx.liveTextures)
        assertEquals(0, sink.textureCount)
    }
}
