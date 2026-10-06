package de.bollwerk.content

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Prüft den ausgelieferten V1-Content gegen die Zahlen der Stil-Bibel (§1, §4) und den Prototyp. */
class ContentDataTest {
    private val db = ClasspathContent.load()

    @Test
    fun loadsAndValidatesCleanly() {
        assertEquals(emptyList(), ContentValidator.validate(db))
        ContentValidator.requireValid(db)
        assertEquals(5, db.materials.size)
        assertEquals(13, db.devices.size)
        assertEquals(6, db.weapons.size)
        assertEquals(4, db.techs.size)
        assertEquals(listOf("schlucht", "huegel"), db.maps.map { it.id })
        assertEquals(listOf("fort_standard", "ai_easy", "ai_normal", "ai_hard"), db.blueprints.map { it.id })
    }

    @Test
    fun materialsMatchStyleBible() {
        data class M(val cost: Float, val hp: Float, val thick: Float)
        val expected = mapOf(
            "wood" to M(4f, 100f, 0.32f), "metal" to M(10f, 260f, 0.26f), "armour" to M(18f, 520f, 0.42f),
            "rope" to M(2f, 60f, 0.08f), "door" to M(14f, 160f, 0.40f),
        )
        assertEquals(listOf("wood", "metal", "armour", "rope", "door"), db.materials.map { it.id })
        for ((id, e) in expected) {
            val m = db.material(id)
            assertEquals(e.cost, m.costPerM, id); assertEquals(e.hp, m.hp, id); assertEquals(e.thick, m.thickness, id)
        }
        assertTrue(db.material("wood").flammable && db.material("rope").flammable)
        assertTrue(!db.material("metal").flammable && !db.material("armour").flammable)
        assertEquals(0.3f, db.material("armour").damageFactor)
        assertTrue(db.material("rope").tensionOnly)
        assertEquals(1.04f, db.material("rope").restLengthFactor)
        assertTrue(db.material("door").isDoor)
        // Dichte: leicht < schwer < sehr schwer (Prototyp: 15 / 50 / 90, Seil 4, Tür 45)
        assertEquals(listOf(15f, 50f, 90f, 4f, 45f), db.materials.map { it.density })
        assertEquals(6.0e6f, db.material("wood").stiffness)
        assertEquals(0.035f, db.material("wood").compressionLimit)
    }

    @Test
    fun weaponsMatchStyleBible() {
        val mg = db.weapon("mg")
        assertEquals(6f, mg.damage); assertEquals(8, mg.shotsPerBurst); assertEquals(60f, mg.maxRange)
        assertEquals(3f, mg.reloadSeconds); assertEquals(8f, mg.shotEnergy); assertEquals(0f, mg.shotMetal)
        assertEquals(WeaponModeDef.HITSCAN, mg.mode)

        val sn = db.weapon("sniper")
        assertEquals(40f, sn.damage); assertEquals(1, sn.piercesBeams); assertEquals(3f, sn.deviceDamageFactor)
        assertEquals(120f, sn.maxRange); assertEquals(4f, sn.reloadSeconds); assertEquals(15f, sn.shotEnergy)

        val mo = db.weapon("mortar")
        assertEquals(120f, mo.splashDamage); assertEquals(2.5f, mo.splashRadius); assertEquals(25f, mo.minRange); assertEquals(110f, mo.maxRange)
        assertEquals(6f, mo.reloadSeconds); assertEquals(15f, mo.shotMetal); assertEquals(30f, mo.shotEnergy)

        val ca = db.weapon("cannon")
        assertEquals(90f, ca.damage); assertEquals(1.2f, ca.splashRadius); assertEquals(100f, ca.maxRange)
        assertEquals(10f, ca.reloadSeconds); assertEquals(30f, ca.shotMetal); assertEquals(60f, ca.shotEnergy)
        assertEquals(2200f, ca.directImpulse)

        val ro = db.weapon("rocket")
        // Stil-Bibel "40 + entzündet Holz in 2 m | Splash 2,0 m": wie beim Mörser (Spalte Schaden = Explosionsschaden)
        // steht der Schaden als splashDamage im Splash-Radius; die Rakete hat keinen eigenen Direktschaden.
        assertEquals(0f, ro.damage); assertEquals(40f, ro.splashDamage)
        assertEquals(2.0f, ro.igniteRadius); assertEquals(2.0f, ro.splashRadius); assertEquals(100f, ro.maxRange)
        assertEquals(12f, ro.reloadSeconds); assertEquals(20f, ro.shotMetal); assertEquals(40f, ro.shotEnergy)
        assertTrue(ro.gravityScale < 1f)

        val la = db.weapon("laser")
        assertEquals(WeaponModeDef.BEAM, la.mode)
        assertEquals(80f, la.damage); assertEquals(2f, la.beamSeconds); assertEquals(140f, la.maxRange)
        assertEquals(20f, la.reloadSeconds); assertEquals(150f, la.shotEnergy)
        assertEquals(listOf("mg", "sniper", "mortar", "cannon", "rocket", "laser"), db.weapons.map { it.id })
    }

