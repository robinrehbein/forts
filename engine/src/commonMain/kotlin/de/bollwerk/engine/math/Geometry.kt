package de.bollwerk.engine.math

import kotlin.math.sqrt

/**
 * Parameter der nächsten Punkte einer Abstandsabfrage (wiederverwendbar, keine Allokation im Hot-Path).
 * [s] gehört zum ersten Segment, [t] zum zweiten (bzw. zum Segment bei Punkt-Segment-Abfragen).
 */
class ClosestParams {
    var s: Float = 0f
    var t: Float = 0f
}

/**
 * Geometrie-Primitive für Kollision und Picking (portiert aus `segSeg`, `distPointSeg`, `rayHit`
 * des Prototyps). Alle Funktionen sind allokationsfrei und nutzen nur +, −, ×, ÷ und `sqrt`.
 */
object Geometry {
    private const val EPS = 1e-9f

    /**
     * Quadrierter kleinster Abstand zwischen Segment AB und Segment CD.
     * Optional werden die Parameter der nächsten Punkte in [out] geschrieben (s auf AB, t auf CD, je in [0, 1]).
     */
    fun segmentSegmentDistSq(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
        out: ClosestParams? = null,
    ): Float {
        val ux = bx - ax; val uy = by - ay
        val vx = dx - cx; val vy = dy - cy
        val wx = ax - cx; val wy = ay - cy
        val a = ux * ux + uy * uy
        val b = ux * vx + uy * vy
        val c = vx * vx + vy * vy
        val d = ux * wx + uy * wy
        val e = vx * wx + vy * wy
        val den = a * c - b * b
        var sN: Float
        var sD = den
        var tN: Float
        var tD = den
        if (den < EPS) {
            sN = 0f; sD = 1f; tN = e; tD = c
        } else {
            sN = b * e - c * d
            tN = a * e - b * d
            if (sN < 0f) { sN = 0f; tN = e; tD = c } else if (sN > sD) { sN = sD; tN = e + b; tD = c }
        }
        if (tN < 0f) {
            tN = 0f
            if (-d < 0f) sN = 0f else if (-d > a) sN = sD else { sN = -d; sD = a }
        } else if (tN > tD) {
            tN = tD
            if ((-d + b) < 0f) sN = 0f else if ((-d + b) > a) sN = sD else { sN = -d + b; sD = a }
        }
        val sc = if (FloatMath.abs(sN) < EPS || sD == 0f) 0f else sN / sD
        val tc = if (FloatMath.abs(tN) < EPS || tD == 0f) 0f else tN / tD
        val px = wx + sc * ux - tc * vx
        val py = wy + sc * uy - tc * vy
        if (out != null) { out.s = sc; out.t = tc }
        return px * px + py * py
    }

    /** Kleinster Abstand zwischen Segment AB und Segment CD. */
    fun segmentSegmentDist(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
        out: ClosestParams? = null,
    ): Float = sqrt(segmentSegmentDistSq(ax, ay, bx, by, cx, cy, dx, dy, out))

    /** Quadrierter Abstand von Punkt P zu Segment AB; Parameter des Fußpunkts in `out.t`. */
    fun pointSegmentDistSq(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float,
        out: ClosestParams? = null,
    ): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < EPS) 0f else FloatMath.clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0f, 1f)
        val qx = ax + dx * t - px
        val qy = ay + dy * t - py
        if (out != null) { out.s = 0f; out.t = t }
        return qx * qx + qy * qy
    }

    /** Abstand von Punkt P zu Segment AB. */
    fun pointSegmentDist(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float,
        out: ClosestParams? = null,
    ): Float = sqrt(pointSegmentDistSq(px, py, ax, ay, bx, by, out))

    /**
     * Swept-Capsule-Test: Ein Kreis mit Radius [radius] bewegt sich von P0 nach P1 und wird gegen
     * die Kapsel um Segment AB mit Halbdicke [halfThickness] getestet.
     *
     * @return frühester Bewegungsparameter t in [0, 1] beim Kontakt, oder `-1f` ohne Treffer.
     * Verhalten wie `rayHit` im Prototyp (nächster Punkt minus Rückversatz), dadurch kein Tunneling.
     */
    fun sweptCapsule(
        x0: Float, y0: Float, x1: Float, y1: Float, radius: Float,
        ax: Float, ay: Float, bx: Float, by: Float, halfThickness: Float,
        scratch: ClosestParams? = null,
    ): Float {
        val p = scratch ?: ClosestParams()
        val r = halfThickness + radius
        val d2 = segmentSegmentDistSq(x0, y0, x1, y1, ax, ay, bx, by, p)
        if (d2 >= r * r) return -1f
        val mx = x1 - x0; val my = y1 - y0
        val sl = sqrt(mx * mx + my * my)
        if (sl < EPS) return 0f
        val back = sqrt(FloatMath.max(0f, r * r - d2)) / sl
        return FloatMath.clamp(p.s - back, 0f, 1f)
    }

    /**
     * Swept-Circle-Test gegen einen Kreis (z. B. Geräte-Trefferzone) mit Mittelpunkt E und Radius [targetRadius].
     * @return frühester t in [0, 1] oder `-1f`.
     */
    fun sweptCircle(
        x0: Float, y0: Float, x1: Float, y1: Float, radius: Float,
        ex: Float, ey: Float, targetRadius: Float,
    ): Float {
        val r = targetRadius + radius
        val dx = x1 - x0; val dy = y1 - y0
        val l2 = dx * dx + dy * dy
        val tt = if (l2 < EPS) 0f else FloatMath.clamp(((ex - x0) * dx + (ey - y0) * dy) / l2, 0f, 1f)
        val qx = x0 + dx * tt - ex
        val qy = y0 + dy * tt - ey
        val q2 = qx * qx + qy * qy
        if (q2 >= r * r) return -1f
        val sl = sqrt(l2)
        if (sl < EPS) return 0f
        return FloatMath.clamp(tt - sqrt(FloatMath.max(0f, r * r - q2)) / sl, 0f, 1f)
    }
}
