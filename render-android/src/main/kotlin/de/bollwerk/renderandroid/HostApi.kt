package de.bollwerk.renderandroid

import android.graphics.Typeface
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.SnapshotExchange
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.scene.SceneTexts

/**
 * Datenquelle des Render-Threads: statische Partiedaten plus der jeweils neueste [FrameSnapshot].
 * Alle Methoden werden **auf dem Render-Thread** gerufen und müssen schnell und thread-sicher sein.
 */
interface SnapshotSource {
    /** Content-Tabellen der Partie (für `GameRenderer.bind`). */
    val tables: SimTables

    /** Karte der Partie (Gelände, Erz, Bauzonen). */
    val map: MapSpec

    /** Neuester veröffentlichter Snapshot oder null, solange die Sim noch keinen geliefert hat. */
    fun latest(): FrameSnapshot?

    /** Sim-Zeit des letzten Ticks in ms (nur Profiling); NaN, wenn unbekannt. */
    fun lastSimMillis(): Float = Float.NaN

    /**
     * Interpolationsfaktor 0..1, wenn die Sim ihn vorgibt (z. B. `GameLoop.alpha`); NaN = der Render-Thread
     * schätzt ihn aus der Ankunftszeit der Snapshots ([SnapshotTiming]).
     */
    fun alphaHint(): Float = Float.NaN

    companion object {
        /** Quelle über den Dreifachpuffer der Engine. [exchange] hat genau einen Leser: den Render-Thread. */
        fun of(
            exchange: SnapshotExchange,
            tables: SimTables,
            map: MapSpec,
            simMillis: FloatSupplier? = null,
            alpha: FloatSupplier? = null,
        ): SnapshotSource = object : SnapshotSource {
            override val tables: SimTables = tables
            override val map: MapSpec = map
            override fun latest(): FrameSnapshot? = exchange.latest()
            override fun lastSimMillis(): Float = simMillis?.get() ?: Float.NaN
            override fun alphaHint(): Float = alpha?.get() ?: Float.NaN
        }
    }
}

/** Float-Lieferant ohne Boxing (wird je Frame gerufen). */
fun interface FloatSupplier {
    fun get(): Float
}

/** Liefert die UI-Überlagerung (Ghost, Lupe, Flugbahn …) je Frame. Thread-sicher (typisch ein `@Volatile`-Feld). */
fun interface OverlaySource {
    fun current(): OverlayState

    companion object {
        val NONE: OverlaySource = OverlaySource { OverlayState.NONE }
    }
}

/** Schriftarten der Spielfläche (Rajdhani aus `res/font`, die App reicht sie hier an). */
interface TypefaceProvider {
    /** Schriftschnitt; null = Standardschrift des Paints. */
    fun typeface(bold: Boolean): Typeface?

