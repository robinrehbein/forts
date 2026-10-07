package de.bollwerk.render.android.audio

import de.bollwerk.renderapi.Camera
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Pan und Entfernungsdämpfung aus der Welt-x-Position relativ zur Kamera. */
object SpatialMixer {
    /** Maximaler Stereo-Ausschlag (volles Links/Rechts wirkt auf Kopfhörern unnatürlich). */
    const val MAX_PAN = 0.85f
    const val MIN_GAIN = 0.2f

    /** −MAX_PAN..MAX_PAN; NaN → Mitte. [halfWidth] = halbe sichtbare Breite in Metern. */
    fun pan(worldX: Float, listenerX: Float, halfWidth: Float): Float {
        if (worldX != worldX || halfWidth <= 0f) return 0f
        return ((worldX - listenerX) / halfWidth).coerceIn(-1f, 1f) * MAX_PAN
    }

    /** 1 im Bild, danach abfallend ~1/(1+d) bis [MIN_GAIN]; NaN → 1. */
    fun gain(worldX: Float, listenerX: Float, halfWidth: Float): Float {
        if (worldX != worldX || halfWidth <= 0f) return 1f
        val over = abs(worldX - listenerX) / halfWidth - 1f
        if (over <= 0f) return 1f
        return (1f / (1f + over)).coerceAtLeast(MIN_GAIN)
    }

    fun pan(worldX: Float, camera: Camera): Float =
        pan(worldX, camera.centerX, camera.viewportWidth * 0.5f / camera.scale)

    fun gain(worldX: Float, camera: Camera): Float =
        gain(worldX, camera.centerX, camera.viewportWidth * 0.5f / camera.scale)

    /** Gleichleistungs-Panning; Mitte ergibt (1, 1). Liefert links in [0], rechts in [1]. */
    fun stereo(pan: Float, out: FloatArray) {
        val a = (pan.coerceIn(-1f, 1f) + 1f) * (Math.PI.toFloat() / 4f)
        out[0] = cos(a) * SQRT2
        out[1] = sin(a) * SQRT2
    }

    private const val SQRT2 = 1.41421356f
}
