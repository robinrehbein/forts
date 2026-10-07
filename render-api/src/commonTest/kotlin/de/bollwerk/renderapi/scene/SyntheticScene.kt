package de.bollwerk.renderapi.scene

import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.FoundationSpec
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.MaterialProps
import de.bollwerk.engine.sim.MountRule
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.sim.OreSpec
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.sim.WeaponProps
import de.bollwerk.engine.sim.ZoneSpec
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.FxEvent

/**
 * Synthetische Szene für Tests und die Vorschau-PNGs: Content-Tabellen (Werte wie in `content/`), die Karte
 * "Schlucht" und zwei Festungen im Aufbau des Prototyps (`buildFort`), direkt als [FrameSnapshot]
 * (keine Simulation nötig).
 */
class SyntheticScene(val width: Float = 120f, canyon: FloatArray = floatArrayOf(40f, 47.5f, 72.5f, 80f)) {
    val tables: SimTables = makeTables()
    val map: MapSpec = makeMap(width, canyon)
    val snap = FrameSnapshot()

    private var cap = 0
    private var nextUid = 1

    init {
        grow(512)
        snap.tick = 600
        snap.seq = 1
        snap.wind = 3.2f
    }

    private fun grow(n: Int) {
        cap = n
        snap.nodeX = snap.nodeX.copyOf(n); snap.nodeY = snap.nodeY.copyOf(n)
        snap.nodePrevX = snap.nodePrevX.copyOf(n); snap.nodePrevY = snap.nodePrevY.copyOf(n)
        snap.nodeFlags = snap.nodeFlags.copyOf(n); snap.nodeOwner = snap.nodeOwner.copyOf(n); snap.nodeUid = snap.nodeUid.copyOf(n)
        snap.beamA = snap.beamA.copyOf(n); snap.beamB = snap.beamB.copyOf(n)
        snap.beamMaterial = snap.beamMaterial.copyOf(n); snap.beamUid = snap.beamUid.copyOf(n)
        snap.beamRestLen = snap.beamRestLen.copyOf(n); snap.beamHp01 = snap.beamHp01.copyOf(n)
        snap.beamFire01 = snap.beamFire01.copyOf(n); snap.beamFuel01 = snap.beamFuel01.copyOf(n)
        snap.beamLoad01 = snap.beamLoad01.copyOf(n); snap.beamTexOffset = snap.beamTexOffset.copyOf(n)
        snap.beamFlags = snap.beamFlags.copyOf(n); snap.beamOwner = snap.beamOwner.copyOf(n)
        snap.deviceType = snap.deviceType.copyOf(64); snap.deviceUid = snap.deviceUid.copyOf(64)
        snap.deviceBeam = snap.deviceBeam.copyOf(64); snap.deviceT = snap.deviceT.copyOf(64)
        snap.deviceHp01 = snap.deviceHp01.copyOf(64); snap.deviceMaxHp = snap.deviceMaxHp.copyOf(64)
        snap.deviceOwner = snap.deviceOwner.copyOf(64); snap.deviceAim = snap.deviceAim.copyOf(64)
        snap.devicePower = snap.devicePower.copyOf(64); snap.deviceReload01 = snap.deviceReload01.copyOf(64)
        snap.deviceBuild01 = snap.deviceBuild01.copyOf(64)
        snap.deviceX = snap.deviceX.copyOf(64); snap.deviceY = snap.deviceY.copyOf(64)
        snap.deviceNX = snap.deviceNX.copyOf(64); snap.deviceNY = snap.deviceNY.copyOf(64)
        snap.deviceLaserEndX = snap.deviceLaserEndX.copyOf(64); snap.deviceLaserEndY = snap.deviceLaserEndY.copyOf(64)
        snap.deviceFlags = snap.deviceFlags.copyOf(64)
        snap.projX = FloatArray(16); snap.projY = FloatArray(16); snap.projPrevX = FloatArray(16); snap.projPrevY = FloatArray(16)
        snap.projVx = FloatArray(16); snap.projVy = FloatArray(16); snap.projKind = IntArray(16); snap.projOwner = IntArray(16)
        snap.projUid = IntArray(16); snap.projFlags = IntArray(16)
    }

