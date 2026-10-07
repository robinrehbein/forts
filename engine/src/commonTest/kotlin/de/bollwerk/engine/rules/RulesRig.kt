package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BlueprintBeam
import de.bollwerk.engine.sim.BlueprintDevice
import de.bollwerk.engine.sim.BlueprintNode
import de.bollwerk.engine.sim.BlueprintProps
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.MatchFactory
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.MaterialProps
import de.bollwerk.engine.sim.MountRule
import de.bollwerk.engine.sim.OreSpec
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.sim.StartFortSpec
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.TechProps
import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.sim.WeaponProps
import de.bollwerk.engine.sim.ZoneSpec
import de.bollwerk.engine.systems.StandardSystems
import de.bollwerk.engine.view.FxEvent
import kotlin.test.assertEquals

/**
 * Content-Tabellen mit den Zahlen der Stil-Bibel §1 (Kosten, TP, Wirtschaft, Bauzeiten, Waffenkosten) für die Regel-Tests.
 * Indizes sind Konstanten, damit Tests lesbar bleiben.
 */
object RuleTables {
    const val WOOD = 0
    const val METAL = 1
    const val ARMOUR = 2
    const val ROPE = 3
    const val DOOR = 4

    const val REACTOR = 0
    const val MINE = 1
    const val TURBINE = 2
    const val WORKSHOP = 3
    const val ARMOURY = 4
    const val UPGRADE = 5
    const val FACTORY = 6
    const val MORTAR = 7
    const val MG = 8

    const val T_WORKSHOP = 0
    const val T_ARMOURY = 1
    const val T_UPGRADE = 2
    const val T_FACTORY = 3

    const val W_MORTAR = 0
    const val W_MG = 1

    private fun mat(
        key: String, cost: Float, hp: Float, density: Float, thick: Float, flam: Boolean, dmg: Float = 1f,
        tensionOnly: Boolean = false, restFactor: Float = 1f, door: Boolean = false, tech: Int = -1,
    ) = MaterialProps(
        key = key, costPerMeter = cost, hp = hp, density = density, stiffness = 6e6f, tensionLimit = 0.05f,
        compressionLimit = if (tensionOnly) Float.POSITIVE_INFINITY else 0.035f, damageFactor = dmg, thickness = thick,
        flammable = flam, damping = 0.1f, tensionOnly = tensionOnly, restLengthFactor = restFactor, isDoor = door, requiredTech = tech,
    )

    private fun dev(
        key: String, role: DeviceRole, costM: Float, costE: Float, hp: Float, buildSeconds: Int,
        metal: Float = 0f, energy: Float = 0f, weapon: Int = -1, requires: Int = -1, grants: Int = -1,
        unique: Boolean = false, ore: Boolean = false, mount: MountRule = MountRule.ANY,
        barrel: Float = 0f, pivot: Float = 0f,
    ) = DeviceProps(
        key = key, role = role, costMetal = costM, costEnergy = costE, hp = hp, mass = 100f, hitRadius = 1f,
        mountOffset = 0.55f, pivotOffset = pivot, barrelLength = barrel, buildTicks = buildSeconds * 60,
        metalPerSecond = metal, energyPerSecond = energy, weapon = weapon, requiredTech = requires, grantsTech = grants,
        unique = unique, requiresOre = ore, oreRadius = if (ore) 2.4f else 0f, mountRule = mount, minSpacing = 1.5f,
    )

    private fun weapon(key: String, reload: Int, shotM: Float, shotE: Float, minAim: Float, maxAim: Float) = WeaponProps(
        key = key, mode = WeaponMode.BALLISTIC, damage = 0f, splashRadius = 2.5f, splashDamage = 120f, minRange = 25f,
        maxRange = 110f, reloadTicks = reload, shotMetal = shotM, shotEnergy = shotE, muzzleSpeed = 33f, shotsPerBurst = 1,
        burstIntervalTicks = 0, spreadRad = 0f, directImpulse = 0f, explosionImpulse = 1100f, recoilImpulse = 260f,
        igniteRadius = 0f, piercesBeams = 0, deviceDamageFactor = 1f, projectileRadius = 0.14f, ttlTicks = 600, beamTicks = 0,
        defaultAimRad = 52f * FloatMath.DEG_TO_RAD, defaultPower = 0.78f, minAimRad = minAim, maxAimRad = maxAim,
    )

