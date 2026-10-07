package de.bollwerk.renderandroid

import de.bollwerk.renderapi.DrawSink
import de.bollwerk.renderapi.ImageSpec
import de.bollwerk.renderapi.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DebugOverlayTest {
    private class TextSink : DrawSink {
        val texts = ArrayList<String>()
        var rects = 0
        override fun save() {}
        override fun restore() {}
        override fun translate(dx: Float, dy: Float) {}
        override fun rotate(rad: Float) {}
        override fun scale(sx: Float, sy: Float) {}
        override fun clipRect(x: Float, y: Float, w: Float, h: Float) {}
        override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) { rects++ }
        override fun strokeRect(x: Float, y: Float, w: Float, h: Float, width: Float, color: Int) {}
        override fun fillPolygon(xy: FloatArray, count: Int, color: Int) {}
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, roundCap: Boolean) {}
        override fun dashedLine(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, dash: Float, gap: Float) {}
        override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int) {}
        override fun gradientRect(x: Float, y: Float, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray) {}
        override fun glow(cx: Float, cy: Float, r: Float, color: Int) {}
        override fun text(text: String, x: Float, y: Float, sizePx: Float, color: Int, align: TextAlign, bold: Boolean) { texts.add(text) }
        override fun image(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) {}
        override fun registerImage(spec: ImageSpec): Int = 0
        override fun releaseImage(handle: Int) {}
    }

    private fun stats(): FrameStats {
        val s = FrameStats(window = 10, refreshEvery = 1000)
        repeat(10) { s.record(16.7f, 4.2f, 0.9f) }
        s.refresh()
        return s
    }

    @Test
    fun showsFpsPercentilesSimAndMemory() {
        val sink = TextSink()
        DebugOverlay().draw(sink, stats(), nowNanos = 0L, density = 2f, topInsetPx = 100f, particles = 143, textureBytes = 1_572_864L, layerBytes = 17L * 1048576)
        assertEquals(1, sink.rects)
        assertEquals(listOf("59 fps  p50 16.7  p95 16.7 ms", "draw p50 4.2  p95 4.2  sim 0.9", "part 143  tex 1.5 MB  layer 17.0 MB  drop 0"), sink.texts)
    }

    @Test
    fun textsAreRebuiltOnlyAfterTheRefreshInterval() {
        val o = DebugOverlay(refreshNanos = 500_000_000L)
        val sink = TextSink()
        val st = stats()
        o.draw(sink, st, 0L, 1f, 0f, 1, 0, 0)
        val first = o.lines[0]
        repeat(20) { o.draw(sink, st, 100_000_000L, 1f, 0f, 999, 0, 0) }
        assertSame(first, o.lines[0], "innerhalb des Intervalls dieselben String-Objekte (keine Allokation je Frame)")
        assertTrue(o.lines[2].startsWith("part 1 "))
        o.draw(sink, st, 600_000_000L, 1f, 0f, 999, 0, 0)
        assertTrue(o.lines[2].startsWith("part 999 "))
    }

    @Test
    fun missingSimTimeIsShownAsDash() {
        val s = FrameStats(window = 4, refreshEvery = 1000)
        s.record(16.7f, 1f); s.refresh()
        val o = DebugOverlay()
        o.draw(TextSink(), s, 0L, 1f, 0f, 0, 0, 0)
        assertTrue(o.lines[1].endsWith("sim -"))
    }
}