    // ---- Aufbau ----

    fun node(x: Float, y: Float, owner: Int, anchored: Boolean = false): Int {
        val i = snap.nodeCount++
        snap.nodeX[i] = x; snap.nodeY[i] = y; snap.nodePrevX[i] = x; snap.nodePrevY[i] = y
        snap.nodeFlags[i] = NodeFlags.ALIVE or (if (anchored) NodeFlags.ANCHORED else 0)
        snap.nodeOwner[i] = owner; snap.nodeUid[i] = nextUid++
        return i
    }

    fun beam(a: Int, b: Int, material: Int, owner: Int = snap.nodeOwner[a]): Int {
        val i = snap.beamCount++
        snap.beamA[i] = a; snap.beamB[i] = b; snap.beamMaterial[i] = material; snap.beamUid[i] = nextUid++
        val dx = snap.nodeX[b] - snap.nodeX[a]; val dy = snap.nodeY[b] - snap.nodeY[a]
        val l = kotlin.math.sqrt(dx * dx + dy * dy)
        snap.beamRestLen[i] = if (material == ROPE) l * 1.04f else l
        snap.beamHp01[i] = 1f; snap.beamFire01[i] = 0f; snap.beamFuel01[i] = 1f; snap.beamLoad01[i] = 0f
        snap.beamTexOffset[i] = (i * 0.37f) % 2f
        snap.beamFlags[i] = BeamFlags.ALIVE
        snap.beamOwner[i] = owner
        return i
    }

    /** Gerät auf Balken [beam] bei [t] (von Ende A); Normale nach oben. */
    fun device(typeKey: String, beam: Int, t: Float, owner: Int, aimRad: Float = 0.9f): Int {
        val type = tables.devices.indexOfFirst { it.key == typeKey }
        require(type >= 0) { "unknown device $typeKey" }
        val i = snap.deviceCount++
        snap.deviceType[i] = type; snap.deviceUid[i] = nextUid++; snap.deviceBeam[i] = beam; snap.deviceT[i] = t
        snap.deviceHp01[i] = 1f; snap.deviceMaxHp[i] = tables.devices[type].hp
        snap.deviceOwner[i] = owner; snap.deviceAim[i] = aimRad; snap.devicePower[i] = 0.78f
        snap.deviceReload01[i] = 1f; snap.deviceBuild01[i] = 1f
        val a = snap.beamA[beam]; val b = snap.beamB[beam]
        val dx = snap.nodeX[b] - snap.nodeX[a]
        // Normale (-dy, dx)/L zeigt nach oben, wenn dx < 0; sonst gegenüberliegende Seite
        snap.deviceFlags[i] = DeviceFlags.ALIVE or (if (dx > 0f) DeviceFlags.SIDE_NEG else 0)
        // Lage wie die Engine sie je Tick schreibt (Fußpunkt und Normale der Montagefläche)
        val geo = FloatArray(DeviceGeometry.SIZE)
        val props = tables.devices[type]
        val mat = snap.beamMaterial[beam]
        DeviceGeometry.mountAt(
            snap.nodeX[a], snap.nodeY[a], snap.nodeX[b], snap.nodeY[b], t, (snap.deviceFlags[i] and DeviceFlags.SIDE_NEG) != 0,
            tables.materials[mat].thickness, props.mountOffset, props.pivotOffset, props.barrelLength, aimRad, geo,
        )
        snap.deviceX[i] = geo[DeviceGeometry.X]; snap.deviceY[i] = geo[DeviceGeometry.Y]
        snap.deviceNX[i] = geo[DeviceGeometry.NX]; snap.deviceNY[i] = geo[DeviceGeometry.NY]
        return i
    }

    fun clearFx() { snap.fx.clear() }

