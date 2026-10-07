package de.bollwerk.simrunner

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameState

/**
 * Testbank-Eingriffe, die kein Spieler-Command sind (siehe [Scenario]). Sie werden vor dem Tick angewendet
 * und sind rein vom Tick im Szenario abhängig, also deterministisch.
 */
object Cheats {
    val ACTIONS: Set<String> = setOf("ignite", "spawnProjectiles", "addLattice")

    /** @return Meldung bei wirkungslosem Schritt (z. B. unbekanntes Material), sonst null. */
    fun apply(state: GameState, step: ScriptStep): String? = when (step.action) {
        "ignite" -> ignite(state, step)
        "spawnProjectiles" -> spawn(state, step)
        "addLattice" -> lattice(state, step)
        else -> "unknown cheat '${step.action}'"
    }

    private fun ignite(state: GameState, s: ScriptStep): String? {
        val mat = state.tables.materials.indexOfFirst { it.key == s.material }
        if (mat < 0) return "ignite: unknown material '${s.material}'"
        val b = state.beams
        var seen = 0
        var lit = 0
        for (j in 0 until b.size) {
            if (!b.isAlive(j) || b.ownerOf[j] != s.player || b.materialOf[j] != mat) continue
            if ((b.flags[j] and BeamFlags.DEBRIS) != 0) continue
            if (seen++ < s.skip) continue
            if (lit >= s.beams) break
            if (b.fuelOf[j] > 0f && state.tables.materials[mat].flammable) { b.fireOf[j] = s.fire; lit++ }
        }
        return if (lit == 0) "ignite: no flammable '${s.material}' beam found for player ${s.player}" else null
    }

    private fun spawn(state: GameState, s: ScriptStep): String? {
        val w = state.tables.weapons.indexOfFirst { it.key == s.weapon }
        if (w < 0) return "spawnProjectiles: unknown weapon '${s.weapon}'"
        val wp = state.tables.weapons[w]
        val ttl = if (wp.ttlTicks > 0) wp.ttlTicks else 600
        for (k in 0 until s.projectiles) {
            state.projectiles.alloc(
                s.x + k * s.dx, s.y, s.vx + k * s.dvx, s.vy + k * s.dvy,
                kind = w, owner = s.player, ttlTicks = ttl, incendiary = s.incendiary || wp.igniteRadius > 0f,
            )
        }
        return null
    }

    private fun lattice(state: GameState, s: ScriptStep): String? {
        val mat = state.tables.materials.indexOfFirst { it.key == s.material }
        if (mat < 0) return "addLattice: unknown material '${s.material}'"
        if (s.cols < 1 || s.rows < 1) return "addLattice: cols and rows must be >= 1"
        val props = state.tables.materials[mat]
        val nodes = state.nodes
        val beams = state.beams
        val ids = IntArray((s.cols + 1) * (s.rows + 1))
        fun at(c: Int, r: Int) = r * (s.cols + 1) + c
        for (r in 0..s.rows) for (c in 0..s.cols) {
            ids[at(c, r)] = nodes.alloc(s.x + c * s.cell, s.y - r * s.cell, s.player, anchored = r == 0)
        }
        fun link(a: Int, b: Int) {
            val dx = nodes.x[b] - nodes.x[a]; val dy = nodes.y[b] - nodes.y[a]
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            beams.alloc(a, b, mat, len * props.restLengthFactor, props.hp, s.player)
        }
        for (r in 0..s.rows) for (c in 0..s.cols) {
            if (c < s.cols) link(ids[at(c, r)], ids[at(c + 1, r)])
            if (r < s.rows) link(ids[at(c, r)], ids[at(c, r + 1)])
            if (c < s.cols && r < s.rows) link(ids[at(c, r)], ids[at(c + 1, r + 1)])
        }
        state.rebuildDerived()
        return null
    }
}
