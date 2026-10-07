package de.bollwerk.engine.loop

import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import de.bollwerk.engine.view.SnapshotExchange
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Zwei Threads gegen den Dreifachpuffer (ohne Sim): Der Schreiber hängt je Snapshot ein nummeriertes Ereignis an, der Leser
 * liest in unregelmäßigem Takt. Jedes Ereignis muss genau einmal und in Reihenfolge ankommen, auch das letzte nach Ende des Stroms.
 */
class SnapshotExchangeStressTest {
    private fun runOnce(total: Int, readerEvery: Int) {
        val ex = SnapshotExchange()
        val writerDone = AtomicBoolean(false)
        val delivered = ArrayList<Long>(total)
        var failure: Throwable? = null
        var finalSeq = 0L

        val reader = Thread {
            try {
                var last = 0L
                var spin = 0
                while (true) {
                    val done = writerDone.get()
                    val s = ex.latest()
                    if (s != null && s.seq != last) {
                        assertTrue(s.seq > last, "seq grows: ${s.seq} after $last")
                        last = s.seq
                        for (e in s.fx) delivered.add(e.tick)
                    }
                    if (done && last == finalSeq) break
                    if (++spin % readerEvery == 0) Thread.yield()
                }
            } catch (t: Throwable) { failure = t }
        }
        val writer = Thread {
            var seq = 0L
            for (i in 0 until total) {
                val s: FrameSnapshot = ex.beginWrite()
                if (s.carryFx) s.carryFx = false else s.fx.clear()
                s.fx.add(FxEvent.Hit(i.toLong(), 0f, 0f, HitTarget.TERRAIN, -1, 1f, 0))
                s.seq = ++seq
                ex.publish()
                if (i % 7 == 0) Thread.yield()
            }
            finalSeq = seq
            writerDone.set(true)
        }
        reader.start(); writer.start()
        writer.join(30_000); reader.join(30_000)
        failure?.let { throw it }
        assertEquals(total, delivered.size, "every event exactly once")
        assertEquals((0L until total.toLong()).toList(), delivered, "in order")
    }

    @Test
    fun noEventIsLostDuplicatedOrReorderedUnderContention() {
        repeat(5) { runOnce(20_000, readerEvery = 1) }
        repeat(5) { runOnce(20_000, readerEvery = 50) }
    }
}
