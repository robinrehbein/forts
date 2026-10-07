package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.rules.RuleTables.DOOR
import de.bollwerk.engine.rules.RuleTables.FACTORY
import de.bollwerk.engine.rules.RuleTables.MINE
import de.bollwerk.engine.rules.RuleTables.MORTAR
import de.bollwerk.engine.rules.RuleTables.REACTOR
import de.bollwerk.engine.rules.RuleTables.ROPE
import de.bollwerk.engine.rules.RuleTables.TURBINE
import de.bollwerk.engine.rules.RuleTables.UPGRADE
import de.bollwerk.engine.rules.RuleTables.WORKSHOP
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.UndoKind
import de.bollwerk.engine.view.FxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceDeviceTest {
    private fun RulesRig.place(
        type: Int, beam: Int, t: Float = 0.5f, side: Boolean = true, player: Int = 0,
    ) = Command.PlaceDevice(tick, player, deviceTypeId = type, beamRef = bref(beam), t = t, sideNegative = side)

    /** Waagerechter Balken (23,34)→(26,34): t = 0,85 hat den Montagepunkt bei x = 25,55 über dem Erz bei 25,5. */
    private val oreT = 0.85f

    @Test
    fun placesMineOnOreChargesCostAndStartsBuildTimer() {
        val rig = RulesRig()
        rig.ok(rig.place(MINE, rig.ground01, oreT))
        val mine = rig.devicesOf(MINE).single()
        val d = rig.state.devices
        assertEquals(400f - 120f, rig.player().metal, 1e-3f)
        assertEquals(200f, rig.player().energy, 0.1f) // + 1 Tick Reaktor (2 ⚡/s)
        assertEquals(5 * 60, d.buildTicks[mine]) // buildSeconds = 5
        assertTrue((d.flags[mine] and DeviceFlags.BUILDING) != 0)
        assertEquals(90f, d.hpOf[mine])
        assertEquals(0, d.ownerOf[mine])
        assertEquals(rig.ground01, d.beamId[mine])
        assertEquals(oreT, d.tOf[mine], 1e-5f)
        assertEquals(1, rig.player().undoCount)
        assertEquals(UndoKind.DEVICE, rig.player().undoTop()!!.kind)
        assertTrue(rig.fx.any { it is FxEvent.DevicePlaced && it.deviceTypeId == MINE })
    }

    @Test
    fun techBuildingCostsAndBuildTimeComeFromContent() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val b = rig.beamBetween(rig.n23, rig.apex)
        rig.ok(rig.place(WORKSHOP, b, 0.5f))
        val w = rig.devicesOf(WORKSHOP).single()
        assertEquals(1000f - 120f, rig.player().metal, 1e-3f)
        assertEquals(400f - 40f, rig.player().energy, 0.1f)
        assertEquals(30 * 60, rig.state.devices.buildTicks[w]) // 30 s
        assertEquals(1, rig.player().undoCount)
    }

    @Test
    fun weaponGetsDefaultAimMirroredForTheRightSide() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val enemyGround = rig.beamBetween(rig.nodeAt(97f, 34f), rig.nodeAt(94f, 34f))
        rig.addDevice(WORKSHOP, rig.ground01, 0.1f, owner = 0)
        rig.addDevice(WORKSHOP, enemyGround, 0.1f, owner = 1)
        rig.run(1) // Werkstätten gewähren die Tech
        rig.ok(rig.place(MORTAR, rig.beamBetween(rig.n20, rig.apex), 0.7f))
        val m0 = rig.devicesOf(MORTAR, 0).single()
        assertEquals(52f * FloatMath.DEG_TO_RAD, rig.state.devices.aimAngle[m0], 1e-5f)
        assertEquals(0.78f, rig.state.devices.power[m0], 1e-5f)
        // rechter Spieler: gespiegelt (π − a)
        val enemyBeam = rig.beamBetween(rig.nodeAt(97f, 34f), rig.nodeAt(97f, 31f))
        rig.ok(Command.PlaceDevice(rig.tick, 1, MORTAR, rig.bref(enemyBeam), 0.8f, false))
        val m1 = rig.devicesOf(MORTAR, 1).single()
        assertEquals(FloatMath.PI - 52f * FloatMath.DEG_TO_RAD, rig.state.devices.aimAngle[m1], 1e-5f)
    }

    @Test
    fun deviceParameterIsClampedToTheBeam() {
        val rig = RulesRig()
        rig.ok(rig.place(TURBINE, rig.ground01, 0.99f))
        val t = rig.devicesOf(TURBINE).single()
        assertEquals(RuleConst.DEVICE_T_MAX, rig.state.devices.tOf[t], 1e-6f)
    }

    // ---- negative Fälle ----

    @Test
    fun rejectsMineAwayFromOre() {
        val rig = RulesRig()
        // erzfrei: Montagepunkt bei x ≈ 22,0 (3,5 m vom Erz bei 25,5, Grenze 2,4 m)
        val diagonal = rig.beamBetween(rig.n20, rig.apex)
        rig.rejects(RejectReason.NEEDS_ORE, rig.place(MINE, diagonal, 0.7f))
        assertEquals(400f, rig.player().metal, 0.1f)
        // direkt neben dem Erz ist es erlaubt
        rig.validates(null, rig.place(MINE, rig.ground01, 0.15f)) // x ≈ 23,45: 2,05 m vom Erz
        // zu hoch über dem Erz (Erz muss knapp unter dem Gerät liegen, Prototyp: < 3 m)
        val a = rig.addAnchor(24f, 28f)
        val b = rig.addAnchor(27f, 28f)
        rig.validates(RejectReason.NEEDS_ORE, rig.place(MINE, rig.addBeam(a, b), 0.6f))
    }

    @Test
    fun oreOfTheOtherPlayerDoesNotCount() {
        // nur der Gegner besitzt Erz neben dem Fort von Spieler 0
        val rig = RulesRig(map = RuleTables.map(ores = listOf(RuleTables.map().ores[0].copy(owner = 1), RuleTables.map().ores[2])))
        rig.rejects(RejectReason.NEEDS_ORE, rig.place(MINE, rig.ground01, oreT))
        // Spieler 1 baut auf seinem Erz bei 94,5: Balken (97,34)→(94,34), Montagepunkt bei t = 0,85 liegt bei x = 94,45
        val eb = rig.beamBetween(rig.nodeAt(97f, 34f), rig.nodeAt(94f, 34f))
        rig.validates(null, Command.PlaceDevice(rig.tick, 1, MINE, rig.bref(eb), 0.85f, false))
    }

    @Test
    fun rejectsTurbineOnTheUnderside() {
        val rig = RulesRig()
        rig.rejects(RejectReason.TOP_MOUNT_ONLY, rig.place(TURBINE, rig.ground01, 0.5f, side = false))
        rig.ok(rig.place(TURBINE, rig.ground01, 0.5f, side = true))
    }

    @Test
    fun rejectsSecondReactor() {
        val rig = RulesRig()
        rig.rejects(RejectReason.UNIQUE_LIMIT, rig.place(REACTOR, rig.ground01, 0.7f))
    }

    @Test
    fun rejectsLockedTechAndWhileTheBuildingIsStillUnderConstruction() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val b = rig.beamBetween(rig.n23, rig.apex)
        rig.rejects(RejectReason.LOCKED_TECH, rig.place(MORTAR, b))
        rig.rejects(RejectReason.LOCKED_TECH, rig.place(FACTORY, b))
        rig.ok(rig.place(WORKSHOP, b, 0.3f))
        // Werkstatt ist im Bau: Mörser bleibt gesperrt, aber mit dem Hinweis "noch im Bau"
        rig.rejects(RejectReason.STILL_BUILDING, rig.place(MORTAR, b, 0.85f))
        rig.run(30 * 60 + 2)
        rig.ok(rig.place(MORTAR, b, 0.85f))
    }

    @Test
    fun factoryNeedsTheUpgradeCenter() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val b = rig.beamBetween(rig.n23, rig.apex)
        rig.rejects(RejectReason.LOCKED_TECH, rig.place(FACTORY, b, 0.3f))
        val up = rig.addDevice(UPGRADE, rig.ground01, 0.1f)
        rig.run(1)
        assertTrue(RuleTables.T_UPGRADE in rig.player().techUnlocked)
        rig.ok(rig.place(FACTORY, b, 0.85f))
        assertEquals(1000f - 480f, rig.player().metal, 1e-3f)
        assertEquals(400f - 240f, rig.player().energy, 0.1f)
        assertEquals(90 * 60, rig.state.devices.buildTicks[rig.devicesOf(FACTORY).single()])
        assertTrue(rig.state.devices.isAlive(up))
    }

    @Test
    fun rejectsOccupiedSpot() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        val b = rig.beamBetween(rig.n23, rig.apex)
        rig.ok(rig.place(WORKSHOP, b, 0.3f))
        rig.rejects(RejectReason.OCCUPIED, rig.place(RuleTables.ARMOURY, b, 0.3f))
        rig.rejects(RejectReason.OCCUPIED, rig.place(RuleTables.ARMOURY, b, 0.35f))
        rig.ok(rig.place(RuleTables.ARMOURY, b, 0.85f))
    }

    @Test
    fun rejectsNotEnoughMetalAndEnergy() {
        val poor = RulesRig(metal = 119f)
        poor.rejects(RejectReason.NOT_ENOUGH_METAL, poor.place(MINE, poor.ground01, oreT))
        val noPower = RulesRig(metal = 1000f, energy = 39f)
        noPower.rejects(RejectReason.NOT_ENOUGH_ENERGY, noPower.place(WORKSHOP, noPower.beamBetween(noPower.n23, noPower.apex)))
    }

    @Test
    fun rejectsForeignBeamAndOutOfZone() {
        val rig = RulesRig()
        val enemy = rig.beamBetween(rig.nodeAt(97f, 34f), rig.nodeAt(100f, 34f))
        rig.rejects(RejectReason.NOT_OWNER, rig.place(TURBINE, enemy))
        // eigener Balken am Zonenrand: Montagepunkt jenseits von x = 39
        val a = rig.addAnchor(37f, 34f)
        val c = rig.addAnchor(41f, 34f)
        val b = rig.addBeam(a, c)
        rig.rejects(RejectReason.OUT_OF_BUILD_ZONE, rig.place(TURBINE, b, 0.85f))
        rig.validates(null, rig.place(TURBINE, b, 0.15f))
    }

    @Test
    fun rejectsDeviceInsideTheGround() {
        val rig = RulesRig()
        // auf der Unterseite eines Boden-Balkens läge das Gerät im Erdreich
        rig.rejects(RejectReason.BLOCKED_BY_TERRAIN, rig.place(WORKSHOP, rig.ground01, 0.5f, side = false))
        rig.ok(rig.place(WORKSHOP, rig.ground01, 0.5f, side = true))
    }

    @Test
    fun rejectsRopesDoorsAndDebrisAsMount() {
        val rig = RulesRig()
        val rope = rig.addBeam(rig.apex, rig.addAnchor(24f, 34f), ROPE)
        rig.rejects(RejectReason.INVALID_TARGET, rig.place(TURBINE, rope, 0.5f))
        val door = rig.addBeam(rig.n26, rig.addAnchor(28.5f, 34f), DOOR)
        rig.rejects(RejectReason.INVALID_TARGET, rig.place(TURBINE, door, 0.5f))
        rig.validates(RejectReason.UNKNOWN_CONTENT, Command.PlaceDevice(rig.tick, 0, 42, rig.bref(rig.ground01), 0.5f))
        rig.validates(RejectReason.STALE_TARGET, Command.PlaceDevice(rig.tick, 0, TURBINE, (9L shl 32) or 1L, 0.5f))
        rig.validates(RejectReason.INVALID_TARGET, Command.PlaceDevice(rig.tick, 0, TURBINE, -1L, 0.5f))
    }

    @Test
    fun threeDevicesInOneTickCannotOverspend() {
        val rig = RulesRig(metal = 200f)
        val extra = rig.addBeam(rig.n26, rig.addAnchor(29f, 34f))
        // je 80 ⚙: 200 reichen für zwei, das dritte scheitert, obwohl es gegen den Zustand vor dem Tick gültig wäre
        val r = rig.send(rig.place(TURBINE, rig.ground01, 0.3f), rig.place(TURBINE, rig.ground01, 0.85f), rig.place(TURBINE, extra, 0.5f))
        assertEquals(listOf(CommandResult.Accepted, CommandResult.Accepted, CommandResult.Rejected(RejectReason.NOT_ENOUGH_METAL)), r)
        assertEquals(40f, rig.player().metal, 1e-3f)
        assertEquals(2, rig.devicesOf(TURBINE).size)
    }

    @Test
    fun reactorsCanNeverBePlacedByCommandEvenAfterTheOwnLossOfOne() {
        val rig = RulesRig()
        rig.killReactor(0) // der eigene Reaktor ist weg (noch kein Tick: Spiel läuft)
        rig.validates(RejectReason.UNIQUE_LIMIT, rig.place(REACTOR, rig.ground01, 0.7f))
        rig.rejects(RejectReason.UNIQUE_LIMIT, rig.place(REACTOR, rig.ground01, 0.7f))
        assertTrue(rig.devicesOf(REACTOR).isEmpty())
    }
}
