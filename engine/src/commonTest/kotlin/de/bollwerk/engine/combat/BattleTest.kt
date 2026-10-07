package de.bollwerk.engine.combat

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.physics.PhysicsSystems
import de.bollwerk.engine.view.FxEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Gescriptete Gefechte: Determinismus (Hash-Gleichheit) und Laufzeit. */
class BattleTest {
    private class Fort(val weapons: IntArray)

    /** Kleine Festung: zwei Stockwerke Holz/Metall, Tür, Reaktor, alle sechs Waffen. [dir] = +1 schießt nach rechts. */
    private fun fort(rig: CombatRig, owner: Int, x0: Float, dir: Float): Fort {
        val g = 34f
        fun nx(dx: Float) = x0 + dir * dx
        val n = Array(3) { row -> IntArray(4) { col -> rig.node(nx(col * 3f), g - row * 3.5f, anchored = row == 0, owner = owner) } }
        val floor1 = IntArray(3); val floor2 = IntArray(3)
        for (c in 0 until 4) {
            rig.beam(n[0][c], n[1][c], if (c == 3) CombatTables.DOOR else CombatTables.WOOD, owner)
            rig.beam(n[1][c], n[2][c], if (c % 2 == 0) CombatTables.METAL else CombatTables.WOOD, owner)
        }
        for (c in 0 until 3) {
            floor1[c] = rig.beam(n[1][c], n[1][c + 1], CombatTables.WOOD, owner)
            floor2[c] = rig.beam(n[2][c], n[2][c + 1], if (c == 1) CombatTables.ARMOUR else CombatTables.METAL, owner)
            rig.beam(n[0][c], n[1][c + 1], CombatTables.WOOD, owner)
            rig.beam(n[1][c], n[2][c + 1], CombatTables.METAL, owner)
        }
        // Balken laufen bei dir < 0 von rechts nach links: Normale umdrehen, damit Geräte oben sitzen
        val top = dir > 0f
        rig.device(CombatTables.REACTOR, rig.beam(n[0][0], n[0][1], CombatTables.ARMOUR, owner), owner = owner, sideNegative = top)
        val w = intArrayOf(
            rig.device(CombatTables.MORTAR, floor2[0], 0.5f, owner, sideNegative = top),
            rig.device(CombatTables.CANNON, floor2[2], 0.5f, owner, sideNegative = top),
            rig.device(CombatTables.MG, floor1[2], 0.3f, owner, sideNegative = top),
            rig.device(CombatTables.ROCKET, floor2[1], 0.5f, owner, sideNegative = top),
            rig.device(CombatTables.SNIPER, floor1[1], 0.5f, owner, sideNegative = top),
            rig.device(CombatTables.LASER, floor1[0], 0.5f, owner, sideNegative = top),
        )
        return Fort(w)
    }

    private fun battle(seed: Long, ticks: Int = 1200): Pair<CombatRig, Long> {
        val rig = CombatRig(seed = seed)
        rig.state.wind = 2.5f
        val forts = arrayOf(fort(rig, 0, 20f, 1f), fort(rig, 1, 100f, -1f))
        PhysicsSystems.settle(rig.state, 120)
        val script = Random(1234) // Script-Zufall (Testeingabe), unabhängig vom Sim-Seed
        val elev = floatArrayOf(45f, 12f, 3f, 35f, 4f, 2f)
        for (t in 0 until ticks) {
            if (t % 20 == 0) {
                for (p in 0 until 2) {
                    val f = forts[p]
                    val k = script.nextInt(f.weapons.size)
                    val d = f.weapons[k]
                    if (!rig.state.devices.isAlive(d)) continue
                    val e = (elev[k] + script.nextInt(-8, 9)) * FloatMath.DEG_TO_RAD
                    val ang = if (p == 0) e else FloatMath.PI - e
                    val pow = 0.6f + script.nextInt(0, 41) / 100f
                    val ref = rig.state.devices.ref(d)
                    rig.command(Command.SetAim(rig.state.tick, p, ref, ang, pow))
                    rig.command(Command.Fire(rig.state.tick, p, ref))
                }
            }
            if (t % 300 == 0) { rig.state.players[0].metal += 300f; rig.state.players[1].metal += 300f; rig.state.players[0].energy = 400f; rig.state.players[1].energy = 400f }
            rig.tick()
        }
        return rig to StateHash.of(rig.state)
    }

