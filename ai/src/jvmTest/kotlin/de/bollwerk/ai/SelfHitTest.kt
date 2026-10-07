package de.bollwerk.ai

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.math.Geometry
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.tools.AimPreviewer
import de.bollwerk.engine.tools.ShotSweep
import de.bollwerk.engine.tools.TrajectoryOutcome
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.GameView
import de.bollwerk.engine.view.HitTarget
import de.bollwerk.setup.MatchBootstrap
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FX1: Die KI feuert nie einen Schuss, dessen Bahn zuerst die eigene Festung trifft. Geprüft wird jeder angenommene
 * `Fire` im Moment des Anwendens (nach dem `SetAim` desselben Ticks, vor dem Waffen-System) mit der gemeinsamen
 * Bahnprüfung [ShotSweep] – derselben Kollision wie die Simulation und die Zielvorschau.
 */
class SelfHitTest {
    /**
     * Prüft jeden `Fire` der KI vor dem Anwenden gegen die View, die die KI sah (Winkel/Kraft aus dem `SetAim`
     * desselben Ticks, sonst die stehende Ausrichtung). Der Schuss fällt in genau diesem Tick.
     */
    private class Audit {
        val sweep = ShotSweep()
        val outcomes = IntArray(TrajectoryOutcome.entries.size)
        val blocked = ArrayList<String>()
        var fires = 0

        fun check(tick: Long, view: GameView, cmds: List<Command>) {
            for (cmd in cmds) {
                if (cmd !is Command.Fire) continue
                val d = view.deviceView
                val i = d.resolve(cmd.deviceRef)
                if (i < 0) continue
                val aim = cmds.lastOrNull { it is Command.SetAim && it.deviceRef == cmd.deviceRef } as Command.SetAim?
                val angle = aim?.angle ?: d.aimAngle(i)
                val power = aim?.power ?: d.power(i)
                fires++
                val o = sweep.device(view, i, angle, power)
                outcomes[o.ordinal]++
                if (o == TrajectoryOutcome.BLOCKED_OWN && blocked.size < 10) {
                    val key = view.tables.devices[d.type(i)].key
                    blocked.add("t=$tick p${cmd.playerId} $key at ${angle * FloatMath.RAD_TO_DEG}° power $power hits ${sweep.hitKind}/${sweep.hitId}")
                }
            }
        }
    }

    /**
     * Unabhängige Prüfung aus der **Simulation** (nicht über [ShotSweep]): Jeder erste Treffer eines Geschosses bzw.
     * Hitscan-Schusses wird seinem Schützen zugeordnet; gezählt wird, wie oft er einen Balken oder ein Gerät des
     * Schützen selbst trifft. Ballistisch: Das Projektil steht nach dem Treffer genau am Explosionspunkt (Slot-Daten
     * bleiben nach `release` bis zur Wiederverwendung erhalten). Hitscan: Treffer vor dem `Fired` desselben Geräts.
     * Laser: Endpunkt des Strahls gegen die Balken/Geräte des Schützen.
     */
    private class SimAudit {
        val selfHits = ArrayList<String>()
        var ballisticImpacts = 0
        var hitscanImpacts = 0
        var laserTicks = 0
        private val prevSlots = ArrayList<Int>()
        private val beamOwner = HashMap<Int, Int>()
        private val deviceOwner = HashMap<Int, Int>()
        private val devX = ArrayList<Float>(); private val devY = ArrayList<Float>(); private val devR = ArrayList<Float>()
        private val devO = ArrayList<Int>()
        private val geo = FloatArray(DeviceGeometry.SIZE)

