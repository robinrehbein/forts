package de.bollwerk.renderapi.scene

import de.bollwerk.renderapi.DrawSink
import de.bollwerk.renderapi.ImageSpec
import de.bollwerk.renderapi.RenderTarget
import de.bollwerk.renderapi.TextAlign

/**
 * Test-Senke: zählt jeden Aufruf, merkt sich Farben, Texte und Bild-Handles und prüft, dass save/restore
 * ausgeglichen sind. Optional simuliert sie den Ebenen-Cache ([cacheLayers]).
 */
class RecordingDrawSink(val cacheLayers: Boolean = false) : DrawSink {
    val counts = HashMap<String, Int>()
    val texts = ArrayList<String>()
    val polygonColors = HashSet<Int>()
    val fillColors = HashSet<Int>()
    val glowColors = ArrayList<Int>()
    /** Radien aller `glow`-Aufrufe (Zeichenreihenfolge). */
    val glowRadii = ArrayList<Float>()
    /** Alle gefüllten Rechtecke (x, y, w, h, Farbe) in Zeichenreihenfolge. */
    class RectRec(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int)
    val fillRects = ArrayList<RectRec>()
    /** Kopien aller Polygone (xy-Paare) samt Farbe; nur mit [recordPolygons] (kostet Speicher). */
    class PolyRec(val color: Int, val xy: FloatArray)
    var recordPolygons = false
    val polygons = ArrayList<PolyRec>()
    val imageHandles = HashMap<Int, Int>()
    val regionHandles = HashMap<Int, Int>()
    val images = HashMap<Int, ImageSpec>()
    val translations = ArrayList<FloatArray>()
    val scales = ArrayList<Float>()
    var depth = 0
    var minDepth = 0
    var maxDepth = 0
    var nextHandle = 1
    var released = 0
    var layerBegins = 0
    var layerHits = 0
    private val layerKeys = HashMap<Int, Long>()
    var gradientCalls = 0
    val gradientColors = ArrayList<IntArray>()
    val layerOrder = ArrayList<Int>()

    /** Gefüllte Kreise in Zeichenreihenfolge (Mitte, Radius, Farbe). */
    class CircleRec(val x: Float, val y: Float, val r: Float, val color: Int)
    /** Erste Ecke jedes gefüllten Polygons mit Farbe (zur Lage kleiner Formen wie Fahnen). */
    class PolyHead(val color: Int, val x: Float, val y: Float, val minX: Float, val maxX: Float)

    val circles = ArrayList<CircleRec>()
    val polyHeads = ArrayList<PolyHead>()
    val strokeRectColors = ArrayList<Int>()
    val lineColors = ArrayList<Int>()
    val measured = ArrayList<String>()
    /** Faktor Breite je Zeichen und Pixel-Größe für [measureText]. */
    var measureFactor = 0.6f

    fun count(name: String): Int = counts[name] ?: 0
    private fun hit(name: String) { counts[name] = (counts[name] ?: 0) + 1 }
    fun reset() {
        glowRadii.clear(); fillRects.clear(); polygons.clear()
        counts.clear(); texts.clear(); polygonColors.clear(); fillColors.clear(); glowColors.clear(); imageHandles.clear()
        regionHandles.clear(); translations.clear(); scales.clear(); layerOrder.clear()
        circles.clear(); polyHeads.clear(); strokeRectColors.clear(); lineColors.clear(); measured.clear()
        depth = 0; minDepth = 0; maxDepth = 0; layerBegins = 0; layerHits = 0; gradientCalls = 0; gradientColors.clear()
    }
    fun totalCalls(): Int = counts.values.sum()

    override fun save() { hit("save"); depth++; if (depth > maxDepth) maxDepth = depth }
    override fun restore() { hit("restore"); depth--; if (depth < minDepth) minDepth = depth }
    override fun translate(dx: Float, dy: Float) { hit("translate"); translations.add(floatArrayOf(dx, dy)) }
    override fun rotate(rad: Float) { hit("rotate") }
    override fun scale(sx: Float, sy: Float) { hit("scale"); scales.add(sx) }
    override fun clipRect(x: Float, y: Float, w: Float, h: Float) { hit("clipRect") }
    override fun clipCircle(cx: Float, cy: Float, r: Float) { hit("clipCircle") }
    override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) { hit("fillRect"); fillColors.add(color); fillRects.add(RectRec(x, y, w, h, color)) }
    override fun strokeRect(x: Float, y: Float, w: Float, h: Float, width: Float, color: Int) { hit("strokeRect"); strokeRectColors.add(color) }
    override fun fillPolygon(xy: FloatArray, count: Int, color: Int) { hit("fillPolygon"); polygonColors.add(color); if (recordPolygons) polygons.add(PolyRec(color, xy.copyOf(count * 2))); if (count > 0) {
        var lo = xy[0]; var hi = xy[0]
        for (k in 1 until count) { val v = xy[k * 2]; if (v < lo) lo = v; if (v > hi) hi = v }
        polyHeads.add(PolyHead(color, xy[0], xy[1], lo, hi))
    } }
    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, roundCap: Boolean) { hit("line"); fillColors.add(color); lineColors.add(color) }
    override fun dashedLine(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, dash: Float, gap: Float) { hit("dashedLine"); fillColors.add(color) }
    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) { hit("fillCircle"); fillColors.add(color); circles.add(CircleRec(cx, cy, r, color)) }
    override fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int) { hit("strokeCircle"); fillColors.add(color) }
    override fun gradientRect(x: Float, y: Float, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray) {
        hit("gradientRect"); gradientCalls++; gradientColors.add(colors.copyOf())
        require(colors.size == stops.size) { "colors/stops mismatch" }
        for (i in 1 until stops.size) require(stops[i] >= stops[i - 1]) { "stops not ascending" }
    }
    override fun glow(cx: Float, cy: Float, r: Float, color: Int) { hit("glow"); glowColors.add(color); glowRadii.add(r) }
    override fun text(text: String, x: Float, y: Float, sizePx: Float, color: Int, align: TextAlign, bold: Boolean) { hit("text"); texts.add(text) }
    override fun measureText(text: String, sizePx: Float, bold: Boolean): Float { measured.add(text); return text.length * sizePx * measureFactor }
    override fun image(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) { hit("image"); imageHandles[handle] = (imageHandles[handle] ?: 0) + 1 }
    override fun imageRegion(handle: Int, u0: Float, v0: Float, u1: Float, v1: Float, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        hit("imageRegion"); regionHandles[handle] = (regionHandles[handle] ?: 0) + 1
        require(u1 > u0 && v1 > v0) { "empty region" }
    }

    override fun beginLayer(layerId: Int, key: Long, width: Int, height: Int, offsetX: Float, offsetY: Float): Boolean {
        layerOrder.add(layerId)
        if (!cacheLayers) { save(); translate(offsetX, offsetY); layerBegins++; return true }
        if (layerKeys[layerId] == key) { layerHits++; return false }
        layerKeys[layerId] = key
        layerBegins++
        save()
        return true
    }

    override fun endLayer(layerId: Int) { restore() }

    override fun registerImage(spec: ImageSpec): Int {
        val h = nextHandle++
        images[h] = spec
        hit("registerImage")
        return h
    }

    override fun releaseImage(handle: Int) { released++; images.remove(handle) }
}

/** Test-Ziel mit fester Größe und Senke. */
class TestTarget(
    override val widthPx: Int = 1280,
    override val heightPx: Int = 576,
    override val density: Float = 1f,
    override val sink: DrawSink = RecordingDrawSink(),
) : RenderTarget
