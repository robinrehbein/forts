package de.bollwerk.engine.physics

import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.physics.PhysicsTables.ANVIL
import de.bollwerk.engine.physics.PhysicsTables.BALLAST
import de.bollwerk.engine.physics.PhysicsTables.BOB
import de.bollwerk.engine.physics.PhysicsTables.CANNON
import de.bollwerk.engine.physics.PhysicsTables.CRATE
import de.bollwerk.engine.physics.PhysicsTables.FACTORY
import de.bollwerk.engine.physics.PhysicsTables.LOAD
import de.bollwerk.engine.physics.PhysicsTables.METAL
import de.bollwerk.engine.physics.PhysicsTables.MORTAR
import de.bollwerk.engine.physics.PhysicsTables.REACTOR
import de.bollwerk.engine.physics.PhysicsTables.ROPE
import de.bollwerk.engine.physics.PhysicsTables.WOOD
import de.bollwerk.engine.physics.PhysicsTables.WOOD_INVERTED_DAMPING
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DebrisConfig
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.SystemSlot
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.sqrt
import kotlin.time.TimeSource

class TrussPhysicsTest {

    /** Knoten-Raster `nodes[Reihe][Spalte]` (Reihe 0 = Anker), alle Balken und die Bodenbalken je Geschoss. */
    private class Truss(val nodes: Array<IntArray>, val beams: IntArray, private val floorBeams: Array<IntArray>) {
        /** Bodenbalken von Geschoss [storey] (1 oder 2), Feld [bay] (0 oder 1). */
        fun floor(storey: Int, bay: Int): Int = floorBeams[storey][bay]
    }

    /**
     * 2-geschossiges Fachwerk (3 m Raster) auf Ankern bei y = 34. [triangulated] = mit Diagonalen; [skew] verschiebt
     * die oberen Knoten seitlich (Imperfektion). [withDevices]: Last (300 kg) und Kiste wie in der ersten Fassung.
     */
    private fun twoStorey(
        rig: PhysicsRig, x0: Float = 20f, material: Int = WOOD,
        triangulated: Boolean = true, skew: Float = 0f, withDevices: Boolean = true,
    ): Truss {
        val xs = floatArrayOf(x0, x0 + 3f, x0 + 6f)
        val ys = floatArrayOf(34f, 31f, 28f)
        val id = Array(3) { r -> IntArray(3) { c -> rig.node(xs[c] + skew * r, ys[r], anchored = r == 0) } }
        val beams = ArrayList<Int>()
        val floors = Array(3) { IntArray(2) { -1 } }
        for (r in 0 until 3) for (c in 0 until 3) {
            if (c < 2 && r > 0) { val f = rig.beam(id[r][c], id[r][c + 1], material); floors[r][c] = f; beams += f }
            if (r < 2) beams += rig.beam(id[r][c], id[r + 1][c], material)
            if (r < 2 && c < 2 && triangulated) {
                beams += rig.beam(id[r][c], id[r + 1][c + 1], material)
                beams += rig.beam(id[r][c + 1], id[r + 1][c], material)
            }
        }
        if (withDevices) {
            rig.device(LOAD, floors[2][1])
            rig.device(CRATE, beams[0])
        }
        return Truss(id, beams.toIntArray(), floors)
    }

    /** Ausstattung im Maßstab der Stil-Bibel: Reaktor + Waffen oben, Fabrik + Lager-Ballast im 1. Stock. */
    private fun equip(rig: PhysicsRig, t: Truss) {
        rig.device(REACTOR, t.floor(2, 0), 0.5f)
        rig.device(CANNON, t.floor(2, 1), 0.3f)
        rig.device(MORTAR, t.floor(2, 1), 0.8f)
        rig.device(FACTORY, t.floor(1, 0), 0.5f)
        rig.device(BALLAST, t.floor(1, 1), 0.5f)
    }

    // (1)
    @Test
    fun triangulatedTwoStoreyTrussStands600Ticks() {
        val rig = PhysicsRig()
        val t = twoStorey(rig, withDevices = false)
        equip(rig, t)
        val top = t.nodes[2][0]
        val x0 = rig.state.nodes.x[top]; val y0 = rig.state.nodes.y[top]
        var maxRatio = 0f
        rig.run(600) { val r = rig.maxStrainRatio(); if (r > maxRatio) maxRatio = r }
        println("two-storey truss: peak strain ratio $maxRatio")
        assertEquals(0, rig.breaks(), "no beam may break")
        assertTrue(maxRatio < 0.7f, "max strain ratio $maxRatio must stay < 70 % of the limit")
        assertTrue(maxRatio > 0.2f, "the loaded truss must carry a substantial load (ratio $maxRatio)")
        assertEquals(5, rig.state.devices.aliveCount)
        for (i in 0 until rig.state.nodes.size) {
            assertEquals(0, rig.state.nodes.flags[i] and NodeFlags.DEBRIS, "node $i must not be debris")
        }
        val n = rig.state.nodes
        val drift = sqrt((n.x[top] - x0) * (n.x[top] - x0) + (n.y[top] - y0) * (n.y[top] - y0))
        assertTrue(drift < 0.05f, "triangulated truss must stay in shape (top moved $drift m)")
    }

