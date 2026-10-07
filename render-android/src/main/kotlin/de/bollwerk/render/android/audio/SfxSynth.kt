package de.bollwerk.render.android.audio

/**
 * Prozedurale Synthese der Sounds (Port der WebAudio-Funktionen aus `physik-spielplatz.html`, Abschnitt 6b).
 * Deterministisch: Rauschen kommt aus einem je [SfxId] fest geseedeten [NoiseSource]. Einmal beim Start rendern
 * ([SfxBank]), nie im Spielpfad.
 */
object SfxSynth {
    /**
     * Gemeinsamer Faktor auf alle Prototyp-Gains (der rohe Mortar-Spitzenwert ist ~1,0; so bleibt Luft nach oben).
     * Nicht je Sound normalisiert: die Lautstärken zueinander entsprechen dem Prototyp.
     */
    const val SHARED_GAIN = 0.9f

    /** Obergrenze für die Spitze eines Sounds (Sicherheitsnetz gegen Clipping bei Hall-Summen). */
    private const val PEAK_CEILING = 0.98f

    /** Referenz-Gain der Knackser (Prototyp: .05 + rnd*.12 → max .17); der Player skaliert je Aufruf herunter. */
    const val CRACKLE_REF_GAIN = 0.17

    /** Gerenderter Mono-Puffer mit den absoluten Prototyp-Gains × [SHARED_GAIN]; Länge = [SfxId.durationSec] * Abtastrate. */
    fun render(id: SfxId): FloatArray {
        val len = Dsp.samplesFor(id.durationSec)
        val rng = NoiseSource(0x5EED_0000L + id.ordinal * 7919L + 1)
        val m = Mix(len, rng)
        val lp = FilterType.LOWPASS
        val hp = FilterType.HIGHPASS
        val bp = FilterType.BANDPASS
        when (id) {
            SfxId.MORTAR -> {
                m.tone(0.0, .32, Wave.SINE, 120.0, 38.0, .9)
                m.noise(0.0, .22, lp, 900.0, 120.0, .55)
                m.noise(0.0, .08, bp, 2400.0, 900.0, .12, 2.0)
            }
            SfxId.CANNON -> {
                val dry = Dsp.samplesFor(0.6) + 1
                m.noise(0.0, .09, hp, 3000.0, 1200.0, .7)
                m.tone(0.0, .5, Wave.SINE, 90.0, 32.0, .9)
                val send = FloatArray(len)
                m.noise(0.0, .6, lp, 1600.0, 160.0, .5, dest = send)
                for (i in 0 until dry.coerceAtMost(len)) m.data[i] += send[i]
                addReverb(m, send, id.durationSec - 0.6, 1.3f)
            }
            SfxId.MG -> {
                m.noise(0.0, .035, bp, 2600.0, 1800.0, .32, 3.0)
                m.tone(0.0, .03, Wave.SQUARE, 180.0, 90.0, .06)
            }
            SfxId.EXPLOSION -> explosion(m, id, 1.0, 1.5)
            SfxId.EXPLOSION_SMALL -> explosion(m, id, 0.4, 1.2)
            SfxId.WOOD_BREAK -> {
                for (i in 0 until 4) {
                    m.noise(i * .022 + rng.unit() * .015, .05, bp, 1900.0 + rng.unit() * 1400.0, 900.0, .35, 4.0)
                }
                m.tone(0.0, .14, Wave.TRIANGLE, 190.0, 90.0, .25)
            }
            SfxId.METAL_BREAK -> {
                for (f in doubleArrayOf(523.0, 1347.0, 2209.0, 3011.0)) {
                    m.tone(0.0, .45 + rng.unit() * .3, Wave.SINE, f * (0.97 + rng.unit() * .06), f * .985, .1)
                }
                m.noise(0.0, .12, hp, 4000.0, 2500.0, .25)
            }
            SfxId.PLACE_WOOD -> {
                m.tone(0.0, .12, Wave.TRIANGLE, 240.0, 150.0, .35)
                m.noise(0.0, .06, bp, 1300.0, 700.0, .25, 2.0)
            }
            SfxId.PLACE_METAL -> {
                m.tone(0.0, .3, Wave.TRIANGLE, 330.0, 300.0, .28)
                m.tone(0.0, .22, Wave.SINE, 862.0, 850.0, .1)
                m.noise(0.0, .05, hp, 3000.0, 2000.0, .14)
            }
            SfxId.THUD -> {
                m.tone(0.0, .18, Wave.SINE, 95.0, 45.0, .35)
                m.noise(0.0, .14, lp, 700.0, 150.0, .25)
            }
            SfxId.CLICK -> m.tone(0.0, .05, Wave.SQUARE, 1200.0, 900.0, .05, att = .002)
            SfxId.RELOAD_PING -> {
                m.tone(0.0, .2, Wave.SINE, 1568.0, 1568.0, .5, att = .004)
                m.tone(0.0, .14, Wave.SINE, 3136.0, 3136.0, .12, att = .004)
            }
            SfxId.VICTORY -> {
                // aufsteigender Dur-Akkord C5-E5-G5-C6, dann gehaltener Schlussakkord
                val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
                for (i in notes.indices) {
                    m.tone(i * .16, .5, Wave.TRIANGLE, notes[i], notes[i], .45, att = .01)
                    m.tone(i * .16, .5, Wave.SINE, notes[i] * 2, notes[i] * 2, .12, att = .01)
                }
                for (f in notes) m.tone(.64, .95, Wave.TRIANGLE, f, f, .3, att = .02)
            }
            SfxId.DEFEAT -> {
                // absteigende Moll-Figur A4-F4-D4-A3 mit dumpfem Schlussschlag
                val notes = doubleArrayOf(440.0, 349.23, 293.66, 220.0)
                for (i in notes.indices) {
                    m.tone(i * .22, .6, Wave.TRIANGLE, notes[i], notes[i] * .97, .45, att = .01)
                }
                m.tone(.88, .9, Wave.SINE, 110.0, 55.0, .6, att = .01)
                m.noise(.88, .5, lp, 500.0, 90.0, .3)
            }
            SfxId.FIRE_LOOP -> fireLoop(m, rng)
            SfxId.CRACKLE -> m.noise(0.0, .05, bp, 3000.0, 1200.0, CRACKLE_REF_GAIN, 3.0)
        }
        return m.finish(SHARED_GAIN, PEAK_CEILING)
    }

