package de.bollwerk.engine.sim

/** Baubereich eines Spielers (x-Intervall in m). */
data class ZoneSpec(val owner: Int, val x0: Float, val x1: Float) {
    fun contains(x: Float): Boolean = x >= x0 && x <= x1
}

/** Erzvorkommen an der Geländeoberfläche bei [x]. */
data class OreSpec(val owner: Int, val x: Float)

/** Vorgegebener, verankerter Fundamentknoten. */
data class FoundationSpec(val owner: Int, val x: Float, val y: Float)

/**
 * Startfestung eines Spielers: Bauvorlage [blueprintKey] (in `SimTables.blueprints`) am Ursprung
 * ([originX], `MapSpec.baseY[owner]`), bei [mirror] an der senkrechten Achse gespiegelt.
 */
data class StartFortSpec(val owner: Int, val blueprintKey: String, val originX: Float, val mirror: Boolean)

/**
 * Laufzeitdaten einer Karte für Simulation, Regeln, KI und Renderer (aus `MapDef` gebaut von
 * `de.bollwerk.setup.MapSpecFactory`). Teil des [GameState] (`state.map`) und über `GameView.map` lesbar.
 */
class MapSpec(
    val id: String,
    val width: Float,
    val height: Float,
    val terrain: Terrain,
    val buildZones: List<ZoneSpec>,
    val ores: List<OreSpec>,
    val foundations: List<FoundationSpec>,
    val startForts: List<StartFortSpec>,
    /** Bodenhöhe (y) je Spieler: Bezug für Startfestung und Turbinen-Höhenfaktor `1 + clamp((baseY − y)/24, 0, 0,5)`. */
    val baseY: FloatArray,
    val windMin: Float,
    val windMax: Float,
    /** Außerhalb dieser Grenzen werden Projektile und Trümmer entfernt (Prototyp: x < −40, x > w+40, y > h+20, y < −120). */
    val killMinX: Float,
    val killMaxX: Float,
    val killMinY: Float,
    val killMaxY: Float,
    val playerCount: Int = 2,
) {
    init {
        require(baseY.size >= playerCount) { "baseY needs one entry per player" }
        require(windMin <= windMax) { "windMin > windMax" }
    }

    /** Liegt (x, y) außerhalb der Kill-Grenzen? */
    fun isOutOfBounds(x: Float, y: Float): Boolean = x < killMinX || x > killMaxX || y < killMinY || y > killMaxY

    /** Baubereich-Prüfung für [owner]. */
    fun inBuildZone(owner: Int, x: Float): Boolean {
        for (i in buildZones.indices) {
            val z = buildZones[i]
            if (z.owner == owner && z.contains(x)) return true
        }
        return false
    }

    /** Gibt es eigenes Erz im Umkreis [radius] (horizontal) um [x]? */
    fun hasOreNear(owner: Int, x: Float, radius: Float): Boolean {
        for (i in ores.indices) {
            val o = ores[i]
            val d = o.x - x
            if (o.owner == owner && d <= radius && d >= -radius) return true
        }
        return false
    }

    companion object {
        /** Flache Testkarte ohne Zonen/Startfestung (Bauzonen: linke/rechte Hälfte). */
        fun flat(width: Float = 120f, groundY: Float = 34f, playerCount: Int = 2): MapSpec = MapSpec(
            id = "flat",
            width = width,
            height = groundY + 30f,
            terrain = Terrain.flat(-40f, width + 40f, groundY),
            buildZones = listOf(ZoneSpec(0, 0f, width * 0.5f - 1f), ZoneSpec(1, width * 0.5f + 1f, width)),
            ores = emptyList(),
            foundations = emptyList(),
            startForts = emptyList(),
            baseY = FloatArray(playerCount) { groundY },
            windMin = 0f,
            windMax = 0f,
            killMinX = -40f,
            killMaxX = width + 40f,
            killMinY = -120f,
            killMaxY = groundY + 50f,
            playerCount = playerCount,
        )
    }
}