    /** Gegenprobe zu (1): Ohne Diagonalen ist das Gelenk-Rahmenwerk ein Mechanismus und klappt unter Last seitlich weg. */
    @Test
    fun untriangulatedFrameFoldsUnderTheSameLoad() {
        val rig = PhysicsRig()
        val t = twoStorey(rig, triangulated = false, skew = 0.05f, withDevices = false)
        equip(rig, t)
        val top = t.nodes[2][0]
        val y0 = rig.state.nodes.y[top]
        rig.run(600)
        val drop = rig.state.nodes.y[top] - y0
        println("untriangulated frame: top dropped $drop m")
        assertTrue(drop > 3f, "pin-jointed rectangles must fold down (top dropped $drop m)")
    }

    // (2)
    @Test
    fun cantileverSagsButHolds() {
        val rig = PhysicsRig()
        val a0 = rig.node(20f, 28f, anchored = true)
        val b0 = rig.node(20f, 31f, anchored = true)
        val a1 = rig.node(23f, 28f); val b1 = rig.node(23f, 31f)
        val a2 = rig.node(26f, 28f); val b2 = rig.node(26f, 31f)
        rig.beam(a0, a1); rig.beam(a1, a2)
        rig.beam(b0, b1); rig.beam(b1, b2)
        rig.beam(a1, b1); val tip = rig.beam(a2, b2)
        rig.beam(a0, b1); rig.beam(a1, b2)
        rig.device(LOAD, tip, 1f)
        val y0 = rig.state.nodes.y[b2]
        rig.run(300)
        val sag = rig.state.nodes.y[b2] - y0
        println("cantilever tip sag: ${sag * 1000f} mm, max strain ratio ${rig.maxStrainRatio()}")
        assertTrue(sag > 0.002f, "tip must sag measurably (was $sag m)")
        assertTrue(sag < 0.3f, "tip must not collapse (sag $sag m)")
        assertEquals(0, rig.breaks())
        assertTrue(rig.maxStrainRatio() < 1f)
        assertEquals(0, rig.state.nodes.flags[b2] and NodeFlags.DEBRIS)
    }

    private fun hangingAnvil(material: Int): PhysicsRig {
        val rig = PhysicsRig()
        val top = rig.node(30f, 20f, anchored = true)
        val bottom = rig.node(30f, 23f)
        val beam = rig.beam(top, bottom, material)
        rig.device(ANVIL, beam, 1f)
        rig.run(300)
        return rig
    }

    // (3)
    @Test
    fun overloadedWoodBreaksSameLoadOnMetalHolds() {
        val wood = hangingAnvil(WOOD)
        val broken = wood.fx.filterIsInstance<FxEvent.BeamBroken>()
        assertTrue(broken.isNotEmpty(), "wood must break under 45 t")
        assertEquals(BreakCause.STRAIN, broken[0].cause)
        assertEquals(WOOD, broken[0].materialId)
        assertTrue(wood.fx.any { it is FxEvent.DeviceDestroyed }, "load on the broken beam dies")

        val metal = hangingAnvil(METAL)
        assertEquals(0, metal.breaks(), "metal must hold the same load")
        assertEquals(1, metal.state.devices.aliveCount)
        assertTrue(metal.maxStrainRatio() > 0.2f && metal.maxStrainRatio() < 1f, "metal ratio ${metal.maxStrainRatio()}")
    }

    // (4)
    @Test
    fun cuttingTheSupportMakesUpperPartDebrisThatFalls() {
        val rig = PhysicsRig()
        val g1 = rig.node(10f, 34f, anchored = true)
        val g2 = rig.node(13f, 34f, anchored = true)
        val p = rig.node(10f, 31f); val q = rig.node(13f, 31f); val r = rig.node(11.5f, 28.5f)
        val s1 = rig.beam(g1, p); val s2 = rig.beam(g2, q); val s3 = rig.beam(g1, q)
        val pq = rig.beam(p, q); rig.beam(p, r); rig.beam(q, r)
        val dev = rig.device(CRATE, pq)
        val devRef = rig.state.devices.ref(dev)
        rig.run(60)
        assertEquals(0, rig.state.nodes.flags[p] and NodeFlags.DEBRIS)
        val yBefore = rig.state.nodes.y[r]
        val refs = longArrayOf(rig.state.beams.ref(s1), rig.state.beams.ref(s2), rig.state.beams.ref(s3))
        rig.inTick { c -> for (ref in refs) assertTrue(BeamBreaker.breakAt(rig.state, ref, 0.5f, BreakCause.DAMAGE, c)) }
        rig.run(29)
        val n = rig.state.nodes
        for (i in intArrayOf(p, q, r)) assertTrue((n.flags[i] and NodeFlags.DEBRIS) != 0, "node $i must be debris")
        assertTrue((rig.state.beams.flags[pq] and BeamFlags.DEBRIS) != 0)
        assertEquals(-1, rig.state.devices.resolve(devRef), "device on debris must die")
        assertTrue(rig.fx.any { it is FxEvent.DeviceDestroyed })
        val fall = n.y[r] - yBefore
        assertTrue(fall > 0.5f, "upper part must fall (fell $fall m)")
        // Anker bleiben, sind keine Trümmer
        assertEquals(0, n.flags[g1] and NodeFlags.DEBRIS)
        // bis zum Boden fallen und Staub erzeugen
        rig.run(120)
        assertTrue(rig.fx.any { it is FxEvent.DebrisLanded }, "dust on ground impact")
    }

