package de.bollwerk.engine.combat

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.physics.PhysicsSystems
import de.bollwerk.engine.physics.PhysicsWorld
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.MaterialProps
import de.bollwerk.engine.sim.MountRule
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.SystemSlot
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.sim.WeaponProps
import de.bollwerk.engine.systems.StandardSystems
import de.bollwerk.engine.view.FxEvent
import kotlin.math.sqrt

/**
 * Tabellen mit den Werten aus `content/…/materials.json`, `devices.json`, `weapons.json` (= Stil-Bibel Abschnitt 1),
 * umgerechnet wie `SimTablesFactory` (Sekunden → Ticks, Grad → Bogenmaß). `:engine` darf nicht von `:content` abhängen.
 */
object CombatTables {
    const val WOOD = 0
    const val METAL = 1
    const val ARMOUR = 2
    const val ROPE = 3
    const val DOOR = 4

    const val REACTOR = 0
    const val MG = 1
    const val SNIPER = 2
    const val MORTAR = 3
    const val CANNON = 4
    const val ROCKET = 5
    const val LASER = 6
    const val TARGET = 7

    const val W_MG = 0
    const val W_SNIPER = 1
    const val W_MORTAR = 2
    const val W_CANNON = 3
    const val W_ROCKET = 4
    const val W_LASER = 5

    private const val D = FloatMath.DEG_TO_RAD

    private fun mat(
        key: String, cost: Float, hp: Float, dens: Float, ea: Float, tens: Float, comp: Float, dmgF: Float,
        thick: Float, flam: Boolean, kd: Float, tensionOnly: Boolean = false, restFactor: Float = 1f, door: Boolean = false,
    ) = MaterialProps(
        key = key, costPerMeter = cost, hp = hp, density = dens, stiffness = ea, tensionLimit = tens,
        compressionLimit = comp, damageFactor = dmgF, thickness = thick, flammable = flam, damping = kd,
        tensionOnly = tensionOnly, restLengthFactor = restFactor, isDoor = door, requiredTech = -1,
    )

    private fun dev(
        key: String, role: DeviceRole, hp: Float, mass: Float, rad: Float, off: Float,
        piv: Float = 0f, barrel: Float = 0f, weapon: Int = -1, unique: Boolean = false,
    ) = DeviceProps(
        key = key, role = role, costMetal = 100f, costEnergy = 0f, hp = hp, mass = mass, hitRadius = rad,
        mountOffset = off, pivotOffset = piv, barrelLength = barrel, buildTicks = 0, metalPerSecond = 0f,
        energyPerSecond = 0f, weapon = weapon, requiredTech = -1, grantsTech = -1, unique = unique,
        requiresOre = false, oreRadius = 0f, mountRule = MountRule.ANY, minSpacing = 1.5f,
    )

    @Suppress("LongParameterList")
    private fun weapon(
        key: String, mode: WeaponMode, damage: Float, splashR: Float = 0f, splashD: Float = 0f, maxRange: Float,
        reloadS: Float, metal: Float = 0f, energy: Float = 0f, speed: Float, shots: Int = 1, burstS: Float = 0f,
        spreadDeg: Float = 0f, directJ: Float = 0f, explJ: Float = 0f, recoilJ: Float = 0f, igniteR: Float = 0f,
        pierce: Int = 0, devF: Float = 1f, radius: Float, lifeS: Float, beamS: Float = 0f, aimDeg: Float, power: Float,
        gravityScale: Float,
    ) = WeaponProps(
        key = key, mode = mode, damage = damage, splashRadius = splashR, splashDamage = splashD, minRange = 0f,
        maxRange = maxRange, reloadTicks = ticks(reloadS), shotMetal = metal, shotEnergy = energy, muzzleSpeed = speed,
        shotsPerBurst = shots, burstIntervalTicks = ticks(burstS), spreadRad = spreadDeg * D, directImpulse = directJ,
        explosionImpulse = explJ, recoilImpulse = recoilJ, igniteRadius = igniteR, piercesBeams = pierce,
        deviceDamageFactor = devF, projectileRadius = radius, ttlTicks = ticks(lifeS), beamTicks = ticks(beamS),
        defaultAimRad = aimDeg * D, defaultPower = power, gravityScale = gravityScale,
    )

    private fun ticks(s: Float): Int = SimConfig.DEFAULT.secondsToTicks(s)

