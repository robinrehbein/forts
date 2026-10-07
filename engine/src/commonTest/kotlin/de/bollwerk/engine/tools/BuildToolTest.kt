package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandValidator
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.rules.RuleTables
import de.bollwerk.engine.rules.RulesValidator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildToolTest {
    private fun tool(settings: ToolSettings = ToolSettings(), material: Int = RuleTables.WOOD) =
        DefaultBuildTool(settings).also { it.material = material }

    private fun BuildToolState.ghost(): GhostBeam = (this as BuildToolState.Previewing).ghost

    // ---- Start: Knoten, Balken-Teilung, frei ----

    @Test
    fun downSnapsToTheNearestOwnNodeWithinThePickRadius() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(23.4f, 31.3f, rig.ctx())
        val s = assertIs<BuildToolState.FirstNodeSelected>(t.state)
        assertEquals(rig.nref(rig.apex), s.nodeRef)
        assertEquals(-1L, s.beamRef)
        assertEquals(23f, s.x, 1e-5f)
        assertEquals(31f, s.y, 1e-5f)
        assertTrue(t.pressed)
    }

    @Test
    fun downPicksTheNearestNodeWhenSeveralAreInRange() {
        val rig = ToolRig()
        val a = rig.addAnchor(30f, 30f)
        val b = rig.addAnchor(30.8f, 30f)
        val t = tool()
        t.onDown(30.6f, 30f, rig.ctx(pick = 1f))
        assertEquals(rig.nref(b), assertIs<BuildToolState.FirstNodeSelected>(t.state).nodeRef)
        assertTrue(a != b)
    }

    @Test
    fun downOnABeamSplitsItAtTheNearestPoint() {
        val rig = ToolRig()
        val beam = rig.beamBetween(rig.n20, rig.apex)
        val t = tool()
        t.onDown(21.5f, 32.5f, rig.ctx())
        val s = assertIs<BuildToolState.FirstNodeSelected>(t.state)
        assertEquals(-1L, s.nodeRef)
        assertEquals(rig.bref(beam), s.beamRef)
        assertEquals(0.5f, s.beamT, 1e-4f)
        assertEquals(21.5f, s.x, 1e-4f)
        assertEquals(32.5f, s.y, 1e-4f)
    }

    @Test
    fun splitParameterIsClampedAwayFromTheEnds() {
        val rig = ToolRig()
        val beam = rig.beamBetween(rig.n20, rig.apex)
        val t = tool()
        // 0,95 m an der Kante der Beamlänge (4,24 m): nahe am Knoten n20, aber knapp außerhalb des Knoten-Radius 0,5
        t.onDown(20.35f, 33.65f, rig.ctx(pick = 0.45f))
        val s = assertIs<BuildToolState.FirstNodeSelected>(t.state)
        assertEquals(rig.bref(beam), s.beamRef)
        assertEquals(0.115f, s.beamT, 0.01f)
        t.cancel()
        t.onDown(20.1f, 33.9f, rig.ctx(pick = 0.1f))
        val s2 = assertIs<BuildToolState.FirstNodeSelected>(t.state)
        assertTrue(s2.beamT >= 0.08f - 1e-6f)
    }

    @Test
    fun nodeWinsOverBeamAtTheStart() {
        val rig = ToolRig()
        val t = tool()
        // zwischen Spitze und den drei Balken: der Knoten gewinnt
        t.onDown(23.1f, 31.2f, rig.ctx())
        assertEquals(rig.nref(rig.apex), assertIs<BuildToolState.FirstNodeSelected>(t.state).nodeRef)
    }

    @Test
    fun downOnEmptySpaceStartsNoGestureSoTheCameraCanPan() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(30f, 20f, rig.ctx())
        assertEquals(BuildToolState.Idle, t.state)
        assertFalse(t.pressed)
        assertNull(t.onUp(30f, 20f, rig.ctx()))
        val c = ToolController()
        c.selectTool(ToolSelection.Material(RuleTables.WOOD))
        assertFalse(c.pointer(PointerPhase.DOWN, 30f, 20f, rig.ctx()).consumed)
        assertFalse(c.pointer(PointerPhase.MOVE, 31f, 20f, rig.ctx()).consumed)
        assertFalse(c.pointer(PointerPhase.UP, 31f, 20f, rig.ctx()).consumed)
    }

    @Test
    fun enemyNodesAndBeamsAreNotStartTargets() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(100f, 34f, rig.ctx()) // Fundament von Spieler 1
        assertEquals(BuildToolState.Idle, t.state)
    }

    @Test
    fun doorsCannotBeSplit() {
        val rig = ToolRig()
        val a = rig.addAnchor(30f, 34f)
        val b = rig.addAnchor(30f, 30f)
        rig.addBeam(a, b, RuleTables.DOOR)
        val t = tool()
        t.onDown(30f, 32f, rig.ctx(pick = 0.3f))
        assertEquals(BuildToolState.Idle, t.state)
    }

    @Test
    fun freeStartIsAllowedWhenConnectionIsNotRequired() {
        val rig = ToolRig()
        val t = tool(ToolSettings().also { it.requireConnectedStart = false })
        t.onDown(30f, 20f, rig.ctx())
        val s = assertIs<BuildToolState.FirstNodeSelected>(t.state)
        assertEquals(-1L, s.nodeRef)
        assertEquals(-1L, s.beamRef)
        t.onMove(31f, 20f, rig.ctx())
        assertEquals(RejectReason.NOT_CONNECTED, t.state.ghost().reason)
    }

    // ---- Ende: Knoten, Balken, frei; Winkel- und Längenrasten ----

    @Test
    fun endSnapsToAnExistingNodeWithValidityLengthAndCost() {
        val rig = ToolRig()
        val other = rig.addAnchor(27.5f, 34f)
        val t = tool()
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(27.8f, 33.8f, rig.ctx())
        val g = t.state.ghost()
        assertEquals(rig.nref(other), g.snapNodeRef)
        assertEquals(-1L, g.snapBeamRef)
        assertEquals(27.5f, g.bx, 1e-5f)
        assertEquals(34f, g.by, 1e-5f)
        assertTrue(g.valid)
        assertNull(g.reason)
        assertEquals(5.408f, g.lengthM, 1e-3f)
        assertEquals(22f, g.cost, 1e-5f) // round(4 · 5,408)
        assertEquals(RuleTables.WOOD, g.materialId)
        assertFalse(g.angleSnapped)
        assertFalse(g.lengthSnapped)
    }

    @Test
    fun endOnABeamSplitsIt() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(24.5f, 34.1f, rig.ctx())
        val g = t.state.ghost()
        assertEquals(rig.bref(rig.ground01), g.snapBeamRef)
        assertEquals(-1L, g.snapNodeRef)
        assertEquals(0.5f, g.snapBeamT, 1e-3f)
        assertEquals(24.5f, g.bx, 1e-3f)
        assertEquals(34f, g.by, 1e-3f)
        assertTrue(g.valid)
        assertEquals(RuleChecksDist(23f, 31f, 24.5f, 34f), g.lengthM, 1e-3f)
    }

    private fun RuleChecksDist(ax: Float, ay: Float, bx: Float, by: Float): Float = kotlin.math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))

    @Test
    fun endNeverSplitsTheStartBeam() {
        val rig = ToolRig()
        val beam = rig.beamBetween(rig.n20, rig.apex)
        val t = tool()
        t.onDown(21.5f, 32.5f, rig.ctx())
        t.onMove(21.8f, 32.2f, rig.ctx(pick = 0.4f))
        assertEquals(-1L, t.state.ghost().snapBeamRef)
        assertTrue(rig.bref(beam) >= 0)
    }

    @Test
    fun freeEndSnapsToFifteenDegreeSteps() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(23f, 31f, rig.ctx())
        // fast waagerecht: -1,9° -> 0°
        t.onMove(26.1f, 30.9f, rig.ctx())
        val g0 = t.state.ghost()
        assertTrue(g0.angleSnapped)
        assertFalse(g0.lengthSnapped)
        assertEquals(31f, g0.by, 1e-4f)
        assertEquals(26.1f, g0.bx, 0.01f)
        // -16,7° -> -15°
        t.onMove(26f, 30.1f, rig.ctx())
        val g1 = t.state.ghost()
        assertTrue(g1.angleSnapped)
        val ang = FastTrig.atan2(g1.by - g1.ay, g1.bx - g1.ax)
        assertEquals(-15f * 0.017453292f, ang, 2e-3f)
        assertEquals(RuleChecksDist(23f, 31f, 26f, 30.1f), g1.lengthM, 0.01f)
        // 21,8°: weder 15° noch 30° in Reichweite -> kein Rasten, Ende genau am Finger
        t.onMove(26f, 29.8f, rig.ctx())
        val g2 = t.state.ghost()
        assertFalse(g2.angleSnapped)
        assertEquals(26f, g2.bx, 1e-5f)
        assertEquals(29.8f, g2.by, 1e-5f)
    }

    @Test
    fun angleSnapCanBeSwitchedOff() {
        val rig = ToolRig()
        val t = tool(ToolSettings().also { it.angleSnap = false })
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26.1f, 30.9f, rig.ctx())
        val g = t.state.ghost()
        assertFalse(g.angleSnapped)
        assertEquals(30.9f, g.by, 1e-5f)
    }

    @Test
    fun lengthSnapsToTheMaximumOfSixMetres() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(23f, 31f, rig.ctx())
        // waagerecht 5,5 m: Winkel 0° und Länge 6 m rasten beide
        t.onMove(28.5f, 31f, rig.ctx())
        val g = t.state.ghost()
        assertTrue(g.angleSnapped)
        assertTrue(g.lengthSnapped)
        assertEquals(6f, g.lengthM, 1e-3f)
        assertEquals(29f, g.bx, 1e-3f)
        assertEquals(31f, g.by, 1e-3f)
        assertTrue(g.valid)
        assertEquals(24f, g.cost, 1e-5f)
        // Rasten nur im Fenster (max − 0,6, max + 1,3): 5,3 m bleibt, 7,4 m bleibt (und ist zu lang)
        t.onMove(28.3f, 31f, rig.ctx())
        assertFalse(t.state.ghost().lengthSnapped)
        t.onMove(30.4f, 31f, rig.ctx())
        val long = t.state.ghost()
        assertFalse(long.lengthSnapped)
        assertEquals(RejectReason.TOO_LONG, long.reason)
        assertEquals(7.4f, long.lengthM, 1e-3f)
    }

    @Test
    fun lengthSnapWithoutAngleSnapKeepsTheDirection() {
        val rig = ToolRig()
        val t = tool(ToolSettings().also { it.angleSnap = false })
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(27f, 26.8f, rig.ctx()) // 5,8 m
        val g = t.state.ghost()
        assertTrue(g.lengthSnapped)
        assertFalse(g.angleSnapped)
        assertEquals(6f, g.lengthM, 1e-3f)
        // gleiche Richtung wie Start -> Finger
        val cross = (g.bx - 23f) * (26.8f - 31f) - (g.by - 31f) * (27f - 23f)
        assertTrue(abs(cross) < 1e-3f, "direction kept, cross=$cross")
        assertTrue(g.valid)
    }

    @Test
    fun endNearTheGroundSnapsOntoIt() {
        val rig = ToolRig()
        val t = tool(ToolSettings().also { it.angleSnap = false })
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(28f, 33.8f, rig.ctx()) // rechts vom Fort: keine Knoten/Balken in Reichweite
        val g = t.state.ghost()
        assertEquals(34f - 0.12f, g.by, 1e-5f)
        assertTrue(g.valid)
    }

    // ---- Ablehnungsgründe (echter Validator) ----

    @Test
    fun invalidReasonsComeFromTheSimValidator() {
        val rig = ToolRig()
        val ctx = rig.ctx()
        val t = tool(ToolSettings().also { it.angleSnap = false })

        // zu lang
        t.onDown(23f, 31f, ctx)
        t.onMove(23f, 23f, ctx)
        assertEquals(RejectReason.TOO_LONG, t.state.ghost().reason)
        assertFalse(t.state.ghost().valid)
        t.cancel()

        // unter dem Gelände
        t.onDown(23f, 31f, ctx)
        t.onMove(23f, 36.4f, rig.ctx(pick = 0.3f))
        assertEquals(RejectReason.BLOCKED_BY_TERRAIN, t.state.ghost().reason)
        t.cancel()

        // existiert schon (Spitze -> n23)
        t.onDown(23f, 31f, ctx)
        t.onMove(23.1f, 33.9f, ctx)
        assertEquals(RejectReason.DUPLICATE_BEAM, t.state.ghost().reason)
        t.cancel()

        // zu kurz (vom freistehenden Anker auf dem Boden 0,32 m weit)
        rig.addAnchor(35f, 34f)
        t.onDown(35f, 34f, ctx)
        t.onMove(35.3f, 33.6f, rig.ctx(pick = 0.2f))
        assertEquals(RejectReason.TOO_SHORT, t.state.ghost().reason)
        t.cancel()

        // außerhalb der Bauzone (Zone 0..39)
        rig.addAnchor(38f, 34f)
        t.onDown(38f, 34f, rig.ctx(pick = 0.2f))
        t.onMove(40.5f, 32f, rig.ctx(pick = 0.2f))
        assertEquals(RejectReason.OUT_OF_BUILD_ZONE, t.state.ghost().reason)
    }

    @Test
    fun notConnectedWhenBothEndsAreFree() {
        val rig = ToolRig()
        val t = tool(ToolSettings().also { it.requireConnectedStart = false })
        t.onDown(30f, 20f, rig.ctx())
        t.onMove(32f, 20f, rig.ctx())
        assertEquals(RejectReason.NOT_CONNECTED, t.state.ghost().reason)
    }

    @Test
    fun notEnoughMetalIsReportedWithLengthAndCost() {
        val rig = ToolRig(metal = 10f)
        val t = tool(ToolSettings().also { it.angleSnap = false })
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 29f, rig.ctx())
        val g = t.state.ghost()
        assertEquals(RejectReason.NOT_ENOUGH_METAL, g.reason)
        assertEquals(14f, g.cost, 1e-5f) // round(4 · 3,606)
        assertEquals(3.606f, g.lengthM, 1e-3f)
        assertNull(t.onUp(26f, 29f, rig.ctx()))
        assertEquals(RejectReason.NOT_ENOUGH_METAL, t.lastReject)
    }

    @Test
    fun lockedTechMaterialIsRejected() {
        val rig = ToolRig()
        val t = tool(material = RuleTables.ARMOUR)
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 31f, rig.ctx())
        assertEquals(RejectReason.LOCKED_TECH, t.state.ghost().reason)
        assertNull(t.onUp(26f, 31f, rig.ctx()))
        assertEquals(RejectReason.LOCKED_TECH, t.lastReject)
    }

    @Test
    fun unknownMaterialIsRejectedWithoutCrashing() {
        val rig = ToolRig()
        val t = tool(material = 99)
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 31f, rig.ctx())
        assertEquals(RejectReason.UNKNOWN_CONTENT, t.state.ghost().reason)
        assertEquals(0f, t.state.ghost().cost)
    }

    @Test
    fun ghostUsesTheValidatorOfTheContext() {
        val rig = ToolRig()
        val ctx = ToolContext(rig.state, 0, 1f, CommandValidator { _, _ -> RejectReason.NOT_YOUR_TURN })
        val t = tool()
        t.onDown(23f, 31f, ctx)
        t.onMove(26f, 31f, ctx)
        assertEquals(RejectReason.NOT_YOUR_TURN, t.state.ghost().reason)
    }

    @Test
    fun ghostReasonEqualsWhatTheSimValidatorSaysAboutTheCommand() {
        val rig = ToolRig(metal = 10f)
        val t = tool()
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 31f, rig.ctx())
        val g = t.state.ghost()
        val cmd = Command.PlaceBeam(
            rig.state.tick, 0, aNodeRef = rig.nref(rig.apex), bX = g.bx, bY = g.by, materialId = RuleTables.WOOD,
        )
        assertEquals(RulesValidator.validate(rig.state, cmd), g.reason)
        assertEquals(RejectReason.NOT_ENOUGH_METAL, g.reason)
    }

    // ---- Commit / Abbruch ----

    @Test
    fun upCommitsAValidGhostAndTheSimAcceptsItWithTheGhostCost() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(23.2f, 31.2f, rig.ctx())
        t.onMove(26f, 28f, rig.ctx())
        val ghost = t.state.ghost()
        val cmd = t.onUp(26f, 28f, rig.ctx())
        val place = assertIs<Command.PlaceBeam>(cmd)
        assertEquals(rig.nref(rig.apex), place.aNodeRef)
        assertEquals(RuleTables.WOOD, place.materialId)
        assertEquals(ghost.bx, place.bX, 1e-6f)
        assertEquals(ghost.by, place.bY, 1e-6f)
        assertEquals(BuildToolState.Idle, t.state)
        val before = rig.metal
        rig.ok(place)
        assertEquals(ghost.cost, before - rig.metal, 1e-3f)
        assertNull(t.lastReject)
    }

    @Test
    fun upCommitsASplitStartAndEnd() {
        val rig = ToolRig()
        val beams = rig.state.beams.aliveCount
        val t = tool()
        t.onDown(21.5f, 32.5f, rig.ctx()) // Mitte von n20-Spitze
        t.onMove(24.5f, 34.05f, rig.ctx()) // Mitte von n23-n26
        val g = t.state.ghost()
        assertTrue(g.valid, "reason=${g.reason}")
        val cmd = assertIs<Command.PlaceBeam>(t.onUp(24.5f, 34.05f, rig.ctx()))
        assertEquals(rig.bref(rig.beamBetween(rig.n20, rig.apex)), cmd.aBeamRef)
        assertEquals(rig.bref(rig.ground01), cmd.bBeamRef)
        rig.ok(cmd)
        assertEquals(beams + 3, rig.state.beams.aliveCount) // 2 Teilungen (+1 je) + neuer Balken
    }

    @Test
    fun upOnAnInvalidGhostSendsNothingAndKeepsTheReason() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(23f, 23f, rig.ctx())
        assertNull(t.onUp(23f, 23f, rig.ctx()))
        assertEquals(RejectReason.TOO_LONG, t.lastReject)
        assertEquals(BuildToolState.Idle, t.state)
    }

    @Test
    fun aTapWithoutDraggingBuildsNothing() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(23f, 31f, rig.ctx())
        assertIs<BuildToolState.FirstNodeSelected>(t.state)
        t.onMove(23.05f, 31.05f, rig.ctx()) // Zittern unter der Toleranz
        assertIs<BuildToolState.FirstNodeSelected>(t.state)
        assertNull(t.onUp(23.05f, 31.05f, rig.ctx()))
        assertEquals(BuildToolState.Idle, t.state)
        assertNull(t.lastReject)
    }

    @Test
    fun cancelDropsTheGestureAndTheFollowingUpDoesNothing() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 29f, rig.ctx())
        assertIs<BuildToolState.Previewing>(t.state)
        t.cancel()
        assertEquals(BuildToolState.Idle, t.state)
        assertTrue(t.snaps.isEmpty())
        assertNull(t.onUp(26f, 29f, rig.ctx()))
        t.onMove(27f, 29f, rig.ctx())
        assertEquals(BuildToolState.Idle, t.state)
    }

    @Test
    fun staleStartNodeRejectsWithoutCrashing() {
        val rig = ToolRig()
        val anchor = rig.addAnchor(30f, 34f)
        val t = tool()
        t.onDown(30f, 34f, rig.ctx())
        t.onMove(33f, 32f, rig.ctx())
        assertTrue(t.state.ghost().valid)
        rig.state.nodes.release(anchor)
        rig.step(); rig.step(); rig.step()
        t.onMove(33.1f, 32f, rig.ctx())
        assertEquals(RejectReason.STALE_TARGET, t.state.ghost().reason)
        assertNull(t.onUp(33.1f, 32f, rig.ctx()))
    }

    // ---- Kettenmodus ----

    @Test
    fun chainModeContinuesAtTheNewEndAndEndsOnATapAtTheAnchor() {
        val rig = ToolRig()
        val settings = ToolSettings().also { it.chainMode = true; it.angleSnap = false }
        val t = tool(settings)
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 28f, rig.ctx())
        val first = assertIs<Command.PlaceBeam>(t.onUp(26f, 28f, rig.ctx()))
        assertTrue(t.chainArmed)
        val armed = assertIs<BuildToolState.FirstNodeSelected>(t.state)
        assertEquals(26f, armed.x, 1e-5f)
        assertEquals(28f, armed.y, 1e-5f)
        rig.ok(first)
        val end = rig.nodeAt(26f, 28f)
        assertTrue(end >= 0)

        // nächster Balken: Finger irgendwo, Start ist der Kettenanker (jetzt echter Knoten)
        t.onDown(29f, 28f, rig.ctx())
        val prev = assertIs<BuildToolState.Previewing>(t.state)
        assertEquals(rig.nref(end), prev.from.nodeRef)
        assertEquals(26f, prev.ghost.ax, 1e-5f)
        assertEquals(28f, prev.ghost.ay, 1e-5f)
        assertTrue(prev.ghost.valid, "reason=${prev.ghost.reason}")
        t.onMove(29.5f, 27f, rig.ctx())
        val second = assertIs<Command.PlaceBeam>(t.onUp(29.5f, 27f, rig.ctx()))
        assertEquals(rig.nref(end), second.aNodeRef)
        rig.ok(second)
        assertTrue(t.chainArmed)

        // Tipp auf den Anker beendet die Kette
        t.onDown(29.5f, 27f, rig.ctx())
        assertNull(t.onUp(29.5f, 27f, rig.ctx()))
        assertFalse(t.chainArmed)
        assertEquals(BuildToolState.Idle, t.state)
    }

    @Test
    fun chainSurvivesAnInvalidReleaseAndAPointerCancel() {
        val rig = ToolRig()
        val settings = ToolSettings().also { it.chainMode = true; it.angleSnap = false }
        val t = tool(settings)
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 28f, rig.ctx())
        rig.ok(t.onUp(26f, 28f, rig.ctx()))
        t.onDown(40f, 20f, rig.ctx()) // zu weit weg
        t.onMove(40f, 12f, rig.ctx())
        assertNull(t.onUp(40f, 12f, rig.ctx()))
        assertNotNull(t.lastReject)
        assertTrue(t.chainArmed)
        t.onDown(28f, 25f, rig.ctx())
        t.cancel()
        assertTrue(t.chainArmed, "ein Abbruch der Geste (zweiter Finger) behält die Kette")
        t.reset()
        assertFalse(t.chainArmed)
    }

    @Test
    fun chainModeOffDoesNotArm() {
        val rig = ToolRig()
        val t = tool()
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 28f, rig.ctx())
        assertNotNull(t.onUp(26f, 28f, rig.ctx()))
        assertFalse(t.chainArmed)
        assertEquals(BuildToolState.Idle, t.state)
    }

    // ---- Snap-Markierungen ----

    @Test
    fun snapMarksShowStartAndSnappedEnd() {
        val rig = ToolRig()
        val other = rig.addAnchor(27.5f, 34f)
        val t = tool()
        t.onDown(23f, 31f, rig.ctx())
        assertEquals(1, t.snaps.size)
        assertEquals(rig.nref(rig.apex), t.snaps[0].nodeRef)
        t.onMove(27.8f, 33.8f, rig.ctx())
        assertEquals(2, t.snaps.size)
        assertEquals(rig.nref(other), t.snaps[1].nodeRef)
        assertTrue(t.snaps[1].active)
        t.onMove(26f, 28f, rig.ctx())
        assertEquals(1, t.snaps.size)
    }

    // ---- Kette unter Physik / Latenz ----

    private fun chainSettings() = ToolSettings().also { it.chainMode = true; it.angleSnap = false }

    @Test
    fun chainAnchorFollowsTheNewNodeWhenPhysicsMovesIt() {
        val rig = ToolRig(physics = true)
        val t = tool(chainSettings())
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(28f, 31f, rig.ctx())
        val first = assertIs<Command.PlaceBeam>(t.onUp(28f, 31f, rig.ctx()))
        rig.ok(first)
        val before = rig.nodeAt(28f, 31f)
        assertTrue(before >= 0)
        // die Struktur hängt durch: der neue Endknoten wandert, der Anker muss ihm folgen
        for (i in 0 until 30) { rig.step(); t.refresh(rig.ctx()) }
        val nowX = rig.state.nodes.x[before]
        val nowY = rig.state.nodes.y[before]
        assertTrue(abs(nowX - 28f) + abs(nowY - 31f) > 0.05f, "Physik bewegt den Knoten (sonst prüft der Test nichts)")
        val anchor = assertIs<BuildToolState.FirstNodeSelected>(t.state)
        assertEquals(rig.nref(before), anchor.nodeRef)
        assertEquals(nowX, anchor.x, 1e-3f)

        t.onDown(nowX + 2f, nowY - 1f, rig.ctx())
        val prev = assertIs<BuildToolState.Previewing>(t.state)
        assertTrue(prev.ghost.valid, "reason=${prev.ghost.reason}")
        assertEquals(nowX, prev.ghost.ax, 1e-3f)
        t.onMove(nowX + 2f, nowY - 1.5f, rig.ctx())
        val nodesBefore = rig.state.nodes.size
        val second = assertIs<Command.PlaceBeam>(t.onUp(nowX + 2f, nowY - 1.5f, rig.ctx()))
        assertEquals(rig.nref(before), second.aNodeRef)
        rig.ok(second)
        assertEquals(1, rig.state.nodes.size - nodesBefore, "nur der neue Endknoten, kein doppelter Anfangsknoten")
    }

    @Test
    fun chainEndSnappedToAnExistingNodeKeepsItsRef() {
        val rig = ToolRig(physics = true)
        val other = rig.addAnchor(28f, 34f)
        val t = tool(chainSettings())
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(27.9f, 33.9f, rig.ctx())
        val first = assertIs<Command.PlaceBeam>(t.onUp(27.9f, 33.9f, rig.ctx()))
        assertEquals(rig.nref(other), first.bNodeRef)
        val anchor = assertIs<BuildToolState.FirstNodeSelected>(t.state)
        assertEquals(rig.nref(other), anchor.nodeRef, "der Anker ist der Knoten selbst, keine freie Position")
        rig.ok(first)
        rig.run(20)
        t.refresh(rig.ctx())
        t.onDown(31f, 31f, rig.ctx())
        assertTrue(t.state.ghost().valid)
        assertEquals(rig.nref(other), (t.state as BuildToolState.Previewing).from.nodeRef)
    }

    @Test
    fun chainWaitsForALateCommandWithAPendingGhost() {
        val rig = ToolRig()
        val t = tool(chainSettings())
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 28f, rig.ctx())
        val first = assertIs<Command.PlaceBeam>(t.onUp(26f, 28f, rig.ctx()))
        // Eingabe-Verzögerung: der nächste Zug beginnt, bevor das erste PlaceBeam ausgeführt wurde
        t.onDown(29f, 28f, rig.ctx())
        t.onMove(29.5f, 27f, rig.ctx())
        val g = t.state.ghost()
        assertTrue(g.pending)
        assertFalse(g.valid)
        assertNull(g.reason, "ausstehend ist weder grün noch rot")
        assertNull(t.onUp(29.5f, 27f, rig.ctx()))
        assertNull(t.lastReject)
        assertTrue(t.chainArmed, "die Kette bleibt")
        // jetzt führt die Sim das erste Command aus; der Anker findet den neuen Knoten
        rig.ok(first)
        t.refresh(rig.ctx())
        val end = rig.nodeAt(26f, 28f)
        assertEquals(rig.nref(end), assertIs<BuildToolState.FirstNodeSelected>(t.state).nodeRef)
        t.onDown(29f, 28f, rig.ctx())
        t.onMove(29.5f, 27f, rig.ctx())
        assertFalse(t.state.ghost().pending)
        assertTrue(t.state.ghost().valid)
        assertEquals(rig.nref(end), assertIs<Command.PlaceBeam>(t.onUp(29.5f, 27f, rig.ctx())).aNodeRef)
    }

    @Test
    fun chainEndsWhenTheCommandNeverCreatesTheNode() {
        val rig = ToolRig()
        val t = tool(chainSettings())
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 28f, rig.ctx())
        assertIs<Command.PlaceBeam>(t.onUp(26f, 28f, rig.ctx())) // wird nie gesendet
        t.refresh(rig.ctx())
        assertTrue(t.chainArmed)
        rig.run(ToolConst.CHAIN_PENDING_TICKS + 2)
        t.refresh(rig.ctx())
        assertFalse(t.chainArmed)
        assertEquals(BuildToolState.Idle, t.state)
    }

    @Test
    fun chainEndsWhenTheAnchorNodeIsDestroyed() {
        val rig = ToolRig()
        val other = rig.addAnchor(28f, 34f)
        val t = tool(chainSettings())
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(27.9f, 33.9f, rig.ctx())
        rig.ok(t.onUp(27.9f, 33.9f, rig.ctx()))
        assertTrue(t.chainArmed)
        rig.state.nodes.release(other)
        rig.run(3)
        t.refresh(rig.ctx())
        assertFalse(t.chainArmed)
    }

    @Test
    fun chainSurvivesCancelWithoutAFinger() {
        val rig = ToolRig()
        val t = tool(chainSettings())
        t.onDown(23f, 31f, rig.ctx())
        t.onMove(26f, 28f, rig.ctx())
        rig.ok(t.onUp(26f, 28f, rig.ctx()))
        assertFalse(t.pressed)
        t.cancel()
        assertTrue(t.chainArmed, "App-Pause ohne Finger beendet die Kette nicht")
        assertIs<BuildToolState.FirstNodeSelected>(t.state)
    }

    // ---- Rasten: Boden + Länge, Planner-Geometrie ----

    @Test
    fun groundSnapNeverBreaksTheLengthSnap() {
        val rig = ToolRig()
        val a = rig.addAnchor(30f, 32.7f)
        val t = tool(ToolSettings().also { it.angleSnap = false })
        t.onDown(30f, 32.7f, rig.ctx())
        assertEquals(rig.nref(a), assertIs<BuildToolState.FirstNodeSelected>(t.state).nodeRef)
        // 5,9 m bei 8° nach unten: Längenrasten (6 m) und Bodenrasten (y = 34 − Knotenradius) greifen beide
        t.onMove(30f + 5.85f, 32.7f + 0.82f, rig.ctx())
        val g = t.state.ghost()
        assertTrue(g.lengthSnapped)
        assertTrue(g.lengthM <= rig.state.config.maxBeamLength + 0.01f, "length=${g.lengthM}")
        assertTrue(g.valid, "reason=${g.reason}")
        assertEquals(34f - rig.state.config.nodeRadius, g.by, 1e-3f, "Bodenrasten bleibt")
    }

    @Test
    fun groundSnapThatWouldMakeTheBeamTooLongIsProjectedOntoTheMaxCircle() {
        val rig = ToolRig()
        rig.addAnchor(30f, 30.5f)
        val t = tool(ToolSettings().also { it.angleSnap = false })
        t.onDown(30f, 30.5f, rig.ctx())
        // 4,9 m flach, aber nur 0,45 m über dem Boden: Bodenrasten würde 3,4 m Höhe dazugeben -> länger als 6 m
        t.onMove(34.9f, 33.55f, rig.ctx())
        val g = t.state.ghost()
        assertTrue(g.lengthM <= rig.state.config.maxBeamLength + 0.01f, "length=${g.lengthM}")
    }

    @Test
    fun ghostUsesThePlannersResolvedEndForAFreeEndInsideTheMergeRadius() {
        val rig = ToolRig()
        val other = rig.addAnchor(28f, 30f)
        val t = tool(ToolSettings().also { it.angleSnap = false })
        t.onDown(23f, 31f, rig.ctx(pick = 0.02f))
        // Finger 3 cm neben dem Knoten, Fangradius 2 cm: kein Picker-Treffer, aber der Planner verschmilzt (Radius 5 cm)
        t.onMove(28.03f, 30f, rig.ctx(pick = 0.02f))
        val g = t.state.ghost()
        assertEquals(rig.nref(other), g.snapNodeRef)
        assertEquals(28f, g.bx, 1e-5f)
        assertEquals(30f, g.by, 1e-5f)
        assertEquals(RuleChecksDist(23f, 31f, 28f, 30f), g.lengthM, 1e-4f)
        val cmd = assertIs<Command.PlaceBeam>(t.onUp(28.03f, 30f, rig.ctx(pick = 0.02f)))
        assertEquals(rig.nref(other), cmd.bNodeRef)
    }
}
