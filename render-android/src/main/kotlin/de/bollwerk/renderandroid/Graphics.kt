package de.bollwerk.renderandroid

import android.graphics.Bitmap
import android.graphics.Canvas
import de.bollwerk.renderapi.ImageSpec

/**
 * Erzeugt und verwirft die Bitmaps der [CanvasDrawSink] (Texturen, Ebenen). Eigene Naht, damit JVM-Tests die
 * Bitmap-Pfade (Aufzeichnen → Aufblenden, Trim, Handle-Wiederverwendung) mit Attrappen durchlaufen können, denn
 * `Bitmap.createBitmap` liefert in der android.jar-Attrappe null.
 */
internal interface GraphicsFactory {
    /** Textur-Bitmap aus [spec] (ARGB_8888, mit Mipmaps) oder null, wenn kein Speicher da ist. */
    fun textureBitmap(spec: ImageSpec): Bitmap?

    /** Leeres ARGB_8888-Bitmap für eine Ebene oder null. */
    fun layerBitmap(width: Int, height: Int): Bitmap?

    /** Zeichenfläche auf [bitmap]. */
    fun canvasFor(bitmap: Bitmap): Canvas

    fun isRecycled(bitmap: Bitmap): Boolean

    fun recycle(bitmap: Bitmap)
}

/** Standard: echte Android-Bitmaps. */
internal object AndroidGraphics : GraphicsFactory {
    override fun textureBitmap(spec: ImageSpec): Bitmap? {
        val bmp: Bitmap = Bitmap.createBitmap(spec.argb, 0, spec.width, spec.width, spec.height, Bitmap.Config.ARGB_8888)
            ?: return null
        bmp.density = Bitmap.DENSITY_NONE
        bmp.setHasMipMap(true)
        return bmp
    }

    override fun layerBitmap(width: Int, height: Int): Bitmap? {
        val bmp: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) ?: return null
        bmp.density = Bitmap.DENSITY_NONE
        return bmp
    }

    override fun canvasFor(bitmap: Bitmap): Canvas = Canvas(bitmap)
    override fun isRecycled(bitmap: Bitmap): Boolean = bitmap.isRecycled
    override fun recycle(bitmap: Bitmap) = bitmap.recycle()
}
