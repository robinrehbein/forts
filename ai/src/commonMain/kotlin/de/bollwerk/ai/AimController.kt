package de.bollwerk.ai

import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.math.Geometry
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.sim.WeaponProps
import de.bollwerk.engine.view.GameView
import kotlin.math.sqrt

/** Ergebnis von [AimController.evaluate]: bester Schuss einer Waffe auf ein Ziel. */
class AimSolution {
    var valid: Boolean = false
    /** Zielwinkel (Bogenmaß, Sim-Konvention: 0 = rechts, positiv = oben) ohne Zielfehler. */
    var angle: Float = 0f
    var power: Float = 1f
    /** 0..1: 1 = Bahn erreicht das Ziel frei, ~0,7 Splash reicht, ~0,3 trifft Deckung nahe am Ziel, 0 = nutzlos. */
    var exposure: Float = 0f
    var highArc: Boolean = false
    var impactX: Float = 0f
    var impactY: Float = 0f
    /** Eigene geschlossene Tür, die erst geöffnet werden muss (−1 = keine). */
    var doorToOpen: Int = -1
    /** Abstand Drehpunkt → Ziel in m. */
    var distance: Float = 0f

    fun reset() {
        valid = false; angle = 0f; power = 1f; exposure = 0f; highArc = false
        impactX = 0f; impactY = 0f; doorToOpen = -1; distance = 0f
    }

    fun copyFrom(o: AimSolution) {
        valid = o.valid; angle = o.angle; power = o.power; exposure = o.exposure; highArc = o.highArc
        impactX = o.impactX; impactY = o.impactY; doorToOpen = o.doorToOpen; distance = o.distance
    }
}

/**
 * Zielen der KI (WP11).
 * - **Ballistisch** (Mörser, Kanone, Brandrakete): [Ballistics.solveAngle] in der Waffen-Fassung, d. h. mit
 *   Mündungsgeschwindigkeit **und** `gravityScale` der Waffe sowie dem aktuellen Wind. Weil die Mündung vom Winkel
 *   abhängt (Drehpunkt + Rohr), wird der Löser zweimal mit der jeweils neuen Mündung aufgerufen. Flacher oder steiler
 *   Bogen und Kraftstufe werden nach Hindernisfreiheit der vorhergesagten Bahn ([Ballistics.predict] gegen [Obstacles])
 *   gewählt.
 * - **Hitscan/Strahl** (MG, Scharfschütze, Laser): direkt auf das Ziel; die Mündung liegt auf dem Strahl vom
 *   Drehpunkt, daher ist der Winkel Drehpunkt → Ziel exakt.
 * - Zielfehler: [applyError] addiert eine annähernd normalverteilte Abweichung aus dem KI-Strom
 *   (σ = `Difficulty.aimErrorDeg`: Leicht 6°, Normal 3°, Schwer 1°).
 */
class AimController(private val obstacles: Obstacles) {
    private val geo = FloatArray(DeviceGeometry.SIZE)
    private val path = FloatArray(MAX_POINTS * 2)
    private val hit = CastHit()
    private val circle = FloatArray(3)
    private val trial = AimSolution()
    private val st = FloatArray(4)

    // Warmstart-Speicher: letzte Lösung je (Waffe, Kraft, Bogen, Zielpunkt), Ringpuffer ohne Hash-Container
    private val cDevice = IntArray(CACHE_SIZE) { -1 }
    private val cPower = FloatArray(CACHE_SIZE)
    private val cHigh = BooleanArray(CACHE_SIZE)
    private val cTx = FloatArray(CACHE_SIZE)
    private val cTy = FloatArray(CACHE_SIZE)
    private val cAngle = FloatArray(CACHE_SIZE)
    private var cNext = 0

