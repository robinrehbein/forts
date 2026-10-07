package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.combat.RepairSystem
import de.bollwerk.engine.rules.RuleCost
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.rules.RuleTables.MINE
import de.bollwerk.engine.rules.RuleTables.REACTOR
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.UndoKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapToolsTest {
    private fun ctrl(sel: ToolSelection) = ToolController().also { it.selectTool(sel) }

    private fun ToolController.tap(ctx: ToolContext, x: Float, y: Float): ToolResult {
        pointer(PointerPhase.DOWN, x, y, ctx)
        return pointer(PointerPhase.UP, x, y, ctx)
    }

    /** Freier Balken (28..31, y 34) mit Platz für Geräte, Spieler 0. */
    private fun freeBeam(rig: ToolRig, material: Int = RuleTables.WOOD): Int =
        rig.addBeam(rig.addAnchor(28f, 34f), rig.addAnchor(31f, 34f), material)

    // ---- Reparatur ----

    @Test
    fun repairTargetsADamagedBeamAndTheSimStartsRepair() {
        val rig = ToolRig()
        val beam = rig.ground01
        rig.state.beams.hpOf[beam] = 40f
        val c = ctrl(ToolSelection.Repair)
        val r = c.tap(rig.ctx(), 24.5f, 34f)
        val cmd = assertIs<Command.RepairBeam>(r.command)
        assertEquals(rig.bref(beam), cmd.beamRef)
        assertTrue(r.consumed)
        rig.ok(cmd)
        assertTrue((rig.state.beams.flags[beam] and BeamFlags.REPAIRING) != 0)
    }

    @Test
    fun repairPreviewShowsCostToFullAndBurning() {
        val rig = ToolRig()
        val beam = rig.ground01 // 3 m Holz, 4 ⚙/m
        rig.state.beams.hpOf[beam] = 50f // halb kaputt
        val c = ctrl(ToolSelection.Repair)
        c.pointer(PointerPhase.DOWN, 24.5f, 34f, rig.ctx())
        val h = assertNotNull(c.overlay.hint)
        assertEquals(TapAction.REPAIR, h.action)
        assertTrue(h.valid)
        assertEquals(0.5f * 4f * 3f * 0.5f, h.costMetal, 1e-3f) // costFactor · Kosten/m · Länge · fehlender Anteil
        assertFalse(h.burning)
        assertEquals(rig.bref(beam), c.overlay.selectedBeamRef)
        assertEquals(-1L, c.overlay.selectedDeviceRef)
        rig.state.beams.fireOf[beam] = 0.4f
        c.pointer(PointerPhase.MOVE, 24.5f, 34f, rig.ctx())
        assertTrue(c.overlay.hint!!.burning)
    }

    @Test
    fun repairPreviewCostEqualsTheMetalTheRepairSystemActuallySpends() {
        val rig = ToolRig(metal = 400f)
        val beam = rig.ground01
        rig.state.beams.hpOf[beam] = 35f
        val c = ctrl(ToolSelection.Repair)
        c.pointer(PointerPhase.DOWN, 24.5f, 34f, rig.ctx())
        val hint = assertNotNull(c.overlay.hint)
        val cmd = assertIs<Command.RepairBeam>(c.pointer(PointerPhase.UP, 24.5f, 34f, rig.ctx()).command)
        rig.ok(cmd)
        assertEquals(hint.costMetal, RuleCost.repairCostToFull(rig.state, beam), 1e-6f, "dieselbe Regel wie das System")
        val before = rig.metal
        val sys = RepairSystem()
        val sc = StepContext()
        repeat(200) { sc.beginTick(rig.state.tick); sys.step(rig.state, sc) }
        assertEquals(rig.state.beams.maxHpOf[beam], rig.state.beams.hpOf[beam], 1e-3f)
        assertTrue(hint.costMetal > 0f)
        assertEquals(hint.costMetal, before - rig.metal, 1e-2f, "Vorschau-Kosten = tatsächlich abgezogenes Metall")
    }

    @Test
    fun repairingAHealthyBeamIsRejected() {
        val rig = ToolRig()
        val c = ctrl(ToolSelection.Repair)
        val r = c.tap(rig.ctx(), 24.5f, 34f)
        assertNull(r.command)
        assertEquals(RejectReason.INVALID_TARGET, r.rejected)
    }

    @Test
    fun repairIgnoresEnemyBeamsAndEmptySpace() {
        val rig = ToolRig()
        val c = ctrl(ToolSelection.Repair)
        assertTrue(c.tap(rig.ctx(), 30f, 15f).commands.isEmpty())
        val enemy = rig.firstBeamOf(1)
        rig.state.beams.hpOf[enemy] = 10f
        val n = rig.state.beams.nodeA(enemy)
        val ex = rig.state.nodes.x(n)
        val ey = rig.state.nodes.y(n)
        assertTrue(c.tap(rig.ctx(), ex, ey).commands.isEmpty())
    }

    // ---- Löschen ----

    @Test
    fun deleteBeamPreviewRefundMatchesTheSim() {
        val rig = ToolRig()
        val beam = freeBeam(rig)
        rig.state.beams.hpOf[beam] = 50f
        val c = ctrl(ToolSelection.Delete)
        c.pointer(PointerPhase.DOWN, 29.5f, 34f, rig.ctx())
        val h = assertNotNull(c.overlay.hint)
        assertEquals(TapAction.DELETE, h.action)
        assertFalse(h.isDevice)
        assertEquals(0.5f * 4f * 3f * 0.5f, h.refundMetal, 1e-3f)
        val cmd = assertIs<Command.DeleteBeam>(c.pointer(PointerPhase.UP, 29.5f, 34f, rig.ctx()).command)
        assertEquals(rig.bref(beam), cmd.beamRef)
        val before = rig.metal
        rig.ok(cmd)
        assertEquals(h.refundMetal, rig.metal - before, 1e-3f)
        assertNull(c.overlay.hint, "Vorschau nach dem Loslassen weg")
    }

    @Test
    fun deleteBeamRefundIncludesDevicesStandingOnIt() {
        val rig = ToolRig()
        val beam = freeBeam(rig)
        rig.addDevice(MINE, beam, 0.5f)
        val c = ctrl(ToolSelection.Delete)
        // an der Beamkante abseits des Geräts-Zentrums (Gerät sitzt oben bei x = 29,5)
        c.pointer(PointerPhase.DOWN, 28.4f, 34f, rig.ctx())
        val h = assertNotNull(c.overlay.hint)
        assertFalse(h.isDevice)
        assertEquals(0.5f * 4f * 3f + 0.5f * 120f, h.refundMetal, 1e-3f)
        val before = rig.metal
        rig.ok(c.pointer(PointerPhase.UP, 28.4f, 34f, rig.ctx()).command)
        assertEquals(h.refundMetal, rig.metal - before, 1e-3f)
    }

    @Test
    fun deleteDeviceWinsOverTheBeamAndRefundsHalfTheCostIncludingEnergy() {
        val rig = ToolRig()
        val beam = freeBeam(rig)
        val dev = rig.addDevice(RuleTables.WORKSHOP, beam, 0.5f) // 120 ⚙ · 40 ⚡
        val c = ctrl(ToolSelection.Delete)
        c.pointer(PointerPhase.DOWN, 29.5f, 33.3f, rig.ctx()) // Gerätezentrum
        val h = assertNotNull(c.overlay.hint)
        assertTrue(h.isDevice)
        assertEquals(rig.dref(dev), h.ref)
        assertEquals(60f, h.refundMetal, 1e-3f)
        assertEquals(20f, h.refundEnergy, 1e-3f)
        assertEquals(rig.dref(dev), c.overlay.selectedDeviceRef)
        val cmd = assertIs<Command.DeleteDevice>(c.pointer(PointerPhase.UP, 29.5f, 33.3f, rig.ctx()).command)
        val metal = rig.metal
        val energy = rig.state.players[0].energy
        rig.ok(cmd)
        assertEquals(60f, rig.metal - metal, 0.5f)
        assertEquals(20f, rig.state.players[0].energy - energy, 0.5f)
    }

    @Test
    fun deleteReactorIsProtected() {
        val rig = ToolRig()
        val reactor = rig.devicesOf(REACTOR).single()
        val c = ctrl(ToolSelection.Delete)
        val x = rig.state.nodes.x(rig.n20) + 1.5f
        c.pointer(PointerPhase.DOWN, x, 33.3f, rig.ctx())
        val h = assertNotNull(c.overlay.hint)
        assertTrue(h.isDevice)
        assertFalse(h.valid)
        assertEquals(RejectReason.REACTOR_PROTECTED, h.reason)
        assertEquals(RejectReason.REACTOR_PROTECTED, c.overlay.rejected)
        val up = c.pointer(PointerPhase.UP, x, 33.3f, rig.ctx())
        assertNull(up.command)
        assertEquals(RejectReason.REACTOR_PROTECTED, up.rejected)
        assertTrue(rig.state.devices.isAlive(reactor))
    }

    @Test
    fun deleteBeamUnderTheReactorIsProtectedToo() {
        val rig = ToolRig()
        val c = ctrl(ToolSelection.Delete)
        // am Balkenende links abseits vom Reaktor-Zentrum
        val r = c.tap(rig.ctx(), 20.3f, 34f)
        assertNull(r.command)
        assertEquals(RejectReason.REACTOR_PROTECTED, r.rejected)
    }

    @Test
    fun deletingABurningBeamOnlyExtinguishes() {
        val rig = ToolRig()
        val beam = freeBeam(rig)
        rig.state.beams.fireOf[beam] = 0.5f
        val c = ctrl(ToolSelection.Delete)
        c.pointer(PointerPhase.DOWN, 29.5f, 34f, rig.ctx())
        val h = assertNotNull(c.overlay.hint)
        assertTrue(h.burning)
        assertEquals(0f, h.refundMetal)
        val cmd = c.pointer(PointerPhase.UP, 29.5f, 34f, rig.ctx()).command
        rig.ok(cmd)
        assertTrue(rig.state.beams.isAlive(beam))
        assertEquals(0f, rig.state.beams.fireOf[beam])
    }

    @Test
    fun deleteOnlyTouchesOwnObjects() {
        val rig = ToolRig()
        val enemy = rig.firstBeamOf(1)
        val n = rig.state.beams.nodeA(enemy)
        val b = rig.state.beams.nodeB(enemy)
        val mx = (rig.state.nodes.x(n) + rig.state.nodes.x(b)) * 0.5f
        val my = (rig.state.nodes.y(n) + rig.state.nodes.y(b)) * 0.5f
        val c = ctrl(ToolSelection.Delete)
        assertTrue(c.tap(rig.ctx(), mx, my).commands.isEmpty())
    }

    // ---- Tür ----

    @Test
    fun doorToolTogglesOnlyDoors() {
        val rig = ToolRig()
        val door = freeBeam(rig, RuleTables.DOOR)
        val wood = rig.addBeam(rig.addAnchor(33f, 34f), rig.addAnchor(36f, 34f))
        val c = ctrl(ToolSelection.Door)
        assertTrue(c.tap(rig.ctx(), 34.5f, 34f).commands.isEmpty(), "Holzbalken ist keine Tür")
        assertNull(c.overlay.hint)
        c.pointer(PointerPhase.DOWN, 29.5f, 34f, rig.ctx())
        assertFalse(c.overlay.hint!!.doorOpen)
        val cmd = assertIs<Command.ToggleDoor>(c.pointer(PointerPhase.UP, 29.5f, 34f, rig.ctx()).command)
        assertEquals(rig.bref(door), cmd.beamRef)
        rig.ok(cmd)
        assertTrue((rig.state.beams.flags[door] and BeamFlags.DOOR_OPEN) != 0)
        c.pointer(PointerPhase.DOWN, 29.5f, 34f, rig.ctx())
        assertTrue(c.overlay.hint!!.doorOpen)
        c.pointer(PointerPhase.CANCEL, 29.5f, 34f, rig.ctx())
        assertTrue(rig.state.beams.isAlive(wood))
    }

    // ---- Gesten: Toleranz, Abbruch, Einmal-Tipp ----

    @Test
    fun movingBeyondTheTapSlopTurnsTheGestureIntoACameraPan() {
        val rig = ToolRig()
        rig.state.beams.hpOf[rig.ground01] = 40f
        val c = ctrl(ToolSelection.Repair)
        assertTrue(c.pointer(PointerPhase.DOWN, 24.5f, 34f, rig.ctx()).consumed)
        assertNotNull(c.overlay.hint)
        assertTrue(c.pointer(PointerPhase.MOVE, 24.6f, 34f, rig.ctx()).consumed, "unter der Toleranz")
        val moved = c.pointer(PointerPhase.MOVE, 26f, 34f, rig.ctx())
        assertFalse(moved.consumed)
        assertNull(c.overlay.hint)
        val up = c.pointer(PointerPhase.UP, 26f, 34f, rig.ctx())
        assertFalse(up.consumed)
        assertTrue(up.commands.isEmpty())
        // die nächste Geste funktioniert wieder
        assertIs<Command.RepairBeam>(c.tap(rig.ctx(), 24.5f, 34f).command)
    }

    @Test
    fun cancelDropsThePreviewAndTheUpDoesNothing() {
        val rig = ToolRig()
        rig.state.beams.hpOf[rig.ground01] = 40f
        val c = ctrl(ToolSelection.Repair)
        c.pointer(PointerPhase.DOWN, 24.5f, 34f, rig.ctx())
        c.pointer(PointerPhase.CANCEL, 24.5f, 34f, rig.ctx())
        assertNull(c.overlay.hint)
        val up = c.pointer(PointerPhase.UP, 24.5f, 34f, rig.ctx())
        assertTrue(up.commands.isEmpty())
    }

    @Test
    fun statelessOnTapContract() {
        val rig = ToolRig()
        val beam = freeBeam(rig, RuleTables.DOOR)
        val tool = DefaultTapTool()
        val cmd = assertIs<Command.ToggleDoor>(tool.onTap(29.5f, 34f, ToolSelection.Door, rig.ctx()))
        assertEquals(rig.bref(beam), cmd.beamRef)
        assertNull(tool.onTap(29.5f, 34f, ToolSelection.Material(0), rig.ctx()))
        assertNull(tool.onTap(50f, 10f, ToolSelection.Door, rig.ctx()))
    }

    // ---- Zurück ----

    @Test
    fun undoIsDisabledOnAFreshGame() {
        val rig = ToolRig()
        val c = ToolController()
        val p = c.undoPreview(rig.ctx())
        assertFalse(p.canUndo)
        assertEquals(RejectReason.NOTHING_TO_UNDO, p.reason)
        assertNull(p.kind)
        val r = c.undo(rig.ctx())
        assertNull(r.command)
        assertEquals(RejectReason.NOTHING_TO_UNDO, r.rejected)
    }

    @Test
    fun undoRevertsTheLastBuildAndPreviewNamesIt() {
        val rig = ToolRig()
        val build = DefaultBuildTool().also { it.material = RuleTables.WOOD }
        build.onDown(23f, 31f, rig.ctx())
        build.onMove(26f, 28f, rig.ctx())
        val place = build.onUp(26f, 28f, rig.ctx())
        val start = rig.metal
        rig.ok(place)
        val spent = start - rig.metal
        assertTrue(spent > 0f)

        val c = ToolController()
        val p = c.undoPreview(rig.ctx())
        assertTrue(p.canUndo)
        assertEquals(UndoKind.BEAM, p.kind)
        assertEquals(spent, p.refundMetal, 1e-3f)
        assertEquals(1, p.entries)
        assertEquals(rig.bref(rig.beamBetween(rig.apex, rig.nodeAt(26f, 28f))), p.ref)

        val r = c.undo(rig.ctx())
        assertIs<Command.Undo>(r.command)
        assertTrue(r.consumed)
        rig.ok(r.command)
        assertEquals(start, rig.metal, 1e-3f)
        assertFalse(c.undoPreview(rig.ctx()).canUndo)
    }

    @Test
    fun undoOfADeviceReportsEnergyRefund() {
        val rig = ToolRig(metal = 1000f, energy = 400f)
        freeBeam(rig)
        val dev = DefaultDeviceTool().also { it.deviceType = RuleTables.WORKSHOP }
        dev.onDown(29.5f, 33.3f, rig.ctx())
        rig.ok(dev.onUp(29.5f, 33.3f, rig.ctx()))
        val p = ToolController().undoPreview(rig.ctx())
        assertEquals(UndoKind.DEVICE, p.kind)
        assertEquals(120f, p.refundMetal)
        assertEquals(40f, p.refundEnergy)
    }

    @Test
    fun undoIsBlockedWhileTheBuildBurns() {
        val rig = ToolRig()
        val build = DefaultBuildTool().also { it.material = RuleTables.WOOD }
        build.onDown(23f, 31f, rig.ctx())
        build.onMove(26f, 28f, rig.ctx())
        rig.ok(build.onUp(26f, 28f, rig.ctx()))
        val beam = rig.beamBetween(rig.apex, rig.nodeAt(26f, 28f))
        rig.state.beams.fireOf[beam] = 0.3f
        val p = ToolController().undoPreview(rig.ctx())
        assertFalse(p.canUndo)
        assertEquals(RejectReason.UNDO_BLOCKED, p.reason)
    }
}
