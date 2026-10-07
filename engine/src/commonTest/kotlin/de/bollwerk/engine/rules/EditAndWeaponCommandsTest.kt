package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.rules.RuleTables.DOOR
import de.bollwerk.engine.rules.RuleTables.MG
import de.bollwerk.engine.rules.RuleTables.MINE
import de.bollwerk.engine.rules.RuleTables.MORTAR
import de.bollwerk.engine.rules.RuleTables.TURBINE
import de.bollwerk.engine.rules.RuleTables.WORKSHOP
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DeleteBeam, DeleteDevice, RepairBeam, ToggleDoor. */
class DeleteRepairDoorTest {
    @Test
    fun deleteBeamRefundsHalfOfCostTimesHpShare() {
        val rig = RulesRig()
        val b = rig.beamBetween(rig.n23, rig.apex) // 3 m Holz = 12 ⚙
        assertEquals(12f, rig.state.beams.restLen[b] * 4f, 1e-3f)
        rig.ok(Command.DeleteBeam(rig.tick, 0, rig.bref(b)))
        assertEquals(400f + 6f, rig.player().metal, 1e-3f) // 50 %
        assertTrue(rig.state.beams.resolve(rig.state.beams.ref(b)) < 0)
        assertFalse(rig.state.beams.isAlive(b))
        assertTrue(rig.fx.any { it is FxEvent.BeamBroken && it.cause == BreakCause.DELETED })
        assertTrue(rig.state.topologyDirty)
    }

    @Test
    fun deleteBeamRefundScalesWithRemainingHp() {
        val rig = RulesRig()
        val b = rig.beamBetween(rig.n23, rig.apex)
        rig.state.beams.hpOf[b] = 25f // 25 %
        rig.ok(Command.DeleteBeam(rig.tick, 0, rig.bref(b)))
        assertEquals(400f + 1.5f, rig.player().metal, 1e-3f)
    }

    @Test
    fun deletingABurningBeamOnlyExtinguishesIt() {
        val rig = RulesRig()
        val b = rig.beamBetween(rig.n23, rig.apex)
        rig.state.beams.fireOf[b] = 0.6f
        rig.ok(Command.DeleteBeam(rig.tick, 0, rig.bref(b)))
        assertTrue(rig.state.beams.isAlive(b))
        assertEquals(0f, rig.state.beams.fireOf[b])
        assertEquals(400f, rig.player().metal, 1e-3f)
    }

    @Test
    fun deletingABeamWithADeviceRefundsAndRemovesTheDevice() {
        val rig = RulesRig()
        val b = rig.beamBetween(rig.n23, rig.apex)
        rig.ok(Command.PlaceDevice(rig.tick, 0, TURBINE, rig.bref(rig.ground01), 0.5f, true)) // 80 ⚙
        val t = rig.devicesOf(TURBINE).single()
        val onB = rig.addDevice(WORKSHOP, b, 0.5f, false)
        val before = rig.player().metal
        rig.ok(Command.DeleteBeam(rig.tick, 0, rig.bref(b)))
        assertFalse(rig.state.devices.isAlive(onB))
        assertTrue(rig.state.devices.isAlive(t))
        assertEquals(before + 6f + 60f, rig.player().metal, 0.1f)  // Balken 6 + Werkstatt ½ · 120
        assertEquals(200f + 20f, rig.player().energy, 0.5f) // Werkstatt ½ · 40 ⚡ (+ Reaktor)
    }

    @Test
    fun deleteBeamRejectsReactorForeignAndStale() {
        val rig = RulesRig()
        rig.rejects(RejectReason.REACTOR_PROTECTED, Command.DeleteBeam(rig.tick, 0, rig.bref(rig.reactorBeam)))
        val enemy = rig.beamBetween(rig.nodeAt(97f, 34f), rig.nodeAt(100f, 34f))
        rig.rejects(RejectReason.NOT_OWNER, Command.DeleteBeam(rig.tick, 0, rig.bref(enemy)))
        val b = rig.beamBetween(rig.n23, rig.apex)
        val ref = rig.bref(b)
        rig.ok(Command.DeleteBeam(rig.tick, 0, ref))
        rig.rejects(RejectReason.STALE_TARGET, Command.DeleteBeam(rig.tick, 0, ref))
        rig.rejects(RejectReason.INVALID_TARGET, Command.DeleteBeam(rig.tick, 0, -1L))
    }