        /** Zustand vor dem nächsten Tick festhalten. */
        fun before(st: GameState) {
            prevSlots.clear()
            val p = st.projectiles
            for (i in 0 until p.size) if (p.isAlive(i)) prevSlots.add(i)
            beamOwner.clear()
            val b = st.beams
            for (j in 0 until b.size) if (b.isAlive(j)) beamOwner[b.uidOf[j]] = b.ownerOf[j]
            deviceOwner.clear(); devX.clear(); devY.clear(); devR.clear(); devO.clear()
            val d = st.devices
            val scale = st.config.combat.deviceRayRadiusScale
            for (i in 0 until d.size) {
                if (!d.isAlive(i)) continue
                deviceOwner[d.uidOf[i]] = d.ownerOf[i]
                if (!b.isAlive(d.beamId[i])) continue
                DeviceGeometry.mount(st, i, geo)
                devX.add(geo[DeviceGeometry.CX]); devY.add(geo[DeviceGeometry.CY])
                devR.add(st.tables.devices[d.typeOf[i]].hitRadius * scale); devO.add(d.ownerOf[i])
            }
        }

        /** Treffer des eben gelaufenen Ticks auswerten. */
        fun after(st: GameState, fx: List<FxEvent>) {
            val p = st.projectiles
            val pendingHits = ArrayList<FxEvent.Hit>()
            for (e in fx) {
                when (e) {
                    is FxEvent.Explosion -> {
                        if (e.weaponId < 0) continue
                        val slot = prevSlots.firstOrNull { !p.isAlive(it) && p.x[it] == e.x && p.y[it] == e.y } ?: continue
                        ballisticImpacts++
                        val shooter = p.ownerOf[slot]
                        val victim = when {
                            e.hitBeamUid >= 0 -> beamOwner[e.hitBeamUid] ?: -1
                            e.hitMaterialId == -1 -> -1 // Gelände
                            else -> nearestDeviceOwner(e.x, e.y)
                        }
                        if (victim == shooter) selfHits.add("t=${e.tick} p$shooter ${st.tables.weapons[e.weaponId].key} explodes at (${e.x}, ${e.y}) on its own fort")
                    }
                    is FxEvent.Hit -> if (!e.splash && e.weaponId >= 0 && st.tables.weapons[e.weaponId].mode == WeaponMode.HITSCAN) pendingHits.add(e)
                    is FxEvent.Fired -> {
                        if (e.weaponId < 0 || st.tables.weapons[e.weaponId].mode != WeaponMode.HITSCAN) continue
                        val first = pendingHits.firstOrNull { it.weaponId == e.weaponId }
                        pendingHits.clear()
                        if (first == null) continue
                        hitscanImpacts++
                        val shooter = deviceOwner[e.deviceUid] ?: continue
                        val victim = when (first.target) {
                            HitTarget.BEAM -> beamOwner[first.targetUid] ?: -1
                            HitTarget.DEVICE -> deviceOwner[first.targetUid] ?: -1
                            HitTarget.TERRAIN -> -1
                        }
                        if (victim == shooter) selfHits.add("t=${e.tick} p$shooter ${st.tables.weapons[e.weaponId].key} hits its own fort")
                    }
                    is FxEvent.LaserBeam -> {
                        laserTicks++
                        val shooter = deviceOwner[e.deviceUid] ?: continue
                        if (ownStructureAt(st, shooter, e.x1, e.y1)) selfHits.add("t=${e.tick} p$shooter laser ends on its own fort")
                    }
                    else -> {}
                }
            }
        }

        private fun nearestDeviceOwner(x: Float, y: Float): Int {
            var best = -1
            var bd = Float.MAX_VALUE
            for (k in devX.indices) {
                val dx = devX[k] - x; val dy = devY[k] - y
                val d = sqrt(dx * dx + dy * dy) - devR[k]
                if (d < bd) { bd = d; best = devO[k] }
            }
            return if (bd < 0.5f) best else -1
        }