    // (5)
    @Test
    fun ropeOnlyResistsTension() {
        val cfg = SimConfig(gravity = 0f)
        fun rig(material: Int): Pair<PhysicsRig, Int> {
            val rig = PhysicsRig(cfg)
            val a = rig.node(20f, 20f, anchored = true)
            val b = rig.node(23f, 20f)
            rig.beam(a, b, material)
            rig.run(1)
            // Auf 1,5 m zusammendrücken (ruhend)
            rig.state.nodes.x[b] = 21.5f; rig.state.nodes.px[b] = 21.5f
            return rig to b
        }
        val (rope, rb) = rig(ROPE)
        rope.run(60)
        assertEquals(21.5f, rope.state.nodes.x[rb], "compressed rope must not push")
        assertEquals(0, rope.breaks())
        // Ziehen: über die Ruhelänge 3,12 m hinaus → wird zurückgezogen
        rope.state.nodes.x[rb] = 25f; rope.state.nodes.px[rb] = 25f
        rope.run(60)
        val restRope = rope.state.beams.restLen[0]
        assertTrue(rope.len(0) < restRope + 0.05f, "stretched rope must pull back (len ${rope.len(0)}, rest $restRope)")

        val (wood, wb) = rig(WOOD)
        wood.run(60)
        assertTrue(wood.state.nodes.x[wb] > 22.5f, "compressed wood pushes back (x ${wood.state.nodes.x[wb]})")
    }

    /** Mechanische Energie (Double, nur Messung): kinetisch + Lage + elastisch, KE/PE zeitgleich am Substep-Mittelpunkt. */
    private fun energy(rig: PhysicsRig): Double {
        val s = rig.state
        val n = s.nodes
        val b = s.beams
        val h = (s.config.dt / s.config.substeps).toDouble()
        val g = s.config.gravity.toDouble()
        var e = 0.0
        for (i in 0 until n.size) {
            if (!n.isAlive(i) || n.isAnchored(i)) continue
            val m = n.mass[i].toDouble()
            val vx = (n.x[i].toDouble() - n.px[i].toDouble()) / h
            val vy = (n.y[i].toDouble() - n.py[i].toDouble()) / h
            val ym = (n.y[i].toDouble() + n.py[i].toDouble()) * 0.5
            e += 0.5 * m * (vx * vx + vy * vy) - m * g * ym
        }
        for (j in 0 until b.size) {
            if (!b.isAlive(j)) continue
            val a = b.a[j]; val c = b.b[j]
            val dx = (n.x[c].toDouble() + n.px[c].toDouble() - n.x[a].toDouble() - n.px[a].toDouble()) * 0.5
            val dy = (n.y[c].toDouble() + n.py[c].toDouble() - n.y[a].toDouble() - n.py[a].toDouble()) * 0.5
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            val rest = b.restLen[j].toDouble()
            val ea = s.tables.materials[b.materialOf[j]].stiffness.toDouble()
            val c0 = len - rest
            e += 0.5 * ea / rest * c0 * c0
        }
        return e
    }

    /**
     * Frei schwingendes Holzdreieck an einem Anker mit 3-t-Gewicht an der Spitze (nahe dem Ursprung für
     * Float-Genauigkeit); das schwere Gewicht regt axiale Schwingungen an, auf die die axiale Dämpfung wirkt.
     * @return größter Energiezuwachs je Tick und insgesamt verlorene Energie.
     */
    private fun swing(material: Int, cfg: SimConfig, ticks: Int, expectBreaks: Boolean = false): Pair<Double, Double> {
        val rig = PhysicsRig(cfg)
        val o = rig.node(1f, 1f, anchored = true)
        val a = rig.node(4f, 1f)
        val c = rig.node(2.5f, 3.6f)
        rig.beam(o, a, material); rig.beam(o, c, material); rig.beam(a, c, material)
        rig.device(BOB, 2, 1f)
        rig.tick()
        var prev = energy(rig)
        val start = prev
        var maxIncrease = Double.NEGATIVE_INFINITY
        for (k in 0 until ticks) {
            rig.tick()
            val e = energy(rig)
            if (e - prev > maxIncrease) maxIncrease = e - prev
            prev = e
        }
        if (!expectBreaks) assertEquals(0, rig.breaks())
        return maxIncrease to (start - prev)
    }

