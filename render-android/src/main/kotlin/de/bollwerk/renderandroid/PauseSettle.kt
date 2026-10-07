package de.bollwerk.renderandroid

import de.bollwerk.engine.view.FxEvent
import de.bollwerk.renderapi.Camera
import de.bollwerk.renderapi.ParticleBuffers
import de.bollwerk.renderapi.ParticleSystem

/**
 * Zeitschritt des Szenen-Renderers während der Pause (reine Logik).
 *
 * Steht das Spiel, bekommt der Renderer `dt = 0`, und damit klingt nichts mehr ab: Kamera-Shake würde mit jedem Frame
 * neu zufällig versetzt und der weiße Bildschirmblitz bliebe stehen, wenn man kurz nach einem schweren Treffer pausiert
 * (oder die Sim nach der Pause noch einen letzten Snapshot mit einer Explosion veröffentlicht). Deshalb läuft der
 * echte Zeitschritt nach dem Pausieren und nach jedem neuen Snapshot noch [SETTLE_SECONDS] weiter, solange der
 * Renderer ein Shake-Offset meldet auch darüber hinaus; die Partikel bleiben dabei über [FreezableParticles] eingefroren.
 */
internal class PauseSettle {
    private var wasPaused = false
    private var seenSeq = Long.MIN_VALUE
    private var remaining = 0f

    /** Wahr, solange pausiert ist: die Partikel stehen still. */
    var frozen = false
        private set

    /**
     * Zeitschritt für diesen Frame. [realDt] = reale Frame-Dauer, [seq] = Nummer des gezeichneten Snapshots,
     * [shakeActive] = der Renderer hat im letzten Frame noch ein Shake-Offset angewendet.
     */
    fun dt(paused: Boolean, realDt: Float, seq: Long, shakeActive: Boolean): Float {
        frozen = paused
        if (!paused) {
            wasPaused = false
            seenSeq = seq
            remaining = 0f
            return realDt
        }
        if (!wasPaused || seq != seenSeq) remaining = SETTLE_SECONDS
        wasPaused = true
        seenSeq = seq
        if (remaining > 0f || shakeActive) {
            remaining -= realDt
            return realDt
        }
        return 0f
    }

    fun reset() {
        wasPaused = false; seenSeq = Long.MIN_VALUE; remaining = 0f; frozen = false
    }

    companion object {
        /** Shake klingt von 6 dp in ~0,8 s auf 0 ab, der Blitz in ~0,06 s; 1 s Reserve ist genug. */
        const val SETTLE_SECONDS = 1f
    }
}

/**
 * Reicht alles an [delegate] durch, aber im eingefrorenen Zustand weder `update` noch `emit`: so bleiben Partikel in der
 * Pause stehen, während Shake/Blitz des Renderers über seinen `dt` ausklingen. Muss **dieselbe Instanz** für die ganze
 * Lebensdauer eines Renderers sein (er bindet das System einmal und leert es bei einem Wechsel).
 */
internal class FreezableParticles(private val delegate: ParticleSystem) : ParticleSystem {
    var frozen = false

    override val count: Int get() = delegate.count
    override val buffers: ParticleBuffers get() = delegate.buffers

    override fun emit(kind: de.bollwerk.renderapi.ParticleKind, x: Float, y: Float, vx: Float, vy: Float, life: Float, size: Float, color: Int) {
        if (!frozen) delegate.emit(kind, x, y, vx, vy, life, size, color)
    }

    override fun onFx(event: FxEvent, wind: Float) = delegate.onFx(event, wind)
    override fun update(dt: Float, wind: Float) { if (!frozen) delegate.update(dt, wind) }
    override fun clear() = delegate.clear()

    override fun bindWorld(
        materialColors: IntArray, materialKinds: IntArray, weaponKinds: IntArray, terrain: de.bollwerk.engine.sim.Terrain?,
    ) = delegate.bindWorld(materialColors, materialKinds, weaponKinds, terrain)

    override fun bindWorld(materialColors: IntArray, terrain: de.bollwerk.engine.sim.Terrain?) =
        delegate.bindWorld(materialColors, terrain)
}

/**
 * Kopiert eine [Camera] konsistent in eine private Kopie des Render-Threads. Die geteilte Kamera gehört dem UI-Thread
 * (Pan/Pinch); `pan`/`pinch` schreiben erst eine ungeklemmte Mitte und stellen sie danach mit `clampToBounds` wieder her.
 * Läse der Render-Thread mittendrin, entstünde ein falscher Versatz, der in einer statischen Ebene (Himmel/Gelände)
 * stehen bliebe. Deshalb: Änderungen unter `synchronized(camera)` ([edit]), Kopie unter demselben Monitor.
 */
internal object CameraSync {
    fun copy(src: Camera, dst: Camera) {
        synchronized(src) {
            dst.minZoom = minOf(src.minZoom, src.zoom)
            dst.maxZoom = maxOf(src.maxZoom, src.zoom)
            dst.setBounds(src.boundsMinX, src.boundsMinY, src.boundsMaxX, src.boundsMaxY)
            dst.setViewport(src.viewportWidth, src.viewportHeight, src.density)
            dst.setZoom(src.zoom)
            dst.centerX = src.centerX
            dst.centerY = src.centerY
            dst.shakeX = src.shakeX
            dst.shakeY = src.shakeY
        }
    }
}
