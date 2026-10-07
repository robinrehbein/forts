package de.bollwerk.ai

import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.sim.WeaponProps
import de.bollwerk.engine.tools.ShotSweep
import de.bollwerk.engine.tools.TrajectoryOutcome
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
    /**
     * FX1: Die vorhergesagte Bahn trifft zuerst die eigene Festung (eigener Balken/eigenes Gerät, gleiche Kollision wie
     * die Sim über `ShotSweep`). Solche Lösungen haben [exposure] 0 und werden nie abgefeuert.
     */
    var selfBlocked: Boolean = false

    fun reset() {
        valid = false; angle = 0f; power = 1f; exposure = 0f; highArc = false
        impactX = 0f; impactY = 0f; doorToOpen = -1; distance = 0f; selfBlocked = false
    }

    fun copyFrom(o: AimSolution) {
        valid = o.valid; angle = o.angle; power = o.power; exposure = o.exposure; highArc = o.highArc
        impactX = o.impactX; impactY = o.impactY; doorToOpen = o.doorToOpen; distance = o.distance; selfBlocked = o.selfBlocked
    }
}

/**
 * Zielen der KI (WP11).
 * - **Ballistisch** (Mörser, Kanone, Brandrakete): [Ballistics.solveAngle] in der Waffen-Fassung, d. h. mit
 *   Mündungsgeschwindigkeit **und** `gravityScale` der Waffe sowie dem aktuellen Wind. Weil die Mündung vom Winkel
 *   abhängt (Drehpunkt + Rohr), wird der Löser zweimal mit der jeweils neuen Mündung aufgerufen. Flacher oder steiler
 *   Bogen und Kraftstufe werden nach Hindernisfreiheit der vorhergesagten Bahn gewählt. Die Bahn verfolgt der gemeinsame
 *   `ShotSweep` der Engine ([Obstacles.sweep]) – dieselbe Kollision wie Simulation und Zielvorschau (Teilschritte,
 *   Swept-Test, Türen, Montagebalken). Trifft sie zuerst die eigene Festung ([AimSolution.selfBlocked]), ist die Lösung
 *   wertlos; dann gewinnt der andere Bogen, eine andere Kraft, ein anderes Ziel oder eine andere Waffe.
 * - **Hitscan/Strahl** (MG, Scharfschütze, Laser): direkt auf das Ziel; die Mündung liegt auf dem Strahl vom
 *   Drehpunkt, daher ist der Winkel Drehpunkt → Ziel exakt.
 * - Zielfehler: [applyError] addiert eine annähernd normalverteilte Abweichung aus dem KI-Strom
 *   (σ = `Difficulty.aimErrorDeg`: Leicht 6°, Normal 3°, Schwer 1°). [applyErrorClear] verhindert, dass der Fehler den
 *   Schuss in die eigene Festung lenkt, ohne ihn zu verkleinern: neu ziehen (begrenzt), dann spiegeln, erst zuletzt der
 *   fehlerfreie, geprüfte Winkel.
 * - Der Bahn-Prüfhorizont ist die volle Lebensdauer des Geschosses ([ShotSweep.lifetimeTicks]), damit auch späte
 *   Eigentreffer (Brandrakete) erkannt werden.
 */
class AimController(private val obstacles: Obstacles) {
    private val geo = FloatArray(DeviceGeometry.SIZE)
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

