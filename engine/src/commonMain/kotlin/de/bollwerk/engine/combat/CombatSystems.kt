package de.bollwerk.engine.combat

import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.SystemSlot

/**
 * Registrierung der Kampf-Systeme (WP4), analog zu `PhysicsSystems.all()`:
 *
 * ```
 * val stepper = SimStepper(PhysicsSystems.all() + CombatSystems.all() + rulesSystems)
 * ```
 * (`SimStepper` ordnet nach `SystemSlot.ordinal`.) Jeder Aufruf erzeugt einen eigenen [CombatWorld]-Cache, den die
 * Systeme teilen (Broadphase, Puffer, Reaktor-Beobachtung). Reihenfolge im Tick:
 * DEVICES → WEAPONS → PROJECTILES → (PHYSICS, STRAIN_DAMAGE) → FIRE → REPAIR → DOORS → (TOPOLOGY, DEBRIS) → REACTORS.
 *
 * **Übergaben an andere Arbeitspakete:**
 * - WP3 (Command-System): `Command.Fire` → `devices.flags |= DeviceFlags.FIRE_REQUESTED` (nach den üblichen Prüfungen),
 *   `Command.SetAim` → `aimAngle`/`power`; `Command.RepairBeam` → `BeamFlags.REPAIRING`; `Command.ToggleDoor` →
 *   `BeamFlags.DOOR_OPEN`/`DOOR_PINNED` und `doorTimerTicks = 0`. Kosten pro Schuss zieht **WEAPONS** ab, nicht WP3.
 * - WP3 (RESULT): Reaktor-Verlust über `PlayerState.reactorDeviceId`; `FxEvent.ReactorDestroyed` + große Explosion
 *   kommen von WP4 für jeden Reaktor, der ab WEAPONS stirbt – auch durch Einsturz/Trümmer (Slot REACTORS vor RESULT,
 *   siehe [ReactorWatch]). RESULT meldet nur, was in diesem Tick noch nicht gemeldet wurde.
 */
object CombatSystems {
    /** Die sieben WP4-Systeme mit gemeinsamem [CombatWorld]. */
    fun all(world: CombatWorld = CombatWorld()): Map<SystemSlot, SimSystem> = linkedMapOf(
        SystemSlot.DEVICES to DeviceSystem(world),
        SystemSlot.WEAPONS to WeaponSystem(world),
        SystemSlot.PROJECTILES to ProjectileSystem(world),
        SystemSlot.FIRE to FireSystem(world),
        SystemSlot.REPAIR to RepairSystem(),
        SystemSlot.DOORS to DoorSystem(),
        SystemSlot.REACTORS to ReactorSystem(world),
    )
}
