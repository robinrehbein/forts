package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandValidator
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.rules.RuleTables.MINE
import de.bollwerk.engine.rules.RuleTables.WOOD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolControllerTest {
    private fun woodController(settings: ToolSettings = ToolSettings()) =
        ToolController(settings).also { it.selectTool(ToolSelection.Material(WOOD)) }

    // ---- Werkzeugwahl ----

    @Test
    fun startsWithoutToolAndIgnoresGestures() {
        val rig = ToolRig()
        val c = ToolController()
        assertEquals(ToolMode.NONE, c.mode)
        assertEquals(ToolOverlay.NONE, c.overlay)
        val r = c.drag(rig.ctx(), 23f, 31f, 26f, 28f)
        assertFalse(r.consumed)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun selectingToolsSetsModeSelectionAndToolParameters() {
        val c = ToolController()
        c.selectTool(ToolSelection.Material(RuleTables.METAL))
        assertEquals(ToolMode.BUILD, c.mode)
        assertEquals(RuleTables.METAL, c.build.material)
        assertEquals(ToolSelection.Material(RuleTables.METAL), c.overlay.tool)
        c.selectTool(ToolSelection.Device(MINE))
        assertEquals(MINE, c.device.deviceType)
        c.selectTool(ToolSelection.Delete)
        assertEquals(ToolSelection.Delete, c.overlay.tool)
        c.enterAimMode()
        assertEquals(ToolMode.AIM, c.mode)
        assertEquals(ToolMode.AIM, c.overlay.mode)
        assertEquals(ToolSelection.None, c.overlay.tool)
        c.selectTool(ToolSelection.None)
        assertEquals(ToolMode.NONE, c.mode)
    }

    @Test
    fun switchingToolsDropsRunningGesturesAndChains() {
        val rig = ToolRig()
        val c = woodController(ToolSettings().also { it.chainMode = true })
        c.pointer(PointerPhase.DOWN, 23f, 31f, rig.ctx())
        c.pointer(PointerPhase.MOVE, 26f, 28f, rig.ctx())
        assertNotNull(c.overlay.ghost)
        c.selectTool(ToolSelection.Material(RuleTables.METAL))
        assertNull(c.overlay.ghost)
        assertEquals(BuildToolState.Idle, c.build.state)
        // Kette zurücksetzen
        c.drag(rig.ctx(), 23f, 31f, 26f, 28f)
        assertTrue(c.build.chainArmed)
        c.selectTool(ToolSelection.Material(WOOD))
        assertFalse(c.build.chainArmed)
    }

    // ---- Overlay: Bauen ----

    @Test
    fun buildOverlayCarriesGhostSnapsAndLoupeWhileDragging() {
        val rig = ToolRig()
        val other = rig.addAnchor(27.5f, 34f)
        val c = woodController()
        val ctx = rig.ctx()
        c.pointer(PointerPhase.DOWN, 23f, 31f, ctx)
        var o = c.overlay
        assertEquals(ToolMode.BUILD, o.mode)
        assertNull(o.ghost)
        assertEquals(1, o.snaps.size)
        assertNotNull(o.loupe, "Lupe schon beim Aufsetzen")
        c.pointer(PointerPhase.MOVE, 27.8f, 33.8f, ctx)
        o = c.overlay
        val g = assertNotNull(o.ghost)
        assertTrue(g.valid)
        assertEquals(rig.nref(other), g.snapNodeRef)
        assertEquals(2, o.snaps.size)
        val loupe = assertNotNull(o.loupe)
        assertEquals(27.8f, loupe.worldX)
        assertEquals(33.8f, loupe.worldY)
        assertEquals(27.5f, loupe.targetX, 1e-5f)
        assertEquals(34f, loupe.targetY, 1e-5f)
        assertNull(o.rejected)
        assertEquals(0, o.localPlayer)

        c.pointer(PointerPhase.MOVE, 23f, 20f, ctx)
        assertEquals(RejectReason.TOO_LONG, c.overlay.rejected)
        assertEquals(RejectReason.TOO_LONG, c.overlay.ghost!!.reason)

        val up = c.pointer(PointerPhase.UP, 23f, 20f, ctx)
        assertTrue(up.commands.isEmpty())
        assertEquals(RejectReason.TOO_LONG, up.rejected)
        o = c.overlay
        assertNull(o.ghost)
        assertNull(o.loupe)
        assertTrue(o.snaps.isEmpty())
    }

    @Test
    fun buildGestureThroughTheControllerProducesOneCommand() {
        val rig = ToolRig()
        val c = woodController()
        val r = c.drag(rig.ctx(), 23.2f, 31.1f, 24.5f, 29f, 26f, 28f)
        assertTrue(r.consumed)
        assertEquals(1, r.commands.size)
        val cmd = assertIs<Command.PlaceBeam>(r.command)
        assertEquals(rig.nref(rig.apex), cmd.aNodeRef)
        rig.ok(cmd)
    }

    @Test
    fun resultObjectIsReusedAndOverwritten() {
        val rig = ToolRig()
        val c = woodController()
        val a = c.pointer(PointerPhase.DOWN, 23f, 31f, rig.ctx())
        val b = c.pointer(PointerPhase.MOVE, 25f, 29f, rig.ctx())
        assertSame(a, b)
    }

    @Test
    fun cancelThroughTheControllerKeepsTheToolAndSendsNothing() {
        val rig = ToolRig()
        val c = woodController()
        c.pointer(PointerPhase.DOWN, 23f, 31f, rig.ctx())
        c.pointer(PointerPhase.MOVE, 26f, 28f, rig.ctx())
        c.pointer(PointerPhase.CANCEL, 26f, 28f, rig.ctx())
        assertNull(c.overlay.ghost)
        assertEquals(ToolMode.BUILD, c.mode)
        val up = c.pointer(PointerPhase.UP, 26f, 28f, rig.ctx())
        assertTrue(up.commands.isEmpty())
        assertFalse(up.consumed)
        // wieder bedienbar
        assertEquals(1, c.drag(rig.ctx(), 23f, 31f, 26f, 28f).commands.size)
    }

    @Test
    fun cancelCallKeepsAChainButDropsTheFinger() {
        val rig = ToolRig()
        val c = woodController(ToolSettings().also { it.chainMode = true })
        rig.ok(c.drag(rig.ctx(), 23f, 31f, 26f, 28f).command)
        c.pointer(PointerPhase.DOWN, 28f, 26f, rig.ctx())
        c.cancel()
        assertTrue(c.build.chainArmed)
        assertNull(c.overlay.ghost)
    }

    // ---- Overlay: Geräte ----

    @Test
    fun deviceOverlayShowsTheGhostAndLoupeAndHoverWorks() {
        val rig = ToolRig()
        rig.addBeam(rig.addAnchor(28f, 34f), rig.addAnchor(31f, 34f))
        val c = ToolController()
        c.selectTool(ToolSelection.Device(MINE))
        c.hover(30f, 33.2f, rig.ctx())
        val gd = assertNotNull(c.overlay.ghostDevice)
        assertTrue(gd.valid)
        assertNull(c.overlay.loupe, "keine Lupe ohne Finger")
        c.pointer(PointerPhase.DOWN, 30f, 33.2f, rig.ctx())
        assertNotNull(c.overlay.loupe)
        val up = c.pointer(PointerPhase.UP, 30f, 33.2f, rig.ctx())
        assertIs<Command.PlaceDevice>(up.command)
        assertNull(c.overlay.ghostDevice)
    }

    @Test
    fun invalidDeviceDropReportsTheReason() {
        val rig = ToolRig(metal = 10f)
        rig.addBeam(rig.addAnchor(28f, 34f), rig.addAnchor(31f, 34f))
        val c = ToolController()
        c.selectTool(ToolSelection.Device(MINE))
        val r = c.drag(rig.ctx(), 30f, 33.2f, 30f, 33.2f)
        assertTrue(r.commands.isEmpty())
        assertEquals(RejectReason.NOT_ENOUGH_METAL, r.rejected)
    }

    // ---- Refresh ----

    @Test
    fun refreshRevalidatesARunningGhostWhenMetalChanges() {
        val rig = ToolRig(metal = 14f)
        val c = woodController(ToolSettings().also { it.angleSnap = false })
        c.pointer(PointerPhase.DOWN, 23f, 31f, rig.ctx())
        c.pointer(PointerPhase.MOVE, 26f, 29f, rig.ctx()) // 14 ⚙
        assertTrue(c.overlay.ghost!!.valid)
        rig.state.players[0].metal = 5f
        c.refresh(rig.ctx())
        assertEquals(RejectReason.NOT_ENOUGH_METAL, c.overlay.ghost!!.reason)
        assertEquals(RejectReason.NOT_ENOUGH_METAL, c.overlay.rejected)
    }

    // ---- Langdruck / Kontextmenü ----

    private fun freeBeam(rig: ToolRig, material: Int = WOOD): Int =
        rig.addBeam(rig.addAnchor(28f, 34f), rig.addAnchor(31f, 34f), material)

    @Test
    fun longPressOnABeamOffersRepairAndDelete() {
        val rig = ToolRig()
        val beam = freeBeam(rig)
        rig.state.beams.hpOf[beam] = 50f
        val c = woodController()
        val r = c.longPress(29.5f, 34f, rig.ctx())
        assertTrue(r.consumed)
        val menu = assertNotNull(c.overlay.contextMenu)
        assertFalse(menu.isDevice)
        assertEquals(rig.bref(beam), menu.targetRef)
        assertEquals(listOf(TapAction.REPAIR, TapAction.DELETE), menu.options.map { it.action })
        assertTrue(menu.options.all { it.enabled })
        assertEquals(rig.bref(beam), c.overlay.selectedBeamRef)
        assertEquals(29.5f, menu.x)

        val pick = c.chooseContext(TapAction.REPAIR, rig.ctx())
        val cmd = assertIs<Command.RepairBeam>(pick.command)
        assertEquals(rig.bref(beam), cmd.beamRef)
        assertNull(c.overlay.contextMenu)
        rig.ok(cmd)
    }

    @Test
    fun longPressOnADoorAddsTheDoorEntryAndDisabledEntriesCarryTheReason() {
        val rig = ToolRig()
        val door = freeBeam(rig, RuleTables.DOOR) // unbeschädigt: Reparatur nicht sinnvoll
        val c = ToolController()
        c.longPress(29.5f, 34f, rig.ctx())
        val menu = assertNotNull(c.contextMenu)
        assertEquals(listOf(TapAction.REPAIR, TapAction.DELETE, TapAction.DOOR), menu.options.map { it.action })
        val repair = menu.options[0]
        assertFalse(repair.enabled)
        assertEquals(RejectReason.INVALID_TARGET, repair.reason)
        assertTrue(menu.options[1].enabled)
        assertTrue(menu.options[2].enabled)
        assertFalse(menu.options[2].hint.doorOpen)

        // gesperrte Wahl liefert kein Command, aber den Grund
        val bad = c.chooseContext(TapAction.REPAIR, rig.ctx())
        assertTrue(bad.commands.isEmpty())
        assertEquals(RejectReason.INVALID_TARGET, bad.rejected)
        assertNull(c.contextMenu)

        c.longPress(29.5f, 34f, rig.ctx())
        val cmd = assertIs<Command.ToggleDoor>(c.chooseContext(TapAction.DOOR, rig.ctx()).command)
        assertEquals(rig.bref(door), cmd.beamRef)
    }

    @Test
    fun longPressOnADeviceOffersOnlyDelete() {
        val rig = ToolRig()
        val dev = rig.addDevice(RuleTables.WORKSHOP, freeBeam(rig), 0.5f)
        val c = ToolController()
        c.longPress(29.5f, 33.3f, rig.ctx())
        val menu = assertNotNull(c.contextMenu)
        assertTrue(menu.isDevice)
        assertEquals(rig.dref(dev), menu.targetRef)
        assertEquals(1, menu.options.size)
        assertEquals(rig.dref(dev), c.overlay.selectedDeviceRef)
        val cmd = assertIs<Command.DeleteDevice>(c.chooseContext(TapAction.DELETE, rig.ctx()).command)
        rig.ok(cmd)
    }

    @Test
    fun longPressOnTheReactorDisablesDelete() {
        val rig = ToolRig()
        val c = ToolController()
        c.longPress(21.5f, 33.3f, rig.ctx())
        val opt = assertNotNull(c.contextMenu).options.single()
        assertFalse(opt.enabled)
        assertEquals(RejectReason.REACTOR_PROTECTED, opt.reason)
    }

    @Test
    fun longPressOnNothingOpensNoMenu() {
        val rig = ToolRig()
        val c = ToolController()
        val r = c.longPress(40f, 10f, rig.ctx())
        assertFalse(r.consumed)
        assertNull(c.overlay.contextMenu)
    }

    @Test
    fun longPressWorksInEveryModeAndCancelsTheRunningGestureAndSwallowsTheRest() {
        val rig = ToolRig()
        val beam = freeBeam(rig)
        val c = woodController()
        // Finger liegt auf einem Knoten des freien Balkens (Bau-Start), hält -> Langdruck
        c.pointer(PointerPhase.DOWN, 28f, 34f, rig.ctx())
        assertIs<BuildToolState.FirstNodeSelected>(c.build.state)
        c.longPress(29.5f, 34f, rig.ctx())
        assertEquals(BuildToolState.Idle, c.build.state)
        assertNotNull(c.overlay.contextMenu)
        assertEquals(rig.bref(beam), c.overlay.selectedBeamRef)
        // Rest der Geste: weiterziehen und loslassen baut nichts
        val move = c.pointer(PointerPhase.MOVE, 30f, 30f, rig.ctx())
        assertTrue(move.consumed)
        assertTrue(move.commands.isEmpty())
        val up = c.pointer(PointerPhase.UP, 30f, 30f, rig.ctx())
        assertTrue(up.commands.isEmpty())
        assertNotNull(c.overlay.contextMenu, "Menü bleibt offen")
        // im Zielmodus geht es ebenso
        c.dismissContext()
        c.enterAimMode()
        c.longPress(29.5f, 34f, rig.ctx())
        assertNotNull(c.contextMenu)
    }

    @Test
    fun touchingOutsideDismissesTheMenuWithoutTriggeringTheTool() {
        val rig = ToolRig()
        freeBeam(rig)
        val c = woodController()
        c.longPress(29.5f, 34f, rig.ctx())
        val down = c.pointer(PointerPhase.DOWN, 23f, 31f, rig.ctx()) // wäre ein Bau-Start
        assertTrue(down.consumed)
        assertNull(c.overlay.contextMenu)
        assertEquals(BuildToolState.Idle, c.build.state)
        c.pointer(PointerPhase.MOVE, 26f, 28f, rig.ctx())
        val up = c.pointer(PointerPhase.UP, 26f, 28f, rig.ctx())
        assertTrue(up.commands.isEmpty())
        // danach wieder normal
        assertEquals(1, c.drag(rig.ctx(), 23f, 31f, 26f, 28f).commands.size)
    }

    @Test
    fun contextChoiceIsRevalidatedAgainstTheCurrentState() {
        val rig = ToolRig()
        val beam = freeBeam(rig)
        val c = ToolController()
        c.longPress(29.5f, 34f, rig.ctx())
        // zwischen Langdruck und Wahl: Balken ist weg
        rig.state.beams.release(beam)
        rig.step(); rig.step(); rig.step()
        val r = c.chooseContext(TapAction.DELETE, rig.ctx())
        assertTrue(r.commands.isEmpty())
        assertEquals(RejectReason.STALE_TARGET, r.rejected)
    }

    @Test
    fun refreshClosesTheMenuWhenItsTargetIsGone() {
        val rig = ToolRig()
        val beam = freeBeam(rig)
        val c = ToolController()
        c.longPress(29.5f, 34f, rig.ctx())
        rig.state.beams.release(beam)
        rig.step(); rig.step(); rig.step()
        c.refresh(rig.ctx())
        assertNull(c.overlay.contextMenu)
    }

    @Test
    fun chooseWithoutMenuIsHarmless() {
        val rig = ToolRig()
        val c = ToolController()
        val r = c.chooseContext(TapAction.DELETE, rig.ctx())
        assertTrue(r.commands.isEmpty())
        assertFalse(r.consumed)
    }

    // ---- Determinismus ----

    @Test
    fun sameInputsGiveIdenticalOverlaysAndCommands() {
        fun run(): Triple<ToolOverlay, List<Command>, ToolOverlay> {
            val rig = ToolRig()
            rig.state.wind = 1.5f
            val c = woodController()
            c.pointer(PointerPhase.DOWN, 23.2f, 31.1f, rig.ctx())
            c.pointer(PointerPhase.MOVE, 25f, 29.3f, rig.ctx())
            val mid = c.overlay
            val cmds = ArrayList(c.pointer(PointerPhase.UP, 26.1f, 28.2f, rig.ctx()).commands)
            c.enterAimMode()
            rig.addDevice(RuleTables.MORTAR, rig.ground01, 0.5f, true, 0, 0.9f, 0.78f)
            c.pointer(PointerPhase.DOWN, 24.5f, 33.29f, rig.ctx())
            c.pointer(PointerPhase.UP, 24.5f, 33.29f, rig.ctx())
            return Triple(mid, cmds, c.overlay)
        }
        val a = run()
        val b = run()
        assertEquals(a.first, b.first)
        assertEquals(a.second, b.second)
        assertEquals(a.third, b.third)
        assertNotNull(a.third.trajectory)
        assertEquals(a.third.hashCode(), b.third.hashCode())
    }

    // ---- Validator-Durchreichung ----

    @Test
    fun theControllerUsesTheValidatorOfTheContext() {
        val rig = ToolRig()
        val ctx = ToolContext(rig.state, 0, 1f, CommandValidator { _, cmd ->
            if (cmd is Command.PlaceBeam) RejectReason.NOT_YOUR_TURN else null
        })
        val c = woodController()
        val r = c.drag(ctx, 23f, 31f, 26f, 28f)
        assertTrue(r.commands.isEmpty())
        assertEquals(RejectReason.NOT_YOUR_TURN, r.rejected)
    }

    @Test
    fun turnBasedGameRejectsToolCommandsOfThePlayerWhoIsNotOnTurn() {
        val rig = ToolRig(turns = true)
        assertEquals(0, rig.state.turn.activePlayer)
        val build = DefaultBuildTool().also { it.material = WOOD }
        val ctx1 = rig.ctx(player = 1)
        build.onDown(97f, 31f, ctx1) // Spitze der gespiegelten Festung von Spieler 1
        build.onMove(94f, 28f, ctx1)
        val ghost = (build.state as BuildToolState.Previewing).ghost
        assertEquals(RejectReason.NOT_YOUR_TURN, ghost.reason)
        assertNull(build.onUp(94f, 28f, ctx1))
        // Spieler 0 darf
        val ok = DefaultBuildTool().also { it.material = WOOD }
        ok.onDown(23f, 31f, rig.ctx())
        ok.onMove(26f, 28f, rig.ctx())
        assertIs<Command.PlaceBeam>(ok.onUp(26f, 28f, rig.ctx()))
    }

    // ---- Chain / Cancel / Spielerwechsel / Overlay ----

    @Test
    fun cancelAfterTheUpKeepsAChainWithoutAFinger() {
        val rig = ToolRig()
        val c = woodController(ToolSettings().also { it.chainMode = true })
        rig.ok(c.drag(rig.ctx(), 23f, 31f, 26f, 28f).command)
        assertTrue(c.build.chainArmed)
        assertFalse(c.build.pressed)
        c.cancel() // App pausiert, kein DOWN
        assertTrue(c.build.chainArmed)
        // und die Kette lässt sich danach fortsetzen
        val next = c.drag(rig.ctx(), 29f, 27f, 29f, 25f)
        assertIs<Command.PlaceBeam>(next.command)
    }

    @Test
    fun switchingTheLocalPlayerDropsChainsAndPreviews() {
        val rig = ToolRig(turns = false)
        val c = woodController(ToolSettings().also { it.chainMode = true })
        rig.ok(c.drag(rig.ctx(0), 23f, 31f, 26f, 28f).command)
        assertTrue(c.build.chainArmed)
        c.pointer(PointerPhase.DOWN, 97f, 31f, rig.ctx(1))
        assertFalse(c.build.chainArmed, "die Kette von Spieler 0 gehört nicht Spieler 1")
        assertEquals(1, c.overlay.localPlayer)
        // Spieler 1 baut an seiner eigenen Festung ohne den fremden Anker
        c.pointer(PointerPhase.MOVE, 94f, 28f, rig.ctx(1))
        val g = assertNotNull(c.overlay.ghost)
        assertNull(g.reason, "reason=${g.reason}")
        assertEquals(97f, g.ax, 1e-3f)
    }

    @Test
    fun switchingTheLocalPlayerClearsTheWeaponSelectionAndContextMenu() {
        val rig = ToolRig()
        val beam = rig.ground01
        val c = ToolController()
        c.selectTool(ToolSelection.Repair)
        c.longPress(24.5f, 34f, rig.ctx(0))
        assertNotNull(c.contextMenu)
        c.refresh(rig.ctx(1))
        assertNull(c.contextMenu)
        assertTrue(beam >= 0)
    }

    @Test
    fun theOverlayRecordsTheLastRejectForTheFlashAndKeepsItAfterTheGesture() {
        val rig = ToolRig(metal = 1f)
        val c = woodController()
        assertEquals(0, c.overlay.lastRejectSeq)
        val up = c.drag(rig.ctx(), 23f, 31f, 26f, 28f)
        assertEquals(RejectReason.NOT_ENOUGH_METAL, up.rejected)
        assertEquals(RejectReason.NOT_ENOUGH_METAL, c.overlay.lastReject)
        assertEquals(1, c.overlay.lastRejectSeq)
        assertEquals(rig.state.tick, c.overlay.lastRejectTick)
        assertNull(c.overlay.rejected, "die laufende Vorschau ist beendet, der Flash bleibt separat")
        c.refresh(rig.ctx())
        assertEquals(1, c.overlay.lastRejectSeq, "nur neue Ablehnungen zählen")
        c.drag(rig.ctx(), 23f, 31f, 26f, 28f)
        assertEquals(2, c.overlay.lastRejectSeq)
    }

    @Test
    fun undoRefreshesTheOverlayAndRecordsARejectWithoutJournal() {
        val rig = ToolRig()
        val c = ToolController()
        val r = c.undo(rig.ctx())
        assertTrue(r.commands.isEmpty())
        assertNotNull(r.rejected)
        assertEquals(r.rejected, c.overlay.lastReject)
        assertEquals(1, c.overlay.lastRejectSeq)
    }

    @Test
    fun refreshWithoutChangesKeepsTheSameOverlayObject() {
        val rig = ToolRig()
        val c = woodController()
        c.pointer(PointerPhase.DOWN, 23f, 31f, rig.ctx())
        c.pointer(PointerPhase.MOVE, 26f, 28f, rig.ctx())
        val o = c.overlay
        assertNotNull(o.ghost)
        c.refresh(rig.ctx())
        c.refresh(rig.ctx())
        assertSame(o, c.overlay, "kein neues Overlay pro Frame, solange sich nichts ändert")
        c.pointer(PointerPhase.MOVE, 26.5f, 28f, rig.ctx())
        assertNotSame(o, c.overlay)
    }
}
