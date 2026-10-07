package de.bollwerk.engine.combat

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.FxEvent

/**
 * [de.bollwerk.engine.sim.SystemSlot.DOORS] (Prototyp `doorTick`): Automatisch geöffnete Türen (`doorTimerTicks > 0`,
 * gesetzt vom Waffen-System beim Schuss, 2,6 s) zählen herunter und schließen bei 0 – außer der Spieler hat sie offen
 * fixiert ([BeamFlags.DOOR_PINNED]). Offene Türen ([BeamFlags.DOOR_OPEN]) lassen Projektile/Strahlen passieren und
 * schirmen Explosionen nicht ab. Schließen erzeugt `FxEvent.DoorToggled(open = false)`.
 */
class DoorSystem : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        val beams = state.beams
        val nodes = state.nodes
        val n = beams.size
        for (j in 0 until n) {
            if (!beams.isAlive(j) || beams.doorTimerTicks[j] <= 0) continue
            val left = beams.doorTimerTicks[j] - 1
            beams.doorTimerTicks[j] = left
            if (left > 0) continue
            val f = beams.flags[j]
            if ((f and BeamFlags.DOOR_PINNED) != 0 || (f and BeamFlags.DOOR_OPEN) == 0) continue
            beams.flags[j] = f and BeamFlags.DOOR_OPEN.inv()
            val a = beams.a[j]; val b = beams.b[j]
            ctx.fx.add(
                FxEvent.DoorToggled(state.tick, (nodes.x[a] + nodes.x[b]) * 0.5f, (nodes.y[a] + nodes.y[b]) * 0.5f, beams.uidOf[j], false),
            )
        }
    }
}