    /** Zähler für Rechenbudget-Tests: volle Rastersuchen (`Ballistics.solveAngle`) und lokale Bahn-Auswertungen. */
    var fullSolves: Long = 0L; private set
    var localEvals: Long = 0L; private set
    /** Bewertete (Ziel, Kraft, Bogen)-Kombinationen. */
    var arcEvaluations: Long = 0L; private set

    /** Drehpunkt der zuletzt bewerteten Waffe (für Tests/Debug). */
    var pivotX: Float = 0f; private set
    var pivotY: Float = 0f; private set

    /**
     * Bewertet den besten Schuss von Waffe [deviceId] auf Gerät [targetId].
     * @return `true`, wenn eine gültige Lösung (im Elevations- und Reichweitenbereich) existiert; Details in [out].
     */
    fun evaluate(
        view: GameView, me: Int, deviceId: Int, props: DeviceProps, weapon: WeaponProps, targetId: Int,
        tuning: AiTuning, out: AimSolution,
    ): Boolean {
        out.reset()
        if (!obstacles.deviceCircle(targetId, circle)) return false
        return evaluatePoint(view, me, deviceId, props, weapon, circle[0], circle[1], circle[2], targetId, tuning, out)
    }

    /** Wie [evaluate], aber auf einen Punkt ([tx], [ty]) mit Trefferradius [tr]; [targetId] darf −1 sein. */
    fun evaluatePoint(
        view: GameView, me: Int, deviceId: Int, props: DeviceProps, weapon: WeaponProps,
        tx: Float, ty: Float, tr: Float, targetId: Int, tuning: AiTuning, out: AimSolution,
    ): Boolean {
        out.reset()
        DeviceGeometry.mount(view, deviceId, geo)
        val px = geo[DeviceGeometry.PIVOT_X]; val py = geo[DeviceGeometry.PIVOT_Y]
        pivotX = px; pivotY = py
        val dx = tx - px; val dy = ty - py
        val dist = sqrt(dx * dx + dy * dy)
        if (weapon.maxRange > 0f && dist > weapon.maxRange + RANGE_SLACK) return false
        if (weapon.minRange > 0f && dist < weapon.minRange) return false
        val mount = view.deviceView.beam(deviceId)
        val autoDoor = autoOpenDoor(view, me, px, py)
        val facing = view.player(me).facing
        if (weapon.mode == WeaponMode.BALLISTIC) {
            val preferHigh = weapon.minAimRad > HIGH_ARC_MIN_ELEVATION
            for (pi in tuning.powerSteps.indices) {
                val power = FloatMath.clamp(tuning.powerSteps[pi], view.simConfig.minPower, view.simConfig.maxPower)
                for (arc in 0 until 2) {
                    if (arc == 1 && !tuning.tryBothArcs) break
                    val high = if (arc == 0) preferHigh else !preferHigh
                    if (!ballistic(view, me, deviceId, props, weapon, px, py, tx, ty, tr, targetId, power, high, facing, mount, autoDoor, trial)) continue
                    trial.distance = dist
                    if (!out.valid || trial.exposure > out.exposure + EXPOSURE_EPS) out.copyFrom(trial)
                    if (out.exposure >= 0.99f) return true
                }
            }
            return out.valid
        }
        // Hitscan / Strahl: direkt zielen
        val angle = FastTrig.atan2(-dy, dx)
        if (!elevationOk(angle, facing, weapon)) return false
        val c = FastTrig.cos(angle); val s = -FastTrig.sin(angle)
        val mx = px + c * props.barrelLength; val my = py + s * props.barrelLength
        val reach = if (weapon.maxRange > 0f) weapon.maxRange else dist + 2f
        val ex = mx + c * reach; val ey = my + s * reach
        out.valid = true
        out.angle = angle
        out.power = view.simConfig.maxPower
        out.highArc = false
        out.distance = dist
        var ignore2 = -1
        var pierce = weapon.piercesBeams
        var exposure = 0f
        var door = -1
        while (true) {
            if (!obstacles.cast(mx, my, ex, ey, weapon.projectileRadius, mount, me, autoDoor, true, hit, ignore2)) {
                exposure = 0f
                break
            }
            if (door < 0) door = hit.doorCrossed
            if (hit.kind == CastHit.BEAM && pierce > 0 && hit.owner != me) {
                pierce--
                ignore2 = hit.id
                continue
            }
            exposure = classify(me, hit, tx, ty, tr, targetId, weapon, view)
            break
        }
        // durchschlagene Balken mindern den Nutzen etwas
        if (ignore2 >= 0 && exposure > 0f) exposure *= PIERCE_FACTOR
        out.exposure = exposure
        out.impactX = hit.x; out.impactY = hit.y
        out.doorToOpen = door
        return true
    }

