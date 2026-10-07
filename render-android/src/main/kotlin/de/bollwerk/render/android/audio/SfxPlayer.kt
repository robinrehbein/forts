package de.bollwerk.render.android.audio

import kotlin.math.exp

/** Ziel für Sound-Aufrufe (vom [FxAudioMapper] genutzt; [SfxPlayer] ist die Implementierung). */
interface SfxSink {
    /** [volume] 0..1 (vor Master/SFX), [pan] −1..1, [pitch] Wiedergaberate (1 = Original). */
    fun play(id: SfxId, volume: Float = 1f, pan: Float = 0f, pitch: Float = 1f)

    /** Zahl brennender Balken; steuert die Lautstärke der Feuerschleife. */
    fun setFireCount(count: Int)

    /** Pro Frame aufrufen: glättet Feuer-Lautstärke, wendet geänderte Einstellungen an. */
    fun update()
}

/**
 * Spielt die Sounds über ein [SfxBackend]: Voice-Limit je Sound ([SfxId.maxVoices], ältester wird gestoppt),
 * Mindestabstand ([SfxId.minGapMs]), Pan (Gleichleistung), Master-/SFX-Lautstärke, Stumm und eine Feuerschleife,
 * deren Lautstärke der Zahl brennender Balken folgt (Prototyp: `min(.09, n*.018)`, Zeitkonstante 0,3 s).
 */