    val tables: SimTables = SimTables(
        materials = listOf(
            mat("wood", 4f, 100f, 15f, 0.32f, true),
            mat("metal", 10f, 260f, 50f, 0.26f, false),
            mat("armour", 18f, 520f, 90f, 0.42f, false, dmg = 0.3f, tech = T_UPGRADE),
            mat("rope", 2f, 60f, 4f, 0.08f, true, tensionOnly = true, restFactor = 1.04f),
            mat("door", 14f, 160f, 45f, 0.4f, false, dmg = 0.8f, door = true),
        ),
        devices = listOf(
            dev("reactor", DeviceRole.REACTOR, 0f, 0f, 300f, 0, energy = 2f, unique = true),
            dev("mine", DeviceRole.MINE, 120f, 0f, 90f, 5, metal = 6f, ore = true),
            dev("turbine", DeviceRole.TURBINE, 80f, 0f, 60f, 4, energy = 6f, mount = MountRule.TOP_ONLY),
            dev("workshop", DeviceRole.TECH, 120f, 40f, 120f, 30, grants = T_WORKSHOP),
            dev("armoury", DeviceRole.TECH, 200f, 80f, 140f, 45, grants = T_ARMOURY),
            dev("upgrade_center", DeviceRole.TECH, 260f, 120f, 150f, 60, grants = T_UPGRADE),
            dev("factory", DeviceRole.TECH, 480f, 240f, 220f, 90, requires = T_UPGRADE, grants = T_FACTORY),
            dev("mortar", DeviceRole.WEAPON, 150f, 40f, 90f, 4, weapon = W_MORTAR, requires = T_WORKSHOP, barrel = 1.05f, pivot = 0.62f),
            dev("mg", DeviceRole.WEAPON, 90f, 20f, 55f, 3, weapon = W_MG, requires = T_ARMOURY, barrel = 1.2f, pivot = 0.72f),
        ),
        weapons = listOf(
            // Mörser 15 ⚙ · 30 ⚡, 6 s; MG 8 ⚡, 3 s (Stil-Bibel)
            weapon("mortar", 360, 15f, 30f, -0.17453292f, 1.4835299f),
            weapon("mg", 180, 0f, 8f, -0.5235988f, 0.9599311f),
        ),
        techs = listOf(
            TechProps("workshop", emptyList()), TechProps("armoury", emptyList()),
            TechProps("upgrade_center", emptyList()), TechProps("factory", listOf(T_UPGRADE)),
        ),
        blueprints = listOf(
            // Fundament n0..n2 auf dem Boden, Spitze n3; Reaktor oben auf Balken 0
            BlueprintProps(
                key = "fort",
                nodes = listOf(BlueprintNode(0f, 0f, true), BlueprintNode(3f, 0f, true), BlueprintNode(6f, 0f, true), BlueprintNode(3f, -3f, false)),
                beams = listOf(
                    BlueprintBeam(0, 1, WOOD), BlueprintBeam(1, 2, WOOD), BlueprintBeam(0, 3, WOOD),
                    BlueprintBeam(1, 3, WOOD), BlueprintBeam(2, 3, WOOD),
                ),
                devices = listOf(BlueprintDevice(REACTOR, 0, 0.5f, true)),
            ),
        ),
    )

    /** Spielfeld 120 m, Boden y = 34, Bauzonen 0..39 / 81..120, Erz bei 25,5 und 31,5 (links), 94,5 und 88,5 (rechts). */
    fun map(
        terrain: Terrain = Terrain.flat(-40f, 160f, 34f),
        ores: List<OreSpec> = listOf(OreSpec(0, 25.5f), OreSpec(0, 31.5f), OreSpec(1, 94.5f), OreSpec(1, 88.5f)),
    ): MapSpec = MapSpec(
        id = "rules", width = 120f, height = 64f, terrain = terrain,
        buildZones = listOf(ZoneSpec(0, 0f, 39f), ZoneSpec(1, 81f, 120f)),
        ores = ores,
        foundations = emptyList(),
        startForts = listOf(StartFortSpec(0, "fort", 20f, false), StartFortSpec(1, "fort", 100f, true)),
        baseY = floatArrayOf(34f, 34f), windMin = -4f, windMax = 4f,
        killMinX = -40f, killMaxX = 160f, killMinY = -120f, killMaxY = 84f,
    )
}

/**
 * Testaufbau für Regeln: Zustand aus `MatchFactory` (Fort mit Reaktor je Spieler) + Stepper. Commands laufen direkt durch
 * das Command-System ([send]); damit sieht man je Command das Ergebnis, ohne die Session-Vorprüfung.
 *
 * Fort Spieler 0 (Ursprung x = 20): Fundamente (20,34) (23,34) (26,34), Spitze (23,31); Balken 0: (20→23), 1: (23→26),
 * 2: (20→Spitze), 3: (23→Spitze), 4: (26→Spitze); Reaktor oben auf Balken 0 bei x = 21,5.
 * Spieler 1 gespiegelt um x = 100.
 */
