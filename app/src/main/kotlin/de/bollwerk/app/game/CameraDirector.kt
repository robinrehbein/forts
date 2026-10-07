package de.bollwerk.app.game

import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.renderapi.Camera

/**
 * Kamera-Ausschnitte der Partie (UI-Thread; alle Änderungen unter dem Kamera-Monitor, siehe `Camera.edit`):
 * Gesamtansicht (beide Festungen, wie Mockup 3/4) und eigene Festung (Bauzone, Standard-Zoom nahe 1 m ≈ 24 dp).
 * Doppeltipp wechselt zwischen beiden ([toggle]).
 */
class CameraDirector(private val camera: Camera, private val map: MapSpec) {
    /** Aktuell gezeigter Ausschnitt; nach freiem Zoomen/Schwenken bleibt der letzte gewählte stehen. */
    var showingOwnFort: Boolean = false
        private set

    private var hasViewport = false

    /** Grenzen und Zoombereich setzen (einmal je Partie). */
    fun configure() = synchronized(camera) {
        camera.minZoom = MIN_ZOOM
        camera.maxZoom = MAX_ZOOM
        camera.setBounds(-MARGIN_X, -SKY_HEADROOM, map.width + MARGIN_X, map.height)
    }

    /** Erste Größe der Spielfläche: Gesamtansicht. Spätere Größenänderungen behalten den Ausschnitt. */
    fun onViewport(widthPx: Float, heightPx: Float, density: Float) {
        synchronized(camera) { camera.setViewport(widthPx, heightPx, density) }
        if (!hasViewport && widthPx > 0f && heightPx > 0f) {
            hasViewport = true
            showOverview()
        }
    }

    fun showOverview() {
        val ground = groundY()
        synchronized(camera) {
            camera.fitRect(0f, ground - OVERVIEW_ABOVE, map.width, ground + OVERVIEW_BELOW, paddingPx = 0f)
        }
        showingOwnFort = false
    }

    fun showOwnFort(playerId: Int) {
        var x0 = 0f
        var x1 = map.width * 0.5f
        for (z in map.buildZones) if (z.owner == playerId) { x0 = z.x0; x1 = z.x1 }
        val base = if (playerId in map.baseY.indices) map.baseY[playerId] else groundY()
        synchronized(camera) {
            camera.fitRect(x0 - FORT_MARGIN, base - FORT_ABOVE, x1 + FORT_MARGIN, base + FORT_BELOW, paddingPx = 0f)
        }
        showingOwnFort = true
    }

    /** Doppeltipp: eigene Festung ↔ Gesamtansicht. */
    fun toggle(playerId: Int) {
        if (showingOwnFort) showOverview() else showOwnFort(playerId)
    }

    private fun groundY(): Float {
        var g = 0f
        for (b in map.baseY) if (b > g) g = b
        return if (g > 0f) g else map.height * 0.5f
    }

    companion object {
        const val MIN_ZOOM = 0.12f
        const val MAX_ZOOM = 3f
        const val MARGIN_X = 20f
        /** Kopfraum über dem Kartenursprung für hohe Flugbahnen. */
        const val SKY_HEADROOM = 70f
        const val OVERVIEW_ABOVE = 30f
        const val OVERVIEW_BELOW = 8f
        const val FORT_MARGIN = 3f
        const val FORT_ABOVE = 24f
        const val FORT_BELOW = 5f
    }
}
