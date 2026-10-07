package de.bollwerk.renderandroid

import de.bollwerk.renderapi.DrawSink
import de.bollwerk.renderapi.ImageSpec
import de.bollwerk.renderapi.RenderTarget
import de.bollwerk.renderapi.TextAlign
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Prüft, dass jede [DrawSink]-Operation auf den passenden Canvas-Aufruf abgebildet wird (aufgezeichnete Canvas). */
class CanvasDrawSinkTest {
    private val canvas = RecordingCanvas()
    private val sink = CanvasDrawSink().also { it.begin(canvas) }

    @Test
    fun drawingOutsideFrameFails() {
        val s = CanvasDrawSink()
        assertFailsWith<IllegalStateException> { s.fillRect(0f, 0f, 1f, 1f, 0) }
    }

    @Test
    fun transformsAndClipsAreForwarded() {
        sink.save()
        sink.translate(3f, 4f)
        sink.rotate((PI / 2).toFloat())
        sink.scale(2f, 5f)
        sink.clipRect(10f, 20f, 30f, 40f)
        sink.restore()
        assertEquals(6, canvas.calls.size)
        assertEquals(listOf("save()", "translate(3.0,4.0)"), canvas.calls.take(2))
        assertEquals(90f, canvas.last("rotate")!!.removePrefix("rotate(").removeSuffix(")").toFloat(), 1e-3f)
        assertEquals(listOf("scale(2.0,5.0)", "clipRect(10.0,20.0,40.0,60.0)", "restore()"), canvas.calls.drop(3))
    }

    @Test
    fun rotationIsConvertedFromRadiansToDegreesClockwise() {
        sink.rotate(1f)
        val deg = canvas.last("rotate")!!.removePrefix("rotate(").removeSuffix(")").toFloat()
        assertEquals(57.29578f, deg, 1e-3f)
    }

    @Test
    fun circleClipUsesPathClip() {
        sink.clipCircle(5f, 5f, 3f)
        assertEquals(1, canvas.count("clipPath"))
        assertEquals(0, canvas.count("clipRect"))
    }

    @Test
    fun shapesMapToCanvasPrimitives() {
        sink.fillRect(1f, 2f, 3f, 4f, -1)
        sink.strokeRect(1f, 2f, 3f, 4f, 2f, -1)
        sink.fillCircle(5f, 6f, 7f, -1)
        sink.strokeCircle(5f, 6f, 7f, 1f, -1)
        sink.line(0f, 0f, 9f, 9f, 2f, -1, roundCap = true)
        sink.line(0f, 0f, 9f, 9f, 2f, -1, roundCap = false)
        assertEquals(2, canvas.count("drawRect"))
        assertEquals("drawRect(1.0,2.0,4.0,6.0)", canvas.calls[0])
        assertEquals(2, canvas.count("drawCircle"))
        assertEquals(2, canvas.count("drawLine"))
    }

    @Test
    fun polygonUsesReusedPathAndIgnoresDegenerateInput() {
        val xy = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        sink.fillPolygon(xy, 4, -1)
        sink.fillPolygon(xy, 2, -1)
        assertEquals(1, canvas.count("drawPath"))
    }

    @Test
    fun dashedLineIsDrawnAsOneBatchOfSegments() {
        sink.dashedLine(0f, 0f, 10f, 0f, 1f, -1, 2f, 1f)
        assertEquals(1, canvas.count("drawLines"))
        assertEquals("drawLines(0,16)", canvas.last("drawLines"), "4 Striche × 4 Koordinaten")
        canvas.clear()
        sink.dashedLine(3f, 3f, 3f, 3f, 1f, -1, 2f, 1f)
        assertEquals(0, canvas.count("drawLines"), "Länge 0: nichts")
    }

