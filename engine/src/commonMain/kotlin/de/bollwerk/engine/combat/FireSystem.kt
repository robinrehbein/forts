package de.bollwerk.engine.combat

import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.physics.BeamBreaker
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent

/**
 * [de.bollwerk.engine.sim.SystemSlot.FIRE] (Prototyp `fireTick`, Werte aus `FireConfig`), je brennendem Balken in ID-Reihenfolge:
 * - Brandstärke wächst (`growthPerSec`, max. 1), Brennstoff sinkt (`fuelPerSec`, Verkohlung = niedriger Brennstoff),
 *   TP-Verlust `(hpLossBase + hpLossScale · fire) · maxHp` pro s.
 * - TP ≤ 0 oder Brennstoff aufgebraucht → Bruch an zufälliger Stelle aus `rngFire` ([BreakCause.FIRE]).
 * - Ab `spreadThreshold`: Ausbreitung auf Nachbarbalken (gemeinsamer Knoten), die brennbar sind, Brennstoff haben und
 *   noch nicht brennen, mit Wahrscheinlichkeit `spreadChancePerSec · bias · dt` aus `rngFire`;
 *   `bias = clamp(1 + windBias · wind · sign(dx), windBiasMin, windBiasMax)` (× `upwardBias`, wenn der Nachbar höher liegt).
 * Metall/Panzer/Tür brennen nie (nur `MaterialProps.flammable`). Im laufenden Tick entstandene Balken werden übersprungen.
 */
class FireSystem(private val world: CombatWorld) : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        val cfg = state.config
        val fc = cfg.fire
        val dt = cfg.dt
        val beams = state.beams
        val nodes = state.nodes
        val mats = state.tables.materials
        val rng = state.rngFire
        val wind = state.wind
        val n = beams.size
        for (j in 0 until n) {
            if (!beams.isAlive(j) || beams.isNew(j)) continue
            if (!(beams.fireOf[j] > 0f)) continue
            val fire = FloatMath.min(1f, beams.fireOf[j] + dt * fc.growthPerSec)
            beams.fireOf[j] = fire
            beams.fuelOf[j] -= dt * fc.fuelPerSec
            beams.hpOf[j] -= dt * beams.maxHpOf[j] * (fc.hpLossBase + fc.hpLossScale * fire)
            if (beams.hpOf[j] <= 0f || beams.fuelOf[j] <= 0f) {
                // Bruchstelle aus dem eigenen Strom (CLAUDE.md Regel 8), nicht aus rngDebris
                val t = rng.nextFloat(BeamBreaker.RANDOM_T_MIN, BeamBreaker.RANDOM_T_MAX)
                BeamBreaker.breakBeam(state, ctx, j, t, BreakCause.FIRE)
                continue
            }
            if (fire <= fc.spreadThreshold) continue
            val a = beams.a[j]; val b = beams.b[j]
            val midX2 = nodes.x[a] + nodes.x[b]
            val midY2 = nodes.y[a] + nodes.y[b]
            for (e in 0 until 2) {
                val node = if (e == 0) a else b
                if (node + 1 >= nodes.adjStart.size) continue
                val s0 = nodes.adjStart[node]; val s1 = nodes.adjStart[node + 1]
                for (k in s0 until s1) {
                    val o = nodes.adjBeam[k]
                    if (o == j || !beams.isAlive(o) || beams.isNew(o)) continue
                    if (beams.a[o] != node && beams.b[o] != node) continue
                    if (beams.fireOf[o] > 0f || beams.fuelOf[o] <= 0f || !mats[beams.materialOf[o]].flammable) continue
                    val oa = beams.a[o]; val ob = beams.b[o]
                    val dirx = ((nodes.x[oa] + nodes.x[ob]) - midX2) * 0.5f
                    var bias = FloatMath.clamp(1f + fc.windBias * wind * FloatMath.sign(dirx), fc.windBiasMin, fc.windBiasMax)
                    if ((nodes.y[oa] + nodes.y[ob]) < midY2) bias *= fc.upwardBias
                    if (rng.chance(fc.spreadChancePerSec * bias * dt)) {
                        beams.fireOf[o] = fc.igniteStart
                        ctx.fx.add(
                            FxEvent.Ignited(
                                state.tick, (nodes.x[oa] + nodes.x[ob]) * 0.5f, (nodes.y[oa] + nodes.y[ob]) * 0.5f, beams.uidOf[o],
                            ),
                        )
                    }
                }
            }
        }
        world.reactors.check(state, ctx, world)
    }
}
