package de.bollwerk.render.android.audio

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import de.bollwerk.renderapi.Camera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecSink : SfxSink {
    class P(val id: SfxId, val volume: Float, val pan: Float, val pitch: Float)
    val plays = ArrayList<P>()
    var fire = -1
    var updates = 0
    override fun play(id: SfxId, volume: Float, pan: Float, pitch: Float) { plays.add(P(id, volume, pan, pitch)) }
    override fun setFireCount(count: Int) { fire = count }
    override fun update() { updates++ }
}

private class RecHaptics : HapticsSink {
    val pulses = ArrayList<HapticStrength>()
    override fun pulse(strength: HapticStrength) { pulses.add(strength) }
}

class FxAudioMapperTest {
    private val sink = RecSink()
    private val hap = RecHaptics()
    private val mapper = FxAudioMapper(sink, hap, localPlayerId = 0)
    private val cam = Camera(1920f, 1080f, 1f).also { it.centerX = 30f; it.centerY = 20f }

    private fun snap(seq: Long, vararg fx: FxEvent): FrameSnapshot = FrameSnapshot().also {
        it.seq = seq
        it.beamCount = 2
        it.beamFlags = intArrayOf(BeamFlags.ALIVE, BeamFlags.ALIVE)
        it.beamFire01 = floatArrayOf(0f, 0f)
        it.beamUid = intArrayOf(100, 101)
        it.beamOwner = intArrayOf(0, 1)
        it.deviceCount = 2
        it.deviceFlags = intArrayOf(DeviceFlags.ALIVE, DeviceFlags.ALIVE)
        it.deviceUid = intArrayOf(10, 11)
        it.deviceOwner = intArrayOf(0, 1)
        it.deviceReload01 = floatArrayOf(1f, 1f)
        it.deviceBuild01 = floatArrayOf(1f, 1f)
        it.fx.addAll(fx)
    }

    private fun fired(w: Int, uid: Int = 10, x: Float = 30f) = FxEvent.Fired(1, x, 10f, uid, w, 0f)

    @Test
    fun eachSeqProcessedOnce() {
        val s = snap(5, fired(3))
        assertTrue(mapper.process(s, cam))
        assertEquals(false, mapper.process(s, cam))
        assertEquals(false, mapper.process(s, cam))
        assertEquals(1, sink.plays.size)
        assertEquals(SfxId.CANNON, sink.plays[0].id)
        assertEquals(3, sink.updates)
        s.seq = 6
        assertTrue(mapper.process(s, cam))
        assertEquals(2, sink.plays.size)
    }

    @Test
    fun weaponSoundsAndOwnFireHaptics() {
        mapper.process(snap(1, fired(0), fired(2), fired(3), fired(5)), cam)
        assertEquals(listOf(SfxId.MG, SfxId.MORTAR, SfxId.CANNON), sink.plays.map { it.id })
        assertEquals(3, hap.pulses.size)
        mapper.process(snap(2, fired(2, uid = 11)), cam)
        assertEquals(3, hap.pulses.size) // gegnerischer Schuss: keine Haptik
    }

    @Test
    fun explosionScalesWithDamageAndPansByPosition() {
        val big = FxEvent.Explosion(1, 60f, 10f, 5f, 200f, 2, 0, false, -1, 0f)
        val small = FxEvent.Explosion(1, 0f, 10f, 1f, 20f, 2, -1, false, -1, 0f)
        mapper.process(snap(1, big, small), cam)
        assertEquals(SfxId.EXPLOSION, sink.plays[0].id)
        assertEquals(SfxId.EXPLOSION_SMALL, sink.plays[1].id)
        assertTrue(sink.plays[0].pan > 0f) // rechts der Kamera
        assertTrue(sink.plays[1].pan < 0f)
    }

    @Test
    fun explosionOnOwnBeamGivesHaptic() {
        mapper.process(snap(1, FxEvent.Explosion(1, 30f, 10f, 5f, 100f, 2, 0, false, 100, 0.5f)), cam)
        assertEquals(listOf(HapticStrength.MEDIUM), hap.pulses)
        mapper.process(snap(2, FxEvent.Explosion(1, 30f, 10f, 5f, 100f, 2, 0, false, 101, 0.5f)), cam)
        assertEquals(1, hap.pulses.size)
    }

    @Test
    fun breakSoundsByMaterialAndCause() {
        mapper.process(snap(1,
            FxEvent.BeamBroken(1, 30f, 1f, 100, 0, 0.5f, BreakCause.DAMAGE),
            FxEvent.BeamBroken(1, 30f, 1f, 100, 1, 0.5f, BreakCause.STRAIN),
            FxEvent.BeamBroken(1, 30f, 1f, 100, 0, 0.5f, BreakCause.DELETED),
            FxEvent.BeamBroken(1, 30f, 1f, 100, 0, 0.5f, BreakCause.DECAY)), cam)
        assertEquals(listOf(SfxId.WOOD_BREAK, SfxId.METAL_BREAK), sink.plays.map { it.id })
        sink.plays.clear()
        mapper.process(snap(2, FxEvent.BeamBroken(1, 30f, 1f, 100, 4, 0.5f, BreakCause.DAMAGE)), cam) // Tür = Metall
        assertEquals(listOf(SfxId.METAL_BREAK), sink.plays.map { it.id })
    }

    @Test
    fun placeAndMiscSounds() {
        mapper.process(snap(1,
            FxEvent.BeamPlaced(1, 30f, 1f, 100, 0),
            FxEvent.BeamPlaced(1, 30f, 1f, 100, 1),
            FxEvent.DevicePlaced(1, 30f, 1f, 10, 3),
            FxEvent.DebrisLanded(1, 30f, 1f, 3f),
            FxEvent.DebrisLanded(1, 30f, 1f, 9f)), cam)
        assertEquals(listOf(SfxId.PLACE_WOOD, SfxId.PLACE_METAL, SfxId.PLACE_METAL, SfxId.THUD), sink.plays.map { it.id })
    }

