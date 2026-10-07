package de.bollwerk.setup

import de.bollwerk.content.ClasspathContent
import de.bollwerk.engine.combat.CombatOps
import de.bollwerk.engine.combat.CombatSystems
import de.bollwerk.engine.combat.CombatWorld
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.sim.SimConfig
import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.SimTables
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kampf-Zahlen aus dem **echten** Content über `SimTablesFactory` (WP4). Die Kampf-Tests in `:engine` arbeiten mit
 * einer Kopie (`CombatTables`, `:engine` darf nicht von `:content` abhängen); dieser Test hält Kopie, Content und
 * Stil-Bibel zusammen (Sekunden → Ticks, Grad → Bogenmaß, Standardwerte) und rechnet zwei Kampfszenen auf den echten
 * Tabellen.
 */
class CombatTablesTest {
    private val db = ClasspathContent.load()
    private val tables: SimTables = SimTablesFactory.build(db)

    private data class W(
        val mode: WeaponMode, val damage: Float, val splashR: Float, val splashD: Float, val maxRange: Float,
        val reloadTicks: Int, val metal: Float, val energy: Float, val speed: Float, val shots: Int, val burstTicks: Int,
        val spreadDeg: Float, val directJ: Float, val explJ: Float, val recoilJ: Float, val igniteR: Float, val pierce: Int,
        val devF: Float, val radius: Float, val ttlTicks: Int, val beamTicks: Int, val gravityScale: Float,
    )

    /** Gleiche Werte wie `CombatTables` in `engine/src/commonTest/.../combat/CombatRig.kt` (= Stil-Bibel Abschnitt 1). */
    private val expectedWeapons = mapOf(
        "mg" to W(WeaponMode.HITSCAN, 6f, 0f, 0f, 60f, 180, 0f, 8f, 120f, 8, 4, 1.2f, 30f, 0f, 0f, 0f, 0, 1f, 0.05f, 0, 0, 0f),
        "sniper" to W(WeaponMode.HITSCAN, 40f, 0f, 0f, 120f, 240, 0f, 15f, 240f, 1, 0, 0f, 60f, 0f, 0f, 0f, 1, 3f, 0.04f, 0, 0, 0f),
        "mortar" to W(WeaponMode.BALLISTIC, 0f, 2.5f, 120f, 110f, 360, 15f, 30f, 33f, 1, 0, 0f, 0f, 1100f, 260f, 0f, 0, 1f, 0.14f, 600, 0, 1f),
        "cannon" to W(WeaponMode.BALLISTIC, 90f, 1.2f, 30f, 100f, 600, 30f, 60f, 46f, 1, 0, 0f, 2200f, 650f, 520f, 0f, 0, 1f, 0.1f, 720, 0, 1f),
        "rocket" to W(WeaponMode.BALLISTIC, 0f, 2f, 40f, 100f, 720, 20f, 40f, 38f, 1, 0, 0f, 0f, 300f, 200f, 2f, 0, 1f, 0.12f, 960, 0, 0.6f),
        "laser" to W(WeaponMode.BEAM, 80f, 0f, 0f, 140f, 1200, 0f, 150f, 0f, 1, 0, 0f, 0f, 0f, 0f, 0f, 0, 1f, 0.08f, 0, 120, 0f),
    )