class SfxPlayer(
    private val backend: SfxBackend,
    private val clock: AudioClock = AudioClock { System.nanoTime() / 1_000_000L },
    settings: AudioSettings = AudioSettings(),
) : SfxSink {
    @Volatile
    var settings: AudioSettings = settings

    // Voice-Warteschlangen je Sound als feste Arrays (älteste zuerst), ohne Allokation im Spielpfad
    private val voiceHandles = Array(SfxId.entries.size) { IntArray(SfxId.entries[it].maxVoices) }
    private val voiceEnds = Array(SfxId.entries.size) { LongArray(SfxId.entries[it].maxVoices) }
    private val voiceCount = IntArray(SfxId.entries.size)
    private val lastStart = LongArray(SfxId.entries.size) { Long.MIN_VALUE / 2 }
    private val lr = FloatArray(2)

    private var fireTarget = 0f
    private var fireLevel = 0f
    private var fireHandle = 0
    private var fireCount = 0
    private var crackleT = 0f
    private var crackleSeed = 0x2545F491
    private var fireLastMs = Long.MIN_VALUE

    /**
     * Zwischen [pause] und [resume]: keine neuen Stimmen, keine Feuerschleife. `SoundPool.autoPause` hält nur laufende Stimmen
     * an; ohne diese Sperre wären danach gestartete (z. B. Knackser aus einem veralteten Snapshot) sofort hörbar.
     */
    private var suspended = false

    /** Angehalten ([pause] ohne [resume])? */
    val isSuspended: Boolean @Synchronized get() = suspended

    /** Aktive (noch nicht abgelaufene) Stimmen eines Sounds; für Tests/Diagnose. */
    @Synchronized
    fun activeVoices(id: SfxId): Int {
        expire(id, clock.nowMs())
        return voiceCount[id.ordinal]
    }

    /** Aktuelle Feuerlautstärke 0..1 (vor Master/SFX). */
    val fireVolume: Float @Synchronized get() = fireLevel

    @Synchronized
    override fun play(id: SfxId, volume: Float, pan: Float, pitch: Float) {
        if (id.loop || suspended) return
        val gain = settings.effectGain * HEADROOM * volume.coerceIn(0f, 1f)
        if (gain <= 0.001f) return
        val now = clock.nowMs()
        val ord = id.ordinal
        if (id.minGapMs > 0 && now - lastStart[ord] < id.minGapMs) return
        expire(id, now)
        while (voiceCount[ord] >= id.maxVoices) backend.stop(removeOldest(ord))
        SpatialMixer.stereo(pan, lr)
        val rate = pitch.coerceIn(0.5f, 2f)
        val h = backend.play(id, gain * lr[0], gain * lr[1], rate, false)
        lastStart[ord] = now
        if (h != 0) {
            val c = voiceCount[ord]
            voiceHandles[ord][c] = h
            voiceEnds[ord][c] = now + (id.durationSec * 1000.0 / rate).toLong()
            voiceCount[ord] = c + 1
        }
    }

    private fun removeOldest(ord: Int): Int {
        val h = voiceHandles[ord][0]
        val c = voiceCount[ord] - 1
        for (i in 0 until c) { voiceHandles[ord][i] = voiceHandles[ord][i + 1]; voiceEnds[ord][i] = voiceEnds[ord][i + 1] }
        voiceCount[ord] = c
        return h
    }

    private fun expire(id: SfxId, now: Long) {
        val ord = id.ordinal
        while (voiceCount[ord] > 0 && voiceEnds[ord][0] <= now) removeOldest(ord)
    }

    @Synchronized
    override fun setFireCount(count: Int) {
        fireCount = count.coerceAtLeast(0)
        fireTarget = (fireCount / FIRE_SATURATION).coerceIn(0f, 1f)
    }

    @Synchronized
    override fun update() {
        if (suspended) return
        val now = clock.nowMs()
        val dt = if (fireLastMs == Long.MIN_VALUE) 0f else ((now - fireLastMs) / 1000f).coerceIn(0f, 1f)
        fireLastMs = now
        val k = if (dt <= 0f) 0f else 1f - exp(-dt / FIRE_TAU)
        fireLevel += (fireTarget - fireLevel) * k
        if (fireLevel < 0.003f && fireTarget == 0f) fireLevel = 0f
        updateCrackles(dt)
        val gain = settings.effectGain * HEADROOM * fireLevel * FIRE_MAX
        if (gain <= 0.0005f) {
            if (fireHandle != 0) { backend.stop(fireHandle); fireHandle = 0 }
            return
        }
        if (fireHandle == 0) fireHandle = backend.play(SfxId.FIRE_LOOP, gain, gain, 1f, true)
        else backend.setVolume(fireHandle, gain, gain)
    }

    /**
     * Knackser wie im Prototyp: Abstand `rnd(.03,.16) / min(3, .6 + n*.25)`, Gain `.05..0.17` (ohne Bezug zur
     * Teppich-Lautstärke), Tonhöhe variiert die Filterfrequenz (1800..4300 Hz). Pseudozufall nur fürs Audio.
     */
    private fun updateCrackles(dt: Float) {
        if (fireCount == 0) { crackleT = 0f; return }
        crackleT -= dt
        if (crackleT > 0f) return
        crackleT = (0.03f + 0.13f * nextUnit()) / minOf(3f, 0.6f + fireCount * 0.25f)
        val vol = (0.05f + 0.12f * nextUnit()) / SfxSynth.CRACKLE_REF_GAIN.toFloat()
        val pitch = 0.6f + 1.4f * nextUnit()
        play(SfxId.CRACKLE, vol, (nextUnit() - 0.5f) * 0.6f, pitch)
    }

    private fun nextUnit(): Float {
        crackleSeed = crackleSeed * 1664525 + 1013904223
        return (crackleSeed ushr 8) / 16777216f
    }

    /**
     * Hält alle Stimmen an (Activity `onPause`, Pause-Dialog) und sperrt neue bis [resume]; die Feuerschleife wird gestoppt.
     * Feuerzustand (Zahl brennender Balken, Pegel) bleibt erhalten, nach [resume] setzt [update] die Schleife neu auf.
     */
    @Synchronized
    fun pause() {
        suspended = true
        backend.pause()
        if (fireHandle != 0) { backend.stop(fireHandle); fireHandle = 0 }
    }

    /** Setzt nach [pause] fort (Activity `onResume`, Spiel läuft wieder). */
    @Synchronized
    fun resume() {
        suspended = false
        fireLastMs = Long.MIN_VALUE // die Pausenzeit zählt nicht als Frame-Zeit
        backend.resume()
    }

    /** Stoppt alle Stimmen (z. B. beim Verlassen der Partie). */
    @Synchronized
    fun stopAll() {
        for (ord in voiceCount.indices) while (voiceCount[ord] > 0) backend.stop(removeOldest(ord))
        if (fireHandle != 0) { backend.stop(fireHandle); fireHandle = 0 }
        fireLevel = 0f
        fireTarget = 0f
        fireCount = 0
    }

    @Synchronized
    fun release() {
        stopAll()
        backend.release()
    }

    companion object {
        /** Brennende Balken, ab denen die Schleife ihre Höchstlautstärke erreicht. */
        const val FIRE_SATURATION = 5f
        const val FIRE_TAU = 0.3f

        /** Prototyp: Feuerbett-Gain `min(.09, n*.018)`, also 0,09 bei [FIRE_SATURATION] Balken (Schleife hat Gain 1). */
        const val FIRE_MAX = 0.09f

        /**
         * Master-Headroom vor der Stereo-Aufteilung (Prototyp: `out.gain = 0.42` plus Kompressor; hier ohne Kompressor
         * 0,5): hält jeden Kanal unter 1 (max. 0,5 × √2 × 0,99), sodass Gleichleistungs-Panning nicht klemmt.
         */
        const val HEADROOM = 0.5f
    }
}