    /**
     * Ein Bogen bei fester Kraft: Winkel lösen (Mündung iteriert) und die Bahn auf Hindernisse prüfen.
     * Rechenzeit: Gibt es eine frühere Lösung für dieselbe Waffe, Kraft, denselben Bogen und (fast) denselben Zielpunkt,
     * wird nur lokal um sie herum gesucht ([localSolve]); sonst einmal die volle Rastersuche [Ballistics.solveAngle],
     * danach (neue Mündung) wieder lokal.
     */
    private fun ballistic(
        view: GameView, me: Int, deviceId: Int, props: DeviceProps, weapon: WeaponProps,
        px: Float, py: Float, tx: Float, ty: Float, tr: Float, targetId: Int, power: Float, high: Boolean,
        facing: Int, mount: Int, autoDoor: Int, out: AimSolution,
    ): Boolean {
        out.reset()
        arcEvaluations++
        val cfg = view.simConfig
        val wind = view.wind
        val maxTicks = if (weapon.ttlTicks in 1 until MAX_POINTS) weapon.ttlTicks else MAX_POINTS
        val cached = cacheFind(deviceId, power, high, tx, ty)
        var a = if (cached >= 0) cAngle[cached] else view.deviceView.aimAngle(deviceId)
        var warm = cached >= 0
        var mx = 0f; var my = 0f
        for (iter in 0 until SOLVE_ITERATIONS) {
            mx = px + FastTrig.cos(a) * props.barrelLength
            my = py - FastTrig.sin(a) * props.barrelLength
            var s = if (warm) localSolve(mx, my, tx, ty, power, weapon, wind, cfg, high, maxTicks, a) else Float.NaN
            if (s.isNaN()) {
                fullSolves++
                s = Ballistics.solveAngle(mx, my, tx, ty, power, weapon, wind, cfg, high, maxTicks)
            }
            if (s.isNaN()) return false
            val change = FloatMath.abs(s - a)
            a = s
            warm = true // die nächste Mündung liegt dicht an der alten: lokal weitersuchen
            if (change < CONVERGED) break
        }
        cachePut(cached, deviceId, power, high, tx, ty, a)
        if (!elevationOk(a, facing, weapon)) return false
        out.highArc = high
        tracePath(view, me, props, weapon, px, py, a, power, tx, ty, tr, targetId, mount, autoDoor, out)
        return true
    }

    /**
     * Wohin führt die **aktuelle** Ausrichtung (Winkel und Kraft, wie sie an der Waffe stehen) von Waffe [deviceId]
     * in Bezug auf Ziel [targetId]? Nur ballistische Waffen (Hitscan streut). Das entspricht dem Blick auf den letzten
     * Einschlag: Lag er gut, kann die KI ohne neues Richten nachfeuern.
     * @return `false`, wenn nicht bewertbar (keine ballistische Waffe, Ziel fehlt).
     */
    fun evaluateCurrentAim(
        view: GameView, me: Int, deviceId: Int, props: DeviceProps, weapon: WeaponProps, targetId: Int, out: AimSolution,
    ): Boolean {
        out.reset()
        if (weapon.mode != WeaponMode.BALLISTIC) return false
        if (!obstacles.deviceCircle(targetId, circle)) return false
        DeviceGeometry.mount(view, deviceId, geo)
        val px = geo[DeviceGeometry.PIVOT_X]; val py = geo[DeviceGeometry.PIVOT_Y]
        val d = view.deviceView
        val a = d.aimAngle(deviceId)
        if (!elevationOk(a, view.player(me).facing, weapon)) return false
        val autoDoor = autoOpenDoor(view, me, px, py)
        tracePath(view, me, props, weapon, px, py, a, d.power(deviceId), circle[0], circle[1], circle[2], targetId, d.beam(deviceId), autoDoor, out)
        val dx = circle[0] - px; val dy = circle[1] - py
        out.distance = sqrt(dx * dx + dy * dy)
        return true
    }