    @Test
    fun weaponNumbersMatchStyleBibleAndEngineTestCopy() {
        for ((key, e) in expectedWeapons) {
            val w = tables.weapons[db.weaponIndex(key)]
            val msg = "weapon $key"
            assertEquals(e.mode, w.mode, msg)
            assertEquals(e.damage, w.damage, msg)
            assertEquals(e.splashR, w.splashRadius, msg)
            assertEquals(e.splashD, w.splashDamage, msg)
            assertEquals(e.maxRange, w.maxRange, msg)
            assertEquals(e.reloadTicks, w.reloadTicks, msg)
            assertEquals(e.metal, w.shotMetal, msg)
            assertEquals(e.energy, w.shotEnergy, msg)
            assertEquals(e.speed, w.muzzleSpeed, msg)
            assertEquals(e.shots, if (w.shotsPerBurst > 1) w.shotsPerBurst else 1, msg)
            assertEquals(e.burstTicks, w.burstIntervalTicks, msg)
            assertEquals(e.spreadDeg * FloatMath.DEG_TO_RAD, w.spreadRad, 1e-6f, msg)
            assertEquals(e.directJ, w.directImpulse, msg)
            assertEquals(e.explJ, w.explosionImpulse, msg)
            assertEquals(e.recoilJ, w.recoilImpulse, msg)
            assertEquals(e.igniteR, w.igniteRadius, msg)
            assertEquals(e.pierce, w.piercesBeams, msg)
            assertEquals(e.devF, w.deviceDamageFactor, msg)
            assertEquals(e.radius, w.projectileRadius, msg)
            assertEquals(e.ttlTicks, w.ttlTicks, msg)
            assertEquals(e.beamTicks, w.beamTicks, msg)
            assertEquals(e.gravityScale, w.gravityScale, msg)
        }
        // Stil-Bibel-Kernzahlen ausdrücklich
        val mortar = tables.weapons[db.weaponIndex("mortar")]
        assertEquals(2.5f, mortar.splashRadius); assertEquals(120f, mortar.splashDamage)
        assertEquals(3f, tables.weapons[db.weaponIndex("sniper")].deviceDamageFactor)
        assertEquals(0.6f, tables.weapons[db.weaponIndex("rocket")].gravityScale)
        val mg = tables.weapons[db.weaponIndex("mg")]
        assertEquals(8, mg.shotsPerBurst); assertEquals(4, mg.burstIntervalTicks)
    }

    @Test
    fun materialAndDeviceNumbersMatch() {
        data class M(val hp: Float, val dmgF: Float, val thick: Float, val flam: Boolean, val door: Boolean)
        val mats = mapOf(
            "wood" to M(100f, 1f, 0.32f, true, false),
            "metal" to M(260f, 1f, 0.26f, false, false),
            "armour" to M(520f, 0.3f, 0.42f, false, false),
            "rope" to M(60f, 1f, 0.08f, true, false),
            "door" to M(160f, 0.8f, 0.4f, false, true),
        )
        for ((key, e) in mats) {
            val m = tables.materials[db.materialIndex(key)]
            assertEquals(e.hp, m.hp, key); assertEquals(e.dmgF, m.damageFactor, key); assertEquals(e.thick, m.thickness, key)
            assertEquals(e.flam, m.flammable, key); assertEquals(e.door, m.isDoor, key)
        }
        data class D(val hp: Float, val rad: Float, val off: Float, val piv: Float, val barrel: Float, val weapon: String?)
        val devs = mapOf(
            "reactor" to D(300f, 1.2f, 1.2f, 0f, 0f, null),
            "mg" to D(55f, 0.6f, 0.55f, 0.72f, 1.2f, "mg"),
            "sniper" to D(60f, 0.6f, 0.55f, 0.75f, 1.8f, "sniper"),
            "mortar" to D(90f, 0.7f, 0.55f, 0.62f, 1.05f, "mortar"),
            "cannon" to D(110f, 0.85f, 0.6f, 0.78f, 1.95f, "cannon"),
            "rocket" to D(80f, 0.8f, 0.6f, 0.7f, 1.4f, "rocket"),
            "laser" to D(100f, 0.8f, 0.65f, 0.8f, 1.5f, "laser"),
        )
        for ((key, e) in devs) {
            val d = tables.devices[db.deviceIndex(key)]
            assertEquals(e.hp, d.hp, key); assertEquals(e.rad, d.hitRadius, key); assertEquals(e.off, d.mountOffset, key)
            assertEquals(e.piv, d.pivotOffset, key); assertEquals(e.barrel, d.barrelLength, key)
            assertEquals(if (e.weapon == null) -1 else db.weaponIndex(e.weapon), d.weapon, key)
        }
    }

