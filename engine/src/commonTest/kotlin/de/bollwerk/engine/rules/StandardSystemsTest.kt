package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.SystemSlot
import de.bollwerk.engine.systems.StandardSystems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Zusammenspiel der Regeln mit Physik und Kampf in der Standard-Systemliste. */
class StandardSystemsTest {
    @Test
    fun standardSystemsCoverEverySlot() {
        val all = StandardSystems.all()
        for (slot in SystemSlot.entries) assertTrue(slot in all, "kein System für $slot")
        val stepper = StandardSystems.stepper()
        for (slot in SystemSlot.entries) assertNotNull(stepper[slot])
    }

    @Test
    fun rulesSystemsRegisterTheirSlots() {
        val rules = RulesSystems.all()
        assertEquals(
            setOf(SystemSlot.COMMANDS, SystemSlot.ECONOMY, SystemSlot.TECH, SystemSlot.RESULT, SystemSlot.TURN, SystemSlot.WIND),
            rules.keys,
        )
    }

    @Test
    fun fireCommandIsConsumedByTheWeaponSystemWhichPaysTheShot() {
        val rig = RulesRig(metal = 500f, energy = 200f, physics = true)
        val m = rig.addDevice(RuleTables.MORTAR, rig.beamBetween(rig.n20, rig.apex), 0.7f)
        rig.run(2)
        val metal = rig.player().metal
        val energy = rig.player().energy
        val r = rig.send(
            Command.SetAim(rig.tick, 0, rig.dref(m), 0.9f, 0.8f),
            Command.Fire(rig.tick, 0, rig.dref(m)),
        )
        assertEquals(listOf(CommandResult.Accepted, CommandResult.Accepted), r)
        assertEquals(0, rig.state.devices.flags[m] and DeviceFlags.FIRE_REQUESTED, "WEAPONS verbraucht die Anforderung im selben Tick")
        assertEquals(metal - 15f, rig.player().metal, 0.1f)
        assertEquals(energy - 30f, rig.player().energy, 0.2f)
        assertTrue(rig.state.devices.reloadTicksOf[m] > 0)
        assertTrue(rig.state.projectiles.aliveCount >= 1)
        // nachladen: der nächste Schuss wird abgelehnt
        rig.rejects(RejectReason.RELOADING, Command.Fire(rig.tick, 0, rig.dref(m)))
    }

    @Test
    fun aDestroyedReactorEndsTheGameThroughTheFullSystemList() {
        val rig = RulesRig(physics = true)
        rig.run(3)
        rig.killReactor(1)
        rig.run(2)
        assertEquals(de.bollwerk.engine.sim.GameResult.Winner(0, de.bollwerk.engine.sim.WinReason.REACTOR_DESTROYED), rig.state.result)
        assertEquals(1, rig.fx.count { it is de.bollwerk.engine.view.FxEvent.ReactorDestroyed && it.playerId == 1 }, "kein doppeltes Ereignis")
    }
}
