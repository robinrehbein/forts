package de.bollwerk.app.game

import de.bollwerk.engine.tools.PointerPhase
import de.bollwerk.renderapi.Camera
import kotlin.math.sqrt

/**
 * Plattformfreies Touch-Ereignis (ein wiederverwendetes Objekt, keine Allokation je Ereignis). Die Android-Schicht füllt es
 * aus dem `MotionEvent`; Tests bauen es direkt. Koordinaten in Bildschirm-Pixeln der Spielfläche.
 */
class TouchSample {
    var action: Int = DOWN
    /** Finger auf dem Schirm **nach** dem Ereignis (bei [POINTER_UP]/[UP] ohne den gehobenen). */
    var pointerCount: Int = 1
    val x = FloatArray(MAX_POINTERS)
    val y = FloatArray(MAX_POINTERS)
    var timeMs: Long = 0L

    fun set(action: Int, timeMs: Long, pointerCount: Int, x0: Float, y0: Float, x1: Float = 0f, y1: Float = 0f): TouchSample {
        this.action = action
        this.timeMs = timeMs
        this.pointerCount = pointerCount
        x[0] = x0; y[0] = y0; x[1] = x1; y[1] = y1
        return this
    }

    companion object {
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
        const val CANCEL = 3
        const val POINTER_DOWN = 4
        const val POINTER_UP = 5
        const val MAX_POINTERS = 2
    }
}

/** Ziel der Gesten (der `GameController` über [GameController.gestureSink]; Tests: Fake). */
interface GestureSink {
    fun pointer(phase: PointerPhase, worldX: Float, worldY: Float, pickRadiusM: Float, gestureId: Long)
    fun longPress(worldX: Float, worldY: Float, pickRadiusM: Float, gestureId: Long)
    fun cancelGesture()
    /** Siehe [GameController.gestureConsumed]: `null` = noch nicht verarbeitet. */
    fun gestureConsumed(gestureId: Long): Boolean?
    /** Doppeltipp ohne Werkzeugwirkung: eigene Festung ↔ Übersicht. */
    fun doubleTap(worldX: Float, worldY: Float)
}

/** Schwellen der Gestenerkennung (dp bzw. ms). */
data class InputConfig(
    /** Fangradius (Stil-Bibel: 1 m ≈ 24 dp; Werkzeuge rechnen ihre Radien relativ dazu). */
    val touchRadiusDp: Float = 24f,
    /** Bewegung unterhalb dieser Strecke zählt als Tippen/Halten. */
    val touchSlopDp: Float = 8f,
    val longPressMs: Long = 450L,
    val tapMaxMs: Long = 300L,
    val doubleTapGapMs: Long = 300L,
    val doubleTapSlopDp: Float = 40f,
)

/**
 * Gestensteuerung der Spielfläche (UI-Thread), Stil-Bibel §7:
 * - **Ein Finger** → aktives Werkzeug (`ToolController` im Sim-Thread) mit Welt-Koordinaten aus der [Camera] und einem
 *   Fangradius aus 24 dp. Übernimmt das Werkzeug die Geste nicht (kein Werkzeug, Bauen ohne Startpunkt, Zielen ohne Waffe,
 *   Tipp-Werkzeug über die Tipp-Toleranz gezogen), verschiebt der Finger die Kamera.
 * - **Zwei Finger** → Pinch-Zoom und Verschieben, jederzeit; bricht die Werkzeug-Geste ab. Bis alle Finger oben sind, gibt es
 *   danach keine neue Werkzeug-Geste.
 * - **Langdruck** (ohne Bewegung) → Kontextmenü (Reparatur/Abriss/Tür) über [GestureSink.longPress].
 * - **Doppeltipp** (zwei Tipps, die kein Werkzeug übernommen hat) → [GestureSink.doubleTap].
 *
 * Die Plattform ruft [onTouch] je `MotionEvent` und [onLongPressTimeout], wenn der Halte-Timer abläuft. Allokationsfrei.
 */
