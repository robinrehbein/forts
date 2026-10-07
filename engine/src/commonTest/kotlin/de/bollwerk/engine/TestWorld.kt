package de.bollwerk.engine

import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BlueprintBeam
import de.bollwerk.engine.sim.BlueprintDevice
import de.bollwerk.engine.sim.BlueprintNode
import de.bollwerk.engine.sim.BlueprintProps
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.MaterialProps
import de.bollwerk.engine.sim.MountRule
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.sim.TechProps
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.sim.WeaponProps

/** Kleine, content-unabhängige Testwelt. */
object TestWorld {
    const val WOOD = 0
    const val REACTOR = 0
    const val MORTAR = 1

    val tables: SimTables = SimTables(
        materials = listOf(
            MaterialProps(
                key = "wood", costPerMeter = 4f, hp = 100f, density = 15f, stiffness = 6e6f,
                tensionLimit = 0.05f, compressionLimit = 0.035f, damageFactor = 1f, thickness = 0.32f,
                flammable = true, damping = 0.1f, tensionOnly = false, restLengthFactor = 1f, isDoor = false, requiredTech = -1,
            ),
        ),
        devices = listOf(
            device("reactor", DeviceRole.REACTOR, hp = 300f, weapon = -1, unique = true),
            device("mortar", DeviceRole.WEAPON, hp = 90f, weapon = 0, buildTicks = 240, barrel = 1.05f, pivot = 0.62f),
        ),
        weapons = listOf(
            WeaponProps(
                key = "mortar", mode = WeaponMode.BALLISTIC, damage = 0f, splashRadius = 2.5f, splashDamage = 120f,
                minRange = 25f, maxRange = 110f, reloadTicks = 360, shotMetal = 15f, shotEnergy = 30f, muzzleSpeed = 33f,
                shotsPerBurst = 1, burstIntervalTicks = 0, spreadRad = 0f, directImpulse = 0f, explosionImpulse = 1100f,
                recoilImpulse = 260f, igniteRadius = 0f, piercesBeams = 0, deviceDamageFactor = 1f, projectileRadius = 0.14f,
                ttlTicks = 600, beamTicks = 0, defaultAimRad = 52f * FloatMath.DEG_TO_RAD, defaultPower = 0.75f,
            ),
        ),
        techs = listOf(TechProps("workshop", emptyList())),
        blueprints = listOf(
            BlueprintProps(
                key = "mini",
                nodes = listOf(BlueprintNode(0f, 0f, true), BlueprintNode(3f, 0f, true), BlueprintNode(1.5f, -2f, false)),
                beams = listOf(BlueprintBeam(0, 1, WOOD), BlueprintBeam(0, 2, WOOD), BlueprintBeam(1, 2, WOOD)),
                devices = listOf(BlueprintDevice(REACTOR, 0, 0.5f, false), BlueprintDevice(MORTAR, 1, 0.5f, false)),
            ),
        ),
    )

    fun device(
        key: String, role: DeviceRole, hp: Float, weapon: Int, unique: Boolean = false,
        buildTicks: Int = 0, barrel: Float = 0f, pivot: Float = 0f,
    ) = DeviceProps(
        key = key, role = role, costMetal = 100f, costEnergy = 20f, hp = hp, mass = 100f, hitRadius = 1f,
        mountOffset = 0.5f, pivotOffset = pivot, barrelLength = barrel, buildTicks = buildTicks, metalPerSecond = 0f,
        energyPerSecond = 0f, weapon = weapon, requiredTech = -1, grantsTech = -1, unique = unique,
        requiresOre = false, oreRadius = 0f, mountRule = MountRule.ANY, minSpacing = 1.5f,
    )

    fun state(seed: Long = 1L, map: MapSpec = MapSpec.flat()): GameState = GameState(seed, tables, map)

    fun empty(seed: Long = 1L): GameState = GameState(seed, SimTables.EMPTY, MapSpec.flat())
}
