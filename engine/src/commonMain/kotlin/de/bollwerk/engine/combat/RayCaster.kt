package de.bollwerk.engine.combat

import de.bollwerk.engine.math.ClosestParams
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.math.Geometry
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameState
import kotlin.math.sqrt

/** Ergebnis einer Strahl-/Sweep-Abfrage (wiederverwendbar, keine Allokation). */
internal class RayHit {
    var kind: Int = NONE
    /** Balken- bzw. Geräte-Slot (−1 bei Gelände). */
    var id: Int = -1
    /** Bewegungsparameter 0..1 entlang des getesteten Segments. */
    var t: Float = 2f
    var x: Float = 0f
    var y: Float = 0f

    fun reset() { kind = NONE; id = -1; t = 2f }

    companion object {
        const val NONE = 0
        const val BEAM = 1
        const val DEVICE = 2
        const val TERRAIN = 3
    }
}

/**
 * Gerichteter Swept-Test eines Kreises (Radius r) von P0 nach P1 gegen Balken (Kapseln, über [BeamGrid]),
 * Gerätezonen (Kreise) und Gelände – frühester Treffer gewinnt (Prototyp `rayHit`). Gleichstand: Balken vor Gerät
 * vor Gelände, unter Balken die kleinere ID.
 *
 * Übersprungen werden: offene Türen ([BeamFlags.DOOR_OPEN]), bis zu zwei ignorierte Balken (eigener Montagebalken,
 * bereits durchschlagener Balken) und Geräte auf [ignoreDevicesOnBeam]. Geräte-Trefferzentren kommen aus den
 * DERIVED-Feldern des Geräte-Pools (vom DEVICES-System je Tick gesetzt).
 */
internal class RayCaster(private val grid: BeamGrid) {
    private val cp = ClosestParams()

    fun cast(
        state: GameState, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float,
        ignoreA: Int, ignoreB: Int, ignoreDevicesOnBeam: Int, withTerrain: Boolean, out: RayHit,
    ): Boolean {
        out.reset()
        grid.ensure(state)
        castBeams(state, x0, y0, x1, y1, radius, ignoreA, ignoreB, out)
        castDevices(state, x0, y0, x1, y1, radius, ignoreDevicesOnBeam, out)
        if (withTerrain) {
            val tt = terrainHit(state, x0, y0, x1, y1)
            if (tt >= 0f && tt < out.t) { out.kind = RayHit.TERRAIN; out.id = -1; out.t = tt }
        }
        if (out.kind == RayHit.NONE) return false
        out.x = x0 + (x1 - x0) * out.t
        out.y = y0 + (y1 - y0) * out.t
        return true
    }

    private fun castBeams(
        state: GameState, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float,
        ignoreA: Int, ignoreB: Int, out: RayHit,
    ) {
        val g = grid
        val beams = state.beams
        val nodes = state.nodes
        val mats = state.tables.materials
        val stamp = g.nextStamp()
        val mark = g.mark
        val built = g.builtSize
        val dx = x1 - x0; val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        val cs = g.cellSize
        val nf = len / cs
        var chunks = nf.toInt()
        if (chunks < nf) chunks++
        if (chunks < 1) chunks = 1
        val start = g.start
        val items = g.items
        val cols = g.cols
        var bestT = out.t
        var bestId = out.id
        val inv = 1f / chunks
        for (k in 0 until chunks) {
            val ta = k * inv
            val tb = if (k == chunks - 1) 1f else (k + 1) * inv
            val sx = x0 + dx * ta; val sy = y0 + dy * ta
            val ex = x0 + dx * tb; val ey = y0 + dy * tb
            val cx0 = g.cellX(FloatMath.min(sx, ex)); val cx1 = g.cellX(FloatMath.max(sx, ex))
            val cy0 = g.cellY(FloatMath.min(sy, ey)); val cy1 = g.cellY(FloatMath.max(sy, ey))
            for (cy in cy0..cy1) {
                val row = cy * cols
                for (cx in cx0..cx1) {
                    val c = row + cx
                    val e = start[c + 1]
                    var p = start[c]
                    while (p < e) {
                        val j = items[p++]
                        if (j >= built || mark[j] == stamp) continue
                        mark[j] = stamp
                        if (j == ignoreA || j == ignoreB || !beams.isAlive(j)) continue
                        if ((beams.flags[j] and BeamFlags.DOOR_OPEN) != 0) continue
                        val a = beams.a[j]; val b = beams.b[j]
                        val half = mats[beams.materialOf[j]].thickness * 0.5f
                        val t = Geometry.sweptCapsule(
                            x0, y0, x1, y1, radius, nodes.x[a], nodes.y[a], nodes.x[b], nodes.y[b], half, cp,
                        )
                        if (t < 0f) continue
                        if (t < bestT || (t == bestT && j < bestId)) { bestT = t; bestId = j }
                    }
                }
            }
        }
        if (bestId >= 0 && bestT <= 1f) { out.kind = RayHit.BEAM; out.id = bestId; out.t = bestT }
    }

    private fun castDevices(
        state: GameState, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, ignoreBeam: Int, out: RayHit,
    ) {
        val d = state.devices
        val props = state.tables.devices
        val scale = state.config.combat.deviceRayRadiusScale
        val n = d.size
        val minX = FloatMath.min(x0, x1); val maxX = FloatMath.max(x0, x1)
        val minY = FloatMath.min(y0, y1); val maxY = FloatMath.max(y0, y1)
        for (i in 0 until n) {
            if (!d.isAlive(i)) continue
            if (ignoreBeam >= 0 && d.beamId[i] == ignoreBeam) continue
            val p = props[d.typeOf[i]]
            val cx = d.x[i] + d.nx[i] * p.mountOffset
            val cy = d.y[i] + d.ny[i] * p.mountOffset
            val r = p.hitRadius * scale
            val rr = r + radius
            if (cx + rr < minX || cx - rr > maxX || cy + rr < minY || cy - rr > maxY) continue
            val t = Geometry.sweptCircle(x0, y0, x1, y1, radius, cx, cy, r)
            if (t < 0f) continue
            if (t < out.t) { out.kind = RayHit.DEVICE; out.id = i; out.t = t }
        }
    }

    /**
     * Segment gegen Gelände (Prototyp `terrainHit`): Abtastung alle `terrainSampleStep` m, dann 6 Bisektionsschritte.
     * @return t in [0, 1] des ersten Treffers oder −1. Liegt P0 schon unter der Oberfläche: 0.
     */
    fun terrainHit(state: GameState, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val terrain = state.terrain
        if (y0 > terrain.heightAt(x0)) return 0f
        val dx = x1 - x0; val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        val nf = len / state.config.combat.terrainSampleStep
        var n = nf.toInt()
        if (n < nf) n++
        if (n < 1) n = 1
        var prev = 0f
        for (i in 1..n) {
            val t = i.toFloat() / n
            if (y0 + dy * t >= terrain.heightAt(x0 + dx * t)) {
                var lo = prev; var hi = t
                for (k in 0 until 6) {
                    val m = (lo + hi) * 0.5f
                    if (y0 + dy * m >= terrain.heightAt(x0 + dx * m)) hi = m else lo = m
                }
                return hi
            }
            prev = t
        }
        return -1f
    }
}
