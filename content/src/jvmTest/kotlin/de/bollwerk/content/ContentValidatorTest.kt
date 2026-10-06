package de.bollwerk.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Jede [ContentRule] hat mindestens einen Negativtest auf Basis des echten Contents. */
class ContentValidatorTest {
    private val good: ContentPack = ClasspathContent.load().toPack()

    private fun ContentPack.material(id: String, f: (MaterialDef) -> MaterialDef) = copy(materials = materials.map { if (it.id == id) f(it) else it })
    private fun ContentPack.device(id: String, f: (DeviceDef) -> DeviceDef) = copy(devices = devices.map { if (it.id == id) f(it) else it })
    private fun ContentPack.weapon(id: String, f: (WeaponDef) -> WeaponDef) = copy(weapons = weapons.map { if (it.id == id) f(it) else it })
    private fun ContentPack.tech(id: String, f: (TechDef) -> TechDef) = copy(techs = techs.map { if (it.id == id) f(it) else it })
    private fun ContentPack.blueprint(id: String, f: (BlueprintDef) -> BlueprintDef) = copy(blueprints = blueprints.map { if (it.id == id) f(it) else it })
    private fun ContentPack.map(id: String, f: (MapDef) -> MapDef) = copy(maps = maps.map { if (it.id == id) f(it) else it })

    private fun assertRule(rule: ContentRule, p: ContentPack) {
        val issues = ContentValidator.validate(p)
        assertTrue(issues.any { it.rule == rule }, "expected $rule but got $issues")
    }

    @Test
    fun goodContentHasNoIssues() {
        assertEquals(emptyList(), ContentValidator.validate(good))
    }

    @Test
    fun requireValidThrowsWithAllIssues() {
        val bad = good.material("wood") { it.copy(costPerM = -1f) }
        val db = ContentDb("t", bad.materials, bad.devices, bad.weapons, bad.techs, bad.maps, bad.blueprints)
        val e = assertFailsWith<ContentException> { ContentValidator.requireValid(db) }
        assertTrue(e.message!!.contains("NEGATIVE_VALUE") && e.message!!.contains("wood"))
    }

    @Test fun duplicateIds() {
        assertRule(ContentRule.DUPLICATE_ID, good.copy(materials = good.materials + good.materials.first()))
        assertRule(ContentRule.DUPLICATE_ID, good.copy(weapons = good.weapons + good.weapons.first().copy(id = " ")))
    }

    @Test fun unknownReferences() {
        assertRule(ContentRule.UNKNOWN_REFERENCE, good.material("armour") { it.copy(requiresTech = "nope") })
        assertRule(ContentRule.UNKNOWN_REFERENCE, good.device("mortar") { it.copy(weapon = "nope") })
        assertRule(ContentRule.UNKNOWN_REFERENCE, good.device("mortar") { it.copy(requiresTech = "nope") })
        assertRule(ContentRule.UNKNOWN_REFERENCE, good.device("workshop") { it.copy(grantsTech = "nope") })
        assertRule(ContentRule.UNKNOWN_REFERENCE, good.tech("factory") { it.copy(requires = listOf("nope")) })
        assertRule(ContentRule.UNKNOWN_REFERENCE, good.tech("workshop") { it.copy(unlocks = it.unlocks + "nope") })
        assertRule(ContentRule.UNKNOWN_REFERENCE, good.blueprint("fort_standard") { bp -> bp.copy(beams = bp.beams.mapIndexed { i, b -> if (i == 0) b.copy(material = "stone") else b }) })
        assertRule(ContentRule.UNKNOWN_REFERENCE, good.blueprint("fort_standard") { bp -> bp.copy(devices = bp.devices.mapIndexed { i, d -> if (i == 0) d.copy(type = "nope") else d }) })
        assertRule(ContentRule.UNKNOWN_REFERENCE, good.map("schlucht") { m -> m.copy(startForts = m.startForts.map { it.copy(blueprint = "nope") }) })
    }

    @Test fun negativeValues() {
        assertRule(ContentRule.NEGATIVE_VALUE, good.material("wood") { it.copy(costPerM = -1f) })
        assertRule(ContentRule.NEGATIVE_VALUE, good.device("mine") { it.copy(costMetal = -5f) })
        assertRule(ContentRule.NEGATIVE_VALUE, good.device("workshop") { it.copy(costEnergy = -1f) })
        assertRule(ContentRule.NEGATIVE_VALUE, good.device("workshop") { it.copy(buildSeconds = -1f) })
        assertRule(ContentRule.NEGATIVE_VALUE, good.weapon("mortar") { it.copy(shotMetal = -1f) })
        assertRule(ContentRule.NEGATIVE_VALUE, good.weapon("mortar") { it.copy(shotEnergy = -1f) })
    }