    @Test
    fun deleteDeviceRefundsHalfOfBothResources() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        rig.ok(Command.PlaceDevice(rig.tick, 0, WORKSHOP, rig.bref(rig.beamBetween(rig.n23, rig.apex)), 0.5f, false))
        val w = rig.devicesOf(WORKSHOP).single()
        val m = rig.player().metal
        val e = rig.player().energy
        rig.ok(Command.DeleteDevice(rig.tick, 0, rig.dref(w)))
        assertFalse(rig.state.devices.isAlive(w))
        assertEquals(m + 60f, rig.player().metal, 1e-3f)
        assertEquals(e + 20f, rig.player().energy, 0.1f)
        // stilles Entfernen: kein "zerstört"-Ereignis
        assertTrue(rig.fx.none { it is FxEvent.DeviceDestroyed })
    }

    @Test
    fun deleteDeviceRejectsReactorForeignAndStale() {
        val rig = RulesRig()
        rig.rejects(RejectReason.REACTOR_PROTECTED, Command.DeleteDevice(rig.tick, 0, rig.dref(rig.state.players[0].reactorDeviceId)))
        rig.rejects(RejectReason.NOT_OWNER, Command.DeleteDevice(rig.tick, 0, rig.dref(rig.state.players[1].reactorDeviceId)))
        rig.rejects(RejectReason.STALE_TARGET, Command.DeleteDevice(rig.tick, 0, (5L shl 32) or 3L))
        assertTrue(rig.state.devices.isAlive(rig.state.players[0].reactorDeviceId))
    }

    @Test
    fun repairSetsTheRequestFlagForTheCombatSystem() {
        val rig = RulesRig()
        val b = rig.beamBetween(rig.n23, rig.apex)
        rig.rejects(RejectReason.INVALID_TARGET, Command.RepairBeam(rig.tick, 0, rig.bref(b))) // unbeschädigt
        rig.state.beams.hpOf[b] = 40f
        rig.ok(Command.RepairBeam(rig.tick, 0, rig.bref(b)))
        assertTrue((rig.state.beams.flags[b] and BeamFlags.REPAIRING) != 0)
        rig.ok(Command.RepairBeam(rig.tick, 0, rig.bref(b))) // idempotent
    }

    @Test
    fun repairRejectsForeignAndStale() {
        val rig = RulesRig()
        val enemy = rig.beamBetween(rig.nodeAt(97f, 34f), rig.nodeAt(100f, 34f))
        rig.state.beams.hpOf[enemy] = 10f
        rig.rejects(RejectReason.NOT_OWNER, Command.RepairBeam(rig.tick, 0, rig.bref(enemy)))
        rig.rejects(RejectReason.STALE_TARGET, Command.RepairBeam(rig.tick, 0, (7L shl 32) or 1L))
    }

    @Test
    fun toggleDoorOpensPinsAndCloses() {
        val rig = RulesRig()
        val door = rig.addBeam(rig.n26, rig.addAnchor(29f, 34f), DOOR)
        val ref = rig.bref(door)
        rig.state.beams.doorTimerTicks[door] = 50
        rig.ok(Command.ToggleDoor(rig.tick, 0, ref))
        val f = rig.state.beams.flags[door]
        assertTrue((f and BeamFlags.DOOR_OPEN) != 0)
        assertTrue((f and BeamFlags.DOOR_PINNED) != 0, "vom Spieler geöffnet = fixiert")
        assertEquals(0, rig.state.beams.doorTimerTicks[door])
        assertTrue(rig.fx.any { it is FxEvent.DoorToggled && it.open })
        rig.ok(Command.ToggleDoor(rig.tick, 0, ref))
        val g = rig.state.beams.flags[door]
        assertEquals(0, g and (BeamFlags.DOOR_OPEN or BeamFlags.DOOR_PINNED))
    }

    @Test
    fun toggleDoorRejectsNonDoorsForeignAndStale() {
        val rig = RulesRig()
        rig.rejects(RejectReason.INVALID_TARGET, Command.ToggleDoor(rig.tick, 0, rig.bref(rig.ground01)))
        val enemyDoor = rig.addBeam(rig.nodeAt(94f, 34f), rig.addAnchor(91f, 34f, 1), DOOR, owner = 1)
        rig.rejects(RejectReason.NOT_OWNER, Command.ToggleDoor(rig.tick, 0, rig.bref(enemyDoor)))
        rig.rejects(RejectReason.STALE_TARGET, Command.ToggleDoor(rig.tick, 0, (3L shl 32) or 2L))
    }
}