    val tables = SimTables(
        materials = listOf(
            mat("wood", 4f, 100f, 15f, 6e6f, 0.05f, 0.035f, 1f, 0.32f, true, 0.10f),
            mat("metal", 10f, 260f, 50f, 6e7f, 0.02f, 0.016f, 1f, 0.26f, false, 0.12f),
            mat("armour", 18f, 520f, 90f, 8e7f, 0.02f, 0.016f, 0.3f, 0.42f, false, 0.12f),
            mat("rope", 2f, 60f, 4f, 3e6f, 0.08f, 0f, 1f, 0.08f, true, 0.05f, tensionOnly = true, restFactor = 1.04f),
            mat("door", 14f, 160f, 45f, 3e7f, 0.03f, 0.022f, 0.8f, 0.4f, false, 0.12f, door = true),
        ),
        devices = listOf(
            dev("reactor", DeviceRole.REACTOR, 300f, 400f, 1.2f, 1.2f, unique = true),
            dev("mg", DeviceRole.WEAPON, 55f, 45f, 0.6f, 0.55f, 0.72f, 1.2f, W_MG),
            dev("sniper", DeviceRole.WEAPON, 60f, 70f, 0.6f, 0.55f, 0.75f, 1.8f, W_SNIPER),
            dev("mortar", DeviceRole.WEAPON, 90f, 110f, 0.7f, 0.55f, 0.62f, 1.05f, W_MORTAR),
            dev("cannon", DeviceRole.WEAPON, 110f, 160f, 0.85f, 0.6f, 0.78f, 1.95f, W_CANNON),
            dev("rocket", DeviceRole.WEAPON, 80f, 120f, 0.8f, 0.6f, 0.7f, 1.4f, W_ROCKET),
            dev("laser", DeviceRole.WEAPON, 100f, 200f, 0.8f, 0.65f, 0.8f, 1.5f, W_LASER),
            dev("target", DeviceRole.OTHER, 1000f, 100f, 1f, 1f),
        ),
        weapons = listOf(
            weapon("mg", WeaponMode.HITSCAN, 6f, maxRange = 60f, reloadS = 3f, energy = 8f, speed = 120f, shots = 8,
                burstS = 0.0667f, spreadDeg = 1.2f, directJ = 30f, radius = 0.05f, lifeS = 0f, aimDeg = 0f, power = 1f, gravityScale = 0f),
            weapon("sniper", WeaponMode.HITSCAN, 40f, maxRange = 120f, reloadS = 4f, energy = 15f, speed = 240f, directJ = 60f,
                pierce = 1, devF = 3f, radius = 0.04f, lifeS = 0f, aimDeg = 5f, power = 1f, gravityScale = 0f),
            weapon("mortar", WeaponMode.BALLISTIC, 0f, 2.5f, 120f, maxRange = 110f, reloadS = 6f, metal = 15f, energy = 30f,
                speed = 33f, explJ = 1100f, recoilJ = 260f, radius = 0.14f, lifeS = 10f, aimDeg = 52f, power = 0.78f, gravityScale = 1f),
            weapon("cannon", WeaponMode.BALLISTIC, 90f, 1.2f, 30f, maxRange = 100f, reloadS = 10f, metal = 30f, energy = 60f,
                speed = 46f, directJ = 2200f, explJ = 650f, recoilJ = 520f, radius = 0.1f, lifeS = 12f, aimDeg = 13f, power = 1f, gravityScale = 1f),
            weapon("rocket", WeaponMode.BALLISTIC, 0f, 2f, 40f, maxRange = 100f, reloadS = 12f, metal = 20f, energy = 40f,
                speed = 38f, explJ = 300f, recoilJ = 200f, igniteR = 2f, radius = 0.12f, lifeS = 16f, aimDeg = 40f, power = 0.8f,
                gravityScale = 0.6f),
            weapon("laser", WeaponMode.BEAM, 80f, maxRange = 140f, reloadS = 20f, energy = 150f, speed = 0f, radius = 0.08f,
                lifeS = 0f, beamS = 2f, aimDeg = 5f, power = 1f, gravityScale = 0f),
        ),
        techs = emptyList(),
    )
}

/**
 * Testaufbau für den Kampf: Zustand + Physik- und Kampf-Systeme (wählbar) + einfacher Command-Anwender im Slot
 * COMMANDS (nur `SetAim`/`Fire`, ohne Prüfungen – das echte Command-System ist WP3).
 */
