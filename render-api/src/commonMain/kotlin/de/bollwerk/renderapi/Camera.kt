package de.bollwerk.renderapi

/**
 * 2D-Kamera Welt (Meter, y nach unten) ↔ Bildschirm (Pixel, y nach unten).
 * Kein Sim-State: Kamera, Shake und Zoom beeinflussen die Simulation nie.
 *
 * Standard-Zoom 1 entspricht 1 m = [dpPerMeter] dp (Stil-Bibel: 24 dp).
 */
class Camera(
    viewportWidthPx: Float = 1f,
    viewportHeightPx: Float = 1f,
    /** Pixel pro dp. */
    var density: Float = 1f,
    val dpPerMeter: Float = 24f,
    var minZoom: Float = 0.25f,
    var maxZoom: Float = 4f,
) {
    var viewportWidth: Float = viewportWidthPx; private set
    var viewportHeight: Float = viewportHeightPx; private set

    /** Weltpunkt in der Bildschirmmitte. */
    var centerX: Float = 0f
    var centerY: Float = 0f

    var zoom: Float = 1f
        private set

    /** Grenzen in Weltkoordinaten (±Unendlich = unbegrenzt), siehe [setBounds]. */
    var boundsMinX: Float = Float.NEGATIVE_INFINITY; private set
    var boundsMinY: Float = Float.NEGATIVE_INFINITY; private set
    var boundsMaxX: Float = Float.POSITIVE_INFINITY; private set
    var boundsMaxY: Float = Float.POSITIVE_INFINITY; private set

    /** Bildschirm-Shake-Offset in px (nur Darstellung). */
    var shakeX: Float = 0f
    var shakeY: Float = 0f

    /** Pixel pro Meter beim aktuellen Zoom. */
    val scale: Float get() = dpPerMeter * density * zoom

    fun setViewport(widthPx: Float, heightPx: Float, density: Float = this.density) {
        viewportWidth = widthPx
        viewportHeight = heightPx
        this.density = density
        clampToBounds()
    }

    fun setBounds(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        boundsMinX = minX; boundsMinY = minY; boundsMaxX = maxX; boundsMaxY = maxY
        clampToBounds()
    }

    fun setZoom(z: Float) {
        zoom = z.coerceIn(minZoom, maxZoom)
        clampToBounds()
    }

    fun worldToScreenX(wx: Float): Float = (wx - centerX) * scale + viewportWidth * 0.5f + shakeX
    fun worldToScreenY(wy: Float): Float = (wy - centerY) * scale + viewportHeight * 0.5f + shakeY
    fun screenToWorldX(sx: Float): Float = (sx - shakeX - viewportWidth * 0.5f) / scale + centerX
    fun screenToWorldY(sy: Float): Float = (sy - shakeY - viewportHeight * 0.5f) / scale + centerY

    /** Länge in Metern → Pixel. */
    fun metersToPx(m: Float): Float = m * scale

    /** Länge in Pixel → Meter. */
    fun pxToMeters(px: Float): Float = px / scale

    /** Sichtbarer Weltbereich (ohne Shake). */
    val visibleMinX: Float get() = centerX - viewportWidth * 0.5f / scale
    val visibleMaxX: Float get() = centerX + viewportWidth * 0.5f / scale
    val visibleMinY: Float get() = centerY - viewportHeight * 0.5f / scale
    val visibleMaxY: Float get() = centerY + viewportHeight * 0.5f / scale

    /** Verschiebt um eine Fingerbewegung in px (Inhalt folgt dem Finger). */
    fun pan(dxPx: Float, dyPx: Float) {
        centerX -= dxPx / scale
        centerY -= dyPx / scale
        clampToBounds()
    }

    /** Zoomt um [scaleFactor], wobei der Weltpunkt unter ([focusX], [focusY]) fest bleibt. */
    fun pinch(focusX: Float, focusY: Float, scaleFactor: Float) {
        val wx = screenToWorldX(focusX)
        val wy = screenToWorldY(focusY)
        zoom = (zoom * scaleFactor).coerceIn(minZoom, maxZoom)
        centerX = wx - (focusX - shakeX - viewportWidth * 0.5f) / scale
        centerY = wy - (focusY - shakeY - viewportHeight * 0.5f) / scale
        clampToBounds()
    }

    /** Wählt Zoom und Mitte so, dass das Rechteck mit [paddingPx] Rand vollständig sichtbar ist. */
    fun fitRect(minX: Float, minY: Float, maxX: Float, maxY: Float, paddingPx: Float = 0f) {
        val rw = (maxX - minX).coerceAtLeast(1e-3f)
        val rh = (maxY - minY).coerceAtLeast(1e-3f)
        val base = dpPerMeter * density
        val zx = (viewportWidth - 2f * paddingPx).coerceAtLeast(1f) / (rw * base)
        val zy = (viewportHeight - 2f * paddingPx).coerceAtLeast(1f) / (rh * base)
        zoom = minOf(zx, zy).coerceIn(minZoom, maxZoom)
        centerX = (minX + maxX) * 0.5f
        centerY = (minY + maxY) * 0.5f
        clampToBounds()
    }

    /** Hält den sichtbaren Bereich innerhalb der Grenzen; ist er größer, wird zentriert. */
    fun clampToBounds() {
        if (scale <= 0f) return
        centerX = clampAxis(centerX, viewportWidth * 0.5f / scale, boundsMinX, boundsMaxX)
        centerY = clampAxis(centerY, viewportHeight * 0.5f / scale, boundsMinY, boundsMaxY)
    }

    private fun clampAxis(c: Float, half: Float, lo: Float, hi: Float): Float {
        if (lo == Float.NEGATIVE_INFINITY || hi == Float.POSITIVE_INFINITY) return c
        return if (hi - lo <= 2f * half) (lo + hi) * 0.5f else c.coerceIn(lo + half, hi - half)
    }
}
