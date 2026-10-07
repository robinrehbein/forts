package de.bollwerk.engine.physics

import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import kotlin.math.sqrt

/**
 * [de.bollwerk.engine.sim.SystemSlot.PHYSICS]: Verlet-Integration + XPBD-Abstandsconstraints
 * (Prototyp `physicsTick`). Pro Tick `SimConfig.substeps` (4) Substeps à `SimConfig.iterations` (6)
 * Gauss-Seidel-Iterationen über `BeamPool.solveOrder` (aufsteigende Balken-ID), danach je Iteration
 * Bodenkontakt mit Reibung; am Ende jedes Substeps axiale Dämpfung.
 *
 * Integriert alle lebenden, nicht verankerten Knoten (auch im laufenden Tick entstandene: Physik ist
 * kontinuierlicher Zustand, kein Ereignis). Allokationsfrei; Topologie-Neuaufbau nur bei `topologyDirty`.
 */
class PhysicsSystem(private val world: PhysicsWorld) : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        world.ensure(state, ctx)
        simulate(state, world)
    }

    companion object {
        /** Mindestlänge für Constraint-Richtungen (Prototyp 1e-6). */
        private const val MIN_LEN: Float = 1e-6f

        /** Ein Tick Physik (Substeps) auf bereits aufgebauten [world]-Caches. */
        fun simulate(state: GameState, world: PhysicsWorld) {
            val cfg = state.config
            val nodes = state.nodes
            val beams = state.beams
            val terrain = state.terrain
            val x = nodes.x; val y = nodes.y; val px = nodes.px; val py = nodes.py
            val inv = nodes.invMass
            val ba = beams.a; val bb = beams.b; val rest = beams.restLen; val lam = beams.lambda
            val order = beams.solveOrder
            val sc = beams.solveCount
            val dyn = world.dynamicNodes
            val dc = world.dynamicCount
            val compliance = world.compliance
            val kd = world.axialDamping
            val tOnly = world.tensionOnly

            val substeps = cfg.substeps
            val iters = cfg.iterations
            val h = cfg.dt / substeps
            val h2 = h * h
            val invH2 = 1f / h2
            val gh2 = cfg.gravity * h2
            val damp = 1f - cfg.linearDamping * h
            val nodeR = cfg.nodeRadius
            val fric = cfg.groundFrictionRemoved

            for (s in 0 until substeps) {
                // Verlet mit globaler Dämpfung
                for (k in 0 until dc) {
                    val i = dyn[k]
                    val vx = (x[i] - px[i]) * damp
                    val vy = (y[i] - py[i]) * damp
                    px[i] = x[i]; py[i] = y[i]
                    x[i] += vx
                    y[i] += vy + gh2
                }
                for (k in 0 until sc) lam[order[k]] = 0f

                for (it in 0 until iters) {
                    // XPBD-Abstandsconstraints, feste Reihenfolge
                    for (k in 0 until sc) {
                        val j = order[k]
                        val ia = ba[j]; val ib = bb[j]
                        var dx = x[ib] - x[ia]
                        var dy = y[ib] - y[ia]
                        val len = sqrt(dx * dx + dy * dy)
                        if (len < MIN_LEN) continue
                        val c = len - rest[j]
                        if (c < 0f && tOnly[j]) continue
                        val wa = inv[ia]; val wb = inv[ib]
                        val w = wa + wb
                        val at = compliance[j] * invH2
                        val dl = (-c - at * lam[j]) / (w + at)
                        lam[j] += dl
                        dx /= len; dy /= len
                        x[ia] -= wa * dx * dl; y[ia] -= wa * dy * dl
                        x[ib] += wb * dx * dl; y[ib] += wb * dy * dl
                    }
                    // Bodenkontakt mit Reibung (Prototyp: y auf Oberfläche, vy = 0, 60 % der vx entfernt)
                    for (k in 0 until dc) {
                        val i = dyn[k]
                        val gy = terrain.heightAt(x[i]) - nodeR
                        if (y[i] > gy) {
                            y[i] = gy; py[i] = gy
                            px[i] += (x[i] - px[i]) * fric
                        }
                    }
                }

                // Axiale Dämpfung: reduziert die axiale Relativgeschwindigkeit um den Faktor kd (entzieht immer Energie,
                // impulserhaltend). Schlaffe Seile übertragen nichts.
                for (k in 0 until sc) {
                    val j = order[k]
                    val ia = ba[j]; val ib = bb[j]
                    var dx = x[ib] - x[ia]
                    var dy = y[ib] - y[ia]
                    val len = sqrt(dx * dx + dy * dy)
                    if (len < MIN_LEN) continue
                    if (tOnly[j] && len < rest[j]) continue
                    val wa = inv[ia]; val wb = inv[ib]
                    val w = wa + wb
                    dx /= len; dy /= len
                    // positive rv = Enden entfernen sich voneinander
                    val rv = ((x[ib] - px[ib]) - (x[ia] - px[ia])) * dx + ((y[ib] - py[ib]) - (y[ia] - py[ia])) * dy
                    val kk = kd[j] * rv / w
                    // a bekommt +kk·wa entlang d, b −kk·wb → rv' = rv · (1 − kd)
                    px[ia] -= wa * dx * kk; py[ia] -= wa * dy * kk
                    px[ib] += wb * dx * kk; py[ib] += wb * dy * kk
                }
            }
        }
    }
}