        private fun ownStructureAt(st: GameState, owner: Int, x: Float, y: Float): Boolean {
            val b = st.beams
            val n = st.nodes
            for (j in 0 until b.size) {
                if (!b.isAlive(j) || b.ownerOf[j] != owner) continue
                val half = st.tables.materials[b.materialOf[j]].thickness * 0.5f
                val d2 = Geometry.pointSegmentDistSq(x, y, n.x[b.a[j]], n.y[b.a[j]], n.x[b.b[j]], n.y[b.b[j]])
                if (sqrt(d2) < half + 0.2f) return true
            }
            for (k in devX.indices) {
                if (devO[k] != owner) continue
                val dx = devX[k] - x; val dy = devY[k] - y
                if (sqrt(dx * dx + dy * dy) < devR[k] + 0.2f) return true
            }
            return false
        }
    }

    private class Played(val audit: Audit, val sim: SimAudit)

    private fun play(seed: Long, a: Difficulty, b: Difficulty, map: String = "schlucht", ticks: Long = THREE_MINUTES): Played {
        val audit = Audit()
        val sim = SimAudit()
        val m = AiMatch(seed, listOf(a, b), map = map, commandTap = audit::check)
        sim.before(m.state)
        m.run(ticks) { st ->
            sim.after(st, m.session.ctx.fx)
            sim.before(st)
        }
        println(
            "self-hit audit seed $seed $a vs $b on $map: fires=${audit.fires} ${TrajectoryOutcome.entries.zip(audit.outcomes.toList())} " +
                "sim: ballistic=${sim.ballisticImpacts} hitscan=${sim.hitscanImpacts} laser=${sim.laserTicks} self=${sim.selfHits.size} " +
                "result=${m.state.result} after ${m.state.tick / 60} s",
        )
        return Played(audit, sim)
    }

    @Test
    fun aiNeverFiresASelfBlockedShotOverAThreeMinuteMatch() {
        var fires = 0
        var impacts = 0
        for ((seed, sides) in listOf(
            1L to (Difficulty.NORMAL to Difficulty.NORMAL),
            2L to (Difficulty.HARD to Difficulty.EASY),
            3L to (Difficulty.EASY to Difficulty.HARD),
        )) {
            val (a, sim) = play(seed, sides.first, sides.second).let { it.audit to it.sim }
            assertEquals(0, a.outcomes[TrajectoryOutcome.BLOCKED_OWN.ordinal], "self-blocked shots: ${a.blocked}")
            // unabhängig aus der Sim: kein eigener Schuss trifft zuerst die eigene Festung
            assertEquals(emptyList(), sim.selfHits.take(10), "self hits in the simulation")
            fires += a.fires
            impacts += sim.ballisticImpacts
        }
        assertTrue(fires > 30, "the AIs actually fired: $fires")
        assertTrue(impacts > 20, "the sim audit attributed impacts: $impacts")
    }

    @Test
    fun aiNeverFiresASelfBlockedShotOnHuegel() {
        val (a, sim) = play(5L, Difficulty.HARD, Difficulty.NORMAL, map = "huegel").let { it.audit to it.sim }
        assertEquals(0, a.outcomes[TrajectoryOutcome.BLOCKED_OWN.ordinal], "self-blocked shots: ${a.blocked}")
        assertEquals(emptyList(), sim.selfHits.take(10), "self hits in the simulation")
        assertTrue(a.fires > 5)
        assertTrue(sim.ballisticImpacts > 3)
    }

