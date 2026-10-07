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

    /**
     * Kreisförmiger Clip (Lupe). Additiv (WP6): Sinks sollten ihn überschreiben (Android `Path`-Clip,
     * AWT `Ellipse2D`); der Standard fällt auf das Begrenzungsquadrat zurück (Lupen-Ecken sind dann sichtbar).
     */
    fun clipCircle(cx: Float, cy: Float, r: Float) = clipRect(cx - r, cy - r, 2f * r, 2f * r)

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
    /**
     * Breite von [text] in Pixeln bei Schriftgröße [sizePx]. Additiv (WP6): Sinks sollten überschreiben
     * (Android `Paint.measureText`, AWT `FontMetrics`); der Standard schätzt 0,56 × Größe je Zeichen. Der Renderer
     * cached das Ergebnis je Text und ruft es nicht jeden Frame auf.
     */
    fun measureText(text: String, sizePx: Float, bold: Boolean = false): Float = text.length * sizePx * 0.56f

    /** Bild [handle] in das Rechteck (x, y, w, h) mit Deckkraft [alpha]. */
    fun image(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f)

    /**
     * Ausschnitt eines Bildes: Der Texturbereich ([u0],[v0])–([u1],[v1]) (0..1) wird in das Rechteck
     * (x, y, w, h) gezeichnet (Kachelung entlang der Balkenlänge). Additiv (WP6): Sinks können den
     * Standard (Clip + gestrecktes [image]) mit `drawBitmap(src, dst)` ersetzen.
     */
    fun imageRegion(handle: Int, u0: Float, v0: Float, u1: Float, v1: Float, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
        val du = u1 - u0
        val dv = v1 - v0
        if (du <= 0f || dv <= 0f) return
        save()
        clipRect(x, y, w, h)
        val sx = w / du
        val sy = h / dv
        image(handle, x - u0 * sx, y - v0 * sy, sx, sy, alpha)
        restore()
    }

    /**
     * Statische Ebene zwischenspeichern (Himmel/Berge, Gelände). Der Renderer ruft vor dem Zeichnen einer
     * statischen Ebene `if (beginLayer(...)) { zeichnen; endLayer(id) }`:
     *  - Gibt es für [layerId] ein Bitmap mit gleichem [key] und gleicher Größe, zeichnet der Sink es
     *    (verschoben um [offsetX]/[offsetY] px, Shake) und liefert `false`; der Renderer zeichnet nichts.
     *  - Sonst beginnt der Sink eine Aufzeichnung in ein Offscreen-Bitmap ([width] × [height] px) und
     *    liefert `true`; der Renderer zeichnet in Bildschirm-Pixeln, `endLayer` schließt ab und
     *    zeichnet das Ergebnis an ([offsetX], [offsetY]).
     * Der Standard zeichnet immer direkt (kein Cache; `save`/`translate` … `restore`). [key] ändert sich nur, wenn die Kamera oder die Karte
     * wechselt (Verschieben, Zoom, Größe); Shake steckt nicht im Schlüssel.
     */
    fun beginLayer(layerId: Int, key: Long, width: Int, height: Int, offsetX: Float, offsetY: Float): Boolean {
        save()
        translate(offsetX, offsetY)
        return true
    }

    /**
     * Verwirft alle zwischengespeicherten Ebenen. Additiv (WP6): der Renderer ruft es bei `bind`/`release`,
     * weil Sinks Ebenen-Bitmaps über Partien hinweg behalten. Standard: nichts zu verwerfen.
     */
    fun invalidateLayers() {}

    /** Beendet [beginLayer] (nur aufgerufen, wenn es `true` lieferte). */
    fun endLayer(layerId: Int) = restore()

    /** Registriert ein vom Renderer erzeugtes ARGB-Pixelbild; liefert das Handle. */
    fun registerImage(spec: ImageSpec): Int
    /** Gibt ein Bild frei. */
    fun releaseImage(handle: Int)
}

/**
 * Prozedural erzeugtes Bild (ARGB, nicht vormultipliziert, zeilenweise). [id] ist ein stabiler Name
 * (z. B. `wood@72`), unter dem Sinks Bitmaps über Neustarts der Partie hinweg zwischenspeichern können.
 */
class ImageSpec(val width: Int, val height: Int, val argb: IntArray, val id: String = "") {
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
 * Fx und Partikel gehören dem Renderer: Ist `snap.seq` neu, übergibt er `snap.fx` genau einmal an
 * [ParticleSystem.onFx] und schreibt die Partikel selbst mit [ParticleSystem.update] fort. Die Plattform ruft
 * beides **nicht** zusätzlich auf (sonst doppelte Effekte); sie reicht nur das [ParticleSystem] durch.
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