    // (6)
    @Test
    fun mechanicalEnergyNeverIncreasesWithDamping() {
        val (inc, lost) = swing(WOOD, SimConfig.DEFAULT, 600)
        println("swing: max energy change per tick $inc J, total lost $lost J")
        // Toleranz 0,01 J bei ~1e5 J Gesamtenergie (Float-Messrauschen der Positionen)
        assertTrue(inc <= 1e-2, "energy increased by $inc J in one tick")
        assertTrue(lost > 1000.0, "damping must remove energy (lost $lost J)")
    }

    /** Gegenprobe: Mit invertierter axialer Dämpfung (alter Prototyp-Fehler) schlägt der Detektor an. */
    @Test
    fun energyDetectorCatchesInvertedDampingSign() {
        val (inc, _) = swing(WOOD_INVERTED_DAMPING, SimConfig.DEFAULT, 600, expectBreaks = true)
        println("swing (inverted damping): max energy change per tick $inc J")
        assertTrue(inc > 100.0, "inverted damping must be detected as an energy increase (was $inc)")
        // Ohne globale Dämpfung: korrekte axiale Dämpfung bleibt im Messrauschen (Leapfrog-Phasenfehler < 1 J),
        // die invertierte pumpt Energie hinein.
        val (incAxial, _) = swing(WOOD, SimConfig(linearDamping = 0f), 600)
        val (incAxialInv, _) = swing(WOOD_INVERTED_DAMPING, SimConfig(linearDamping = 0f), 600, expectBreaks = true)
        println("swing (axial damping only): correct $incAxial J, inverted $incAxialInv J")
        assertTrue(incAxial < 1.0, "axial damping alone added $incAxial J")
        assertTrue(incAxialInv > 1000.0)
    }

    /** Szenario mit Brüchen, Trümmern, Zerfall und Einschlägen (für Determinismus). */
    private fun chaos(seed: Long): PhysicsRig {
        val rig = PhysicsRig(seed = seed)
        val beams = twoStorey(rig, 20f)
        twoStorey(rig, 40f, METAL)
        // Überladener Holz-Ausleger, der bricht und auf den Bau fällt
        val top = rig.node(26f, 22f, anchored = true)
        val hang = rig.node(26f, 25f)
        val hb = rig.beam(top, hang, WOOD)
        rig.device(ANVIL, hb, 1f)
        rig.run(1)
        val ref = rig.state.beams.ref(beams.beams[1])
        rig.inTick { c -> BeamBreaker.breakAt(rig.state, ref, 0.4f, BreakCause.DAMAGE, c) }
        return rig
    }

    // (7)
    @Test
    fun identicalRunsGiveIdenticalHashes() {
        val r1 = chaos(99L)
        val r2 = chaos(99L)
        for (k in 0 until 20) {
            r1.run(60); r2.run(60)
            assertEquals(StateHash.of(r1.state), StateHash.of(r2.state), "hash diverged at tick ${r1.state.tick}")
        }
        assertTrue(r1.breaks() >= 2, "scenario must contain breaks (${r1.breaks()})")
        assertEquals(r1.fx.size, r2.fx.size)
    }

    /**
     * (7b) Restore/Rollback: Wird mitten im Lauf (auch während Einsturz und Trümmerflug) ein frischer Physik-Cache
     * eingesetzt und alle DERIVED-Daten verworfen (`rebuildDerived()`), rechnet die Simulation bitgleich weiter –
     * der Grund für die Lösungsreihenfolge nach Balken-ID statt nach Position.
     */
    @Test
    fun restoringDerivedDataMidRunContinuesBitIdentically() {
        val ref = chaos(99L)
        val restored = chaos(99L)
        val restoreAt = setOf(5L, 30L, 100L, 300L, 700L, 900L)
        var restores = 0
        for (k in 0 until 1200) {
            if (restored.state.tick in restoreAt) { restored.restoreDerived(); restores++ }
            ref.tick(); restored.tick()
            if (k % 10 == 0 || restored.state.tick - 1 in restoreAt) {
                assertEquals(StateHash.of(ref.state), StateHash.of(restored.state), "hash diverged at tick ${ref.state.tick}")
            }
        }
        assertEquals(restoreAt.size, restores)
        assertEquals(StateHash.of(ref.state), StateHash.of(restored.state))
        assertTrue(ref.breaks() >= 2, "scenario must contain breaks (${ref.breaks()})")
        assertEquals(ref.fx.size, restored.fx.size)
    }

