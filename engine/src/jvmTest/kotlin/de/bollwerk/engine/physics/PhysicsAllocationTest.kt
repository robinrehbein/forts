package de.bollwerk.engine.physics

import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.SystemSlot
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regressionswächter: Die vier WP2-Systeme (PHYSICS, STRAIN_DAMAGE, TOPOLOGY, DEBRIS) allokieren im Normalbetrieb
 * (statisches Bauwerk, ruhende Trümmer, kein Bruch) **nichts** im Hot-Path. Gemessen wird nur um die vier
 * `step`-Aufrufe herum (nicht `GameState.beginTick`, dessen Pool-Schleife Welle 0 gehört).
 */
class PhysicsAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private var fxEvents = 0

    private fun allocated(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().id)

    /** Fachwerk-Raster mit Geräten plus ein ruhender Trümmerbalken auf dem Boden. */
    private fun scene(): PhysicsRig {
        val rig = PhysicsRig()
        val cols = 8; val rows = 4; val cell = 2f
        val id = Array(rows + 1) { r -> IntArray(cols + 1) { c -> rig.node(20f + c * cell, 34f - r * cell, anchored = r == 0) } }
        var floor = -1
        for (r in 0..rows) for (c in 0..cols) {
            if (c < cols && r > 0) floor = rig.beam(id[r][c], id[r][c + 1], PhysicsTables.METAL)
            if (r < rows) rig.beam(id[r][c], id[r + 1][c], PhysicsTables.WOOD)
            if (r < rows && c < cols) rig.beam(id[r][c], id[r + 1][c + 1], PhysicsTables.WOOD)
        }
        rig.device(PhysicsTables.REACTOR, floor, 0.5f)
        // ruhender Trümmerbalken (altert, würfelt aber erst nach decayAfterTicks)
        val a = rig.node(60f, 34f - 0.12f); val b = rig.node(61.5f, 34f - 0.12f)
        rig.beam(a, b, PhysicsTables.WOOD)
        return rig
    }

    private fun stepSystems(rig: PhysicsRig, systems: Array<SimSystem>, measure: Boolean): Long {
        val state = rig.state
        rig.ctx.beginTick(state.tick)
        state.beginTick()
        val before = if (measure) allocated() else 0L
        for (s in systems) s.step(state, rig.ctx)
        val bytes = if (measure) allocated() - before else 0L
        if (measure) fxEvents += rig.ctx.fx.size
        state.endTick()
        state.tick++
        return bytes
    }

    @Test
    fun physicsSystemsAllocateNothingPerTick() {
        val order = arrayOf(SystemSlot.PHYSICS, SystemSlot.STRAIN_DAMAGE, SystemSlot.TOPOLOGY, SystemSlot.DEBRIS)
        // JIT-Aufwärmen auf einer eigenen Szene
        val warm = scene()
        val warmSystems = Array(order.size) { warm.systems.getValue(order[it]) }
        for (k in 0 until 3000) stepSystems(warm, warmSystems, measure = false)

        val rig = scene()
        val systems = Array(order.size) { rig.systems.getValue(order[it]) }
        for (k in 0 until 30) stepSystems(rig, systems, measure = false) // Einschwingen, erster Topologie-Aufbau
        // Messaufwand von getThreadAllocatedBytes selbst herausrechnen
        val probe0 = allocated(); val probeOverhead = allocated() - probe0
        var total = 0L
        val ticks = 600
        for (k in 0 until ticks) total += stepSystems(rig, systems, measure = true) - probeOverhead
        println("ALLOC: $total bytes over $ticks ticks (${rig.state.beams.aliveCount} beams, probe overhead $probeOverhead B)")
        assertEquals(0, fxEvents, "scene must stay static (no breaks, hits or landings)")
        assertTrue(rig.state.nodes.debrisTicks.any { it > 0 }, "debris path must be exercised")
        assertTrue(total <= 0L, "physics hot path allocated $total bytes over $ticks ticks")
    }
}