/** SetAim und Fire. */
class AimAndFireTest {
    private fun rigWithMortars(): Triple<RulesRig, Int, Int> {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val g1 = rig.beamBetween(rig.n20, rig.apex)
        val enemyBeam = rig.beamBetween(rig.nodeAt(97f, 34f), rig.nodeAt(97f, 31f))
        val m0 = rig.addDevice(MORTAR, g1, 0.7f)
        val m1 = rig.addDevice(MORTAR, enemyBeam, 0.8f, false, owner = 1)
        return Triple(rig, m0, m1)
    }

    @Test
    fun setAimStoresAngleAndClampsPower() {
        val (rig, m0, _) = rigWithMortars()
        rig.ok(Command.SetAim(rig.tick, 0, rig.dref(m0), 1.0f, 0.5f))
        assertEquals(1.0f, rig.state.devices.aimAngle[m0], 1e-6f)
        assertEquals(0.5f, rig.state.devices.power[m0], 1e-6f)
        rig.ok(Command.SetAim(rig.tick, 0, rig.dref(m0), 1.0f, 5f))
        assertEquals(1f, rig.state.devices.power[m0])
        rig.ok(Command.SetAim(rig.tick, 0, rig.dref(m0), 1.0f, 0.01f))
        assertEquals(0.3f, rig.state.devices.power[m0], 1e-6f)
    }

    @Test
    fun setAimClampsToTheWeaponLimits() {
        val (rig, m0, m1) = rigWithMortars()
        val w = RuleTables.tables.weapons[RuleTables.W_MORTAR]
        // links schießender Spieler 0 (facing +1): Elevation in [min, max]
        rig.ok(Command.SetAim(rig.tick, 0, rig.dref(m0), 1.5707964f, 1f)) // 90° > 85°
        assertEquals(w.maxAimRad, rig.state.devices.aimAngle[m0], 1e-5f)
        rig.ok(Command.SetAim(rig.tick, 0, rig.dref(m0), -1.2f, 1f))
        assertEquals(w.minAimRad, rig.state.devices.aimAngle[m0], 1e-5f)
        // nach hinten gedreht (Winkel 170°) wird auf die Feindseite begrenzt
        rig.ok(Command.SetAim(rig.tick, 0, rig.dref(m0), 2.97f, 1f))
        assertEquals(w.maxAimRad, rig.state.devices.aimAngle[m0], 1e-5f)
        // rechter Spieler: Winkel π − Elevation, gleiche Grenzen gespiegelt
        rig.ok(Command.SetAim(rig.tick, 1, rig.dref(m1), 1.5707964f, 1f)) // senkrecht → Elevation 90° → max
        assertEquals(FloatMath.PI - w.maxAimRad, rig.state.devices.aimAngle[m1], 1e-5f)
        rig.ok(Command.SetAim(rig.tick, 1, rig.dref(m1), FloatMath.PI - 0.8f, 1f)) // innerhalb → unverändert
        assertEquals(FloatMath.PI - 0.8f, rig.state.devices.aimAngle[m1], 1e-5f)
    }

