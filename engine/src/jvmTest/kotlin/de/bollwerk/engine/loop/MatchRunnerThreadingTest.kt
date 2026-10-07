package de.bollwerk.engine.loop

import de.bollwerk.engine.command.Command
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Das dokumentierte Drei-Thread-Modell: UI-Thread schiebt Commands ein, Sim-Thread treibt [MatchRunner.advance],
 * Render-Thread liest nur Snapshots. Nichts geht verloren, nichts kommt doppelt, Snapshots sind in sich konsistent.
 */
class MatchRunnerThreadingTest {
    @Test
    fun uiPushSimAdvanceAndRenderReadRunConcurrentlyWithoutLosingResults() {
        val runner = LoopRig.runner(record = false)
        val total = 200
        val heard = AtomicInteger()
        runner.addResultListener { heard.incrementAndGet() }

        val uiDone = AtomicBoolean(false)
        val simDone = AtomicBoolean(false)
        val ui = Thread {
            repeat(total / 10) {
                repeat(10) { runner.input.push(Command.Undo(0, 0)) } // abgelehnt: NOTHING_TO_UNDO, je Command ein Ergebnis
                Thread.sleep(1)
            }
            uiDone.set(true)
        }
        var rendered = 0
        var snapshots = 0
        var lastSeq = 0L
        var lastTick = 0L
        var failure: Throwable? = null
        val render = Thread {
            try {
                while (true) {
                    val done = simDone.get() // zuerst lesen: danach muss der letzte Snapshot noch gelesen werden
                    val s = runner.exchange.latest()
                    if (s != null && s.seq != lastSeq) {
                        assertTrue(s.seq > lastSeq, "seq grows")
                        assertTrue(s.tick >= lastTick, "tick never goes back")
                        assertTrue(s.nodeCount >= 8, "snapshot is complete (two forts)")
                        lastSeq = s.seq; lastTick = s.tick
                        snapshots++
                        rendered += s.commandResults.size
                    }
                    if (done && lastSeq == runner.publishedSeq) break
                }
            } catch (t: Throwable) { failure = t }
        }
        val sim = Thread {
            // Sim-Thread: feste Frame-Zeit, damit jeder Durchlauf genau einen Tick macht
            while (!uiDone.get() || heard.get() < total) runner.advance(1f / 60f)
            repeat(5) { runner.advance(1f / 60f) } // letzter Snapshot trägt die restlichen Ergebnisse
            // Ohne Tail-Flush nötig: ein ungelesener Snapshot trägt immer alle noch nicht zugestellten Ereignisse
            simDone.set(true)
        }
        render.start(); sim.start(); ui.start()
        ui.join(20_000); sim.join(20_000); render.join(20_000)
        failure?.let { throw it }
        assertEquals(total, heard.get(), "every pushed command produced exactly one result on the sim thread")
        assertTrue(snapshots > 2, "render thread saw $snapshots snapshots")
        assertEquals(total, rendered, "render thread received every result exactly once (an unread snapshot is reclaimed and carries everything)")
    }

    @Test
    fun pauseAndSpeedFromAnotherThreadTakeEffectAtTheNextAdvance() {
        val runner = LoopRig.runner(record = false)
        val t = Thread { runner.setPaused(true); runner.setSpeed(2f) }
        t.start(); t.join()
        assertEquals(0, runner.advance(1f / 60f))
        assertTrue(runner.paused)
        assertEquals(2f, runner.speed)
        Thread { runner.setPaused(false) }.also { it.start(); it.join() }
        assertEquals(2, runner.advance(1f / 60f))
    }
}
