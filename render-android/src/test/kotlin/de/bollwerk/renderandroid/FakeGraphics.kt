package de.bollwerk.renderandroid

import android.graphics.Bitmap
import android.graphics.Canvas
import de.bollwerk.renderapi.ImageSpec
import java.util.Collections
import java.util.IdentityHashMap

/** Legt eine Instanz ohne Konstruktoraufruf an (die android.jar-Attrappe kennt keine öffentlichen Bitmap-Konstruktoren). */
@Suppress("UNCHECKED_CAST")
internal fun <T> allocateWithoutConstructor(c: Class<T>): T {
    val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    f.isAccessible = true
    val unsafe = f.get(null)
    return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, c) as T
}

/**
 * Bitmap-Fabrik für JVM-Tests: liefert Attrappen-Bitmaps (ohne Pixel) und je Ebene eine [RecordingCanvas], zählt
 * Erzeugen und Recyceln. Damit laufen die Bitmap-Pfade der Senke (Textur-Handles, Aufzeichnen/Aufblenden, Trim) ohne Gerät.
 */
internal class FakeGraphics : GraphicsFactory {
    val textures = ArrayList<Bitmap>()
    val layerBitmaps = ArrayList<Bitmap>()
    val layerCanvases = ArrayList<RecordingCanvas>()
    private val recycled: MutableSet<Bitmap> = Collections.newSetFromMap(IdentityHashMap())
    var recycleCount = 0; private set
    var failTextures = false
    var failLayers = false

    val liveTextures: Int get() = textures.count { it !in recycled }
    val liveLayers: Int get() = layerBitmaps.count { it !in recycled }

    override fun textureBitmap(spec: ImageSpec): Bitmap? {
        if (failTextures) return null
        return allocateWithoutConstructor(Bitmap::class.java).also { textures.add(it) }
    }

    override fun layerBitmap(width: Int, height: Int): Bitmap? {
        if (failLayers) return null
        return allocateWithoutConstructor(Bitmap::class.java).also { layerBitmaps.add(it) }
    }

    override fun canvasFor(bitmap: Bitmap): Canvas = RecordingCanvas().also { layerCanvases.add(it) }
    override fun isRecycled(bitmap: Bitmap): Boolean = bitmap in recycled
    override fun recycle(bitmap: Bitmap) { if (recycled.add(bitmap)) recycleCount++ }
}