    /** Bahn bei Winkel [a] und Kraft [power] vorhersagen und den ersten Treffer bewerten (füllt [out]). */
    private fun tracePath(
        view: GameView, me: Int, props: DeviceProps, weapon: WeaponProps, px: Float, py: Float, a: Float, power: Float,
        tx: Float, ty: Float, tr: Float, targetId: Int, mount: Int, autoDoor: Int, out: AimSolution,
    ) {
        val cfg = view.simConfig
        val wind = view.wind
        val maxTicks = if (weapon.ttlTicks in 1 until MAX_POINTS) weapon.ttlTicks else MAX_POINTS
        val mx = px + FastTrig.cos(a) * props.barrelLength
        val my = py - FastTrig.sin(a) * props.barrelLength
        val n = Ballistics.predict(mx, my, a, power, weapon, wind, cfg, path, maxTicks, view.terrain, view.map)
        out.valid = true
        out.angle = a
        out.power = power
        var prevX = mx; var prevY = my
        var door = -1
        val ignoreTicks = cfg.combat.sourceIgnoreTicks
        for (k in 0 until n) {
            val x = path[k * 2]; val y = path[k * 2 + 1]
            val ign = if (k < ignoreTicks) mount else -1
            if (obstacles.cast(prevX, prevY, x, y, weapon.projectileRadius, ign, me, autoDoor, false, hit)) {
                if (door < 0) door = hit.doorCrossed
                out.exposure = classify(me, hit, tx, ty, tr, targetId, weapon, view)
                out.impactX = hit.x; out.impactY = hit.y
                out.doorToOpen = door
                return
            }
            if (door < 0) door = hit.doorCrossed
            prevX = x; prevY = y
        }
        // Gelände/Kartengrenze: nur Splash in Zielnähe zählt
        val ddx = prevX - tx; val ddy = prevY - ty
        val d = sqrt(ddx * ddx + ddy * ddy)
        out.exposure = if (weapon.splashRadius > 0f && d < weapon.splashRadius * 0.7f + tr) SPLASH_NEAR else 0f
        out.impactX = prevX; out.impactY = prevY
        out.doorToOpen = door
    }