    /**
     * Ein Stepper, abwechselnd für zwei Zustände benutzt, darf keinen fremden Cache verwenden (Kennung im Zustand
     * statt Referenz auf den Zustand in der Welt): Ergebnis bitgleich zu je einem eigenen Stepper.
     */
    @Test
    fun oneStepperAlternatingBetweenTwoStatesStaysCorrect() {
        val a = chaos(11L); val b = chaos(12L)
        val refA = chaos(11L); val refB = chaos(12L)
        val shared = PhysicsSystems.stepper()
        val ctx = de.bollwerk.engine.sim.StepContext()
        for (k in 0 until 300) {
            ctx.beginTick(a.state.tick); shared.step(a.state, ctx)
            ctx.beginTick(b.state.tick); shared.step(b.state, ctx)
            refA.tick(); refB.tick()
        }
        assertEquals(StateHash.of(refA.state), StateHash.of(a.state))
        assertEquals(StateHash.of(refB.state), StateHash.of(b.state))
        // Die Welt hält keinen Zustand fest: die Kennung liegt im Zustand
        assertTrue(a.state.beams.solveCacheOwner !== refA.world)
    }

    // (8)
    @Test
    fun beamBreakerSplitConservesRestLengthAndCreatesTwoHalves() {
        val rig = PhysicsRig()
        val a = rig.node(20f, 30f, anchored = true)
        val b = rig.node(23f, 30f, anchored = true)
        val beam = rig.beam(a, b, WOOD)
        val dev = rig.device(CRATE, beam, 0.5f)
        rig.run(1)
        val bp = rig.state.beams
        val np = rig.state.nodes
        val rest = bp.restLen[beam]
        val uid = bp.uidOf[beam]
        val beamRef = bp.ref(beam)
        val beamsBefore = bp.aliveCount
        val nodesBefore = np.aliveCount
        val nodeSlotsBefore = np.size
        val breakTick = rig.state.tick
        // Bruch innerhalb des Ticks (Slot PROJECTILES), wie bei einem Treffer im Spiel
        rig.inTick { c ->
            assertTrue(BeamBreaker.breakAt(rig.state, beamRef, 0.3f, BreakCause.DAMAGE, c))
            assertFalse(bp.isAlive(beam))
            assertFalse(rig.state.devices.isAlive(dev))
            assertEquals(beamsBefore + 1, bp.aliveCount, "1 beam replaced by 2 halves")
            assertEquals(nodesBefore + 4, np.aliveCount)
            // stale ref → kein zweiter Bruch
            assertFalse(BeamBreaker.breakAt(rig.state, beamRef, 0.5f, BreakCause.DAMAGE, c))
        }
        var sum = 0f
        var halves = 0
        for (j in 0 until bp.size) {
            if (!bp.isAlive(j)) continue
            assertTrue((bp.flags[j] and BeamFlags.HALF) != 0)
            assertEquals(WOOD, bp.materialOf[j])
            assertEquals(breakTick, bp.allocTick[j], "halves are allocated in the break tick")
            // im Entstehungs-Tick übersprungen (isNew): kein Dehnungsschaden
            assertEquals(60f, bp.hpOf[j], 1e-4f)
            assertTrue((bp.flags[j] and BeamFlags.DEBRIS) != 0, "TOPOLOGY marks the free halves as debris in the same tick")
            sum += bp.restLen[j]
            halves++
        }
        assertEquals(2, halves)
        assertEquals(rest, sum, 1e-5f, "total rest length must be conserved")
        // DEBRIS übersprang die neuen Knoten im Entstehungs-Tick (isNew): Alter noch 0
        for (i in nodeSlotsBefore until np.size) {
            assertTrue(np.isAlive(i))
            assertEquals(breakTick, np.allocTick[i])
            assertTrue((np.flags[i] and NodeFlags.DEBRIS) != 0)
            assertEquals(0, np.debrisTicks[i], "new debris node $i must be skipped by DEBRIS in its creation tick")
        }
        val ev = rig.lastFx().filterIsInstance<FxEvent.BeamBroken>().single()
        assertEquals(uid, ev.beamUid)
        assertEquals(WOOD, ev.materialId)
        assertEquals(0.3f, ev.t)
        assertEquals(20.9f, ev.x, 1e-4f)
        assertEquals(30f, ev.y, 1e-4f)
        assertEquals(breakTick, ev.tick)
        assertEquals(BreakCause.DAMAGE, ev.cause)
        assertEquals(1, rig.lastFx().count { it is FxEvent.DeviceDestroyed }, "device on a damaged beam dies loudly")
        rig.tick()
        for (i in nodeSlotsBefore until np.size) assertEquals(1, np.debrisTicks[i], "aged from the next tick on")
        // Hälften fallen als Trümmer
        rig.run(29)
        for (j in 0 until bp.size) if (bp.isAlive(j)) assertTrue((bp.flags[j] and BeamFlags.DEBRIS) != 0)
        // Hälften teilen sich nicht erneut, Seile auch nicht
        val half = (0 until bp.size).first { bp.isAlive(it) }
        val before = bp.aliveCount
        rig.inTick { c -> assertTrue(BeamBreaker.breakBeam(rig.state, c, half, 0.5f, BreakCause.DAMAGE)) }
        assertEquals(before - 1, bp.aliveCount)
        val x = rig.node(40f, 30f, anchored = true); val y = rig.node(43f, 30f, anchored = true)
        val r = rig.beam(x, y, ROPE)
        val before2 = bp.aliveCount
        rig.inTick { c -> assertTrue(BeamBreaker.breakBeam(rig.state, c, r, 0.5f, BreakCause.STRAIN)) }
        assertEquals(before2 - 1, bp.aliveCount)
        // Klemmen der Bruchstelle
        val w = rig.beam(x, y, WOOD)
        rig.inTick { c -> BeamBreaker.breakBeam(rig.state, c, w, 0.01f, BreakCause.DAMAGE) }
        assertEquals(BeamBreaker.T_MIN, rig.lastFx().filterIsInstance<FxEvent.BeamBroken>().single().t)
    }