    @Test
    fun timesAreWholeTicksAndBallisticsAreConsistent() {
        // Salvenabstand: ganze Ticks (0,075 s wären 4,5 Ticks = Rundungs-Gleichstand)
        val mg = db.weapon("mg")
        assertEquals(4f, mg.burstIntervalSeconds * 60f, 0.05f)
        // Lebensdauer reicht für den steilsten Schuss bei voller Kraft (mit gravityScale), ohne Fall in die Kluft
        for (w in db.weapons.filter { it.mode == WeaponModeDef.BALLISTIC }) {
            val g = 9.81f * w.gravityScale
            val vy = w.muzzleSpeed * kotlin.math.sin(Math.toRadians(w.maxAimDeg.toDouble())).toFloat()
            val flat = 2f * vy / g
            assertTrue(w.lifetimeSeconds >= flat, "${w.id}: lifetime ${w.lifetimeSeconds} < flat flight $flat")
            // maxRange ist ein Anzeige-/KI-Wert und darf die Physik nicht überbieten
            assertTrue(w.maxRange <= w.muzzleSpeed * w.muzzleSpeed / g * 1.001f, "${w.id}: maxRange above physical range")
        }
        assertTrue(db.weapon("rocket").lifetimeSeconds >= 12f && db.weapon("cannon").lifetimeSeconds >= 9f)
    }

    @Test
    fun devicesAndEconomyMatchStyleBible() {
        assertEquals(6f, db.device("mine").metalPerSec); assertTrue(db.device("mine").requiresOre)
        assertEquals(6f, db.device("turbine").energyPerSec); assertEquals(MountRuleDef.TOP_ONLY, db.device("turbine").mountRule)
        assertEquals(2f, db.device("reactor").energyPerSec); assertTrue(db.device("reactor").unique)
        // Techgebäude: Kosten ⚙ · ⚡ · Bauzeit
        fun tech(id: String, m: Float, e: Float, s: Float) {
            val d = db.device(id)
            assertEquals(m, d.costMetal, id); assertEquals(e, d.costEnergy, id); assertEquals(s, d.buildSeconds, id)
            assertEquals(DeviceCategory.TECH, d.category); assertEquals(id, d.grantsTech)
        }
        tech("workshop", 120f, 40f, 30f); tech("armoury", 200f, 80f, 45f); tech("upgrade_center", 260f, 120f, 60f); tech("factory", 480f, 240f, 90f)
        assertEquals("upgrade_center", db.device("factory").requiresTech)
        // Waffengeräte: Freischaltung laut Tabelle
        assertEquals("armoury", db.device("mg").requiresTech); assertEquals("armoury", db.device("sniper").requiresTech)
        assertEquals("workshop", db.device("mortar").requiresTech); assertEquals("workshop", db.device("cannon").requiresTech)
        assertEquals("factory", db.device("rocket").requiresTech); assertEquals("factory", db.device("laser").requiresTech)
        for (w in db.weapons) assertEquals(w.id, db.device(w.id).weapon)
        assertEquals(listOf("mortar", "cannon"), db.tech("workshop").unlocks)
        assertEquals(listOf("upgrade_center"), db.tech("factory").requires)
    }