    private fun explosion(m: Mix, id: SfxId, k: Double, noiseDur: Double) {
        val len = m.length
        val send = FloatArray(len)
        m.noise(0.0, noiseDur, FilterType.LOWPASS, 2200.0, 110.0, minOf(1.0, .55 + .25 * k), .7, .006, dest = send)
        for (i in 0 until Dsp.samplesFor(noiseDur).coerceAtMost(len)) m.data[i] += send[i]
        m.tone(0.0, .7, Wave.SINE, 70.0, 26.0, .8 * minOf(1.0, k))
        addReverb(m, send, id.durationSec - noiseDur, 1.3f)
    }

    private fun addReverb(m: Mix, send: FloatArray, tail: Double, wet: Float) {
        val r = Reverb.process(send, tail)
        // Hall-Schwanz in den letzten 150 ms ausblenden, damit der Puffer ohne Klick auf 0 endet
        val fade = Dsp.samplesFor(0.15)
        for (i in m.data.indices) {
            if (i >= r.size) break
            val toEnd = m.data.size - i
            val f = if (toEnd < fade) toEnd.toFloat() / fade else 1f
            m.data[i] += r[i] * wet * f
        }
    }

    /**
     * Feuer-Teppich: Rauschen durch Bandpass 900 Hz mit Gain 1 (der Player setzt die Lautstärke wie der Prototyp auf
     * `min(.09, n*.018)`). Nahtlos: die ersten Samples werden mit der Fortsetzung desselben Filters nach dem Ende
     * überblendet (bei w=0 ist Sample 0 die direkte Fortsetzung von Sample n-1). Knackser sind separate [SfxId.CRACKLE].
     */
    private fun fireLoop(m: Mix, rng: NoiseSource) {
        val n = m.length
        val xf = Dsp.samplesFor(0.05)
        val bq = Biquad(FilterType.BANDPASS).also { it.set(900.0, .6) }
        val ext = FloatArray(n + xf)
        for (i in ext.indices) ext[i] = bq.process(rng.next()).toFloat()
        for (i in 0 until n) m.data[i] = ext[i]
        for (i in 0 until xf) {
            val w = i.toFloat() / xf
            m.data[i] = ext[i] * w + ext[n + i] * (1f - w)
        }
    }
}

/** Beim Start einmal gerenderte Sounds als PCM und WAV; deterministisch. */
class SfxBank private constructor(private val pcm: Map<SfxId, ShortArray>) {
    fun pcm(id: SfxId): ShortArray = pcm.getValue(id)
    fun wav(id: SfxId): ByteArray = Pcm.toWav(pcm(id))

    companion object {
        fun synthesize(): SfxBank = SfxBank(SfxId.entries.associateWith { Pcm.toShorts(SfxSynth.render(it)) })
    }
}
