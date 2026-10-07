package de.bollwerk.render.android.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class HapticStrength(val millis: Long, val amplitude: Int) {
    LIGHT(14, 60),
    MEDIUM(26, 120),
}

/** Haptik-Ziel (vom [FxAudioMapper] genutzt). */
interface HapticsSink {
    fun pulse(strength: HapticStrength)
}

/** Kein Haptik-Gerät. */
object NoHaptics : HapticsSink {
    override fun pulse(strength: HapticStrength) = Unit
}

/** Plattformzugriff auf den Vibrator (austauschbar für Tests). */
interface VibrationDevice {
    fun vibrate(millis: Long, amplitude: Int)
}

/**
 * Leichte Haptik: nur wenn [AudioSettings.hapticsEnabled] (unabhängig von Stumm),
 * mit Mindestabstand, damit MG-Salven nicht dauerhaft brummen.
 */
class Haptics(
    private val device: VibrationDevice,
    private val clock: AudioClock = AudioClock { System.nanoTime() / 1_000_000L },
    @Volatile var settings: AudioSettings = AudioSettings(),
    private val minGapMs: Long = 130,
) : HapticsSink {
    private var last = Long.MIN_VALUE / 2

    @Synchronized
    override fun pulse(strength: HapticStrength) {
        if (!settings.hapticsEnabled) return
        val now = clock.nowMs()
        if (now - last < minGapMs) return
        last = now
        device.vibrate(strength.millis, strength.amplitude)
    }
}

/** [VibrationDevice] über den System-Vibrator (braucht `android.permission.VIBRATE`, in diesem Modul deklariert). */
class AndroidVibrationDevice(context: Context) : VibrationDevice {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override fun vibrate(millis: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(VibrationEffect.createOneShot(millis, amplitude.coerceIn(1, 255)))
        } catch (_: SecurityException) {
            // Berechtigung fehlt: Haptik bleibt aus
        }
    }
}