    @Test
    fun setAimRejections() {
        val (rig, m0, m1) = rigWithMortars()
        rig.rejects(RejectReason.NOT_OWNER, Command.SetAim(rig.tick, 0, rig.dref(m1), 1f, 1f))
        rig.rejects(RejectReason.INVALID_TARGET, Command.SetAim(rig.tick, 0, rig.dref(rig.state.players[0].reactorDeviceId), 1f, 1f))
        rig.rejects(RejectReason.INVALID_TARGET, Command.SetAim(rig.tick, 0, rig.dref(m0), Float.NaN, 1f))
        rig.rejects(RejectReason.INVALID_TARGET, Command.SetAim(rig.tick, 0, rig.dref(m0), 1e6f, 1f))
        rig.rejects(RejectReason.STALE_TARGET, Command.SetAim(rig.tick, 0, (4L shl 32) or 3L, 1f, 1f))
    }

    @Test
    fun fireSetsTheRequestFlagWithoutChargingYet() {
        val (rig, m0, _) = rigWithMortars()
        val metal = rig.player().metal
        rig.ok(Command.Fire(rig.tick, 0, rig.dref(m0)))
        assertTrue((rig.state.devices.flags[m0] and DeviceFlags.FIRE_REQUESTED) != 0)
        // die Kosten zieht das Waffen-System (WP4) beim Verbrauch ab
        assertEquals(metal, rig.player().metal, 0.1f)
    }

    @Test
    fun fireRejections() {
        val (rig, m0, m1) = rigWithMortars()
        rig.rejects(RejectReason.NOT_OWNER, Command.Fire(rig.tick, 0, rig.dref(m1)))
        rig.rejects(RejectReason.INVALID_TARGET, Command.Fire(rig.tick, 0, rig.dref(rig.state.players[0].reactorDeviceId)))
        rig.state.devices.reloadTicksOf[m0] = 100
        rig.rejects(RejectReason.RELOADING, Command.Fire(rig.tick, 0, rig.dref(m0)))
        rig.state.devices.reloadTicksOf[m0] = 0
        rig.state.devices.burstLeft[m0] = 3
        rig.rejects(RejectReason.RELOADING, Command.Fire(rig.tick, 0, rig.dref(m0)))
        rig.state.devices.burstLeft[m0] = 0
        rig.state.devices.buildTicks[m0] = 50
        rig.rejects(RejectReason.STILL_BUILDING, Command.Fire(rig.tick, 0, rig.dref(m0)))
        rig.state.devices.buildTicks[m0] = 0
        rig.state.players[0].energy = 29.9f
        rig.rejects(RejectReason.NOT_ENOUGH_ENERGY, Command.Fire(rig.tick, 0, rig.dref(m0)))
        rig.state.players[0].energy = 400f
        rig.state.players[0].metal = 14.9f
        rig.rejects(RejectReason.NOT_ENOUGH_METAL, Command.Fire(rig.tick, 0, rig.dref(m0)))
    }

    @Test
    fun secondFireInTheSameTickIsRejectedAsReloading() {
        val (rig, m0, _) = rigWithMortars()
        val r = rig.send(Command.Fire(rig.tick, 0, rig.dref(m0)), Command.Fire(rig.tick, 0, rig.dref(m0)))
        assertEquals(listOf(CommandResult.Accepted, CommandResult.Rejected(RejectReason.RELOADING)), r)
    }

    @Test
    fun twoWeaponsInOneTickCannotOverspendEnergy() {
        val rig = RulesRig(metal = 1000f, energy = 50f)
        val g1 = rig.beamBetween(rig.n20, rig.apex)
        val a = rig.addDevice(MORTAR, g1, 0.7f)
        val b = rig.addDevice(MORTAR, rig.beamBetween(rig.n26, rig.apex), 0.7f)
        // je 30 ⚡: 50 reichen für einen Schuss
        val r = rig.send(Command.Fire(rig.tick, 0, rig.dref(a)), Command.Fire(rig.tick, 0, rig.dref(b)))
        assertEquals(listOf(CommandResult.Accepted, CommandResult.Rejected(RejectReason.NOT_ENOUGH_ENERGY)), r)
    }

