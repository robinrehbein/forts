package de.bollwerk.render.android.audio

/**
 * Reine DSP-Bausteine (kein Android, keine Wall-Clock, kein globaler Zufall): Oszillatoren, Rauschen, Biquad-Filter,
 * Hüllkurven, Hall und WAV-Kodierung. Alle Funktionen sind deterministisch; Trigonometrie/Exponentialfunktion laufen
 * über `StrictMath`, damit die Bytes auf jeder JVM/ART identisch sind.
 */
object Dsp {
    const val SAMPLE_RATE: Int = 22_050
    private const val TWO_PI = 2.0 * Math.PI

    fun samplesFor(seconds: Double): Int = Math.round(seconds * SAMPLE_RATE).toInt()
}

/** Deterministisches Weißrauschen (xorshift64*), Wertebereich −1..1. */
class NoiseSource(seed: Long) {
    private var s: Long = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    fun nextLong(): Long {
        s = s xor (s ushr 12)
        s = s xor (s shl 25)
        s = s xor (s ushr 27)
        return s * 0x2545F4914F6CDD1DL
    }

    /** −1..1. */
    fun next(): Double = (nextLong() ushr 11).toDouble() / (1L shl 52).toDouble() - 1.0

    /** 0..1. */
    fun unit(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()

    fun range(lo: Double, hi: Double): Double = lo + (hi - lo) * unit()
}

enum class FilterType { LOWPASS, HIGHPASS, BANDPASS }

enum class Wave { SINE, TRIANGLE, SQUARE }

/** Biquad nach RBJ-Cookbook (Direktform I); Bandpass mit konstanter 0-dB-Spitze wie WebAudio. */
class Biquad(private val type: FilterType, private val sampleRate: Int = Dsp.SAMPLE_RATE) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun set(freq: Double, q: Double) {
        val f = freq.coerceIn(20.0, sampleRate * 0.45)
        val qq = if (q < 0.05) 0.05 else q
        val w0 = 2.0 * Math.PI * f / sampleRate
        val c = StrictMath.cos(w0)
        val alpha = StrictMath.sin(w0) / (2.0 * qq)
        val a0 = 1.0 + alpha
        when (type) {
            FilterType.LOWPASS -> { b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2 }
            FilterType.HIGHPASS -> { b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2 }
            FilterType.BANDPASS -> { b0 = alpha; b1 = 0.0; b2 = -alpha }
        }
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha) / a0
        b0 /= a0; b1 /= a0; b2 /= a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

/** Zeitliche Mischfläche: Töne und gefiltertes Rauschen werden addiert (Mono, Float, −1..1 nach [normalize]). */
class Mix(val length: Int, private val noise: NoiseSource) {
    val data = FloatArray(length)

    /**
     * Oszillator mit exponentiellem Frequenzverlauf [f0]→[f1] und Hüllkurve (Anstieg [att], exponentieller Abfall bis
     * [dur]) ab Zeit [t0] (s) in [dest].
     */
    fun tone(
        t0: Double, dur: Double, wave: Wave, f0: Double, f1: Double, gain: Double,
        att: Double = 0.006, dest: FloatArray = data,
    ) {
        val start = Dsp.samplesFor(t0)
        val n = Dsp.samplesFor(dur)
        var phase = 0.0
        val ratio = StrictMath.pow(maxOf(20.0, f1) / maxOf(20.0, f0), 1.0 / maxOf(1, n - 1))
        var f = f0
        for (i in 0 until n) {
            val idx = start + i
            if (idx >= dest.size) break
            phase += f / Dsp.SAMPLE_RATE
            phase -= StrictMath.floor(phase)
            val s = when (wave) {
                Wave.SINE -> StrictMath.sin(phase * 2.0 * Math.PI)
                Wave.TRIANGLE -> if (phase < 0.5) 4 * phase - 1 else 3 - 4 * phase
                Wave.SQUARE -> if (phase < 0.5) 1.0 else -1.0
            }
            dest[idx] += (s * envelope(i.toDouble() / Dsp.SAMPLE_RATE, dur, gain, att)).toFloat()
            f *= ratio
        }
    }

