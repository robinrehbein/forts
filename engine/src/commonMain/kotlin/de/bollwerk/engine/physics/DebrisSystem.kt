package de.bollwerk.engine.physics

import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.math.Geometry
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import kotlin.math.sqrt

/**
 * [de.bollwerk.engine.sim.SystemSlot.DEBRIS] (Prototyp `debrisTick`), je Trümmerknoten in ID-Reihenfolge:
 * - Alter (`debrisTicks`) und Zeit unter der Oberfläche (`belowGroundTicks`, tiefer als `belowGroundDepth`).
 * - Bodenaufprall: `FxEvent.DebrisLanded` (Staub), wenn der Knoten den Boden neu berührt und im Vortick schneller
 *   als `landedFxMinSpeed` war ([NodeFlags.GROUNDED], RENDER `lastSpeed`).
 * - Entfernen (alle Balken still brechen, [BreakCause.DECAY]): zu lange unter dem Gelände, außerhalb der
 *   Kill-Grenzen der Karte oder älter als `maxAgeTicks`; nach `decayAfterTicks` zusätzlich zufälliger Zerfall
 *   (`decayChancePerSec`, Strom `rngDebris`) des ersten Balkens.
 * - Einschlag ab `impactMinSpeed`: Sweep des Knotens über den ganzen Tick gegen die Kapseln aller Nicht-Trümmer-
 *   Balken (halbe Dicke + `impactRadius`). Es zählt nur der **Eintritt** in eine Kapsel: Lag der Sweep-Startpunkt
 *   schon in ihr, wird der Balken übersprungen. Damit treffen die Enden frischer Bruchhälften (sie entstehen genau
 *   auf dem Gelenk, an dem der Rest des Bauwerks hängt) nicht ihre eigenen Nachbarbalken (Abweichung vom Prototyp,
 *   der diesen Fehler hat). Schaden `clamp((v − 3) · m · 0,01, min, max)`, Sperrzeit, Abbremsen
 *   des Trümmers (30 % Restgeschwindigkeit) und Stoß auf die Balkenknoten.
 * Verwaiste Knoten räumt der nächste Topologie-Durchlauf ab.
 */
