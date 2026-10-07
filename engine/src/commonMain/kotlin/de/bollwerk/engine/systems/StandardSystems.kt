package de.bollwerk.engine.systems

import de.bollwerk.engine.combat.CombatSystems
import de.bollwerk.engine.physics.PhysicsSystems
import de.bollwerk.engine.rules.RulesSystems
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.SystemSlot

/**
 * Die vollständige Systemliste einer Partie: Physik-Kern (WP2) + Regeln (WP3) + Kampf (WP4). Spätere Einträge
 * überschreiben gleiche Slots.
 *
 * Jeder Aufruf erzeugt neue System-Instanzen mit eigenem Physik-Cache; ein Stepper gehört zu **einem** `GameState`.
 */
object StandardSystems {
    fun all(): Map<SystemSlot, SimSystem> = PhysicsSystems.all() + RulesSystems.all() + CombatSystems.all()

    fun stepper(): SimStepper = SimStepper(all())
}
