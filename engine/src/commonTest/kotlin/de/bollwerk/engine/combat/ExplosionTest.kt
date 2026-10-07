package de.bollwerk.engine.combat

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExplosionTest {
    private fun explode(rig: CombatRig, x: Float, y: Float, r: Float = 2.5f, dmg: Float = 120f, j: Float = 1100f, ignite: Float = 0f) {
        CombatOps.explode(rig.state, rig.ctx, rig.world, x, y, r, dmg, j, ignite, 2, -1, -1, -1f, 1f)
        rig.fx.addAll(rig.ctx.fx)
    }

    @Test
    fun damageFallsOffWithDistance() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val half = rig.state.tables.materials[CombatTables.METAL].thickness * 0.5f
        val near = rig.beamAt(20.5f, 20f, 20.5f, 30f, CombatTables.METAL)
        val far = rig.beamAt(21.8f, 20f, 21.8f, 30f, CombatTables.METAL)
        val outside = rig.beamAt(23.5f, 20f, 23.5f, 30f, CombatTables.METAL)
        rig.tick()
        explode(rig, 20f, 25f)
        val b = rig.state.beams
        val lossNear = 260f - b.hpOf[near]
        val lossFar = 260f - b.hpOf[far]
        assertEquals(120f * (1f - (0.5f - half) / 2.5f), lossNear, 1e-3f)
        assertEquals(120f * (1f - (1.8f - half) / 2.5f), lossFar, 1e-3f)
        assertTrue(lossNear > lossFar)
        assertEquals(260f, b.hpOf[outside])
        val ev = rig.events<FxEvent.Explosion>().single()
        assertEquals(2.5f, ev.radius)
        assertEquals(120f, ev.damage)
    }

    @Test
    fun explosionPushesNodesAway() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val l = rig.node(18f, 25f, anchored = false)
        val r = rig.node(22f, 25f, anchored = false)
        val far = rig.node(30f, 25f, anchored = false)
        rig.beam(l, rig.node(18f, 24f, anchored = false), CombatTables.WOOD)
        rig.beam(r, rig.node(22f, 24f, anchored = false), CombatTables.WOOD)
        rig.beam(far, rig.node(30f, 24f, anchored = false), CombatTables.WOOD)
        rig.tick()
        val n = rig.state.nodes
        explode(rig, 20f, 25f, r = 3f, j = 1100f)
        val h = rig.state.config.substepDt
        val vl = (n.x[l] - n.px[l]) / h
        val vr = (n.x[r] - n.px[r]) / h
        assertTrue(vl < 0f, "left node pushed left ($vl)")
        assertTrue(vr > 0f, "right node pushed right ($vr)")
        // dv = min(16, J · (1 − d/R) · invMass) mit invMass = 1 (ohne Topologie-Lauf)
        assertEquals(16f, -vl, 1e-2f)
        assertEquals(0f, (n.x[far] - n.px[far]) / h, 1e-4f)
    }

    @Test
    fun armourTakesThirtyPercent() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val metal = rig.beamAt(19f, 20f, 19f, 30f, CombatTables.METAL)
        val armour = rig.beamAt(21f, 20f, 21f, 30f, CombatTables.ARMOUR)
        rig.tick()
        // gleicher Abstand zur Kapsel-Oberfläche: Mittelpunkt so wählen, dass beide Oberflächen 0,5 m entfernt sind
        val hm = rig.state.tables.materials[CombatTables.METAL].thickness * 0.5f
        val ha = rig.state.tables.materials[CombatTables.ARMOUR].thickness * 0.5f
        val x = (19f + hm + 21f - ha) * 0.5f
        explode(rig, x, 25f)
        val lossMetal = 260f - rig.state.beams.hpOf[metal]
        val lossArmour = 520f - rig.state.beams.hpOf[armour]
        assertTrue(lossMetal > 50f)
        assertEquals(0.3f * lossMetal, lossArmour, 1e-2f)
    }

    @Test
    fun closedDoorShieldsBeamBehindIt() {
        val losses = FloatArray(2)
        for ((k, open) in listOf(false, true).withIndex()) {
            val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
            val door = rig.beamAt(20.8f, 22f, 20.8f, 28f, CombatTables.DOOR)
            val behind = rig.beamAt(21.6f, 22f, 21.6f, 28f, CombatTables.METAL)
            if (open) rig.state.beams.flags[door] = rig.state.beams.flags[door] or BeamFlags.DOOR_OPEN
            rig.tick()
            explode(rig, 20f, 25f)
            losses[k] = 260f - rig.state.beams.hpOf[behind]
        }
        assertTrue(losses[1] > 0f)
        assertEquals(0.3f * losses[1], losses[0], 1e-2f)
    }

    @Test
    fun explosionDamagesDevicesAndBreaksBeams() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val beam = rig.beamAt(18f, 30f, 22f, 30f, CombatTables.WOOD)
        val dev = rig.device(CombatTables.TARGET, beam)
        rig.state.beams.hpOf[beam] = 10f
        rig.tick()
        rig.tick() // DEVICES berechnet die Gerätelage
        explode(rig, 20f, 30f)
        assertFalse(rig.state.beams.isAlive(beam))
        val broken = rig.events<FxEvent.BeamBroken>().single()
        assertEquals(0.5f, broken.t, 1e-3f)
        // zwei Hälften sind entstanden, das Gerät auf dem Balken ist zerstört
        assertEquals(2, rig.state.beams.aliveCount)
        assertFalse(rig.state.devices.isAlive(dev))
        assertEquals(1, rig.events<FxEvent.DeviceDestroyed>().size)
    }

    @Test
    fun deviceTakesSplashDamage() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val beam = rig.beamAt(10f, 30f, 14f, 30f, CombatTables.ARMOUR)
        val dev = rig.device(CombatTables.TARGET, beam)
        rig.run(2)
        val cx = rig.state.devices.x[dev]
        val cy = rig.state.devices.y[dev] - 1f // Trefferzentrum (mountOffset 1, Normale nach oben)
        explode(rig, cx, cy - 1.5f)
        val loss = 1000f - rig.state.devices.hpOf[dev]
        val dd = 1.5f - 1f * rig.state.config.combat.deviceSplashRadiusScale
        assertEquals(120f * (1f - dd / 2.5f) * 0.9f, loss, 1e-2f)
    }

    @Test
    fun incendiaryIgnitesWoodInRadiusButNotMetal() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val woodNear = rig.beamAt(21f, 22f, 21f, 28f, CombatTables.WOOD)
        val metalNear = rig.beamAt(19f, 22f, 19f, 28f, CombatTables.METAL)
        val woodFar = rig.beamAt(23.5f, 22f, 23.5f, 28f, CombatTables.WOOD)
        rig.tick()
        explode(rig, 20f, 25f, r = 4f, dmg = 1f, ignite = 2f)
        val b = rig.state.beams
        assertTrue(b.fireOf[woodNear] > 0f)
        assertEquals(0f, b.fireOf[metalNear])
        assertEquals(0f, b.fireOf[woodFar])
        assertEquals(1, rig.events<FxEvent.Ignited>().size)
        assertTrue(rig.events<FxEvent.Explosion>().single().incendiary)
    }

    @Test
    fun rocketIgnitesWoodOnImpact() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val wood = rig.beamAt(30f, 20f, 30f, 30f, CombatTables.WOOD)
        rig.tick()
        rig.state.projectiles.alloc(10f, 25f, 60f, 0f, CombatTables.W_ROCKET, 1, 600, incendiary = true)
        rig.run(40)
        assertTrue(rig.state.beams.fireOf[wood] > 0f)
        assertTrue(rig.events<FxEvent.Explosion>().single().incendiary)
    }

    @Test
    fun splashDamageFlashesEachDamagedBeamAndDevice() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val near = rig.beamAt(20.5f, 20f, 20.5f, 30f, CombatTables.METAL)
        val outside = rig.beamAt(23.5f, 20f, 23.5f, 30f, CombatTables.METAL)
        val base = rig.beamAt(18f, 31f, 20f, 31f, CombatTables.ARMOUR)
        val dev = rig.device(CombatTables.TARGET, base)
        rig.run(2)
        explode(rig, 20f, 29f)
        val hits = rig.events<FxEvent.Hit>()
        assertTrue(hits.all { it.splash && it.weaponId == 2 })
        val b = rig.state.beams
        val beamHit = hits.single { it.target == HitTarget.BEAM && it.targetUid == b.uidOf[near] }
        assertEquals(260f - b.hpOf[near], beamHit.damage, 1e-3f)
        assertTrue(hits.none { it.targetUid == b.uidOf[outside] && it.target == HitTarget.BEAM })
        val devHit = hits.single { it.target == HitTarget.DEVICE }
        assertEquals(rig.state.devices.uidOf[dev], devHit.targetUid)
        assertEquals(1000f - rig.state.devices.hpOf[dev], devHit.damage, 1e-3f)
        // Material-Faktor wird im Schaden angewandt, das Ereignis trägt den Rohschaden (wie direkte Treffer)
        val armourHit = hits.single { it.target == HitTarget.BEAM && it.targetUid == b.uidOf[base] }
        assertEquals(0.3f * armourHit.damage, 520f - b.hpOf[base], 1e-2f)
    }

    @Test
    fun explosionWithoutFxStillDamagesButEmitsNoExplosionEvent() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val beam = rig.beamAt(20.5f, 20f, 20.5f, 30f, CombatTables.METAL)
        rig.tick()
        CombatOps.explode(rig.state, rig.ctx, rig.world, 20f, 25f, 2.5f, 120f, 1100f, 0f, -1, -2, -1, -1f, 1f, emitFx = false)
        rig.fx.addAll(rig.ctx.fx)
        assertTrue(rig.state.beams.hpOf[beam] < 260f)
        assertTrue(rig.events<FxEvent.Explosion>().isEmpty())
    }
}
