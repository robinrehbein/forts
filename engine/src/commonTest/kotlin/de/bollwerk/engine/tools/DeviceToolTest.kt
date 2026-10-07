package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.rules.RuleTables.MINE
import de.bollwerk.engine.rules.RuleTables.MORTAR
import de.bollwerk.engine.rules.RuleTables.TURBINE
import de.bollwerk.engine.rules.RuleTables.WORKSHOP
import de.bollwerk.engine.sim.DeviceFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceToolTest {
    private fun tool(type: Int) = DefaultDeviceTool().also { it.deviceType = type }

    /** Freistehender Balken (28..31, y 34) in der Nähe von Erz (31,5): Mine gültig bei x = 30. */
    private fun oreBeam(rig: ToolRig): Int {
        val a = rig.addAnchor(28f, 34f)
        val b = rig.addAnchor(31f, 34f)
        return rig.addBeam(a, b)
    }

    /** Freistehender Balken (33..36, y 34) weit weg von Erz. */
    private fun farBeam(rig: ToolRig): Int {
        val a = rig.addAnchor(33f, 34f)
        val b = rig.addAnchor(36f, 34f)
        return rig.addBeam(a, b)
    }

    private fun DefaultDeviceTool.ghost(): GhostDevice = (state as DeviceToolState.Previewing).ghost

    @Test
    fun mineOverOreIsValidAndShowsMountPointNormalAndCost() {
        val rig = ToolRig()
        val beam = oreBeam(rig)
        val t = tool(MINE)
        t.onDown(30f, 33.2f, rig.ctx()) // über dem Balken 28..31, Erz bei x = 31,5
        val g = t.ghost()
        assertTrue(g.valid, "reason=${g.reason}")
        assertEquals(rig.bref(beam), g.beamRef)
        assertEquals(2f / 3f, g.t, 1e-3f)
        assertTrue(g.sideNegative, "Finger oberhalb -> negative Seite (Normale nach oben)")
        assertEquals(-1f, g.ny, 1e-4f)
        assertEquals(30f, g.x, 1e-3f)
        assertEquals(34f - 0.16f, g.y, 1e-3f)
        assertEquals(RuleTables.tables.devices[MINE].costMetal, g.costMetal)
        assertEquals(0f, g.costEnergy)
        assertEquals(MINE, g.typeId)
    }

    @Test
    fun upCommitsPlaceDeviceAndTheSimAccepts() {
        val rig = ToolRig()
        oreBeam(rig)
        val t = tool(MINE)
        t.onDown(30f, 33.2f, rig.ctx())
        val g = t.ghost()
        val cmd = assertIs<Command.PlaceDevice>(t.onUp(30f, 33.2f, rig.ctx()))
        assertEquals(MINE, cmd.deviceTypeId)
        assertEquals(g.beamRef, cmd.beamRef)
        assertEquals(g.t, cmd.t, 1e-6f)
        assertTrue(cmd.sideNegative)
        assertEquals(DeviceToolState.Idle, t.state)
        val before = rig.metal
        rig.ok(cmd)
        assertEquals(1, rig.devicesOf(MINE).size)
        assertEquals(g.costMetal, before - rig.metal, 1e-3f)
    }

    @Test
    fun mineAwayFromOreIsRejected() {
        val rig = ToolRig()
        val beam = farBeam(rig)
        val t = tool(MINE)
        // Balken 33..36: weit weg vom Erz (25,5 / 31,5)
        t.onDown(35f, 33.2f, rig.ctx())
        val g = t.ghost()
        assertEquals(rig.bref(beam), g.beamRef)
        assertFalse(g.valid)
        assertEquals(RejectReason.NEEDS_ORE, g.reason)
        assertNull(t.onUp(35f, 33.2f, rig.ctx()))
        assertEquals(RejectReason.NEEDS_ORE, t.lastReject)
    }

    @Test
    fun deviceNextToAnotherOneIsOccupied() {
        val rig = ToolRig()
        val t = tool(MINE)
        // Reaktor sitzt bei x = 21,5 oben auf n20-n23
        t.onDown(21.5f, 33.2f, rig.ctx())
        assertEquals(RejectReason.OCCUPIED, t.ghost().reason)
    }

    @Test
    fun lockedTechDevice() {
        val rig = ToolRig()
        farBeam(rig)
        val t = tool(MORTAR)
        t.onDown(35f, 33.2f, rig.ctx())
        assertEquals(RejectReason.LOCKED_TECH, t.ghost().reason)
        t.cancel()
        t.deviceType = RuleTables.FACTORY
        t.onDown(35f, 33.2f, rig.ctx())
        assertEquals(RejectReason.LOCKED_TECH, t.ghost().reason)
    }

    @Test
    fun notEnoughMetalForTheDevice() {
        val rig = ToolRig(metal = 50f)
        oreBeam(rig)
        val t = tool(MINE)
        t.onDown(30f, 33.2f, rig.ctx())
        assertEquals(RejectReason.NOT_ENOUGH_METAL, t.ghost().reason)
        assertNull(t.onUp(30f, 33.2f, rig.ctx()))
        assertEquals(RejectReason.NOT_ENOUGH_METAL, t.lastReject)
    }

    @Test
    fun notEnoughEnergyForTheDevice() {
        val rig = ToolRig(energy = 10f)
        farBeam(rig)
        val t = tool(WORKSHOP) // 120 ⚙ · 40 ⚡
        t.onDown(35f, 33.2f, rig.ctx())
        assertEquals(RejectReason.NOT_ENOUGH_ENERGY, t.ghost().reason)
        assertEquals(40f, t.ghost().costEnergy)
    }

    @Test
    fun sideFollowsTheFingerAndTurbinesNeedTheTopSide() {
        val rig = ToolRig()
        farBeam(rig)
        val t = tool(TURBINE)
        t.onDown(35f, 33.2f, rig.ctx())
        assertTrue(t.ghost().valid, "reason=${t.ghost().reason}")
        assertTrue(t.ghost().sideNegative)
        t.onMove(35f, 34.6f, rig.ctx()) // unterhalb des Balkens
        val below = t.ghost()
        assertFalse(below.sideNegative)
        assertEquals(RejectReason.TOP_MOUNT_ONLY, below.reason)
        assertEquals(1f, below.ny, 1e-4f)
    }

    @Test
    fun noBeamUnderTheFingerGivesARedGhostAtTheFinger() {
        val rig = ToolRig()
        val t = tool(MINE)
        t.onDown(30f, 20f, rig.ctx())
        val g = t.ghost()
        assertFalse(g.valid)
        assertEquals(RejectReason.INVALID_TARGET, g.reason)
        assertEquals(-1L, g.beamRef)
        assertEquals(30f, g.x)
        assertEquals(20f, g.y)
        assertNull(t.onUp(30f, 20f, rig.ctx()))
    }

    @Test
    fun ropesDoorsAndEnemyBeamsAreNoHosts() {
        val rig = ToolRig()
        val a = rig.addAnchor(30f, 34f)
        val b = rig.addAnchor(33f, 34f)
        rig.addBeam(a, b, RuleTables.ROPE)
        val c = rig.addAnchor(35f, 34f)
        val d = rig.addAnchor(35f, 30f)
        rig.addBeam(c, d, RuleTables.DOOR)
        val t = tool(MINE)
        t.onDown(31.5f, 34f, rig.ctx())
        assertEquals(-1L, t.ghost().beamRef)
        t.onMove(35f, 32f, rig.ctx())
        assertEquals(-1L, t.ghost().beamRef)
        t.onMove(100f, 34f, rig.ctx()) // Fundament von Spieler 1
        assertEquals(-1L, t.ghost().beamRef)
    }

    @Test
    fun hoverShowsAGhostWithoutPressingButNeverCommits() {
        val rig = ToolRig()
        oreBeam(rig)
        val t = tool(MINE)
        t.hover(30f, 33.2f, rig.ctx())
        assertTrue(t.ghost().valid)
        assertFalse(t.pressed)
        assertNull(t.onUp(30f, 33.2f, rig.ctx()))
    }

    @Test
    fun cancelDropsTheGhostAndTheUp() {
        val rig = ToolRig()
        oreBeam(rig)
        val t = tool(MINE)
        t.onDown(30f, 33.2f, rig.ctx())
        t.cancel()
        assertEquals(DeviceToolState.Idle, t.state)
        assertNull(t.onUp(30f, 33.2f, rig.ctx()))
    }

    @Test
    fun unknownDeviceTypeIsHandled() {
        val rig = ToolRig()
        val t = tool(99)
        t.onDown(25f, 33f, rig.ctx())
        assertEquals(RejectReason.UNKNOWN_CONTENT, t.ghost().reason)
        assertNull(t.onUp(25f, 33f, rig.ctx()))
    }

    @Test
    fun placedWeaponGetsBuildTimerLikeInTheSim() {
        // Werkstatt zuerst, danach ist der Mörser (Tech Werkstatt) gesperrt, solange sie noch baut: STILL_BUILDING aus dem Validator
        val rig = ToolRig(metal = 1000f, energy = 400f)
        farBeam(rig)
        val t = tool(WORKSHOP)
        t.onDown(34f, 33.2f, rig.ctx())
        rig.ok(t.onUp(34f, 33.2f, rig.ctx()))
        val ws = rig.devicesOf(WORKSHOP).single()
        assertTrue((rig.state.devices.flags[ws] and DeviceFlags.BUILDING) != 0 || rig.state.devices.buildTicks[ws] > 0)
        t.deviceType = MORTAR
        t.onDown(35.5f, 33.2f, rig.ctx())
        assertEquals(RejectReason.STILL_BUILDING, t.ghost().reason)
    }
}
