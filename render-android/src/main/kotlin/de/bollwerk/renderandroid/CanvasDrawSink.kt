package de.bollwerk.renderandroid

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import de.bollwerk.renderapi.DrawSink
import de.bollwerk.renderapi.ImageSpec
import de.bollwerk.renderapi.TextAlign
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Android-Implementierung von [DrawSink] auf `android.graphics.Canvas` (am Render-Thread: `lockHardwareCanvas`).
 *
 * **Allokationsfrei im Dauerbetrieb:** Paints, `Path`, `RectF`, Dash-Puffer und Verläufe sind wiederverwendet bzw.
 * gecacht; Einzelheiten:
 *  - **Verläufe** ([GradientCache]): Lineare Verläufe sind **lageunabhängig**: gecacht wird ein Einheitsverlauf von (0,0) nach
 *    (1,0) je (Farben, Stützstellen); [gradientRect] clippt auf das Rechteck und platziert ihn über die Canvas-Matrix
 *    (translate, rotate, scale auf Länge) – so trifft ein Himmel, der bei Pan/Zoom jeden Frame woandershin rutscht, immer
 *    den Cache. Verläufe, die sich nur im gemeinsamen Alpha unterscheiden (Feuerschein), teilen einen Shader; das Alpha
 *    steht am Paint. Radiale Scheine ([glow]) sind ebenso Einheitsverläufe je RGB-Farbe (kein `setLocalMatrix`).
 *  - **Texturen** ([registerImage]): Bitmap + `BitmapShader(REPEAT, CLAMP)` im Texel-Raum (lokale Matrix = Einheit).
 *    [imageRegion] verschiebt/skaliert das *Canvas* (nicht den Shader) auf den Ausschnitt und füllt ein Rechteck im
 *    Texel-Raum; die Drehung des Balkens steckt in der Canvas-Matrix, die Textur folgt ihr also. Das vermeidet
 *    `Shader.setLocalMatrix` je Aufruf (verwirft den nativen Shader und legt Cleaner-Objekte an).
 *    Die REPEAT-Kachelung schließt die Stöße zwischen den Perioden ohne Naht, Mipmaps entschärfen Flimmern beim Auszoomen.
 *  - **Bitmap-Cache** ([RefCountedCache]): Schlüssel `ImageSpec.id` + Größe (die ID trägt die Texel-Dichte, z. B.
 *    `wood@72`). Freigegebene Texturen bleiben im Leerlauf-Cache (Partie-Neustart, gleiche Dichte), bis das Budget
 *    überschritten ist oder [trimMemory] sie verwirft.
 *  - **Statische Ebenen** ([beginLayer]): Himmel/Gelände werden in Offscreen-Bitmaps aufgezeichnet und bei unverändertem
 *    Schlüssel nur noch aufgeblendet. Ändert sich der Schlüssel in aufeinanderfolgenden Frames (Kamera wird bewegt),
 *    zeichnet die Senke direkt auf die GPU-Fläche, statt je Frame zwei Vollbilder auf der CPU zu rastern; sobald der
 *    Schlüssel mindestens [LayerState.DEFAULT_STABLE_FRAMES] Frames stillsteht (Hysterese), wird einmal aufgezeichnet.
 *
 * Nicht thread-sicher bis auf [trimMemory] (merkt sich die Stufe, angewendet wird sie am Anfang des nächsten Frames
 * bzw. über [applyPendingTrim]).
 */
