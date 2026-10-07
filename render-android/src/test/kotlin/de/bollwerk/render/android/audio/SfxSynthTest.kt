package de.bollwerk.render.android.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SfxSynthTest {
    @Test
    fun everySoundHasExpectedDurationNoNanAndPeakWithinOne() {
        for (id in SfxId.entries) {
            val f = SfxSynth.render(id)
            assertEquals(Dsp.samplesFor(id.durationSec), f.size, "Länge $id")
            var peak = 0f
            for (v in f) {
                assertFalse(v.isNaN() || v.isInfinite(), "NaN in $id")
                peak = maxOf(peak, kotlin.math.abs(v))
            }
            assertTrue(peak <= 1f, "Peak $id = $peak")
            assertTrue(peak > 0.03f, "zu leise $id = $peak")
        }
    }

    @Test
    fun pcmIsDeterministicAcrossRuns() {
        val a = SfxBank.synthesize()
        val b = SfxBank.synthesize()
        for (id in SfxId.entries) {
            assertContentEquals(a.wav(id), b.wav(id), "Bytes $id")
        }
    }

    /** Golden-Hash je WAV: jede Änderung der Synthese (oder JVM/ART-Abweichung) muss bewusst sein. */
    @Test
    fun wavBytesMatchGoldenChecksums() {
        val bank = SfxBank.synthesize()
        val actual = SfxId.entries.joinToString("\n") { id ->
            val c = java.util.zip.CRC32().also { it.update(bank.wav(id)) }
            "${id.name}=${java.lang.Long.toHexString(c.value)}"
        }
        assertEquals(GOLDEN.trimIndent(), actual)
    }

    private fun rms(f: FloatArray, from: Int = 0, to: Int = f.size): Double {
        var s = 0.0
        for (i in from until to) s += f[i] * f[i]
        return kotlin.math.sqrt(s / (to - from))
    }

    private fun peak(f: FloatArray) = f.maxOf { kotlin.math.abs(it) }

    private fun db(ratio: Double) = 20 * kotlin.math.log10(ratio)

    /** Lautstärken zueinander wie im Prototyp (absolute Gains, kein Normalisieren je Sound). */
    @Test
    fun relativeLoudnessMatchesPrototype() {
        val mortar = SfxSynth.render(SfxId.MORTAR)
        val click = SfxSynth.render(SfxId.CLICK)
        // Prototyp: click = Square .05, Mortar-Spitze ~1.0 → ca. -26 dB
        assertEquals(-26.0, db(peak(click).toDouble() / peak(mortar)), 3.0, "click/mortar")
        // Feuer bei Sättigung: Schleife (Gain 1) × min(.09, n*.018), RMS gegen Mortar-Spitze
        val fire = SfxSynth.render(SfxId.FIRE_LOOP)
        val fireSat = rms(fire) * SfxPlayer.FIRE_MAX
        val ratio = db(fireSat / peak(mortar))
        assertTrue(ratio < -22.0 && ratio > -40.0, "fire/mortar $ratio dB")
        // Prototyp-Reihenfolge der Spitzen: Mortar/Kanone/Explosion laut, MG und Place leiser
        assertTrue(peak(SfxSynth.render(SfxId.MG)) < peak(mortar) * 0.5f)
        assertTrue(peak(SfxSynth.render(SfxId.PLACE_WOOD)) < peak(mortar) * 0.6f)
        assertTrue(peak(SfxSynth.render(SfxId.CRACKLE)) in 0.05f..0.2f)
    }

    @Test
    fun smallExplosionTailEndsWithoutClick() {
        val f = SfxSynth.render(SfxId.EXPLOSION_SMALL)
        assertEquals(0f, f[f.size - 1], 1e-4f)
        val last10ms = peak(f.copyOfRange(f.size - Dsp.samplesFor(0.01), f.size))
        assertTrue(last10ms < 1e-3f, "Klick am Ende: $last10ms")
    }

    @Test
    fun wavHeaderIsValid() {
        val bank = SfxBank.synthesize()
        val w = bank.wav(SfxId.CLICK)
        assertEquals("RIFF", String(w, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(w, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(w, 36, 4, Charsets.US_ASCII))
        val dataLen = (w[40].toInt() and 0xFF) or ((w[41].toInt() and 0xFF) shl 8) or
            ((w[42].toInt() and 0xFF) shl 16) or ((w[43].toInt() and 0xFF) shl 24)
        assertEquals(bank.pcm(SfxId.CLICK).size * 2, dataLen)
        assertEquals(44 + dataLen, w.size)
    }

    @Test
    fun explosionHasDecayingEnergy() {
        val f = SfxSynth.render(SfxId.EXPLOSION)
        fun rms(from: Double, to: Double): Double {
            val a = Dsp.samplesFor(from); val b = Dsp.samplesFor(to)
            var s = 0.0
            for (i in a until b) s += f[i] * f[i]
            return kotlin.math.sqrt(s / (b - a))
        }
        assertTrue(rms(0.0, 0.2) > rms(0.8, 1.0))
        assertTrue(rms(0.8, 1.0) > rms(2.0, 2.2))
    }

    @Test
    fun cannonHasReverbTailAfterDryPart() {
        val f = SfxSynth.render(SfxId.CANNON)
        var tail = 0f
        for (i in Dsp.samplesFor(0.7) until Dsp.samplesFor(1.2)) tail = maxOf(tail, kotlin.math.abs(f[i]))
        assertTrue(tail > 0.01f, "kein Nachhall: $tail")
    }

    @Test
    fun fireLoopIsSeamless() {
        val f = SfxSynth.render(SfxId.FIRE_LOOP)
        val steps = FloatArray(f.size - 1) { kotlin.math.abs(f[it + 1] - f[it]) }.sortedArray()
        val p95 = steps[(steps.size * 0.95).toInt()]
        val seam = kotlin.math.abs(f[0] - f[f.size - 1])
        assertTrue(seam <= p95 * 1.5f, "Naht $seam gegen p95 $p95")
        val n = Dsp.samplesFor(0.005)
        val head = rms(f, 0, n)
        val tail = rms(f, f.size - n, f.size)
        assertTrue(head / tail in 0.4..2.5, "RMS Anfang/Ende: $head / $tail")
    }

    @Test
    fun biquadLowpassAttenuatesHighFrequency() {
        val bq = Biquad(FilterType.LOWPASS).also { it.set(500.0, 0.7) }
        val n = Dsp.SAMPLE_RATE
        var peakHigh = 0.0
        for (i in 0 until n) {
            val y = bq.process(StrictMath.sin(2 * Math.PI * 5000.0 * i / Dsp.SAMPLE_RATE))
            if (i > n / 2) peakHigh = maxOf(peakHigh, kotlin.math.abs(y))
        }
        assertTrue(peakHigh < 0.05, "Tiefpass dämpft 5 kHz: $peakHigh")
    }

    @Test
    fun noiseSourceIsDeterministicAndBounded() {
        val a = NoiseSource(42); val b = NoiseSource(42)
        repeat(1000) {
            val x = a.next()
            assertEquals(x, b.next())
            assertTrue(x >= -1.0 && x <= 1.0)
        }
    }

    @Test
    fun pcmClampsAndHandlesNan() {
        val s = Pcm.toShorts(floatArrayOf(2f, -2f, Float.NaN, 0.5f))
        assertEquals(32767, s[0].toInt())
        assertEquals(-32767, s[1].toInt())
        assertEquals(0, s[2].toInt())
    }

    private companion object {
        const val GOLDEN = """
            MORTAR=bba220af
            CANNON=51335da3
            MG=13ac94e3
            EXPLOSION=71e3d132
            EXPLOSION_SMALL=409c9437
            WOOD_BREAK=a370f733
            METAL_BREAK=2f84bbde
            PLACE_WOOD=77a0dd77
            PLACE_METAL=f7562687
            THUD=7226f33b
            CLICK=36df4f55
            RELOAD_PING=2d5a408a
            VICTORY=725bc60c
            DEFEAT=46f9c08d
            FIRE_LOOP=c17ffb7c
            CRACKLE=1c2c1dd9
        """
    }
}