    /** Abriss (DELETED) und Zerfall (DECAY) sind still: kein Split, Geräte ohne `DeviceDestroyed`, Bruch-Ereignis mit Ursache. */
    @Test
    fun deletedAndDecayedBeamsAreRemovedSilently() {
        for (cause in listOf(BreakCause.DELETED, BreakCause.DECAY)) {
            val rig = PhysicsRig()
            val a = rig.node(20f, 30f, anchored = true)
            val b = rig.node(23f, 30f, anchored = true)
            val beam = rig.beam(a, b, WOOD)
            val dev = rig.device(REACTOR, beam, 0.5f)
            rig.run(1)
            val beams = rig.state.beams.aliveCount
            val nodes = rig.state.nodes.aliveCount
            rig.inTick { c -> assertTrue(BeamBreaker.breakBeam(rig.state, c, beam, 0.5f, cause)) }
            assertEquals(beams - 1, rig.state.beams.aliveCount, "$cause: no halves")
            assertEquals(nodes, rig.state.nodes.aliveCount, "$cause: no new nodes")
            assertFalse(rig.state.devices.isAlive(dev), "$cause: device removed")
            assertTrue(rig.lastFx().none { it is FxEvent.DeviceDestroyed }, "$cause: silent device removal")
            assertEquals(cause, rig.lastFx().filterIsInstance<FxEvent.BeamBroken>().single().cause)
        }
    }

    /**
     * Enden frischer Bruchhälften entstehen genau auf dem Gelenk, an dem der Rest des Bauwerks hängt. Fliegen sie
     * schnell weg, dürfen sie die Nachbarbalken dieses Gelenks nicht "treffen" (nur Eintritt in eine Kapsel zählt).
     */
    @Test
    fun freshHalfEndsDoNotHitTheBeamsAtTheirOwnJoint() {
        val rig = PhysicsRig()
        val g1 = rig.node(10f, 34f, anchored = true)
        val g2 = rig.node(13f, 34f, anchored = true)
        val p = rig.node(11.5f, 31f)
        val s1 = rig.beam(g1, p); val s2 = rig.beam(g2, p)
        val tip = rig.node(14.5f, 31f)
        val arm = rig.beam(p, tip)
        rig.run(30)
        val n = rig.state.nodes
        val neighbours = setOf(rig.state.beams.uidOf[s1], rig.state.beams.uidOf[s2])
        val armRef = rig.state.beams.ref(arm)
        val jointX = n.x[p]; val jointY = n.y[p]
        val firstNew = n.size
        rig.inTick { c ->
            // Stoß 8 m/s nach rechts oben (z. B. Explosion), dann Bruch in der Mitte
            val h = rig.state.config.substepDt
            for (i in intArrayOf(p, tip)) { n.px[i] = n.x[i] - 6f * h; n.py[i] = n.y[i] + 5f * h }
            assertTrue(BeamBreaker.breakAt(rig.state, armRef, 0.5f, BreakCause.DAMAGE, c))
        }
        rig.run(40)
        val hits = rig.fx.filterIsInstance<FxEvent.Hit>().filter { it.targetUid in neighbours }
        assertTrue(hits.isEmpty(), "half end must not hit the beams of its own joint: $hits")
        assertEquals(100f, rig.state.beams.hpOf[s1]); assertEquals(100f, rig.state.beams.hpOf[s2])
        // Das Hälften-Ende (Kopie von p) ist tatsächlich weggeflogen und nicht am Gelenk hängen geblieben
        val a1 = firstNew
        val d = sqrt((n.x[a1] - jointX) * (n.x[a1] - jointX) + (n.y[a1] - jointY) * (n.y[a1] - jointY))
        assertTrue(d > 1.5f, "half end must fly away from the joint (moved $d m)")
    }

