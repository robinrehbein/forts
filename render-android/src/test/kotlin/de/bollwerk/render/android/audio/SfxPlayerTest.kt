package de.bollwerk.render.android.audio

import de.bollwerk.renderapi.Camera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeBackend : SfxBackend {
    class Call(val id: SfxId, val left: Float, val right: Float, val rate: Float, val loop: Boolean, val handle: Int)
    val calls = ArrayList<Call>()
    val stopped = ArrayList<Int>()
    val volumes = HashMap<Int, Pair<Float, Float>>()
    private var next = 1
    override fun play(id: SfxId, left: Float, right: Float, rate: Float, loop: Boolean): Int {
        val h = next++
        calls.add(Call(id, left, right, rate, loop, h)); volumes[h] = left to right
        return h
    }
    override fun setVolume(handle: Int, left: Float, right: Float) { volumes[handle] = left to right }
    override fun stop(handle: Int) { stopped.add(handle) }
    var paused = 0
    var resumed = 0
    override fun pause() { paused++ }
    override fun resume() { resumed++ }
    override fun release() = Unit
}

class TestClock(var ms: Long = 1000) : AudioClock {
    override fun nowMs() = ms
}

class SfxPlayerTest {
    private val backend = FakeBackend()
    private val clock = TestClock()
    private val player = SfxPlayer(backend, clock)

    @Test
    fun voiceLimitStopsOldest() {
        repeat(6) { clock.ms += 100; player.play(SfxId.EXPLOSION) }
        assertEquals(6, backend.calls.size)
        assertEquals(4, player.activeVoices(SfxId.EXPLOSION))
        assertEquals(listOf(1, 2), backend.stopped)
    }

    @Test
    fun voicesExpireAfterDuration() {
        player.play(SfxId.CLICK)
        assertEquals(1, player.activeVoices(SfxId.CLICK))
        clock.ms += 500
        assertEquals(0, player.activeVoices(SfxId.CLICK))
    }

    @Test
    fun minGapSuppressesRapidRepeats() {
        player.play(SfxId.MG); clock.ms += 10; player.play(SfxId.MG)
        assertEquals(1, backend.calls.size)
        clock.ms += 40; player.play(SfxId.MG)
        assertEquals(2, backend.calls.size)
    }

    @Test
    fun panIsEqualPowerAndCentered() {
        player.play(SfxId.CLICK, 0.5f, 0f)
        val c = backend.calls[0]
        assertEquals(0.5f * SfxPlayer.HEADROOM, c.left, 1e-3f); assertEquals(0.5f * SfxPlayer.HEADROOM, c.right, 1e-3f)
        clock.ms += 10
        player.play(SfxId.CLICK, 0.5f, 1f)
        val r = backend.calls[1]
        assertTrue(r.right > r.left * 10)
        clock.ms += 10
        player.play(SfxId.CLICK, 0.5f, -1f)
        assertTrue(backend.calls[2].left > backend.calls[2].right * 10)
    }

    @Test
    fun volumeSettingsAndMute() {
        player.settings = AudioSettings(masterVolume = 0.5f, sfxVolume = 0.5f)
        player.play(SfxId.CLICK, 1f, 0f)
        assertEquals(0.25f * SfxPlayer.HEADROOM, backend.calls[0].left, 1e-3f)
        clock.ms += 10
        player.settings = AudioSettings(muted = true)
        player.play(SfxId.CLICK, 1f, 0f)
        assertEquals(1, backend.calls.size)
    }

    @Test
    fun pitchIsClamped() {
        player.play(SfxId.CLICK, 1f, 0f, 9f)
        assertEquals(2f, backend.calls[0].rate)
    }

    @Test
    fun fireLoopFollowsBurningCountAndStops() {
        player.setFireCount(5)
        player.update()
        clock.ms += 1000; player.update()
        val loopCall = backend.calls.single { it.loop }
        assertEquals(SfxId.FIRE_LOOP, loopCall.id)
        val h = loopCall.handle
        clock.ms += 3000; player.update()
        val full = backend.volumes.getValue(h).first
        assertEquals(0.09f * SfxPlayer.HEADROOM, full, 1e-3f) // Prototyp: min(.09, n*.018) bei 5 Balken
        player.setFireCount(1)
        clock.ms += 3000; player.update()
        val low = backend.volumes.getValue(h).first
        assertTrue(low < full * 0.3f && low > 0f, "weniger Feuer ist leiser: $low")
        player.setFireCount(0)
        repeat(20) { clock.ms += 1000; player.update() }
        assertTrue(h in backend.stopped)
        assertEquals(0f, player.fireVolume)
    }