    @Test
    fun aimControllerRejectsTheSelfBlockedArcAndTakesTheOtherOne() {
        // Mörser der Startfestung auf ein fernes Ziel: der steile Bogen (> 63°) träfe das eigene Panzerdach
        val m = AiMatch(seed = 7L, difficulties = listOf(null, null))
        m.run(60L)
        val st = m.state
        st.wind = 0f
        val d = st.devices
        val mortar = (0 until d.size).first {
            d.isAlive(it) && d.owner(it) == 0 && st.tables.devices[d.type(it)].role == DeviceRole.WEAPON &&
                st.tables.weapons[st.tables.devices[d.type(it)].weapon].key == "mortar"
        }
        val props = st.tables.devices[d.type(mortar)]
        val w = st.tables.weapons[props.weapon]
        val obstacles = Obstacles()
        obstacles.rebuild(st)
        val aim = AimController(obstacles)
        // Ziel: gegnerischer Reaktor
        val target = st.players[1].reactorDeviceId
        val tuning = AiTuning.of(Difficulty.HARD)
        val sol = AimSolution()
        assertTrue(aim.evaluate(st, 0, mortar, props, w, target, tuning, sol))
        assertTrue(!sol.selfBlocked && sol.exposure > 0f, "exposure ${sol.exposure}")
        assertTrue(!aim.selfBlockedAt(st, 0, mortar, props, w, sol.angle, sol.power))
        // der steile Bogen auf dasselbe Ziel ist blockiert; die gewählte Lösung ist der flache
        val steep = 75f * FloatMath.DEG_TO_RAD
        assertTrue(aim.selfBlockedAt(st, 0, mortar, props, w, steep, 1f))
        assertTrue(sol.angle < 63f * FloatMath.DEG_TO_RAD, "angle ${sol.angle * FloatMath.RAD_TO_DEG}")
        // gestörter Winkel in die eigene Festung wird auf den freien zurückgenommen
        val bad = AimSolution().also { it.copyFrom(sol); it.angle = 60f * FloatMath.DEG_TO_RAD }
        val rng = AiRng(1L, 0)
        repeat(20) {
            val a = aim.applyErrorClear(st, 0, mortar, props, w, bad, 12f, rng)
            assertTrue(!aim.selfBlockedAt(st, 0, mortar, props, w, a, bad.power), "noisy ${a * FloatMath.RAD_TO_DEG}°")
        }
    }

    /** Start-Mörser von Spieler 0 nach einer Minute ohne Eingaben; Wind 0. */
    private class MortarBench {
        val m = AiMatch(seed = 7L, difficulties = listOf(null, null)).also { it.run(60L) }
        val st = m.state.also { it.wind = 0f }
        val d = st.devices
        val mortar = (0 until d.size).first {
            d.isAlive(it) && d.owner(it) == 0 && st.tables.devices[d.type(it)].role == DeviceRole.WEAPON &&
                st.tables.weapons[st.tables.devices[d.type(it)].weapon].key == "mortar"
        }
        val props = st.tables.devices[d.type(mortar)]
        val w = st.tables.weapons[props.weapon]
        val obstacles = Obstacles().also { it.rebuild(st) }
        val aim = AimController(obstacles)
    }

    @Test
    fun easyAlsoTakesTheOtherArcWhenThePreferredOneHitsTheOwnFort() {
        // Leicht prüft sonst nur den bevorzugten (steilen) Bogen; auf ein mittleres Ziel ist der bei Kraft 1 und 0,8 so
        // steil, dass er das eigene Dach träfe – dann muss auch Leicht den flachen Bogen nehmen
        val b = MortarBench()
        val geo = FloatArray(DeviceGeometry.SIZE)
        DeviceGeometry.mount(b.st, b.mortar, geo)
        val px = geo[DeviceGeometry.PIVOT_X]
        val facing = b.st.players[0].facing
        val easy = AiTuning.of(Difficulty.EASY)
        assertTrue(!easy.tryBothArcs)
        val bothArcs = AiTuning(
            easy.maxCommandsPerThink, easy.candidateTargets, easy.targetNoise, easy.powerSteps, true, easy.repairThreshold,
            easy.useDoors, easy.rebuild, easy.extinguish, easy.minExposure, easy.reactionChance, easy.weaponEvalsPerThink,
        )
        var checked = 0
        for (dist in 40..95 step 5) {
            val tx = px + facing * dist
            val ty = b.st.terrain.heightAt(tx) - 0.5f
            val sol = AimSolution()
            val aim = AimController(b.obstacles) // frisch: kein Warmstart aus früheren Abständen
            aim.evaluatePoint(b.st, 0, b.mortar, b.props, b.w, tx, ty, 1f, -1, easy, sol)
            val tag = "EASY $dist m: angle ${sol.angle * FloatMath.RAD_TO_DEG}° exposure ${sol.exposure} high ${sol.highArc}"
            if (sol.exposure > 0f) assertTrue(!sol.selfBlocked && !b.aim.selfBlockedAt(b.st, 0, b.mortar, b.props, b.w, sol.angle, sol.power), tag)
            if (aim.selfBlockedArcs > 0) {
                // der bevorzugte steile Bogen war blockiert: dann wird der flache trotz tryBothArcs = false geprüft –
                // findet die Prüfung beider Bögen (gleiche Kraftstufen) etwas Brauchbares, findet Leicht es auch
                val both = AimSolution()
                AimController(b.obstacles).evaluatePoint(b.st, 0, b.mortar, b.props, b.w, tx, ty, 1f, -1, bothArcs, both)
                assertEquals(both.exposure > 0f, sol.exposure > 0f, tag)
                // vor dem Fix wählte Leicht beim Mörser nie den flachen Bogen
                if (sol.exposure > 0f && !sol.highArc) checked++
            }
        }
        assertTrue(checked > 0, "EASY met a self-blocked preferred arc")
    }

