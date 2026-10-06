package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import de.bollwerk.renderapi.ParticleKind
import de.bollwerk.renderapi.PooledParticleSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PooledParticleSystemTest {
    private fun kinds(p: PooledParticleSystem): Set<Int> = (0 until p.count).map { p.buffers.kind[it] }.toSet()

    private fun explosion(x: Float = 10f, y: Float = 10f, r: Float = 2.5f, dmg: Float = 120f, mat: Int = 0) =
        FxEvent.Explosion(1, x, y, r, dmg, 2, mat, false, -1, -1f)

    @Test
    fun capacityIsFixed() {
        assertEquals(600, PooledParticleSystem().buffers.capacity)
        assertEquals(260, PooledParticleSystem(reducedMotion = true).buffers.capacity)
        assertEquals(600, PooledParticleSystem.DEFAULT_CAP)
        assertEquals(260, PooledParticleSystem.REDUCED_CAP)
    }

    @Test
    fun capIsNeverExceeded() {
        for (reduced in listOf(false, true)) {
            val p = PooledParticleSystem(reducedMotion = reduced)
            val cap = p.buffers.capacity
            for (i in 0 until 5000) {
                p.emit(ParticleKind.entries[i % ParticleKind.entries.size], i * 0.01f, 0f, 1f, -1f, 2f, 0.3f)
                assertTrue(p.count <= cap, "count ${p.count} > cap $cap")
            }
            assertEquals(cap, p.count)
            for (i in 0 until 200) p.onFx(explosion(), 3f)
            assertTrue(p.count <= cap)
        }
    }

    @Test
    fun fullPoolDropsLowPriorityButAcceptsImportantParticles() {
        val p = PooledParticleSystem()
        for (i in 0 until 600) p.emit(ParticleKind.SMOKE, 0f, 0f, 0f, 0f, 5f, 0.5f)
        assertEquals(600, p.count)
        // Rauch wird verworfen, Funken/Trümmer verdrängen Rauch
        p.emit(ParticleKind.SMOKE, 1f, 1f, 0f, 0f, 5f, 0.5f)
        assertEquals(600, p.count)
        var sparks = 0
        for (i in 0 until 200) p.emit(ParticleKind.SPARK, 1f, 1f, 0f, 0f, 1f, 1f)
        for (i in 0 until p.count) if (p.buffers.kind[i] == ParticleKind.SPARK.ordinal) sparks++
        assertTrue(sparks > 100, "sparks=$sparks")
        assertEquals(600, p.count)
    }

    @Test
    fun explosionIsFivePhaseSequence() {
        val p = PooledParticleSystem()
        p.onFx(explosion(), 0f)
        val k = kinds(p)
        for (expected in listOf(ParticleKind.FLASH, ParticleKind.FIREBALL, ParticleKind.SHOCKWAVE, ParticleKind.CHUNK, ParticleKind.SMOKE, ParticleKind.DUST, ParticleKind.SPARK)) {
            assertTrue(expected.ordinal in k, "missing $expected")
        }
        val b = p.buffers
        var chunks = 0
        for (i in 0 until p.count) if (b.kind[i] == ParticleKind.CHUNK.ordinal) chunks++
        assertTrue(chunks in 6..12, "6-12 Trümmerstücke, war $chunks")
        // Blitz = 1 Frame, Feuerball 0,3 s, Schockwelle 0,38 s, Rauch bleibt ≥ 2,6 s
        for (i in 0 until p.count) {
            when (b.kind[i]) {
                ParticleKind.FLASH.ordinal -> assertTrue(b.life[i] <= 0.05f)
                ParticleKind.FIREBALL.ordinal -> assertEquals(0.3f, b.life[i], 1e-4f)
                ParticleKind.SHOCKWAVE.ordinal -> assertEquals(0.38f, b.life[i], 1e-4f)
                ParticleKind.SMOKE.ordinal -> assertTrue(b.life[i] >= 2.6f)
            }
        }
    }

    @Test
    fun phasesExpireInOrder() {
        val p = PooledParticleSystem()
        p.onFx(explosion(), 0f)
        for (i in 0 until 3) p.update(1f / 60f, 0f)
        assertFalse(ParticleKind.FLASH.ordinal in kinds(p), "Blitz nach einem Frame weg")
        assertTrue(ParticleKind.FIREBALL.ordinal in kinds(p))
        for (i in 0 until 20) p.update(1f / 60f, 0f)
        assertFalse(ParticleKind.FIREBALL.ordinal in kinds(p), "Feuerball nach 0,3 s weg")
        for (i in 0 until 60) p.update(1f / 60f, 0f)
        assertFalse(ParticleKind.SHOCKWAVE.ordinal in kinds(p))
        assertTrue(ParticleKind.SMOKE.ordinal in kinds(p), "Rauch bleibt ~3 s")
        for (i in 0 until 240) p.update(1f / 60f, 0f)
        assertEquals(0, p.count)
    }

    @Test
    fun reducedMotionHasNoFlashAndFewerParticles() {
        val full = PooledParticleSystem()
        val red = PooledParticleSystem(reducedMotion = true)
        full.onFx(explosion(), 0f); red.onFx(explosion(), 0f)
        assertFalse(ParticleKind.FLASH.ordinal in kinds(red))
        assertTrue(red.count < full.count)
    }

    @Test
    fun windDriftsSmokeAndEmbers() {
        val p = PooledParticleSystem()
        p.emit(ParticleKind.SMOKE, 0f, 20f, 0f, 0f, 3f, 0.5f)
        p.emit(ParticleKind.EMBER, 0f, 20f, 0f, 0f, 3f, 0.05f)
        for (i in 0 until 120) p.update(1f / 60f, 6f)
        for (i in 0 until p.count) assertTrue(p.buffers.x[i] > 0.5f, "kind ${p.buffers.kind[i]} drifted: ${p.buffers.x[i]}")
        // Rauch steigt auf (y nimmt ab)
        assertTrue(p.buffers.y[0] < 20f)
    }

    @Test
    fun chunksBounceOnTerrainAndRaiseDust() {
        val p = PooledParticleSystem()
        p.bindWorld(intArrayOf(0xFFA3692F.toInt()), Terrain.flat(-10f, 50f, 34f))
        p.emit(ParticleKind.CHUNK, 5f, 30f, 0f, 2f, 3f, 0.2f, 0xFFA3692F.toInt())
        var dust = 0
        for (i in 0 until 120) {
            p.update(1f / 60f, 0f)
            for (k in 0 until p.count) {
                if (p.buffers.kind[k] == ParticleKind.CHUNK.ordinal) assertTrue(p.buffers.y[k] <= 34.05f, "chunk below ground: ${p.buffers.y[k]}")
                if (p.buffers.kind[k] == ParticleKind.DUST.ordinal) dust++
            }
        }
        assertTrue(dust > 0, "Staub, wenn Trümmer den Boden treffen")
    }

    @Test
    fun everyEventKindIsHandled() {
        val p = PooledParticleSystem()
        val evs = listOf<FxEvent>(
            explosion(),
            FxEvent.BeamBroken(1, 5f, 5f, 3, 0, 0.5f, BreakCause.DAMAGE),
            FxEvent.BeamBroken(1, 5f, 5f, 3, 1, 0.5f, BreakCause.STRAIN),
            FxEvent.BeamBroken(1, 5f, 5f, 3, 0, 0.5f, BreakCause.FIRE),
            FxEvent.BeamBroken(1, 5f, 5f, 3, 3, 0.5f, BreakCause.DELETED),
            FxEvent.BeamBroken(1, 5f, 5f, 3, 0, 0.5f, BreakCause.DECAY),
            FxEvent.BeamSplit(1, 5f, 5f, 3, 4, 5),
            FxEvent.Fired(1, 5f, 5f, 2, 3, 0.7f),
            FxEvent.Tracer(1, 5f, 5f, 15f, 6f, 0),
            FxEvent.LaserBeam(1, 5f, 5f, 15f, 6f, 1),
            FxEvent.Hit(1, 5f, 5f, HitTarget.BEAM, 3, 10f, 0),
            FxEvent.Hit(1, 5f, 5f, HitTarget.DEVICE, 3, 10f, 0),
            FxEvent.Hit(1, 5f, 5f, HitTarget.TERRAIN, -1, 10f, 0),
            FxEvent.Ignited(1, 5f, 5f, 3),
            FxEvent.DeviceDestroyed(1, 5f, 5f, 3, 1),
            FxEvent.DevicePlaced(1, 5f, 5f, 3, 1),
            FxEvent.DebrisLanded(1, 5f, 5f, 5f),
            FxEvent.BeamPlaced(1, 5f, 5f, 3, 0),
            FxEvent.BeamRepaired(1, 5f, 5f, 3),
            FxEvent.DoorToggled(1, 5f, 5f, 3, true),
            FxEvent.TechChanged(1, Float.NaN, Float.NaN, 0, 1, true),
            FxEvent.CommandRejected(1, Float.NaN, Float.NaN, 0, de.bollwerk.engine.command.RejectReason.TOO_LONG),
            FxEvent.ReactorDestroyed(1, 5f, 5f, 1),
        )
        for (e in evs) {
            val before = p.count
            p.onFx(e, 2f)
            if (e !is FxEvent.BeamSplit && e !is FxEvent.TechChanged && e !is FxEvent.CommandRejected) assertTrue(p.count > before, "no particles for ${e::class.simpleName}")
        }
        // Mündungsfeuer zeigt in Schussrichtung
        val q = PooledParticleSystem()
        q.onFx(FxEvent.Fired(1, 5f, 5f, 2, 3, 0f), 0f)
        assertEquals(ParticleKind.MUZZLE_FLASH.ordinal, q.buffers.kind[0])
        assertEquals(1f, q.buffers.vx[0], 1e-3f)
    }

    @Test
    fun colorZeroMeansDefaultOfTheKind() {
        val p = PooledParticleSystem()
        p.emit(ParticleKind.CHUNK, 0f, 0f, 0f, 0f, 1f, 0.2f)
        p.emit(ParticleKind.SPARK, 0f, 0f, 0f, 0f, 1f, 1f)
        assertTrue((p.buffers.color[0] and 0xFFFFFF) != 0, "chunk default is not black")
        assertTrue((p.buffers.color[1] and 0xFFFFFF) != 0)
        p.emit(ParticleKind.CHUNK, 0f, 0f, 0f, 0f, 1f, 0.2f, 0xFF123456.toInt())
        assertEquals(0xFF123456.toInt(), p.buffers.color[2])
    }

    @Test
    fun clearEmptiesThePool() {
        val p = PooledParticleSystem()
        p.onFx(explosion(), 0f)
        assertTrue(p.count > 0)
        p.clear()
        assertEquals(0, p.count)
    }

    @Test
    fun contentOrderIsNotAssumed() {
        val colors = intArrayOf(0xFF111111.toInt(), 0xFF222222.toInt(), 0xFF333333.toInt(), 0xFF444444.toInt(), 0xFF555555.toInt())
        // umsortierter Content: Metall zuerst, dann Seil, Holz, Panzer, Tür
        val matKinds = intArrayOf(MatKind.METAL, MatKind.ROPE, MatKind.WOOD, MatKind.ARMOUR, MatKind.DOOR)
        val weaponKinds = intArrayOf(DevKind.CANNON, DevKind.MG, DevKind.LASER)
        fun bound(): PooledParticleSystem = PooledParticleSystem(seed = 4).also { it.bindWorld(colors, matKinds, weaponKinds, Terrain.flat(-10f, 50f, 34f)) }

        val wood = bound(); wood.onFx(FxEvent.BeamBroken(1, 5f, 5f, 3, 2, 0.5f, BreakCause.DAMAGE), 0f)
        assertTrue(ParticleKind.SPLINTER.ordinal in kinds(wood), "material 2 is wood here: splinters")
        assertFalse(ParticleKind.SPARK.ordinal in kinds(wood))
        val metal = bound(); metal.onFx(FxEvent.BeamBroken(1, 5f, 5f, 3, 0, 0.5f, BreakCause.DAMAGE), 0f)
        assertTrue(ParticleKind.SPARK.ordinal in kinds(metal), "material 0 is metal here: sparks")
        assertFalse(ParticleKind.SPLINTER.ordinal in kinds(metal))
        val rope = bound(); rope.onFx(FxEvent.BeamBroken(1, 5f, 5f, 3, 1, 0.5f, BreakCause.DAMAGE), 0f)
        assertTrue(ParticleKind.DEBRIS.ordinal in kinds(rope)); assertFalse(ParticleKind.SPARK.ordinal in kinds(rope))
        assertFalse(ParticleKind.SPLINTER.ordinal in kinds(rope))

        // Mündungsfeuer nach Waffenart, nicht nach Index: Waffe 0 ist hier die Kanone (1,1), Waffe 1 das MG (0,55)
        val cannon = bound(); cannon.onFx(FxEvent.Fired(1, 5f, 5f, 2, 0, 0f), 0f)
        assertEquals(1.1f, cannon.buffers.size[0], 1e-4f)
        val mg = bound(); mg.onFx(FxEvent.Fired(1, 5f, 5f, 2, 1, 0f), 0f)
        assertEquals(0.55f, mg.buffers.size[0], 1e-4f)
        val unbound = PooledParticleSystem(seed = 4); unbound.onFx(FxEvent.Fired(1, 5f, 5f, 2, 0, 0f), 0f)
        assertEquals(0.55f, unbound.buffers.size[0], 1e-4f, "default order of the shipped content: weapon 0 = MG")

        // Reaktor-Trümmer haben die Farbe des Metall-Materials (hier Index 0), nicht von Index 1
        val reactor = bound(); reactor.onFx(FxEvent.ReactorDestroyed(1, 5f, 5f, 1), 0f)
        val chunkColors = (0 until reactor.count).filter { reactor.buffers.kind[it] == ParticleKind.CHUNK.ordinal }.map { reactor.buffers.color[it] }.toSet()
        assertEquals(setOf(colors[0]), chunkColors)
    }

    @Test
    fun reactorPulsePhaseIntegratesInsteadOfScalingAbsoluteTime() {
        val sc = SyntheticScene().both()
        val snap = sc.snap
        val reactor = (0 until snap.deviceCount).first { sc.tables.devices[snap.deviceType[it]].key == "reactor" }
        val fx = FxState(false)
        val kinds = IntArray(sc.tables.devices.size) { DevKind.of(sc.tables.devices[it].key, sc.tables.devices[it].role, sc.tables.devices[it].weapon >= 0) }
        fx.sync(snap)
        val dt = 1f / 60f
        // spät im Match, Dauerschaden: die Phase wächst je Schritt um genau f(hp)·dt (kein Phasensprung durch Zeit × Δf)
        var hp = 1f
        for (step in 0 until 600) {
            hp = 1f - step / 700f
            snap.deviceHp01[reactor] = hp
            val before = fx.devPhase[reactor]
            fx.update(dt, snap, kinds)
            var d = fx.devPhase[reactor] - before
            if (d < 0f) d += SceneContext.TAU * 64f // Wrap
            assertEquals((2.6f + (1f - hp) * 7f) * dt, d, 1e-3f, "step $step")
        }
        // höhere Schadensstufe = schnellerer Puls, aber nie ein Sprung
        assertTrue((2.6f + (1f - 0.1f) * 7f) > (2.6f + (1f - 0.9f) * 7f))
    }
}