    @Test
    fun mapsMatchStyleBible() {
        val s = db.map("schlucht")
        assertEquals(120f, s.width)
        assertEquals(listOf(40f, 40f), s.plateaus.map { it.x1 - it.x0 })
        // Kluft 40 m breit (Plateau-Kanten 40 und 80), 18 m tief (34 → 52)
        assertEquals(40f, s.plateaus[1].x0 - s.plateaus[0].x1)
        assertEquals(18f, s.terrain.maxOf { it.y } - s.plateaus[0].y)
        val h = db.map("huegel")
        assertEquals(160f, h.width)
        assertEquals(8f, h.terrain.maxOf { it.y } - h.baseY[0]) // Senke 8 m
        for (m in db.maps) {
            assertEquals(2, m.startForts.size); assertEquals(setOf(0, 1), m.startForts.map { it.owner }.toSet())
            assertEquals(2, m.ores.count { it.owner == 0 }); assertEquals(2, m.ores.count { it.owner == 1 })
            // 5 Fundamente der Startfestung + 2 für den Anbau der KI-Pläne (x = −6 und −9 relativ zum Ursprung)
            assertEquals(7, m.foundations.count { it.owner == 0 }); assertEquals(7, m.foundations.count { it.owner == 1 })
            assertTrue(m.windMax > m.windMin)
            assertTrue(m.startForts.single { it.owner == 1 }.mirror)
        }
    }

    @Test
    fun startFortMirrorsPrototype() {
        val bp = db.blueprint("fort_standard")
        // 4 Buchten × 3 Stockwerke auf 3-m-Raster: x 0..12, y 0..-9
        assertEquals(setOf(0f, 3f, 6f, 9f, 12f, -3f), bp.nodes.map { it.x }.toSet())
        assertEquals(setOf(0f, -3f, -6f, -9f), bp.nodes.map { it.y }.toSet())
        assertEquals(5, bp.nodes.count { it.anchored && it.y == 0f && it.x >= 0f })
        val mats = bp.beams.groupingBy { it.material }.eachCount()
        for (m in listOf("wood", "metal", "armour", "door", "rope")) assertTrue((mats[m] ?: 0) > 0, "start fort uses $m")
        assertEquals(2, mats["door"]); assertEquals(1, mats["rope"])
        val devs = bp.devices.groupingBy { it.type }.eachCount()
        assertEquals(mapOf("mine" to 2, "reactor" to 1, "cannon" to 1, "mortar" to 1, "turbine" to 1, "mg" to 1), devs)
        // Reaktor im unteren Stockwerk (Balken auf y = 0), Turbine und MG oben, Mörser/Kanone hinter Türen
        fun beamY(type: String) = bp.beams[bp.devices.single { it.type == type }.beam].let { (bp.nodes[it.a].y + bp.nodes[it.b].y) / 2 }
        assertEquals(0f, beamY("reactor")); assertEquals(-9f, beamY("turbine")); assertEquals(-9f, beamY("mg"))
        assertEquals(0f, beamY("cannon")); assertEquals(-3f, beamY("mortar"))
        // Alle Geräte stehen mit der Normalen nach oben (Prototyp `face: up`)
        for (d in bp.devices) {
            val b = bp.beams[d.beam]
            val dx = bp.nodes[b.b].x - bp.nodes[b.a].x; val dy = bp.nodes[b.b].y - bp.nodes[b.a].y
            val ny = (dx / kotlin.math.sqrt(dx * dx + dy * dy)) * (if (d.sideNegative) -1f else 1f)
            assertTrue(ny < 0f, "${d.type} must face up")
        }
        for (b in bp.beams) {
            val a = bp.nodes[b.a]; val c = bp.nodes[b.b]
            assertTrue(kotlin.math.hypot(c.x - a.x, c.y - a.y) <= 6f)
        }
    }

