package de.bollwerk.setup

import de.bollwerk.content.ClasspathContent
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.GameSession
import de.bollwerk.engine.loop.ReplayRecorder
import de.bollwerk.engine.loop.ScriptedCommandSource
import de.bollwerk.engine.loop.StateHash
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.PlayerSetup
import de.bollwerk.engine.sim.WeaponMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SetupTest {
    private val db = ClasspathContent.load()
    private val setup = MatchSetup(77L, "schlucht", listOf(PlayerSetup(Controller.HUMAN), PlayerSetup(Controller.AI, "NORMAL")))

    @Test
    fun buildsTablesInContentIndexOrder() {
        val t = SimTablesFactory.build(db)
        assertEquals(db.materials.size, t.materials.size)
        val wood = t.materials[db.materialIndex("wood")]
        assertEquals("wood", wood.key)
        assertEquals(0.035f, wood.compressionLimit)
        assertEquals(Float.POSITIVE_INFINITY, t.materials[db.materialIndex("rope")].compressionLimit)
        assertEquals(db.techIndex("upgrade_center"), t.materials[db.materialIndex("armour")].requiredTech)
        val mortarWeapon = t.weapons[db.weaponIndex("mortar")]
        assertEquals(360, mortarWeapon.reloadTicks) // 6 s · 60
        assertEquals(WeaponMode.BALLISTIC, mortarWeapon.mode)
        assertEquals(120f, mortarWeapon.splashDamage)
        assertEquals(52f * FloatMath.DEG_TO_RAD, mortarWeapon.defaultAimRad)
        val mortarDevice = t.devices[db.deviceIndex("mortar")]
        assertEquals(DeviceRole.WEAPON, mortarDevice.role)
        assertEquals(db.weaponIndex("mortar"), mortarDevice.weapon)
        assertEquals(1.05f, mortarDevice.barrelLength)
        assertTrue(t.devices[db.deviceIndex("reactor")].unique)
        assertEquals(1800, t.devices[db.deviceIndex("workshop")].buildTicks)
        assertEquals(db.blueprints.map { it.id }, t.blueprints.map { it.key })
    }

    @Test
    fun mapSpecFromSchlucht() {
        val m = MapSpecFactory.build(db, "schlucht")
        assertEquals(120f, m.width)
        assertEquals(52f, m.terrain.heightAt(60f), 1e-3f)
        assertEquals(-40f, m.killMinX); assertEquals(160f, m.killMaxX)
        assertEquals(-120f, m.killMinY); assertEquals(84f, m.killMaxY)
        assertTrue(m.inBuildZone(1, 100f))
        assertTrue(m.hasOreNear(0, 26f, 2.4f))
        assertEquals(-6f, m.windMin)
        assertEquals(34f, m.baseY[1])
    }

    @Test
    fun bootstrapsMatchWithReactorsOnFoundations() {
        val s = MatchBootstrap.create(db, setup)
        for (p in s.players) {
            val r = p.reactorDeviceId
            assertTrue(s.devices.isAlive(r), "player ${p.id} needs a reactor")
            assertEquals(400f, p.metal); assertEquals(200f, p.energy)
        }
        // Startfestung nutzt die Fundamente (keine doppelten Knoten auf 24..36 bzw. 96..84): alle Fundamente der Karte
        // (je Spieler 5 der Startfestung + 2 für den KI-Anbau) + je Fort alle Knoten ohne die 5 Fundamente
        val fort = db.blueprint("fort_standard")
        assertEquals(db.map(setup.mapId).foundations.size + 2 * (fort.nodes.size - 5), s.nodes.aliveCount)
        assertEquals(StateHash.of(s), StateHash.of(MatchBootstrap.create(db, setup)))
    }

    @Test
    fun replayIsReproducibleAndGuardedByFingerprint() {
        val rec = ReplayRecorder()
        val s = GameSession(MatchBootstrap.create(db, setup), sources = listOf(ScriptedCommandSource(listOf(Command.Undo(5, 0)))), recorder = rec)
        s.run(60)
        val replay = rec.toReplay(setup, s.state.config, db.version, db.fingerprint, s.state.tick)
        val back = de.bollwerk.engine.loop.Replay.fromJson(replay.toJson())
        val again = GameSession(MatchBootstrap.forReplay(db, back), sources = listOf(ScriptedCommandSource(back.commands)))
        again.run(back.ticks)
        assertEquals(s.hash(), again.hash())
        assertFailsWith<IllegalStateException> { MatchBootstrap.forReplay(db, back.copy(contentHash = back.contentHash + 1)) }
    }
}