    /**
     * Lokale Winkelsuche um [guess]: Klammer mit wachsender Schrittweite (0,5° … 8°) auf beiden Seiten, dann Bisektion.
     * Dieselbe Fehlerfunktion wie [Ballistics.solveAngle] (Höhe der Bahn bei x = [tx] minus [ty], gleiche
     * `Ballistics.tick`-Integration mit Wind und `gravityScale`). Die gefundene Nullstelle muss zum gewünschten Bogen
     * passen (flach: Bahn steigt mit der Elevation über das Ziel; steil: umgekehrt), sonst NaN → volle Suche.
     */
    private fun localSolve(
        x0: Float, y0: Float, tx: Float, ty: Float, power: Float, weapon: WeaponProps, wind: Float, cfg: SimConfig,
        high: Boolean, maxTicks: Int, guess: Float,
    ): Float {
        val right = tx >= x0
        val e0 = heightError(x0, y0, tx, ty, guess, power, weapon, wind, cfg, maxTicks, right)
        if (!e0.isFinite()) return Float.NaN
        var step = LOCAL_STEP
        for (k in 0 until LOCAL_EXPANSIONS) {
            for (dir in 0 until 2) {
                val b = if (dir == 0) guess + step else guess - step
                val eb = heightError(x0, y0, tx, ty, b, power, weapon, wind, cfg, maxTicks, right)
                if (!eb.isFinite() || (eb <= 0f) == (e0 <= 0f)) continue
                // Bogen prüfen: e bei der kleineren Elevation > 0 (Bahn unter dem Ziel) ⇒ flache Lösung
                val lowAngleE = if (b > guess) e0 else eb // e beim kleineren Winkel
                val lowElevE = if (right) lowAngleE else (if (b > guess) eb else e0)
                val isLowArc = lowElevE > 0f
                if (isLowArc == high) return Float.NaN
                var lo = guess; var elo = e0; var hi = b
                for (i in 0 until LOCAL_BISECT) {
                    val m = (lo + hi) * 0.5f
                    val em = heightError(x0, y0, tx, ty, m, power, weapon, wind, cfg, maxTicks, right)
                    if (!em.isFinite()) { hi = m; continue }
                    if ((em <= 0f) == (elo <= 0f)) { lo = m; elo = em } else hi = m
                }
                return (lo + hi) * 0.5f
            }
            step *= 2f
        }
        return Float.NaN
    }

    /** y(Bahn bei x = [tx]) − [ty]; +∞, wenn x = [tx] nicht erreicht wird (wie im Löser der Engine). */
    private fun heightError(
        x0: Float, y0: Float, tx: Float, ty: Float, angle: Float, power: Float, weapon: WeaponProps, wind: Float,
        cfg: SimConfig, maxTicks: Int, right: Boolean,
    ): Float {
        localEvals++
        Ballistics.launch(x0, y0, angle, power, weapon.muzzleSpeed, st)
        for (i in 0 until maxTicks) {
            val px = st[Ballistics.X]; val py = st[Ballistics.Y]
            Ballistics.tick(st, wind, cfg, weapon.gravityScale)
            val x = st[Ballistics.X]
            if (if (right) x >= tx else x <= tx) {
                val dx = x - px
                val f = if (dx != 0f) (tx - px) / dx else 1f
                return py + (st[Ballistics.Y] - py) * f - ty
            }
            if (st[Ballistics.VY] > 0f && st[Ballistics.Y] > ty + 200f) return Float.POSITIVE_INFINITY
        }
        return Float.POSITIVE_INFINITY
    }

    private fun cacheFind(device: Int, power: Float, high: Boolean, tx: Float, ty: Float): Int {
        for (k in 0 until CACHE_SIZE) {
            if (cDevice[k] != device || cPower[k] != power || cHigh[k] != high) continue
            if (FloatMath.abs(cTx[k] - tx) + FloatMath.abs(cTy[k] - ty) < CACHE_TARGET_TOL) return k
        }
        return -1
    }

    private fun cachePut(slot: Int, device: Int, power: Float, high: Boolean, tx: Float, ty: Float, angle: Float) {
        val k = if (slot >= 0) slot else cNext.also { cNext = (cNext + 1) % CACHE_SIZE }
        cDevice[k] = device; cPower[k] = power; cHigh[k] = high; cTx[k] = tx; cTy[k] = ty; cAngle[k] = angle
    }

    /** Nutzen eines Treffers in Bezug auf das Ziel. */
    private fun classify(
        me: Int, h: CastHit, tx: Float, ty: Float, tr: Float, targetId: Int, weapon: WeaponProps, view: GameView,
    ): Float {
        val dx = h.x - tx; val dy = h.y - ty
        val d = sqrt(dx * dx + dy * dy)
        return when (h.kind) {
            CastHit.DEVICE -> when {
                h.id == targetId -> 1f
                h.owner != me && h.owner >= 0 -> OTHER_ENEMY_DEVICE
                else -> 0f
            }
            CastHit.BEAM -> when {
                h.owner == me || h.owner < 0 -> 0f
                weapon.splashRadius > 0f && d < weapon.splashRadius + tr -> SPLASH_REACHES
                d < DIG_DISTANCE -> DIGGING
                else -> FAR_ENEMY_BEAM
            }
            CastHit.TERRAIN -> if (weapon.splashRadius > 0f && d < weapon.splashRadius * 0.7f + tr) SPLASH_NEAR else 0f
            else -> 0f
        }
    }