    companion object {
        /** Systemschrift (Rückfall, wenn die App keine eigene liefert). */
        val SYSTEM: TypefaceProvider = object : TypefaceProvider {
            override fun typeface(bold: Boolean): Typeface? = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        /**
         * Eigene Schnitte. Fehlt der fette Schnitt, wird er aus [regular] abgeleitet (`Typeface.create(regular, BOLD)`),
         * damit in einem Chip nicht zwei Schriftfamilien gemischt werden; nur ohne beide gilt die Systemschrift.
         * [deriveBold] ist austauschbar (Tests).
         */
        fun of(
            regular: Typeface?,
            bold: Typeface?,
            deriveBold: (Typeface) -> Typeface? = { Typeface.create(it, Typeface.BOLD) },
        ): TypefaceProvider {
            val boldFace: Typeface? = bold ?: regular?.let(deriveBold)
            return object : TypefaceProvider {
                override fun typeface(bold: Boolean): Typeface? =
                    (if (bold) boldFace else regular) ?: SYSTEM.typeface(bold)
            }
        }
    }
}

/**
 * Darstellungseinstellungen. Unveränderlich; Änderungen über [GameViewHost.updateSettings] bzw. die Einzelsetzer.
 *
 * @property reducedMotion weniger Bewegung (Barrierefreiheit): Shake ×0,25, kein Blitz, weniger Partikel.
 * @property debugOverlay Frame-Statistik oben links.
 * @property maxFps Obergrenze der Zeichenrate; 0 = Bildwiederholrate des Displays.
 * @property hudTopInsetDp Höhe der HUD-Leiste oben (Lupe/Chips weichen ihr aus).
 * @property maxIdleTextureBytes Budget für ungenutzte Texturen im Bitmap-Cache.
 */
data class RenderSettings(
    val reducedMotion: Boolean = false,
    val debugOverlay: Boolean = false,
    val maxFps: Int = 0,
    val typefaces: TypefaceProvider = TypefaceProvider.SYSTEM,
    val texts: SceneTexts = SceneTexts(),
    val hudTopInsetDp: Float = 76f,
    val maxIdleTextureBytes: Long = 8L * 1024 * 1024,
    val seed: Int = 1,
)

/**
 * Führt [block] unter dem Monitor der Kamera aus. **Jede** Änderung der geteilten [Camera] (pan, pinch, setZoom, fitRect,
 * setBounds, Mitte setzen) muss so geschehen: Der Render-Thread kopiert die Kamera je Frame unter demselben Monitor.
 * Ohne Sperre könnte er einen Zwischenzustand lesen (z. B. die ungeklemmte Mitte zwischen Verschieben und `clampToBounds`)
 * und damit eine statische Ebene (Himmel/Gelände) mit falschem Versatz aufzeichnen, die dann bis zur nächsten
 * Kamerabewegung stehen bleibt.
 */
inline fun <R> Camera.edit(block: Camera.() -> R): R = synchronized(this) { block() }

/** Meldet die Größe der Spielfläche, sobald sie bekannt ist oder sich ändert (UI-Thread). */
fun interface ViewportListener {
    fun onViewportChanged(widthPx: Int, heightPx: Int, density: Float)
}

/**
 * Schnittstelle, die die App (WP9) zum Steuern der Spielfläche benutzt. [GameSurfaceView] implementiert sie.
 * Alle Methoden sind vom UI-Thread aus aufzurufen.
 *
 * Typischer Ablauf: `attach(...)` bei Partiestart; `onHostPause()`/`onHostResume()` im Activity-Lebenszyklus;
 * `detach()` beim Verlassen der Partie. Der Render-Thread läuft nur, wenn eine Quelle angehängt, die Oberfläche
 * vorhanden und der Host nicht pausiert ist.
 */
interface GameViewHost {
    /**
     * Hängt eine Partie an. Eine vorherige wird abgebaut. [settings] = null behält die bisherigen Einstellungen
     * (auch die vor `attach` per [setReducedMotion]/[setDebugOverlay] gesetzten). [camera] gehört der App (Pan/Zoom auf dem UI-Thread);
     * Viewport und Pixeldichte der Kamera setzt die View bei jeder Größenänderung selbst. **Alle Änderungen an [camera]
     * (auch `fitRect` im [ViewportListener]) laufen unter `synchronized(camera)`, bequem über [edit]**: Der Render-Thread
     * kopiert die Kamera je Frame unter demselben Monitor (siehe dort).
     */
    fun attach(
        snapshotSource: SnapshotSource,
        overlaySource: OverlaySource,
        camera: Camera,
        settings: RenderSettings? = null,
    )

    /** Löst die Partie und gibt Texturen, Ebenen und Partikel frei (die Referenzen auf die Quellen entfallen). */
    fun detach()

    /** Setzt die Einstellung „Weniger Bewegung“; baut Renderer und Partikelsystem beim nächsten Frame neu auf. */
    fun setReducedMotion(reduced: Boolean)

    /**
     * Spiel pausiert: Der Render-Thread zeichnet weiter (Kamera und Overlay bleiben bedienbar), aber mit halber Rate;
     * Partikel und Flammen stehen still. Kamera-Shake, Bildschirmblitz und Treffer-Blitze klingen nach dem Pausieren
     * (bzw. nach einem letzten Snapshot mit Ereignissen) noch aus und stehen dann ebenfalls still.
     */
    fun setPaused(paused: Boolean)

    /** Schaltet die Frame-Statistik ein/aus. */
    fun setDebugOverlay(enabled: Boolean)

    /** Ersetzt alle Einstellungen. */
    fun updateSettings(settings: RenderSettings)

    /** Activity `onPause`: Render-Thread beenden (kein Zeichnen im Hintergrund). */
    fun onHostPause()

    /** Activity `onResume`: Render-Thread wieder starten. */
    fun onHostResume()

    /** Speicherdruck (`ComponentCallbacks2.onTrimMemory`); die View meldet sich selbst an, ein Aufruf ist optional. */
    fun trimMemory(level: Int)

    /** Frame-Statistik des laufenden Renderers (Zeiten p50/p95, Sim-Zeit). */
    val frameStats: FrameStats

    /** Optionaler Hörer für Größenänderungen (z. B. `camera.fitRect` beim ersten Layout). */
    var viewportListener: ViewportListener?
}
