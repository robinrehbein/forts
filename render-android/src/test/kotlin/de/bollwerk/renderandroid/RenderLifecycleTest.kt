package de.bollwerk.renderandroid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RenderLifecycleTest {
    private class FakeLoop : RenderLoop {
        var shutdownCalls = 0
        var hangs = false
        var ended = false
        var exitAction: Runnable? = null
        override fun shutdown(): Boolean {
            shutdownCalls++
            if (!hangs) end()
            return ended
        }
        fun end() { ended = true; exitAction?.run(); exitAction = null }
        override fun runAfterExit(action: Runnable) { if (ended) action.run() else exitAction = action }
    }

    private val loops = ArrayList<FakeLoop>()
    private var timeouts = 0
    private val life = RenderLifecycle({ FakeLoop().also { loops.add(it) } }, { timeouts++ })

    private fun ready() { life.hasSession = true; life.surfaceReady = true; life.update() }

    @Test
    fun runsOnlyWithSessionAndSurfaceAndNeitherPause() {
        life.update()
        assertFalse(life.isRunning)
        life.hasSession = true; life.update()
        assertFalse(life.isRunning, "ohne Oberfläche")
        life.surfaceReady = true; life.update()
        assertTrue(life.isRunning)
        assertEquals(1, loops.size)
        life.update()
        assertEquals(1, loops.size, "kein zweiter Thread")
    }

    @Test
    fun hostPauseAndLifecyclePauseEachStopTheThread() {
        ready()
        life.hostPaused = true; life.update()
        assertFalse(life.isRunning)
        assertTrue(loops[0].ended)
        life.hostPaused = false; life.update()
        assertTrue(life.isRunning)
        assertEquals(2, loops.size)
        life.lifecyclePaused = true; life.update()
        assertFalse(life.isRunning, "ON_PAUSE ohne zerstörte Oberfläche (Multi-Window, Dialog)")
        life.hostPaused = true; life.lifecyclePaused = false; life.update()
        assertFalse(life.isRunning, "Host pausiert weiterhin")
        life.hostPaused = false; life.update()
        assertTrue(life.isRunning)
    }

    @Test
    fun surfaceDestroyedAndDetachStopTheThread() {
        ready()
        life.surfaceReady = false; life.update()
        assertFalse(life.isRunning)
        life.surfaceReady = true; life.update()
        assertTrue(life.isRunning)
        life.hasSession = false
        assertTrue(life.stop())
        assertFalse(life.isRunning)
        life.update()
        assertFalse(life.isRunning, "ohne Session kein Neustart")
    }

    @Test
    fun hangingThreadBlocksRestartAndDefersCleanupUntilItEnds() {
        ready()
        loops[0].hangs = true
        life.hostPaused = true
        life.update()
        assertEquals(1, timeouts)
        assertTrue(life.hasLingeringThread)
        // Resume, während der alte Thread noch hängt: kein zweiter Thread auf derselben Session
        life.hostPaused = false
        life.update()
        assertFalse(life.isRunning)
        assertEquals(1, loops.size)
        // Aufräumen (z. B. session.release) wartet auf das Ende des Threads
        var released = 0
        life.runWhenStopped { released++ }
        assertEquals(0, released)
        assertNotNull(loops[0].exitAction)
        loops[0].end()
        assertEquals(1, released)
    }

    @Test
    fun lingeringThreadThatEndsLaterAllowsRestart() {
        ready()
        loops[0].hangs = true
        life.hostPaused = true; life.update()
        loops[0].hangs = false // Treiber hat sich erholt: nächster shutdown() meldet das Ende
        life.hostPaused = false; life.update()
        assertTrue(life.isRunning)
        assertEquals(2, loops.size)
        assertFalse(life.hasLingeringThread)
    }

    @Test
    fun cleanupRunsImmediatelyWhenNothingHangs() {
        ready()
        life.stop()
        var released = 0
        life.runWhenStopped { released++ }
        assertEquals(1, released)
    }
}
