package de.bollwerk.app.game

import de.bollwerk.app.settings.AppSettings
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.render.android.audio.AudioContentIds
import de.bollwerk.render.android.audio.AudioSettings
import de.bollwerk.render.android.audio.FxAudioMapper
import de.bollwerk.render.android.audio.HapticsSink
import de.bollwerk.render.android.audio.SfxSink
import de.bollwerk.renderapi.Camera

/** Audio und Haptik des Spiels, App-weit (ein SoundPool); je Partie ein [FrameListener]. */
interface GameAudio {
    /** Hörer für die Spielfläche einer Partie (Render-Thread). */
    fun listenerFor(session: MatchSession): FrameListener

    /** Einstellungen anwenden (Lautstärke, Stumm bei 0, Haptik). */
    fun apply(settings: AppSettings)

    /** Activity im Hintergrund / zurück. */
    fun onPause()
    fun onResume()

    /** Partie verlassen: alle Stimmen und die Feuerschleife stoppen. */
    fun stopAll()
}

/** Abbildung der App-Einstellungen auf den Audio-Vertrag (`:render-android`). */
fun AppSettings.toAudioSettings(): AudioSettings = AudioSettings(
    masterVolume = 1f,
    sfxVolume = soundVolume.coerceIn(0f, 1f),
    muted = soundVolume <= 0f,
    hapticsEnabled = haptics,
)

/**
 * Audio einer Partie (Render-Thread): reicht jeden Frame an den [FxAudioMapper] weiter (Fx genau einmal je `seq`, Pan relativ
 * zur Kamera, Feuerschleife je Frame). Haptik und Sieg/Niederlage-Stinger beziehen sich auf den HUD-Spieler des Snapshots
 * (im Hotseat der aktive). Ist die Sim angehalten (`hud.paused`), ruht das Spiel-Audio.
 */
class MatchAudio(private val sfx: SfxSink, haptics: HapticsSink, ids: AudioContentIds, localPlayer: Int) : FrameListener {
    val mapper = FxAudioMapper(sfx, haptics, localPlayer, ids)

    override fun onFrame(snap: FrameSnapshot, fresh: Boolean, camera: Camera) {
        if (snap.hud.paused) {
            // Sim angehalten (Pause, Einstellungen, Übergabe wartet): Feuer-Teppich und Knackser verstummen, keine Fx.
            // `update` lässt die Schleife ausblenden und stoppt sie; nach dem Fortsetzen setzt der nächste neue Snapshot
            // die Zahl brennender Balken wieder.
            sfx.setFireCount(0)
            sfx.update()
            return
        }
        val lp = snap.hud.localPlayer
        if (lp >= 0) mapper.localPlayerId = lp
        mapper.process(snap, camera)
    }

    companion object {
        fun idsFor(catalog: GameCatalog): AudioContentIds = AudioContentIds.fromIds(catalog.materialIds, catalog.weaponIds)
    }
}
