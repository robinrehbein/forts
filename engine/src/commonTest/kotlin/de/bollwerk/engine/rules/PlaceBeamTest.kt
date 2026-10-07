package de.bollwerk.engine.rules

import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.sim.UndoKind
import de.bollwerk.engine.rules.RuleTables.ARMOUR
import de.bollwerk.engine.rules.RuleTables.DOOR
import de.bollwerk.engine.rules.RuleTables.METAL
import de.bollwerk.engine.rules.RuleTables.ROPE
import de.bollwerk.engine.rules.RuleTables.WOOD
import de.bollwerk.engine.view.FxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaceBeamTest {
    private fun RulesRig.beam(
        from: Int, x: Float, y: Float, mat: Int = WOOD, player: Int = 0,
    ) = Command.PlaceBeam(tick, player, aNodeRef = nref(from), bX = x, bY = y, materialId = mat)

    @Test
    fun placesBeamFromNodeToFreePositionAndChargesLengthTimesCost() {
        val rig = RulesRig()
        val apex = rig.apex
        val nodesBefore = rig.state.nodes.aliveCount
        // senkrecht nach oben: 4,5 m Holz = 18 ⚙ (Bauen-Mockup der Stil-Bibel)
        rig.ok(rig.beam(apex, 23f, 26.5f))
        assertEquals(400f - 18f, rig.player().metal, 1e-3f)
        val top = rig.nodeAt(23f, 26.5f)
        assertTrue(top >= 0)
        assertEquals(nodesBefore + 1, rig.state.nodes.aliveCount)
        val b = rig.beamBetween(apex, top)
        assertTrue(b >= 0)
        assertEquals(WOOD, rig.state.beams.materialOf[b])
        assertEquals(4.5f, rig.state.beams.restLen[b], 1e-3f)
        assertEquals(100f, rig.state.beams.hpOf[b])
        assertEquals(0, rig.state.beams.ownerOf[b])
        assertFalse(rig.state.nodes.isAnchored(top))
        assertTrue(rig.state.topologyDirty)
        assertEquals(1, rig.player().undoCount)
        assertTrue(rig.fx.any { it is FxEvent.BeamPlaced && it.materialId == WOOD })
    }

    @Test
    fun costScalesWithMaterial() {
        val rig = RulesRig()
        rig.ok(rig.beam(rig.apex, 23f, 27f, METAL)) // 4 m · 10
        assertEquals(400f - 40f, rig.player().metal, 1e-3f)
        val r2 = RulesRig()
        r2.ok(r2.beam(r2.apex, 23f, 27f, ROPE)) // 4 m · 2, Seil bekommt 1,04 Durchhang
        assertEquals(400f - 8f, r2.player().metal, 1e-3f)
        val b = r2.beamBetween(r2.apex, r2.nodeAt(23f, 27f))
        assertEquals(4.16f, r2.state.beams.restLen[b], 1e-3f)
    }

    @Test
    fun spendsExactlyTheRemainingMetal() {
        val rig = RulesRig(metal = 16f)
        rig.ok(rig.beam(rig.apex, 23f, 27f)) // 4 m · 4 = 16
        assertEquals(0f, rig.player().metal, 1e-3f)
    }

    // ---- negative Fälle: jeder Grund ----

    @Test
    fun rejectsNotEnoughMetal() {
        val rig = RulesRig(metal = 15.9f)
        rig.rejects(RejectReason.NOT_ENOUGH_METAL, rig.beam(rig.apex, 23f, 27f))
        assertEquals(15.9f, rig.player().metal, 1e-4f)
        assertEquals(0, rig.player().undoCount)
    }

    @Test
    fun rejectsTooLong() {
        val rig = RulesRig()
        rig.rejects(RejectReason.TOO_LONG, rig.beam(rig.apex, 23f, 24.9f)) // 6,1 m
        rig.ok(rig.beam(rig.apex, 23f, 25f)) // genau 6 m
    }

    @Test
    fun rejectsTooShort() {
        val rig = RulesRig()
        rig.rejects(RejectReason.TOO_SHORT, rig.beam(rig.apex, 23f, 30.7f)) // 0,3 m < 0,5 m
        rig.ok(rig.beam(rig.apex, 23f, 30.5f)) // genau Mindestlänge
    }

    @Test
    fun rejectsOutsideBuildZone() {
        val rig = RulesRig()
        val edge = rig.addAnchor(38f, 34f)
        rig.rejects(RejectReason.OUT_OF_BUILD_ZONE, rig.beam(edge, 41f, 34f))
        rig.ok(rig.beam(edge, 39f, 33f))
    }

    @Test
    fun rejectsEndsBelowTheTerrain() {
        val rig = RulesRig()
        rig.rejects(RejectReason.BLOCKED_BY_TERRAIN, rig.beam(rig.n26, 28f, 36f))
    }

    @Test
    fun rejectsBeamCrossingAHill() {
        val hill = Terrain.fromPolyline(floatArrayOf(-40f, 28f, 30f, 32f, 160f), floatArrayOf(34f, 34f, 28f, 34f, 34f))
        val rig = RulesRig(map = RuleTables.map(hill))
        // beide Enden über/auf dem Boden, aber der Balken läuft durch den Hügel
        rig.rejects(RejectReason.BLOCKED_BY_TERRAIN, rig.beam(rig.n26, 31.9f, 33.9f))
        // darüber hinweg geht es
        rig.ok(rig.beam(rig.apex, 27f, 29f))
    }

    @Test
    fun rejectsDuplicateBeam() {
        val rig = RulesRig()
        val cmd = Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(rig.n23), bNodeRef = rig.nref(rig.n26), materialId = WOOD)
        rig.rejects(RejectReason.DUPLICATE_BEAM, cmd)
        // Gegenrichtung ebenfalls
        rig.rejects(RejectReason.DUPLICATE_BEAM, Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(rig.n26), bNodeRef = rig.nref(rig.n23), materialId = METAL))
        // andere, noch nicht verbundene Knoten: Balken Spitze → Fundament links? (existiert) / neuer Knoten → Knoten geht
        val n38 = rig.addAnchor(25f, 34f)
        rig.ok(Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(rig.apex), bNodeRef = rig.nref(n38), materialId = WOOD))
    }

    @Test
    fun duplicateIsDetectedWithinTheSameTick() {
        val rig = RulesRig()
        val a = rig.nref(rig.n26)
        val x = rig.addAnchor(29f, 34f)
        val c1 = Command.PlaceBeam(rig.tick, 0, aNodeRef = a, bNodeRef = rig.nref(x), materialId = WOOD)
        assertEquals(listOf(CommandResult.Accepted, CommandResult.Rejected(RejectReason.DUPLICATE_BEAM)), rig.send(c1, c1))
    }

    @Test
    fun rejectsNotConnected() {
        val rig = RulesRig()
        // beide Enden frei
        rig.rejects(
            RejectReason.NOT_CONNECTED,
            Command.PlaceBeam(rig.tick, 0, aX = 10f, aY = 30f, bX = 12f, bY = 30f, materialId = WOOD),
        )
        // Trümmer-Knoten zählen nicht als Struktur
        rig.state.nodes.flags[rig.apex] = rig.state.nodes.flags[rig.apex] or NodeFlags.DEBRIS
        rig.rejects(RejectReason.NOT_CONNECTED, rig.beam(rig.apex, 23f, 27f))
        // ... und Trümmer-Balken lassen sich nicht teilen
        val b = rig.beamBetween(rig.n23, rig.apex)
        rig.state.beams.flags[b] = rig.state.beams.flags[b] or BeamFlags.DEBRIS
        rig.rejects(
            RejectReason.NOT_CONNECTED,
            Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(b), aBeamT = 0.5f, bX = 21f, bY = 28f, materialId = WOOD),
        )
    }

    @Test
    fun rejectsForeignStructure() {
        val rig = RulesRig()
        val enemyNode = rig.nodeAt(97f, 34f)
        assertTrue(enemyNode >= 0)
        rig.rejects(RejectReason.NOT_OWNER, Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(enemyNode), bX = 90f, bY = 30f, materialId = WOOD))
        rig.rejects(RejectReason.NOT_OWNER, Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(rig.apex), bNodeRef = rig.nref(enemyNode), materialId = WOOD))
        val enemyBeam = rig.beamBetween(enemyNode, rig.nodeAt(100f, 34f))
        rig.rejects(RejectReason.NOT_OWNER, Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(enemyBeam), aBeamT = 0.5f, bX = 98f, bY = 30f, materialId = WOOD))
    }

    @Test
    fun rejectsLockedMaterialThenAllowsAfterTech() {
        val rig = RulesRig(metal = 1000f)
        rig.rejects(RejectReason.LOCKED_TECH, rig.beam(rig.apex, 23f, 27f, ARMOUR))
        // Panzer braucht das Upgrade-Zentrum; im Bau reicht nicht
        val up = rig.addDevice(RuleTables.UPGRADE, rig.ground01, 0.5f)
        rig.state.devices.buildTicks[up] = 100
        rig.run(1)
        rig.rejects(RejectReason.STILL_BUILDING, rig.beam(rig.apex, 23f, 27f, ARMOUR))
        rig.run(101)
        rig.ok(rig.beam(rig.apex, 23f, 27f, ARMOUR)) // 4 m · 18
        assertEquals(1000f - 72f, rig.player().metal, 1e-3f) // der Reaktor liefert Energie, kein Metall
    }

    @Test
    fun rejectsUnknownMaterialAndStaleRefsAndNonFinite() {
        val rig = RulesRig()
        rig.rejects(RejectReason.UNKNOWN_CONTENT, rig.beam(rig.apex, 23f, 27f, mat = 99))
        rig.rejects(RejectReason.STALE_TARGET, Command.PlaceBeam(rig.tick, 0, aNodeRef = (99L shl 32) or 3L, bX = 23f, bY = 27f, materialId = WOOD))
        rig.rejects(RejectReason.INVALID_TARGET, rig.beam(rig.apex, Float.NaN, 27f))
        // Ende außerhalb der Welt
        rig.rejects(RejectReason.INVALID_TARGET, rig.beam(rig.apex, 23f, -200f))
        // beide Enden derselbe Knoten
        rig.rejects(RejectReason.INVALID_TARGET, Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(rig.apex), bNodeRef = rig.nref(rig.apex), materialId = WOOD))
    }

    // ---- Knoten-Verschmelzung ----

    @Test
    fun freeEndNearOwnNodeReusesIt() {
        val rig = RulesRig()
        rig.addAnchor(25f, 34f)
        val before = rig.state.nodes.aliveCount
        val b = Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(rig.apex), bX = 25.02f, bY = 33.98f, materialId = WOOD)
        // (25, 34) ist ein Fundament; 0,03 m daneben (< 0,05 m) verschmilzt
        rig.ok(b)
        assertEquals(before, rig.state.nodes.aliveCount)
        // zweiter identischer Versuch ist ein Duplikat
        rig.rejects(RejectReason.DUPLICATE_BEAM, b)
    }

    @Test
    fun freeEndBeyondMergeRadiusCreatesNewNode() {
        val rig = RulesRig()
        val before = rig.state.nodes.aliveCount
        rig.ok(Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(rig.apex), bX = 26.2f, bY = 33.8f, materialId = WOOD))
        assertEquals(before + 1, rig.state.nodes.aliveCount)
    }

    @Test
    fun secondCommandInTheSameTickBuildsOnTheFirst() {
        val rig = RulesRig()
        val before = rig.state.nodes.aliveCount
        val c1 = rig.beam(rig.apex, 23f, 27f)
        // beginnt an freien Koordinaten des Endes von c1 (existiert nur wegen c1)
        val c2 = Command.PlaceBeam(rig.tick, 0, aX = 23f, aY = 27f, bX = 27f, bY = 27f, materialId = WOOD)
        assertEquals(listOf(CommandResult.Accepted, CommandResult.Accepted), rig.send(c1, c2))
        assertEquals(before + 2, rig.state.nodes.aliveCount) // (23, 27) wird wiederverwendet, (27, 27) ist neu
        assertEquals(400f - 32f, rig.player().metal, 1e-3f)
    }

    // ---- Balken teilen ----

    @Test
    fun endOnBeamSplitsItIntoTwoHalvesKeepingState() {
        val rig = RulesRig()
        val target = rig.beamBetween(rig.n20, rig.apex) // 20,34 → 23,31
        rig.state.beams.hpOf[target] = 60f
        rig.state.beams.fireOf[target] = 0.3f
        rig.state.beams.fuelOf[target] = 0.7f
        val rest = rig.state.beams.restLen[target]
        val tex = rig.state.beams.texOffset[target]
        val ref = rig.bref(target)
        val uid = rig.state.beams.uidOf[target]
        val cmd = Command.PlaceBeam(rig.tick, 0, aBeamRef = ref, aBeamT = 0.25f, bX = 17f, bY = 30f, materialId = WOOD)
        rig.ok(cmd)
        assertTrue(rig.state.beams.resolve(ref) < 0, "Original ist weg")
        val mid = rig.nodeAt(20f + 3f * 0.25f, 34f - 3f * 0.25f)
        assertTrue(mid >= 0)
        val h1 = rig.beamBetween(rig.n20, mid)
        val h2 = rig.beamBetween(mid, rig.apex)
        assertTrue(h1 >= 0 && h2 >= 0)
        val b = rig.state.beams
        assertEquals(rest * 0.25f, b.restLen[h1], 1e-4f)
        assertEquals(rest * 0.75f, b.restLen[h2], 1e-4f)
        for (h in intArrayOf(h1, h2)) {
            assertEquals(60f, b.hpOf[h]); assertEquals(100f, b.maxHpOf[h])
            assertEquals(0.3f, b.fireOf[h]); assertEquals(0.7f, b.fuelOf[h])
            assertEquals(WOOD, b.materialOf[h]); assertEquals(0, b.ownerOf[h])
        }
        assertEquals(tex, b.texOffset[h1], 1e-5f)
        assertEquals(tex + rest * 0.25f, b.texOffset[h2], 1e-5f)
        // Journal: ein Split mit zwei Hälften
        val e = rig.player().undoTop()!!
        assertEquals(UndoKind.BEAM, e.kind)
        assertEquals(1, e.splits.size)
        assertEquals(rig.state.nodes.ref(mid), e.splits[0].nodeRef)
        assertEquals(ref, e.splits[0].original.ref)
        val split = rig.fx.filterIsInstance<FxEvent.BeamSplit>().single()
        assertEquals(uid, split.beamUid)
        assertEquals(b.uidOf[h1], split.halfAUid)
        assertEquals(b.uidOf[h2], split.halfBUid)
        // neuer Balken vom Split-Knoten nach (17, 30)
        assertTrue(rig.beamBetween(mid, rig.nodeAt(17f, 30f)) >= 0)
    }

    @Test
    fun splitMovesDevicesToTheRightHalfWithConvertedParameter() {
        val rig = RulesRig()
        val target = rig.reactorBeam
        val reactor = rig.state.players[0].reactorDeviceId
        assertEquals(0.5f, rig.state.devices.tOf[reactor])
        // zweites Gerät bei t = 0,2 (links der Teilung)
        val other = rig.addDevice(RuleTables.TURBINE, target, 0.2f)
        rig.ok(Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(target), aBeamT = 0.4f, bX = 21f, bY = 29f, materialId = WOOD))
        val mid = rig.nodeAt(20f + 3f * 0.4f, 34f)
        val left = rig.beamBetween(rig.n20, mid)
        val right = rig.beamBetween(mid, rig.n23)
        assertEquals(left, rig.state.devices.beamId[other])
        assertEquals(0.2f / 0.4f, rig.state.devices.tOf[other], 1e-5f)
        assertEquals(right, rig.state.devices.beamId[reactor])
        assertEquals((0.5f - 0.4f) / 0.6f, rig.state.devices.tOf[reactor], 1e-5f)
    }

    @Test
    fun bothEndsCanSplitDifferentBeams() {
        val rig = RulesRig()
        val b1 = rig.beamBetween(rig.n20, rig.apex)
        val b2 = rig.beamBetween(rig.n26, rig.apex)
        val before = rig.state.beams.aliveCount
        rig.ok(
            Command.PlaceBeam(
                rig.tick, 0, aBeamRef = rig.bref(b1), aBeamT = 0.5f, bBeamRef = rig.bref(b2), bBeamT = 0.5f, materialId = WOOD,
            ),
        )
        // zwei Originale weg, je zwei Hälften, ein neuer Balken
        assertEquals(before - 2 + 4 + 1, rig.state.beams.aliveCount)
        assertEquals(2, rig.player().undoTop()!!.splits.size)
        assertTrue(rig.nodeAt(21.5f, 32.5f) >= 0 && rig.nodeAt(24.5f, 32.5f) >= 0)
    }

    @Test
    fun cannotUseTheSameBeamForBothEnds() {
        val rig = RulesRig()
        val b = rig.reactorBeam
        rig.rejects(
            RejectReason.INVALID_TARGET,
            Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(b), aBeamT = 0.3f, bBeamRef = rig.bref(b), bBeamT = 0.7f, materialId = WOOD),
        )
    }

    @Test
    fun splitParameterIsClampedAndTinyPiecesAreRejected() {
        val rig = RulesRig()
        val b = rig.beamBetween(rig.n20, rig.apex)
        // t = 0 wird auf 0,08 geklemmt (4,24 m · 0,08 = 0,34 m ≥ 0,25 m)
        rig.ok(Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(b), aBeamT = 0f, bX = 17f, bY = 31f, materialId = WOOD))
        assertTrue(rig.nodeAt(20f + 3f * 0.08f, 34f - 3f * 0.08f) >= 0)
        // ein winziger Balken (0,5 m) lässt sich nicht mehr teilen: 0,5 · 0,08 < 0,25
        val rig2 = RulesRig()
        val n = rig2.addAnchor(30f, 34f)
        val tiny = rig2.addBeam(n, rig2.addAnchor(30.5f, 34f))
        rig2.rejects(
            RejectReason.INVALID_TARGET,
            Command.PlaceBeam(rig2.tick, 0, aBeamRef = rig2.bref(tiny), aBeamT = 0.1f, bX = 30.2f, bY = 31f, materialId = WOOD),
        )
    }

    @Test
    fun doorsCannotBeSplit() {
        val rig = RulesRig()
        val door = rig.addBeam(rig.n26, rig.addAnchor(29f, 34f), DOOR)
        rig.rejects(
            RejectReason.INVALID_TARGET,
            Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(door), aBeamT = 0.5f, bX = 27.5f, bY = 30f, materialId = WOOD),
        )
    }

    @Test
    fun doorsAreBuiltWithTheHingeAtTheLowerEnd() {
        val rig = RulesRig(metal = 1000f)
        rig.ok(rig.beam(rig.apex, 23f, 27f, DOOR)) // nach oben: Ende B liegt höher → Scharnier an A
        val up = rig.beamBetween(rig.apex, rig.nodeAt(23f, 27f))
        assertEquals(0, rig.state.beams.flags[up] and BeamFlags.DOOR_HINGE_B)
        assertEquals(1000f - 56f, rig.player().metal, 1e-3f) // 4 m · 14 ⚙
        rig.ok(rig.beam(rig.n23, 23f, 28f).copy(aNodeRef = rig.nref(rig.nodeAt(23f, 27f)), bX = 25f, bY = 30f, materialId = DOOR)) // nach unten
        val down = rig.beamBetween(rig.nodeAt(23f, 27f), rig.nodeAt(25f, 30f))
        assertTrue((rig.state.beams.flags[down] and BeamFlags.DOOR_HINGE_B) != 0)
    }

    @Test
    fun placeBeamIsNotAppliedWhenValidatorRejects() {
        val rig = RulesRig(metal = 5f)
        val beams = rig.state.beams.aliveCount
        val nodes = rig.state.nodes.aliveCount
        rig.rejects(RejectReason.NOT_ENOUGH_METAL, rig.beam(rig.apex, 23f, 27f))
        assertEquals(beams, rig.state.beams.aliveCount)
        assertEquals(nodes, rig.state.nodes.aliveCount)
    }

    @Test
    fun previewFillsLengthAndCostEvenWhenRejected() {
        val rig = RulesRig(metal = 5f)
        val plan = BeamPlan()
        val reason = BeamPlanner.plan(rig.state, rig.beam(rig.apex, 23f, 27f), plan)
        assertEquals(RejectReason.NOT_ENOUGH_METAL, reason)
        assertEquals(4f, plan.length, 1e-4f)
        assertEquals(16f, plan.cost, 1e-3f)
        assertIs<CommandResult.Rejected>(rig.send(rig.beam(rig.apex, 23f, 27f)).single())
    }

    @Test
    fun rejectsDuplicateViaSplitOfABeamTheNodeAlreadyBelongsTo() {
        val rig = RulesRig()
        val onApex = rig.beamBetween(rig.n20, rig.apex)
        val beams = rig.state.beams.aliveCount
        // Spitze → Mitte des Balkens (20 → Spitze): läge exakt auf der halben Strecke eines schon bestehenden Balkens
        val cmd = Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(rig.apex), bBeamRef = rig.bref(onApex), bBeamT = 0.5f, materialId = WOOD)
        rig.validates(RejectReason.DUPLICATE_BEAM, cmd)
        rig.rejects(RejectReason.DUPLICATE_BEAM, cmd)
        assertEquals(beams, rig.state.beams.aliveCount, "nichts wurde geteilt oder gebaut")
        // andere Richtung (Teilungspunkt als Ende A), und der andere Endknoten des Balkens
        rig.rejects(RejectReason.DUPLICATE_BEAM, Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(onApex), aBeamT = 0.3f, bNodeRef = rig.nref(rig.n20), materialId = WOOD))
        // ein Knoten, der nicht am Balken hängt, darf dagegen zur Balkenmitte bauen
        rig.ok(Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(rig.n26), bBeamRef = rig.bref(onApex), bBeamT = 0.5f, materialId = WOOD))
    }

    @Test
    fun acceptsMaxLengthBeamsInEveryDirectionDespiteFloatRounding() {
        val rig = RulesRig()
        val ax = rig.state.nodes.x[rig.apex]
        val ay = rig.state.nodes.y[rig.apex]
        for (deg in 0 until 360) {
            val rad = deg * FloatMath.DEG_TO_RAD
            val x = ax + kotlin.math.cos(rad.toDouble()).toFloat() * 6f
            val y = ay + kotlin.math.sin(rad.toDouble()).toFloat() * 6f
            // Gelände (y > 34) und Bauzone blockieren manche Richtungen, aber nie wegen der Länge
            val reason = RulesValidator.validate(rig.state, rig.beam(rig.apex, x, y).copy(tick = rig.tick))
            assertTrue(reason != RejectReason.TOO_LONG && reason != RejectReason.TOO_SHORT, "deg=$deg x=$x y=$y -> $reason")
        }
        rig.rejects(RejectReason.TOO_LONG, rig.beam(rig.apex, 23f, 24.9f)) // 6,1 m bleibt zu lang
    }

    @Test
    fun beamCostIsWholeMetalRoundedLikeThePrototype() {
        val rig = RulesRig()
        // 4,6 m Holz = 18,4 ⚙ → 18 (Ghost "4,6 m · 18 ⚙")
        rig.ok(rig.beam(rig.apex, 23f, 26.4f))
        assertEquals(382f, rig.player().metal, 1e-4f)
        rig.ok(rig.undo())
        assertEquals(400f, rig.player().metal, 1e-4f)
        // 4,4 m Metall = 44 ⚙; 0,55 m Holz = 2,2 → 2; Rundung halbe aufwärts (4,125 m → 16,5 → 17)
        assertEquals(17f, RuleCost.beam(4.125f, 4f), 0f)
        assertEquals(2f, RuleCost.beam(0.55f, 4f), 0f)
        // Ein Spieler mit 18,2 ⚙ sieht "18 ⚙" und kann bauen
        rig.player().metal = 18.2f
        rig.ok(rig.beam(rig.apex, 23f, 26.4f))
    }

    private fun RulesRig.undo() = Command.Undo(tick, 0)
}
