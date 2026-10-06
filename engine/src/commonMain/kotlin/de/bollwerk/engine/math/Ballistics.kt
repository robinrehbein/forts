package de.bollwerk.engine.math

import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.Terrain

/**
 * Gemeinsame Ballistik für Simulation (WP4), Zielvorschau (WP8) und KI-Löser (WP11) – alle benutzen
 * **genau diese** Funktionen, damit Vorschau und Flug bitgleich übereinstimmen (Prototyp `ballistic()`):
 * semi-implizites Euler, erst v (Schwerkraft, Wind · `windAccelPerMps`), dann x; `projectileSubsteps`
 * Teilschritte pro Tick. Winkel: 0 = rechts, positiv = nach oben, Richtung `(cos a, −sin a)`.
 *
 * Zustand als `FloatArray(4)`: [X], [Y], [VX], [VY].
 */
object Ballistics {
    const val X = 0
    const val Y = 1
    const val VX = 2
    const val VY = 3

    /** Ein Teilschritt der Länge [h]. */
    fun step(st: FloatArray, h: Float, wind: Float, cfg: SimConfig) {
        st[VY] += cfg.gravity * h
        st[VX] += wind * cfg.windAccelPerMps * h
        st[X] += st[VX] * h
        st[Y] += st[VY] * h
    }

    /** Ein ganzer Tick (`projectileSubsteps` Teilschritte). */
    fun tick(st: FloatArray, wind: Float, cfg: SimConfig) {
        val n = cfg.projectileSubsteps
        val h = cfg.dt / n
        for (i in 0 until n) step(st, h, wind, cfg)
    }

    /** Startzustand aus Mündung ([x0], [y0]), Winkel, Kraft (0..1) und Mündungsgeschwindigkeit. */
    fun launch(x0: Float, y0: Float, angle: Float, power: Float, muzzleSpeed: Float, st: FloatArray) {
        val v = muzzleSpeed * power
        st[X] = x0; st[Y] = y0
        st[VX] = FastTrig.cos(angle) * v
        st[VY] = -FastTrig.sin(angle) * v
    }

    /**
     * Flugbahn-Vorschau: schreibt die Position nach jedem Tick als (x, y)-Paare in [out] (Länge ≥ 2·[maxPoints]),
     * bis Gelände ([terrain]) erreicht, Kartengrenze ([map]) verlassen oder [maxPoints] erreicht ist.
     * Der letzte Punkt ist der Einschlag (auf die Geländelinie interpoliert).
     * @return Anzahl Punkte.
     */
    fun predict(
        x0: Float, y0: Float, angle: Float, power: Float, muzzleSpeed: Float, wind: Float, cfg: SimConfig,
        out: FloatArray, maxPoints: Int, terrain: Terrain? = null, map: MapSpec? = null,
    ): Int {
        val st = FloatArray(4)
        launch(x0, y0, angle, power, muzzleSpeed, st)
        var count = 0
        val limit = if (maxPoints * 2 > out.size) out.size / 2 else maxPoints
        while (count < limit) {
            val px = st[X]; val py = st[Y]
            tick(st, wind, cfg)
            var x = st[X]; var y = st[Y]
            var stop = false
            if (terrain != null) {
                val g = terrain.heightAt(x)
                if (y >= g) {
                    // linear zwischen Vortick und Tick auf die Oberfläche interpolieren
                    val gp = terrain.heightAt(px)
                    val d0 = gp - py; val d1 = g - y
                    val denom = d0 - d1
                    val f = if (denom != 0f) FloatMath.clamp(d0 / denom, 0f, 1f) else 1f
                    x = px + (x - px) * f; y = py + (y - py) * f
                    stop = true
                }
            }
            if (map != null && map.isOutOfBounds(x, y)) stop = true
            out[count * 2] = x; out[count * 2 + 1] = y
            count++
            if (stop) break
        }
        return count
    }

    /**
     * Sucht den Abschusswinkel, mit dem die Bahn bei Kraft [power] den Punkt ([tx], [ty]) trifft
     * (Fehler = Höhe der Bahn bei x = tx minus ty). Rastersuche über 1°-Schritte in Zielrichtung, dann
     * Bisektion. [highArc] wählt die steile Lösung (Mörser).
     * @return Winkel in Bogenmaß oder `Float.NaN`, wenn das Ziel unerreichbar ist.
     */
    fun solveAngle(
        x0: Float, y0: Float, tx: Float, ty: Float, power: Float, muzzleSpeed: Float, wind: Float,
        cfg: SimConfig, highArc: Boolean, maxTicks: Int = 900,
    ): Float {
        val right = tx >= x0
        val st = FloatArray(4)
        fun err(a: Float): Float = heightErrorAt(x0, y0, tx, ty, a, power, muzzleSpeed, wind, cfg, maxTicks, st, right)
        val steps = 88
        var bestLo = Float.NaN; var bestHi = Float.NaN
        var prevA = deg(1f, right)
        var prevE = err(prevA)
        for (i in 2..steps) {
            val a = deg(i.toFloat(), right)
            val e = err(a)
            if (prevE.isFinite() && e.isFinite() && ((prevE <= 0f) != (e <= 0f))) {
                val root = bisect(prevA, prevE, a, ::err)
                if (bestLo.isNaN()) bestLo = root
                bestHi = root
            }
            prevA = a; prevE = e
        }
        return if (highArc) bestHi else bestLo
    }

    private fun deg(d: Float, right: Boolean): Float {
        val r = d * FloatMath.DEG_TO_RAD
        return if (right) r else FloatMath.PI - r
    }

    private inline fun bisect(a0: Float, e0: Float, b0: Float, err: (Float) -> Float): Float {
        var a = a0; var ea = e0; var b = b0
        for (k in 0 until 24) {
            val m = (a + b) * 0.5f
            val em = err(m)
            if (!em.isFinite()) { b = m; continue }
            if ((em <= 0f) == (ea <= 0f)) { a = m; ea = em } else b = m
        }
        return (a + b) * 0.5f
    }

    /** y(Bahn bei x = tx) − ty; +∞, wenn x = tx nie erreicht wird (zu kurz). */
    private fun heightErrorAt(
        x0: Float, y0: Float, tx: Float, ty: Float, angle: Float, power: Float, muzzleSpeed: Float, wind: Float,
        cfg: SimConfig, maxTicks: Int, st: FloatArray, right: Boolean,
    ): Float {
        launch(x0, y0, angle, power, muzzleSpeed, st)
        for (i in 0 until maxTicks) {
            val px = st[X]; val py = st[Y]
            tick(st, wind, cfg)
            val crossed = if (right) st[X] >= tx else st[X] <= tx
            if (crossed) {
                val dx = st[X] - px
                val f = if (dx != 0f) (tx - px) / dx else 1f
                return py + (st[Y] - py) * f - ty
            }
            // Fällt weit unter das Ziel, ohne es zu erreichen → zu kurz
            if (st[VY] > 0f && st[Y] > ty + 200f) return Float.POSITIVE_INFINITY
        }
        return Float.POSITIVE_INFINITY
    }
}