    /**
     * Die Tür, die das Waffen-System beim Schuss automatisch öffnet: nächste eigene Tür (kein Trümmer) im Umkreis
     * `DoorConfig.searchRadius` um den Drehpunkt (Prototyp `doorFor`). −1 = keine.
     */
    fun autoOpenDoor(view: GameView, me: Int, px: Float, py: Float): Int {
        val beams = view.beamView
        val nodes = view.nodeView
        val mats = view.tables.materials
        val r = view.simConfig.door.searchRadius
        var best = -1
        var bd = r * r
        for (j in 0 until beams.size) {
            if (!beams.isAlive(j) || beams.owner(j) != me) continue
            if ((beams.flags(j) and BeamFlags.DEBRIS) != 0) continue
            val m = beams.material(j)
            if (m < 0 || m >= mats.size || !mats[m].isDoor) continue
            val a = beams.nodeA(j); val b = beams.nodeB(j)
            val d2 = Geometry.pointSegmentDistSq(px, py, nodes.x(a), nodes.y(a), nodes.x(b), nodes.y(b))
            if (d2 < bd) { bd = d2; best = j }
        }
        return best
    }

    companion object {
        const val MAX_POINTS: Int = 720
        private const val RANGE_SLACK = 4f
        private const val SOLVE_ITERATIONS = 3
        private const val CACHE_SIZE = 48
        /** Zielpunkt-Abweichung (|dx| + |dy|, m), bis zu der eine frühere Lösung als Startwert dient. */
        private const val CACHE_TARGET_TOL = 3f
        private const val LOCAL_STEP = 0.5f * FloatMath.DEG_TO_RAD
        private const val LOCAL_EXPANSIONS = 5
        private const val LOCAL_BISECT = 22
        private const val CONVERGED = 0.002f
        private const val EXPOSURE_EPS = 0.01f
        /** Waffen mit größerer Mindest-Elevation (Mörser 20°) bevorzugen den steilen Bogen. */
        private const val HIGH_ARC_MIN_ELEVATION = 0.3f
        const val SPLASH_REACHES = 0.7f
        const val SPLASH_NEAR = 0.5f
        const val OTHER_ENEMY_DEVICE = 0.6f
        const val DIGGING = 0.3f
        const val FAR_ENEMY_BEAM = 0.08f
        const val DIG_DISTANCE = 7f
        private const val PIERCE_FACTOR = 0.85f

        /** Liegt [angle] im erlaubten Elevationsbereich der Waffe (Seite über [facing] gespiegelt)? */
        fun elevationOk(angle: Float, facing: Int, weapon: WeaponProps): Boolean {
            var e = if (facing >= 0) angle else FloatMath.PI - angle
            while (e > FloatMath.PI) e -= FloatMath.TWO_PI
            while (e <= -FloatMath.PI) e += FloatMath.TWO_PI
            return e >= weapon.minAimRad - 1e-4f && e <= weapon.maxAimRad + 1e-4f
        }

        /**
         * Zielfehler: Winkel + Normal(0, σ) mit σ = [sigmaDeg] Grad aus dem KI-Strom [rng].
         */
        fun applyError(angle: Float, sigmaDeg: Float, rng: AiRng): Float {
            if (!(sigmaDeg > 0f)) return angle
            return angle + rng.gaussian() * sigmaDeg * FloatMath.DEG_TO_RAD
        }
    }
}
