package de.bollwerk.simrunner

import de.bollwerk.renderapi.DrawSink
import de.bollwerk.renderapi.ImageSpec
import de.bollwerk.renderapi.RenderTarget
import de.bollwerk.renderapi.TextAlign
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.CompositeContext
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.LinearGradientPaint
import java.awt.RadialGradientPaint
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.awt.image.Raster
import java.awt.image.WritableRaster

/**
 * Minimale java.awt-Senke (Kopie der render-api-Vorschau-Senke aus jvmTest, dort nicht auf dem Klassenpfad): zeichnet [DrawSink] in ein [BufferedImage].
 * Entspricht dem, was der Simrunner (WP12) später als PNG-Senke braucht; das Glühen (`glow`) ist additiv
 * wie auf Android (PorterDuff.ADD).
 */
class AwtDrawSink(val image: BufferedImage) : DrawSink {
    private val g: Graphics2D = image.createGraphics().also {
        it.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        it.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        it.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        it.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        it.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }
    private val transforms = ArrayList<AffineTransform>()
    private val clips = ArrayList<Shape?>()
    private val images = HashMap<Int, BufferedImage>()
    private var nextHandle = 1
    private val path = Path2D.Float()
    private val layers = HashMap<Int, Pair<Long, BufferedImage>>()
    override fun invalidateLayers() { layers.clear() }
    private var layerStack = ArrayList<Triple<Int, Graphics2D, BufferedImage>>()
    private var layerOffset = ArrayList<FloatArray>()
    private var cur: Graphics2D = g
    var cacheHits = 0
    var layerRecords = 0

    fun dispose() { g.dispose() }

