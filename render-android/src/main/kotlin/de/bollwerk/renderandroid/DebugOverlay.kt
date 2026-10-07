package de.bollwerk.renderandroid

import de.bollwerk.renderapi.DrawSink
import de.bollwerk.renderapi.TextAlign

/**
 * Debug-Anzeige oben links: Bildrate, p50/p95 des Frame-Abstands und der Zeichenzeit, Sim-Zeit, Partikel, Speicher.
 * Zeichnet über [DrawSink] (plattformneutral, testbar). Die Texte werden höchstens alle [refreshNanos] neu gebaut
 * (wiederverwendeter [StringBuilder]); zwischendurch entstehen keine Objekte.
 */
class DebugOverlay(private val refreshNanos: Long = 500_000_000L) {
    private val sb = StringBuilder(96)
    private var line1 = ""
    private var line2 = ""
    private var line3 = ""
    private var lastBuild = Long.MIN_VALUE

    /** Aktuelle Zeilen (Tests). */
    val lines: List<String> get() = listOf(line1, line2, line3)

    fun draw(
        sink: DrawSink, stats: FrameStats, nowNanos: Long, density: Float, topInsetPx: Float,
        particles: Int, textureBytes: Long, layerBytes: Long,
    ) {
        if (lastBuild == Long.MIN_VALUE || nowNanos - lastBuild >= refreshNanos) {
            lastBuild = nowNanos
            build(stats, particles, textureBytes, layerBytes)
        }
        val d = density
        val x = 8f * d
        val y = topInsetPx + 6f * d
        val fs = 11f * d
        val lh = 14f * d
        sink.fillRect(x, y, 214f * d, lh * 3f + 8f * d, PANEL)
        sink.text(line1, x + 6f * d, y + lh, fs, TEXT, TextAlign.LEFT, true)
        sink.text(line2, x + 6f * d, y + lh * 2f, fs, TEXT, TextAlign.LEFT, false)
        sink.text(line3, x + 6f * d, y + lh * 3f, fs, TEXT, TextAlign.LEFT, false)
    }

    private fun build(stats: FrameStats, particles: Int, textureBytes: Long, layerBytes: Long) {
        val b = sb
        b.setLength(0)
        b.append(stats.fps.toInt()).append(" fps  p50 "); one(b, stats.p50Ms).append("  p95 "); one(b, stats.p95Ms).append(" ms")
        line1 = b.toString()
        b.setLength(0)
        b.append("draw p50 "); one(b, stats.workP50Ms).append("  p95 "); one(b, stats.workP95Ms).append("  sim ")
        if (stats.simMeanMs.isNaN()) b.append('-') else one(b, stats.simMeanMs)
        line2 = b.toString()
        b.setLength(0)
        b.append("part ").append(particles).append("  tex "); one(b, textureBytes / 1048576f)
        b.append(" MB  layer "); one(b, layerBytes / 1048576f).append(" MB  drop ").append(stats.droppedFrames)
        line3 = b.toString()
    }

    /** Hängt [v] mit einer Nachkommastelle an (ohne `String.format`). */
    private fun one(b: StringBuilder, v: Float): StringBuilder {
        val t = (v * 10f + 0.5f).toInt()
        return b.append(t / 10).append('.').append(t % 10)
    }

    private companion object {
        const val PANEL = 0xB0000000.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
    }
}
