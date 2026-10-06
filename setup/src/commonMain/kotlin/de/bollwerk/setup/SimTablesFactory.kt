package de.bollwerk.setup

import de.bollwerk.content.ContentDb
import de.bollwerk.content.DeviceCategory
import de.bollwerk.content.MountRuleDef
import de.bollwerk.content.WeaponModeDef
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BlueprintBeam
import de.bollwerk.engine.sim.BlueprintDevice
import de.bollwerk.engine.sim.BlueprintNode
import de.bollwerk.engine.sim.BlueprintProps
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.MaterialProps
import de.bollwerk.engine.sim.MountRule
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.sim.TechProps
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.sim.WeaponProps

/**
 * Brücke `ContentDb` → [SimTables] (gleiche Index-Reihenfolge). Sekunden werden in Ticks, Grad in
 * Bogenmaß umgerechnet. Jedes neue Content-Feld, das die Simulation braucht, wird hier abgebildet.
 */
object SimTablesFactory {
    fun build(db: ContentDb, config: SimConfig = SimConfig.DEFAULT): SimTables {
        val materials = db.materials.mapIndexed { i, m ->
            MaterialProps(
                key = m.id,
                costPerMeter = m.costPerM,
                hp = m.hp,
                density = m.density,
                stiffness = m.stiffness,
                tensionLimit = m.tensionLimit,
                compressionLimit = if (m.tensionOnly || m.compressionLimit <= 0f) Float.POSITIVE_INFINITY else m.compressionLimit,
                damageFactor = m.damageFactor,
                thickness = m.thickness,
                flammable = m.flammable,
                damping = m.damping,
                tensionOnly = m.tensionOnly,
                restLengthFactor = m.restLengthFactor,
                isDoor = m.isDoor,
                requiredTech = db.materialRequiredTech[i],
            )
        }
        val devices = db.devices.mapIndexed { i, d ->
            DeviceProps(
                key = d.id,
                role = roleOf(d.category),
                costMetal = d.costMetal,
                costEnergy = d.costEnergy,
                hp = d.hp,
                mass = d.mass,
                hitRadius = d.hitRadius,
                mountOffset = d.mountOffset,
                pivotOffset = d.pivotOffset,
                barrelLength = d.barrelLength,
                buildTicks = config.secondsToTicks(d.buildSeconds),
                metalPerSecond = d.metalPerSec,
                energyPerSecond = d.energyPerSec,
                weapon = db.deviceWeapon[i],
                requiredTech = db.deviceRequiredTech[i],
                grantsTech = db.deviceGrantsTech[i],
                unique = d.unique,
                requiresOre = d.requiresOre,
                oreRadius = d.oreRadius,
                mountRule = when (d.mountRule) {
                    MountRuleDef.ANY -> MountRule.ANY
                    MountRuleDef.TOP_ONLY -> MountRule.TOP_ONLY
                },
                minSpacing = d.minSpacing,
            )
        }
        val weapons = db.weapons.map { w ->
            WeaponProps(
                key = w.id,
                mode = when (w.mode) {
                    WeaponModeDef.BALLISTIC -> WeaponMode.BALLISTIC
                    WeaponModeDef.HITSCAN -> WeaponMode.HITSCAN
                    WeaponModeDef.BEAM -> WeaponMode.BEAM
                },
                damage = w.damage,
                splashRadius = w.splashRadius,
                splashDamage = w.splashDamage,
                minRange = w.minRange,
                maxRange = w.maxRange,
                reloadTicks = config.secondsToTicks(w.reloadSeconds),
                shotMetal = w.shotMetal,
                shotEnergy = w.shotEnergy,
                muzzleSpeed = w.muzzleSpeed,
                shotsPerBurst = w.shotsPerBurst,
                burstIntervalTicks = config.secondsToTicks(w.burstIntervalSeconds),
                spreadRad = w.spreadDeg * FloatMath.DEG_TO_RAD,
                directImpulse = w.directImpulse,
                explosionImpulse = w.explosionImpulse,
                recoilImpulse = w.recoilImpulse,
                igniteRadius = w.igniteRadius,
                piercesBeams = w.piercesBeams,
                deviceDamageFactor = w.deviceDamageFactor,
                projectileRadius = w.projectileRadius,
                ttlTicks = config.secondsToTicks(w.lifetimeSeconds),
                beamTicks = config.secondsToTicks(w.beamSeconds),
                defaultAimRad = w.defaultAimDeg * FloatMath.DEG_TO_RAD,
                defaultPower = w.defaultPower,
            )
        }
        val techs = db.techs.mapIndexed { i, t -> TechProps(key = t.id, requires = db.techRequires[i].toList()) }
        val blueprints = db.blueprints.map { bp ->
            BlueprintProps(
                key = bp.id,
                nodes = bp.nodes.map { BlueprintNode(it.x, it.y, it.anchored) },
                beams = bp.beams.map { BlueprintBeam(it.a, it.b, db.materialIndex(it.material)) },
                devices = bp.devices.map { BlueprintDevice(db.deviceIndex(it.type), it.beam, it.t, it.sideNegative) },
                tags = bp.tags,
            )
        }
        return SimTables(materials, devices, weapons, techs, blueprints)
    }

    fun roleOf(c: DeviceCategory): DeviceRole = when (c) {
        DeviceCategory.REACTOR -> DeviceRole.REACTOR
        DeviceCategory.MINE -> DeviceRole.MINE
        DeviceCategory.TURBINE -> DeviceRole.TURBINE
        DeviceCategory.TECH -> DeviceRole.TECH
        DeviceCategory.WEAPON -> DeviceRole.WEAPON
        DeviceCategory.STORAGE -> DeviceRole.STORAGE
        DeviceCategory.OTHER -> DeviceRole.OTHER
    }
}