    @Test fun invalidValues() {
        assertRule(ContentRule.INVALID_VALUE, good.material("wood") { it.copy(hp = 0f) })
        assertRule(ContentRule.INVALID_VALUE, good.material("wood") { it.copy(density = Float.NaN) })
        assertRule(ContentRule.INVALID_VALUE, good.material("wood") { it.copy(compressionLimit = 0f) }) // nicht tensionOnly
        assertRule(ContentRule.INVALID_VALUE, good.device("reactor") { it.copy(hp = -1f) })
        assertRule(ContentRule.INVALID_VALUE, good.weapon("mortar") { it.copy(minRange = 200f) })
        assertRule(ContentRule.INVALID_VALUE, good.weapon("mortar") { it.copy(reloadSeconds = 0f) })
        assertRule(ContentRule.INVALID_VALUE, good.weapon("mortar") { it.copy(muzzleSpeed = 0f) })
        assertRule(ContentRule.INVALID_VALUE, good.weapon("laser") { it.copy(beamSeconds = 0f) })
        assertRule(ContentRule.INVALID_VALUE, good.weapon("mg") { it.copy(burstIntervalSeconds = 0f) })
        assertRule(ContentRule.INVALID_VALUE, good.weapon("mortar") { it.copy(minAimDeg = 70f, maxAimDeg = 30f) })
        assertRule(ContentRule.INVALID_VALUE, good.weapon("mortar") { it.copy(defaultAimDeg = 5f) }) // außerhalb 20..85
    }

    private fun movedNode(dx: Float): (BlueprintDef) -> BlueprintDef = { bp -> bp.copy(nodes = bp.nodes.mapIndexed { i, n -> if (i == 17) n.copy(x = n.x + dx) else n }) }

    @Test fun beamTooLong() {
        // Knoten 17 (9, -9) um 8 m verschieben: Balken zu Knoten 16 und 13 werden > 6 m
        assertRule(ContentRule.BEAM_TOO_LONG, good.blueprint("fort_standard", movedNode(8f)))
    }

    @Test fun beamTooShort() {
        assertRule(ContentRule.BEAM_TOO_SHORT, good.blueprint("fort_standard", movedNode(-8.9f).let { f -> { bp: BlueprintDef -> f(bp).let { b2 -> b2.copy(nodes = b2.nodes.mapIndexed { i, n -> if (i == 17) n.copy(x = bp.nodes[16].x + 0.1f, y = bp.nodes[16].y) else n }) } } }))
    }

    @Test fun blueprintGraph() {
        assertRule(ContentRule.BLUEPRINT_GRAPH, good.blueprint("fort_standard") { bp -> bp.copy(beams = bp.beams + BpBeam(0, 99, "wood")) })
        assertRule(ContentRule.BLUEPRINT_GRAPH, good.blueprint("fort_standard") { bp -> bp.copy(beams = bp.beams + BpBeam(3, 3, "wood")) })
        assertRule(ContentRule.BLUEPRINT_GRAPH, good.blueprint("fort_standard") { bp -> bp.copy(beams = bp.beams + bp.beams.first().copy(a = bp.beams.first().b, b = bp.beams.first().a)) })
    }

    @Test fun deviceBeamIndex() {
        assertRule(ContentRule.DEVICE_BEAM_INDEX, good.blueprint("fort_standard") { bp -> bp.copy(devices = bp.devices + BpDevice("mine", 999)) })
        assertRule(ContentRule.DEVICE_BEAM_INDEX, good.blueprint("fort_standard") { bp -> bp.copy(devices = bp.devices.mapIndexed { i, d -> if (i == 0) d.copy(t = 1.5f) else d }) })
    }

    @Test fun reactorCount() {
        val noReactor = good.blueprint("fort_standard") { bp -> bp.copy(devices = bp.devices.filter { it.type != "reactor" }) }
        assertRule(ContentRule.REACTOR_COUNT, noReactor)
        assertRule(ContentRule.REACTOR_COUNT, good.blueprint("fort_standard") { bp -> bp.copy(devices = bp.devices + BpDevice("reactor", 3)) })
        assertRule(ContentRule.REACTOR_COUNT, good.map("huegel") { m -> m.copy(startForts = m.startForts.filter { it.owner == 0 }) })
        assertRule(ContentRule.REACTOR_COUNT, good.map("huegel") { m -> m.copy(startForts = m.startForts + m.startForts.first()) })
        assertRule(ContentRule.REACTOR_COUNT, good.blueprint("ai_hard") { bp -> bp.copy(devices = bp.devices.filter { it.type != "reactor" }, steps = emptyList()) })
    }

