package de.bollwerk.app.game

import de.bollwerk.app.match.EndReason
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.MatchResult
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.tools.PointerPhase
import de.bollwerk.renderapi.Camera

/**
 * Alles, was eine laufende Partie für die Spielfläche braucht: [controller] (Sim-Thread), die geteilte [camera] (UI-Thread,
 * Änderungen unter ihrem Monitor), [snapshotSource] und [overlaySource] für den Render-Thread, [input] (Gesten) und
 * [director] (Ausschnitte). Reine Kotlin-Objekte; die Android-Schicht hängt sie nur an die `GameSurfaceView`.
 */
class GameRuntime(
    val controller: GameController,
    frameListener: FrameListener? = null,
) {
    val session: MatchSession get() = controller.session
    val camera: Camera = Camera()
    val director: CameraDirector = CameraDirector(camera, session.map).also { it.configure() }
    val snapshotSource: GameSnapshotSource = GameSnapshotSource(
        controller.runner.exchange, session.tables, session.map, camera, frameListener,
        simMillis = { controller.lastSimMillis },
    )
    val overlaySource: OverlayBridge = OverlayBridge(camera) { controller.toolOverlay }

    private val sink = object : GestureSink {
        override fun pointer(phase: PointerPhase, worldX: Float, worldY: Float, pickRadiusM: Float, gestureId: Long) {
            controller.pointer(phase, worldX, worldY, pickRadiusM, gestureId)
        }

        override fun longPress(worldX: Float, worldY: Float, pickRadiusM: Float, gestureId: Long) {
            controller.longPress(worldX, worldY, pickRadiusM, gestureId)
        }

        override fun cancelGesture() { controller.cancelGesture() }

        override fun gestureConsumed(gestureId: Long): Boolean? = controller.gestureConsumed(gestureId)

        override fun doubleTap(worldX: Float, worldY: Float) = director.toggle(controller.toolState.value.localPlayer)
    }

    val input: InputController = InputController(camera, sink)

    fun start() = controller.start()

    fun stop() = controller.stop()
}

/** Abbildung Sim-Ergebnis → App-Ergebnis (Ergebnis-Screen, Mockup 7). */
object MatchResults {
    /**
     * @param stats Kennzahlen je Spieler; der Bericht zeigt die des Banner-Spielers (gegen KI der Mensch, im Hotseat der Sieger).
     */
    fun from(config: MatchConfig, result: GameResult, statsFor: (Int) -> de.bollwerk.app.match.MatchStats): MatchResult? {
        val r = when (result) {
            GameResult.Ongoing -> return null
            GameResult.Draw -> MatchResult(config, winnerPlayerId = -1, reason = EndReason.DRAW)
            is GameResult.Winner -> {
                val human = config.humanPlayerId
                val reason = when (result.reason) {
                    WinReason.SURRENDER -> EndReason.SURRENDER
                    WinReason.TIMEOUT -> EndReason.TIMEOUT
                    WinReason.REACTOR_DESTROYED ->
                        if (config.mode == GameMode.HOTSEAT || result.playerId == human) EndReason.ENEMY_REACTOR_DESTROYED
                        else EndReason.OWN_REACTOR_DESTROYED
                }
                MatchResult(config, winnerPlayerId = result.playerId, reason = reason)
            }
        }
        return r.copy(stats = statsFor(r.bannerPlayerId))
    }
}
