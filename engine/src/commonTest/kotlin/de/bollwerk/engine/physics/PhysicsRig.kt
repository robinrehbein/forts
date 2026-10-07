package de.bollwerk.engine.physics

import de.bollwerk.engine.sim.BeamFlags
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
import de.bollwerk.engine.view.FxEvent
import kotlin.math.sqrt

/** Materialien/Geräte mit Prototyp-Werten (`MAT`) für die Physik-Tests. */
object PhysicsTables {
    const val WOOD = 0
    const val METAL = 1
    const val ROPE = 2
    /** Holz mit invertierter axialer Dämpfung (simuliert den alten Vorzeichenfehler). */
    const val WOOD_INVERTED_DAMPING = 3
    /** Metall mit invertierter axialer Dämpfung. */
    const val METAL_INVERTED_DAMPING = 4

    const val LOAD = 0
    const val ANVIL = 1
    const val CRATE = 2
    const val BOB = 3
    /** Geräte mit Content-Massen (devices.json / Stil-Bibel). */
    const val REACTOR = 4
    const val CANNON = 5
    const val MORTAR = 6
    const val FACTORY = 7
    /** Lager-Ballast (Metallvorrat o. Ä.), um ein Fachwerk in den Bereich 20–50 % der Grenzdehnung zu bringen. */
    const val BALLAST = 8

    private fun mat(
        key: String, density: Float, ea: Float, tens: Float, comp: Float, hp: Float, dmgF: Float,
        thick: Float, flam: Boolean, kd: Float, tensionOnly: Boolean = false, restFactor: Float = 1f,
    ) = MaterialProps(
        key = key, costPerMeter = 1f, hp = hp, density = density, stiffness = ea, tensionLimit = tens,
        compressionLimit = comp, damageFactor = dmgF, thickness = thick, flammable = flam, damping = kd,
        tensionOnly = tensionOnly, restLengthFactor = restFactor, isDoor = false, requiredTech = -1,
    )

    private fun dev(key: String, mass: Float) = DeviceProps(
        key = key, role = DeviceRole.OTHER, costMetal = 0f, costEnergy = 0f, hp = 100f, mass = mass, hitRadius = 1f,
        mountOffset = 0.5f, pivotOffset = 0f, barrelLength = 0f, buildTicks = 0, metalPerSecond = 0f,
        energyPerSecond = 0f, weapon = -1, requiredTech = -1, grantsTech = -1, unique = false,
        requiresOre = false, oreRadius = 0f, mountRule = MountRule.ANY, minSpacing = 0f,
    )

    val tables = SimTables(
        materials = listOf(
            mat("wood", 15f, 6.0e6f, 0.05f, 0.035f, 100f, 1f, 0.32f, true, 0.10f),
            mat("metal", 50f, 6.0e7f, 0.020f, 0.016f, 260f, 1f, 0.26f, false, 0.12f),
            mat("rope", 4f, 3.0e6f, 0.080f, Float.POSITIVE_INFINITY, 60f, 1f, 0.08f, true, 0.05f, tensionOnly = true, restFactor = 1.04f),
            mat("wood-inverted", 15f, 6.0e6f, 0.05f, 0.035f, 100f, 1f, 0.32f, true, -0.10f),
            mat("metal-inverted", 50f, 6.0e7f, 0.020f, 0.016f, 260f, 1f, 0.26f, false, -0.12f),
        ),
        devices = listOf(
            dev("load", 300f), dev("anvil", 45000f), dev("crate", 100f), dev("bob", 3000f),
            dev("reactor", 400f), dev("cannon", 160f), dev("mortar", 110f), dev("factory", 320f), dev("ballast", 12000f),
        ),
        weapons = emptyList(),
        techs = emptyList(),
    )
}

/**
 * Testaufbau: Zustand + Physik-Stepper + gesammelte Fx.
 *
 * Eingriffe, die im Spiel **innerhalb** eines Ticks passieren (Brüche durch Projektile/Explosionen, WP4), laufen
 * über [inTick]: Die Aktion wird im Slot [SystemSlot.PROJECTILES] ausgeführt, also nach `state.beginTick()` und vor
 * PHYSICS – neue Slots tragen damit wie im Spiel `allocTick = tick` und gelten in diesem Tick als `isNew`.
 *
 * @param slots welche WP2-Systeme laufen (Standard alle vier; z. B. ohne PHYSICS, um Trümmer-Regeln isoliert zu testen).
 */