class InputController(
    private val camera: Camera,
    private val sink: GestureSink,
    private val config: InputConfig = InputConfig(),
) {
    /** Pixel je dp (aus der Anzeige). */
    var density: Float = 1f

    private var state = IDLE
    private var gestureId = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var longPressed = false

    private var pinchDist = 0f
    private var pinchCx = 0f
    private var pinchCy = 0f

    private var lastTapTime = Long.MIN_VALUE / 2
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapGesture = -1L

    /** Haltezeit bis zum Langdruck (für den Timer der Plattform). */
    val longPressMs: Long get() = config.longPressMs

    /** Läuft gerade eine Ein-Finger-Geste (für den Langdruck-Timer der Plattform)? */
    val singleFingerActive: Boolean get() = state == ONE && !moved && !longPressed

    /** Aktuelle Geste (Tests). */
    val currentGesture: Long get() = gestureId

    fun onTouch(e: TouchSample) {
        when (e.action) {
            TouchSample.DOWN -> down(e)
            TouchSample.POINTER_DOWN -> pointerDown(e)
            TouchSample.MOVE -> move(e)
            TouchSample.POINTER_UP -> pointerUp(e)
            TouchSample.UP -> up(e)
            TouchSample.CANCEL -> cancel()
        }
    }

    /** Halte-Timer abgelaufen ([nowMs] in derselben Zeitbasis wie [TouchSample.timeMs]). @return Langdruck ausgelöst. */
    fun onLongPressTimeout(nowMs: Long): Boolean {
        if (state != ONE || moved || longPressed || nowMs - downTime < config.longPressMs) return false
        longPressed = true
        sink.longPress(worldX(downX), worldY(downY), pickRadiusM(), gestureId)
        return true
    }

    private fun down(e: TouchSample) {
        state = ONE
        gestureId++
        downX = e.x[0]; downY = e.y[0]; downTime = e.timeMs
        lastX = downX; lastY = downY
        moved = false
        longPressed = false
        sink.pointer(PointerPhase.DOWN, worldX(downX), worldY(downY), pickRadiusM(), gestureId)
    }

    private fun pointerDown(e: TouchSample) {
        if (state == ONE) sink.cancelGesture()
        if (e.pointerCount >= 2) {
            state = MULTI
            startPinch(e)
        } else if (state == IDLE) {
            state = DONE
        }
    }

    private fun move(e: TouchSample) {
        when (state) {
            ONE -> {
                val x = e.x[0]
                val y = e.y[0]
                if (!moved) {
                    val dx = x - downX
                    val dy = y - downY
                    val slop = config.touchSlopDp * density
                    if (dx * dx + dy * dy > slop * slop) moved = true
                }
                if (longPressed) { lastX = x; lastY = y; return } // Werkzeug verschluckt den Rest
                // Schwenken, sobald das Werkzeug die Geste ablehnt (null = Sim-Thread hat sie noch nicht gesehen: abwarten)
                val panning = moved && sink.gestureConsumed(gestureId) == false
                if (panning) {
                    val ddx = x - lastX
                    val ddy = y - lastY
                    synchronized(camera) { camera.pan(ddx, ddy) }
                }
                lastX = x; lastY = y
                if (!panning) sink.pointer(PointerPhase.MOVE, worldX(x), worldY(y), pickRadiusM(), gestureId)
            }
            MULTI -> if (e.pointerCount >= 2) {
                val cx = (e.x[0] + e.x[1]) * 0.5f
                val cy = (e.y[0] + e.y[1]) * 0.5f
                val d = dist(e)
                synchronized(camera) {
                    if (pinchDist > 1f && d > 1f) camera.pinch(cx, cy, d / pinchDist)
                    camera.pan(cx - pinchCx, cy - pinchCy)
                }
                pinchDist = d; pinchCx = cx; pinchCy = cy
            }
            else -> Unit
        }
    }

    private fun pointerUp(e: TouchSample) {
        if (state == MULTI) {
            if (e.pointerCount >= 2) startPinch(e) else state = DONE
        }
    }

    private fun up(e: TouchSample) {
        if (state != ONE) {
            state = IDLE
            return
        }
        state = IDLE
        val x = e.x[0]
        val y = e.y[0]
        sink.pointer(PointerPhase.UP, worldX(x), worldY(y), pickRadiusM(), gestureId)
        if (moved || longPressed || e.timeMs - downTime > config.tapMaxMs) return
        // Tipp: Doppeltipp, wenn der vorige Tipp kurz davor in der Nähe war und keines von beiden ein Werkzeug auslöste
        val consumedNow = sink.gestureConsumed(gestureId) == true
        val slop = config.doubleTapSlopDp * density
        val dx = x - lastTapX
        val dy = y - lastTapY
        val isDouble = lastTapGesture == gestureId - 1 && e.timeMs - lastTapTime <= config.doubleTapGapMs &&
            dx * dx + dy * dy <= slop * slop && !consumedNow
        if (isDouble) {
            lastTapGesture = -1L
            sink.doubleTap(worldX(x), worldY(y))
        } else if (!consumedNow) {
            lastTapGesture = gestureId
            lastTapTime = e.timeMs
            lastTapX = x
            lastTapY = y
        } else {
            lastTapGesture = -1L
        }
    }

    private fun cancel() {
        if (state == ONE) sink.cancelGesture()
        state = IDLE
    }

    private fun startPinch(e: TouchSample) {
        pinchDist = dist(e)
        pinchCx = (e.x[0] + e.x[1]) * 0.5f
        pinchCy = (e.y[0] + e.y[1]) * 0.5f
    }

    private fun dist(e: TouchSample): Float {
        val dx = e.x[1] - e.x[0]
        val dy = e.y[1] - e.y[0]
        return sqrt(dx * dx + dy * dy)
    }

    private fun worldX(sx: Float): Float = synchronized(camera) { camera.screenToWorldX(sx) }
    private fun worldY(sy: Float): Float = synchronized(camera) { camera.screenToWorldY(sy) }

    /** Fangradius in Metern beim aktuellen Zoom (24 dp). */
    fun pickRadiusM(): Float = synchronized(camera) { camera.pxToMeters(config.touchRadiusDp * density) }

    private companion object {
        const val IDLE = 0
        const val ONE = 1
        const val MULTI = 2
        /** Nach einer Mehrfinger-Geste: bis alle Finger oben sind, nichts mehr. */
        const val DONE = 3
    }
}
