package de.bollwerk.app.audio

import android.content.Context
import android.util.Log
import de.bollwerk.app.game.FrameListener
import de.bollwerk.app.game.GameAudio
import de.bollwerk.app.game.MatchAudio
import de.bollwerk.app.game.MatchSession
import de.bollwerk.app.game.toAudioSettings
import de.bollwerk.app.settings.AppSettings
import de.bollwerk.render.android.audio.AndroidVibrationDevice
import de.bollwerk.render.android.audio.Haptics
import de.bollwerk.render.android.audio.SfxPlayer
import de.bollwerk.render.android.audio.SoundPoolBackend
import kotlin.concurrent.thread

/**
 * [GameAudio] über SoundPool ([SoundPoolBackend], Sounds werden einmal je Prozess auf einem Hintergrund-Thread synthetisiert
 * und geladen) und den System-Vibrator. Noch nicht geladene Sounds bleiben still.
 */
class AndroidGameAudio(context: Context) : GameAudio {
    private val app = context.applicationContext
    private val backend = SoundPoolBackend(app)
    private val player = SfxPlayer(backend)
    private val haptics = Haptics(AndroidVibrationDevice(app))

    init {
        thread(name = "bollwerk-sfx-load", isDaemon = true) {
            try {
                backend.load()
            } catch (e: RuntimeException) {
                Log.w(TAG, "sound synthesis failed; playing without sound", e)
            }
        }
    }

    override fun listenerFor(session: MatchSession): FrameListener =
        MatchAudio(player, haptics, MatchAudio.idsFor(session.catalog), session.config.humanPlayerId)

    override fun apply(settings: AppSettings) {
        val a = settings.toAudioSettings()
        player.settings = a
        haptics.settings = a
    }

    override fun onPause() = player.pause()

    override fun onResume() = player.resume()

    override fun stopAll() = player.stopAll()

    private companion object {
        const val TAG = "AndroidGameAudio"
    }
}