    @Test
    fun requestedShotReservesResourcesAgainstBuilding() {
        val rig = RulesRig(metal = 160f, energy = 200f)
        val m = rig.addDevice(MORTAR, rig.beamBetween(rig.n20, rig.apex), 0.7f)
        val extra = rig.addBeam(rig.n26, rig.addAnchor(29f, 34f))
        val place = Command.PlaceDevice(rig.tick, 0, TURBINE, rig.bref(rig.ground01), 0.5f, true) // 80 ⚙
        val r = rig.send(Command.Fire(rig.tick, 0, rig.dref(m)), place, place.copy(beamRef = rig.bref(extra)))
        // Schuss reserviert 15 ⚙: von 160 bleiben 145 → eine Turbine (80), die zweite scheitert (65 < 80)
        assertEquals(listOf(CommandResult.Accepted, CommandResult.Accepted, CommandResult.Rejected(RejectReason.NOT_ENOUGH_METAL)), r)
    }

    @Test
    fun machineGunCostsOnlyEnergy() {
        val rig = RulesRig(metal = 0f, energy = 7.9f)
        val mg = rig.addDevice(MG, rig.beamBetween(rig.n20, rig.apex), 0.7f)
        rig.state.players[0].techUnlocked.add(RuleTables.T_ARMOURY)
        rig.rejects(RejectReason.NOT_ENOUGH_ENERGY, Command.Fire(rig.tick, 0, rig.dref(mg)))
        rig.state.players[0].energy = 8f
        rig.ok(Command.Fire(rig.tick, 0, rig.dref(mg))) // 8 ⚡, kein Metall
    }

    @Test
    fun mineCannotBeFired() {
        val rig = RulesRig(metal = 1000f)
        val m = rig.addDevice(MINE, rig.ground01, 0.8f)
        rig.rejects(RejectReason.INVALID_TARGET, Command.Fire(rig.tick, 0, rig.dref(m)))
    }

    @Test
    fun weaponOnADebrisBeamCannotBeFired() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val beam = rig.beamBetween(rig.n20, rig.apex)
        val m = rig.addDevice(MORTAR, beam, 0.7f)
        rig.state.players[0].techUnlocked.add(RuleTables.T_WORKSHOP)
        rig.validates(null, Command.Fire(rig.tick, 0, rig.dref(m)))
        rig.state.beams.flags[beam] = rig.state.beams.flags[beam] or BeamFlags.DEBRIS
        rig.rejects(RejectReason.INVALID_TARGET, Command.Fire(rig.tick, 0, rig.dref(m)))
        assertFalse((rig.state.devices.flags[m] and DeviceFlags.FIRE_REQUESTED) != 0)
    }

    @Test
    fun deletingDebrisIsRejectedWithoutRefund() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val beam = rig.beamBetween(rig.n26, rig.apex)
        val t = rig.addDevice(TURBINE, beam, 0.6f)
        rig.validates(null, Command.DeleteBeam(rig.tick, 0, rig.bref(beam)))
        rig.validates(null, Command.DeleteDevice(rig.tick, 0, rig.dref(t)))
        rig.state.beams.flags[beam] = rig.state.beams.flags[beam] or BeamFlags.DEBRIS
        val metal = rig.player().metal
        val energy = rig.player().energy
        rig.rejects(RejectReason.INVALID_TARGET, Command.DeleteDevice(rig.tick, 0, rig.dref(t)))
        rig.rejects(RejectReason.INVALID_TARGET, Command.DeleteBeam(rig.tick, 0, rig.bref(beam)))
        assertTrue(rig.state.beams.isAlive(beam))
        assertTrue(rig.state.devices.isAlive(t))
        assertEquals(metal, rig.player().metal, 1e-3f, "keine Erstattung für Wrackteile")
        assertEquals(energy, rig.player().energy, 0.2f)
    }
}