    /** Beschädigungs-/Material-Galerie (wie `9-effekte`): Zeilen von Balken auf freien Knoten, y von oben nach unten. */
    fun materialGallery(): SyntheticScene {
        fun hbeam(x: Float, y: Float, len: Float, mat: Int, hp: Float = 1f): Int {
            val a = node(x, y, 0); val b = node(x + len, y, 0)
            val i = beam(a, b, mat, 0)
            snap.beamHp01[i] = hp
            return i
        }
        val hps = floatArrayOf(1f, 0.6f, 0.3f, 0.1f)
        for (k in hps.indices) { hbeam(2f + k * 5f, 4f, 4f, WOOD, hps[k]); hbeam(2f + k * 5f, 8f, 4f, METAL, hps[k]) }
        // gebrochen: Holzhälften mit Splitterenden, Metall mit abgescherten Enden
        for (row in 0 until 2) {
            val y = if (row == 0) 4f else 8f
            val m = if (row == 0) WOOD else METAL
            val x = 22f
            val a = hbeam(x, y - 0.4f, 1.7f, m, 0.3f); val b = hbeam(x + 2.3f, y + 0.5f, 1.7f, m, 0.3f)
            snap.beamFlags[a] = snap.beamFlags[a] or BeamFlags.JAG_B or BeamFlags.DEBRIS
            snap.beamFlags[b] = snap.beamFlags[b] or BeamFlags.JAG_A or BeamFlags.DEBRIS
        }
        // Feuer: Entzündung, Brand, verkohlt
        val f1 = hbeam(2f, 13f, 4f, WOOD); snap.beamFire01[f1] = 0.25f
        val f2 = hbeam(7f, 13f, 4f, WOOD); snap.beamFire01[f2] = 0.9f; snap.beamFuel01[f2] = 0.8f
        val f3 = hbeam(12f, 13f, 4f, WOOD); snap.beamFire01[f3] = 0.7f; snap.beamFuel01[f3] = 0.25f
        // Panzer, Seil, Türen, kritisch (pulsierend), Dehnung
        hbeam(2f, 18f, 4f, ARMOUR)
        val rp = hbeam(7f, 18f, 4f, ROPE); snap.beamRestLen[rp] = 4.6f
        hbeam(12f, 18f, 3f, DOOR)
        val dop = hbeam(16f, 18f, 3f, DOOR); snap.beamFlags[dop] = snap.beamFlags[dop] or BeamFlags.DOOR_OPEN
        hbeam(20f, 18f, 4f, ARMOUR, 0.3f)
        val ld = hbeam(17f, 13f, 4f, WOOD); snap.beamLoad01[ld] = 0.9f
        snap.beamHp01[hbeam(22f, 13f, 4f, METAL)] = 0.1f
        return this
    }

    /** Alle Geräte nebeneinander auf einer Metallschiene (Asset-Sheet-Vergleich). */
    fun deviceGallery(): SyntheticScene {
        val keys = listOf("reactor", "mine", "turbine", "workshop", "armoury", "upgrade_center", "factory", "mg", "sniper", "mortar", "cannon", "rocket", "laser")
        var x = 2f
        for (k in keys) {
            val len = if (k == "factory") 4f else 3.2f
            val a = node(x, 20f, 0, true); val b = node(x + len, 20f, 0, true)
            val bm = beam(a, b, METAL, 0)
            device(k, bm, 0.5f, 0, 0.8f)
            x += len + 0.4f
        }
        return this
    }