    @Test
    fun scriptedBattleIsDeterministic() {
        val (rigA, a) = battle(77L)
        val (_, b) = battle(77L)
        assertEquals(a, b)
        // es ist tatsächlich gekämpft worden
        assertTrue(rigA.events<FxEvent.Fired>().size > 20, "fired ${rigA.events<FxEvent.Fired>().size}")
        assertTrue(rigA.events<FxEvent.Explosion>().size > 5, "explosions ${rigA.events<FxEvent.Explosion>().size}")
        assertTrue(rigA.events<FxEvent.Hit>().isNotEmpty())
        assertTrue(rigA.events<FxEvent.LaserBeam>().isNotEmpty())
        // anderer Seed (MG-Streuung, Feuer, Trümmer) → anderer Verlauf
        assertNotEquals(a, battle(78L).second)
    }

    @Test
    fun hundredProjectilesAndThreeHundredBeamsUnderBudget() {
        val rig = CombatRig(seed = 5L)
        // Gitterturm aus Metall: 300 Balken
        val cols = 11
        val ids = ArrayList<IntArray>()
        var beams = 0
        var row = 0
        while (beams < 300) {
            val r = IntArray(cols) { c -> rig.node(50f + c * 2f, 34f - row * 2f, anchored = row == 0) }
            if (row > 0) {
                val prev = ids[row - 1]
                for (c in 0 until cols) {
                    if (beams < 300) { rig.beam(prev[c], r[c], CombatTables.METAL); beams++ }
                    if (c + 1 < cols && beams < 300) { rig.beam(r[c], r[c + 1], CombatTables.METAL); beams++ }
                    if (c + 1 < cols && beams < 300) { rig.beam(prev[c], r[c + 1], CombatTables.METAL); beams++ }
                }
            }
            ids.add(r)
            row++
        }
        assertEquals(300, rig.state.beams.aliveCount)
        PhysicsSystems.settle(rig.state, 60)
        val rnd = Random(9)
        val p = rig.state.projectiles
        fun topUp() {
            while (p.aliveCount < 100) {
                val kind = if (rnd.nextBoolean()) CombatTables.W_MORTAR else CombatTables.W_CANNON
                p.alloc(
                    rnd.nextInt(-30, 20).toFloat(), rnd.nextInt(-60, 0).toFloat(),
                    rnd.nextInt(5, 40).toFloat(), rnd.nextInt(-30, 5).toFloat(), kind, 1, 2000,
                )
            }
        }
        // Aufwärmen (JIT), danach bester von 5 Messblöcken: Wanduhrzeit auf geteilten CI-Runnern
        // schwankt durch GC und Nachbarlast; der beste Block misst die Kosten des Codes, nicht der Umgebung.
        for (k in 0 until 900) { topUp(); rig.tick() }
        val blocks = 5
        val ticksPerBlock = 120
        var best = Long.MAX_VALUE
        var maxProj = 0
        for (b in 0 until blocks) {
            var total = 0L
            for (k in 0 until ticksPerBlock) {
                topUp()
                if (p.aliveCount > maxProj) maxProj = p.aliveCount
                val mark = TimeSource.Monotonic.markNow()
                rig.tick()
                total += mark.elapsedNow().inWholeNanoseconds
            }
            if (total < best) best = total
        }
        val msPerTick = best / 1e6 / ticksPerBlock
        println("combat perf: ${(msPerTick * 1000).toLong() / 1000.0} ms/tick (physics + combat), ${rig.state.beams.aliveCount} beams alive")
        assertEquals(100, maxProj)
        assertTrue(msPerTick < 1.5, "tick took $msPerTick ms")
    }
}
