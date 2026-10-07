package de.bollwerk.app.game

import de.bollwerk.engine.tools.SnapMark
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolOverlay
import de.bollwerk.renderandroid.OverlaySource
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.InteractionMode
import de.bollwerk.renderapi.Loupe
import de.bollwerk.renderapi.OverlayState
import de.bollwerk.renderapi.SnapHighlight

/**
 * Übersetzt die Werkzeug-Überlagerung ([ToolOverlay], Welt-Meter) in den Render-Vertrag [OverlayState]. Die Lupe bekommt
 * Bildschirmkoordinaten (Fingerposition) über die Kamera; Mitte der Vergrößerung ist der eingerastete Zielpunkt.
 *
 * **Allokationsfrei je Frame:** Solange sich weder die Überlagerung (Identität, der `ToolController` baut sie nur bei
 * Änderung neu) noch – bei offener Lupe – die Kamera ändert, liefert [convert] dieselbe Instanz zurück.
 */
class OverlayConverter(
    private val loupeRadiusDp: Float = 48f,
    private val loupeOffsetDp: Float = 80f,
) {
    private var lastTool: ToolOverlay? = null
    private var lastSnaps: List<SnapMark>? = null
    private var lastSnapOut: List<SnapHighlight> = emptyList()
    private var lastOut: OverlayState = OverlayState.NONE
    private var camCx = Float.NaN
    private var camCy = Float.NaN
    private var camScale = Float.NaN
    private var camW = Float.NaN
    private var camH = Float.NaN

    /** Anzahl neu gebauter [OverlayState]s (Tests: keine Allokation bei unveränderter Eingabe). */
    var builds: Int = 0
        private set

    /**
     * @param cx,cy,scale,viewportW,viewportH Kamerastand (Weltmitte, px je m, Viewport in px), nur für die Lupe gebraucht.
     */
    fun convert(t: ToolOverlay, cx: Float, cy: Float, scale: Float, viewportW: Float, viewportH: Float): OverlayState {
        val loupe = t.loupe
        val camChanged = loupe != null &&
            (cx != camCx || cy != camCy || scale != camScale || viewportW != camW || viewportH != camH)
        if (t === lastTool && !camChanged) return lastOut
        lastTool = t
        camCx = cx; camCy = cy; camScale = scale; camW = viewportW; camH = viewportH
        if (t.snaps !== lastSnaps) {
            lastSnaps = t.snaps
            lastSnapOut = if (t.snaps.isEmpty()) emptyList() else t.snaps.map { SnapHighlight(it.x, it.y, it.nodeRef, it.active) }
        }
        val l = if (loupe == null) null else Loupe(
            screenX = (loupe.worldX - cx) * scale + viewportW * 0.5f,
            screenY = (loupe.worldY - cy) * scale + viewportH * 0.5f,
            worldX = loupe.targetX,
            worldY = loupe.targetY,
            magnification = 2f,
            radiusDp = loupeRadiusDp,
            offsetDp = loupeOffsetDp,
        )
        builds++
        lastOut = OverlayState(
            mode = modeOf(t.mode),
            tool = t.tool,
            ghost = t.ghost,
            ghostDevice = t.ghostDevice,
            snaps = lastSnapOut,
            loupe = l,
            trajectory = t.trajectory,
            impactX = t.impactX,
            impactY = t.impactY,
            selectedDeviceRef = t.selectedDeviceRef,
            selectedBeamRef = t.selectedBeamRef,
            localPlayer = t.localPlayer,
        )
        return lastOut
    }

    companion object {
        private val MODES: Array<InteractionMode> = Array(ToolMode.entries.size) { InteractionMode.valueOf(ToolMode.entries[it].name) }

        /** `ToolMode` → `InteractionMode` (gleiche Namen, siehe `ToolMode`). */
        fun modeOf(m: ToolMode): InteractionMode = MODES[m.ordinal]
    }
}

/**
 * [OverlaySource] für die Spielfläche: liest je Frame (Render-Thread) die vom Sim-Thread veröffentlichte Überlagerung und
 * den Kamerastand (unter dem Kamera-Monitor, siehe `Camera.edit`) und übersetzt sie mit dem [OverlayConverter].
 */
class OverlayBridge(
    private val camera: Camera,
    private val overlay: () -> ToolOverlay,
) : OverlaySource {
    private val converter = OverlayConverter()

    override fun current(): OverlayState {
        val t = overlay()
        val cx: Float
        val cy: Float
        val s: Float
        val w: Float
        val h: Float
        synchronized(camera) {
            cx = camera.centerX; cy = camera.centerY; s = camera.scale; w = camera.viewportWidth; h = camera.viewportHeight
        }
        return converter.convert(t, cx, cy, s, w, h)
    }
}