    /** Gefiltertes Rauschen mit exponentiell gleitender Filterfrequenz [f0]→[f1]. */
    fun noise(
        t0: Double, dur: Double, type: FilterType, f0: Double, f1: Double, gain: Double,
        q: Double = 1.0, att: Double = 0.004, dest: FloatArray = data,
    ) {
        val start = Dsp.samplesFor(t0)
        val n = Dsp.samplesFor(dur)
        val bq = Biquad(type)
        val lo = maxOf(30.0, f1)
        for (i in 0 until n) {
            val idx = start + i
            if (idx >= dest.size) break
            if (i and 15 == 0) {
                val u = i.toDouble() / maxOf(1, n - 1)
                bq.set(f0 * StrictMath.pow(lo / f0, u), q)
            }
            val s = bq.process(noise.next())
            dest[idx] += (s * envelope(i.toDouble() / Dsp.SAMPLE_RATE, dur, gain, att)).toFloat()
        }
    }

    /** Wie WebAudio: 0,0001 → gain (exp) in [att], danach exponentiell zurück auf 0,0001 bei [dur]. */
    private fun envelope(t: Double, dur: Double, gain: Double, att: Double): Double {
        val floor = 0.0001
        val g = maxOf(gain, floor * 2)
        val a = minOf(att, dur * 0.5)
        val e = if (t < a) floor * StrictMath.pow(g / floor, t / a)
        else g * StrictMath.pow(floor / g, (t - a) / maxOf(1e-6, dur - a))
        // letzte 2 ms linear auf 0, damit kein Knacken am Ende bleibt
        val tail = 0.002
        return if (t > dur - tail) e * maxOf(0.0, (dur - t) / tail) else e
    }

    /** Skaliert mit [gain]; überschreitet die Spitze danach [ceiling], wird nur dieser Puffer auf [ceiling] gesenkt. */
    fun finish(gain: Float, ceiling: Float): FloatArray {
        var m = 0f
        for (v in data) { val a = if (v < 0) -v else v; if (a > m) m = a }
        var k = gain
        if (m * k > ceiling) k = ceiling / m
        for (i in data.indices) data[i] *= k
        return data
    }
}

/** Schroeder-Hall (4 Kammfilter + 2 Allpässe) auf einem Mono-Puffer; Ergebnis hat [extraSeconds] Nachhall dazu. */
object Reverb {
    fun process(input: FloatArray, extraSeconds: Double, feedback: Double = 0.88): FloatArray {
        val out = FloatArray(input.size + Dsp.samplesFor(extraSeconds))
        val combMs = doubleArrayOf(29.7, 37.1, 41.1, 43.7)
        for (ms in combMs) {
            val d = Dsp.samplesFor(ms / 1000.0)
            val buf = DoubleArray(d)
            var p = 0
            for (i in out.indices) {
                val x = if (i < input.size) input[i].toDouble() else 0.0
                val y = buf[p]
                buf[p] = x + y * feedback
                p = if (p + 1 == d) 0 else p + 1
                out[i] += (y * 0.25).toFloat()
            }
        }
        for (ms in doubleArrayOf(5.0, 1.7)) {
            val d = Dsp.samplesFor(ms / 1000.0)
            val buf = DoubleArray(d)
            var p = 0
            for (i in out.indices) {
                val x = out[i].toDouble()
                val b = buf[p]
                val y = -0.5 * x + b
                buf[p] = x + 0.5 * y
                p = if (p + 1 == d) 0 else p + 1
                out[i] = y.toFloat()
            }
        }
        return out
    }
}

/** 16-Bit-PCM-Hilfen und WAV-Kodierung (RIFF, mono, little endian). */
object Pcm {
    fun toShorts(data: FloatArray): ShortArray {
        val out = ShortArray(data.size)
        for (i in data.indices) {
            val v = data[i]
            val c = if (v > 1f) 1f else if (v < -1f) -1f else if (v != v) 0f else v
            out[i] = Math.round(c * 32767f).toShort()
        }
        return out
    }

    fun toWav(pcm: ShortArray, sampleRate: Int = Dsp.SAMPLE_RATE): ByteArray {
        val dataLen = pcm.size * 2
        val b = ByteArray(44 + dataLen)
        fun put32(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte() }
        fun put16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        fun tag(o: Int, s: String) { for (i in 0 until 4) b[o + i] = s[i].code.toByte() }
        tag(0, "RIFF"); put32(4, 36 + dataLen); tag(8, "WAVE")
        tag(12, "fmt "); put32(16, 16); put16(20, 1); put16(22, 1)
        put32(24, sampleRate); put32(28, sampleRate * 2); put16(32, 2); put16(34, 16)
        tag(36, "data"); put32(40, dataLen)
        for (i in pcm.indices) put16(44 + i * 2, pcm[i].toInt())
        return b
    }
}
