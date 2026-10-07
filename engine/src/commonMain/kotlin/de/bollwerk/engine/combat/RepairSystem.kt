package de.bollwerk.engine.combat

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.FxEvent

/**
 * [de.bollwerk.engine.sim.SystemSlot.REPAIR] (Prototyp `b.rep` in `fireTick`, Werte aus `RepairConfig`): Balken mit
 * [BeamFlags.REPAIRING] (gesetzt vom Command-System bei `Command.RepairBeam`) regenerieren `hpFractionPerSec · maxHp`
 * pro s (Standard 50 % → von 0 auf voll in 2 s = 120 Ticks).
 * - **Kosten** beim Heilen, proportional zu den geheilten TP: `costFactor · costPerMeter · restLen · (ΔTP / maxHp)`
 *   Metall vom Besitzer. Reicht das Metall nicht, wird nur der bezahlbare Teil geheilt; bei 0 Metall endet die Reparatur.
 * - **Blockiert**, solange der Balken brennt (pausiert, keine Kosten; das Flag bleibt).
 * - Voll → Flag gelöscht, `FxEvent.BeamRepaired`. Trümmer werden nicht repariert (Flag gelöscht).
 */
class RepairSystem : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        val beams = state.beams
        val mats = state.tables.materials
        val rc = state.config.repair
        val dt = state.config.dt
        val n = beams.size
        for (j in 0 until n) {
            if (!beams.isAlive(j)) continue
            val f = beams.flags[j]
            if ((f and BeamFlags.REPAIRING) == 0) continue
            if ((f and BeamFlags.DEBRIS) != 0) { beams.flags[j] = f and BeamFlags.REPAIRING.inv(); continue }
            if (beams.fireOf[j] > 0f) continue
            val maxHp = beams.maxHpOf[j]
            val missing = maxHp - beams.hpOf[j]
            if (!(missing > 0f) || !(maxHp > 0f)) { finish(state, ctx, j); continue }
            var heal = maxHp * rc.hpFractionPerSec * dt
            if (heal > missing) heal = missing
            val owner = beams.ownerOf[j]
            val fullCost = rc.costFactor * mats[beams.materialOf[j]].costPerMeter * beams.restLen[j] / maxHp
            var cost = fullCost * heal
            if (owner >= 0 && owner < state.players.size) {
                val pl = state.players[owner]
                if (cost > pl.metal) {
                    if (!(pl.metal > 0f) || !(fullCost > 0f)) { beams.flags[j] = f and BeamFlags.REPAIRING.inv(); continue }
                    heal = pl.metal / fullCost
                    cost = pl.metal
                }
                pl.metal -= cost
                if (pl.metal < 0f) pl.metal = 0f
            }
            beams.hpOf[j] += heal
            if (heal >= missing || beams.hpOf[j] >= maxHp) finish(state, ctx, j)
        }
    }

    private fun finish(state: GameState, ctx: StepContext, j: Int) {
        val beams = state.beams
        val nodes = state.nodes
        beams.hpOf[j] = beams.maxHpOf[j]
        beams.flags[j] = beams.flags[j] and BeamFlags.REPAIRING.inv()
        val a = beams.a[j]; val b = beams.b[j]
        ctx.fx.add(FxEvent.BeamRepaired(state.tick, (nodes.x[a] + nodes.x[b]) * 0.5f, (nodes.y[a] + nodes.y[b]) * 0.5f, beams.uidOf[j]))
    }
}
