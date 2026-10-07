package de.bollwerk.render.android.audio

/** Audio-/Haptik-Einstellungen (vom App-Modul aus den Settings befüllt). Lautstärken 0..1. */
data class AudioSettings(
    val masterVolume: Float = 1f,
    val sfxVolume: Float = 1f,
    val muted: Boolean = false,
    val hapticsEnabled: Boolean = true,
) {
    /** Gesamtverstärkung für Effekte; 0 bei Stumm. */
    val effectGain: Float
        get() = if (muted) 0f else masterVolume.coerceIn(0f, 1f) * sfxVolume.coerceIn(0f, 1f)
}