class PhysicsRig(
    config: SimConfig = SimConfig.DEFAULT,
    seed: Long = 7L,
    map: MapSpec = MapSpec.flat(),
    private val slots: Set<SystemSlot> = setOf(SystemSlot.PHYSICS, SystemSlot.STRAIN_DAMAGE, SystemSlot.TOPOLOGY, SystemSlot.DEBRIS),
) {
    val state = GameState(seed, PhysicsTables.tables, map, config)
    var world = PhysicsWorld(); private set
    var systems: Map<SystemSlot, SimSystem> = PhysicsSystems.all(world); private set
    private val pending = ArrayList<(StepContext) -> Unit>()
    private val inTickSystem = SimSystem { _, c ->
        for (k in 0 until pending.size) pending[k](c)
        pending.clear()
    }
    var stepper = makeStepper(); private set
    val strain: StrainDamageSystem get() = systems.getValue(SystemSlot.STRAIN_DAMAGE) as StrainDamageSystem
    val ctx = StepContext()
    val fx = ArrayList<FxEvent>()

    private fun makeStepper(): SimStepper =
        SimStepper(systems.filterKeys { it in slots } + (SystemSlot.PROJECTILES to inTickSystem))

    /** Führt [action] im nächsten Tick im Slot PROJECTILES aus (wie ein Treffer im Spiel) und simuliert diesen Tick. */
    fun inTick(action: (StepContext) -> Unit) {
        pending += action
        tick()
    }

    /**
     * Simuliert einen Restore/Rollback: frische Systeme mit leerem [PhysicsWorld], alle DERIVED-Daten verworfen,
     * danach `GameState.rebuildDerived()` (Vertrag nach Laden/Rollback).
     */
    fun restoreDerived() {
        val n = state.nodes; val b = state.beams
        for (i in 0 until n.size) { n.mass[i] = 0f; n.component[i] = 0 }
        for (j in 0 until b.size) { b.solveOrder[j] = 0; b.lambda[j] = 0f; b.strainOf[j] = 0f; b.creaking[j] = false }
        b.solveCount = 0
        b.solveCacheOwner = null
        world = PhysicsWorld()
        systems = PhysicsSystems.all(world)
        stepper = makeStepper()
        state.rebuildDerived()
    }

    fun node(x: Float, y: Float, anchored: Boolean = false, owner: Int = 0): Int {
        state.topologyDirty = true
        return state.nodes.alloc(x, y, owner, anchored)
    }

    fun beam(a: Int, b: Int, material: Int = PhysicsTables.WOOD, owner: Int = 0): Int {
        val n = state.nodes
        val dx = n.x[b] - n.x[a]; val dy = n.y[b] - n.y[a]
        val mat = state.tables.materials[material]
        state.topologyDirty = true
        return state.beams.alloc(a, b, material, sqrt(dx * dx + dy * dy) * mat.restLengthFactor, mat.hp, owner)
    }

    fun device(type: Int, beam: Int, t: Float = 0.5f, owner: Int = 0): Int {
        state.topologyDirty = true
        return state.devices.alloc(type, beam, t, state.tables.devices[type].hp, owner, 0, false, 0f, 1f)
    }

    fun tick() {
        ctx.beginTick(state.tick)
        stepper.step(state, ctx)
        fx.addAll(ctx.fx)
    }

    /** Fx des zuletzt simulierten Ticks. */
    fun lastFx(): List<FxEvent> = ctx.fx

    fun run(ticks: Int, each: (() -> Unit)? = null) {
        for (k in 0 until ticks) {
            tick()
            each?.invoke()
        }
    }

    fun breaks(): Int = fx.count { it is FxEvent.BeamBroken }

    /** Größtes |Dehnung|/Grenze aller lebenden Nicht-Trümmer-Balken (aus `strainOf`). */
    fun maxStrainRatio(): Float {
        var m = 0f
        val b = state.beams
        for (j in 0 until b.size) {
            if (!b.isAlive(j) || (b.flags[j] and BeamFlags.DEBRIS) != 0) continue
            val mat = state.tables.materials[b.materialOf[j]]
            val s = b.strainOf[j]
            val lim = if (s >= 0f) mat.tensionLimit else mat.compressionLimit
            if (lim == Float.POSITIVE_INFINITY || lim <= 0f || (s < 0f && mat.tensionOnly)) continue
            val r = (if (s < 0f) -s else s) / lim
            if (r > m) m = r
        }
        return m
    }

    fun len(beam: Int): Float {
        val n = state.nodes; val b = state.beams
        val dx = n.x[b.b[beam]] - n.x[b.a[beam]]; val dy = n.y[b.b[beam]] - n.y[b.a[beam]]
        return sqrt(dx * dx + dy * dy)
    }
}
