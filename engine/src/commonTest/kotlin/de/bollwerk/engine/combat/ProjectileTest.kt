package de.bollwerk.engine.combat

import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectileTest {
    private val ground = 34f

    /**
     * Für jede ballistische Waffe: Flugbahn mit `Ballistics.predict` (gleiche Mündung, Wind, gravityScale) vorhersagen,
     * eine senkrechte Wand auf den absteigenden Ast stellen und prüfen, dass der Treffer genau im vorhergesagten Tick fällt.
     */
    @Test
    fun ballisticWeaponsHitTargetAtPredictedTick() {
        val cases = listOf(
            Triple(CombatTables.MORTAR, CombatTables.W_MORTAR, 52f to 0.78f),
            Triple(CombatTables.CANNON, CombatTables.W_CANNON, 13f to 1f),
            Triple(CombatTables.ROCKET, CombatTables.W_ROCKET, 40f to 0.8f),
        )
        for ((type, wi, aimPow) in cases) {
            val rig = CombatRig(seed = 3L)
            rig.state.wind = 3.2f
            val aim = aimPow.first * FloatMath.DEG_TO_RAD
            val dev = rig.weaponOnPlatform(type, 10f, 30f, aim, aimPow.second)
            val wp = rig.state.tables.weapons[wi]
            val g = rig.geometry(dev)
            val out = FloatArray(4000)
            // Waffen-Überladung: Mündungsgeschwindigkeit und gravityScale kommen gemeinsam aus WeaponProps
            val n = Ballistics.predict(
                g[DeviceGeometry.MUZZLE_X], g[DeviceGeometry.MUZZLE_Y], aim, aimPow.second, wp, rig.state.wind,
                rig.state.config, out, 2000, rig.state.terrain, rig.state.map,
            )
            assertTrue(n > 20, "trajectory too short for ${wp.key}")
            // Wand bei 75 % der Flugzeit, 6 m hoch um den vorhergesagten Punkt
            val k = n * 3 / 4
            val wx = out[2 * k]; val wy = out[2 * k + 1]
            val wall = rig.beamAt(wx, FloatMath.min(wy + 3f, ground - 0.2f), wx, wy - 3f, CombatTables.METAL)
            val wallUid = rig.state.beams.uidOf[wall]
            val reach = wp.projectileRadius + rig.state.tables.materials[CombatTables.METAL].thickness * 0.5f
            var expected = -1
            for (i in 0 until n) if (out[2 * i] > wx - reach) { expected = i; break }
            assertTrue(expected in 1..k, "no predicted crossing for ${wp.key}")

            rig.tick()
            val fireTick = rig.state.tick
            rig.fire(dev)
            rig.runUntil(2000) { rig.state.projectiles.aliveCount == 0 && rig.state.tick > fireTick + 1 }
            val fired = rig.events<FxEvent.Fired>().single()
            assertEquals(fireTick, fired.tick)
            val hitTick = if (wp.damage > 0f) {
                rig.events<FxEvent.Hit>().direct().single { it.target == HitTarget.BEAM && it.targetUid == wallUid }.tick
            } else {
                rig.events<FxEvent.Explosion>().single { it.hitBeamUid == wallUid }.tick
            }
            assertEquals(fireTick + 1 + expected, hitTick, "hit tick for ${wp.key}")
            val ex = rig.events<FxEvent.Explosion>().single()
            assertEquals(wp.splashRadius, ex.radius)
            assertEquals(wp.splashDamage, ex.damage)
            assertEquals(wi, ex.weaponId)
            assertEquals(CombatTables.METAL, ex.hitMaterialId)
            assertEquals(wp.igniteRadius > 0f, ex.incendiary)
            assertTrue(ex.hitBeamT in 0f..1f)
        }
    }

    @Test
    fun mortarNumbersMatchStyleBible() {
        val w = CombatTables.tables.weapons[CombatTables.W_MORTAR]
        assertEquals(2.5f, w.splashRadius)
        assertEquals(120f, w.splashDamage)
        assertEquals(0.3f, CombatTables.tables.materials[CombatTables.ARMOUR].damageFactor)
    }

    @Test
    fun fastProjectileDoesNotTunnelThroughThinBeam() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val rope = rig.beamAt(30f, 20f, 30f, 30f, CombatTables.ROPE)
        val ropeUid = rig.state.beams.uidOf[rope]
        rig.tick()
        // 400 m/s: 3,3 m pro Teilschritt gegen 0,08 m Seil
        rig.state.projectiles.alloc(10f, 25f, 400f, 0f, CombatTables.W_CANNON, 1, 600)
        rig.run(3)
        assertEquals(0, rig.state.projectiles.aliveCount)
        val hit = rig.events<FxEvent.Hit>().direct().single()
        assertEquals(HitTarget.BEAM, hit.target)
        assertEquals(ropeUid, hit.targetUid)
        assertEquals(30f, hit.x, 0.2f)
    }

    @Test
    fun openDoorLetsProjectilePassClosedDoorBlocks() {
        for (open in listOf(false, true)) {
            val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
            val door = rig.beamAt(30f, 20f, 30f, 30f, CombatTables.DOOR)
            val wall = rig.beamAt(40f, 20f, 40f, 30f, CombatTables.METAL)
            if (open) rig.state.beams.flags[door] = rig.state.beams.flags[door] or BeamFlags.DOOR_OPEN
            rig.tick()
            rig.state.projectiles.alloc(10f, 25f, 120f, 0f, CombatTables.W_CANNON, 1, 600)
            rig.run(40)
            val hit = rig.events<FxEvent.Hit>().direct().single()
            val expected = if (open) rig.state.beams.uidOf[wall] else rig.state.beams.uidOf[door]
            assertEquals(expected, hit.targetUid, "open=$open")
        }
    }

    @Test
    fun weaponOpensOwnDoorAndClosesAfterTimer() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val aim = 0f
        val dev = rig.weaponOnPlatform(CombatTables.CANNON, 10f, 30f, aim)
        val g = rig.geometry(dev)
        val door = rig.beamAt(g[DeviceGeometry.MUZZLE_X] + 1f, 25f, g[DeviceGeometry.MUZZLE_X] + 1f, 30f, CombatTables.DOOR)
        val wall = rig.beamAt(40f, 20f, 40f, 33f, CombatTables.METAL)
        rig.tick()
        val fireTick = rig.state.tick
        rig.fire(dev)
        rig.tick()
        assertTrue((rig.state.beams.flags[door] and BeamFlags.DOOR_OPEN) != 0)
        assertEquals(fireTick, rig.events<FxEvent.DoorToggled>().single { it.open }.tick)
        rig.run(80)
        // Kugel ist durch die offene Tür geflogen und hat die Wand getroffen
        assertEquals(rig.state.beams.uidOf[wall], rig.events<FxEvent.Hit>().direct().single().targetUid)
        assertTrue((rig.state.beams.flags[door] and BeamFlags.DOOR_OPEN) != 0)
        rig.run(200)
        // genau 2,6 s (156 Ticks) nach dem Schuss-Tick wieder zu
        val close = rig.events<FxEvent.DoorToggled>().single { !it.open }
        assertEquals(fireTick + rig.state.config.door.autoCloseTicks, close.tick)
        assertEquals(156L, close.tick - fireTick)
        assertTrue((rig.state.beams.flags[door] and BeamFlags.DOOR_OPEN) == 0)
    }

    @Test
    fun pinnedDoorStaysOpen() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val door = rig.beamAt(20f, 25f, 20f, 30f, CombatTables.DOOR)
        rig.state.beams.flags[door] = rig.state.beams.flags[door] or BeamFlags.DOOR_OPEN or BeamFlags.DOOR_PINNED
        rig.state.beams.doorTimerTicks[door] = 3
        rig.run(10)
        assertTrue((rig.state.beams.flags[door] and BeamFlags.DOOR_OPEN) != 0)
    }

    @Test
    fun projectileIgnoresOwnMountBeamThenExpiresOutOfBounds() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        // fast senkrecht nach oben: fliegt aus den Kill-Grenzen (y < −120) bzw. fällt auf das Gelände zurück
        val dev = rig.weaponOnPlatform(CombatTables.CANNON, 10f, 30f, 89f * FloatMath.DEG_TO_RAD)
        rig.tick()
        rig.fire(dev)
        rig.tick()
        assertEquals(1, rig.state.projectiles.aliveCount)
        rig.run(700)
        assertEquals(0, rig.state.projectiles.aliveCount)
        // eigene Plattform/Gerät nicht getroffen
        assertTrue(rig.state.devices.isAlive(dev))
        assertTrue(rig.events<FxEvent.Hit>().none { it.target == HitTarget.DEVICE })
    }

    /**
     * Regression: Projektil A bricht einen Balken, Projektil B kreuzt im selben Tick eine der eben entstandenen
     * Bruchhälften. Die Broadphase muss die neuen Hälften kennen (sonst fliegt B hindurch und trifft die Rückwand).
     */
    @Test
    fun secondProjectileHitsHalfOfBeamBrokenEarlierInSameTick() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val wall = rig.beamAt(30f, 20f, 30f, 30f, CombatTables.WOOD)
        val backstop = rig.beamAt(40f, 15f, 40f, 33f, CombatTables.METAL)
        val backUid = rig.state.beams.uidOf[backstop]
        val wallUid = rig.state.beams.uidOf[wall]
        rig.state.beams.hpOf[wall] = 1f
        rig.tick()
        val p = rig.state.projectiles
        val a = p.alloc(10f, 24f, 120f, 0f, CombatTables.W_CANNON, 1, 600)
        val b = p.alloc(10f, 27f, 120f, 0f, CombatTables.W_CANNON, 1, 600)
        assertTrue(a < b, "A wird vor B bewegt")
        rig.runUntil(60) { p.aliveCount == 0 }
        val hits = rig.events<FxEvent.Hit>().direct()
        assertEquals(2, hits.size, "beide Kugeln treffen: $hits")
        assertEquals(wallUid, hits[0].targetUid)
        assertTrue(hits.none { it.targetUid == backUid }, "B ist durch die Bruchhälfte getunnelt: $hits")
        assertEquals(hits[0].tick, hits[1].tick, "gleicher Tick")
        assertEquals(1, rig.events<FxEvent.BeamBroken>().count { it.beamUid == wallUid })
        assertEquals(260f, rig.state.beams.hpOf[backstop])
    }

    /**
     * Ein `CombatSystems.all()` abwechselnd für zwei Zustände im selben Tick: Zustand B darf nicht das Raster von A
     * (ohne Wand) benutzen.
     */
    @Test
    fun sharedCombatWorldAlternatingStatesUsesOwnBeams() {
        val world = CombatWorld()
        val a = CombatRig(slots = CombatRig.COMBAT_ONLY, world = world)
        val b = CombatRig(slots = CombatRig.COMBAT_ONLY, world = world)
        val snA = a.weaponOnPlatform(CombatTables.SNIPER, 10f, 30f, 0f)
        val snB = b.weaponOnPlatform(CombatTables.SNIPER, 10f, 30f, 0f)
        val wallB = b.beamAt(20f, 25f, 20f, 33f, CombatTables.WOOD)
        a.tick(); b.tick()
        a.fire(snA); b.fire(snB)
        a.tick(); b.tick()
        assertEquals(a.state.tick, b.state.tick)
        assertEquals(60f, b.state.beams.hpOf[wallB])
        assertEquals(b.state.beams.uidOf[wallB], b.events<FxEvent.Hit>().single().targetUid)
        assertTrue(a.events<FxEvent.Hit>().isEmpty())
    }

    /**
     * Zielvorschau/KI ↔ Simulation für die Rakete (gravityScale 0,6): `solveAngle` mit der Waffen-Überladung liefert
     * einen Winkel, mit dem die echte Rakete das Ziel trifft; ohne Faktor (Standard 1) läge der Winkel daneben.
     */
    @Test
    fun rocketSolverWithWeaponPropsMatchesSimulatedFlight() {
        val rig = CombatRig(seed = 4L, slots = CombatRig.COMBAT_ONLY)
        rig.state.wind = -1.5f
        val dev = rig.weaponOnPlatform(CombatTables.ROCKET, 10f, 30f, 0f, 0.8f)
        val wp = rig.state.tables.weapons[CombatTables.W_ROCKET]
        val tx = 60f; val ty = 28f
        val cfg = rig.state.config
        // Mündung hängt vom Winkel ab: Fixpunkt-Iteration Winkel → Mündung → Winkel
        var angle = 0f
        var mx = 0f; var my = 0f
        for (k in 0 until 4) {
            rig.state.devices.aimAngle[dev] = angle
            val g = rig.geometry(dev)
            mx = g[DeviceGeometry.MUZZLE_X]; my = g[DeviceGeometry.MUZZLE_Y]
            angle = Ballistics.solveAngle(mx, my, tx, ty, 0.8f, wp, rig.state.wind, cfg, highArc = false)
            assertTrue(angle.isFinite())
        }
        val naive = Ballistics.solveAngle(mx, my, tx, ty, 0.8f, wp.muzzleSpeed, rig.state.wind, cfg, highArc = false)
        assertTrue(FloatMath.abs(angle - naive) > 0.02f, "gravityScale ändert den Winkel ($angle vs $naive)")
        // Zielpfosten am Zielpunkt
        val post = rig.beamAt(tx, ty - 1.5f, tx, ty + 1.5f, CombatTables.METAL)
        rig.state.devices.aimAngle[dev] = angle
        rig.tick()
        rig.fire(dev)
        rig.runUntil(600) { rig.state.projectiles.aliveCount == 0 && rig.events<FxEvent.Fired>().isNotEmpty() }
        val ex = rig.events<FxEvent.Explosion>().single()
        assertEquals(rig.state.beams.uidOf[post], ex.hitBeamUid)
        assertEquals(ty, ex.y, 0.5f)
    }
}
