package de.bollwerk.engine.combat

import de.bollwerk.engine.physics.BeamBreaker
import de.bollwerk.engine.rng.SplitMix64
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.SystemSlot
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FireRepairTest {
    private val fireOnly = setOf(SystemSlot.FIRE)
    private val repairOnly = setOf(SystemSlot.REPAIR)

    @Test
    fun fireSpreadsToAdjacentWoodButNotToMetal() {
        val rig = CombatRig(seed = 5L, slots = fireOnly)
        rig.state.wind = 12f
        val shared = rig.node(20f, 30f)
        val burning = rig.beam(rig.node(17f, 30f), shared, CombatTables.WOOD)
        val wood = rig.beam(shared, rig.node(23f, 27f), CombatTables.WOOD)
        val metal = rig.beam(shared, rig.node(20f, 26f), CombatTables.METAL)
        val loneWood = rig.beamAt(40f, 30f, 43f, 30f, CombatTables.WOOD)
        assertTrue(CombatOps.ignite(rig.state, rig.ctx, burning))
        assertFalse(CombatOps.ignite(rig.state, rig.ctx, metal))
        val b = rig.state.beams
        val t = rig.runUntil(1200) { b.fireOf[wood] > 0f }
        assertTrue(t > 0, "fire did not spread")
        assertEquals(1, rig.events<FxEvent.Ignited>().count { it.beamUid == b.uidOf[wood] })
        rig.run(1200)
        assertEquals(0f, b.fireOf[metal])
        assertEquals(260f, b.hpOf[metal])
        assertEquals(0f, b.fireOf[loneWood])
    }

    @Test
    fun burningBeamCharsAndBreaks() {
        val rig = CombatRig(slots = fireOnly)
        val beam = rig.beamAt(20f, 30f, 23f, 30f, CombatTables.WOOD)
        CombatOps.ignite(rig.state, rig.ctx, beam)
        val b = rig.state.beams
        rig.run(60)
        assertTrue(b.fireOf[beam] > 0.5f)
        assertTrue(b.fuelOf[beam] < 1f)
        assertTrue(b.hpOf[beam] < 100f)
        val ticks = rig.runUntil(1200) { !b.isAlive(beam) }
        assertTrue(ticks > 0, "burning beam never broke")
        val broken = rig.events<FxEvent.BeamBroken>().first()
        assertEquals(BreakCause.FIRE, broken.cause)
        assertTrue(broken.t in BeamBreaker.RANDOM_T_MIN..BeamBreaker.RANDOM_T_MAX)
        // Prototyp: fire → 1 nach ~1,7 s, dann 12 % maxHp/s → Bruch nach rund 9–10 s
        assertTrue(60 + ticks in 480..700, "broke after ${60 + ticks} ticks")
    }

    @Test
    fun fireIsDeterministicForSeed() {
        fun run(seed: Long): Long {
            val rig = CombatRig(seed = seed, slots = fireOnly)
            rig.state.wind = 2f
            var prev = rig.node(0f, 30f)
            for (i in 1..12) {
                val n = rig.node(i * 2f, 30f - (i % 2))
                rig.beam(prev, n, CombatTables.WOOD)
                prev = n
            }
            CombatOps.ignite(rig.state, rig.ctx, 0)
            rig.run(900)
            var h = 0L
            val b = rig.state.beams
            for (j in 0 until b.size) h = h * 31 + (if (b.isAlive(j)) b.fireOf[j].toRawBits() else -1)
            return h
        }
        assertEquals(run(9L), run(9L))
    }

    @Test
    fun repairRestoresHpOver120TicksAndCostsMetal() {
        val rig = CombatRig(slots = repairOnly)
        val beam = rig.beamAt(20f, 30f, 23f, 30f, CombatTables.WOOD)
        val b = rig.state.beams
        b.hpOf[beam] = 1f
        b.flags[beam] = b.flags[beam] or BeamFlags.REPAIRING
        val metal0 = rig.state.players[0].metal
        rig.run(60)
        assertEquals(51f, b.hpOf[beam], 0.1f)
        rig.run(59)
        assertEquals(100f, b.hpOf[beam])
        assertEquals(0, b.flags[beam] and BeamFlags.REPAIRING)
        assertEquals(1, rig.events<FxEvent.BeamRepaired>().size)
        // costFactor 0,5 × 4 ⚙/m × 3 m × 99 % geheilt
        val spent = metal0 - rig.state.players[0].metal
        assertEquals(0.5f * 4f * 3f * 0.99f, spent, 1e-2f)
    }

    @Test
    fun repairCostIsProportionalToMissingHp() {
        val spent = FloatArray(2)
        for ((k, hp) in listOf(50f, 75f).withIndex()) {
            val rig = CombatRig(slots = repairOnly)
            val beam = rig.beamAt(20f, 30f, 25f, 30f, CombatTables.METAL)
            val b = rig.state.beams
            b.hpOf[beam] = hp * 2.6f
            b.flags[beam] = b.flags[beam] or BeamFlags.REPAIRING
            rig.run(200)
            assertEquals(260f, b.hpOf[beam])
            spent[k] = 1000f - rig.state.players[0].metal
        }
        assertEquals(2f * spent[1], spent[0], 1e-3f)
    }

    @Test
    fun repairIsBlockedWhileBurning() {
        val rig = CombatRig(slots = repairOnly)
        val beam = rig.beamAt(20f, 30f, 23f, 30f, CombatTables.WOOD)
        val b = rig.state.beams
        b.hpOf[beam] = 40f
        b.flags[beam] = b.flags[beam] or BeamFlags.REPAIRING
        b.fireOf[beam] = 0.5f
        rig.run(120)
        assertEquals(40f, b.hpOf[beam])
        assertEquals(1000f, rig.state.players[0].metal)
        assertTrue((b.flags[beam] and BeamFlags.REPAIRING) != 0, "repair pauses, it is not cancelled")
        b.fireOf[beam] = 0f
        rig.run(120)
        assertEquals(100f, b.hpOf[beam])
    }

    @Test
    fun repairStopsWithoutMetal() {
        val rig = CombatRig(slots = repairOnly)
        val beam = rig.beamAt(20f, 30f, 23f, 30f, CombatTables.WOOD)
        val b = rig.state.beams
        b.hpOf[beam] = 10f
        b.flags[beam] = b.flags[beam] or BeamFlags.REPAIRING
        rig.state.players[0].metal = 1f
        rig.run(120)
        assertEquals(0f, rig.state.players[0].metal)
        // 1 ⚙ heilt 1 / (0,5 · 4 · 3) · 100 TP
        assertEquals(10f + 100f / 6f, b.hpOf[beam], 1e-2f)
        assertEquals(0, b.flags[beam] and BeamFlags.REPAIRING)
    }

    /** Die Bruchstelle eines verbrannten Balkens kommt aus `rngFire` (CLAUDE.md Regel 8), nicht aus `rngDebris`. */
    @Test
    fun burntBeamBreakPositionDrawsFromFireStream() {
        val rig = CombatRig(slots = fireOnly)
        val beam = rig.beamAt(20f, 30f, 23f, 30f, CombatTables.WOOD)
        CombatOps.ignite(rig.state, rig.ctx, beam)
        val b = rig.state.beams
        // isolierter Balken: rngFire wird nur für die Bruchstelle gezogen
        val fire0 = rig.state.rngFire.state
        assertTrue(rig.runUntil(1200) { !b.isAlive(beam) } > 0)
        val expected = SplitMix64(fire0).nextFloat(BeamBreaker.RANDOM_T_MIN, BeamBreaker.RANDOM_T_MAX)
        assertEquals(expected, rig.events<FxEvent.BeamBroken>().first().t)
        assertEquals(SplitMix64(fire0).also { it.nextLong() }.state, rig.state.rngFire.state)
    }
}