class DebrisSystem : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        val cfg = state.config
        val dc = cfg.debris
        val nodes = state.nodes
        val beams = state.beams
        val map = state.map
        val terrain = state.terrain
        val h = cfg.dt / cfg.substeps
        val decayChance = dc.decayChancePerSec * cfg.dt
        val n = nodes.size
        for (i in 0 until n) {
            if (!nodes.isAlive(i) || nodes.isNew(i)) continue
            val f = nodes.flags[i]
            if ((f and NodeFlags.DEBRIS) == 0) continue
            val age = nodes.debrisTicks[i] + 1
            nodes.debrisTicks[i] = age
            val x = nodes.x[i]; val y = nodes.y[i]
            val gy = terrain.heightAt(x)
            val dx = x - nodes.px[i]; val dy = y - nodes.py[i]
            val sp = sqrt(dx * dx + dy * dy) / h
            if (y > gy + dc.belowGroundDepth) nodes.belowGroundTicks[i]++ else nodes.belowGroundTicks[i] = 0

            // Staub beim Aufprall
            val onGround = y >= gy - cfg.nodeRadius - 0.02f
            val wasGrounded = (f and NodeFlags.GROUNDED) != 0
            if (onGround && !wasGrounded && nodes.lastSpeed[i] > dc.landedFxMinSpeed) {
                ctx.fx.add(FxEvent.DebrisLanded(state.tick, x, gy, nodes.lastSpeed[i]))
            }
            nodes.flags[i] = if (onGround) f or NodeFlags.GROUNDED else f and NodeFlags.GROUNDED.inv()
            nodes.lastSpeed[i] = sp

            // Entfernen
            if (nodes.belowGroundTicks[i] > dc.belowGroundTicks || map.isOutOfBounds(x, y) || age > dc.maxAgeTicks) {
                breakAllAdjacent(state, ctx, i)
                continue
            }
            if (age > dc.decayAfterTicks && state.rngDebris.chance(decayChance)) {
                val j = firstAliveAdjacent(state, i)
                if (j >= 0) BeamBreaker.breakBeam(state, ctx, j, 0.5f, BreakCause.DECAY)
                continue
            }

            // Einschlag auf fremde Balken
            if (sp < dc.impactMinSpeed) continue
            impact(state, ctx, i, sp)
        }
    }

    private fun impact(state: GameState, ctx: StepContext, i: Int, sp: Float) {
        val cfg = state.config
        val dc = cfg.debris
        val nodes = state.nodes
        val beams = state.beams
        val mats = state.tables.materials
        val h = cfg.dt / cfg.substeps
        val x = nodes.x[i]; val y = nodes.y[i]
        val vx = x - nodes.px[i]; val vy = y - nodes.py[i]
        // Sweep über die Bewegung des ganzen Ticks
        val sx = x - vx * cfg.substeps; val sy = y - vy * cfg.substeps
        val bn = beams.size
        for (j in 0 until bn) {
            if (!beams.isAlive(j) || beams.isNew(j)) continue
            if ((beams.flags[j] and BeamFlags.DEBRIS) != 0 || beams.hitCooldownTicks[j] > 0) continue
            val a = beams.a[j]; val b = beams.b[j]
            val ax = nodes.x[a]; val ay = nodes.y[a]; val bx = nodes.x[b]; val by = nodes.y[b]
            val r = mats[beams.materialOf[j]].thickness * 0.5f + dc.impactRadius
            // Grobtest (Achsen-Box)
            if (FloatMath.max(ax, bx) < FloatMath.min(x, sx) - r) continue
            if (FloatMath.min(ax, bx) > FloatMath.max(x, sx) + r) continue
            if (FloatMath.max(ay, by) < FloatMath.min(y, sy) - r) continue
            if (FloatMath.min(ay, by) > FloatMath.max(y, sy) + r) continue
            val r2 = r * r
            val d2 = Geometry.segmentSegmentDistSq(sx, sy, x, y, ax, ay, bx, by)
            if (d2 >= r2) continue
            // Nur Eintritt in die Kapsel zählt: Lag der Knoten zu Tickbeginn schon darin (anliegend, oder als
            // Bruchhälften-Ende genau auf dem Gelenk, von dem er abgebrochen ist), ist das kein neuer Einschlag.
            if (Geometry.pointSegmentDistSq(sx, sy, ax, ay, bx, by) < r2) continue

            val mass = nodes.mass[i]
            val dmg = FloatMath.clamp((sp - 3f) * mass * 0.01f, dc.impactDamageMin, dc.impactDamageMax)
            beams.hitCooldownTicks[j] = dc.hitCooldownTicks
            // Trümmer abbremsen
            nodes.px[i] = x - vx * 0.3f
            nodes.py[i] = y - vy * 0.3f
            // Stoß auf die Balkenknoten in Flugrichtung (Prototyp: k = clamp(m · v · 0,05, 0, 40), dv = k · invMass · 40)
            val k = FloatMath.clamp(mass * sp * 0.05f, 0f, 40f)
            val ux = vx / (sp * h); val uy = vy / (sp * h)
            push(state, a, ux, uy, k, h)
            push(state, b, ux, uy, k, h)
            ctx.fx.add(FxEvent.Hit(state.tick, x, y, HitTarget.BEAM, beams.uidOf[j], dmg, -1))
            BeamBreaker.damage(state, ctx, j, dmg, x, y, BreakCause.DAMAGE)
            return
        }
    }

    private fun push(state: GameState, node: Int, ux: Float, uy: Float, k: Float, h: Float) {
        val nodes = state.nodes
        val inv = nodes.invMass[node]
        if (inv == 0f) return
        val dv = FloatMath.min(k * inv * 40f, state.config.combat.explosionDvCap)
        nodes.px[node] -= ux * dv * h
        nodes.py[node] -= uy * dv * h
    }

    private fun firstAliveAdjacent(state: GameState, i: Int): Int {
        val nodes = state.nodes
        val beams = state.beams
        val s = nodes.adjStart[i]; val e = nodes.adjStart[i + 1]
        for (k in s until e) {
            val j = nodes.adjBeam[k]
            if (beams.isAlive(j) && (beams.a[j] == i || beams.b[j] == i)) return j
        }
        return -1
    }

    private fun breakAllAdjacent(state: GameState, ctx: StepContext, i: Int) {
        while (true) {
            val j = firstAliveAdjacent(state, i)
            if (j < 0) return
            BeamBreaker.breakBeam(state, ctx, j, 0.5f, BreakCause.DECAY)
        }
    }
}
