package de.bollwerk.renderapi

import de.bollwerk.engine.tools.GhostBeam
import de.bollwerk.engine.tools.GhostDevice
import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.engine.tools.Trajectory

/** Interaktionsmodus (Stil-Bibel §7: Modusschalter ZIELEN / BAUEN). */
enum class InteractionMode { NONE, BUILD, AIM }

/** Einrast-Markierung an einem Knoten (Snap-Ring). */
data class SnapHighlight(val x: Float, val y: Float, val nodeRef: Long, val active: Boolean)

/** Lupe 2× ca. 80 dp über dem Finger; zeigt den echten vergrößerten Ausschnitt um ([worldX], [worldY]). */
data class Loupe(
    val screenX: Float,
    val screenY: Float,
    val worldX: Float,
    val worldY: Float,
    val magnification: Float = 2f,
    val radiusDp: Float = 48f,
    val offsetDp: Float = 80f,
)

/**
 * UI-Überlagerung über der Spielwelt (Ghost-Balken, Snap-Ringe, Lupe, Flugbahn, Auswahl).
 * Reine Darstellungsdaten, kommt aus den Werkzeugen der App.
 */
data class OverlayState(
    val mode: InteractionMode = InteractionMode.NONE,
    /** Aktives Werkzeug (Toolbar-Hervorhebung). */
    val tool: ToolSelection = ToolSelection.None,
    val ghost: GhostBeam? = null,
    val ghostDevice: GhostDevice? = null,
    val snaps: List<SnapHighlight> = emptyList(),
    val loupe: Loupe? = null,
    /**
     * Flugbahn in Welt-Metern (`Ballistics.predict`, am ersten Treffer abgeschnitten); Punkte werden zum Ende kleiner.
     * `Trajectory.outcome == BLOCKED_OWN` (Schuss träfe die eigene Festung): rote Punkte, Warnmarke am Einschlag.
     */
    val trajectory: Trajectory? = null,
    /** Einschlagspunkt der Vorschau (Fadenkreuz) oder null. */
    val impactX: Float = Float.NaN,
    val impactY: Float = Float.NaN,
    val selectedDeviceRef: Long = -1,
    val selectedBeamRef: Long = -1,
    /** Debug-Ansicht: Balken nach Dehnung einfärben. */
    val strainView: Boolean = false,
    /** Lokaler Spieler (Teamfarben, Bauzone). */
    val localPlayer: Int = 0,
) {
    companion object {
        val NONE: OverlayState = OverlayState()
    }
}