    private fun state(): GameState = GameState(5L, tables, MapSpec.flat(), SimConfig.DEFAULT)

    private fun beam(s: GameState, x0: Float, y0: Float, x1: Float, y1: Float, mat: Int, owner: Int = 0): Int {
        val a = s.nodes.alloc(x0, y0, owner, true)
        val b = s.nodes.alloc(x1, y1, owner, true)
        val dx = x1 - x0; val dy = y1 - y0
        val m = s.tables.materials[mat]
        val id = s.beams.alloc(a, b, mat, sqrt(dx * dx + dy * dy) * m.restLengthFactor, m.hp, owner)
        s.nodes.rebuildAdjacency(s.beams)
        s.topologyDirty = true
        return id
    }

    /** Mörser-Explosion aus der echten Tabelle: Falloff 120 · (1 − d/2,5), Panzer nimmt 30 %. */
    @Test
    fun mortarExplosionOnRealTables() {
        val s = state()
        val metal = db.materialIndex("metal"); val armour = db.materialIndex("armour")
        val mb = beam(s, 19f, 20f, 19f, 30f, metal)
        val ab = beam(s, 21f, 20f, 21f, 30f, armour)
        val hm = tables.materials[metal].thickness * 0.5f
        val ha = tables.materials[armour].thickness * 0.5f
        val x = (19f + hm + 21f - ha) * 0.5f
        val d = x - 19f - hm
        val w = tables.weapons[db.weaponIndex("mortar")]
        val ctx = StepContext()
        SimStepper.NONE.step(s, ctx) // ein Tick, damit die Balken nicht mehr "neu im laufenden Tick" sind
        ctx.beginTick(s.tick)
        CombatOps.explode(
            s, ctx, CombatWorld(), x, 25f, w.splashRadius, w.splashDamage, w.explosionImpulse, 0f,
            db.weaponIndex("mortar"), -1, -1, -1f, w.deviceDamageFactor,
        )
        val lossMetal = 260f - s.beams.hpOf[mb]
        assertEquals(120f * (1f - d / 2.5f), lossMetal, 1e-2f)
        assertEquals(0.3f * lossMetal, 520f - s.beams.hpOf[ab], 1e-2f)
        assertEquals(1, ctx.fx.count { it is FxEvent.Explosion })
    }

    /** Scharfschütze auf den echten Tabellen über die Kampf-Systeme: 40 Schaden, ×3 auf Geräte = 120. */
    @Test
    fun sniperTriplesDeviceDamageOnRealTables() {
        val s = state()
        val metal = db.materialIndex("metal")
        val platform = beam(s, 8.5f, 30f, 11.5f, 30f, metal)
        val sniper = s.devices.alloc(db.deviceIndex("sniper"), platform, 0.5f, 60f, 0, 0, true, 0f, 1f)
        // Ziel: Reaktor von Spieler 1 auf Schusshöhe (Drehpunkt 0,75 m über der Plattform)
        val base = beam(s, 24f, 31.2f, 26f, 31.2f, db.materialIndex("armour"), owner = 1)
        val reactor = s.devices.alloc(db.deviceIndex("reactor"), base, 0.5f, 300f, 1, 0, true, 0f, 1f)
        s.players[0].energy = 400f
        val stepper = SimStepper(CombatSystems.all())
        val ctx = StepContext()
        val fx = ArrayList<FxEvent>()
        fun tick() { ctx.beginTick(s.tick); stepper.step(s, ctx); fx.addAll(ctx.fx) }
        tick()
        s.devices.flags[sniper] = s.devices.flags[sniper] or DeviceFlags.FIRE_REQUESTED
        tick()
        val hit = fx.filterIsInstance<FxEvent.Hit>().single { it.target == HitTarget.DEVICE && !it.splash }
        assertEquals(s.devices.uidOf[reactor], hit.targetUid)
        assertEquals(120f, hit.damage)
        assertEquals(300f - 120f, s.devices.hpOf[reactor])
        assertEquals(400f - 15f, s.players[0].energy)
    }
}
