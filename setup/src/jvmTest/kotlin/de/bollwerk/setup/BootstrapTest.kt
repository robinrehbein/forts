package de.bollwerk.setup

import de.bollwerk.content.ClasspathContent
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Beide Karten bootstrappen mit Startfestungen für beide Spieler in einen vollständigen [de.bollwerk.engine.sim.GameState]. */
class BootstrapTest {
    private val db = ClasspathContent.load()

    private fun setup(map: String, seed: Long = 5L) =
        MatchSetup(seed, map, listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.AI, "NORMAL")))

    @Test
    fun bothMapsBootstrapWithPopulatedPools() {
        val fort = db.blueprint("fort_standard")
        for (map in listOf("schlucht", "huegel")) {
            val s = MatchBootstrap.create(db, setup(map))
            // Knoten: alle Fundamente (je Spieler 5 der Startfestung + 2 für den KI-Anbau) + je Fort alle nicht
            // auf einem Fundament liegenden Knoten (die nicht verankerten und der Abspann-Anker)
            assertEquals(db.map(map).foundations.size + 2 * (fort.nodes.size - 5), s.nodes.aliveCount, "$map nodes")
            assertEquals(2 * fort.beams.size, s.beams.aliveCount, "$map beams")
            assertEquals(2 * fort.devices.size, s.devices.aliveCount, "$map devices")
            assertEquals(14, s.devices.aliveCount)
            for (p in s.players) {
                assertEquals(400f, p.metal, "$map metal"); assertEquals(200f, p.energy, "$map energy")
                val r = p.reactorDeviceId
                assertTrue(s.devices.isAlive(r), "$map player ${p.id} reactor")
                assertEquals(DeviceRole.REACTOR, s.tables.devices[s.devices.typeOf[r]].role)
                assertEquals(p.id, s.devices.ownerOf[r])
            }
            // genau ein Reaktor je Spieler, je Spieler 2 Minen, 1 Turbine, 3 Waffen
            for (owner in 0..1) {
                val types = (0 until s.devices.size).filter { s.devices.isAlive(it) && s.devices.ownerOf[it] == owner }
                    .map { s.tables.devices[s.devices.typeOf[it]].key }.groupingBy { it }.eachCount()
                assertEquals(mapOf("mine" to 2, "reactor" to 1, "cannon" to 1, "mortar" to 1, "turbine" to 1, "mg" to 1), types, "$map owner $owner")
            }
            assertEquals(s.map.id, map)
        }
    }

    @Test
    fun bootstrapIsDeterministic() {
        for (map in listOf("schlucht", "huegel")) {
            assertEquals(StateHash.of(MatchBootstrap.create(db, setup(map))), StateHash.of(MatchBootstrap.create(db, setup(map))))
        }
    }

    @Test
    fun seedSetsWindOnly() {
        for (mapId in listOf("schlucht", "huegel")) {
            val def = db.map(mapId)
            val states = (1L..12L).map { MatchBootstrap.create(db, setup(mapId, it)) }
            for (s in states) assertTrue(s.wind >= def.windMin && s.wind <= def.windMax, "$mapId wind ${s.wind} outside ${def.windMin}..${def.windMax}")
            assertTrue(states.map { it.wind }.toSet().size > 1, "$mapId wind must depend on the seed")
            // alles andere (Knotenlage, Geräte) hängt nicht vom Seed ab
            val ref = states.first()
            for (s in states) {
                assertEquals(ref.nodes.size, s.nodes.size)
                for (i in 0 until ref.nodes.size) { assertEquals(ref.nodes.x[i], s.nodes.x[i]); assertEquals(ref.nodes.y[i], s.nodes.y[i]) }
            }
        }
    }

    private fun aiSetup(map: String, key: String) =
        MatchSetup(5L, map, listOf(PlayerSetup(Controller.AI, "NORMAL"), PlayerSetup(Controller.AI, "NORMAL")), startFortBlueprint = key)

    /** Jeder KI-Plan lässt sich als Startfestung bootstrappen: alle Teile da, ein Reaktor je Spieler, Knoten verschmelzen mit Fundamenten. */
    @Test
    fun aiBlueprintsBootstrapAsStartForts() {
        for (key in listOf("ai_easy", "ai_normal", "ai_hard")) {
            val bp = db.blueprint(key)
            for (map in listOf("schlucht", "huegel")) {
                val def = db.map(map)
                val s = MatchBootstrap.create(db, aiSetup(map, key))
                assertEquals(2 * bp.beams.size, s.beams.aliveCount, "$key $map beams")
                assertEquals(2 * bp.devices.size, s.devices.aliveCount, "$key $map devices")
                for (p in s.players) {
                    assertTrue(s.devices.isAlive(p.reactorDeviceId), "$key $map player ${p.id} reactor")
                    val reactors = (0 until s.devices.size).count {
                        s.devices.isAlive(it) && s.devices.ownerOf[it] == p.id && s.tables.devices[s.devices.typeOf[it]].role == DeviceRole.REACTOR
                    }
                    assertEquals(1, reactors, "$key $map player ${p.id} reactors")
                }
                // Knoten je Spieler: Fundamente + Plan-Knoten, die nicht auf einem Fundament liegen
                for (owner in 0..1) {
                    val fort = def.startForts.single { it.owner == owner }
                    val foundations = def.foundations.filter { it.owner == owner }
                    val free = bp.nodes.count { n ->
                        val wx = if (fort.mirror) fort.originX - n.x else fort.originX + n.x
                        foundations.none { kotlin.math.abs(it.x - wx) <= 0.05f && kotlin.math.abs(it.y - (def.baseY[owner] + n.y)) <= 0.05f }
                    }
                    val alive = (0 until s.nodes.size).count { s.nodes.isAlive(it) && s.nodes.ownerOf[it] == owner }
                    assertEquals(foundations.size + free, alive, "$key $map owner $owner nodes")
                }
            }
        }
    }

    /**
     * Die KI baut auf der Standard-Startfestung auf. Auf dem Zustand mit nur dieser Festung gilt für jeden
     * KI-Plan: Plan-Knoten, die er verankert voraussetzt, existieren dort bereits verankert (Fundament oder
     * Festungsknoten) – neue Knoten kann `PlaceBeam` nicht verankern. Neue Geräte halten den Mindestabstand zu den
     * Geräten der Festung, und Balken auf bestehenden Balken haben dasselbe Material.
     */
    @Test
    fun aiPlansFitOnTheStandardStartFort() {
        val out = FloatArray(DeviceGeometry.SIZE); val other = FloatArray(DeviceGeometry.SIZE)
        for (map in listOf("schlucht", "huegel")) {
            val def = db.map(map)
            val state = MatchBootstrap.create(db, setup(map))
            val tables = state.tables
            for (key in listOf("ai_easy", "ai_normal", "ai_hard")) {
                val plan = db.blueprint(key)
                val props = tables.blueprint(key)!!
                for (owner in 0..1) {
                    val fort = def.startForts.single { it.owner == owner }
                    val baseY = def.baseY[owner]
                    val baseDevs = (0 until state.devices.size).filter { state.devices.isAlive(it) && state.devices.ownerOf[it] == owner }
                    // Plan-Knoten → vorhandener eigener Knoten (Radius `nodeMergeRadius`) oder neu
                    val r = state.config.nodeMergeRadius
                    val nodeAt = IntArray(plan.nodes.size) { -1 }
                    for ((i, n) in plan.nodes.withIndex()) {
                        val wx = if (fort.mirror) fort.originX - n.x else fort.originX + n.x
                        val wy = baseY + n.y
                        val j = (0 until state.nodes.size).firstOrNull {
                            state.nodes.isAlive(it) && state.nodes.ownerOf[it] == owner &&
                                (state.nodes.x[it] - wx) * (state.nodes.x[it] - wx) + (state.nodes.y[it] - wy) * (state.nodes.y[it] - wy) <= r * r
                        }
                        if (j != null) nodeAt[i] = j
                        else assertTrue(!n.anchored, "$key $map owner $owner: anchored plan node $i at x=$wx has no anchored node/foundation")
                        if (j != null && n.anchored) assertTrue(state.nodes.isAnchored(j), "$key $map owner $owner: plan node $i must be anchored")
                    }
                    // Balken auf bestehendem Balken: gleiches Material
                    for ((i, b) in props.beams.withIndex()) {
                        val na = nodeAt[b.a]; val nb = nodeAt[b.b]
                        if (na < 0 || nb < 0) continue
                        for (k in 0 until state.beams.size) {
                            if (!state.beams.isAlive(k) || state.beams.ownerOf[k] != owner) continue
                            val same = (state.beams.a[k] == na && state.beams.b[k] == nb) || (state.beams.a[k] == nb && state.beams.b[k] == na)
                            if (same) assertEquals(state.beams.materialOf[k], b.material, "$key $map owner $owner: beam $i changes the material of a start fort beam")
                        }
                    }
                    // neue Geräte (nicht deckungsgleich mit einem vorhandenen): Mindestabstand zu den vorhandenen
                    for ((i, d) in props.devices.withIndex()) {
                        val pb = props.beams[d.beam]
                        val na = nodeAt[pb.a]; val nb = nodeAt[pb.b]
                        val dp = tables.devices[d.type]
                        val ax = fort.originX + (if (fort.mirror) -plan.nodes[pb.a].x else plan.nodes[pb.a].x)
                        val ay = baseY + plan.nodes[pb.a].y
                        val bx = fort.originX + (if (fort.mirror) -plan.nodes[pb.b].x else plan.nodes[pb.b].x)
                        val by = baseY + plan.nodes[pb.b].y
                        DeviceGeometry.mountAt(ax, ay, bx, by, d.t, d.sideNegative != fort.mirror, tables.materials[pb.material].thickness, dp.mountOffset, dp.pivotOffset, dp.barrelLength, 0f, out)
                        var existing = false
                        for (e in baseDevs) {
                            DeviceGeometry.mount(state, e, other)
                            val dist = kotlin.math.hypot(out[DeviceGeometry.X] - other[DeviceGeometry.X], out[DeviceGeometry.Y] - other[DeviceGeometry.Y])
                            if (dist < 1e-3f && state.devices.typeOf[e] == d.type) { existing = true; continue }
                            assertTrue(dist >= dp.minSpacing, "$key $map owner $owner: ${dp.key} (device $i) is $dist m from start fort device ${tables.devices[state.devices.typeOf[e]].key}")
                        }
                        if (na < 0 || nb < 0) assertTrue(!existing, "$key: a device on a new beam cannot already exist")
                    }
                }
            }
        }
    }

    @Test
    fun mirroredFortFacesTheOtherWay() {
        for (map in listOf("schlucht", "huegel")) {
            val s = MatchBootstrap.create(db, setup(map))
            val w = s.map.width
            val mortars = (0 until s.devices.size).filter { s.devices.isAlive(it) && s.tables.devices[s.devices.typeOf[it]].key == "mortar" }
            val a0 = s.devices.aimAngle[mortars.single { s.devices.ownerOf[it] == 0 }]
            val a1 = s.devices.aimAngle[mortars.single { s.devices.ownerOf[it] == 1 }]
            assertTrue(a0 > 0f && a0 < 1.57f, "left player aims right")
            assertTrue(a1 > 1.57f && a1 < 3.15f, "right player aims left")
            // Knoten des rechten Forts liegen spiegelbildlich zum linken
            val xs0 = (0 until s.nodes.size).filter { s.nodes.isAlive(it) && s.nodes.ownerOf[it] == 0 }.map { s.nodes.x[it] }.sorted()
            val xs1 = (0 until s.nodes.size).filter { s.nodes.isAlive(it) && s.nodes.ownerOf[it] == 1 }.map { w - s.nodes.x[it] }.sorted()
            assertEquals(xs0, xs1, "$map mirror symmetry")
        }
    }

    @Test
    fun mapSpecsMatchStyleBible() {
        val s = MapSpecFactory.build(db, "schlucht")
        assertEquals(34f, s.terrain.heightAt(20f), 1e-3f); assertEquals(52f, s.terrain.heightAt(60f), 1e-3f)
        assertEquals(34f, s.terrain.heightAt(100f), 1e-3f)
        val h = MapSpecFactory.build(db, "huegel")
        assertEquals(160f, h.width)
        assertEquals(34f, h.terrain.heightAt(30f), 1e-3f); assertEquals(42f, h.terrain.heightAt(80f), 1e-3f)
        assertEquals(h.terrain.heightAt(60f), h.terrain.heightAt(100f), 1e-3f)
        assertTrue(h.inBuildZone(0, 50f) && h.inBuildZone(1, 110f) && !h.inBuildZone(0, 80f))
        assertTrue(h.hasOreNear(1, 134f, 2.4f))
    }
}
