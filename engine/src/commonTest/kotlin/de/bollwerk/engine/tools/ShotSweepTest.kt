package de.bollwerk.engine.tools

import de.bollwerk.engine.combat.CombatRig
import de.bollwerk.engine.combat.CombatTables
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * FX1: Die Bahnprüfung der Zielvorschau ([ShotSweep]) muss denselben ersten Treffer finden wie die Simulation
 * (`ProjectileSystem`). Testfestung wie die Startfestung der Schlucht: Kanone im Erdgeschoss, Mörser im ersten Stock,
 * Panzerdach darüber, Türen in der Außenwand; gegnerische Wand und Zielgerät weiter rechts.
 */
class ShotSweepTest {
    private val deg = FloatMath.DEG_TO_RAD

    private class Bay(val rig: CombatRig, val mortar: Int, val cannon: Int, val roof: Int, val lowerDoor: Int, val upperDoor: Int,
                      val enemyWall: Int, val enemyTarget: Int)

    /**
     * Erdgeschoss y 34..31, erster Stock 31..28, Dach (Panzer) bei y 28 über x 6..12, Türen bei x = 12.
     * [anchoredAll]: alle Knoten verankert (ohne Physik ohnehin ruhend).
     */
    private fun bay(physics: Boolean = false, anchoredAll: Boolean = !physics): Bay {
        val rig = if (physics) CombatRig() else CombatRig(slots = CombatRig.COMBAT_ONLY)
        val a = anchoredAll
        val f = IntArray(5) { rig.node(it * 3f, 34f, true) }
        val m = IntArray(5) { rig.node(it * 3f, 31f, a) }
        val r2 = rig.node(6f, 28f, a); val r3 = rig.node(9f, 28f, a); val r4 = rig.node(12f, 28f, a)
        val floor = IntArray(4) { rig.beam(f[it], f[it + 1], CombatTables.METAL) }
        for (i in 0 until 4) rig.beam(f[i], m[i], CombatTables.METAL)
        for (i in 0 until 3) rig.beam(f[i], m[i + 1], CombatTables.WOOD)
        val mid = IntArray(4) { rig.beam(m[it], m[it + 1], CombatTables.METAL) }
        val lowerDoor = rig.beam(f[4], m[4], CombatTables.DOOR)
        rig.beam(m[2], r2, CombatTables.METAL)
        rig.beam(m[3], r3, CombatTables.METAL)
        rig.beam(m[2], r3, CombatTables.WOOD)
        rig.beam(r2, r3, CombatTables.ARMOUR)
        val roof = rig.beam(r3, r4, CombatTables.ARMOUR)
        val upperDoor = rig.beam(m[4], r4, CombatTables.DOOR)
        val cannon = rig.device(CombatTables.CANNON, floor[3], 0.3f, aim = 13f * deg, power = 1f)
        val mortar = rig.device(CombatTables.MORTAR, mid[3], 0.72f, aim = 52f * deg, power = 0.78f)
        rig.device(CombatTables.REACTOR, floor[1], 0.5f)
        val enemyWall = rig.beamAt(72f, 18f, 72f, 34f, CombatTables.METAL, owner = 1)
        val plat = rig.beamAt(55f, 33f, 58f, 33f, CombatTables.METAL, owner = 1)
        val target = rig.device(CombatTables.TARGET, plat, 0.5f, owner = 1)
        return Bay(rig, mortar, cannon, roof, lowerDoor, upperDoor, enemyWall, target)
    }

    /** Erster Treffer der echten Simulation. */
    private data class SimHit(val outcome: TrajectoryOutcome, val beamUid: Int, val x: Float, val y: Float)