    /**
     * FX1-Zähler: bewertete Bahnen, die zuerst die eigene Festung träfen; Zielfehler, die in die eigene Festung zeigten
     * ([errorCorrections]), davon neu gezogen ([errorRedraws]) bzw. gespiegelt ([errorMirrors]); der Rest wurde auf den
     * fehlerfreien Winkel zurückgenommen.
     */
    var selfBlockedArcs: Long = 0L; private set
    var errorCorrections: Long = 0L; private set
    var errorRedraws: Long = 0L; private set
    var errorMirrors: Long = 0L; private set

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
                var preferredBlocked = false
                for (arc in 0 until 2) {
                    // Eigentreffer-Vermeidung hängt nicht vom Schwierigkeitsgrad ab: ist der bevorzugte Bogen durch die eigene
                    // Festung blockiert, prüft auch Leicht den anderen (FX1-Review)
                    if (arc == 1 && !tuning.tryBothArcs && !preferredBlocked) break
                    val high = if (arc == 0) preferHigh else !preferHigh
                    if (!ballistic(view, me, deviceId, props, weapon, px, py, tx, ty, tr, targetId, power, high, facing, mount, autoDoor, trial)) continue
                    if (arc == 0) preferredBlocked = trial.selfBlocked
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
            if (ignore2 < 0 && (hit.kind == CastHit.BEAM || hit.kind == CastHit.DEVICE) && hit.owner == me) out.selfBlocked = true
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

    /**
     * Bahn bei Winkel [a] und Kraft [power] mit dem gemeinsamen `ShotSweep` verfolgen (wie die Sim: Teilschritte,
     * Swept-Test gegen Balken/Geräte/Gelände, Montagebalken in den ersten `sourceIgnoreTicks`, automatisch öffnende Tür)
     * und den ersten Treffer bewerten (füllt [out]). Eigene geschlossene Türen gelten als passierbar (die KI öffnet sie,
     * [AimSolution.doorToOpen]).
     */
    private fun tracePath(
        view: GameView, me: Int, props: DeviceProps, weapon: WeaponProps, px: Float, py: Float, a: Float, power: Float,
        tx: Float, ty: Float, tr: Float, targetId: Int, mount: Int, autoDoor: Int, out: AimSolution,
    ) {
        val sweep = obstacles.sweep
        // Kollision über die ganze Lebensdauer wie in der Sim (nicht nur den Löser-Horizont MAX_POINTS)
        val maxTicks = ShotSweep.lifetimeTicks(weapon)
        val mx = px + FastTrig.cos(a) * props.barrelLength
        val my = py - FastTrig.sin(a) * props.barrelLength
        out.valid = true
        out.angle = a
        out.power = power
        // eigene Türen genau wie die Sim (Eigentreffer!), offene Türen des Gegners bleiben offen (er schießt durch sie)
        val outcome = sweep.ballistic(me, mount, autoDoor, mx, my, a, power, weapon, view.wind, maxTicks, passDoorsOf = me, timedDoorsOf = me)
        out.doorToOpen = sweep.doorCrossed
        out.selfBlocked = outcome == TrajectoryOutcome.BLOCKED_OWN
        if (out.selfBlocked) selfBlockedArcs++
        if (outcome.hasHit) {
            Obstacles.copyHit(sweep, hit)
            out.exposure = classify(me, hit, tx, ty, tr, targetId, weapon, view)
            out.impactX = hit.x; out.impactY = hit.y
            return
        }
        // Karte verlassen / Lebensdauer: nur Splash in Zielnähe zählt
        val ex = if (sweep.hitX.isNaN()) mx else sweep.hitX
        val ey = if (sweep.hitY.isNaN()) my else sweep.hitY
        val ddx = ex - tx; val ddy = ey - ty
        val d = sqrt(ddx * ddx + ddy * ddy)
        out.exposure = if (weapon.splashRadius > 0f && d < weapon.splashRadius * 0.7f + tr) SPLASH_NEAR else 0f
        out.impactX = ex; out.impactY = ey
    }

    /**
     * Zielfehler wie [applyError], aber nie in die eigene Festung – ohne den Fehler zu verkleinern (FX1-Review): Trifft
     * die Bahn beim gestörten Winkel zuerst die eigene Festung (gemeinsamer `ShotSweep`, Türzustand beim Schuss), wird
     * bis zu [ERROR_REDRAWS]-mal neu gezogen (gleiche Verteilung, beschränkt auf freie Winkel; aus demselben KI-Strom,
     * deterministisch). Sind alle Ziehungen blockiert, wird die erste Abweichung gespiegelt (`2·sol.angle − noisy`:
     * gleicher Betrag, andere Richtung); erst wenn auch das blockiert ist oder außerhalb der Zielgrenzen liegt, gilt der
     * fehlerfreie Winkel der Lösung [sol] (die selbst frei ist, sonst hätte sie kein Freiliegen). Benutzt die
     * Momentaufnahme des letzten [Obstacles.rebuild]. Ohne Blockade derselbe Zufallsverbrauch wie [applyError].
     */
    fun applyErrorClear(
        view: GameView, me: Int, deviceId: Int, props: DeviceProps, weapon: WeaponProps, sol: AimSolution, sigmaDeg: Float,
        rng: AiRng,
    ): Float {
        val noisy = applyError(sol.angle, sigmaDeg, rng)
        if (noisy == sol.angle || !selfBlockedAt(view, me, deviceId, props, weapon, noisy, sol.power)) return noisy
        errorCorrections++
        for (r in 0 until ERROR_REDRAWS) {
            val again = applyError(sol.angle, sigmaDeg, rng)
            if (!selfBlockedAt(view, me, deviceId, props, weapon, again, sol.power)) {
                errorRedraws++
                return again
            }
        }
        val mirrored = sol.angle + (sol.angle - noisy)
        if (elevationOk(mirrored, view.player(me).facing, weapon) &&
            !selfBlockedAt(view, me, deviceId, props, weapon, mirrored, sol.power)
        ) {
            errorMirrors++
            return mirrored
        }
        return sol.angle
    }

    /** Träfe Waffe [deviceId] bei Winkel [a] und Kraft [power] zuerst die eigene Festung? (Türzustand wie beim Schuss.) */
    fun selfBlockedAt(view: GameView, me: Int, deviceId: Int, props: DeviceProps, weapon: WeaponProps, a: Float, power: Float): Boolean {
        val sweep = obstacles.sweep
        DeviceGeometry.mount(view, deviceId, geo)
        val px = geo[DeviceGeometry.PIVOT_X]; val py = geo[DeviceGeometry.PIVOT_Y]
        val mount = view.deviceView.beam(deviceId)
        val autoDoor = autoOpenDoor(view, me, px, py)
        val mx = px + FastTrig.cos(a) * props.barrelLength
        val my = py - FastTrig.sin(a) * props.barrelLength
        val outcome = if (weapon.mode == WeaponMode.BALLISTIC) {
            sweep.ballistic(me, mount, autoDoor, mx, my, a, power, weapon, view.wind, ShotSweep.lifetimeTicks(weapon), timedDoorsOf = me)
        } else {
            val reach = if (weapon.maxRange > 0f) weapon.maxRange else 200f
            sweep.ray(me, mount, autoDoor, mx, my, mx + FastTrig.cos(a) * reach, my - FastTrig.sin(a) * reach, weapon.projectileRadius)
        }
        return outcome == TrajectoryOutcome.BLOCKED_OWN
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
     * `DoorConfig.searchRadius` um den Drehpunkt (Prototyp `doorFor`). −1 = keine. Gemeinsam mit der Zielvorschau
     * ([ShotSweep.autoOpenDoor]).
     */
    fun autoOpenDoor(view: GameView, me: Int, px: Float, py: Float): Int = ShotSweep.autoOpenDoor(view, me, px, py)

    companion object {
        const val MAX_POINTS: Int = 720
        /** Neue Ziehungen des Zielfehlers, wenn er in die eigene Festung zeigt ([applyErrorClear]). */
        const val ERROR_REDRAWS: Int = 3
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
