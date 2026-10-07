package de.bollwerk.setup

import de.bollwerk.content.BlueprintDef
import de.bollwerk.content.BoundsDef
import de.bollwerk.content.BpBeam
import de.bollwerk.content.BpDevice
import de.bollwerk.content.BpNode
import de.bollwerk.content.BpStep
import de.bollwerk.content.BuildZoneDef
import de.bollwerk.content.ClasspathContent
import de.bollwerk.content.ContentDb
import de.bollwerk.content.ContentLoader
import de.bollwerk.content.ContentPack
import de.bollwerk.content.DeviceDef
import de.bollwerk.content.FoundationDef
import de.bollwerk.content.MapDef
import de.bollwerk.content.MaterialDef
import de.bollwerk.content.OreSpotDef
import de.bollwerk.content.PlateauDef
import de.bollwerk.content.PointDef
import de.bollwerk.content.StartFortDef
import de.bollwerk.content.TechDef
import de.bollwerk.content.WeaponDef
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimTables
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Mapping-Vollständigkeit der Brücke (`SimTablesFactory`, `MapSpecFactory`): **kein Feld einer Content-Klasse
 * wird still verworfen**. Jedes Feld jeder Def-Klasse (per Reflection ermittelt) muss hier genau einer Kategorie
 * zugeordnet sein:
 *  - [Perturb]: Das Feld wird im Content-JSON verändert; die gebauten `SimTables`/`MapSpec` müssen sich ändern.
 *  - [Verified]: Verweise/Bezeichner, deren Abbildung (Index, Schlüssel) explizit geprüft wird.
 *  - [Nested]: Liste/Objekt, dessen Elemente über die eigene Klasse abgedeckt sind.
 *  - [Ignored]: bewusst nicht abgebildet, mit Begründung; Änderungen dürfen die Ausgabe nicht ändern.
 * Kommt ein neues Feld hinzu, schlägt [everyFieldOfEveryDefClassIsCovered] fehl, bis es hier eingetragen ist.
 */
class MappingCompletenessTest {
    private val db = ClasspathContent.load()

    private sealed interface Cover
    private class Perturb(val mutate: ((JsonObject) -> JsonElement)? = null) : Cover
    private class Verified(val check: (ContentDb, SimTables) -> Unit) : Cover
    private class Nested(val cls: Class<*>) : Cover
    private class Ignored(val why: String, val mutate: ((JsonObject) -> JsonElement)? = null) : Cover

    private class Spec(val cls: Class<*>, val paths: (JsonObject) -> List<List<Any>>, val fields: Map<String, Cover>)

    // ---- Hilfen für JSON-Pfade -------------------------------------------------------------------

    private fun at(root: JsonElement, path: List<Any>): JsonElement =
        path.fold(root) { e, k -> if (k is Int) e.jsonArray[k] else e.jsonObject.getValue(k as String) }

    private fun replace(root: JsonElement, path: List<Any>, v: JsonElement, i: Int = 0): JsonElement {
        if (i == path.size) return v
        val k = path[i]
        return if (k is Int) JsonArray(root.jsonArray.toMutableList().also { it[k] = replace(it[k], path, v, i + 1) })
        else JsonObject(root.jsonObject.toMutableMap().also { it[k as String] = replace(it.getValue(k), path, v, i + 1) })
    }

    private fun indices(root: JsonObject, vararg keys: String): List<List<Any>> {
        fun go(e: JsonElement, prefix: List<Any>, rest: List<String>): List<List<Any>> {
            if (rest.isEmpty()) return listOf(prefix)
            val arr = e.jsonObject[rest[0]]?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyList()
            return arr.indices.flatMap { go(arr[it], prefix + rest[0] + it, rest.drop(1)) }
        }
        return go(root, emptyList(), keys.toList())
    }

    private fun defaultMutation(v: JsonElement): JsonElement? {
        if (v !is JsonPrimitive || v is JsonNull) return null
        if (v.isString) return JsonPrimitive(v.content + "_x")
        v.content.toBooleanStrictOrNull()?.let { return JsonPrimitive(!it) }
        v.content.toLongOrNull()?.let { return JsonPrimitive(it * 3 / 2 + 1) }
        return JsonPrimitive(v.double * 1.5 + 1.0)
    }


