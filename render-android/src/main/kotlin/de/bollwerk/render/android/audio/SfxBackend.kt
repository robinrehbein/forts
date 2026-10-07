package de.bollwerk.render.android.audio

/** Abspiel-Backend (SoundPool in der App, Fake in Tests). Handles: 0 = nicht gestartet. */
interface SfxBackend {
    fun play(id: SfxId, left: Float, right: Float, rate: Float, loop: Boolean): Int
    fun setVolume(handle: Int, left: Float, right: Float)
    fun stop(handle: Int)
    /** Lifecycle: alle Streams anhalten bzw. fortsetzen (Activity onPause/onResume). */
    fun pause()
    fun resume()
    fun release()
}

/** Monotone Zeit in ms (injizierbar, damit Voice-Limit und Glättung testbar sind). */
fun interface AudioClock {
    fun nowMs(): Long
}
