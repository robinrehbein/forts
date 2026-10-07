package de.bollwerk.app.game

import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.SnapshotExchange
import de.bollwerk.renderandroid.FloatSupplier
import de.bollwerk.renderandroid.SnapshotSource
import de.bollwerk.renderapi.Camera

/** Bekommt jeden gezeichneten Snapshot auf dem Render-Thread (Audio: Fx einmal je `seq`, Feuerschleife je Frame). */
fun interface FrameListener {
    /** [fresh] = neue `seq` (Fx noch nicht verarbeitet); [camera] = Kopie des aktuellen Kamerastands (Pan/Entfernung). */
    fun onFrame(snap: FrameSnapshot, fresh: Boolean, camera: Camera)
}

/**
 * [SnapshotSource] der Spielfläche: liest den Dreifachpuffer der Engine (einziger Leser ist der Render-Thread) und gibt
 * jeden Snapshot an das Audio ([FrameListener], `fresh` genau einmal je neuer `seq`) weiter, bevor der Szenen-Renderer
 * ihn zeichnet (der seine Fx selbst einmal je `seq` verarbeitet). Die Kennzahlen zählt der Sim-Thread selbst
 * ([MatchStatsCounter.sync]), unabhängig davon, ob gezeichnet wird.
 *
 * **Alpha:** Die Sim veröffentlicht je Tick mehrere Snapshots, die sich oft nur im Interpolationsfaktor unterscheiden
 * ([FrameSnapshot.alpha], ~120 Hz). [alphaHint] gibt genau diesen Wert des zuletzt gelieferten Snapshots weiter; ohne
 * ihn würde der Render-Thread das Alpha aus der Ankunftszeit schätzen und bei jeder neuen `seq` auf ~0 zurücksetzen
 * (Sägezahn-Ruckeln bei Projektilen und Trümmern).
 */
class GameSnapshotSource(
    private val exchange: SnapshotExchange,
    override val tables: SimTables,
    override val map: MapSpec,
    private val sharedCamera: Camera,
    @Volatile var listener: FrameListener? = null,
    /** Dauer des letzten Sim-Schritts in ms (Profiling, `FrameStats`); null = unbekannt. */
    private val simMillis: FloatSupplier? = null,
) : SnapshotSource {
    private var lastSeq = Long.MIN_VALUE
    private var lastSnap: FrameSnapshot? = null
    private val cam = Camera(dpPerMeter = sharedCamera.dpPerMeter)

    override fun alphaHint(): Float = lastSnap?.alpha ?: Float.NaN

    override fun lastSimMillis(): Float = simMillis?.get() ?: Float.NaN

    override fun latest(): FrameSnapshot? {
        val snap = exchange.latest() ?: return null
        lastSnap = snap
        val fresh = snap.seq != lastSeq
        if (fresh) lastSeq = snap.seq
        val l = listener
        if (l != null) {
            copyCamera()
            l.onFrame(snap, fresh, cam)
        }
        return snap
    }

    private fun copyCamera() {
        synchronized(sharedCamera) {
            cam.minZoom = sharedCamera.minZoom
            cam.maxZoom = sharedCamera.maxZoom
            cam.setViewport(sharedCamera.viewportWidth, sharedCamera.viewportHeight, sharedCamera.density)
            cam.setZoom(sharedCamera.zoom)
            cam.centerX = sharedCamera.centerX
            cam.centerY = sharedCamera.centerY
        }
    }
}
