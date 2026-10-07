package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.rules.RuleTables.MINE
import de.bollwerk.engine.rules.RuleTables.TURBINE
import de.bollwerk.engine.rules.RuleTables.WOOD
import de.bollwerk.engine.sim.SimConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UndoTest {
    private fun RulesRig.beam(from: Int, x: Float, y: Float) =
        Command.PlaceBeam(tick, 0, aNodeRef = nref(from), bX = x, bY = y, materialId = WOOD)

    private fun RulesRig.undo() = Command.Undo(tick, 0)

    @Test
    fun nothingToUndoOnAFreshGame() {
        val rig = RulesRig()
        rig.rejects(RejectReason.NOTHING_TO_UNDO, rig.undo())
    }

    @Test
    fun undoBeamRefundsFullCostAndRemovesTheFreeNode() {
        val rig = RulesRig()
        val nodes = rig.state.nodes.aliveCount
        val beams = rig.state.beams.aliveCount
        rig.ok(rig.beam(rig.apex, 23f, 26.5f))
        assertEquals(382f, rig.player().metal, 1e-3f)
        rig.ok(rig.undo())
        assertEquals(400f, rig.player().metal, 1e-3f)
        assertEquals(beams, rig.state.beams.aliveCount)
        assertEquals(nodes, rig.state.nodes.aliveCount, "der freie Endknoten verschwindet mit")
        assertEquals(0, rig.player().undoCount)
        rig.rejects(RejectReason.NOTHING_TO_UNDO, rig.undo())
    }

    @Test
    fun undoDeviceRefundsMetalAndEnergyAndDropsTheBuildTimer() {
        val rig = RulesRig(metal = 1000f, energy = 400f)
        rig.ok(Command.PlaceDevice(rig.tick, 0, RuleTables.WORKSHOP, rig.bref(rig.beamBetween(rig.n23, rig.apex)), 0.5f, false))
        assertEquals(1000f - 120f, rig.player().metal, 1e-3f)
        rig.ok(rig.undo())
        assertEquals(1000f, rig.player().metal, 1e-3f)
        assertEquals(400f, rig.player().energy, 0.2f)
        assertTrue(rig.devicesOf(RuleTables.WORKSHOP).isEmpty())
    }

    @Test
    fun undoRestoresASplitBeamExactly() {
        val rig = RulesRig()
        val original = rig.beamBetween(rig.n20, rig.apex)
        val b = rig.state.beams
        b.hpOf[original] = 70f
        b.fireOf[original] = 0f
        b.fuelOf[original] = 0.9f
        val rest = b.restLen[original]
        val tex = b.texOffset[original]
        val oldUid = b.uidOf[original]
        val dev = rig.addDevice(TURBINE, original, 0.2f)
        val aliveBeams = b.aliveCount
        val aliveNodes = rig.state.nodes.aliveCount

        rig.ok(Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(original), aBeamT = 0.4f, bX = 17f, bY = 30f, materialId = WOOD))
        assertEquals(aliveBeams + 2, b.aliveCount) // −1 Original +2 Hälften +1 neuer Balken
        assertEquals(0.2f / 0.4f, rig.state.devices.tOf[dev], 1e-5f)
        val metalAfterBuild = rig.player().metal

        rig.ok(rig.undo())
        // ein Balken statt zwei Hälften, kein Split-Knoten, kein neuer Balken
        assertEquals(aliveBeams, b.aliveCount)
        assertEquals(aliveNodes, rig.state.nodes.aliveCount)
        val restored = rig.beamBetween(rig.n20, rig.apex)
        assertTrue(restored >= 0)
        assertEquals(WOOD, b.materialOf[restored])
        assertEquals(rest, b.restLen[restored], 1e-5f)
        assertEquals(70f, b.hpOf[restored]); assertEquals(100f, b.maxHpOf[restored])
        assertEquals(0.9f, b.fuelOf[restored]); assertEquals(0f, b.fireOf[restored])
        assertEquals(tex, b.texOffset[restored], 1e-6f)
        assertNotEquals(oldUid, b.uidOf[restored], "neue uid")
        assertEquals(0, b.ownerOf[restored])
        // Gerät sitzt wieder auf dem Original-Balken an der alten Stelle
        assertEquals(restored, rig.state.devices.beamId[dev])
        assertEquals(0.2f, rig.state.devices.tOf[dev], 1e-6f)
        assertTrue(metalAfterBuild < 400f)
        assertEquals(400f, rig.player().metal, 1e-3f)
        assertEquals(0, rig.player().undoCount)
    }

    @Test
    fun undoRestoresTwoSplitsOfOneBuild() {
        val rig = RulesRig()
        val b1 = rig.beamBetween(rig.n20, rig.apex)
        val b2 = rig.beamBetween(rig.n26, rig.apex)
        val beams = rig.state.beams.aliveCount
        val nodes = rig.state.nodes.aliveCount
        rig.ok(Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(b1), aBeamT = 0.5f, bBeamRef = rig.bref(b2), bBeamT = 0.5f, materialId = WOOD))
        rig.ok(rig.undo())
        assertEquals(beams, rig.state.beams.aliveCount)
        assertEquals(nodes, rig.state.nodes.aliveCount)
        assertTrue(rig.beamBetween(rig.n20, rig.apex) >= 0 && rig.beamBetween(rig.n26, rig.apex) >= 0)
        assertEquals(400f, rig.player().metal, 1e-3f)
    }

    @Test
    fun olderBuildStaysUndoableAfterANewerSplitWasUndone() {
        val rig = RulesRig()
        // X: neuer Balken; Y: beginnt auf der Mitte von X (teilt ihn); zweimal Zurück räumt beides weg
        rig.ok(rig.beam(rig.apex, 23f, 27f)) // X senkrecht, 4 m
        val xTop = rig.nodeAt(23f, 27f)
        val x = rig.beamBetween(rig.apex, xTop)
        rig.run(1)
        rig.ok(Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(x), aBeamT = 0.5f, bX = 26f, bY = 29f, materialId = WOOD))
        val beams = rig.state.beams.aliveCount
        assertEquals(2, rig.player().undoCount)
        rig.ok(rig.undo())
        assertEquals(beams - 3 + 1, rig.state.beams.aliveCount) // Hälften + Y weg, X zurück
        rig.ok(rig.undo()) // ältester Eintrag zeigt auf den neu angelegten X
        assertEquals(400f, rig.player().metal, 1e-3f)
        assertEquals(beams - 3 + 1 - 1, rig.state.beams.aliveCount)
        assertEquals(0, rig.player().undoCount)
        assertTrue(rig.nodeAt(23f, 27f) < 0)
    }

    @Test
    fun undoIsLifoAcrossBeamsAndDevices() {
        val rig = RulesRig(metal = 1000f)
        rig.ok(rig.beam(rig.apex, 23f, 27f))
        rig.run(1)
        rig.ok(Command.PlaceDevice(rig.tick, 0, TURBINE, rig.bref(rig.ground01), 0.5f, true))
        rig.run(1)
        rig.ok(rig.beam(rig.n26, 29f, 34f))
        assertEquals(3, rig.player().undoCount)
        rig.ok(rig.undo())
        assertTrue(rig.nodeAt(29f, 34f) < 0)
        assertEquals(1, rig.devicesOf(TURBINE).size)
        rig.ok(rig.undo())
        assertTrue(rig.devicesOf(TURBINE).isEmpty())
        rig.ok(rig.undo())
        assertTrue(rig.nodeAt(23f, 27f) < 0)
    }

    // ---- UNDO_BLOCKED ----

    @Test
    fun undoIsBlockedAfterTheWindowAndOldEntriesAreDropped() {
        val rig = RulesRig()
        rig.ok(rig.beam(rig.apex, 23f, 27f))
        rig.run(599) // Fenster: 10 s (600 Ticks nach dem Bau)
        rig.validates(null, rig.undo())
        rig.run(1)
        // abgelaufen: Validator (zwischen den Ticks) und Command-System antworten gleich, der Eintrag gilt als erledigt
        rig.validates(RejectReason.NOTHING_TO_UNDO, rig.undo())
        assertFalse(RulesValidator.canUndo(rig.state, 0))
        rig.run(1)
        assertEquals(0, rig.player().undoCount, "abgelaufene Einträge werden entfernt")
        rig.rejects(RejectReason.NOTHING_TO_UNDO, rig.undo())
        assertEquals(400f - 16f, rig.player().metal, 1e-3f)
    }

    @Test
    fun unlimitedWindowAllowsLateUndo() {
        val rig = RulesRig(config = SimConfig(undoWindowTicks = 0))
        rig.ok(rig.beam(rig.apex, 23f, 27f))
        rig.run(5000)
        rig.ok(rig.undo())
        assertEquals(400f, rig.player().metal, 1e-3f)
    }

    @Test
    fun damagedOrBurningBuildBlocksUndoAndStays() {
        val rig = RulesRig()
        rig.ok(rig.beam(rig.apex, 23f, 27f))
        val b = rig.beamBetween(rig.apex, rig.nodeAt(23f, 27f))
        rig.state.beams.hpOf[b] = 99f
        rig.rejects(RejectReason.UNDO_BLOCKED, rig.undo())
        assertEquals(1, rig.player().undoCount, "Eintrag bleibt")
        rig.state.beams.hpOf[b] = 100f
        rig.state.beams.fireOf[b] = 0.1f
        rig.rejects(RejectReason.UNDO_BLOCKED, rig.undo())
        rig.state.beams.fireOf[b] = 0f
        rig.ok(rig.undo())
    }

    @Test
    fun damagedHalfBlocksUndoOfASplit() {
        val rig = RulesRig()
        val original = rig.beamBetween(rig.n20, rig.apex)
        rig.ok(Command.PlaceBeam(rig.tick, 0, aBeamRef = rig.bref(original), aBeamT = 0.5f, bX = 17f, bY = 30f, materialId = WOOD))
        val mid = rig.nodeAt(21.5f, 32.5f)
        val half = rig.beamBetween(rig.n20, mid)
        rig.state.beams.hpOf[half] = 50f // < TP des Originals (100)
        rig.rejects(RejectReason.UNDO_BLOCKED, rig.undo())
        rig.state.beams.hpOf[half] = 100f
        rig.ok(rig.undo())
    }

    @Test
    fun destroyedBuildIsDroppedAndDoesNotBlockOlderEntries() {
        val rig = RulesRig(metal = 1000f)
        rig.ok(rig.beam(rig.apex, 23f, 27f)) // älterer Eintrag
        rig.run(1)
        rig.ok(rig.beam(rig.n26, 29f, 34f)) // neuerer Eintrag
        val newer = rig.beamBetween(rig.n26, rig.nodeAt(29f, 34f))
        rig.state.beams.release(newer) // vom Gegner zerstört
        rig.run(1)
        assertEquals(1, rig.player().undoCount)
        rig.ok(rig.undo())
        assertTrue(rig.nodeAt(23f, 27f) < 0)
    }

    @Test
    fun journalIsBoundedAndHashed() {
        val rig = RulesRig(metal = 1000f, config = SimConfig(undoDepth = 3, undoWindowTicks = 0))
        val h0 = StateHash.of(rig.state)
        for (i in 0 until 5) {
            rig.ok(rig.beam(rig.apex, 23f - 0.5f * i - 1f, 28f))
            rig.run(1)
        }
        assertEquals(3, rig.player().undoCount, "ältester Eintrag fällt heraus")
        val h1 = StateHash.of(rig.state)
        assertNotEquals(h0, h1)
        // Journal allein beeinflusst den Hash: gleicher Zustand, anderer Journal-Eintrag
        val before = StateHash.of(rig.state)
        rig.state.players[0].undoJournal.pop()
        assertNotEquals(before, StateHash.of(rig.state))
    }

    @Test
    fun undoRefundCanBePartial() {
        val rig = RulesRig(config = SimConfig(undoRefund = 0.5f))
        rig.ok(rig.beam(rig.apex, 23f, 27f))
        rig.ok(rig.undo())
        assertEquals(400f - 8f, rig.player().metal, 1e-3f)
    }

    @Test
    fun undoBelongsToThePlayerWhoBuilt() {
        val rig = RulesRig()
        rig.ok(rig.beam(rig.apex, 23f, 27f))
        rig.rejects(RejectReason.NOTHING_TO_UNDO, Command.Undo(rig.tick, 1))
        assertEquals(1, rig.player(0).undoCount)
        assertFalse(rig.state.players[1].undoCount > 0)
    }

    @Test
    fun mineOnOreCanBeUndoneWhileUnderConstruction() {
        val rig = RulesRig()
        rig.ok(Command.PlaceDevice(rig.tick, 0, MINE, rig.bref(rig.ground01), 0.85f, true))
        rig.run(100)
        rig.ok(rig.undo())
        assertEquals(400f, rig.player().metal, 1e-3f)
    }

    @Test
    fun validatorAnswerBetweenTicksMatchesTheCommandSystemWhenTheNewestBuildIsGone() {
        val rig = RulesRig(metal = 1000f)
        rig.ok(rig.beam(rig.apex, 23f, 27f)) // älterer Eintrag
        rig.run(1)
        rig.ok(rig.beam(rig.n26, 29f, 34f)) // neuerer Eintrag
        rig.state.beams.release(rig.beamBetween(rig.n26, rig.nodeAt(29f, 34f))) // zerstört, vor dem nächsten Tick
        // noch ungeprunt: die UI-Antwort ("Zurück aktiv") darf nicht anders sein als das, was der nächste Tick tut
        assertTrue(RulesValidator.canUndo(rig.state, 0))
        rig.validates(null, rig.undo())
        rig.ok(rig.undo())
        assertTrue(rig.nodeAt(23f, 27f) < 0, "der ältere Bau wurde zurückgenommen, der zerstörte übersprungen")
        assertEquals(0, rig.player().undoCount)
    }

    @Test
    fun entryWhoseBuildingIsGoneAndNothingOlderYieldsNothingToUndoImmediately() {
        val rig = RulesRig()
        rig.ok(rig.beam(rig.apex, 23f, 27f))
        rig.state.beams.release(rig.beamBetween(rig.apex, rig.nodeAt(23f, 27f)))
        rig.validates(RejectReason.NOTHING_TO_UNDO, rig.undo())
        assertFalse(RulesValidator.canUndo(rig.state, 0))
    }
}