    @Test
    fun gradientRectIsPlacedThroughCanvasMatrixWithCachedUnitShader() {
        val cols = intArrayOf(0xFFFF0000.toInt(), 0x00FF0000)
        val stops = floatArrayOf(0f, 1f)
        repeat(10) { sink.gradientRect(0f, 0f, 5f, 5f, 0f, 0f, 0f, 5f, cols, stops) }
        assertEquals(10, canvas.count("drawPaint"))
        assertEquals(0, canvas.count("drawRect"), "der Verlauf füllt den Clip, kein Rechteck")
        assertEquals(1, sink.gradientCacheMisses, "gleicher Verlauf: ein einziger Shader")
        // gleiche Farben, nur anderes Gesamt-Alpha (Feuerschein) → derselbe Shader
        cols[0] = 0x80FF0000.toInt(); cols[1] = 0x00FF0000
        sink.gradientRect(0f, 0f, 5f, 5f, 0f, 0f, 0f, 5f, cols, stops)
        cols[0] = 0x20FF0000
        sink.gradientRect(0f, 0f, 5f, 5f, 0f, 0f, 0f, 5f, cols, stops)
        assertEquals(1, sink.gradientCacheMisses, "nur das Gesamt-Alpha ändert sich: derselbe Shader, Alpha steht am Paint")
        cols[0] = 0x80FF0000.toInt(); cols[1] = 0x40FF0000 // Verlaufsform ändert sich: neuer Shader
        sink.gradientRect(0f, 0f, 5f, 5f, 0f, 0f, 0f, 5f, cols, stops)
        assertEquals(2, sink.gradientCacheMisses)
    }

    @Test
    fun gradientGeometryIsExpressedAsClipTranslateRotateScale() {
        val cols = intArrayOf(-1, 0xFF000000.toInt())
        val stops = floatArrayOf(0f, 1f)
        // senkrecht nach unten von (10,20) über 40 px, auf ein Rechteck (0,0,100,200) beschränkt
        sink.gradientRect(0f, 0f, 100f, 200f, 10f, 20f, 10f, 60f, cols, stops)
        assertEquals(
            listOf("save()", "clipRect(0.0,0.0,100.0,200.0)", "translate(10.0,20.0)"),
            canvas.calls.take(3),
        )
        val rot = canvas.last("rotate")!!.removePrefix("rotate(").removeSuffix(")").toFloat()
        assertEquals(90f, rot, 1e-3f)
        assertEquals("scale(40.0,40.0)", canvas.last("scale"))
        assertEquals(listOf("drawPaint()", "restore()"), canvas.calls.takeLast(2))
        assertEquals(0, canvas.saveDepth)
        // waagerecht nach rechts: keine Drehung nötig
        canvas.clear()
        sink.gradientRect(0f, 0f, 100f, 200f, 0f, 0f, 30f, 0f, cols, stops)
        assertEquals(0, canvas.count("rotate"))
        assertEquals("scale(30.0,30.0)", canvas.last("scale"))
        // nach links: 180 Grad
        canvas.clear()
        sink.gradientRect(0f, 0f, 100f, 200f, 30f, 0f, 0f, 0f, cols, stops)
        assertEquals(180f, canvas.last("rotate")!!.removePrefix("rotate(").removeSuffix(")").toFloat(), 1e-3f)
    }

    @Test
    fun gradientShaderIsIndependentOfPositionSoPanAndZoomHitTheCache() {
        val cols = intArrayOf(0xFF102040.toInt(), 0xFFFFA040.toInt())
        val stops = floatArrayOf(0f, 1f)
        // Himmel, der bei jedem Frame (Pan/Zoom) an anderer Stelle und in anderer Höhe liegt
        for (frame in 0 until 200) {
            val y = 100f + frame * 3.7f
            val h = 400f + frame * 1.3f
            sink.gradientRect(0f, y, 1920f, h, 0f, y, 0f, y + h, cols, stops)
        }
        assertEquals(1, sink.gradientCacheMisses, "ein Shader für alle Lagen und Größen")
        assertEquals(200, canvas.count("drawPaint"))
    }

    @Test
    fun degenerateGradientsFallBackToSolidOrNothing() {
        sink.gradientRect(0f, 0f, 5f, 5f, 1f, 1f, 1f, 1f, intArrayOf(-1, 0), floatArrayOf(0f, 1f))
        assertEquals(1, canvas.count("drawRect"))
        canvas.clear()
        sink.gradientRect(0f, 0f, 5f, 5f, 0f, 0f, 0f, 5f, intArrayOf(0x00112233, 0x00445566), floatArrayOf(0f, 1f))
        assertEquals(0, canvas.count("drawRect") + canvas.count("drawPaint"), "komplett durchsichtig: nichts zeichnen")
    }

    @Test
    fun glowIsPlacedThroughCanvasMatrix() {
        sink.glow(10f, 20f, 4f, 0x80FFAA00.toInt())
        assertEquals(listOf("save()", "translate(10.0,20.0)", "scale(4.0,4.0)", "drawCircle(0.0,0.0,1.0)", "restore()"), canvas.calls)
        canvas.clear()
        sink.glow(0f, 0f, 1f, 0x00FFAA00)
        sink.glow(0f, 0f, 0f, -1)
        assertTrue(canvas.calls.isEmpty(), "Alpha 0 oder Radius 0: nichts")
    }