    private fun enumSwap(field: String, a: String, b: String): (JsonObject) -> JsonElement =
        { o -> JsonPrimitive(if (o.getValue(field).jsonPrimitive.content == a) b else a) }

    private fun appendToStrings(field: String): (JsonObject) -> JsonElement =
        { o -> JsonArray(o.getValue(field).jsonArray + JsonPrimitive("x")) }

    private fun appendInt(field: String): (JsonObject) -> JsonElement =
        { o -> JsonArray(o.getValue(field).jsonArray + JsonPrimitive(0)) }

    /** Ein Knotenindex ≠ aktueller Wert und ≠ Gegenknoten. */
    private fun otherNode(field: String, opposite: String): (JsonObject) -> JsonElement = { o ->
        val cur = o.getValue(field).jsonPrimitive.content.toInt(); val opp = o.getValue(opposite).jsonPrimitive.content.toInt()
        JsonPrimitive((0..3).first { it != cur && it != opp })
    }


    // ---- Spezifikation: alle Def-Klassen ----------------------------------------------------------

    private val nameKey = Ignored("UI-Schlüssel: Anzeigenamen kommen aus den App-Ressourcen (DE/EN), die Simulation braucht sie nicht")

    private val specs: List<Spec> = listOf(
        Spec(MaterialDef::class.java, { indices(it, "materials") }, mapOf(
            "id" to Verified { d, t -> assertEquals(d.materials.map { it.id }, t.materials.map { it.key }) },
            "nameKey" to nameKey,
            "costPerM" to Perturb(), "hp" to Perturb(), "density" to Perturb(), "stiffness" to Perturb(), "tensionLimit" to Perturb(),
            "compressionLimit" to Perturb(), "damageFactor" to Perturb(), "thickness" to Perturb(), "flammable" to Perturb(),
            "damping" to Perturb(), "tensionOnly" to Perturb(), "restLengthFactor" to Perturb(), "isDoor" to Perturb(),
            "requiresTech" to Verified { d, t -> assertEquals(d.materialRequiredTech.toList(), t.materials.map { it.requiredTech }) },
        )),
        Spec(DeviceDef::class.java, { indices(it, "devices") }, mapOf(
            "id" to Verified { d, t -> assertEquals(d.devices.map { it.id }, t.devices.map { it.key }) },
            "nameKey" to nameKey,
            "category" to Perturb(enumSwap("category", "other", "mine")), "costMetal" to Perturb(), "costEnergy" to Perturb(),
            "hp" to Perturb(), "mass" to Perturb(), "hitRadius" to Perturb(), "mountOffset" to Perturb(), "pivotOffset" to Perturb(),
            "barrelLength" to Perturb(), "buildSeconds" to Perturb(), "metalPerSec" to Perturb(), "energyPerSec" to Perturb(),
            "weapon" to Verified { d, t -> assertEquals(d.deviceWeapon.toList(), t.devices.map { it.weapon }) },
            "requiresTech" to Verified { d, t -> assertEquals(d.deviceRequiredTech.toList(), t.devices.map { it.requiredTech }) },
            "grantsTech" to Verified { d, t -> assertEquals(d.deviceGrantsTech.toList(), t.devices.map { it.grantsTech }) },
            "unique" to Perturb(), "requiresOre" to Perturb(), "oreRadius" to Perturb(),
            "mountRule" to Perturb(enumSwap("mountRule", "any", "top")), "minSpacing" to Perturb(),
        )),
        Spec(WeaponDef::class.java, { indices(it, "weapons") }, mapOf(
            "id" to Verified { d, t -> assertEquals(d.weapons.map { it.id }, t.weapons.map { it.key }) },
            "nameKey" to nameKey,
            "projectile" to Ignored("Geschossdarstellung: Renderer wählt das Aussehen über Waffen-Index/-Schlüssel (`ProjectilePool.kindOf` = Waffenindex)", enumSwap("projectile", "bullet", "shell")),
            "mode" to Perturb(enumSwap("mode", "ballistic", "hitscan")),
            "damage" to Perturb(), "splashRadius" to Perturb(), "splashDamage" to Perturb(), "minRange" to Perturb(), "maxRange" to Perturb(),
            "reloadSeconds" to Perturb(), "shotMetal" to Perturb(), "shotEnergy" to Perturb(), "muzzleSpeed" to Perturb(),
            "shotsPerBurst" to Perturb(), "burstIntervalSeconds" to Perturb(), "spreadDeg" to Perturb(), "directImpulse" to Perturb(),
            "explosionImpulse" to Perturb(), "recoilImpulse" to Perturb(), "igniteRadius" to Perturb(), "piercesBeams" to Perturb(),
            "deviceDamageFactor" to Perturb(), "projectileRadius" to Perturb(), "lifetimeSeconds" to Perturb(), "beamSeconds" to Perturb(),
            "defaultAimDeg" to Perturb(), "defaultPower" to Perturb(), "gravityScale" to Perturb(), "minAimDeg" to Perturb(), "maxAimDeg" to Perturb(),
        )),
        Spec(TechDef::class.java, { indices(it, "techs") }, mapOf(
            "id" to Verified { d, t -> assertEquals(d.techs.map { it.id }, t.techs.map { it.key }) },
            "nameKey" to nameKey,
            "requires" to Verified { d, t -> assertEquals(d.techRequires.map { it.toList() }, t.techs.map { it.requires }) },
            "unlocks" to Ignored("nur Anzeige im Techbaum (UI liest `ContentDb`); freigeschaltet wird zur Laufzeit über `DeviceProps.requiredTech`/`MaterialProps.requiredTech`"),
        )),
        Spec(BlueprintDef::class.java, { indices(it, "blueprints") }, mapOf(
            "id" to Verified { d, t -> assertEquals(d.blueprints.map { it.id }, t.blueprints.map { it.key }) },
            "nameKey" to nameKey,
            "nodes" to Nested(BpNode::class.java), "beams" to Nested(BpBeam::class.java), "devices" to Nested(BpDevice::class.java),
            "steps" to Nested(BpStep::class.java),
            "tags" to Perturb(appendToStrings("tags")),
        )),
        Spec(BpNode::class.java, { indices(it, "blueprints", "nodes") }, mapOf("x" to Perturb(), "y" to Perturb(), "anchored" to Perturb())),
        Spec(BpBeam::class.java, { indices(it, "blueprints", "beams") }, mapOf(
            "a" to Perturb(otherNode("a", "b")), "b" to Perturb(otherNode("b", "a")),
            "material" to Verified { d, t -> assertEquals(d.blueprints.map { bp -> bp.beams.map { d.materialIndex(it.material) } }, t.blueprints.map { bp -> bp.beams.map { it.material } }) },
        )),
        Spec(BpDevice::class.java, { indices(it, "blueprints", "devices") }, mapOf(
            "type" to Verified { d, t -> assertEquals(d.blueprints.map { bp -> bp.devices.map { d.deviceIndex(it.type) } }, t.blueprints.map { bp -> bp.devices.map { it.type } }) },
            "beam" to Perturb({ o -> JsonPrimitive(if (o.getValue("beam").jsonPrimitive.content == "0") 1 else 0) }),
            "t" to Perturb(), "sideNegative" to Perturb(),
        )),
        Spec(BpStep::class.java, { indices(it, "blueprints", "steps") }, mapOf(
            "phase" to Perturb(), "beams" to Perturb(appendInt("beams")), "devices" to Perturb(appendInt("devices")),
        )),
        Spec(MapDef::class.java, { indices(it, "maps") }, mapOf(
            "id" to Verified { d, _ -> assertEquals(d.maps.map { it.id }, d.maps.map { MapSpecFactory.build(it).id }) },
            "nameKey" to nameKey,
            "width" to Perturb(), "height" to Perturb(),
            "terrain" to Nested(PointDef::class.java), "plateaus" to Nested(PlateauDef::class.java), "ores" to Nested(OreSpotDef::class.java),
            "foundations" to Nested(FoundationDef::class.java), "buildZones" to Nested(BuildZoneDef::class.java), "startForts" to Nested(StartFortDef::class.java),
            "baseY" to Perturb({ o -> JsonArray(o.getValue("baseY").jsonArray.mapIndexed { i, e -> if (i == 0) JsonPrimitive(e.jsonPrimitive.double + 1.0) else e }) }),
            "bounds" to Nested(BoundsDef::class.java),
            "windMin" to Perturb(), "windMax" to Perturb(),
            "playerCount" to Verified { d, _ -> for (m in d.maps) assertEquals(m.playerCount, MapSpecFactory.build(m).playerCount) },
        )),
        Spec(PointDef::class.java, { indices(it, "maps", "terrain") }, mapOf("x" to Perturb(), "y" to Perturb())),
        Spec(PlateauDef::class.java, { indices(it, "maps", "plateaus") }, mapOf(
            "x0" to plateauIgnored, "x1" to plateauIgnored, "y" to plateauIgnored,
        )),
        Spec(OreSpotDef::class.java, { indices(it, "maps", "ores") }, mapOf("x" to Perturb(), "owner" to Perturb())),
        Spec(FoundationDef::class.java, { indices(it, "maps", "foundations") }, mapOf("owner" to Perturb(), "x" to Perturb(), "y" to Perturb())),
        Spec(BuildZoneDef::class.java, { indices(it, "maps", "buildZones") }, mapOf("owner" to Perturb(), "x0" to Perturb(), "x1" to Perturb())),
        Spec(StartFortDef::class.java, { indices(it, "maps", "startForts") }, mapOf(
            "owner" to Perturb(),
            "blueprint" to Verified { d, _ -> for (m in d.maps) assertEquals(m.startForts.map { it.blueprint }, MapSpecFactory.build(m).startForts.map { it.blueprintKey }) },
            "originX" to Perturb(), "mirror" to Perturb(),
        )),
        Spec(BoundsDef::class.java, { emptyList() }, mapOf(
            "minX" to boundsVerified { it.killMinX }, "maxX" to boundsVerified { it.killMaxX },
            "minY" to boundsVerified { it.killMinY }, "maxY" to boundsVerified { it.killMaxY },
        )),
    )

