package de.bollwerk.engine.tools

import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.ClosestParams
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.math.Geometry
import de.bollwerk.engine.rules.RuleChecks
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.sim.WeaponProps
import de.bollwerk.engine.view.GameView
import kotlin.math.sqrt

/**
 * Was der vorhergesagte Schuss als Erstes trifft (FX1, Eigentreffer).
 * - [CLEAR]: nichts – die Bahn verlässt die Karte oder die Lebensdauer läuft ab.
 * - [TERRAIN]: Gelände.
 * - [HIT_ENEMY]: Balken oder Gerät, das nicht dem Schützen gehört (Gegner, neutral).
 * - [BLOCKED_OWN]: eigener Balken oder eigenes Gerät – der Schuss explodiert in der eigenen Festung (Stil-Bibel: rot).
 */
enum class TrajectoryOutcome {
    CLEAR, TERRAIN, HIT_ENEMY, BLOCKED_OWN;

    /** Die Bahn endet an einem Treffer (Einschlag-Markierung zeichnen). */
    val hasHit: Boolean get() = this != CLEAR
}

/**
 * Gemeinsame Schussbahn-Prüfung für Zielvorschau (`AimPreviewer`) und KI (`AimController`/`Obstacles`): verfolgt einen
 * Schuss **genau wie die Simulation** und meldet den ersten Treffer.
 *
 * Nachgebildet wird `ProjectileSystem` + `RayCaster` (Kampf, WP4) – auf der [GameView] statt dem `GameState`:
 * - Flug mit [Ballistics.launch]/[Ballistics.step] (`projectileSubsteps` Teilschritte je Tick, `gravityScale` der Waffe,
 *   aktueller Wind) – bitgleich zur Sim und zu [Ballistics.predict].
 * - Nach jedem Teilschritt Swept-Test des Segments mit `projectileRadius`: Balken als Kapseln ([Geometry.sweptCapsule],
 *   halbe Materialdicke), Geräte als Kreise ([Geometry.sweptCircle], `hitRadius · deviceRayRadiusScale` um
 *   Montage + Normale · `mountOffset`), Gelände (Abtastung alle `terrainSampleStep` m + 6 Bisektionsschritte).
 *   Frühester Treffer gewinnt; Gleichstand: Balken (kleinere ID) vor Gerät vor Gelände. Danach Kartengrenze.
 * - Übersprungen: offene Türen, der eigene Montagebalken samt Geräten darauf solange `Alter < sourceIgnoreTicks`.
 * - Türen in der Zeit: Die Tür, die das Waffen-System beim Schuss automatisch öffnet ([autoOpenDoor]: nächste eigene
 *   Tür im Umkreis `DoorConfig.searchRadius` um den Drehpunkt), gilt als offen, bis sie nach `autoCloseTicks` wieder
 *   zufällt; andere automatisch geöffnete Türen fallen nach ihrem Restzeitgeber zu, fixierte bleiben offen.
 *   Angenommen wird, dass der Schuss im Tick `view.tick` fällt (das Command trägt diesen Tick).
 *
 * Für die KI zusätzlich: eigene **geschlossene** Türen eines Spielers als passierbar werten (die KI öffnet sie vorher,
 * `passDoorsOf`); die erste überquerte steht in [doorCrossed].
 *
 * Allokationsfrei nach dem Aufwärmen. Ablauf: [capture] (Momentaufnahme der Hindernisse aus der View), dann beliebig
 * viele Abfragen ([ballistic], [ray], [cast]); oder alles in einem mit [device]. Nur auf dem Sim-Thread benutzen.
 */
class ShotSweep {
    // ---------------------------------------------------------------------------------------------
    // Ergebnis der letzten Abfrage
    // ---------------------------------------------------------------------------------------------