    @Test
    fun hitHapticOnlyForOwnTargets() {
        mapper.process(snap(1,
            FxEvent.Hit(1, 30f, 1f, HitTarget.BEAM, 100, 5f, 0),
            FxEvent.Hit(1, 30f, 1f, HitTarget.BEAM, 101, 5f, 0),
            FxEvent.Hit(1, 30f, 1f, HitTarget.DEVICE, 10, 5f, 2)), cam)
        assertEquals(2, hap.pulses.size)
    }

    @Test
    fun fireLoopCountFollowsBurningBeams() {
        val s = snap(1)
        s.beamFire01 = floatArrayOf(0.8f, 0.02f)
        mapper.process(s, cam)
        assertEquals(1, sink.fire)
        s.seq = 2
        s.beamFire01 = floatArrayOf(0.8f, 0.5f)
        mapper.process(s, cam)
        assertEquals(2, sink.fire)
        s.seq = 3
        s.beamFlags = intArrayOf(0, BeamFlags.ALIVE) // toter Slot zählt nicht
        mapper.process(s, cam)
        assertEquals(1, sink.fire)
        assertEquals(1, mapper.burningCount)
    }

    @Test
    fun reloadPingOnlyOnTransitionOfOwnDevice() {
        val s = snap(1)
        s.deviceReload01 = floatArrayOf(0.4f, 0.4f)
        mapper.process(s, cam)
        assertTrue(sink.plays.isEmpty())
        s.seq = 2
        s.deviceReload01 = floatArrayOf(1f, 1f)
        mapper.process(s, cam)
        assertEquals(listOf(SfxId.RELOAD_PING), sink.plays.map { it.id })
        s.seq = 3
        mapper.process(s, cam)
        assertEquals(1, sink.plays.size)
    }

    @Test
    fun reactorDestroyedOnlyExplodes() {
        mapper.process(snap(1, FxEvent.ReactorDestroyed(1, 10f, 10f, 1)), cam)
        assertEquals(listOf(SfxId.EXPLOSION), sink.plays.map { it.id })
    }

    @Test
    fun stingerComesFromResultOnce() {
        val s = snap(1)
        mapper.process(s, cam)
        s.seq = 2; s.result = GameResult.Winner(0, WinReason.REACTOR_DESTROYED)
        mapper.process(s, cam)
        s.seq = 3
        mapper.process(s, cam)
        assertEquals(listOf(SfxId.VICTORY), sink.plays.map { it.id })
        // neue Partie
        mapper.reset(); sink.plays.clear()
        val t = snap(1); t.result = GameResult.Winner(1, WinReason.SURRENDER)
        mapper.process(t, cam)
        assertEquals(listOf(SfxId.DEFEAT), sink.plays.map { it.id }) // Aufgabe ohne ReactorDestroyed
    }

    @Test
    fun noStingerForDrawSpectatorOrPendingTurnResult() {
        val d = snap(1); d.result = GameResult.Draw
        mapper.process(d, cam)
        assertTrue(sink.plays.isEmpty())
        // Rundenmodus: Reaktor zerstört, Ergebnis noch offen → kein Stinger (nur Explosion)
        mapper.process(snap(2, FxEvent.ReactorDestroyed(1, 10f, 10f, 1)), cam)
        assertEquals(listOf(SfxId.EXPLOSION), sink.plays.map { it.id })
        val spectator = FxAudioMapper(sink, hap, localPlayerId = -1)
        sink.plays.clear()
        val w = snap(1); w.result = GameResult.Winner(0, WinReason.REACTOR_DESTROYED)
        spectator.process(w, cam)
        assertTrue(sink.plays.isEmpty())
    }

    @Test
    fun noReloadPingForNonWeaponDeviceWithUidZeroOnFirstFrame() {
        val s = snap(1)
        s.deviceUid = intArrayOf(0, 11)
        mapper.process(s, cam)
        s.seq = 2
        mapper.process(s, cam)
        assertTrue(sink.plays.none { it.id == SfxId.RELOAD_PING })
    }

    @Test
    fun mgBurstGivesSingleHaptic() {
        val burst = Array(8) { FxEvent.Fired(it * 4L, 30f, 10f, 10, 0, 0f) }
        mapper.process(snap(1, *burst), cam)
        assertEquals(8, sink.plays.count { it.id == SfxId.MG })
        assertEquals(1, hap.pulses.size)
    }

    @Test
    fun rejectionsAndUiForLocalPlayerOnly() {
        mapper.process(snap(1,
            FxEvent.FireRefused(1, 1f, 1f, 10, 0, de.bollwerk.engine.command.RejectReason.NOT_ENOUGH_METAL),
            FxEvent.FireRefused(1, 1f, 1f, 11, 1, de.bollwerk.engine.command.RejectReason.NOT_ENOUGH_METAL)), cam)
        assertEquals(listOf(SfxId.CLICK), sink.plays.map { it.id })
    }

    @Test
    fun contentIdsResolveByName() {
        val ids = AudioContentIds.fromIds(listOf("metal", "wood"), listOf("cannon", "mg"))
        assertEquals(1, ids.wood); assertEquals(0, ids.metal); assertEquals(1, ids.mg); assertEquals(0, ids.cannon)
        assertEquals(-1, ids.sniper) // fehlend: kein Treffer, keine Kollision mit mg=1
        assertEquals(-1, ids.door)
    }
}