    /** Trümmer-Einschlag beschädigt einen Balken darunter, Trümmer verschwinden unter dem Gelände/ außerhalb. */
    @Test
    fun debrisImpactDamagesBeamsAndDebrisDespawns() {
        val rig = PhysicsRig()
        val g1 = rig.node(30f, 30f, anchored = true)
        val g2 = rig.node(36f, 30f, anchored = true)
        val target = rig.beam(g1, g2, WOOD)
        // freier Metallbalken über dem Ziel, fällt drauf
        val f1 = rig.node(32f, 24f); val f2 = rig.node(34.5f, 24f)
        val falling = rig.beam(f1, f2, METAL)
        rig.device(CRATE, falling, 0.5f)
        rig.run(90)
        assertTrue(rig.state.beams.hpOf[target] < 100f, "debris impact must damage the beam (hp ${rig.state.beams.hpOf[target]})")
        assertTrue(rig.fx.any { it is FxEvent.Hit })
        // Außerhalb der Kartengrenzen → entfernt
        val o1 = rig.node(-39f, 0f); val o2 = rig.node(-38f, 0f)
        val off = rig.beam(o1, o2, WOOD)
        for (o in intArrayOf(o1, o2)) { rig.state.nodes.x[o] -= 21f; rig.state.nodes.px[o] -= 21f }
        rig.run(3)
        assertFalse(rig.state.beams.isAlive(off))
        assertFalse(rig.state.nodes.isAlive(o1), "orphaned debris nodes are purged")
        assertTrue(rig.fx.any { it is FxEvent.BeamBroken && it.cause == BreakCause.DECAY })
    }

    /** Freier Holzbalken (Trümmer), ruhend auf dem Boden bei y = 34. */
    private fun restingDebris(rig: PhysicsRig, x: Float = 30f, y: Float = 34f - 0.12f): Int {
        val a = rig.node(x, y); val b = rig.node(x + 1.5f, y)
        return rig.beam(a, b, WOOD)
    }

    private fun decayBreaks(rig: PhysicsRig) = rig.fx.filterIsInstance<FxEvent.BeamBroken>().filter { it.cause == BreakCause.DECAY }

    @Test
    fun debrisDecaysRandomlyOnlyAfterDecayTicks() {
        val rig = PhysicsRig()
        val beam = restingDebris(rig)
        val decayAfter = rig.state.config.debris.decayAfterTicks
        rig.run(decayAfter)
        assertTrue(rig.state.beams.isAlive(beam), "no random decay before $decayAfter ticks")
        assertEquals(decayAfter, rig.state.nodes.debrisTicks[rig.state.beams.a[beam]])
        var removedAt = -1L
        for (k in 0 until 1000) {
            rig.tick()
            if (!rig.state.beams.isAlive(beam)) { removedAt = rig.state.tick; break }
        }
        assertTrue(removedAt > 0, "debris must decay (chance ${rig.state.config.debris.decayChancePerSec}/s)")
        assertTrue(removedAt < rig.state.config.debris.maxAgeTicks, "removed by random decay, not by the age limit")
        assertEquals(1, decayBreaks(rig).size)
        rig.run(2)
        assertEquals(0, rig.state.nodes.aliveCount, "orphaned debris nodes are purged")
    }

    @Test
    fun debrisIsRemovedAtMaxAge() {
        val cfg = SimConfig(debris = DebrisConfig(maxAgeTicks = 30, decayAfterTicks = 1_000_000))
        val rig = PhysicsRig(cfg)
        val beam = restingDebris(rig)
        rig.run(30)
        assertTrue(rig.state.beams.isAlive(beam))
        rig.run(1)
        assertFalse(rig.state.beams.isAlive(beam), "debris older than maxAgeTicks is removed")
        assertEquals(1, decayBreaks(rig).size)
    }

    /** Unter-Gelände-Regel isoliert (ohne PHYSICS, die Knoten sonst auf die Oberfläche schiebt). */
    @Test
    fun debrisBelowGroundIsRemovedAfterBelowGroundTicks() {
        val rig = PhysicsRig(slots = setOf(SystemSlot.TOPOLOGY, SystemSlot.DEBRIS))
        val deep = restingDebris(rig, 30f, 35f)       // 1 m unter der Oberfläche (34)
        val shallow = restingDebris(rig, 40f, 34.3f)  // 0,3 m: innerhalb der Toleranz belowGroundDepth
        val limit = rig.state.config.debris.belowGroundTicks
        rig.run(limit)
        assertTrue(rig.state.beams.isAlive(deep))
        assertEquals(limit, rig.state.nodes.belowGroundTicks[rig.state.beams.a[deep]])
        rig.run(1)
        assertFalse(rig.state.beams.isAlive(deep), "debris buried longer than $limit ticks is removed")
        assertTrue(rig.state.beams.isAlive(shallow))
        assertEquals(0, rig.state.nodes.belowGroundTicks[rig.state.beams.a[shallow]])
    }