    var outcome: TrajectoryOutcome = TrajectoryOutcome.CLEAR; private set
    /** [NONE], [BEAM], [DEVICE] oder [TERRAIN]. */
    var hitKind: Int = NONE; private set
    /** Balken- bzw. Geräte-Slot des ersten Treffers (−1 bei Gelände/ohne). */
    var hitId: Int = -1; private set
    /** Besitzer des getroffenen Balkens/Geräts (−1 bei Gelände/ohne). */
    var hitOwner: Int = -1; private set
    /** Trefferpunkt; beim Verlassen der Karte der Austrittspunkt; ohne beides NaN. */
    var hitX: Float = Float.NaN; private set
    var hitY: Float = Float.NaN; private set
    /** Flug-Tick (Alter des Projektils, 0 = erster Flugtick) des Treffers bzw. Austritts, −1 ohne. */
    var hitTick: Int = -1; private set
    /** Die Bahn hat die Kill-Grenzen der Karte verlassen (ohne Treffer). */
    var exitedMap: Boolean = false; private set
    /** Erste überquerte eigene geschlossene Tür (nur mit `passDoorsOf`), sonst −1. */
    var doorCrossed: Int = -1; private set
    /** Anzahl der in den Pfad geschriebenen Punkte (je Flugtick einer, zuletzt Treffer/Austritt). */
    var pathCount: Int = 0; private set
    /** Mündung und Drehpunkt der letzten [device]-Abfrage. */
    var muzzleX: Float = 0f; private set
    var muzzleY: Float = 0f; private set
    var pivotX: Float = 0f; private set
    var pivotY: Float = 0f; private set
    /** Automatisch öffnende Tür der letzten [device]-Abfrage (−1 = keine). */
    var autoDoor: Int = -1; private set
    /**
     * Wie viele Ticks nach [capturedTick] das Ergebnis der letzten Abfrage gegenüber ablaufenden Tür-Zeitgebern gültig
     * bleibt (gleiche Struktur, gleiche Tür-Bits vorausgesetzt): Eine als offen übersprungene Tür mit Restzeitgeber, die
     * das Geschoss kreuzt, wäre ab diesem Versatz beim Durchflug schon zu. [Int.MAX_VALUE] = kein solcher Tür-Einfluss.
     * Die Zielvorschau rechnet damit nur dann neu, wenn ein Zeitgeber das Ergebnis wirklich ändern kann (FX1-Review).
     */
    var stableTicks: Int = Int.MAX_VALUE; private set

    // ---------------------------------------------------------------------------------------------
    // Momentaufnahme
    // ---------------------------------------------------------------------------------------------

    private var v: GameView? = null
    /** Tick der Momentaufnahme. */
    var capturedTick: Long = Long.MIN_VALUE; private set

    private var bCount = 0
    private var bId = IntArray(INITIAL)
    private var bOwner = IntArray(INITIAL)
    private var bDoor = BooleanArray(INITIAL)
    /** Flug-Alter, bis zu dem der Balken offen (passierbar) ist; [CLOSED] = zu, [FOREVER] = bleibt offen. */
    private var bOpen = IntArray(INITIAL)
    /** Wie [bOpen], falls dieser Balken die beim Schuss automatisch öffnende Tür ist. */
    private var bAutoOpen = IntArray(INITIAL)
    private var bAx = FloatArray(INITIAL)
    private var bAy = FloatArray(INITIAL)
    private var bBx = FloatArray(INITIAL)
    private var bBy = FloatArray(INITIAL)
    private var bHalf = FloatArray(INITIAL)
    private var bMinX = FloatArray(INITIAL)
    private var bMinY = FloatArray(INITIAL)
    private var bMaxX = FloatArray(INITIAL)
    private var bMaxY = FloatArray(INITIAL)

    private var dCount = 0
    private var dId = IntArray(INITIAL)
    private var dOwner = IntArray(INITIAL)
    private var dBeam = IntArray(INITIAL)
    private var dX = FloatArray(INITIAL)
    private var dY = FloatArray(INITIAL)
    private var dR = FloatArray(INITIAL)

    // Kandidaten eines Bahnblocks (Indizes in die Momentaufnahme, aufsteigend)
    private var candB = IntArray(INITIAL)
    private var candBN = 0
    private var candD = IntArray(INITIAL)
    private var candDN = 0
    private var useCandidates = false

    private val cp = ClosestParams()
    private val geo = FloatArray(DeviceGeometry.SIZE)
    private val st = FloatArray(4)
    private var pos = FloatArray(2 * (BLOCK_TICKS * 8 + 1))