    @Test fun unlockMismatch() {
        assertRule(ContentRule.UNLOCK_MISMATCH, good.tech("workshop") { it.copy(unlocks = it.unlocks + "sniper") }) // sniper braucht armoury
        assertRule(ContentRule.UNLOCK_MISMATCH, good.tech("workshop") { it.copy(unlocks = it.unlocks + "armour") }) // Material braucht upgrade_center
        assertRule(ContentRule.UNLOCK_MISMATCH, good.tech("armoury") { it.copy(unlocks = it.unlocks - "sniper") }) // Sniper verlangt armoury, wird aber nicht gelistet
        assertRule(ContentRule.UNLOCK_MISMATCH, good.material("armour") { it.copy(requiresTech = "factory") })
    }

    @Test fun ungrantedTech() {
        assertRule(ContentRule.UNGRANTED_TECH, good.device("armoury") { it.copy(grantsTech = null) })
    }

    @Test fun techCycle() {
        assertRule(ContentRule.TECH_CYCLE, good.tech("upgrade_center") { it.copy(requires = listOf("factory")) })
        assertRule(ContentRule.TECH_CYCLE, good.tech("workshop") { it.copy(requires = listOf("workshop")) })
    }

    @Test fun weaponLink() {
        assertRule(ContentRule.WEAPON_LINK, good.device("mortar") { it.copy(weapon = null) })
        assertRule(ContentRule.WEAPON_LINK, good.device("mine") { it.copy(weapon = "mg") })
        assertRule(ContentRule.WEAPON_LINK, good.copy(devices = good.devices.filter { it.id != "cannon" })) // Waffe ohne Gerät
        assertRule(ContentRule.WEAPON_LINK, good.device("cannon") { it.copy(weapon = "mortar") }) // Waffe an zwei Geräten
    }

    @Test fun mapGeometry() {
        assertRule(ContentRule.MAP_GEOMETRY, good.map("schlucht") { m -> m.copy(terrain = m.terrain.reversed()) })
        assertRule(ContentRule.MAP_GEOMETRY, good.map("schlucht") { m -> m.copy(windMin = 3f, windMax = -3f) })
        assertRule(ContentRule.MAP_GEOMETRY, good.map("schlucht") { m -> m.copy(ores = m.ores + OreSpotDef(70f, 0)) }) // Erz in der Kluft
        assertRule(ContentRule.MAP_GEOMETRY, good.map("schlucht") { m -> m.copy(foundations = m.foundations.map { if (it.x == 24f) it.copy(y = 30f) else it }) })
        assertRule(ContentRule.MAP_GEOMETRY, good.map("schlucht") { m -> m.copy(plateaus = listOf(PlateauDef(0f, 50f, 34f))) }) // Plateau über die Kluft
        assertRule(ContentRule.MAP_GEOMETRY, good.map("schlucht") { m -> m.copy(buildZones = m.buildZones.filter { it.owner == 0 }) })
        assertRule(ContentRule.MAP_GEOMETRY, good.map("schlucht") { m -> m.copy(baseY = listOf(34f)) })
    }

    @Test fun mapFort() {
        // Ursprung 10 m nach rechts: Anker nicht mehr auf dem Boden/in der Zone (Kluft beginnt bei x = 40)
        assertRule(ContentRule.MAP_FORT, good.map("schlucht") { m -> m.copy(startForts = m.startForts.map { if (it.owner == 0) it.copy(originX = 34f) else it }) })
        assertRule(ContentRule.MAP_FORT, good.map("huegel") { m -> m.copy(baseY = listOf(30f, 34f)) })
    }

    @Test fun mineOre() {
        assertRule(ContentRule.MINE_ORE, good.map("schlucht") { m -> m.copy(ores = m.ores.filter { it.owner != 0 }) })
        assertRule(ContentRule.MINE_ORE, good.map("huegel") { m -> m.copy(ores = m.ores.map { if (it.owner == 1) it.copy(x = it.x - 10f) else it }) })
    }