    @Test
    fun aiBlueprintsExistForThreeDifficulties() {
        val plans = db.blueprints.filter { "ai" in it.tags }
        assertEquals(setOf("easy", "normal", "hard"), plans.flatMap { it.tags }.toSet() - "ai")
        val base = db.blueprint("fort_standard")
        for (p in plans) {
            assertTrue(p.steps.isNotEmpty())
            // Pläne setzen auf der Startfestung auf: deren Teile stehen unverändert am Anfang und sind in keinem Schritt
            assertEquals(base.nodes, p.nodes.take(base.nodes.size), "${p.id} nodes")
            assertEquals(base.beams, p.beams.take(base.beams.size), "${p.id} beams")
            assertEquals(base.devices, p.devices.take(base.devices.size), "${p.id} devices")
            assertEquals((base.beams.size until p.beams.size).toSet(), p.steps.flatMap { it.beams }.toSet(), "${p.id} step beams")
            assertEquals((base.devices.size until p.devices.size).toSet(), p.steps.flatMap { it.devices }.toSet(), "${p.id} step devices")
            // Der eindeutige Reaktor wird nie gebaut
            for (i in p.steps.flatMap { it.devices }) assertTrue(!db.device(p.devices[i].type).unique, "${p.id}: unique device in steps")
            assertTrue(p.steps.first().phase == "economy" && p.steps.last().phase == "reactor_cover")
        }
        val phases = plans.associate { it.id to it.steps.map { s -> s.phase } }
        assertTrue("workshop" in phases.getValue("ai_easy") && "factory" !in phases.getValue("ai_easy") && "weapons" !in phases.getValue("ai_easy"))
        assertTrue("weapons" in phases.getValue("ai_normal") && "factory" !in phases.getValue("ai_normal"))
        assertTrue("factory" in phases.getValue("ai_hard"))
        // Schwerer = mehr Waffentypen
        fun weaponTypes(id: String) = db.blueprint(id).devices.map { it.type }.filter { db.device(it).category == DeviceCategory.WEAPON }.toSet().size
        assertTrue(weaponTypes("ai_easy") < weaponTypes("ai_normal") && weaponTypes("ai_normal") < weaponTypes("ai_hard"))
        // Schwerer = mehr Techgebäude
        fun techs(id: String) = db.blueprint(id).devices.count { db.device(it.type).category == DeviceCategory.TECH }
        assertTrue(techs("ai_easy") < techs("ai_normal") && techs("ai_normal") < techs("ai_hard"))
    }

    /** Unabhängig vom Validator: neue Geräte der KI-Pläne kollidieren nicht mit der Startfestung, Anker haben Fundamente. */
    @Test
    fun aiPlansOverlayStartFortWithoutConflicts() {
        val base = db.blueprint("fort_standard")
        fun mount(bp: BlueprintDef, d: BpDevice): Pair<Float, Float> {
            val b = bp.beams[d.beam]; val a = bp.nodes[b.a]; val e = bp.nodes[b.b]
            val dx = e.x - a.x; val dy = e.y - a.y; val l = kotlin.math.sqrt(dx * dx + dy * dy)
            val s = if (d.sideNegative) -1f else 1f
            val h = db.material(b.material).thickness / 2f
            return Pair(a.x + dx * d.t + s * (-dy / l) * h, a.y + dy * d.t + s * (dx / l) * h)
        }
        val baseMounts = base.devices.map { mount(base, it) }
        for (p in db.blueprints.filter { "ai" in it.tags }) {
            for ((i, d) in p.devices.withIndex().drop(base.devices.size)) {
                val m = mount(p, d)
                for ((j, bm) in baseMounts.withIndex()) {
                    assertTrue(kotlin.math.hypot(m.first - bm.first, m.second - bm.second) >= db.device(d.type).minSpacing,
                        "${p.id}: ${d.type} ($i) too close to start fort device $j")
                }
            }
            // gleiche Position = gleiches Material
            for (b in p.beams.drop(base.beams.size)) {
                val a = p.nodes[b.a]; val e = p.nodes[b.b]
                for (bb in base.beams) {
                    val ba = base.nodes[bb.a]; val be = base.nodes[bb.b]
                    val same = (ba == a && be == e) || (ba == e && be == a)
                    assertTrue(!same, "${p.id}: beam duplicates a start fort beam at (${a.x}, ${a.y})")
                }
            }
            // verankerte Knoten außerhalb der Startfestung liegen auf Fundamenten beider Spieler auf allen Karten
            for (n in p.nodes.drop(base.nodes.size).filter { it.anchored }) {
                for (m in db.maps) for (f in m.startForts) {
                    val wx = if (f.mirror) f.originX - n.x else f.originX + n.x
                    assertTrue(m.foundations.any { it.owner == f.owner && kotlin.math.abs(it.x - wx) < 0.05f }, "${p.id}: no foundation at x=$wx on ${m.id}")
                }
            }
        }
    }

    @Test
    fun indexListsEveryContentFile() {
        val (index, _) = ClasspathContent.loadTexts()
        val url = assertNotNull(ClasspathContent::class.java.classLoader.getResource(ClasspathContent.INDEX_PATH))
        val dir = File(url.toURI()).parentFile
        val onDisk = dir.walkTopDown().filter { it.isFile && it.extension == "json" && it.name != "index.json" }
            .map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
        assertEquals(onDisk, index.files.toSet(), "index.json must list exactly the content files")
        assertEquals(index.files.size, index.files.toSet().size)
    }
}