    /** Siehe `timedDoorsOf` in [ballistic]. */
    private var timedOwner = ALL_PLAYERS

    // Ergebnis des letzten Segmenttests
    private var segT = 2f
    private var segKind = NONE
    private var segId = -1
    private var segOwner = -1
    private var segDoorT = 2f
    private var segDoor = -1

    /** Momentaufnahme aller Hindernisse (lebende Balken, lebende Geräte) und Türzustände aus [view]. */
    fun capture(view: GameView) {
        v = view
        capturedTick = view.tick
        val beams = view.beamView
        val nodes = view.nodeView
        val mats = view.tables.materials
        val autoClose = view.simConfig.door.autoCloseTicks
        bCount = 0
        for (j in 0 until beams.size) {
            if (!beams.isAlive(j)) continue
            val m = beams.material(j)
            if (m < 0 || m >= mats.size) continue
            if (bCount == bId.size) growBeams()
            val k = bCount++
            val a = beams.nodeA(j); val b = beams.nodeB(j)
            val ax = nodes.x(a); val ay = nodes.y(a); val bx = nodes.x(b); val by = nodes.y(b)
            val half = mats[m].thickness * 0.5f
            val f = beams.flags(j)
            bId[k] = j; bOwner[k] = beams.owner(j); bDoor[k] = mats[m].isDoor
            bAx[k] = ax; bAy[k] = ay; bBx[k] = bx; bBy[k] = by; bHalf[k] = half
            bMinX[k] = FloatMath.min(ax, bx) - half; bMaxX[k] = FloatMath.max(ax, bx) + half
            bMinY[k] = FloatMath.min(ay, by) - half; bMaxY[k] = FloatMath.max(ay, by) + half
            val open = (f and BeamFlags.DOOR_OPEN) != 0
            val pinned = (f and BeamFlags.DOOR_PINNED) != 0
            val timer = beams.doorTimer(j)
            // Schuss im Tick T = view.tick: DOORS zählt ab T herunter, die Tür fällt am Ende von Tick T + timer − 1 zu;
            // das Projektil mit Alter a fliegt in Tick T + 1 + a → offen für a ≤ timer − 2.
            bOpen[k] = when {
                !open -> CLOSED
                pinned || timer <= 0 -> FOREVER
                else -> timer - 2
            }
            // Automatisch geöffnet (oder Timer neu gesetzt) im Schuss-Tick T: zu am Ende von T + autoClose → a ≤ autoClose − 1.
            bAutoOpen[k] = if (open && (pinned || timer <= 0)) FOREVER else autoClose - 1
        }
        val d = view.deviceView
        val props = view.tables.devices
        val scale = view.simConfig.combat.deviceRayRadiusScale
        dCount = 0
        for (i in 0 until d.size) {
            if (!d.isAlive(i)) continue
            val ty = d.type(i)
            if (ty < 0 || ty >= props.size) continue
            if (dCount == dId.size) growDevices()
            val k = dCount++
            val p = props[ty]
            val b = d.beam(i)
            dId[k] = i; dOwner[k] = d.owner(i); dBeam[k] = b
            // Trefferzentrum wie das DEVICES-System es im Flugtick setzt (Montage + Normale · mountOffset); aus den Knoten
            // gerechnet, weil die DERIVED-Felder vor dem ersten Tick noch leer sind
            if (b >= 0 && b < view.beamView.size && view.beamView.isAlive(b)) {
                DeviceGeometry.mount(view, i, geo)
                dX[k] = geo[DeviceGeometry.CX]; dY[k] = geo[DeviceGeometry.CY]
            } else {
                dX[k] = d.x(i) + d.nx(i) * p.mountOffset
                dY[k] = d.y(i) + d.ny(i) * p.mountOffset
            }
            dR[k] = p.hitRadius * scale
        }
        if (candB.size < bCount) candB = IntArray(bId.size)
        if (candD.size < dCount) candD = IntArray(dId.size)
    }

    /** Ist die Momentaufnahme für [view] im aktuellen Tick schon gemacht? */
    fun isCaptured(view: GameView): Boolean = v === view && capturedTick == view.tick