class RulesRig(
    turns: Boolean = false,
    turnTicks: Int = 0,
    metal: Float = 400f,
    energy: Float = 200f,
    config: SimConfig = SimConfig.DEFAULT,
    physics: Boolean = false,
    map: MapSpec = RuleTables.map(),
    seed: Long = 7L,
) {
    val setup = MatchSetup(
        seed, "rules", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.HUMAN)),
        if (turns) TurnMode.TURNS else TurnMode.REALTIME, turnTicks, metal, energy,
    )
    val state: GameState = MatchFactory.create(setup, RuleTables.tables, map, config)
    val stepper: SimStepper = if (physics) StandardSystems.stepper() else SimStepper(RulesSystems.all())
    val ctx = StepContext()

    /** Alle Fx-Ereignisse seit dem letzten [send]/[run]. */
    val fx: MutableList<FxEvent> = ArrayList()

    val tick: Long get() = state.tick

    // ---- Zeit ----

    /** Ein Tick ohne Commands. */
    fun step() {
        ctx.beginTick(state.tick)
        stepper.step(state, ctx)
        fx.addAll(ctx.fx)
    }

    fun run(ticks: Int) { for (i in 0 until ticks) step() }

    /** Alle [cmds] im selben Tick (in Reihenfolge) anwenden; liefert ein Ergebnis je Command. */
    fun send(vararg cmds: Command): List<CommandResult> {
        ctx.beginTick(state.tick)
        for (c in cmds) ctx.commands.add(c.withTick(state.tick))
        stepper.step(state, ctx)
        fx.addAll(ctx.fx)
        return ctx.results.toList()
    }

    /** Ein Command, Ergebnis erwarten. */
    fun ok(cmd: Command) {
        val r = send(cmd).single()
        assertEquals(CommandResult.Accepted, r, "$cmd")
    }

    fun rejects(reason: RejectReason, cmd: Command) {
        val r = send(cmd).single()
        assertEquals(CommandResult.Rejected(reason), r, "$cmd")
    }

    /** Wie [rejects], aber nur der Validator (ohne Zustandsänderung). */
    fun validates(reason: RejectReason?, cmd: Command) {
        assertEquals(reason, RulesValidator.validate(state, cmd.withTick(state.tick)), "$cmd")
    }

    // ---- Zugriff ----

    fun nref(slot: Int): Long = state.nodes.ref(slot)
    fun bref(slot: Int): Long = state.beams.ref(slot)
    fun dref(slot: Int): Long = state.devices.ref(slot)

    /** Knoten-Slot am Ort ((x, y), Toleranz 0,01) oder −1. */
    fun nodeAt(x: Float, y: Float): Int {
        for (i in 0 until state.nodes.size) {
            if (state.nodes.isAlive(i) && FloatMath.abs(state.nodes.x[i] - x) < 0.01f && FloatMath.abs(state.nodes.y[i] - y) < 0.01f) return i
        }
        return -1
    }

    /** Lebender Balken zwischen den Knoten [a] und [b] (beliebige Richtung) oder −1. */
    fun beamBetween(a: Int, b: Int): Int {
        for (i in 0 until state.beams.size) {
            if (!state.beams.isAlive(i)) continue
            val x = state.beams.a[i]; val y = state.beams.b[i]
            if ((x == a && y == b) || (x == b && y == a)) return i
        }
        return -1
    }

    /** Lebende Geräte eines Typs (Slots, aufsteigend). */
    fun devicesOf(type: Int, owner: Int = 0): List<Int> =
        (0 until state.devices.size).filter { state.devices.isAlive(it) && state.devices.typeOf[it] == type && state.devices.ownerOf[it] == owner }

    fun player(id: Int = 0) = state.players[id]

    /** Neuer verankerter Knoten (z. B. Fundament am Rand der Bauzone). */
    fun addAnchor(x: Float, y: Float, owner: Int = 0): Int {
        val n = state.nodes.alloc(x, y, owner, anchored = true)
        state.topologyDirty = true
        return n
    }

    /** Beliebiger Balken zwischen zwei Knoten (ohne Kosten). */
    fun addBeam(a: Int, b: Int, material: Int = RuleTables.WOOD, owner: Int = 0): Int {
        val mat = RuleTables.tables.materials[material]
        val len = RuleChecks.dist(state.nodes.x[a], state.nodes.y[a], state.nodes.x[b], state.nodes.y[b])
        val id = state.beams.alloc(a, b, material, len * mat.restLengthFactor, mat.hp, owner)
        state.topologyDirty = true
        return id
    }

    /** Gerät ohne Kosten und Bauzeit (fertig). */
    fun addDevice(type: Int, beam: Int, t: Float, sideNegative: Boolean = true, owner: Int = 0): Int {
        val props = RuleTables.tables.devices[type]
        val id = state.devices.alloc(type, beam, t, props.hp, owner, 0, sideNegative, 0f, 1f)
        state.topologyDirty = true
        return id
    }

    /** Das Fort-Fundament von Spieler 0: Knoten bei (23, 34) und (26, 34), Balken 1 dazwischen. */
    val n23: Int get() = nodeAt(23f, 34f)
    val n26: Int get() = nodeAt(26f, 34f)
    val n20: Int get() = nodeAt(20f, 34f)
    val apex: Int get() = nodeAt(23f, 31f)
    val ground01: Int get() = beamBetween(n23, n26)
    val reactorBeam: Int get() = beamBetween(n20, n23)

    /** Reaktor eines Spielers töten (wie ein Treffer: Gerät freigeben). */
    fun killReactor(player: Int) {
        val d = state.players[player].reactorDeviceId
        state.devices.hpOf[d] = 0f
        state.devices.release(d)
    }
}
