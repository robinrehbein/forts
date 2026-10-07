package de.bollwerk.app.game

import de.bollwerk.content.ContentDb
import de.bollwerk.content.DeviceCategory
import de.bollwerk.content.WeaponModeDef

/** Material der Toolbar (Index = Content-/SimTables-Index). */
data class MaterialItem(val index: Int, val id: String, val costPerM: Float, val requiredTech: Int, val isDoor: Boolean)

/** Gerät der Toolbar (Mine, Turbine, Techgebäude, Waffen). */
data class DeviceItem(
    val index: Int,
    val id: String,
    val category: DeviceCategory,
    val costMetal: Float,
    val costEnergy: Float,
    val buildSeconds: Float,
    val requiredTech: Int,
    val grantsTech: Int,
)

/** Waffe (= Gerät mit Waffe) mit den Werten für Waffenkarte und Techbaum (Stil-Bibel §1 Waffen). */
data class WeaponItem(
    val device: DeviceItem,
    val weaponIndex: Int,
    val weaponId: String,
    val mode: WeaponModeDef,
    val damage: Float,
    val splashRadius: Float,
    val splashDamage: Float,
    val maxRange: Float,
    val reloadSeconds: Float,
    val shotMetal: Float,
    val shotEnergy: Float,
    val shotsPerBurst: Int,
    val beamSeconds: Float,
    val igniteRadius: Float,
)

/** Techgebäude (Werkstatt, Waffenkammer, Upgrade-Zentrum, Fabrik) mit Voraussetzungen und Freischaltungen. */
data class TechBuildingItem(
    val device: DeviceItem,
    val techIndex: Int,
    val techId: String,
    /** Voraussetzungen des Gebäudes (Tech-Indizes): `requiresTech` des Gebäudes. */
    val requires: List<Int>,
    /** Freigeschaltete Waffen (Indizes in [GameCatalog.weapons]). */
    val unlockWeapons: List<Int>,
    /** Freigeschaltete Materialien (Indizes in [GameCatalog.materials]). */
    val unlockMaterials: List<Int>,
)

/**
 * Was die HUD-Schicht aus dem Content braucht (Namen über die Content-ID, Werte, Sperren), einmal je Partie aus der
 * [ContentDb] gebaut. Reine Daten; Reihenfolgen folgen dem Content, außer [materials] (feste Toolbar-Reihenfolge,
 * [MATERIAL_ORDER]).
 */
class GameCatalog(
    /** Materialien in Toolbar-Reihenfolge (Mockup 3: HOLZ · METALL · PANZER · TÜR · SEIL); `index` bleibt der Content-Index. */
    val materials: List<MaterialItem>,
    /** Wirtschaftsgeräte der Toolbar (Mine, Turbine). */
    val economy: List<DeviceItem>,
    val weapons: List<WeaponItem>,
    val techBuildings: List<TechBuildingItem>,
    /** Alle Geräte nach Index (für HUD-Waffen und Ghost-Kosten). */
    val devices: List<DeviceItem>,
    val materialIds: List<String>,
    val weaponIds: List<String>,
) {
    /** Material mit dem Content-Index [materialIndex] (nicht die Position in [materials]). */
    fun materialByIndex(materialIndex: Int): MaterialItem? {
        for (m in materials) if (m.index == materialIndex) return m
        return null
    }

    fun weaponByDevice(deviceIndex: Int): WeaponItem? {
        for (w in weapons) if (w.device.index == deviceIndex) return w
        return null
    }

    fun techBuildingByDevice(deviceIndex: Int): TechBuildingItem? {
        for (t in techBuildings) if (t.device.index == deviceIndex) return t
        return null
    }

    fun techBuildingByTech(techIndex: Int): TechBuildingItem? {
        for (t in techBuildings) if (t.techIndex == techIndex) return t
        return null
    }

    companion object {
        /** Toolbar-Reihenfolge der Materialien (Mockup 3); unbekannte IDs folgen dahinter in Content-Reihenfolge. */
        val MATERIAL_ORDER: List<String> = listOf("wood", "metal", "armour", "door", "rope")

        /** Sortiert nach [MATERIAL_ORDER] (stabil: gleiche Rangstufe behält die Content-Reihenfolge). */
        fun inToolbarOrder(materials: List<MaterialItem>): List<MaterialItem> = materials.sortedBy { m ->
            val i = MATERIAL_ORDER.indexOf(m.id)
            if (i >= 0) i else MATERIAL_ORDER.size
        }

        fun from(db: ContentDb): GameCatalog {
            val byIndex = db.materials.mapIndexed { i, m ->
                MaterialItem(i, m.id, m.costPerM, db.materialRequiredTech[i], m.isDoor)
            }
            val materials = inToolbarOrder(byIndex)
            val devices = db.devices.mapIndexed { i, d ->
                DeviceItem(
                    index = i, id = d.id, category = d.category, costMetal = d.costMetal, costEnergy = d.costEnergy,
                    buildSeconds = d.buildSeconds, requiredTech = db.deviceRequiredTech[i], grantsTech = db.deviceGrantsTech[i],
                )
            }
            val economy = devices.filter { it.category == DeviceCategory.MINE || it.category == DeviceCategory.TURBINE }
            val weapons = devices.filter { db.deviceWeapon[it.index] >= 0 }.map { d ->
                val wi = db.deviceWeapon[d.index]
                val w = db.weapons[wi]
                WeaponItem(
                    device = d, weaponIndex = wi, weaponId = w.id, mode = w.mode, damage = w.damage,
                    splashRadius = w.splashRadius, splashDamage = w.splashDamage, maxRange = w.maxRange,
                    reloadSeconds = w.reloadSeconds, shotMetal = w.shotMetal, shotEnergy = w.shotEnergy,
                    shotsPerBurst = w.shotsPerBurst, beamSeconds = w.beamSeconds, igniteRadius = w.igniteRadius,
                )
            }
            val techBuildings = devices.filter { it.category == DeviceCategory.TECH && it.grantsTech >= 0 }.map { d ->
                val tech = db.techs[d.grantsTech]
                TechBuildingItem(
                    device = d,
                    techIndex = d.grantsTech,
                    techId = tech.id,
                    requires = if (d.requiredTech >= 0) listOf(d.requiredTech) else emptyList(),
                    unlockWeapons = tech.unlocks.mapNotNull { u -> weapons.indexOfFirst { it.device.id == u || it.weaponId == u }.takeIf { it >= 0 } },
                    unlockMaterials = tech.unlocks.mapNotNull { u -> materials.indexOfFirst { it.id == u }.takeIf { it >= 0 } },
                )
            }
            return GameCatalog(
                materials = materials,
                economy = economy,
                weapons = weapons,
                techBuildings = techBuildings,
                devices = devices,
                materialIds = db.materials.map { it.id },
                weaponIds = db.weapons.map { it.id },
            )
        }
    }
}
