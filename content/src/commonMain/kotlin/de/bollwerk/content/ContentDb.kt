package de.bollwerk.content

/** Fehler beim Laden/Auflösen von Content. */
class ContentException(message: String) : RuntimeException(message)

/**
 * Aufgelöster Content: Listen nach Index, ID → Index-Lookups und vorab aufgelöste Querverweise.
 * Indizes sind stabil für eine gegebene [version] + Dateireihenfolge und werden in Commands,
 * Pools und `SimTables` verwendet.
 *
 * Die ID-Maps dienen nur Lade-/UI-Zwecken; der Sim-Pfad arbeitet ausschließlich mit Indizes.
 */
class ContentDb(
    val version: String,
    val materials: List<MaterialDef>,
    val devices: List<DeviceDef>,
    val weapons: List<WeaponDef>,
    val techs: List<TechDef>,
    val maps: List<MapDef>,
    val blueprints: List<BlueprintDef> = emptyList(),
) {
    private val materialIdx = indexOf("material", materials) { it.id }
    private val deviceIdx = indexOf("device", devices) { it.id }
    private val weaponIdx = indexOf("weapon", weapons) { it.id }
    private val techIdx = indexOf("tech", techs) { it.id }
    private val mapIdx = indexOf("map", maps) { it.id }
    private val blueprintIdx = indexOf("blueprint", blueprints) { it.id }

    /** Waffen-Index je Gerät (−1 = keine Waffe). */
    val deviceWeapon: IntArray = IntArray(devices.size) { i -> devices[i].weapon?.let { ref("weapon", it, weaponIdx, devices[i].id) } ?: -1 }

    /** Benötigte Tech je Gerät (−1 = keine). */
    val deviceRequiredTech: IntArray = IntArray(devices.size) { i -> devices[i].requiresTech?.let { ref("tech", it, techIdx, devices[i].id) } ?: -1 }

    /** Freigeschaltete Tech je Gerät (−1 = keine). */
    val deviceGrantsTech: IntArray = IntArray(devices.size) { i -> devices[i].grantsTech?.let { ref("tech", it, techIdx, devices[i].id) } ?: -1 }

    /** Benötigte Tech je Material (−1 = keine). */
    val materialRequiredTech: IntArray = IntArray(materials.size) { i -> materials[i].requiresTech?.let { ref("tech", it, techIdx, materials[i].id) } ?: -1 }

    /** Vorausgesetzte Tech-Indizes je Tech. */
    val techRequires: List<IntArray> = techs.map { t -> IntArray(t.requires.size) { ref("tech", t.requires[it], techIdx, t.id) } }

    init {
        for (t in techs) for (u in t.unlocks) {
            if (u !in materialIdx && u !in deviceIdx && u !in weaponIdx) {
                throw ContentException("tech '${t.id}' unlocks unknown id '$u'")
            }
        }
        // Gebäude, das Tech T gewährt, muss eine Voraussetzung von T verlangen
        for (d in devices) {
            val g = d.grantsTech ?: continue
            val req = tech(g).requires
            if (req.isNotEmpty() && d.requiresTech !in req) {
                throw ContentException("device '${d.id}' grants '$g' but does not require one of $req")
            }
        }
        for ((i, d) in devices.withIndex()) {
            if (d.category == DeviceCategory.WEAPON && deviceWeapon[i] < 0) throw ContentException("weapon device '${d.id}' has no weapon")
        }
        for (bp in blueprints) checkBlueprint(bp)
        for (m in maps) checkMap(m)
    }

    /**
     * FNV-1a-64 über die kanonische JSON-Kodierung aller Listen in Index-Reihenfolge. Jede Änderung an
     * Werten **oder** Reihenfolge ändert den Wert; Replays und Lockstep vergleichen ihn.
     */
    val fingerprint: Long by lazy {
        val pack = toPack()
        fnv1a(ContentLoader.canonicalJson.encodeToString(ContentPack.serializer(), pack).encodeToByteArray())
    }

    /** Alle Listen als [ContentPack] (Validator, Fingerabdruck, Tests). */
    fun toPack(): ContentPack = ContentPack(materials, devices, weapons, techs, maps, blueprints)

    fun blueprintIndex(id: String): Int = blueprintIdx[id] ?: throw ContentException("unknown blueprint '$id'")
    fun blueprint(id: String): BlueprintDef = blueprints[blueprintIndex(id)]

    private fun checkBlueprint(bp: BlueprintDef) {
        for (b in bp.beams) {
            if (b.a !in bp.nodes.indices || b.b !in bp.nodes.indices || b.a == b.b) {
                throw ContentException("blueprint '${bp.id}' has invalid beam ${b.a}-${b.b}")
            }
            ref("material", b.material, materialIdx, bp.id)
        }
        for (d in bp.devices) {
            if (d.beam !in bp.beams.indices) throw ContentException("blueprint '${bp.id}' device on missing beam ${d.beam}")
            ref("device", d.type, deviceIdx, bp.id)
        }
    }

    private fun checkMap(m: MapDef) {
        if (m.terrain.size < 2) throw ContentException("map '${m.id}' needs >= 2 terrain points")
        if (m.baseY.isNotEmpty() && m.baseY.size != m.playerCount) throw ContentException("map '${m.id}' baseY needs ${m.playerCount} entries")
        if (m.windMin > m.windMax) throw ContentException("map '${m.id}' windMin > windMax")
        for (f in m.startForts) {
            if (f.owner !in 0 until m.playerCount) throw ContentException("map '${m.id}' start fort owner ${f.owner}")
            ref("blueprint", f.blueprint, blueprintIdx, m.id)
        }
    }

    fun materialIndex(id: String): Int = materialIdx[id] ?: throw ContentException("unknown material '$id'")
    fun deviceIndex(id: String): Int = deviceIdx[id] ?: throw ContentException("unknown device '$id'")
    fun weaponIndex(id: String): Int = weaponIdx[id] ?: throw ContentException("unknown weapon '$id'")
    fun techIndex(id: String): Int = techIdx[id] ?: throw ContentException("unknown tech '$id'")
    fun mapIndex(id: String): Int = mapIdx[id] ?: throw ContentException("unknown map '$id'")

    fun material(id: String): MaterialDef = materials[materialIndex(id)]
    fun device(id: String): DeviceDef = devices[deviceIndex(id)]
    fun weapon(id: String): WeaponDef = weapons[weaponIndex(id)]
    fun tech(id: String): TechDef = techs[techIndex(id)]
    fun map(id: String): MapDef = maps[mapIndex(id)]

    private fun ref(kind: String, id: String, idx: Map<String, Int>, from: String): Int =
        idx[id] ?: throw ContentException("'$from' references unknown $kind '$id'")

    private companion object {
        fun fnv1a(bytes: ByteArray): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in bytes) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
            return h
        }

        fun <T> indexOf(kind: String, list: List<T>, id: (T) -> String): Map<String, Int> {
            val m = HashMap<String, Int>(list.size * 2)
            list.forEachIndexed { i, d ->
                val key = id(d)
                if (key.isBlank()) throw ContentException("$kind at index $i has blank id")
                if (m.put(key, i) != null) throw ContentException("duplicate $kind id '$key'")
            }
            return m
        }
    }
}