    @Test
    fun textAndMeasureAreForwarded() {
        sink.text("Hallo", 5f, 6f, 14f, -1, TextAlign.CENTER, bold = true)
        assertEquals("drawText(Hallo,5.0,6.0)", canvas.calls.single())
        // Die android.jar-Attrappe misst 0; wichtig ist, dass der Aufruf nicht fehlschlägt
        assertEquals(0f, sink.measureText("Hallo", 14f, true))
    }

    @Test
    fun unknownImageHandlesAreIgnored() {
        sink.image(-1, 0f, 0f, 1f, 1f, 1f)
        sink.image(999, 0f, 0f, 1f, 1f, 1f)
        sink.imageRegion(7, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, 1f)
        sink.releaseImage(5)
        assertTrue(canvas.calls.isEmpty())
    }

    @Test
    fun failingBitmapCreationYieldsNoTextureHandle() {
        // In der Attrappe liefert Bitmap.createBitmap null: die Senke meldet -1 (Renderer überspringt die Textur)
        assertEquals(-1, sink.registerImage(ImageSpec(2, 2, IntArray(4), "x@72")))
    }

    @Test
    fun layerFallsBackToDirectDrawingWithoutBitmapAndStaysBalanced() {
        assertTrue(sink.beginLayer(1, 42L, 100, 50, 3f, 4f), "ohne Bitmap: Renderer muss selbst zeichnen")
        sink.fillRect(0f, 0f, 10f, 10f, -1)
        sink.endLayer(1)
        assertEquals(0, canvas.saveDepth, "save/restore ausgeglichen")
        assertEquals(listOf("save()", "translate(3.0,4.0)", "drawRect(0.0,0.0,10.0,10.0)", "restore()"), canvas.calls.filter { !it.startsWith("restoreToCount") })
    }

    @Test
    fun invalidLayerArgumentsDrawDirectly() {
        assertTrue(sink.beginLayer(99, 1L, 100, 50, 0f, 0f))
        sink.endLayer(99)
        assertTrue(sink.beginLayer(1, 1L, 0, 50, 0f, 0f))
        sink.endLayer(1)
        assertEquals(0, canvas.saveDepth)
    }

    @Test
    fun releaseMakesSinkUnusableUntilNextBegin() {
        sink.release()
        assertFailsWith<IllegalStateException> { sink.fillRect(0f, 0f, 1f, 1f, 0) }
    }

    /**
     * Kotlin erzeugt für nicht überschriebene Interface-Standardmethoden Brücken in der Klasse, die in
     * `DrawSink$DefaultImpls` springen; sie stehen in `declaredMethods` und sind nicht als synthetisch markiert, ein
     * Namensvergleich kann deshalb nie fehlschlagen. Zuverlässig ist der Blick in den Bytecode: Verweist die Klasse
     * auf `DrawSink$DefaultImpls`, hat sie mindestens eine Standardmethode nicht überschrieben.
     */
    private fun referencesDefaultImpls(c: Class<*>): Boolean {
        val bytes = c.getResourceAsStream("/" + c.name.replace('.', '/') + ".class")!!.use { it.readBytes() }
        return String(bytes, Charsets.ISO_8859_1).contains("DrawSink\$DefaultImpls")
    }

    private class MinimalSink : DrawSink {
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
        override fun registerImage(spec: ImageSpec): Int = -1
        override fun releaseImage(handle: Int) {}
    }

    @Test
    fun implementsEveryDrawSinkMethod() {
        // Kontrolle: das Verfahren erkennt eine Klasse, die alle Standardmethoden (clipCircle, measureText, imageRegion,
        // beginLayer, invalidateLayers, endLayer) erbt
        assertTrue(referencesDefaultImpls(MinimalSink::class.java), "Verfahren erkennt geerbte Standardmethoden nicht")
        assertFalse(referencesDefaultImpls(CanvasDrawSink::class.java), "CanvasDrawSink erbt eine Standardmethode von DrawSink")
    }

    @Test
    fun renderTargetExposesSizeAndSink() {
        val t = CanvasRenderTarget(sink)
        t.update(1920, 1080, 2.5f)
        val rt: RenderTarget = t
        assertEquals(1920, rt.widthPx); assertEquals(1080, rt.heightPx); assertEquals(2.5f, rt.density)
        assertFalse(rt.sink !== sink)
    }
}