    @Test
    fun dustOnLandingOncePerNodeNotWhileResting() {
        val rig = PhysicsRig()
        val a = rig.node(60f, 24f); val b = rig.node(61.5f, 24f)
        rig.beam(a, b, WOOD)
        var landedTick = -1L
        for (k in 0 until 120) {
            rig.tick()
            if (landedTick < 0 && rig.lastFx().any { it is FxEvent.DebrisLanded }) landedTick = rig.state.tick
        }
        assertTrue(landedTick > 0, "falling debris raises dust")
        val landings = rig.fx.filterIsInstance<FxEvent.DebrisLanded>()
        assertTrue(landings.size in 1..2, "at most one landing per node (${landings.size})")
        assertTrue(landings.all { it.speed > rig.state.config.debris.landedFxMinSpeed })
        val countAfterLanding = landings.size
        rig.run(300)
        assertEquals(countAfterLanding, rig.fx.count { it is FxEvent.DebrisLanded }, "no repeated dust while resting")
        assertTrue((rig.state.nodes.flags[a] and NodeFlags.GROUNDED) != 0)
    }

    @Test
    fun creakFlagAboveEightyPercent() {
        val rig = PhysicsRig()
        val a = rig.node(20f, 30f, anchored = true)
        val b = rig.node(23f, 30f, anchored = true)
        val beam = rig.beam(a, b, WOOD)
        rig.state.beams.restLen[beam] = 3f / 1.045f // 4,5 % Zug = 90 % der Grenze
        rig.run(1)
        assertTrue(rig.state.beams.creaking[beam])
        rig.state.beams.restLen[beam] = 3f
        rig.run(1)
        assertFalse(rig.state.beams.creaking[beam])
    }

    @Test
    fun systemsAreRegisteredInTheirSlots() {
        val all = PhysicsSystems.all()
        assertEquals(setOf(SystemSlot.PHYSICS, SystemSlot.STRAIN_DAMAGE, SystemSlot.TOPOLOGY, SystemSlot.DEBRIS), all.keys)
        val stepper = PhysicsSystems.stepper()
        for (slot in all.keys) assertNotNull(stepper[slot])
    }

    @Test
    fun settleRemovesStartupMotionWithoutDamage() {
        val rig = PhysicsRig()
        twoStorey(rig)
        PhysicsSystems.settle(rig.state, 120)
        assertEquals(0L, rig.state.tick)
        val n = rig.state.nodes
        for (i in 0 until n.size) { assertEquals(n.x[i], n.px[i]); assertEquals(n.y[i], n.py[i]) }
        for (j in 0 until rig.state.beams.size) assertEquals(100f, rig.state.beams.hpOf[j])
    }

    // (9)
    @Test
    fun perf300BeamsUnderOneMillisecondPerTick() {
        val rig = PhysicsRig()
        val cols = 10; val rows = 10; val cell = 1.5f
        val id = Array(rows + 1) { r -> IntArray(cols + 1) { c -> rig.node(20f + c * cell, 34f - r * cell, anchored = r == 0) } }
        for (r in 0..rows) for (c in 0..cols) {
            if (c < cols && r > 0) rig.beam(id[r][c], id[r][c + 1], METAL)
            if (r < rows) rig.beam(id[r][c], id[r + 1][c], METAL)
            if (r < rows && c < cols) rig.beam(id[r][c], id[r + 1][c + 1], METAL)
        }
        val beamCount = rig.state.beams.aliveCount
        assertTrue(beamCount >= 300, "needs >= 300 beams, has $beamCount")
        // Warmup (JIT)
        for (k in 0 until 600) rig.stepper.step(rig.state, rig.ctx.also { it.beginTick(rig.state.tick) })
        val mark = TimeSource.Monotonic.markNow()
        val ticks = 600
        for (k in 0 until ticks) rig.stepper.step(rig.state, rig.ctx.also { it.beginTick(rig.state.tick) })
        val ms = mark.elapsedNow().inWholeNanoseconds / 1e6 / ticks
        println("PERF: $beamCount beams, ${rig.state.nodes.aliveCount} nodes: ${(ms * 1000).toLong() / 1000.0} ms/tick (avg over $ticks ticks)")
        assertEquals(beamCount, rig.state.beams.aliveCount, "nothing may break")
        assertTrue(ms < 1.0, "physics step took $ms ms/tick")
    }
}
