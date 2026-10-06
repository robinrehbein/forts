package de.bollwerk.engine.physics

import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext

/**
 * [de.bollwerk.engine.sim.SystemSlot.TOPOLOGY]: Ist die Topologie in diesem Tick verändert worden (Bruch, Bau,
 * Abriss, Gerät zerstört), baut [PhysicsWorld.rebuild] Konnektivität (Union-Find), Trümmer-Flags, Adjazenz,
 * Massen und Lösungsreihenfolge neu. Geräte auf Trümmern sterben dabei.
 */
class TopologySystem(private val world: PhysicsWorld) : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        world.ensure(state, ctx)
    }
}