    @Test
    fun aimErrorIntoTheOwnFortIsRedrawnOrMirroredAndKeepsItsSize() {
        val b = MortarBench()
        val sol = AimSolution()
        sol.valid = true; sol.angle = 58f * FloatMath.DEG_TO_RAD; sol.power = 1f; sol.exposure = 1f
        fun blocked(a: Float) = b.aim.selfBlockedAt(b.st, 0, b.mortar, b.props, b.w, a, sol.power)
        assertTrue(!blocked(sol.angle))
        val rngA = AiRng(3L, 0)
        val rngB = AiRng(3L, 0) // gleicher Strom: zeigt die gezogenen Fehler
        var redrawn = 0
        var mirrored = 0
        var exact = 0
        val sigma = 8f
        repeat(300) {
            val got = b.aim.applyErrorClear(b.st, 0, b.mortar, b.props, b.w, sol, sigma, rngA)
            assertTrue(!blocked(got), "never into the own fort")
            // Erwartung aus denselben Ziehungen nachrechnen
            val first = AimController.applyError(sol.angle, sigma, rngB)
            if (!blocked(first)) {
                assertEquals(first, got)
                return@repeat
            }
            var expected = Float.NaN
            for (r in 0 until AimController.ERROR_REDRAWS) {
                val again = AimController.applyError(sol.angle, sigma, rngB)
                if (!blocked(again)) { expected = again; break }
            }
            when {
                !expected.isNaN() -> { assertEquals(expected, got); redrawn++ }
                got != sol.angle -> {
                    // gespiegelt: gleicher Betrag des Fehlers, andere Richtung
                    assertTrue(abs(abs(got - sol.angle) - abs(first - sol.angle)) < 1e-5f, "|error| kept")
                    assertTrue((got - sol.angle) * (first - sol.angle) < 0f, "direction flipped")
                    mirrored++
                }
                else -> exact++
            }
            assertEquals(rngB.state, rngA.state, "same random use")
        }
        assertTrue(redrawn > 5, "redrawn $redrawn, mirrored $mirrored, exact $exact")
        assertEquals(redrawn.toLong(), b.aim.errorRedraws)
        assertEquals(mirrored.toLong(), b.aim.errorMirrors)
        assertEquals((redrawn + mirrored + exact).toLong(), b.aim.errorCorrections)
        // der Fehler wird nicht systematisch kleiner: höchstens ein kleiner Teil der Korrekturen schießt fehlerfrei
        assertTrue(exact * 10 <= redrawn + mirrored + exact, "exact $exact of ${redrawn + mirrored + exact}")
    }