    private val plateauIgnored get() = Ignored("Plateaus beschreiben dieselbe Geometrie wie `terrain`; `MapSpec` hält nur den Polygonzug, `ContentValidator` (MAP_GEOMETRY) prüft die Übereinstimmung")

    private fun boundsVerified(read: (MapSpec) -> Float) = Verified { d, _ ->
        val m = d.maps.first().copy(bounds = BoundsDef(-11f, 222f, -33f, 444f))
        val s = MapSpecFactory.build(m)
        val all = listOf(s.killMinX, s.killMaxX, s.killMinY, s.killMaxY)
        assertEquals(listOf(-11f, 222f, -33f, 444f), all)
        assertTrue(read(s) in all)
    }

    // ---- Beobachtung -----------------------------------------------------------------------------

    private fun digest(m: MapSpec): String = buildString {
        append(m.id).append('|').append(m.width).append('|').append(m.height).append('|').append(m.playerCount).append('|')
        append(m.windMin).append('|').append(m.windMax).append('|')
        append(m.killMinX).append(',').append(m.killMaxX).append(',').append(m.killMinY).append(',').append(m.killMaxY).append('|')
        append(m.baseY.toList()).append('|').append(m.buildZones).append(m.ores).append(m.foundations).append(m.startForts).append('|')
        append(m.terrain.x0).append(',').append(m.terrain.step).append(',').append(m.terrain.heights.contentHashCode())
    }