class CanvasDrawSink internal constructor(
    typefaces: TypefaceProvider,
    maxIdleTextureBytes: Long,
    private val graphics: GraphicsFactory,
) : DrawSink {

    constructor(
        typefaces: TypefaceProvider = TypefaceProvider.SYSTEM,
        maxIdleTextureBytes: Long = DEFAULT_IDLE_TEXTURE_BYTES,
    ) : this(typefaces, maxIdleTextureBytes, AndroidGraphics)

    /** Schrift der Spielfläche; austauschbar (Einstellungen). */
    var typefaces: TypefaceProvider = typefaces
        set(value) {
            field = value
            fakeBold = value.typeface(true) === value.typeface(false)
            lastTypeface = null
        }
    private var fakeBold = typefaces.typeface(true) === typefaces.typeface(false)
    private var lastTypeface: Typeface? = null

    private var cur: Canvas? = null
    private var frameCanvas: Canvas? = null

    // ---- Paints, Path, RectF (einmal angelegt) ----
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { style = Paint.Style.FILL }
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val layerPaint = Paint() // Ebenen: ganzzahlig, ungefiltert
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        // Stil-Bibel §3: Zahlen immer tabular-nums, damit Chips (Länge, Kosten, TP) beim Ziehen nicht in der Breite zucken
        fontFeatureSettings = TABULAR_NUMS
    }
    private val path = Path()
    private val clipPath = Path()
    private val rect = RectF()
    private val regionOut = FloatArray(4)
    private var dashBuf = FloatArray(64 * 4)
    private val normColors = IntArray(MAX_GRADIENT_STOPS)

    // ---- Verläufe ----
    /** Einheitsverläufe (0,0)→(1,0), nur nach Farben/Stützstellen geschlüsselt; die Lage steckt in der Canvas-Matrix. */
    private val linearCache = GradientCache(64, GradientFactory<LinearGradient> { x0, y0, x1, y1, colors, n, stops ->
        LinearGradient(x0, y0, x1, y1, colors.copyOf(n), stops.copyOf(n), Shader.TileMode.CLAMP)
    })
    private val glowCache = IntValueCache(32, IntFactory<RadialGradient> { rgb ->
        RadialGradient(0f, 0f, 1f, intArrayOf(rgb or OPAQUE, rgb and 0xFFFFFF), null, Shader.TileMode.CLAMP)
    })

    // ---- Bitmap-Cache ----
    private class Texture(val bitmap: Bitmap, val shader: BitmapShader, val width: Int, val height: Int, val bytes: Long)

    private val textureCache = RefCountedCache<Texture>(maxIdleTextureBytes) { graphics.recycle(it.bitmap) }
    private var handleTextures = arrayOfNulls<Texture>(32)
    private var handleKeys = arrayOfNulls<String>(32)
    private var freeHandles = IntArray(32)
    private var freeCount = 0
    private var nextHandle = 1
    private var anonymousImages = 0

    /** Budget für ungenutzte Texturen; nachträglich änderbar. */
    var maxIdleTextureBytes: Long
        get() = textureCache.maxIdleBytes
        set(value) { textureCache.maxIdleBytes = value }

    // ---- Ebenen ----
    private class LayerSlot {
        val state = LayerState()
        var bitmap: Bitmap? = null
        var canvas: Canvas? = null
        var width = 0
        var height = 0
    }

    private val layers = Array(MAX_LAYERS) { LayerSlot() }
    private val layerStackSlot = arrayOfNulls<LayerSlot>(MAX_LAYER_DEPTH)
    private val layerStackParent = arrayOfNulls<Canvas>(MAX_LAYER_DEPTH)
    private val layerStackOffX = FloatArray(MAX_LAYER_DEPTH)
    private val layerStackOffY = FloatArray(MAX_LAYER_DEPTH)
    private var layerDepth = 0

    /** Zähler (Tests, Debug-Anzeige). */
    var layerRecords = 0; private set
    var layerBlits = 0; private set
    var layerDirectDraws = 0; private set

    private val pendingTrim = AtomicInteger(0)

    // ---------------------------------------------------------------------------------------------
    // Frame
    // ---------------------------------------------------------------------------------------------

    /** Beginnt einen Frame auf [canvas] (am Render-Thread die Hardware-Canvas der Oberfläche). */
    fun begin(canvas: Canvas) {
        applyPendingTrim()
        frameCanvas = canvas
        cur = canvas
        layerDepth = 0
    }

    /** Beendet den Frame (Canvas nicht länger halten). */
    fun end() {
        cur = null
        frameCanvas = null
        layerDepth = 0
    }

    private fun c(): Canvas = cur ?: error("CanvasDrawSink: draw call outside begin()/end()")

    // ---------------------------------------------------------------------------------------------
    // Transformation und Clip
    // ---------------------------------------------------------------------------------------------

    override fun save() { c().save() }
    override fun restore() { c().restore() }
    override fun translate(dx: Float, dy: Float) { c().translate(dx, dy) }
    override fun rotate(rad: Float) { c().rotate(rad * DrawMath.RAD_TO_DEG) }
    override fun scale(sx: Float, sy: Float) { c().scale(sx, sy) }
    override fun clipRect(x: Float, y: Float, w: Float, h: Float) { c().clipRect(x, y, x + w, y + h) }

    override fun clipCircle(cx: Float, cy: Float, r: Float) {
        clipPath.rewind()
        clipPath.addCircle(cx, cy, r, Path.Direction.CW)
        c().clipPath(clipPath)
    }

    // ---------------------------------------------------------------------------------------------
    // Formen
    // ---------------------------------------------------------------------------------------------

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        fillPaint.color = color
        c().drawRect(x, y, x + w, y + h, fillPaint)
    }

    override fun strokeRect(x: Float, y: Float, w: Float, h: Float, width: Float, color: Int) {
        val p = strokePaint
        p.color = color
        p.strokeWidth = width
        p.strokeCap = Paint.Cap.BUTT
        p.strokeJoin = Paint.Join.MITER
        c().drawRect(x, y, x + w, y + h, p)
    }

    override fun fillPolygon(xy: FloatArray, count: Int, color: Int) {
        if (count < 3) return
        val pa = path
        pa.rewind()
        pa.moveTo(xy[0], xy[1])
        for (i in 1 until count) pa.lineTo(xy[i * 2], xy[i * 2 + 1])
        pa.close()
        fillPaint.color = color
        c().drawPath(pa, fillPaint)
    }

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, roundCap: Boolean) {
        val p = strokePaint
        p.color = color
        p.strokeWidth = width
        p.strokeCap = if (roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
        p.strokeJoin = Paint.Join.ROUND
        c().drawLine(x0, y0, x1, y1, p)
    }

    override fun dashedLine(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, dash: Float, gap: Float) {
        val need = DrawMath.segmentsNeeded(x0, y0, x1, y1, dash, gap) * 4
        if (dashBuf.size < need) dashBuf = FloatArray(need + need / 2)
        val n = DrawMath.dashSegments(x0, y0, x1, y1, dash, gap, dashBuf)
        if (n == 0) return
        val p = strokePaint
        p.color = color
        p.strokeWidth = width
        p.strokeCap = Paint.Cap.BUTT
        p.strokeJoin = Paint.Join.ROUND
        c().drawLines(dashBuf, 0, n * 4, p)
    }

    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {
        fillPaint.color = color
        c().drawCircle(cx, cy, r, fillPaint)
    }

    override fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int) {
        val p = strokePaint
        p.color = color
        p.strokeWidth = width
        p.strokeCap = Paint.Cap.BUTT
        p.strokeJoin = Paint.Join.MITER
        c().drawCircle(cx, cy, r, p)
    }

    override fun gradientRect(
        x: Float, y: Float, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float,
        colors: IntArray, stops: FloatArray,
    ) {
        val n = if (colors.size < stops.size) colors.size else stops.size
        if (n == 0) return
        val canvas = c()
        if (n == 1 || (x0 == x1 && y0 == y1)) {
            fillPaint.color = colors[0]
            canvas.drawRect(x, y, x + w, y + h, fillPaint)
            return
        }
        if (n > MAX_GRADIENT_STOPS) return
        val maxA = DrawMath.normalizeAlpha(colors, n, normColors)
        if (maxA == 0) return
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        val shader = linearCache.get(0f, 0f, 1f, 0f, normColors, n, stops)
        val p = gradientPaint
        p.shader = shader
        p.alpha = maxA
        // Einheitsverlauf über die Canvas-Matrix auf die Strecke (x0,y0)–(x1,y1) legen, auf das Rechteck beschränkt
        canvas.save()
        canvas.clipRect(x, y, x + w, y + h)
        canvas.translate(x0, y0)
        if (dy != 0f || dx < 0f) canvas.rotate(atan2(dy, dx) * DrawMath.RAD_TO_DEG)
        canvas.scale(len, len)
        canvas.drawPaint(p)
        canvas.restore()
    }

    override fun glow(cx: Float, cy: Float, r: Float, color: Int) {
        val a = color ushr 24
        if (a == 0 || r <= 0f) return
        val p = glowPaint
        p.shader = glowCache.get(color and 0xFFFFFF)
        p.alpha = a
        // Einheitsverlauf über die Canvas-Matrix platzieren: der Shader liegt im lokalen Raum und folgt ihr.
        val canvas = c()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(r, r)
        canvas.drawCircle(0f, 0f, 1f, p)
        canvas.restore()
    }

    // ---------------------------------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------------------------------

    private fun prepareText(sizePx: Float, bold: Boolean) {
        val p = textPaint
        val tf = typefaces.typeface(bold)
        if (tf !== lastTypeface) { p.typeface = tf; lastTypeface = tf }
        p.isFakeBoldText = bold && fakeBold
        p.textSize = sizePx
    }

    override fun text(text: String, x: Float, y: Float, sizePx: Float, color: Int, align: TextAlign, bold: Boolean) {
        prepareText(sizePx, bold)
        val p = textPaint
        p.color = color
        p.textAlign = when (align) {
            TextAlign.LEFT -> Paint.Align.LEFT
            TextAlign.CENTER -> Paint.Align.CENTER
            TextAlign.RIGHT -> Paint.Align.RIGHT
        }
        c().drawText(text, x, y, p)
    }

    override fun measureText(text: String, sizePx: Float, bold: Boolean): Float {
        prepareText(sizePx, bold)
        return textPaint.measureText(text)
    }

    // ---------------------------------------------------------------------------------------------
    // Bilder
    // ---------------------------------------------------------------------------------------------

    private fun texture(handle: Int): Texture? =
        if (handle > 0 && handle < handleTextures.size) handleTextures[handle] else null

    override fun image(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        val t = texture(handle) ?: return
        if (alpha <= 0f) return
        bitmapPaint.alpha = DrawMath.alpha255(alpha)
        rect.set(x, y, x + w, y + h)
        c().drawBitmap(t.bitmap, null, rect, bitmapPaint)
    }

    override fun imageRegion(
        handle: Int, u0: Float, v0: Float, u1: Float, v1: Float, x: Float, y: Float, w: Float, h: Float, alpha: Float,
    ) {
        val t = texture(handle) ?: return
        if (alpha <= 0f) return
        val o = regionOut
        if (!DrawMath.regionTransform(u0, v0, u1, v1, x, y, w, h, t.width, t.height, o)) return
        val p = shaderPaint
        p.shader = t.shader
        p.alpha = DrawMath.alpha255(alpha)
        val canvas = c()
        canvas.save()
        canvas.translate(o[0], o[1])
        canvas.scale(o[2], o[3])
        canvas.drawRect(u0 * t.width, v0 * t.height, u1 * t.width, v1 * t.height, p)
        canvas.restore()
    }

    override fun registerImage(spec: ImageSpec): Int {
        val key = if (spec.id.isEmpty()) "anon#${anonymousImages++}" else "${spec.id}|${spec.width}x${spec.height}"
        var tex = textureCache.acquire(key)
        if (tex == null) {
            tex = createTexture(spec) ?: return -1
            textureCache.insert(key, tex, tex.bytes)
        }
        val handle = if (freeCount > 0) freeHandles[--freeCount] else nextHandle++
        if (handle >= handleTextures.size) {
            val n = handle * 2
            handleTextures = handleTextures.copyOf(n)
            handleKeys = handleKeys.copyOf(n)
        }
        handleTextures[handle] = tex
        handleKeys[handle] = key
        return handle
    }

    private fun createTexture(spec: ImageSpec): Texture? {
        return try {
            val bmp = graphics.textureBitmap(spec) ?: return null
            val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
            Texture(bmp, shader, spec.width, spec.height, spec.width.toLong() * spec.height * 4L)
        } catch (_: OutOfMemoryError) {
            textureCache.trimIdle()
            null
        }
    }

    override fun releaseImage(handle: Int) {
        if (handle <= 0 || handle >= handleTextures.size) return
        val key = handleKeys[handle] ?: return
        handleTextures[handle] = null
        handleKeys[handle] = null
        if (freeCount == freeHandles.size) freeHandles = freeHandles.copyOf(freeCount * 2)
        freeHandles[freeCount++] = handle
        textureCache.release(key)
    }

    // ---------------------------------------------------------------------------------------------
    // Statische Ebenen
    // ---------------------------------------------------------------------------------------------

    override fun beginLayer(layerId: Int, key: Long, width: Int, height: Int, offsetX: Float, offsetY: Float): Boolean {
        val canvas = c()
        val depth = layerDepth
        val slot = if (layerId in 0 until MAX_LAYERS && width > 0 && height > 0) layers[layerId] else null
        if (slot == null || depth >= MAX_LAYER_DEPTH) return beginDirect(canvas, null, offsetX, offsetY)

        val bmp = slot.bitmap
        when (slot.state.decide(key, width, height, bmp != null && !graphics.isRecycled(bmp))) {
            LayerDecision.BLIT -> {
                layerBlits++
                canvas.drawBitmap(bmp!!, Math.round(offsetX).toFloat(), Math.round(offsetY).toFloat(), layerPaint)
                return false
            }
            LayerDecision.DIRECT -> {
                layerDirectDraws++
                return beginDirect(canvas, null, offsetX, offsetY)
            }
            LayerDecision.RECORD -> Unit
        }
        val rec = ensureLayerBitmap(slot, width, height) ?: return beginDirect(canvas, null, offsetX, offsetY)
        rec.eraseColor(0)
        val lc = slot.canvas!!
        lc.restoreToCount(1)
        slot.state.recording(key, width, height)
        layerStackSlot[depth] = slot
        layerStackParent[depth] = canvas
        layerStackOffX[depth] = offsetX
        layerStackOffY[depth] = offsetY
        layerDepth = depth + 1
        cur = lc
        layerRecords++
        return true
    }

    private fun beginDirect(canvas: Canvas, slot: LayerSlot?, offsetX: Float, offsetY: Float): Boolean {
        val depth = layerDepth
        if (depth >= MAX_LAYER_DEPTH) { // zu tief verschachtelt: ohne Buchführung direkt zeichnen
            canvas.save(); canvas.translate(offsetX, offsetY)
            return true
        }
        layerStackSlot[depth] = slot
        layerStackParent[depth] = canvas
        layerStackOffX[depth] = offsetX
        layerStackOffY[depth] = offsetY
        layerDepth = depth + 1
        canvas.save()
        canvas.translate(offsetX, offsetY)
        return true
    }

    override fun endLayer(layerId: Int) {
        if (layerDepth == 0) { cur?.restore(); return }
        val depth = --layerDepth
        val slot = layerStackSlot[depth]
        val parent = layerStackParent[depth]!!
        layerStackSlot[depth] = null
        layerStackParent[depth] = null
        if (slot == null) { // direkt gezeichnet
            parent.restore()
            return
        }
        slot.state.completed()
        cur = parent
        val bmp = slot.bitmap ?: return
        parent.drawBitmap(bmp, Math.round(layerStackOffX[depth]).toFloat(), Math.round(layerStackOffY[depth]).toFloat(), layerPaint)
    }

    private fun ensureLayerBitmap(slot: LayerSlot, width: Int, height: Int): Bitmap? {
        val old = slot.bitmap
        if (old != null && slot.width == width && slot.height == height && !graphics.isRecycled(old)) return old
        if (old != null) graphics.recycle(old)
        slot.bitmap = null; slot.canvas = null; slot.state.dropped()
        return try {
            val bmp = graphics.layerBitmap(width, height) ?: return null
            slot.bitmap = bmp
            slot.canvas = graphics.canvasFor(bmp)
            slot.width = width
            slot.height = height
            bmp
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    override fun invalidateLayers() {
        for (s in layers) s.state.invalidate()
    }

    /** Speicher der Ebenen-Bitmaps in Bytes (Debug-Anzeige). */
    val layerBytes: Long
        get() {
            var sum = 0L
            for (s in layers) if (s.bitmap != null) sum += s.width.toLong() * s.height * 4L
            return sum
        }

    /** Speicher aller Texturen (benutzt + Leerlauf) in Bytes. */
    val textureBytes: Long get() = textureCache.inUseBytes + textureCache.idleBytes
    val textureCount: Int get() = textureCache.size
    val textureCacheHits: Int get() = textureCache.hits
    val gradientCacheMisses: Int get() = linearCache.misses

    // ---------------------------------------------------------------------------------------------
    // Speicherdruck
    // ---------------------------------------------------------------------------------------------

    /**
     * Meldet Speicherdruck (`ComponentCallbacks2`-Stufe). Thread-sicher: die Freigabe geschieht erst am Anfang des
     * nächsten Frames ([begin]) oder über [applyPendingTrim], nie mitten im Zeichnen. Liefert die gewählte Aktion.
     */
    internal fun trimMemory(level: Int): TrimAction {
        val action = TrimPolicy.actionFor(level)
        if (action != TrimAction.NONE) {
            while (true) {
                val old = pendingTrim.get()
                if (old >= action.ordinal || pendingTrim.compareAndSet(old, action.ordinal)) break
            }
        }
        return action
    }

    /** Wendet eine gemerkte Trim-Stufe an. Nur auf dem Thread rufen, der gerade zeichnet (oder wenn keiner läuft). */
    fun applyPendingTrim() {
        val level = pendingTrim.getAndSet(0)
        if (level == 0) return
        applyTrim(TrimAction.entries[level])
    }

    /**
     * Gibt sofort frei, was [action] vorsieht (gleicher Thread-Vertrag wie [applyPendingTrim]). Unabhängig von einer
     * gemerkten Stufe: wer zuerst Texturen in den Leerlauf schiebt (Renderer abbauen) und danach trimmt, muss die
     * Leerlauf-Texturen auch wirklich loswerden, deshalb gibt es diese Methode statt eines Umwegs über den Zähler.
     */
    internal fun applyTrim(action: TrimAction) {
        if (action == TrimAction.NONE) return
        textureCache.trimIdle()
        if (action.ordinal >= TrimAction.LAYERS.ordinal) dropLayers()
    }

    private fun dropLayers() {
        for (s in layers) {
            s.state.dropped()
            s.canvas = null
            s.bitmap?.let { graphics.recycle(it) }
            s.bitmap = null
            s.width = 0; s.height = 0
        }
    }

    /** Gibt alle Bitmaps frei (Sink wird nicht mehr gebraucht). */
    fun release() {
        end()
        dropLayers()
        for (i in handleTextures.indices) { handleTextures[i] = null; handleKeys[i] = null }
        freeCount = 0
        nextHandle = 1
        textureCache.clear()
        linearCache.clear()
        glowCache.clear()
    }

    companion object {
        const val DEFAULT_IDLE_TEXTURE_BYTES: Long = 8L * 1024 * 1024
        private const val MAX_LAYERS = 8
        private const val MAX_LAYER_DEPTH = 4
        private const val MAX_GRADIENT_STOPS = 16
        private const val OPAQUE = 0xFF000000.toInt()
        private const val TABULAR_NUMS = "tnum"
    }
}
