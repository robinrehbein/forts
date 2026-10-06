package de.bollwerk.setup

import de.bollwerk.content.ContentDb
import de.bollwerk.content.MapDef
import de.bollwerk.engine.sim.FoundationSpec
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.OreSpec
import de.bollwerk.engine.sim.StartFortSpec
import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.sim.ZoneSpec

/** Brücke `MapDef` → [MapSpec] (Gelände abgetastet mit 0,5 m wie `terrH` im Prototyp). */
object MapSpecFactory {
    /** Prototyp-Standardränder der Kill-Grenzen. */
    const val KILL_MARGIN_X: Float = 40f
    const val KILL_MARGIN_BOTTOM: Float = 20f
    const val KILL_TOP_Y: Float = -120f

    fun build(db: ContentDb, mapId: String): MapSpec = build(db.map(mapId))

    fun build(m: MapDef, terrainStep: Float = 0.5f): MapSpec {
        val xs = FloatArray(m.terrain.size) { m.terrain[it].x }
        val ys = FloatArray(m.terrain.size) { m.terrain[it].y }
        val terrain = Terrain.fromPolyline(xs, ys, terrainStep)
        val zones = m.buildZones.map { ZoneSpec(it.owner, it.x0, it.x1) }
        val baseY = FloatArray(m.playerCount) { p ->
            m.baseY.getOrNull(p) ?: run {
                val z = zones.firstOrNull { it.owner == p }
                terrain.heightAt(if (z != null) (z.x0 + z.x1) * 0.5f else m.width * 0.5f)
            }
        }
        val b = m.bounds
        return MapSpec(
            id = m.id,
            width = m.width,
            height = m.height,
            terrain = terrain,
            buildZones = zones,
            ores = m.ores.map { OreSpec(it.owner, it.x) },
            foundations = m.foundations.map { FoundationSpec(it.owner, it.x, it.y) },
            startForts = m.startForts.map { StartFortSpec(it.owner, it.blueprint, it.originX, it.mirror) },
            baseY = baseY,
            windMin = m.windMin,
            windMax = m.windMax,
            killMinX = b?.minX ?: -KILL_MARGIN_X,
            killMaxX = b?.maxX ?: (m.width + KILL_MARGIN_X),
            killMinY = b?.minY ?: KILL_TOP_Y,
            killMaxY = b?.maxY ?: (m.height + KILL_MARGIN_BOTTOM),
            playerCount = m.playerCount,
        )
    }
}
