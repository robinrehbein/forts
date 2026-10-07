package de.bollwerk.renderandroid

import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.SnapshotExchange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val MS = 1_000_000L

class FrameStatsTest {
    @Test
    fun percentilesOfKnownDistribution() {
        val s = FrameStats(window = 100, refreshEvery = 1000)
        for (i in 1..100) s.record(frameIntervalMs = i.toFloat(), workMs = i / 10f)
        s.refresh()
        assertEquals(50f, s.p50Ms)
        assertEquals(95f, s.p95Ms)
        assertEquals(5f, s.workP50Ms, 1e-4f)
        assertEquals(9.5f, s.workP95Ms, 1e-4f)
        assertEquals(20f, s.fps, 1e-3f)
    }

    @Test
    fun windowKeepsOnlyTheLastFrames() {
        val s = FrameStats(window = 10, refreshEvery = 1000)
        repeat(10) { s.record(100f, 1f) }
        repeat(10) { s.record(16f, 1f) }
        s.refresh()
        assertEquals(16f, s.p95Ms)
        assertEquals(20L, s.frames)
    }

    @Test
    fun autoRefreshEveryNFrames() {
        val s = FrameStats(window = 60, refreshEvery = 10)
        repeat(9) { s.record(16.6f, 2f) }
        assertEquals(0f, s.p50Ms, "noch nicht berechnet")
        s.record(16.6f, 2f)
        assertEquals(16.6f, s.p50Ms)
    }

    @Test
    fun countsDroppedFramesAgainstBudget() {
        val s = FrameStats(window = 20, refreshEvery = 1000, budgetMs = 16.67f)
        s.record(16.7f, 1f); s.record(33.3f, 1f); s.record(50f, 1f); s.record(25f, 1f); s.record(24.9f, 1f)
        // > 25,005 ms zählt als ausgelassen
        assertEquals(2L, s.droppedFrames)
    }

    @Test
    fun throttledFramesAreNotCountedAsDropsAgainstTheExpectedInterval() {
        val s = FrameStats(window = 20, refreshEvery = 1000, budgetMs = 16.67f)
        // Pause oder maxFps 30: Soll-Abstand 33,3 ms, 33 ms Ist ist kein ausgelassener Frame
        repeat(10) { s.record(33.3f, 1f, expectedIntervalMs = 33.3f) }
        assertEquals(0L, s.droppedFrames)
        // 70 ms bei Soll 33,3 ms (> 1,5 ×): ausgelassen
        s.record(70f, 1f, expectedIntervalMs = 33.3f)
        assertEquals(1L, s.droppedFrames)
        // Ohne Angabe gilt das Display-Budget
        s.record(33.3f, 1f)
        assertEquals(2L, s.droppedFrames)
    }

    @Test
    fun simTimeIsAveragedOnlyOverFramesThatHaveOne() {
        val s = FrameStats(window = 20, refreshEvery = 1000)
        s.record(16f, 1f, simMs = 2f); s.record(16f, 1f); s.record(16f, 1f, simMs = 4f)
        s.refresh()
        assertEquals(3f, s.simMeanMs, 1e-4f)
        val t = FrameStats(window = 20, refreshEvery = 1000)
        t.record(16f, 1f); t.refresh()
        assertTrue(t.simMeanMs.isNaN())
    }

    @Test
    fun resetClearsEverything() {
        val s = FrameStats(window = 10, refreshEvery = 1)
        repeat(5) { s.record(40f, 3f, 1f) }
        s.reset()
        assertEquals(0L, s.frames); assertEquals(0L, s.droppedFrames); assertEquals(0f, s.p95Ms)
        s.record(10f, 1f)
        assertEquals(10f, s.p50Ms)
    }
}

class SnapshotTimingTest {
    private val tick = 1f / 60f
    private val tickNanos = (tick * 1e9f).toLong()

    @Test
    fun alphaRisesFromZeroToOneBetweenSnapshots() {
        val t = SnapshotTiming()
        t.advance(0L, seq = 1, hint = Float.NaN)
        assertEquals(0f, t.alpha, 1e-6f)
        t.advance(tickNanos / 4, 1, Float.NaN)
        assertEquals(0.25f, t.alpha, 1e-3f)
        t.advance(tickNanos / 2, 1, Float.NaN)
        assertEquals(0.5f, t.alpha, 1e-3f)
        t.advance(tickNanos * 3, 1, Float.NaN)
        assertEquals(1f, t.alpha, "ohne neuen Snapshot hält alpha bei 1 (kein Hochextrapolieren)")
    }

    @Test
    fun newSnapshotResetsAlpha() {
        val t = SnapshotTiming()
        t.advance(0L, 1, Float.NaN)
        t.advance(tickNanos * 9 / 10, 1, Float.NaN)
        t.advance(tickNanos, 2, Float.NaN)
        assertEquals(0f, t.alpha, 1e-6f)
    }

