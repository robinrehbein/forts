package de.bollwerk.engine.rules

import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.SystemSlot

/**
 * Registrierung der Regel-Systeme (WP3):
 * [SystemSlot.COMMANDS] ([CommandSystem], sequenzielles Prüfen/Anwenden), [SystemSlot.ECONOMY], [SystemSlot.TECH],
 * [SystemSlot.RESULT], [SystemSlot.TURN], [SystemSlot.WIND]. Der Tür-Timer ([SystemSlot.DOORS]) gehört zu den
 * Kampf-Systemen (WP4), `Command.ToggleDoor` setzt nur die Flags.
 *
 * Wie `PhysicsSystems.all()` liefert jeder Aufruf eigene System-Instanzen; zusammengesetzt wird in
 * `de.bollwerk.engine.systems.StandardSystems`.
 */
object RulesSystems {
    fun all(): Map<SystemSlot, SimSystem> = linkedMapOf(
        SystemSlot.COMMANDS to CommandSystem(),
        SystemSlot.ECONOMY to EconomySystem(),
        SystemSlot.TECH to TechSystem(),
        SystemSlot.RESULT to ResultSystem(),
        SystemSlot.TURN to TurnSystem(),
        SystemSlot.WIND to WindSystem(),
    )
}