    private fun simulate(rig: CombatRig, dev: Int, maxTicks: Int): SimHit {
        val before = rig.fx.size
        rig.fire(dev)
        rig.tick()
        val p = rig.state.projectiles
        var slot = -1
        for (i in 0 until p.size) if (p.isAlive(i)) slot = i
        if (slot < 0) fail("kein Projektil")
        var n = 0
        while (p.isAlive(slot) && n < maxTicks + 5) { rig.tick(); n++ }
        val ex = rig.fx.subList(before, rig.fx.size).filterIsInstance<FxEvent.Explosion>().firstOrNull { it.weaponId >= 0 }
            ?: return SimHit(TrajectoryOutcome.CLEAR, -1, Float.NaN, Float.NaN)
        val owner = rig.state.devices.ownerOf[dev]
        val outcome = when {
            ex.hitMaterialId == -1 -> TrajectoryOutcome.TERRAIN
            ex.hitBeamUid >= 0 -> if (beamOwnerByUid(rig, ex.hitBeamUid) == owner) TrajectoryOutcome.BLOCKED_OWN else TrajectoryOutcome.HIT_ENEMY
            else -> if (deviceOwnerAt(rig, ex.x, ex.y) == owner) TrajectoryOutcome.BLOCKED_OWN else TrajectoryOutcome.HIT_ENEMY
        }
        return SimHit(outcome, ex.hitBeamUid, ex.x, ex.y)
    }

    private fun beamOwnerByUid(rig: CombatRig, uid: Int): Int {
        val b = rig.state.beams
        for (j in 0 until b.size) if (b.uidOf[j] == uid) return b.ownerOf[j]
        return -1
    }