    /** Prototyp-Festung eines Spielers ([team] 0 links, 1 rechts gespiegelt). */
    fun fort(team: Int) {
        val cols = floatArrayOf(24f, 27f, 30f, 33f, 36f)
        val rows = floatArrayOf(34f, 31f, 28f, 25f)
        fun fx(x: Float) = if (team == 1) width - x else x
        val n = HashMap<String, Int>()
        fun nd(i: Int, j: Int, anchored: Boolean = false) { n["$i$j"] = node(fx(cols[i]), rows[j], team, anchored) }
        for (i in 0 until 5) nd(i, 0, true)
        for (i in 0 until 5) nd(i, 1)
        for (i in 0 until 5) nd(i, 2)
        for (i in 1 until 4) nd(i, 3)
        val r = node(fx(21f), rows[0], team, true)
        val mat = mapOf('W' to WOOD, 'M' to METAL, 'A' to ARMOUR, 'D' to DOOR, 'R' to ROPE)
        fun bm(p: String, q: String, m: Char): Int = beam(n[p]!!, n[q]!!, mat[m]!!, team)
        val fZ = bm("00", "10", 'M'); val fA = bm("10", "20", 'M'); val fB = bm("20", "30", 'W'); val fC = bm("30", "40", 'M')
        bm("00", "01", 'M'); bm("10", "11", 'W'); bm("20", "21", 'W'); bm("30", "31", 'W'); bm("40", "41", 'D')
        bm("01", "10", 'W'); bm("20", "31", 'W'); bm("30", "41", 'M')
        bm("01", "11", 'M'); bm("11", "21", 'M'); bm("21", "31", 'M'); val dC = bm("31", "41", 'M')
        bm("01", "02", 'M'); bm("11", "12", 'W'); bm("21", "22", 'W'); bm("31", "32", 'W'); bm("41", "42", 'D')
        bm("01", "12", 'W'); bm("11", "22", 'W'); bm("21", "12", 'W'); bm("22", "31", 'W')
        bm("02", "12", 'W'); bm("12", "22", 'W'); bm("22", "32", 'W'); bm("32", "42", 'A')
        bm("12", "13", 'M'); bm("22", "23", 'W'); bm("32", "33", 'A')
        val rA = bm("13", "23", 'M'); val rB = bm("23", "33", 'M')
        bm("12", "23", 'W'); bm("33", "22", 'W'); bm("33", "42", 'A'); bm("02", "13", 'W')
        beam(r, n["01"]!!, ROPE, team)
        fun dir(deg: Float): Float = ((if (team == 1) 180f - deg else deg) * 0.017453292f)
        device("mine", fZ, 0.5f, team); device("reactor", fA, 0.5f, team); device("mine", fB, 0.5f, team)
        device("cannon", fC, 0.3f, team, dir(13f)); device("mortar", dC, 0.72f, team, dir(52f))
        device("turbine", rA, 0.45f, team); device("mg", rB, 0.62f, team, dir(0f))
        snap.deviceBeam[snap.deviceCount - 1] = rB
    }

    /** Beide Festungen + Wind, wie im Spiel-Mockup. */
    fun both(): SyntheticScene { fort(0); fort(1); return this }

    /** Eintrag aus Beschädigung/Feuer wie in `9-effekte`: [slot] Balken mit TP-Anteil [hp]. */
    fun damage(slot: Int, hp: Float, fire: Float = 0f, fuel: Float = 1f) {
        snap.beamHp01[slot] = hp; snap.beamFire01[slot] = fire; snap.beamFuel01[slot] = fuel
    }

    fun explosionEvent(x: Float, y: Float, radius: Float = 2.5f, damage: Float = 120f, mat: Int = WOOD): FxEvent.Explosion =
        FxEvent.Explosion(snap.tick, x, y, radius, damage, 2, mat, false, -1, -1f)

