package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.rules.RuleTables.MINE
import de.bollwerk.engine.rules.RuleTables.MORTAR
import de.bollwerk.engine.rules.RuleTables.REACTOR
import de.bollwerk.engine.rules.RuleTables.TURBINE
import de.bollwerk.engine.rules.RuleTables.WOOD
import de.bollwerk.engine.rules.RuleTables.WORKSHOP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Für **jeden** `RejectReason` gibt es hier einen Fall, der ihn über den echten Weg (Command-System) auslöst und das
 * Ergebnis `Rejected(reason)` prüft. Ein neuer Grund im Enum ohne Eintrag lässt [everyReasonHasACase] scheitern.
 */
class RejectReasonMatrixTest {
    private class Case(val rig: RulesRig, val cmd: Command)

    private fun beam(rig: RulesRig, x: Float, y: Float, from: Int = rig.apex, mat: Int = WOOD) =
        Command.PlaceBeam(rig.tick, 0, aNodeRef = rig.nref(from), bX = x, bY = y, materialId = mat)

    private val cases: Map<RejectReason, () -> Case> = mapOf(
        RejectReason.NOT_ENOUGH_METAL to { RulesRig(metal = 1f).let { Case(it, beam(it, 23f, 27f)) } },
        RejectReason.NOT_ENOUGH_ENERGY to {
            RulesRig(metal = 1000f, energy = 0f).let { r ->
                Case(r, Command.PlaceDevice(r.tick, 0, WORKSHOP, r.bref(r.beamBetween(r.n23, r.apex)), 0.5f))
            }
        },
        RejectReason.TOO_LONG to { RulesRig().let { Case(it, beam(it, 23f, 24f)) } },
        RejectReason.TOO_SHORT to { RulesRig().let { Case(it, beam(it, 23f, 30.9f)) } },
        RejectReason.NOT_CONNECTED to { RulesRig().let { Case(it, Command.PlaceBeam(it.tick, 0, aX = 5f, aY = 30f, bX = 7f, bY = 30f, materialId = WOOD)) } },
        RejectReason.OUT_OF_BUILD_ZONE to { RulesRig().let { r -> Case(r, beam(r, 41f, 34f, from = r.addAnchor(38f, 34f))) } },
        RejectReason.BLOCKED_BY_TERRAIN to { RulesRig().let { Case(it, beam(it, 28f, 36f, from = it.n26)) } },
        RejectReason.DUPLICATE_BEAM to { RulesRig().let { r -> Case(r, Command.PlaceBeam(r.tick, 0, aNodeRef = r.nref(r.n23), bNodeRef = r.nref(r.n26), materialId = WOOD)) } },
        RejectReason.OCCUPIED to {
            RulesRig(metal = 1000f).let { r ->
                val b = r.beamBetween(r.n23, r.apex)
                r.addDevice(WORKSHOP, b, 0.5f)
                Case(r, Command.PlaceDevice(r.tick, 0, WORKSHOP, r.bref(b), 0.5f))
            }
        },
        RejectReason.LOCKED_TECH to { RulesRig(metal = 1000f).let { r -> Case(r, beam(r, 23f, 27f, mat = RuleTables.ARMOUR)) } },
        RejectReason.INVALID_TARGET to { RulesRig().let { Case(it, beam(it, Float.NaN, 27f)) } },
        RejectReason.STALE_TARGET to { RulesRig().let { Case(it, Command.DeleteBeam(it.tick, 0, (9L shl 32) or 2L)) } },
        RejectReason.NOT_OWNER to { RulesRig().let { r -> Case(r, Command.DeleteBeam(r.tick, 0, r.bref(r.beamBetween(r.nodeAt(97f, 34f), r.nodeAt(100f, 34f))))) } },
        RejectReason.NOT_YOUR_TURN to { RulesRig(turns = true, turnTicks = 2700).let { Case(it, Command.EndTurn(it.tick, 1)) } },
        RejectReason.RELOADING to {
            RulesRig(metal = 500f, energy = 200f).let { r ->
                val m = r.addDevice(MORTAR, r.beamBetween(r.n20, r.apex), 0.7f)
                r.state.devices.reloadTicksOf[m] = 30
                Case(r, Command.Fire(r.tick, 0, r.dref(m)))
            }
        },
        RejectReason.STILL_BUILDING to {
            RulesRig(metal = 500f, energy = 200f).let { r ->
                val m = r.addDevice(MORTAR, r.beamBetween(r.n20, r.apex), 0.7f)
                r.state.devices.buildTicks[m] = 30
                Case(r, Command.Fire(r.tick, 0, r.dref(m)))
            }
        },
        RejectReason.NEEDS_ORE to { RulesRig().let { r -> Case(r, Command.PlaceDevice(r.tick, 0, MINE, r.bref(r.beamBetween(r.n20, r.apex)), 0.7f, true)) } },
        RejectReason.TOP_MOUNT_ONLY to { RulesRig().let { r -> Case(r, Command.PlaceDevice(r.tick, 0, TURBINE, r.bref(r.ground01), 0.5f, false)) } },
        RejectReason.UNIQUE_LIMIT to { RulesRig().let { r -> Case(r, Command.PlaceDevice(r.tick, 0, REACTOR, r.bref(r.ground01), 0.85f, true)) } },
        RejectReason.REACTOR_PROTECTED to { RulesRig().let { r -> Case(r, Command.DeleteBeam(r.tick, 0, r.bref(r.reactorBeam))) } },
        RejectReason.NOTHING_TO_UNDO to { RulesRig().let { Case(it, Command.Undo(it.tick, 0)) } },
        RejectReason.UNDO_BLOCKED to {
            RulesRig().let { r ->
                r.ok(beam(r, 23f, 27f))
                r.state.beams.hpOf[r.beamBetween(r.apex, r.nodeAt(23f, 27f))] = 10f
                Case(r, Command.Undo(r.tick, 0))
            }
        },
        RejectReason.UNKNOWN_CONTENT to { RulesRig().let { Case(it, beam(it, 23f, 27f, mat = 77)) } },
        RejectReason.GAME_OVER to { RulesRig().let { r -> r.ok(Command.Surrender(r.tick, 0)); Case(r, beam(r, 23f, 27f)) } },
    )

    @Test
    fun everyReasonHasACase() {
        assertEquals(RejectReason.entries.toSet(), cases.keys, "fehlender Fall für: ${RejectReason.entries.toSet() - cases.keys}")
    }

    @Test
    fun everyReasonIsProducedByTheCommandSystemAndTheValidator() {
        for ((reason, make) in cases) {
            val c = make()
            assertEquals(reason, RulesValidator.validate(c.rig.state, c.cmd.withTick(c.rig.tick)), "Validator: $reason")
            val r = c.rig.send(c.cmd).single()
            assertEquals(CommandResult.Rejected(reason), r, "Command-System: $reason")
            // abgelehnte Commands ändern nichts am Journal
            assertTrue(c.rig.player().undoCount <= 1, "$reason")
        }
    }
}