class CombatRig(
    seed: Long = 11L,
    map: MapSpec = MapSpec.flat(),
    config: SimConfig = SimConfig.DEFAULT,
    slots: Set<SystemSlot> = ALL,
    val world: CombatWorld = CombatWorld(),
    /** true: vollständige `StandardSystems`-Liste (Physik + Regeln + Kampf) statt der Auswahl [slots]. */
    standard: Boolean = false,
) {
    val state = GameState(seed, CombatTables.tables, map, config)
    val ctx = StepContext()
    val fx = ArrayList<FxEvent>()
    private val pending = ArrayList<Command>()

    private val applier = SimSystem { s, c ->
        for (k in c.commands.indices) {
            when (val cmd = c.commands[k]) {
                is Command.SetAim -> {
                    val id = s.devices.resolve(cmd.deviceRef)
                    if (id >= 0) { s.devices.aimAngle[id] = cmd.angle; s.devices.power[id] = cmd.power }
                }
                is Command.Fire -> {
                    val id = s.devices.resolve(cmd.deviceRef)
                    if (id >= 0) s.devices.flags[id] = s.devices.flags[id] or DeviceFlags.FIRE_REQUESTED
                }
                else -> Unit
            }
        }
    }

    val stepper: SimStepper = if (standard) {
        StandardSystems.stepper()
    } else {
        val all = PhysicsSystems.all(PhysicsWorld()) + CombatSystems.all(world) + (SystemSlot.COMMANDS to applier)
        SimStepper(all.filterKeys { it in slots || it == SystemSlot.COMMANDS })
    }

    init {
        for (p in state.players) { p.metal = 1000f; p.energy = 400f }
    }

    fun node(x: Float, y: Float, anchored: Boolean = true, owner: Int = 0): Int {
        state.topologyDirty = true
        return state.nodes.alloc(x, y, owner, anchored)
    }

    fun beam(a: Int, b: Int, material: Int, owner: Int = 0): Int {
        val n = state.nodes
        val dx = n.x[b] - n.x[a]; val dy = n.y[b] - n.y[a]
        val mat = state.tables.materials[material]
        state.topologyDirty = true
        val id = state.beams.alloc(a, b, material, sqrt(dx * dx + dy * dy) * mat.restLengthFactor, mat.hp, owner)
        state.nodes.rebuildAdjacency(state.beams)
        return id
    }

    /** Balken zwischen zwei neuen Knoten. */
    fun beamAt(x0: Float, y0: Float, x1: Float, y1: Float, material: Int, anchored: Boolean = true, owner: Int = 0): Int =
        beam(node(x0, y0, anchored, owner), node(x1, y1, anchored, owner), material, owner)

    /** Gerät auf Balken [beam]; standardmäßig auf der Oberseite eines von links nach rechts laufenden Balkens. */
    fun device(type: Int, beam: Int, t: Float = 0.5f, owner: Int = 0, aim: Float = 0f, power: Float = 1f, sideNegative: Boolean = true): Int {
        state.topologyDirty = true
        val id = state.devices.alloc(type, beam, t, state.tables.devices[type].hp, owner, 0, sideNegative, aim, power)
        if (state.tables.devices[type].role == DeviceRole.REACTOR) state.players[owner].reactorDeviceId = id
        return id
    }

    /** Waffe auf einer verankerten Metall-Plattform bei ([x], [y]). */
    fun weaponOnPlatform(type: Int, x: Float, y: Float, aim: Float, power: Float = 1f, owner: Int = 0, anchored: Boolean = true): Int {
        val b = beamAt(x - 1.5f, y, x + 1.5f, y, CombatTables.METAL, anchored, owner)
        return device(type, b, 0.5f, owner, aim, power)
    }

    /** Aktuelle Geräte-Lage (Mündung usw.). */
    fun geometry(device: Int): FloatArray {
        val out = FloatArray(DeviceGeometry.SIZE)
        DeviceGeometry.mount(state, device, out)
        return out
    }

    fun command(c: Command) { pending += c }

    /** Fordert einen Schuss für den nächsten Tick an (wie ein angewendetes `Command.Fire`). */
    fun fire(device: Int) = command(Command.Fire(state.tick, state.devices.ownerOf[device], state.devices.ref(device)))

    fun tick() {
        ctx.beginTick(state.tick)
        ctx.commands.addAll(pending)
        pending.clear()
        stepper.step(state, ctx)
        fx.addAll(ctx.fx)
    }

    fun run(ticks: Int) { for (k in 0 until ticks) tick() }

    /** Läuft, bis [cond] wahr ist (höchstens [max] Ticks). @return benötigte Ticks oder −1. */
    fun runUntil(max: Int, cond: () -> Boolean): Int {
        for (k in 0 until max) {
            tick()
            if (cond()) return k + 1
        }
        return -1
    }

    inline fun <reified T : FxEvent> events(): List<T> = fx.filterIsInstance<T>()

    companion object {
        val ALL: Set<SystemSlot> = setOf(
            SystemSlot.DEVICES, SystemSlot.WEAPONS, SystemSlot.PROJECTILES, SystemSlot.PHYSICS, SystemSlot.STRAIN_DAMAGE,
            SystemSlot.FIRE, SystemSlot.REPAIR, SystemSlot.DOORS, SystemSlot.TOPOLOGY, SystemSlot.DEBRIS, SystemSlot.REACTORS,
        )
        /** Nur Kampf, ohne Physik (Positionen bleiben stehen). */
        val COMBAT_ONLY: Set<SystemSlot> = setOf(
            SystemSlot.DEVICES, SystemSlot.WEAPONS, SystemSlot.PROJECTILES, SystemSlot.FIRE, SystemSlot.REPAIR, SystemSlot.DOORS,
            SystemSlot.REACTORS,
        )
    }
}

/** Direkte Treffer (ohne Explosions-Blitze `splash = true`). */
fun List<FxEvent.Hit>.direct(): List<FxEvent.Hit> = filter { !it.splash }
