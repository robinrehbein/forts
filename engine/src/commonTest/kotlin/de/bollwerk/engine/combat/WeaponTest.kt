package de.bollwerk.engine.combat

import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WeaponTest {
    private fun mgBurst(seed: Long): List<FxEvent.Tracer> {
        val rig = CombatRig(seed = seed, slots = CombatRig.COMBAT_ONLY)
        val mg = rig.weaponOnPlatform(CombatTables.MG, 10f, 30f, 0f)
        val wall = rig.beamAt(30f, 18f, 30f, 33f, CombatTables.ARMOUR)
        rig.tick()
        val fireTick = rig.state.tick
        rig.fire(mg)
        rig.run(60)
        val tracers = rig.events<FxEvent.Tracer>()
        assertEquals(8, tracers.size)
        // Salve: erster Schuss sofort, dann alle 4 Ticks (0,0667 s)
        for ((k, t) in tracers.withIndex()) assertEquals(fireTick + 4L * k, t.tick)
        // alle Schüsse treffen die Panzerwand: 8 × 6 × 0,3
        assertEquals(520f - 8 * 6f * 0.3f, rig.state.beams.hpOf[wall], 1e-3f)
        assertEquals(8, rig.events<FxEvent.Hit>().count { it.target == HitTarget.BEAM })
        assertEquals(400f - 8f, rig.state.players[0].energy)
        return tracers
    }

    @Test
    fun mgBurstIsDeterministicForSeed() {
        val a = mgBurst(42L)
        val b = mgBurst(42L)
        assertEquals(a, b)
        val c = mgBurst(43L)
        assertNotEquals(a.map { it.y1 }, c.map { it.y1 })
        // Streuung innerhalb ±1,2°
        for (t in a) {
            val ang = FastTrig.atan2(-(t.y1 - t.y), t.x1 - t.x)
            assertTrue(FloatMath.abs(ang) <= 1.2f * FloatMath.DEG_TO_RAD + 1e-4f, "spread $ang")
        }
        assertTrue(a.map { it.y1 }.distinct().size > 4, "spread varies per shot")
    }

    @Test
    fun sniperPiercesOneBeamAndTriplesDeviceDamage() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val sn = rig.weaponOnPlatform(CombatTables.SNIPER, 10f, 30f, 0f)
        val first = rig.beamAt(20f, 25f, 20f, 33f, CombatTables.WOOD)
        val second = rig.beamAt(25f, 25f, 25f, 33f, CombatTables.WOOD)
        val third = rig.beamAt(30f, 25f, 30f, 33f, CombatTables.WOOD)
        rig.tick()
        rig.fire(sn)
        rig.tick()
        val b = rig.state.beams
        assertEquals(60f, b.hpOf[first])
        assertEquals(60f, b.hpOf[second])
        assertEquals(100f, b.hpOf[third])
        val tracer = rig.events<FxEvent.Tracer>().single()
        assertEquals(25f - 0.16f - 0.04f, tracer.x1, 0.05f)

        val rig2 = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val sn2 = rig2.weaponOnPlatform(CombatTables.SNIPER, 10f, 30f, 0f)
        rig2.beamAt(20f, 25f, 20f, 33f, CombatTables.WOOD)
        val base = rig2.beamAt(24f, 30.6f, 26f, 30.6f, CombatTables.ARMOUR)
        val target = rig2.device(CombatTables.TARGET, base)
        rig2.tick()
        rig2.fire(sn2)
        rig2.tick()
        assertEquals(1000f - 120f, rig2.state.devices.hpOf[target])
        assertEquals(120f, rig2.events<FxEvent.Hit>().single { it.target == HitTarget.DEVICE }.damage)
    }

    @Test
    fun laserDealsDamagePerSecondForTwoSeconds() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val laser = rig.weaponOnPlatform(CombatTables.LASER, 10f, 30f, 0f)
        val wall = rig.beamAt(40f, 20f, 40f, 33f, CombatTables.METAL)
        rig.tick()
        rig.fire(laser)
        rig.run(60)
        val b = rig.state.beams
        assertEquals(260f - 80f, b.hpOf[wall], 0.05f)
        assertTrue((rig.state.devices.flags[laser] and DeviceFlags.FIRING_BEAM) != 0)
        rig.run(200)
        assertEquals(260f - 160f, b.hpOf[wall], 0.05f)
        assertEquals(120, rig.events<FxEvent.LaserBeam>().size)
        assertEquals(0, rig.state.devices.flags[laser] and DeviceFlags.FIRING_BEAM)
        assertEquals(400f - 150f, rig.state.players[0].energy)
        val lb = rig.events<FxEvent.LaserBeam>().first()
        assertEquals(40f - 0.13f - 0.08f, lb.x1, 0.05f)
    }

    @Test
    fun recoilPushesMountBackwards() {
        // Plattform hängt an zwei Seilen (Pendel), damit der Rückstoß sie frei bewegen kann
        fun build(fire: Boolean): Float {
            val rig = CombatRig(seed = 1L)
            val a = rig.node(8f, 25f, anchored = false); val b = rig.node(12f, 25f, anchored = false)
            rig.beam(rig.node(4f, 19f), a, CombatTables.ROPE)
            rig.beam(rig.node(16f, 19f), b, CombatTables.ROPE)
            val top = rig.beam(a, b, CombatTables.METAL)
            val cannon = rig.device(CombatTables.CANNON, top, aim = 60f * FloatMath.DEG_TO_RAD)
            rig.run(120)
            if (fire) rig.fire(cannon)
            rig.tick()
            if (fire) assertEquals(1, rig.state.projectiles.aliveCount)
            rig.run(5)
            val n = rig.state.nodes
            return (n.x[a] + n.x[b]) * 0.5f
        }
        val still = build(false)
        val fired = build(true)
        assertTrue(fired < still - 0.02f, "mount moved by recoil: $still -> $fired")
    }

    @Test
    fun recoilDvIsCapped() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val m = rig.weaponOnPlatform(CombatTables.MORTAR, 10f, 30f, 0f, anchored = false)
        rig.tick()
        rig.fire(m)
        rig.tick()
        val n = rig.state.nodes
        val a = rig.state.beams.a[rig.state.devices.beamId[m]]
        // invMass 1 (ohne Topologie) → dv = min(5, 260) = 5 m/s entgegen der Schussrichtung (nach links)
        assertEquals(-5f, (n.x[a] - n.px[a]) / rig.state.config.substepDt, 1e-3f)
    }

    @Test
    fun costsAreDeductedAtFireTimeAndRefusedWhenUnaffordable() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val m = rig.weaponOnPlatform(CombatTables.MORTAR, 10f, 30f, 52f * FloatMath.DEG_TO_RAD, 0.78f)
        val p = rig.state.players[0]
        p.energy = 20f
        rig.tick()
        rig.fire(m)
        rig.tick()
        val refused = rig.events<FxEvent.FireRefused>().single()
        assertEquals(RejectReason.NOT_ENOUGH_ENERGY, refused.reason)
        assertEquals(0, rig.state.projectiles.aliveCount)
        assertEquals(1000f, p.metal)
        assertEquals(0, rig.state.devices.reloadTicksOf[m])
        assertEquals(0, rig.state.devices.flags[m] and DeviceFlags.FIRE_REQUESTED)

        p.metal = 10f
        p.energy = 400f
        rig.fire(m)
        rig.tick()
        assertEquals(RejectReason.NOT_ENOUGH_METAL, rig.events<FxEvent.FireRefused>().last().reason)

        p.metal = 1000f
        rig.fire(m)
        rig.tick()
        assertEquals(1, rig.state.projectiles.aliveCount)
        assertEquals(1000f - 15f, p.metal)
        assertEquals(400f - 30f, p.energy)
        assertEquals(360, rig.state.devices.reloadTicksOf[m])
        assertEquals(1, rig.events<FxEvent.Fired>().size)
    }

    @Test
    fun reloadingWeaponIgnoresFireRequests() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val m = rig.weaponOnPlatform(CombatTables.MORTAR, 10f, 30f, 80f * FloatMath.DEG_TO_RAD, 0.3f)
        rig.tick()
        rig.fire(m)
        rig.tick()
        for (k in 0 until 358) {
            rig.fire(m)
            rig.tick()
        }
        assertEquals(1, rig.events<FxEvent.Fired>().size)
        assertEquals(2, rig.state.devices.reloadTicksOf[m])
        rig.run(2)
        assertEquals(0, rig.state.devices.reloadTicksOf[m])
        assertTrue((rig.state.devices.flags[m] and DeviceFlags.READY) != 0)
        rig.fire(m)
        rig.tick()
        assertEquals(2, rig.events<FxEvent.Fired>().size)
    }

    @Test
    fun reactorDestructionEmitsEventAndBigExplosion() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        val base = rig.beamAt(48f, 32f, 52f, 32f, CombatTables.ARMOUR, owner = 1)
        val reactor = rig.device(CombatTables.REACTOR, base, owner = 1)
        rig.beamAt(51.5f, 26f, 51.5f, 31f, CombatTables.WOOD, owner = 1)
        rig.state.devices.hpOf[reactor] = 5f
        rig.tick()
        // Mörsergranate fällt senkrecht auf den Reaktor
        rig.state.projectiles.alloc(50f, 20f, 0f, 10f, CombatTables.W_MORTAR, 0, 600)
        rig.runUntil(120) { !rig.state.devices.isAlive(reactor) }
        assertFalse(rig.state.devices.isAlive(reactor))
        val ev = rig.events<FxEvent.ReactorDestroyed>().single()
        assertEquals(1, ev.playerId)
        // genau eine darstellbare 6,5-m-Explosion: die über ReactorDestroyed (Renderer/Audio); die Physik-Explosion
        // meldet kein eigenes FxEvent.Explosion (sonst doppelter Feuerball, doppelter Knall, doppelter Shake)
        assertTrue(rig.events<FxEvent.Explosion>().none { it.radius >= 6.5f }, "${rig.events<FxEvent.Explosion>()}")
        assertEquals(1, rig.events<FxEvent.Explosion>().size, "nur die Mörsergranate")
        // Wirkung der Reaktor-Explosion: Holzbalken (≈ 2 m entfernt) brennt und hat Blast-Schaden mit Treffer-Blitz
        assertTrue(rig.events<FxEvent.Ignited>().isNotEmpty(), "reactor blast is incendiary")
        val blastHits = rig.events<FxEvent.Hit>().filter { it.splash && it.weaponId == -1 }
        assertTrue(blastHits.isNotEmpty(), "Reaktor-Explosion lässt getroffene Balken aufblitzen")
        assertTrue(blastHits.all { it.tick == ev.tick })
    }

    /**
     * Reaktor stirbt durch Einsturz (Stützen weg → Trümmer → TOPOLOGY tötet Geräte auf Trümmern) in der vollständigen
     * `StandardSystems`-Liste: Die große Explosion (Schaden, Impuls) kommt trotzdem im selben Tick, genau ein
     * `ReactorDestroyed`, und RESULT wertet aus.
     */
    @Test
    fun reactorLostToCollapseStillExplodesInStandardSystems() {
        val rig = CombatRig(standard = true)
        val s = rig.state
        val a = rig.node(50f, 31f, anchored = false, owner = 1)
        val b = rig.node(53f, 31f, anchored = false, owner = 1)
        val supA = rig.beam(rig.node(50f, 34f, owner = 1), a, CombatTables.METAL, owner = 1)
        val supB = rig.beam(rig.node(53f, 34f, owner = 1), b, CombatTables.METAL, owner = 1)
        val platform = rig.beam(a, b, CombatTables.ARMOUR, owner = 1)
        val reactor = rig.device(CombatTables.REACTOR, platform, owner = 1)
        // unabhängiger, verstrebter Pfosten von Spieler 0 rund 3 m neben dem Reaktor: misst Schaden und Impuls
        val pa = rig.node(54.5f, 34f, owner = 0)
        val pb = rig.node(54.5f, 30f, anchored = false, owner = 0)
        val post = rig.beam(pa, pb, CombatTables.METAL, owner = 0)
        rig.beam(pb, rig.node(56.5f, 34f, owner = 0), CombatTables.METAL, owner = 0)
        rig.run(30)
        assertTrue(s.devices.isAlive(reactor))
        assertEquals(260f, s.beams.hpOf[post])
        // Stützen entfernen: die Plattform mit dem Reaktor hat keinen Anker mehr
        s.beams.release(supA); s.beams.release(supB)
        s.topologyDirty = true
        val before = rig.fx.size
        rig.tick()
        assertFalse(s.devices.isAlive(reactor), "TOPOLOGY tötet den Reaktor auf Trümmern")
        val tickFx = rig.fx.subList(before, rig.fx.size)
        assertEquals(1, tickFx.count { it is FxEvent.ReactorDestroyed && it.playerId == 1 }, "kein doppeltes Ereignis")
        assertTrue(tickFx.none { it is FxEvent.Explosion && it.radius >= 6.5f })
        // Explosion hat gewirkt: Schaden am Pfosten (≈ 3 m von der Reaktor-Mitte) und Impuls auf den freien Knoten
        assertTrue(s.beams.hpOf[post] < 260f - 50f, "Blast-Schaden ${260f - s.beams.hpOf[post]}")
        assertTrue(tickFx.any { it is FxEvent.Hit && it.splash && it.targetUid == s.beams.uidOf[post] })
        assertTrue(s.nodes.x[pb] - s.nodes.px[pb] > 1e-3f, "Pfosten-Knoten nach außen gestoßen")
        assertEquals(GameResult.Winner(0, WinReason.REACTOR_DESTROYED), s.result)
        // nur einmal: weitere Ticks melden nichts mehr
        rig.run(10)
        assertEquals(1, rig.events<FxEvent.ReactorDestroyed>().size)
    }

    @Test
    fun disabledOrBuildingWeaponDoesNotFireAndIsNotReady() {
        for (flag in listOf(DeviceFlags.DISABLED, DeviceFlags.BUILDING)) {
            val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
            val m = rig.weaponOnPlatform(CombatTables.MORTAR, 10f, 30f, 52f * FloatMath.DEG_TO_RAD, 0.78f)
            val d = rig.state.devices
            rig.tick()
            assertTrue((d.flags[m] and DeviceFlags.READY) != 0)
            d.flags[m] = d.flags[m] or flag
            rig.fire(m)
            rig.tick()
            assertEquals(0, d.flags[m] and DeviceFlags.READY, "flag $flag")
            assertEquals(0, d.flags[m] and DeviceFlags.FIRE_REQUESTED, "Anforderung verbraucht")
            assertEquals(0, rig.state.projectiles.aliveCount)
            assertTrue(rig.events<FxEvent.Fired>().isEmpty())
            assertEquals(1000f, rig.state.players[0].metal, "nichts bezahlt")
            d.flags[m] = d.flags[m] and flag.inv()
            rig.fire(m)
            rig.tick()
            assertEquals(1, rig.events<FxEvent.Fired>().size)
        }
    }

    @Test
    fun projectileStoresWeaponUidNotSlot() {
        val rig = CombatRig(slots = CombatRig.COMBAT_ONLY)
        // Slot 0 einmal belegen und freigeben, damit die Waffe in Slot 0 eine andere uid (1) bekommt
        val dummy = rig.device(CombatTables.TARGET, rig.beamAt(30f, 33f, 32f, 33f, CombatTables.METAL))
        rig.state.devices.release(dummy)
        rig.state.devices.endTick()
        val m = rig.weaponOnPlatform(CombatTables.MORTAR, 10f, 30f, 52f * FloatMath.DEG_TO_RAD, 0.78f)
        assertEquals(dummy, m)
        assertNotEquals(m, rig.state.devices.uidOf[m])
        rig.tick()
        rig.fire(m)
        rig.tick()
        val p = rig.state.projectiles
        val id = (0 until p.size).single { p.isAlive(it) }
        assertEquals(rig.state.devices.uidOf[m], p.sourceDevice[id])
    }
}