    /** Besitzer des Geräts mit dem nächsten Trefferzentrum. */
    private fun deviceOwnerAt(rig: CombatRig, x: Float, y: Float): Int {
        val d = rig.state.devices
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in 0 until d.size) {
            val off = rig.state.tables.devices[d.typeOf[i]].mountOffset
            val dx = d.x[i] + d.nx[i] * off - x; val dy = d.y[i] + d.ny[i] * off - y
            val dd = dx * dx + dy * dy
            if (dd < bd) { bd = dd; best = i }
        }
        return if (best >= 0) d.ownerOf[best] else -1
    }

    private class Predicted(val outcome: TrajectoryOutcome, val beamUid: Int, val x: Float, val y: Float)

    private fun predict(rig: CombatRig, dev: Int): Predicted {
        val s = ShotSweep()
        val d = rig.state.devices
        val o = s.device(rig.state, dev, d.aimAngle[dev], d.power[dev])
        val uid = if (s.hitKind == ShotSweep.BEAM) rig.state.beams.uidOf[s.hitId] else -1
        return Predicted(o, uid, s.hitX, s.hitY)
    }

    private fun aimAndCompare(physics: Boolean, weapon: Int, angleDeg: Float, power: Float, wind: Float): Pair<Predicted, SimHit> {
        val b = bay(physics)
        val rig = b.rig
        if (physics) rig.run(90) else rig.tick()
        val dev = if (weapon == CombatTables.MORTAR) b.mortar else b.cannon
        rig.state.devices.aimAngle[dev] = angleDeg * deg
        rig.state.devices.power[dev] = power
        rig.state.wind = wind
        val pre = predict(rig, dev)
        val w = rig.state.tables.weapons[rig.state.tables.devices[rig.state.devices.typeOf[dev]].weapon]
        val sim = simulate(rig, dev, ShotSweep.maxTicksFor(w, ShotSweep.DEFAULT_MAX_TICKS))
        return pre to sim
    }

    // ---- Eigentreffer erkennen ----

    @Test
    fun steepMortarAndRaisedCannonAreBlockedByTheOwnFort() {
        for ((weapon, angle) in listOf(CombatTables.MORTAR to 80f, CombatTables.MORTAR to 85f, CombatTables.CANNON to 45f, CombatTables.CANNON to 60f)) {
            val (pre, sim) = aimAndCompare(false, weapon, angle, 1f, 0f)
            assertEquals(TrajectoryOutcome.BLOCKED_OWN, pre.outcome, "weapon $weapon at $angle°")
            assertEquals(TrajectoryOutcome.BLOCKED_OWN, sim.outcome, "sim: weapon $weapon at $angle°")
        }
    }

    @Test
    fun normalAimsAreClearThroughTheAutoOpeningDoor() {
        for ((weapon, angle) in listOf(CombatTables.MORTAR to 52f, CombatTables.MORTAR to 40f, CombatTables.CANNON to 5f, CombatTables.CANNON to 13f)) {
            val (pre, sim) = aimAndCompare(false, weapon, angle, 0.8f, 0f)
            assertTrue(pre.outcome == TrajectoryOutcome.TERRAIN || pre.outcome == TrajectoryOutcome.HIT_ENEMY, "weapon $weapon at $angle°: ${pre.outcome}")
            assertEquals(sim.outcome, pre.outcome)
        }
    }

    @Test
    fun theDoorThatOpensOnFireCountsAsOpenOtherClosedDoorsBlock() {
        val b = bay()
        b.rig.tick()
        val s = ShotSweep()
        // Kanone flach durch die untere Tür (öffnet automatisch)
        val flat = s.device(b.rig.state, b.cannon, 0f, 1f)
        assertTrue(flat == TrajectoryOutcome.TERRAIN || flat == TrajectoryOutcome.HIT_ENEMY, "$flat")
        assertEquals(b.lowerDoor, s.autoDoor)
        // die obere Tür ist für den Mörser die automatische, für die Kanone nicht: Kanone steil durch die obere Tür → blockiert
        assertEquals(b.upperDoor, s.device(b.rig.state, b.mortar, 52f * deg, 0.78f).let { s.autoDoor })
        // Kanonenschuss, der die obere Tür kreuzen würde, wird von ihr gestoppt (sie öffnet sich nicht für die Kanone)
        var blockedByUpperDoor = false
        for (a in 20..40) {
            s.device(b.rig.state, b.cannon, a * deg, 1f)
            if (s.outcome == TrajectoryOutcome.BLOCKED_OWN && s.hitId == b.upperDoor) blockedByUpperDoor = true
        }
        assertTrue(blockedByUpperDoor, "obere Tür blockiert die Kanone")
        // fixiert offene obere Tür: dieselben Winkel kommen durch die Tür
        b.rig.state.beams.flags[b.upperDoor] = b.rig.state.beams.flags[b.upperDoor] or BeamFlags.DOOR_OPEN or BeamFlags.DOOR_PINNED
        for (a in 20..40) {
            s.device(b.rig.state, b.cannon, a * deg, 1f)
            assertTrue(s.hitId != b.upperDoor || s.hitKind != ShotSweep.BEAM, "offene Tür bei $a°")
        }
    }

    /** Kanone flach auf eine eigene Tür bei x = 30 (nicht die automatische), gegnerische Wand bei x = 50. */
    private class DoorLane(val rig: CombatRig, val cannon: Int, val door: Int)

    private fun doorLane(): DoorLane {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val dev = rig.weaponOnPlatform(CombatTables.CANNON, 10f, 30f, 0f)
        val door = rig.beamAt(30f, 24f, 30f, 32f, CombatTables.DOOR)
        rig.beamAt(50f, 20f, 50f, 33f, CombatTables.METAL, owner = 1)
        rig.tick()
        return DoorLane(rig, dev, door)
    }

    @Test
    fun anOpenDoorWhoseTimerRunsOutBeforeTheShellArrivesBlocks() {
        // Kreuzungs-Alter aus einem ersten Lauf mit geschlossener Tür bestimmen
        val probe = doorLane()
        val s = ShotSweep()
        assertEquals(TrajectoryOutcome.BLOCKED_OWN, s.device(probe.rig.state, probe.cannon, 0f, 1f))
        assertEquals(probe.door, s.hitId)
        val crossing = s.hitTick
        assertTrue(crossing in 5..60, "crossing age $crossing")
        // Zeitgeber genau um die Grenze (offen für Alter ≤ timer − 2): Vorschau == Sim, und beide Ergebnisse kommen vor
        val seen = HashSet<TrajectoryOutcome>()
        for (timer in listOf(2, 3, crossing - 1, crossing, crossing + 1, crossing + 2, crossing + 3, crossing + 4, 400)) {
            val lane = doorLane()
            val rig = lane.rig
            rig.state.beams.flags[lane.door] = rig.state.beams.flags[lane.door] or BeamFlags.DOOR_OPEN
            rig.state.beams.doorTimerTicks[lane.door] = timer
            val pre = predict(rig, lane.cannon)
            val sim = simulate(rig, lane.cannon, 800)
            assertEquals(sim.outcome, pre.outcome, "timer $timer (crossing $crossing)")
            assertEquals(sim.beamUid, pre.beamUid, "timer $timer")
            assertEquals(sim.x, pre.x, "timer $timer")
            assertEquals(sim.y, pre.y, "timer $timer")
            if (timer in crossing - 1..crossing + 4) seen += pre.outcome
        }
        assertTrue(TrajectoryOutcome.BLOCKED_OWN in seen && TrajectoryOutcome.HIT_ENEMY in seen, "boundary exercised: $seen")
    }

    @Test
    fun theAutoOpenedDoorClosesJustBeforeASlowShellComesBack() {
        // Mörser unter einer langen waagerechten eigenen Tür (die automatische): steil und schwach durch die Tür hinauf,
        // nach ~2,6 s zurück – je nach Kraft ist die Tür (zu bei Alter ≥ autoCloseTicks) noch offen oder schon zu
        val autoClose = CombatRig().state.config.door.autoCloseTicks
        var blockedAtBoundary = 0
        var passed = 0
        var n = 0
        var power = 0.30f
        while (power <= 0.46f) {
            val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
            val dev = rig.weaponOnPlatform(CombatTables.MORTAR, 40f, 30f, 82f * deg, power = power)
            val door = rig.beamAt(36f, 27.2f, 60f, 27.2f, CombatTables.DOOR)
            rig.tick()
            val s = ShotSweep()
            val o = s.device(rig.state, dev, 82f * deg, power)
            assertEquals(door, s.autoDoor)
            val pre = predict(rig, dev)
            val sim = simulate(rig, dev, 800)
            val tag = "power $power: pre ${pre.outcome} at tick ${s.hitTick}, sim ${sim.outcome}"
            assertEquals(sim.outcome, pre.outcome, tag)
            assertEquals(sim.beamUid, pre.beamUid, tag)
            assertEquals(sim.x, pre.x, tag)
            assertEquals(sim.y, pre.y, tag)
            if (o == TrajectoryOutcome.BLOCKED_OWN && s.hitId == door && s.hitTick in autoClose..autoClose + 3) blockedAtBoundary++
            if (o == TrajectoryOutcome.TERRAIN) passed++
            n++
            power += 0.004f
        }
        assertTrue(n > 30)
        assertTrue(blockedAtBoundary > 0, "a shell hits the door right after it closed")
        assertTrue(passed > 0, "a faster shell passes the still open door")
    }

    // ---- späte Treffer: Prüfung über die ganze Lebensdauer ----

    @Test
    fun aRocketFallingBackOntoTheOwnFortAfterTenSecondsIsBlocked() {
        // Review-Fall: Brandrakete (16 s) steil in starken Gegenwind, fällt nach > 600 Ticks auf den eigenen Boden
        var late = 0
        for (a in 60..86 step 2) {
            val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
            val dev = rig.weaponOnPlatform(CombatTables.ROCKET, 40f, 30f, a * deg, power = 1f)
            rig.beamAt(-30f, 33f, 110f, 33f, CombatTables.METAL)
            rig.tick()
            rig.state.wind = -8f
            val s = ShotSweep()
            val o = s.device(rig.state, dev, a * deg, 1f)
            val pre = predict(rig, dev)
            val sim = simulate(rig, dev, 1000)
            val tag = "rocket $a°: pre $o at tick ${s.hitTick}, sim ${sim.outcome}"
            assertEquals(sim.outcome, pre.outcome, tag)
            if (sim.outcome != TrajectoryOutcome.CLEAR) {
                assertEquals(sim.beamUid, pre.beamUid, tag)
                assertEquals(sim.x, pre.x, tag)
                assertEquals(sim.y, pre.y, tag)
            }
            val preview = AimPreviewer().preview(rig.state, dev, a * deg, 1f)!!
            assertEquals(o, preview.outcome, tag)
            if (o == TrajectoryOutcome.BLOCKED_OWN && s.hitTick >= ToolConst.TRAJECTORY_MAX_POINTS) {
                late++
                assertTrue(preview.hasImpact, tag)
                assertEquals(ShotSweep.REASON_BLOCKED_OWN, preview.blockedReasonKey)
                val tr = preview.trajectory
                assertTrue(tr.count > ToolConst.TRAJECTORY_MAX_POINTS, "$tag: ${tr.count} points")
                assertEquals(s.hitX, tr.x(tr.count - 1), tag)
                assertEquals(s.hitY, tr.y(tr.count - 1), tag)
                assertEquals(s.hitX, preview.impactX)
            }
        }
        assertTrue(late > 0, "at least one late self-hit (> ${ToolConst.TRAJECTORY_MAX_POINTS} ticks)")
    }

    // ---- Hitscan und Laser: Vorschau == Simulation ----

    /** Kleine Festung mit Hitscan-/Strahlwaffe auf einer Plattform, eigenen Balken darüber und rechts, Gegner weiter rechts. */
    private fun rayRig(type: Int, angleDeg: Float): Pair<CombatRig, Int> {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val dev = rig.weaponOnPlatform(type, 10f, 30f, angleDeg * deg)
        rig.beamAt(6f, 25f, 16f, 25f, CombatTables.WOOD)
        rig.beamAt(17f, 22f, 17f, 28f, CombatTables.METAL)
        rig.beamAt(14f, 33f, 22f, 33f, CombatTables.METAL)
        rig.beamAt(40f, 18f, 40f, 33f, CombatTables.METAL, owner = 1)
        val plat = rig.beamAt(30f, 29f, 33f, 29f, CombatTables.METAL, owner = 1)
        rig.device(CombatTables.TARGET, plat, 0.5f, owner = 1)
        rig.tick()
        return rig to dev
    }

    private fun ownerOfUid(rig: CombatRig, target: HitTarget, uid: Int): Int = when (target) {
        HitTarget.BEAM -> beamOwnerByUid(rig, uid)
        HitTarget.DEVICE -> {
            val d = rig.state.devices
            (0 until d.size).firstOrNull { d.isAlive(it) && d.uidOf[it] == uid }?.let { d.ownerOf[it] } ?: -1
        }
        HitTarget.TERRAIN -> -1
    }

    @Test
    fun hitscanAndLaserFirstHitsEqualTheSimulation() {
        val seen = HashSet<TrajectoryOutcome>()
        var n = 0
        for (type in listOf(CombatTables.MG, CombatTables.SNIPER, CombatTables.LASER)) {
            for (a in -30..80 step 5) {
                // Sim: echter Schuss (MG mit Streuung – der tatsächliche Winkel steht im Fired-Ereignis)
                val (rig, dev) = rayRig(type, a.toFloat())
                val before = rig.fx.size
                rig.fire(dev)
                rig.run(3)
                val ev = rig.fx.subList(before, rig.fx.size)
                val fired = ev.filterIsInstance<FxEvent.Fired>().first()
                // Vorschau auf einer frischen, gleichen Anordnung mit dem tatsächlich geschossenen Winkel
                val (rig2, dev2) = rayRig(type, a.toFloat())
                val s = ShotSweep()
                val o = s.device(rig2.state, dev2, fired.angle, 1f)
                val tag = "type $type $a° (fired ${fired.angle / deg}°): pre $o hit ${s.hitKind}/${s.hitId} (${s.hitX}, ${s.hitY})"
                assertEquals(fired.x, s.muzzleX, tag)
                assertEquals(fired.y, s.muzzleY, tag)
                if (type == CombatTables.LASER) {
                    val beam = ev.filterIsInstance<FxEvent.LaserBeam>().first()
                    if (o.hasHit) {
                        assertEquals(beam.x1, s.hitX, tag)
                        assertEquals(beam.y1, s.hitY, tag)
                    }
                    // eigene Balken/Geräte verlieren TP genau dann, wenn die Vorschau "eigene Festung" meldet
                    var ownDamaged = false
                    val b = rig.state.beams
                    for (j in 0 until b.size) if (b.isAlive(j) && b.ownerOf[j] == 0 && b.hp(j) < rig.state.tables.materials[b.materialOf[j]].hp) ownDamaged = true
                    assertEquals(o == TrajectoryOutcome.BLOCKED_OWN, ownDamaged, tag)
                } else {
                    val hit = ev.filterIsInstance<FxEvent.Hit>().firstOrNull { !it.splash }
                    if (hit == null) {
                        assertEquals(TrajectoryOutcome.CLEAR, o, tag)
                    } else {
                        val simOutcome = when {
                            hit.target == HitTarget.TERRAIN -> TrajectoryOutcome.TERRAIN
                            ownerOfUid(rig, hit.target, hit.targetUid) == 0 -> TrajectoryOutcome.BLOCKED_OWN
                            else -> TrajectoryOutcome.HIT_ENEMY
                        }
                        assertEquals(simOutcome, o, tag)
                        assertEquals(hit.x, s.hitX, tag)
                        assertEquals(hit.y, s.hitY, tag)
                    }
                }
                seen += o
                n++
            }
        }
        assertTrue(n > 60)
        assertTrue(TrajectoryOutcome.BLOCKED_OWN in seen && TrajectoryOutcome.HIT_ENEMY in seen && TrajectoryOutcome.TERRAIN in seen, "$seen")
    }

    // ---- Vorschau == Simulation (Eigenschaft über ein Raster) ----

    @Test
    fun firstHitEqualsTheSimulationOverAGridOfAnglesPowersAndWinds() {
        var n = 0
        val seen = HashSet<TrajectoryOutcome>()
        for (weapon in listOf(CombatTables.MORTAR, CombatTables.CANNON)) {
            val angles = if (weapon == CombatTables.MORTAR) (20..85 step 5) else (-10..60 step 5)
            for (a in angles) for (power in floatArrayOf(0.45f, 0.8f, 1f)) for (wind in floatArrayOf(-4f, 0f, 3.5f)) {
                val (pre, sim) = aimAndCompare(false, weapon, a.toFloat(), power, wind)
                val tag = "weapon $weapon $a° power $power wind $wind"
                assertEquals(sim.outcome, pre.outcome, tag)
                if (sim.outcome != TrajectoryOutcome.CLEAR) {
                    // ohne Physik bitgleich: gleicher Balken, gleicher Punkt
                    assertEquals(sim.beamUid, pre.beamUid, tag)
                    assertEquals(sim.x, pre.x, tag)
                    assertEquals(sim.y, pre.y, tag)
                }
                seen += pre.outcome
                n++
            }
        }
        assertTrue(n > 200)
        assertTrue(TrajectoryOutcome.BLOCKED_OWN in seen && TrajectoryOutcome.HIT_ENEMY in seen && TrajectoryOutcome.TERRAIN in seen, "$seen")
    }

    @Test
    fun firstHitMatchesTheSimulationWithPhysicsAndRecoil() {
        var n = 0
        var same = 0
        for (weapon in listOf(CombatTables.MORTAR, CombatTables.CANNON)) {
            val angles = if (weapon == CombatTables.MORTAR) (25..85 step 6) else (-5..55 step 6)
            for (a in angles) for (wind in floatArrayOf(-3f, 2.5f)) {
                val (pre, sim) = aimAndCompare(true, weapon, a.toFloat(), 0.9f, wind)
                n++
                val dx = pre.x - sim.x; val dy = pre.y - sim.y
                val close = sim.outcome == TrajectoryOutcome.CLEAR || sqrt(dx * dx + dy * dy) < 0.25f
                if (pre.outcome == sim.outcome && pre.beamUid == sim.beamUid && close) same++
                // was die Vorschau als Eigentreffer meldet, ist in der Sim einer (und umgekehrt)
                assertEquals(
                    sim.outcome == TrajectoryOutcome.BLOCKED_OWN, pre.outcome == TrajectoryOutcome.BLOCKED_OWN,
                    "weapon $weapon $a° wind $wind: pre ${pre.outcome} sim ${sim.outcome}",
                )
            }
        }
        assertEquals(n, same, "Physik bewegt die (verankerte) Festung kaum: gleicher erster Treffer")
    }

    // ---- Werkzeug-Überlagerung ----

    @Test
    fun aimToolMarksABlockedShotAndEndsTheTrajectoryAtTheOwnFort() {
        val b = bay()
        b.rig.tick()
        val view = b.rig.state
        val previewer = AimPreviewer()
        val blocked = previewer.preview(view, b.mortar, 80f * deg, 0.78f)!!
        assertEquals(TrajectoryOutcome.BLOCKED_OWN, blocked.outcome)
        assertEquals(TrajectoryOutcome.BLOCKED_OWN, blocked.trajectory.outcome)
        assertTrue(blocked.trajectory.blockedOwn)
        assertEquals(ShotSweep.REASON_BLOCKED_OWN, blocked.blockedReasonKey)
        assertTrue(blocked.hasImpact)
        assertTrue(blocked.trajectory.count <= 3, "Bahn endet am Dach: ${blocked.trajectory.count}")
        // Einschlag = letzter Punkt = Dach (y ≈ 28 + halbe Panzerdicke + Radius)
        val ly = blocked.trajectory.y(blocked.trajectory.count - 1)
        assertEquals(blocked.impactY, ly)
        assertTrue(ly > 28f && ly < 28.6f, "y=$ly")
        val clear = previewer.preview(view, b.mortar, 52f * deg, 0.78f)!!
        assertTrue(clear.outcome == TrajectoryOutcome.TERRAIN || clear.outcome == TrajectoryOutcome.HIT_ENEMY, "${clear.outcome}")
        assertEquals(null, clear.blockedReasonKey)
        assertTrue(clear.trajectory.count > 30)
    }

    @Test
    fun previewRecomputesWhenTheStructureChanges() {
        val b = bay()
        b.rig.tick()
        val view = b.rig.state
        val previewer = AimPreviewer()
        val p1 = previewer.preview(view, b.mortar, 80f * deg, 0.78f)!!
        assertEquals(TrajectoryOutcome.BLOCKED_OWN, p1.outcome)
        // Dach weg → frei
        view.beams.release(b.roof)
        view.beams.endTick()
        val p2 = previewer.preview(view, b.mortar, 80f * deg, 0.78f)!!
        assertTrue(p2.outcome != TrajectoryOutcome.BLOCKED_OWN, "${p2.outcome}")
    }

    @Test
    fun hitscanPreviewStopsAtTheFirstObstacle() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val mg = rig.weaponOnPlatform(CombatTables.MG, 10f, 30f, 0f)
        val own = rig.beamAt(20f, 25f, 20f, 33f, CombatTables.WOOD)
        rig.tick()
        val p = AimPreviewer().preview(rig.state, mg, 0f, 1f)!!
        assertEquals(TrajectoryOutcome.BLOCKED_OWN, p.outcome)
        assertEquals(2, p.trajectory.count)
        assertTrue(p.trajectory.x(1) < 20f && p.trajectory.x(1) > 19.5f, "x=${p.trajectory.x(1)}")
        assertTrue(rig.state.beams.isAlive(own))
    }

    @Test
    fun aStillAimIsNotRecomputedWhileAnUnrelatedDoorTimerRunsDown() {
        val lane = doorLane()
        val rig = lane.rig
        // unbeteiligte eigene Tür weit weg, automatisch geöffnet: ihr Zeitgeber zählt je Tick herunter
        val other = rig.beamAt(-20f, 20f, -20f, 24f, CombatTables.DOOR)
        rig.state.beams.flags[other] = rig.state.beams.flags[other] or BeamFlags.DOOR_OPEN
        rig.state.beams.doorTimerTicks[other] = 120
        val previewer = AimPreviewer()
        val p0 = previewer.preview(rig.state, lane.cannon, 0f, 1f)!!
        assertEquals(TrajectoryOutcome.BLOCKED_OWN, p0.outcome)
        val count = previewer.computeCount
        for (k in 0 until 60) {
            rig.tick()
            assertTrue(rig.state.beams.doorTimerTicks[other] > 0)
            val p = previewer.preview(rig.state, lane.cannon, 0f, 1f)
            assertTrue(p === p0, "tick $k: same instance")
        }
        assertEquals(count, previewer.computeCount)
    }

    @Test
    fun theCacheIsRecomputedExactlyWhenTheCrossedDoorWouldBeClosed() {
        val lane = doorLane()
        val rig = lane.rig
        rig.state.beams.flags[lane.door] = rig.state.beams.flags[lane.door] or BeamFlags.DOOR_OPEN
        rig.state.beams.doorTimerTicks[lane.door] = 60
        val previewer = AimPreviewer()
        var flips = 0
        var last: TrajectoryOutcome? = null
        for (k in 0 until 70) {
            val p = previewer.preview(rig.state, lane.cannon, 0f, 1f)!!
            // stets gleich einer frischen Berechnung
            val fresh = AimPreviewer().preview(rig.state, lane.cannon, 0f, 1f)!!
            assertEquals(fresh.outcome, p.outcome, "tick $k")
            assertEquals(fresh.trajectory, p.trajectory, "tick $k")
            if (last != null && last != p.outcome) flips++
            last = p.outcome
            rig.tick()
        }
        assertEquals(1, flips, "open → blocked once")
        assertEquals(TrajectoryOutcome.BLOCKED_OWN, last)
        // höchstens: erste Berechnung, Kippen der Tür im Flug, Schließen der Tür (Tür-Bits)
        assertTrue(previewer.computeCount <= 3, "recomputes ${previewer.computeCount}")
    }
}