    override fun save() { transforms.add(cur.transform); clips.add(cur.clip) }
    override fun restore() {
        val n = transforms.size - 1
        cur.transform = transforms.removeAt(n)
        cur.clip = clips.removeAt(n)
    }
    override fun translate(dx: Float, dy: Float) { cur.translate(dx.toDouble(), dy.toDouble()) }
    override fun rotate(rad: Float) { cur.rotate(rad.toDouble()) }
    override fun scale(sx: Float, sy: Float) { cur.scale(sx.toDouble(), sy.toDouble()) }
    override fun clipRect(x: Float, y: Float, w: Float, h: Float) { cur.clip(Rectangle2D.Float(x, y, w, h)) }
    override fun clipCircle(cx: Float, cy: Float, r: Float) { cur.clip(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r)) }

    private fun col(c: Int) = Color(c, true)

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        cur.color = col(color); cur.fill(Rectangle2D.Float(x, y, w, h))
    }
    override fun strokeRect(x: Float, y: Float, w: Float, h: Float, width: Float, color: Int) {
        cur.color = col(color); cur.stroke = BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER); cur.draw(Rectangle2D.Float(x, y, w, h))
    }
    override fun fillPolygon(xy: FloatArray, count: Int, color: Int) {
        if (count < 3) return
        path.reset(); path.moveTo(xy[0], xy[1])
        for (i in 1 until count) path.lineTo(xy[i * 2], xy[i * 2 + 1])
        path.closePath()
        cur.color = col(color); cur.fill(path)
    }
    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, roundCap: Boolean) {
        cur.color = col(color)
        cur.stroke = BasicStroke(width, if (roundCap) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        cur.draw(Line2D.Float(x0, y0, x1, y1))
    }
    override fun dashedLine(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, dash: Float, gap: Float) {
        cur.color = col(color)
        cur.stroke = BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(maxOf(dash, 1e-3f), maxOf(gap, 1e-3f)), 0f)
        cur.draw(Line2D.Float(x0, y0, x1, y1))
    }
    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {
        cur.color = col(color); cur.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
    }
    override fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int) {
        cur.color = col(color); cur.stroke = BasicStroke(width); cur.draw(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
    }
    override fun gradientRect(x: Float, y: Float, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray) {
        // LinearGradientPaint verlangt streng aufsteigende Stützstellen
        val fr = stops.copyOf()
        for (i in 1 until fr.size) if (fr[i] <= fr[i - 1]) fr[i] = fr[i - 1] + 1e-4f
        val cs = Array(colors.size) { col(colors[it]) }
        if (x0 == x1 && y0 == y1) { cur.color = cs[0]; cur.fill(Rectangle2D.Float(x, y, w, h)); return }
        cur.paint = LinearGradientPaint(Point2D.Float(x0, y0), Point2D.Float(x1, y1), fr, cs)
        cur.fill(Rectangle2D.Float(x, y, w, h))
    }
    override fun glow(cx: Float, cy: Float, r: Float, color: Int) {
        val c = col(color)
        val c0 = Color(c.red, c.green, c.blue, c.alpha)
        val c1 = Color(c.red, c.green, c.blue, 0)
        cur.paint = RadialGradientPaint(Point2D.Float(cx, cy), r, floatArrayOf(0f, 1f), arrayOf(c0, c1))
        val old = cur.composite
        cur.composite = ADD
        cur.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
        cur.composite = old
    }
    override fun text(text: String, x: Float, y: Float, sizePx: Float, color: Int, align: TextAlign, bold: Boolean) {
        cur.font = Font("SansSerif", if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(sizePx)
        cur.color = col(color)
        val w = cur.fontMetrics.stringWidth(text)
        val dx = when (align) { TextAlign.LEFT -> 0f; TextAlign.CENTER -> -w / 2f; TextAlign.RIGHT -> -w.toFloat() }
        cur.drawString(text, x + dx, y)
    }
    override fun measureText(text: String, sizePx: Float, bold: Boolean): Float {
        val f = Font("SansSerif", if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(sizePx)
        return g.getFontMetrics(f).stringWidth(text).toFloat()
    }
    override fun image(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        val img = images[handle] ?: return
        val old = cur.composite
        if (alpha < 1f) cur.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        cur.drawImage(img, AffineTransform(w / img.width, 0f, 0f, h / img.height, x, y), null)
        cur.composite = old
    }
    override fun imageRegion(handle: Int, u0: Float, v0: Float, u1: Float, v1: Float, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        val img = images[handle] ?: return
        val old = cur.composite
        if (alpha < 1f) cur.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        // Quellbereich (Texel) exakt in das Zielrechteck abbilden; Clip auf das Ziel verhindert Überstände
        val sw = (u1 - u0) * img.width
        val sh = (v1 - v0) * img.height
        val oc = cur.clip
        cur.clip(Rectangle2D.Float(x, y, w, h))
        cur.drawImage(img, AffineTransform(w / sw, 0f, 0f, h / sh, x - u0 * img.width * (w / sw), y - v0 * img.height * (h / sh)).also { it.concatenate(AffineTransform.getScaleInstance(1.0, 1.0)) }, null)
        cur.clip = oc
        cur.composite = old
    }

    override fun beginLayer(layerId: Int, key: Long, width: Int, height: Int, offsetX: Float, offsetY: Float): Boolean {
        val cached = layers[layerId]
        if (cached != null && cached.first == key && cached.second.width == width && cached.second.height == height) {
            cacheHits++
            cur.drawImage(cached.second, offsetX.toInt(), offsetY.toInt(), null)
            return false
        }
        layerRecords++
        val bmp = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val lg = bmp.createGraphics()
        lg.setRenderingHints(g.renderingHints)
        layerStack.add(Triple(layerId, cur, bmp))
        layerOffset.add(floatArrayOf(offsetX, offsetY, key.toFloat()))
        layers[layerId] = Pair(key, bmp)
        pushLayerGraphics(lg)
        return true
    }

    private val graphicsStack = ArrayList<Graphics2D>()
    private fun pushLayerGraphics(lg: Graphics2D) { graphicsStack.add(lg); cur = lg }

    override fun endLayer(layerId: Int) {
        val (_, parent, bmp) = layerStack.removeAt(layerStack.size - 1)
        val off = layerOffset.removeAt(layerOffset.size - 1)
        graphicsStack.removeAt(graphicsStack.size - 1).dispose()
        cur = parent
        cur.drawImage(bmp, off[0].toInt(), off[1].toInt(), null)
    }

    override fun registerImage(spec: ImageSpec): Int {
        val bi = BufferedImage(spec.width, spec.height, BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, spec.width, spec.height, spec.argb, 0, spec.width)
        val h = nextHandle++
        images[h] = bi
        return h
    }
    override fun releaseImage(handle: Int) { images.remove(handle) }

    /** Additive Überblendung (Android `PorterDuff.Mode.ADD`): dst + src·alpha, begrenzt auf 255. */
    private object ADD : Composite {
        override fun createContext(srcColorModel: ColorModel, dstColorModel: ColorModel, hints: java.awt.RenderingHints?): CompositeContext =
            object : CompositeContext {
                override fun dispose() {}
                override fun compose(src: Raster, dstIn: Raster, dstOut: WritableRaster) {
                    val w = minOf(src.width, dstIn.width); val h = minOf(src.height, dstIn.height)
                    val s = IntArray(4); val d = IntArray(4)
                    for (y in 0 until h) for (x in 0 until w) {
                        src.getPixel(x, y, s); dstIn.getPixel(x, y, d)
                        val a = s[3] / 255f
                        d[0] = minOf(255, d[0] + (s[0] * a).toInt()); d[1] = minOf(255, d[1] + (s[1] * a).toInt()); d[2] = minOf(255, d[2] + (s[2] * a).toInt())
                        dstOut.setPixel(x, y, d)
                    }
                }
            }
    }
}

/** Ziel für die Vorschau: BufferedImage in [widthPx] × [heightPx] mit [density]. */
class AwtTarget(override val widthPx: Int, override val heightPx: Int, override val density: Float = 1f) : RenderTarget {
    val image = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB)
    val awt = AwtDrawSink(image)
    override val sink: DrawSink get() = awt
}