    @Test fun aiPlan() {
        assertRule(ContentRule.AI_PLAN, good.blueprint("ai_hard") { it.copy(tags = listOf("ai")) }) // Schwierigkeit fehlt
        assertRule(ContentRule.AI_PLAN, good.blueprint("ai_hard") { it.copy(tags = listOf("ai", "easy")) }) // easy doppelt
        assertRule(ContentRule.AI_PLAN, good.blueprint("ai_normal") { it.copy(steps = emptyList()) })
        assertRule(ContentRule.AI_PLAN, good.blueprint("ai_normal") { bp -> bp.copy(steps = bp.steps.map { if (it.phase == "workshop") it.copy(beams = it.beams.drop(1)) else it }) }) // neuer Balken ohne Schritt
        assertRule(ContentRule.AI_PLAN, good.blueprint("ai_normal") { bp -> bp.copy(steps = bp.steps.map { if (it.phase == "weapons") it.copy(devices = emptyList()) else it }) }) // neues Gerät ohne Schritt
        assertRule(ContentRule.AI_PLAN, good.blueprint("ai_normal") { bp -> bp.copy(steps = bp.steps.reversed()) }) // Phasen-Reihenfolge
        assertRule(ContentRule.AI_PLAN, good.blueprint("ai_normal") { bp -> bp.copy(steps = bp.steps.map { it.copy(phase = if (it.phase == "workshop") "bogus" else it.phase) }) })
        assertRule(ContentRule.AI_PLAN, good.blueprint("ai_normal") { bp -> bp.copy(steps = bp.steps + bp.steps.first().copy()) }) // Wiederholung/doppelt
        // Brandrakete vor der Fabrik: Rakete in den Waffen-Schritt (vor dem Fabrik-Schritt) verschieben
        assertRule(ContentRule.AI_PLAN, good.blueprint("ai_hard") { bp ->
            val rocket = bp.devices.indexOfFirst { it.type == "rocket" }
            bp.copy(steps = bp.steps.map { s -> if (s.phase == "weapons") s.copy(devices = s.devices + rocket) else s.copy(devices = s.devices - rocket) })
        })
        // Panzerung vor dem Upgrade-Zentrum: Hauben-Balken in den Wirtschafts-Schritt
        assertRule(ContentRule.AI_PLAN, good.blueprint("ai_normal") { bp ->
            val hood = bp.steps.first { it.phase == "reactor_cover" }.beams
            bp.copy(steps = bp.steps.map { s -> if (s.phase == "economy") s.copy(beams = s.beams + hood) else if (s.phase == "reactor_cover") s.copy(beams = emptyList()) else s })
        })
    }

    private fun ContentPack.aiMessages(rule: ContentRule = ContentRule.AI_PLAN) = ContentValidator.validate(this).filter { it.rule == rule }.joinToString("\n") { it.message }

    @Test fun aiPlanMaterialConflictWithStartFort() {
        // Balken 7 der Startfestung ist Holz; ein gebauter Balken kann sein Material nicht ändern
        val p = good.blueprint("ai_hard") { bp -> bp.copy(beams = bp.beams.mapIndexed { i, b -> if (i == 7) b.copy(material = "armour") else b }) }
        assertTrue("same position" in p.aiMessages(), p.aiMessages())
    }

    @Test fun aiPlanDeviceCollidesWithStartFortDevice() {
        // Scharfschütze auf der Turbinen-Montagestelle (Balken 32, t = 0,45)
        val p = good.blueprint("ai_hard") { bp -> bp.copy(devices = bp.devices.map { if (it.type == "sniper") it.copy(beam = 32, t = 0.45f) else it }) }
        assertTrue("start fort device" in p.aiMessages(), p.aiMessages())
        assertRule(ContentRule.DEVICE_PLACEMENT, p)
    }

    @Test fun aiPlanAnchoredNodeNeedsFoundation() {
        // Fundament x = 18 (Knoten −6 relativ zum Ursprung 24) entfernen
        val p = good.map("schlucht") { m -> m.copy(foundations = m.foundations.filter { !(it.owner == 0 && it.x == 18f) }) }
        assertTrue("no foundation" in p.aiMessages(), p.aiMessages())
        // gespiegelter Spieler 1 (Ursprung 96 → 102)
        val q = good.map("huegel") { m -> m.copy(foundations = m.foundations.filter { !(it.owner == 1 && it.x == 142f) }) }
        assertTrue("no foundation" in q.aiMessages(), q.aiMessages())
    }

