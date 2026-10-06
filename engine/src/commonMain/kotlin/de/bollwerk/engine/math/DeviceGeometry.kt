package de.bollwerk.engine.math

import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.view.GameView

/**
 * Lage eines Geräts auf seinem Balken (Prototyp `devicePos`, `muzzle`) – gemeinsam für Sim (WP4),
 * Renderer (WP7, mit interpolierten Knoten), Werkzeuge (WP8) und KI (WP11).
 *
 * Normale `n = (−dy, dx) / L` von Ende A nach B, bei `sideNegative` umgedreht. Montagepunkt =
 * `lerp(A, B, t) + n · Dicke/2`; Trefferzentrum = Montage + n · mountOffset; Rohr-Drehpunkt =
 * Montage + n · pivotOffset; Mündung = Drehpunkt + (cos a, −sin a) · barrelLength.
 *
 * Ausgabe in `FloatArray(SIZE)` an den Indizes [X] … [MUZZLE_Y].
 */
object DeviceGeometry {
    const val X = 0
    const val Y = 1
    const val NX = 2
    const val NY = 3
    const val CX = 4
    const val CY = 5
    const val PIVOT_X = 6
    const val PIVOT_Y = 7
    const val MUZZLE_X = 8
    const val MUZZLE_Y = 9
    const val SIZE = 10

    fun mountAt(
        ax: Float, ay: Float, bx: Float, by: Float, t: Float, sideNegative: Boolean,
        beamThickness: Float, mountOffset: Float, pivotOffset: Float, barrelLength: Float, aimAngle: Float,
        out: FloatArray,
    ) {
        val dx = bx - ax; val dy = by - ay
        var len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len <= 0f) len = 1f
        var nx = -dy / len; var ny = dx / len
        if (sideNegative) { nx = -nx; ny = -ny }
        val half = beamThickness * 0.5f
        val mx = ax + dx * t + nx * half
        val my = ay + dy * t + ny * half
        out[X] = mx; out[Y] = my; out[NX] = nx; out[NY] = ny
        out[CX] = mx + nx * mountOffset; out[CY] = my + ny * mountOffset
        val px = mx + nx * pivotOffset; val py = my + ny * pivotOffset
        out[PIVOT_X] = px; out[PIVOT_Y] = py
        out[MUZZLE_X] = px + FastTrig.cos(aimAngle) * barrelLength
        out[MUZZLE_Y] = py - FastTrig.sin(aimAngle) * barrelLength
    }

    /** Lage von Gerät [deviceId] im aktuellen Zustand (Content aus `view.tables`). */
    fun mount(view: GameView, deviceId: Int, out: FloatArray) {
        val d = view.deviceView
        val beams = view.beamView
        val nodes = view.nodeView
        val props = view.tables.devices[d.type(deviceId)]
        val beam = d.beam(deviceId)
        val mat = view.tables.materials[beams.material(beam)]
        val a = beams.nodeA(beam); val b = beams.nodeB(beam)
        mountAt(
            nodes.x(a), nodes.y(a), nodes.x(b), nodes.y(b), d.t(deviceId),
            (d.flags(deviceId) and DeviceFlags.SIDE_NEG) != 0,
            mat.thickness, props.mountOffset, props.pivotOffset, props.barrelLength, d.aimAngle(deviceId), out,
        )
    }
}
