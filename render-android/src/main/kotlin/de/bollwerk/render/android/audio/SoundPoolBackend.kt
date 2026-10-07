package de.bollwerk.render.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * [SfxBackend] auf Basis von [SoundPool]. Der Konstruktor ist billig (nur der Pool); die teure Arbeit (Synthese, WAVs
 * nach `cacheDir/bollwerk_sfx` schreiben, in den Pool laden) macht [load] und gehört auf einen Hintergrund-Thread
 * (z. B. `Dispatchers.IO`), nie auf den Main-Thread. Sounds, die noch nicht fertig geladen sind, werden still übersprungen.
 *
 * Streams: [MAX_STREAMS] liegt unter der Summe der `maxVoices`; das ist beabsichtigt (selten alle gleichzeitig). Bei
 * vollem Pool verdrängt SoundPool die niedrigste [SfxId.priority] zuerst, die Feuerschleife (3) also zuletzt.
 */
class SoundPoolBackend(private val context: Context) : SfxBackend {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val soundIds = IntArray(SfxId.entries.size)

    // 1 = geladen; Schreiber ist der Load-Callback-Thread, Leser der Render-Thread
    private val ready = AtomicIntegerArray(SfxId.entries.size)

    /** Synthetisiert (falls nötig) und lädt alle Sounds. Blockiert: auf einem Hintergrund-Thread aufrufen. */
    fun load(bank: SfxBank = SfxBank.synthesize()) {
        val dir = File(context.cacheDir, "bollwerk_sfx").apply { mkdirs() }
        val bySoundId = HashMap<Int, Int>()
        pool.setOnLoadCompleteListener { _, sid, status ->
            val ord = synchronized(bySoundId) { bySoundId[sid] }
            if (ord != null && status == 0) ready.set(ord, 1)
        }
        for (id in SfxId.entries) {
            val f = File(dir, id.name.lowercase() + ".wav")
            try {
                f.writeBytes(bank.wav(id))
                synchronized(bySoundId) {
                    val sid = pool.load(f.absolutePath, 1)
                    soundIds[id.ordinal] = sid
                    bySoundId[sid] = id.ordinal
                }
            } catch (_: java.io.IOException) {
                // Sound bleibt stumm
            }
        }
    }

    override fun play(id: SfxId, left: Float, right: Float, rate: Float, loop: Boolean): Int {
        if (ready.get(id.ordinal) == 0) return 0
        return pool.play(soundIds[id.ordinal], left.coerceIn(0f, 1f), right.coerceIn(0f, 1f), id.priority,
            if (loop) -1 else 0, rate.coerceIn(0.5f, 2f))
    }

    override fun setVolume(handle: Int, left: Float, right: Float) {
        if (handle != 0) pool.setVolume(handle, left.coerceIn(0f, 1f), right.coerceIn(0f, 1f))
    }

    override fun stop(handle: Int) {
        if (handle != 0) pool.stop(handle)
    }

    override fun pause() = pool.autoPause()

    override fun resume() = pool.autoResume()

    override fun release() = pool.release()

    private companion object {
        const val MAX_STREAMS = 24
    }
}
