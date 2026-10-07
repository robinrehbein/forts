package de.bollwerk.app.game

import de.bollwerk.app.match.MatchConfig
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.renderapi.InteractionMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Durchstich ohne Android: Touch (synthetisch) → InputController → GameController (Werkzeuge im Sim-Schritt) →
 * LocalInputSource → Sim, dazu Overlay für den Render-Thread und Kennzahlen.
 */
class GameRuntimeIntegrationTest {
    private val clock = FakeClock()
    private val runtime = GameRuntime(GameController(MatchSessions.create(HudFixtures.db, MatchConfig(seed = 11)), clock))
    private val c = runtime.controller
    private val e = TouchSample()
    private var t = 0L

    @AfterTest
    fun stop() = runtime.stop()

    private fun frames(n: Int) = repeat(n) { clock.advanceMs(1000.0 / 60.0); c.stepOnce() }

    private fun screenOf(wx: Float, wy: Float): Pair<Float, Float> = synchronized(runtime.camera) {
        runtime.camera.worldToScreenX(wx) to runtime.camera.worldToScreenY(wy)
    }

    private fun touch(action: Int, x: Float, y: Float, count: Int = 1) {
        t += 16
        runtime.input.onTouch(e.set(action, t, count, x, y))
        frames(1)
    }

    /** Oberster eigener Knoten von Spieler 0 (Startfestung). */
    private fun topOwnNode(): Pair<Float, Float> {
        val n = c.runner.state.nodeView
        var best = -1
        for (i in 0 until n.size) if (n.isAlive(i) && n.owner(i) == 0 && (best < 0 || n.y(i) < n.y(best))) best = i
        return n.x(best) to n.y(best)
    }

    @Test
    fun dragFromAnOwnNodeBuildsABeam() {
        runtime.input.density = 2f
        runtime.director.onViewport(1600f, 720f, 2f)
        runtime.director.showOwnFort(0)
        c.stepOnce()
        c.selectTool(ToolSelection.Material(HudFixtures.material("wood")))
        frames(1)
        val (nx, ny) = topOwnNode()
        val (sx, sy) = screenOf(nx, ny)
        val (ex, ey) = screenOf(nx + 0.5f, ny - 3f)
        val beamsBefore = c.runner.state.beamView.aliveCount
        touch(TouchSample.DOWN, sx, sy)
        touch(TouchSample.MOVE, (sx + ex) / 2, (sy + ey) / 2)
        touch(TouchSample.MOVE, ex, ey)
        val during = runtime.overlaySource.current()
        assertEquals(InteractionMode.BUILD, during.mode)
        val ghost = assertNotNull(during.ghost, "ghost beam while dragging")
        assertTrue(ghost.valid, "ghost is valid: ${ghost.reason}")
        assertNotNull(during.loupe, "loupe while the finger is down")
        touch(TouchSample.UP, ex, ey, count = 0)
        frames(3)
        assertEquals(beamsBefore + 1, c.runner.state.beamView.aliveCount)
        assertEquals(1, c.stats.statsFor(0, 0).beamsBuilt)
        frames(6) // Zurück-Zustand kommt im HUD-Takt
        assertTrue(c.toolState.value.canUndo)
    }

    @Test
    fun aimDragShowsTheTrajectoryAndSetsTheAim() {
        runtime.input.density = 2f
        runtime.director.onViewport(1600f, 720f, 2f)
        c.stepOnce()
        c.enterAimMode()
        frames(1)
        assertEquals(ToolMode.AIM, c.toolState.value.mode)
        val ref = c.toolState.value.weaponRef
        assertTrue(ref >= 0)
        // Irgendwo ziehen: Zugrichtung = Schussrichtung (nach rechts oben)
        touch(TouchSample.DOWN, 800f, 500f)
        touch(TouchSample.MOVE, 900f, 420f)
        touch(TouchSample.MOVE, 1000f, 340f)
        val o = runtime.overlaySource.current()
        assertEquals(InteractionMode.AIM, o.mode)
        assertTrue((o.trajectory?.count ?: 0) > 2, "trajectory preview while aiming")
        touch(TouchSample.UP, 1000f, 340f, count = 0)
        frames(2)
        val s = c.runner.state
        val id = s.deviceView.resolve(ref)
        val angle = s.deviceView.aimAngle(id)
        assertTrue(angle > 0.3f && angle < 1.2f, "aim follows the drag direction, got $angle")
    }
}