    /** Mittelpunkt und Trefferradius von Gerät [deviceId] aus der Momentaufnahme in [out] (x, y, r); `false`, wenn nicht vorhanden. */
    fun deviceCircle(deviceId: Int, out: FloatArray): Boolean {
        for (k in 0 until dCount) if (dId[k] == deviceId) {
            out[0] = dX[k]; out[1] = dY[k]; out[2] = dR[k]
            return true
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // Abfragen
    // ---------------------------------------------------------------------------------------------

    /**
     * Komplettprüfung für Waffe [deviceId] bei Winkel [angle] und Kraft [power]: Momentaufnahme, Mündung (wie die Sim,
     * über die Montage beim Winkel), automatisch öffnende Tür und Bahn bzw. Strahl. Ballistisch wird die **ganze
     * Lebensdauer** geprüft ([lifetimeTicks]: `ttlTicks`, ohne ttl bis zur Kartengrenze mit Sicherheitsobergrenze) – auch
     * späte Eigentreffer (Brandrakete 16 s) werden gefunden. Die Bahn je Flugtick geht nach [path] (falls nicht null,
     * so viele Punkte, wie Platz ist; die Prüfung läuft unabhängig davon weiter).
     * Hitscan/Strahl: gerade Linie über `maxRange` (ohne Streuung).
     * @param maxTicks > 0: Prüfung zusätzlich auf so viele Flugticks begrenzen (Tests); 0 = volle Lebensdauer.
     * @return Ergebnis, oder [TrajectoryOutcome.CLEAR], wenn das Gerät keine Waffe ist (dann [hitKind] = [NONE]).
     */
    fun device(
        view: GameView, deviceId: Int, angle: Float, power: Float, path: FloatArray? = null, maxTicks: Int = 0,
        passDoorsOf: Int = -1,
    ): TrajectoryOutcome {
        reset()
        val devices = view.deviceView
        if (!devices.isAlive(deviceId)) return outcome
        val type = devices.type(deviceId)
        if (type < 0 || type >= view.tables.devices.size) return outcome
        val props = view.tables.devices[type]
        if (props.weapon < 0 || props.weapon >= view.tables.weapons.size) return outcome
        val weapon = view.tables.weapons[props.weapon]
        val beam = devices.beam(deviceId)
        if (beam < 0 || !view.beamView.isAlive(beam)) return outcome
        capture(view)
        val side = (devices.flags(deviceId) and DeviceFlags.SIDE_NEG) != 0
        RuleChecks.mountOn(view, beam, devices.t(deviceId), side, props, angle, geo)
        val mx = geo[DeviceGeometry.MUZZLE_X]; val my = geo[DeviceGeometry.MUZZLE_Y]
        val px = geo[DeviceGeometry.PIVOT_X]; val py = geo[DeviceGeometry.PIVOT_Y]
        val owner = devices.owner(deviceId)
        val door = autoOpenDoor(view, owner, px, py)
        val result = if (weapon.mode == WeaponMode.BALLISTIC) {
            val life = lifetimeTicks(weapon)
            ballistic(owner, beam, door, mx, my, angle, power, weapon, view.wind, if (maxTicks in 1 until life) maxTicks else life, passDoorsOf, path)
        } else {
            val ex = mx + FastTrig.cos(angle) * weapon.maxRange
            val ey = my - FastTrig.sin(angle) * weapon.maxRange
            ray(owner, beam, door, mx, my, ex, ey, weapon.projectileRadius, passDoorsOf)
        }
        muzzleX = mx; muzzleY = my; pivotX = px; pivotY = py; autoDoor = door
        return result
    }

    /**
     * Ballistischer Schuss von Spieler [owner] ab Mündung ([x0], [y0]) – genau wie `ProjectileSystem` (siehe Klasse).
     * Vorher [capture] aufrufen.
     * @param mountBeam Montagebalken der Waffe (−1 = keiner): samt Geräten darauf übersprungen, solange das Alter
     *   kleiner `sourceIgnoreTicks` ist.
     * @param autoDoor Tür, die sich beim Schuss automatisch öffnet ([autoOpenDoor]), −1 = keine.
     * @param maxTicks Lebensdauer in Flugticks ([lifetimeTicks] der Waffe). Unabhängig von der Größe von [path].
     * @param path optional: Punkte (x, y) je Flugtick, zuletzt Treffer bzw. Austritt ([pathCount]); ist er voll, werden
     *   keine weiteren Punkte geschrieben, die Prüfung läuft aber bis [maxTicks] weiter.
     * @param timedDoorsOf nur die offenen Türen dieses Spielers fallen nach ihrem Restzeitgeber zu; offene Türen anderer
     *   Spieler gelten als offen bleibend (KI: der Gegner öffnet sie im Gefecht mit jedem Schuss neu). [ALL_PLAYERS]
     *   (Standard, Zielvorschau) = alle Türen genau wie die Sim ohne weitere Eingaben.
     */
    fun ballistic(
        owner: Int, mountBeam: Int, autoDoor: Int, x0: Float, y0: Float, angle: Float, power: Float, weapon: WeaponProps,
        wind: Float, maxTicks: Int, passDoorsOf: Int = -1, path: FloatArray? = null, timedDoorsOf: Int = ALL_PLAYERS,
    ): TrajectoryOutcome {
        reset()
        timedOwner = timedDoorsOf
        val view = v ?: return outcome
        val cfg = view.simConfig
        val map = view.map
        val subs = cfg.projectileSubsteps
        val h = cfg.dt / subs
        val ignoreTicks = cfg.combat.sourceIgnoreTicks
        val radius = weapon.projectileRadius
        val gs = weapon.gravityScale
        val limit = maxTicks
        val need = 2 * (BLOCK_TICKS * subs + 1)
        if (pos.size < need) pos = FloatArray(need)
        val ps = pos
        Ballistics.launch(x0, y0, angle, power, weapon.muzzleSpeed, st)
        var k = 0
        while (k < limit) {
            val bt = if (limit - k < BLOCK_TICKS) limit - k else BLOCK_TICKS
            // Block vorausrechnen (gleiche Teilschritte wie die Sim), dann nur Hindernisse in seiner Box testen
            ps[0] = st[Ballistics.X]; ps[1] = st[Ballistics.Y]
            var minX = ps[0]; var maxX = ps[0]; var minY = ps[1]; var maxY = ps[1]
            var m = 0
            for (q in 0 until bt) for (s in 0 until subs) {
                Ballistics.step(st, h, wind, cfg, gs)
                m++
                val x = st[Ballistics.X]; val y = st[Ballistics.Y]
                ps[m * 2] = x; ps[m * 2 + 1] = y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            gather(minX - radius, minY - radius, maxX + radius, maxY + radius)
            for (q in 0 until bt) {
                val age = k + q
                val ign = if (age < ignoreTicks) mountBeam else -1
                for (s in 0 until subs) {
                    val idx = q * subs + s
                    val ax = ps[idx * 2]; val ay = ps[idx * 2 + 1]
                    val bx = ps[idx * 2 + 2]; val by = ps[idx * 2 + 3]
                    if (segment(ax, ay, bx, by, radius, ign, -1, ign, true, age, autoDoor, passDoorsOf)) {
                        finishHit(ax, ay, bx, by, owner, age)
                        append(path, hitX, hitY)
                        useCandidates = false
                        return outcome
                    }
                    if (map.isOutOfBounds(bx, by)) {
                        exitedMap = true
                        hitTick = age
                        hitX = bx; hitY = by
                        append(path, bx, by)
                        useCandidates = false
                        return outcome
                    }
                }
                val e = (q + 1) * subs
                append(path, ps[e * 2], ps[e * 2 + 1])
            }
            k += bt
        }
        useCandidates = false
        return outcome
    }

    /**
     * Gerader Strahl (Hitscan, Laser) im Schuss-Tick von ([x0], [y0]) nach ([x1], [y1]) wie `WeaponSystem`: Montagebalken
     * samt Geräten übersprungen, Gelände geprüft, automatisch öffnende Tür offen. Vorher [capture] aufrufen.
     */
    fun ray(
        owner: Int, mountBeam: Int, autoDoor: Int, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float,
        passDoorsOf: Int = -1, ignoreBeam2: Int = -1,
    ): TrajectoryOutcome {
        reset()
        if (v == null) return outcome
        if (segment(x0, y0, x1, y1, radius, mountBeam, ignoreBeam2, mountBeam, true, NOW, autoDoor, passDoorsOf)) {
            finishHit(x0, y0, x1, y1, owner, 0)
        }
        return outcome
    }

    /**
     * Einzelner Segmenttest (frühester Treffer) gegen die Momentaufnahme, für eigene Prüfungen der KI. Türzustand "jetzt".
     * @return `true` bei Treffer; Ergebnis in [hitKind], [hitId], [hitOwner], [hitX], [hitY], [doorCrossed]
     *   ([outcome] aus Sicht von Spieler [owner]).
     */
    fun cast(
        x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, ignoreBeam: Int, ignoreBeam2: Int,
        ignoreDevicesOnBeam: Int, withTerrain: Boolean, autoDoor: Int = -1, passDoorsOf: Int = -1, owner: Int = -1,
    ): Boolean {
        reset()
        if (v == null) return false
        if (!segment(x0, y0, x1, y1, radius, ignoreBeam, ignoreBeam2, ignoreDevicesOnBeam, withTerrain, NOW, autoDoor, passDoorsOf)) {
            return false
        }
        finishHit(x0, y0, x1, y1, owner, 0)
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Intern
    // ---------------------------------------------------------------------------------------------

    private fun reset() {
        timedOwner = ALL_PLAYERS
        stableTicks = Int.MAX_VALUE
        outcome = TrajectoryOutcome.CLEAR
        hitKind = NONE; hitId = -1; hitOwner = -1; hitX = Float.NaN; hitY = Float.NaN; hitTick = -1
        exitedMap = false; doorCrossed = -1; pathCount = 0
        useCandidates = false
    }

    private fun append(path: FloatArray?, x: Float, y: Float) {
        if (path == null || pathCount * 2 + 1 >= path.size) return
        path[pathCount * 2] = x; path[pathCount * 2 + 1] = y
        pathCount++
    }

    private fun finishHit(x0: Float, y0: Float, x1: Float, y1: Float, owner: Int, age: Int) {
        hitKind = segKind; hitId = segId; hitOwner = segOwner; hitTick = age
        hitX = x0 + (x1 - x0) * segT
        hitY = y0 + (y1 - y0) * segT
        outcome = when (segKind) {
            TERRAIN -> TrajectoryOutcome.TERRAIN
            BEAM, DEVICE -> if (owner >= 0 && segOwner == owner) TrajectoryOutcome.BLOCKED_OWN else TrajectoryOutcome.HIT_ENEMY
            else -> TrajectoryOutcome.CLEAR
        }
    }

    /** Kandidaten (Balken/Geräte), deren Box die Box ([x0], [y0])–([x1], [y1]) schneidet; in aufsteigender Reihenfolge. */
    private fun gather(x0: Float, y0: Float, x1: Float, y1: Float) {
        var n = 0
        for (k in 0 until bCount) {
            if (bMaxX[k] < x0 || bMinX[k] > x1 || bMaxY[k] < y0 || bMinY[k] > y1) continue
            candB[n++] = k
        }
        candBN = n
        n = 0
        for (k in 0 until dCount) {
            val r = dR[k]
            if (dX[k] + r < x0 || dX[k] - r > x1 || dY[k] + r < y0 || dY[k] - r > y1) continue
            candD[n++] = k
        }
        candDN = n
        useCandidates = true
    }

    /**
     * Swept-Test eines Segments – dieselben Regeln wie `RayCaster.cast` (frühester Treffer; Balken: kleinere ID bei
     * Gleichstand; Gerät nur bei echt kleinerem t; dann Gelände). [age] = Flug-Alter für den Türzustand ([NOW] = jetzt).
     */
    private fun segment(
        x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, ignoreA: Int, ignoreB: Int, ignoreDevBeam: Int,
        withTerrain: Boolean, age: Int, autoDoor: Int, passDoorsOf: Int,
    ): Boolean {
        segT = 2f; segKind = NONE; segId = -1; segOwner = -1; segDoorT = 2f; segDoor = -1
        val minX = FloatMath.min(x0, x1) - radius; val maxX = FloatMath.max(x0, x1) + radius
        val minY = FloatMath.min(y0, y1) - radius; val maxY = FloatMath.max(y0, y1) + radius
        var bestT = 2f
        var bestId = -1
        var bestK = -1
        val nb = if (useCandidates) candBN else bCount
        for (c in 0 until nb) {
            val k = if (useCandidates) candB[c] else c
            if (bMaxX[k] < minX || bMinX[k] > maxX || bMaxY[k] < minY || bMinY[k] > maxY) continue
            val id = bId[k]
            if (id == ignoreA || id == ignoreB) continue
            val openUntil = when {
                id == autoDoor -> bAutoOpen[k]
                timedOwner != ALL_PLAYERS && bOpen[k] != CLOSED && bOwner[k] != timedOwner -> FOREVER
                else -> bOpen[k]
            }
            if (openUntil != CLOSED && age <= openUntil) {
                // Offen mit Restzeitgeber (nicht die automatische Tür, deren Zustand beim Schuss feststeht): Je Tick
                // später gefeuert schrumpft openUntil um 1 – ab Versatz openUntil − age + 1 wäre sie hier zu.
                if (id != autoDoor && openUntil != FOREVER) {
                    val flip = openUntil - age + 1
                    if (flip < stableTicks &&
                        Geometry.sweptCapsule(x0, y0, x1, y1, radius, bAx[k], bAy[k], bBx[k], bBy[k], bHalf[k], cp) >= 0f
                    ) stableTicks = flip
                }
                continue
            }
            val t = Geometry.sweptCapsule(x0, y0, x1, y1, radius, bAx[k], bAy[k], bBx[k], bBy[k], bHalf[k], cp)
            if (t < 0f) continue
            // eigene, jetzt geschlossene Tür: die KI öffnet sie vor dem Schuss
            if (passDoorsOf >= 0 && bDoor[k] && bOwner[k] == passDoorsOf && bOpen[k] == CLOSED && id != autoDoor) {
                if (t < segDoorT) { segDoorT = t; segDoor = id }
                continue
            }
            if (t < bestT || (t == bestT && id < bestId)) { bestT = t; bestId = id; bestK = k }
        }
        if (bestK >= 0 && bestT <= 1f) {
            segKind = BEAM; segId = bestId; segOwner = bOwner[bestK]; segT = bestT
        }
        val nd = if (useCandidates) candDN else dCount
        for (c in 0 until nd) {
            val k = if (useCandidates) candD[c] else c
            if (ignoreDevBeam >= 0 && dBeam[k] == ignoreDevBeam) continue
            val r = dR[k]
            // minX … maxY enthalten den Projektilradius bereits: Box um das Segment, vergrößert um r + radius
            if (dX[k] + r < minX || dX[k] - r > maxX || dY[k] + r < minY || dY[k] - r > maxY) continue
            val t = Geometry.sweptCircle(x0, y0, x1, y1, radius, dX[k], dY[k], r)
            if (t < 0f) continue
            if (t < segT) { segKind = DEVICE; segId = dId[k]; segOwner = dOwner[k]; segT = t }
        }
        if (withTerrain) {
            val tt = terrainHit(x0, y0, x1, y1)
            if (tt >= 0f && tt < segT) { segKind = TERRAIN; segId = -1; segOwner = -1; segT = tt }
        }
        // erste überquerte (als passierbar gewertete) eigene Tür vor dem Treffer bzw. auf dem freien Segment
        if (segDoor >= 0 && segDoorT <= segT && doorCrossed < 0) doorCrossed = segDoor
        return segKind != NONE
    }

    /** Segment gegen Gelände wie `RayCaster.terrainHit`: Abtastung je `terrainSampleStep` m, dann 6 Bisektionsschritte. */
    private fun terrainHit(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val view = v ?: return -1f
        val terrain = view.terrain
        if (y0 > terrain.heightAt(x0)) return 0f
        val dx = x1 - x0; val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        val nf = len / view.simConfig.combat.terrainSampleStep
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

    private fun growBeams() {
        val n = bId.size * 2
        bId = bId.copyOf(n); bOwner = bOwner.copyOf(n); bDoor = bDoor.copyOf(n); bOpen = bOpen.copyOf(n); bAutoOpen = bAutoOpen.copyOf(n)
        bAx = bAx.copyOf(n); bAy = bAy.copyOf(n); bBx = bBx.copyOf(n); bBy = bBy.copyOf(n); bHalf = bHalf.copyOf(n)
        bMinX = bMinX.copyOf(n); bMinY = bMinY.copyOf(n); bMaxX = bMaxX.copyOf(n); bMaxY = bMaxY.copyOf(n)
    }

    private fun growDevices() {
        val n = dId.size * 2
        dId = dId.copyOf(n); dOwner = dOwner.copyOf(n); dBeam = dBeam.copyOf(n)
        dX = dX.copyOf(n); dY = dY.copyOf(n); dR = dR.copyOf(n)
    }

    companion object {
        const val NONE: Int = 0
        const val BEAM: Int = 1
        const val DEVICE: Int = 2
        const val TERRAIN: Int = 3

        /** `timedDoorsOf` in [ballistic]: alle Türen fallen nach ihrem Zeitgeber zu (wie die Sim ohne weitere Eingaben). */
        const val ALL_PLAYERS: Int = -2

        /**
         * Sicherheitsobergrenze der Flugticks für ballistische Waffen **ohne** `ttlTicks` (60 s; die Sim lässt sie bis zur
         * Kartengrenze fliegen). Länger als jede Lebensdauer im Content (Brandrakete 16 s = 960 Ticks).
         */
        const val DEFAULT_MAX_TICKS: Int = 3600

        /** Grund-Schlüssel (Ressourcenname) für die Zielkarte, wenn [TrajectoryOutcome.BLOCKED_OWN]: "eigene Festung im Weg". */
        const val REASON_BLOCKED_OWN: String = "aim_blocked_own_fort"

        private const val BLOCK_TICKS = 8
        private const val INITIAL = 128
        private const val CLOSED = Int.MIN_VALUE
        private const val FOREVER = Int.MAX_VALUE
        /** Flug-Alter "jetzt" (Strahlwaffen im Schuss-Tick, KI-Sichtprüfungen). */
        private const val NOW = -1

        /** Lebensdauer in Flugticks: `ttlTicks` der Waffe, höchstens [maxPoints]. */
        fun maxTicksFor(weapon: WeaponProps, maxPoints: Int): Int =
            if (weapon.ttlTicks in 1 until maxPoints) weapon.ttlTicks else maxPoints

        /**
         * Prüfhorizont eines ballistischen Schusses in Flugticks – genau die Lebensdauer wie in der Sim (`ttlTicks`);
         * ohne ttl (die Sim fliegt bis zur Kartengrenze) [DEFAULT_MAX_TICKS]. Nie durch die Zahl der Anzeige-Punkte begrenzt.
         */
        fun lifetimeTicks(weapon: WeaponProps): Int = if (weapon.ttlTicks > 0) weapon.ttlTicks else DEFAULT_MAX_TICKS

        /**
         * Die Tür, die `WeaponSystem` beim Schuss automatisch öffnet (Prototyp `doorFor`): nächste eigene Tür (kein
         * Trümmer) im Umkreis `DoorConfig.searchRadius` um den Drehpunkt ([px], [py]); −1 = keine.
         */
        fun autoOpenDoor(view: GameView, owner: Int, px: Float, py: Float): Int {
            val beams = view.beamView
            val nodes = view.nodeView
            val mats = view.tables.materials
            val r = view.simConfig.door.searchRadius
            var best = -1
            var bd = r * r
            for (j in 0 until beams.size) {
                if (!beams.isAlive(j) || beams.owner(j) != owner) continue
                if ((beams.flags(j) and BeamFlags.DEBRIS) != 0) continue
                val m = beams.material(j)
                if (m < 0 || m >= mats.size || !mats[m].isDoor) continue
                val a = beams.nodeA(j); val b = beams.nodeB(j)
                val d2 = Geometry.pointSegmentDistSq(px, py, nodes.x(a), nodes.y(a), nodes.x(b), nodes.y(b))
                if (d2 < bd) { bd = d2; best = j }
            }
            return best
        }
    }
}