    private fun observe(d: ContentDb): Pair<SimTables, List<String>> = SimTablesFactory.build(d) to d.maps.map { digest(MapSpecFactory.build(it)) }

    private val baseTree: JsonObject = ContentLoader.canonicalJson.encodeToJsonElement(ContentPack.serializer(), db.toPack()).jsonObject
    private val baseObs = observe(db)

    private fun dbOf(tree: JsonElement): ContentDb {
        val p = ContentLoader.json.decodeFromJsonElement(ContentPack.serializer(), tree)
        return ContentDb(db.version, p.materials, p.devices, p.weapons, p.techs, p.maps, p.blueprints)
    }

    /** Verändert [field] an einer Instanz von [spec] und liefert, ob sich die Brückenausgabe dadurch änderte (erste Instanz, bei der sie sich ändert). */
    private fun changesOutput(spec: Spec, field: String, mutate: ((JsonObject) -> JsonElement)?, maxInstances: Int): Boolean {
        for (path in spec.paths(baseTree).take(maxInstances)) {
            val obj = at(baseTree, path).jsonObject
            val v = obj[field] ?: continue
            val nv = mutate?.invoke(obj) ?: defaultMutation(v) ?: continue
            val changed = dbOf(replace(baseTree, path + field, nv))
            if (observe(changed) != baseObs) return true
        }
        return false
    }