    @Test fun aiPlanAnchoredFlagMustMatchStartFort() {
        val p = good.blueprint("ai_normal") { bp -> bp.copy(nodes = bp.nodes.mapIndexed { i, n -> if (i == 18) n.copy(anchored = false) else n }) }
        assertTrue("anchored" in p.aiMessages(), p.aiMessages())
    }

    @Test fun aiPlanStepsMustNotContainStartFortPartsOrUniqueDevices() {
        val reactor = good.blueprint("ai_normal") { bp ->
            val r = bp.devices.indexOfFirst { it.type == "reactor" }
            bp.copy(steps = bp.steps.map { if (it.phase == "economy") it.copy(devices = it.devices + r) else it })
        }
        assertTrue("unique" in reactor.aiMessages(), reactor.aiMessages())
        val beam = good.blueprint("ai_normal") { bp -> bp.copy(steps = bp.steps.map { if (it.phase == "economy") it.copy(beams = it.beams + 0) else it }) }
        assertTrue("already part of the start fort" in beam.aiMessages(), beam.aiMessages())
        // neuer Reaktor (nicht der der Startfestung) ist ebenfalls unzulässig
        val moved = good.blueprint("ai_normal") { bp -> bp.copy(devices = bp.devices.map { if (it.type == "reactor") it.copy(t = 0.4f) else it }) }
        assertTrue("unique" in moved.aiMessages(), moved.aiMessages())
    }

    @Test fun devicePlacement() {
        fun fort(f: (BlueprintDef) -> BlueprintDef) = good.blueprint("fort_standard", f)
        fun msg(p: ContentPack) = p.aiMessages(ContentRule.DEVICE_PLACEMENT)
        // Gerät auf Seil (Balken 38) und auf Tür (Balken 27)
        assertTrue("rope" in msg(fort { it.copy(devices = it.devices + BpDevice("workshop", 38)) }))
        assertTrue("door" in msg(fort { it.copy(devices = it.devices + BpDevice("workshop", 27)) }))
        // t außerhalb 0,15..0,85
        assertTrue("outside" in msg(fort { bp -> bp.copy(devices = bp.devices.mapIndexed { i, d -> if (i == 0) d.copy(t = 0.05f) else d }) }))
        assertTrue("outside" in msg(fort { bp -> bp.copy(devices = bp.devices.mapIndexed { i, d -> if (i == 0) d.copy(t = 0.95f) else d }) }))
        // Turbine nicht nach oben (Standardseite von Balken 32 zeigt nach unten)
        assertTrue("face up" in msg(fort { bp -> bp.copy(devices = bp.devices.map { if (it.type == "turbine") it.copy(sideNegative = false) else it }) }))
        // Mindestabstand: MG neben der Turbine (Balken 32, t = 0,5)
        assertTrue("apart" in msg(fort { it.copy(devices = it.devices + BpDevice("mg", 32, 0.5f, true)) }))
    }

    @Test fun tickAlignment() {
        assertRule(ContentRule.TICK_ALIGNMENT, good.weapon("mg") { it.copy(burstIntervalSeconds = 0.075f) }) // 4,5 Ticks
        assertRule(ContentRule.TICK_ALIGNMENT, good.weapon("mortar") { it.copy(reloadSeconds = 6.0083f) }) // 360,5 Ticks
        assertRule(ContentRule.TICK_ALIGNMENT, good.weapon("laser") { it.copy(beamSeconds = 2.0083f) })
        assertRule(ContentRule.TICK_ALIGNMENT, good.device("workshop") { it.copy(buildSeconds = 30.0083f) })
    }

    @Test fun ballisticsConsistency() {
        fun msg(p: ContentPack) = p.aiMessages(ContentRule.INVALID_VALUE)
        // Rakete: Flugzeit bei 75°, voller Kraft, gravityScale 0,6 ist > 8 s
        assertTrue("flight time" in msg(good.weapon("rocket") { it.copy(lifetimeSeconds = 8f) }))
        assertTrue("flight time" in msg(good.weapon("cannon") { it.copy(lifetimeSeconds = 8f) }))
        // gravityScale senkt die Flugkurve: mit 1,0 wäre die Rakete schon nach 8 s am Ziel, mit 0,6 nicht
        assertTrue("flight time" !in msg(good.weapon("rocket") { it.copy(gravityScale = 1f, lifetimeSeconds = 12f) }))
        // maxRange über der physikalischen Reichweite
        assertTrue("physical range" in msg(good.weapon("mortar") { it.copy(maxRange = 200f) }))
        assertTrue("physical range" in msg(good.weapon("rocket") { it.copy(muzzleSpeed = 10f) }))
    }
}