    companion object {
        const val WOOD = 0
        const val METAL = 1
        const val ARMOUR = 2
        const val ROPE = 3
        const val DOOR = 4

        /** Schmalere Schlucht (Gegner nur 32 m entfernt) für Vorschau-Bilder im Maßstab des Mockups. */
        fun narrow(): SyntheticScene = SyntheticScene(92f, floatArrayOf(40f, 43f, 49f, 52f))

        fun makeMap(width: Float = 120f, canyon: FloatArray = floatArrayOf(40f, 47.5f, 72.5f, 80f)): MapSpec {
            val xs = floatArrayOf(-40f, canyon[0], canyon[1], canyon[2], canyon[3], width + 40f)
            val ys = floatArrayOf(34f, 34f, 52f, 52f, 34f, 34f)
            return MapSpec(
                id = "schlucht", width = width, height = 64f,
                terrain = Terrain.fromPolyline(xs, ys),
                buildZones = listOf(ZoneSpec(0, 0f, 39f), ZoneSpec(1, 81f, 120f)),
                ores = listOf(OreSpec(0, 25.5f), OreSpec(0, 31.5f), OreSpec(1, width - 25.5f), OreSpec(1, width - 31.5f)),
                foundations = listOf(FoundationSpec(0, 24f, 34f)),
                startForts = emptyList(),
                baseY = floatArrayOf(34f, 34f),
                windMin = -6f, windMax = 6f,
                killMinX = -40f, killMaxX = width + 40f, killMinY = -120f, killMaxY = 70f,
            )
        }

        private fun mat(key: String, cost: Float, hp: Float, th: Float, flam: Boolean, rope: Boolean = false, door: Boolean = false) =
            MaterialProps(key, cost, hp, 10f, 1e7f, 0.05f, if (rope) Float.POSITIVE_INFINITY else 0.03f, 1f, th, flam, 0.1f, rope, if (rope) 1.04f else 1f, door, -1)

        private fun dev(key: String, role: DeviceRole, hp: Float, rad: Float, mo: Float, po: Float = 0f, bl: Float = 0f, weapon: Int = -1) =
            DeviceProps(key, role, 100f, 0f, hp, 100f, rad, mo, po, bl, 60, 0f, 0f, weapon, -1, -1, false, false, 0f, MountRule.ANY, 1.5f)

        private fun wpn(key: String, mode: WeaponMode, splash: Float) = WeaponProps(
            key, mode, 40f, splash, 0f, 0f, 100f, 360, 0f, 0f, 30f, 1, 0, 0f, 0f, 0f, 0f, 0f, 0, 1f, 0.14f, 600, 0, 0.9f, 0.8f,
        )

        fun makeTables(): SimTables = SimTables(
            materials = listOf(
                mat("wood", 4f, 100f, 0.32f, true), mat("metal", 10f, 260f, 0.26f, false), mat("armour", 18f, 520f, 0.42f, false),
                mat("rope", 2f, 60f, 0.08f, true, rope = true), mat("door", 14f, 160f, 0.40f, false, door = true),
            ),
            devices = listOf(
                dev("reactor", DeviceRole.REACTOR, 300f, 1.2f, 1.2f), dev("mine", DeviceRole.MINE, 90f, 1f, 1.1f),
                dev("turbine", DeviceRole.TURBINE, 60f, 1f, 1.8f), dev("workshop", DeviceRole.TECH, 120f, 1.1f, 1f),
                dev("armoury", DeviceRole.TECH, 140f, 1.2f, 1.1f), dev("upgrade_center", DeviceRole.TECH, 150f, 1.2f, 1.3f),
                dev("factory", DeviceRole.TECH, 220f, 1.5f, 1.4f),
                dev("mg", DeviceRole.WEAPON, 55f, 0.6f, 0.55f, 0.72f, 1.2f, 0), dev("sniper", DeviceRole.WEAPON, 60f, 0.6f, 0.55f, 0.75f, 1.8f, 1),
                dev("mortar", DeviceRole.WEAPON, 90f, 0.7f, 0.55f, 0.62f, 1.05f, 2), dev("cannon", DeviceRole.WEAPON, 110f, 0.85f, 0.6f, 0.78f, 1.95f, 3),
                dev("rocket", DeviceRole.WEAPON, 80f, 0.8f, 0.6f, 0.7f, 1.4f, 4), dev("laser", DeviceRole.WEAPON, 100f, 0.8f, 0.65f, 0.8f, 1.5f, 5),
            ),
            weapons = listOf(
                wpn("mg", WeaponMode.HITSCAN, 0f), wpn("sniper", WeaponMode.HITSCAN, 0f), wpn("mortar", WeaponMode.BALLISTIC, 2.5f),
                wpn("cannon", WeaponMode.BALLISTIC, 1.2f), wpn("rocket", WeaponMode.BALLISTIC, 2f), wpn("laser", WeaponMode.BEAM, 0f),
            ),
            techs = emptyList(),
        )
    }
}
