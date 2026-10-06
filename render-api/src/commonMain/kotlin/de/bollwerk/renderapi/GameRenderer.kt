package de.bollwerk.renderapi

import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.view.FrameSnapshot

/** Textausrichtung für [DrawSink.text]. */
enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * Plattformneutrale Zeichen-Primitive in **Bildschirm-Pixeln** (nach Kamera-Transform). Android: Canvas-Sink
 * (WP7), Simrunner: BufferedImage-Sink. Farben ARGB ([Palette]). Layout, Culling und Zeichenreihenfolge
 * stehen im gemeinsamen Renderer-Code und benutzen nur diese Schnittstelle.
 *
 * Bilder (Texturen) werden über Ganzzahl-Handles referenziert, die die Plattform-Sink vergibt
 * ([registerImage]); der gemeinsame Code erzeugt Texturen prozedural über [ImageSpec].
 */
interface DrawSink {
    /** Speichert die aktuelle Transformation (verschachtelbar). */
    fun save()
    fun restore()
    fun translate(dx: Float, dy: Float)
    /** Drehung in Bogenmaß im Uhrzeigersinn auf dem Bildschirm (y nach unten). */
    fun rotate(rad: Float)
    fun scale(sx: Float, sy: Float)
    /** Rechteckiger Clip in aktuellen Koordinaten. */
    fun clipRect(x: Float, y: Float, w: Float, h: Float)

    fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun strokeRect(x: Float, y: Float, w: Float, h: Float, width: Float, color: Int)
    /** Gefülltes Polygon aus [count] Punkten (xy-Paare in [xy]). */
    fun fillPolygon(xy: FloatArray, count: Int, color: Int)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, roundCap: Boolean = true)
    /** Gestrichelte Linie (Ghost-Balken). */
    fun dashedLine(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, dash: Float, gap: Float)
    fun fillCircle(cx: Float, cy: Float, r: Float, color: Int)
    fun strokeCircle(cx: Float, cy: Float, r: Float, width: Float, color: Int)
    /** Rechteck mit linearem Verlauf von (x0,y0) nach (x1,y1); [stops] 0..1 passend zu [colors]. */
    fun gradientRect(x: Float, y: Float, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray)
    /** Additiver radialer Schein (Feuer, Mündungsblitz). */
    fun glow(cx: Float, cy: Float, r: Float, color: Int)
    fun text(text: String, x: Float, y: Float, sizePx: Float, color: Int, align: TextAlign = TextAlign.LEFT, bold: Boolean = false)
    /** Bild [handle] in das Rechteck (x, y, w, h) mit Deckkraft [alpha]. */
    fun image(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f)

    /** Registriert ein vom Renderer erzeugtes ARGB-Pixelbild; liefert das Handle. */
    fun registerImage(spec: ImageSpec): Int
    /** Gibt ein Bild frei. */
    fun releaseImage(handle: Int)
}

/** Prozedural erzeugtes Bild (ARGB, zeilenweise). */
class ImageSpec(val width: Int, val height: Int, val argb: IntArray) {
    init { require(argb.size >= width * height) { "argb too small" } }
}

/** Zeichenfläche (Android: Canvas-Wrapper; Simrunner: BufferedImage-Wrapper). */
interface RenderTarget {
    val widthPx: Int
    val heightPx: Int
    /** Pixel pro dp. */
    val density: Float
    val sink: DrawSink
}

/**
 * Zeichnet Frames. Liest nur [FrameSnapshot] (nie den GameState) und die statischen Daten aus [bind].
 * Interpolation: `lerp(nodePrev*, node*, alpha)` – beide Endpunkte stehen im Snapshot.
 * Fx: Ist `snap.seq` neu, übergibt der Renderer `snap.fx` genau einmal an [ParticleSystem.onFx].
 *
 * Lebenszyklus: [bind] bei Partiestart (Gelände, Erz, Materialschlüssel, Texturen), dann [render] je Frame,
 * [release] beim Verlassen (Texturen freigeben). Läuft auf dem Render-Thread.
 */
interface GameRenderer {
    fun bind(tables: SimTables, map: MapSpec, target: RenderTarget)

    fun render(
        target: RenderTarget,
        snap: FrameSnapshot,
        alpha: Float,
        camera: Camera,
        overlay: OverlayState,
        particles: ParticleSystem,
    )

    fun release()
}