    @Test
    fun panKeepsEqualPowerAndNeverClamps() {
        for (pan in listOf(-0.85f, -0.4f, 0f, 0.4f, 0.85f)) {
            val b = FakeBackend()
            val p = SfxPlayer(b, TestClock())
            p.play(SfxId.EXPLOSION, 1f, pan)
            val c = b.calls.single()
            assertTrue(c.left < 1f && c.right < 1f, "klemmt bei pan $pan: ${c.left}/${c.right}")
            val g = SfxPlayer.HEADROOM
            assertEquals(2f * g * g, c.left * c.left + c.right * c.right, 1e-3f, "Leistung bei pan $pan")
        }
    }

    @Test
    fun fireLoopHasHighestPriorityAndCracklesPlayWhileBurning() {
        assertTrue(SfxId.entries.all { it == SfxId.FIRE_LOOP || it.priority < SfxId.FIRE_LOOP.priority })
        player.setFireCount(3)
        repeat(120) { clock.ms += 16; player.update() }
        assertTrue(backend.calls.count { it.id == SfxId.CRACKLE } > 5)
        val before = backend.calls.count { it.id == SfxId.CRACKLE }
        player.setFireCount(0)
        repeat(60) { clock.ms += 16; player.update() }
        assertEquals(before, backend.calls.count { it.id == SfxId.CRACKLE })
    }

    @Test
    fun crackleDensityGrowsWithBurningCount() {
        fun crackles(n: Int): Int {
            val b = FakeBackend(); val c = TestClock(); val p = SfxPlayer(b, c)
            p.setFireCount(n)
            repeat(300) { c.ms += 16; p.update() }
            return b.calls.count { it.id == SfxId.CRACKLE }
        }
        assertTrue(crackles(5) > crackles(1) * 1.5f)
    }

    @Test
    fun pauseAndResumeForwardToBackendAndKeepFireState() {
        player.setFireCount(5)
        repeat(10) { clock.ms += 500; player.update() }
        val level = player.fireVolume
        player.pause(); player.resume()
        assertEquals(1, backend.paused); assertEquals(1, backend.resumed)
        assertEquals(level, player.fireVolume)
    }

    @Test
    fun fireLoopSilentWhenMuted() {
        player.settings = AudioSettings(muted = true)
        player.setFireCount(5)
        repeat(5) { clock.ms += 1000; player.update() }
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun spatialMixerPanAndFalloff() {
        assertEquals(0f, SpatialMixer.pan(10f, 10f, 20f))
        assertTrue(SpatialMixer.pan(30f, 10f, 20f) > 0.8f)
        assertTrue(SpatialMixer.pan(-100f, 10f, 20f) < -0.8f)
        assertEquals(1f, SpatialMixer.gain(25f, 10f, 20f))
        assertTrue(SpatialMixer.gain(70f, 10f, 20f) < 0.5f)
        assertEquals(SpatialMixer.MIN_GAIN, SpatialMixer.gain(1000f, 0f, 20f))
        assertEquals(0f, SpatialMixer.pan(Float.NaN, 0f, 20f))
        val cam = Camera(1920f, 1080f, 1f).also { it.centerX = 50f }
        assertTrue(SpatialMixer.pan(60f, cam) > 0f)
    }

    @Test
    fun hapticsRespectSettingAndRateLimit() {
        val vib = ArrayList<Long>()
        val clk = TestClock()
        val dev = object : VibrationDevice { override fun vibrate(millis: Long, amplitude: Int) { vib.add(millis) } }
        val h = Haptics(dev, clk)
        h.pulse(HapticStrength.LIGHT)
        h.pulse(HapticStrength.LIGHT)
        assertEquals(1, vib.size)
        clk.ms += 100
        h.settings = AudioSettings(hapticsEnabled = false)
        h.pulse(HapticStrength.MEDIUM)
        assertEquals(1, vib.size)
        clk.ms += 100
        h.settings = AudioSettings(hapticsEnabled = true)
        h.pulse(HapticStrength.MEDIUM)
        assertEquals(listOf(14L, 26L), vib)
        clk.ms += 129; h.pulse(HapticStrength.LIGHT)
        assertEquals(2, vib.size) // Mindestabstand 130 ms
    }
}