    @Test
    fun aMirroredAimErrorKeepsItsSizeWhenAllRedrawsAreBlocked() {
        // Lösung direkt an der Kante des blockierten Bereichs, großer Fehler nach oben: die Spiegelung ist frei
        val b = MortarBench()
        fun blocked(a: Float) = b.aim.selfBlockedAt(b.st, 0, b.mortar, b.props, b.w, a, 1f)
        var edge = 50f
        while (!blocked((edge + 0.5f) * FloatMath.DEG_TO_RAD)) edge += 0.5f
        val sol = AimSolution()
        sol.valid = true; sol.angle = edge * FloatMath.DEG_TO_RAD; sol.power = 1f; sol.exposure = 1f
        assertTrue(!blocked(sol.angle))
        var mirrored = 0
        for (seed in 1L..400L) {
            val rngA = AiRng(seed, 0)
            val rngB = AiRng(seed, 0)
            val draws = FloatArray(1 + AimController.ERROR_REDRAWS) { AimController.applyError(sol.angle, 6f, rngB) }
            if (!draws.all { blocked(it) }) continue
            val got = b.aim.applyErrorClear(b.st, 0, b.mortar, b.props, b.w, sol, 6f, rngA)
            val m = sol.angle + (sol.angle - draws[0])
            if (!blocked(m)) {
                assertEquals(m, got, "seed $seed")
                assertTrue(abs(abs(got - sol.angle) - abs(draws[0] - sol.angle)) < 1e-5f)
                mirrored++
            } else {
                assertEquals(sol.angle, got)
            }
        }
        assertTrue(mirrored > 0, "mirror path exercised")
    }

    @Test
    fun aLateRocketSelfHitIsSeenByTheAiOverTheWholeLifetime() {
        // Review-Fall: Brandrakete (16 s) steil in −8 m/s Gegenwind fällt nach mehr als 720 Ticks (altem KI-Horizont)
        // auf den eigenen Boden. Inhalt aus dem echten Content, flache Testkarte.
        val setup = MatchSetup(1L, "schlucht", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN)))
        val tables = MatchBootstrap.create(AiMatch.db, setup).tables
        val st = GameState(1L, tables, MapSpec.flat(width = 120f))
        val metal = tables.materials.indexOfFirst { it.key == "metal" }
        val rocketType = tables.devices.indexOfFirst { it.key == "rocket" }
        val props = tables.devices[rocketType]
        val w = tables.weapons[props.weapon]
        fun beam(x0: Float, y0: Float, x1: Float, y1: Float): Int {
            val a = st.nodes.alloc(x0, y0, 0, true); val c = st.nodes.alloc(x1, y1, 0, true)
            val dx = x1 - x0; val dy = y1 - y0
            val id = st.beams.alloc(a, c, metal, sqrt(dx * dx + dy * dy), tables.materials[metal].hp, 0)
            st.nodes.rebuildAdjacency(st.beams)
            return id
        }
        val plat = beam(38.5f, 30f, 41.5f, 30f)
        beam(-30f, 33f, 110f, 33f)
        val dev = st.devices.alloc(rocketType, plat, 0.5f, props.hp, 0, 0, true, w.defaultAimRad, 1f)
        st.wind = -8f
        val obstacles = Obstacles().also { it.rebuild(st) }
        val aim = AimController(obstacles)
        val sweep = ShotSweep()
        val previewer = AimPreviewer()
        var late = 0
        var a = 60f
        while (a <= w.maxAimRad * FloatMath.RAD_TO_DEG + 1e-3f) {
            val angle = a * FloatMath.DEG_TO_RAD
            val o = sweep.device(st, dev, angle, 1f)
            assertEquals(o == TrajectoryOutcome.BLOCKED_OWN, aim.selfBlockedAt(st, 0, dev, props, w, angle, 1f), "$a°: sweep $o at ${sweep.hitTick}")
            assertEquals(o, previewer.preview(st, dev, angle, 1f)!!.outcome, "$a°")
            if (o == TrajectoryOutcome.BLOCKED_OWN && sweep.hitTick >= AimController.MAX_POINTS) late++
            a += 1f
        }
        assertTrue(late > 0, "late self-hits after ${AimController.MAX_POINTS} ticks")
    }

    private companion object {
        const val THREE_MINUTES = 3L * 60L * 60L
    }
}