    @Test
    fun simProvidedAlphaWinsAndIsClamped() {
        val t = SnapshotTiming()
        t.advance(0L, 1, 0.3f)
        assertEquals(0.3f, t.alpha, 1e-6f)
        t.advance(tickNanos, 1, 1.7f)
        assertEquals(1f, t.alpha)
        t.advance(2 * tickNanos, 1, -0.2f)
        assertEquals(0f, t.alpha)
    }

    @Test
    fun dtIsRealFrameTimeClampedAndZeroOnFirstFrame() {
        val t = SnapshotTiming()
        t.advance(1_000 * MS, 1, Float.NaN)
        assertEquals(0f, t.dtSeconds)
        t.advance(1_016 * MS, 1, Float.NaN)
        assertEquals(0.016f, t.dtSeconds, 1e-4f)
        t.advance(3_000 * MS, 1, Float.NaN)
        assertEquals(SnapshotTiming.MAX_DT, t.dtSeconds, "Hänger wird begrenzt")
    }

    @Test
    fun resetAvoidsTimeJumpAfterPause() {
        val t = SnapshotTiming()
        t.advance(0L, 1, Float.NaN)
        t.advance(tickNanos, 2, Float.NaN)
        t.reset()
        t.advance(60_000 * MS, 7, Float.NaN)
        assertEquals(0f, t.dtSeconds)
        assertEquals(0f, t.alpha)
        assertEquals(tick, t.snapshotIntervalSeconds, 1e-6f)
    }

    @Test
    fun snapshotIntervalAdaptsToSlowSim() {
        val t = SnapshotTiming()
        var now = 0L
        // Sim liefert nur alle 25 ms einen Snapshot (40 Hz)
        for (seq in 1L..200L) { t.advance(now, seq, Float.NaN); now += 25 * MS }
        assertEquals(0.025f, t.snapshotIntervalSeconds, 0.002f)
        t.advance(now - 25 * MS + 12 * MS, 200L, Float.NaN)
        assertEquals(0.48f, t.alpha, 0.1f)
    }

    @Test
    fun intervalIsClampedAgainstOutliers() {
        val t = SnapshotTiming()
        t.advance(0L, 1, Float.NaN)
        t.advance(10_000 * MS, 2, Float.NaN) // 10 s Pause: kein gültiger Messwert
        assertEquals(tick, t.snapshotIntervalSeconds, 1e-6f)
    }

    @Test
    fun skippedSnapshotsDivideTheObservedInterval() {
        val t = SnapshotTiming()
        t.advance(0L, 1, Float.NaN)
        t.advance(2 * tickNanos, 3, Float.NaN) // zwei Veröffentlichungen in einem Frame-Abstand
        assertEquals(tick, t.snapshotIntervalSeconds, 1e-4f)
    }
}

class SnapshotSourceTest {
    private val tables = de.bollwerk.renderapi.scene.SyntheticScene.makeTables()
    private val map = de.bollwerk.renderapi.scene.SyntheticScene.makeMap()

    @Test
    fun sourceOverTripleBufferDeliversLatestPublishedSnapshotOnly() {
        val ex = SnapshotExchange()
        val src = SnapshotSource.of(ex, tables, map, simMillis = { 1.5f }, alpha = { 0.4f })
        assertNull(src.latest(), "vor der ersten Veröffentlichung")
        val w1 = ex.beginWrite(); w1.seq = 1; ex.publish()
        val w2 = ex.beginWrite(); w2.seq = 2; ex.publish()
        val got = src.latest()
        assertNotNull(got)
        assertEquals(2L, got.seq, "bei zwei Veröffentlichungen gilt die neueste")
        assertSame(got, src.latest(), "ohne neue Veröffentlichung derselbe Puffer")
        assertEquals(1.5f, src.lastSimMillis())
        assertEquals(0.4f, src.alphaHint())
        assertSame(tables, src.tables)
    }

    @Test
    fun optionalHintsDefaultToUnknown() {
        val src = SnapshotSource.of(SnapshotExchange(), tables, map)
        assertTrue(src.lastSimMillis().isNaN())
        assertTrue(src.alphaHint().isNaN())
    }

    @Test
    fun renderThreadReadsWhileSimThreadWritesWithoutTearingSequenceNumbers() {
        val ex = SnapshotExchange { FrameSnapshot() }
        val src = SnapshotSource.of(ex, tables, map)
        val total = 20_000L
        val sim = Thread {
            for (i in 1..total) {
                val s = ex.beginWrite()
                s.seq = i; s.tick = i // beide Felder müssen zusammen gelesen werden
                ex.publish()
            }
        }
        sim.start()
        var last = 0L
        var reads = 0
        while (sim.isAlive) {
            val s = src.latest() ?: continue
            assertTrue(s.seq >= last, "seq darf nicht zurückspringen")
            assertEquals(s.tick, s.seq)
            last = s.seq
            reads++
        }
        sim.join()
        assertEquals(total, src.latest()!!.seq, "nach dem Ende liefert der Leser den letzten Snapshot")
        assertTrue(reads >= 0)
    }
}