    // ---- Tests -----------------------------------------------------------------------------------

    private fun fieldsOf(cls: Class<*>): Set<String> =
        cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()

    @Test
    fun everyFieldOfEveryDefClassIsCovered() {
        for (s in specs) {
            assertEquals(fieldsOf(s.cls), s.fields.keys, "${s.cls.simpleName}: Feldliste in MappingCompletenessTest muss alle Felder der Klasse enthalten " +
                "(neues Feld: in SimTablesFactory/MapSpecFactory abbilden und hier als Perturb/Verified eintragen oder als Ignored begründen)")
        }
        val covered = specs.map { it.cls }.toSet()
        for (s in specs) for ((f, c) in s.fields) if (c is Nested) assertTrue(c.cls in covered, "${s.cls.simpleName}.$f verweist auf ungeprüfte Klasse ${c.cls.simpleName}")
        for (s in specs) for ((f, c) in s.fields) if (c is Ignored) assertTrue(c.why.length > 20, "${s.cls.simpleName}.$f braucht eine Begründung")
    }

    @Test
    fun perturbedFieldsChangeTheBridgeOutput() {
        var n = 0
        for (s in specs) for ((f, c) in s.fields) if (c is Perturb) {
            assertTrue(changesOutput(s, f, c.mutate, Int.MAX_VALUE), "${s.cls.simpleName}.$f wird von SimTablesFactory/MapSpecFactory nicht abgebildet")
            n++
        }
        assertTrue(n > 80, "only $n perturbed fields")
    }

    @Test
    fun verifiedFieldsAreMappedByIndexOrKey() {
        val tables = SimTablesFactory.build(db)
        for (s in specs) for ((f, c) in s.fields) if (c is Verified) {
            try { c.check(db, tables) } catch (e: AssertionError) { throw AssertionError("${s.cls.simpleName}.$f: ${e.message}", e) }
        }
    }

    @Test
    fun ignoredFieldsReallyDoNotAffectTheBridge() {
        for (s in specs) for ((f, c) in s.fields) if (c is Ignored) {
            assertTrue(!changesOutput(s, f, c.mutate, 3), "${s.cls.simpleName}.$f ist als ignoriert eingetragen, wird aber abgebildet: als Perturb eintragen")
        }
    }

    @Test
    fun conversionsAreExact() {
        val t = SimTablesFactory.build(db)
        val cfg = de.bollwerk.engine.sim.SimConfig.DEFAULT
        for ((i, w) in db.weapons.withIndex()) {
            val p = t.weapons[i]
            assertEquals(cfg.secondsToTicks(w.reloadSeconds), p.reloadTicks, w.id)
            assertEquals(cfg.secondsToTicks(w.lifetimeSeconds), p.ttlTicks, w.id)
            assertEquals(w.minAimDeg * FloatMath.DEG_TO_RAD, p.minAimRad, w.id)
            assertEquals(w.maxAimDeg * FloatMath.DEG_TO_RAD, p.maxAimRad, w.id)
            assertEquals(w.gravityScale, p.gravityScale, w.id)
            assertTrue(p.minAimRad <= p.defaultAimRad && p.defaultAimRad <= p.maxAimRad, "${w.id} default aim within limits")
        }
        assertEquals(db.blueprints.map { bp -> bp.steps.map { Triple(it.phase, it.beams, it.devices) } },
            t.blueprints.map { bp -> bp.steps.map { Triple(it.phase, it.beams, it.devices) } })
        // jedes Waffengerät verweist auf seine Waffe und umgekehrt
        for (w in db.weapons) assertEquals(db.weaponIndex(w.id), t.devices[db.deviceIndex(w.id)].weapon)
        assertNotEquals(0, t.blueprints.size)
    }
}
