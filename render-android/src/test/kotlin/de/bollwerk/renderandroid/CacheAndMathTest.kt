package de.bollwerk.renderandroid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RefCountedCacheTest {
    private val disposed = ArrayList<String>()
    private fun cache(budget: Long) = RefCountedCache<String>(budget) { disposed.add(it) }

    @Test
    fun missThenInsertThenHit() {
        val c = cache(1000)
        assertNull(c.acquire("a"))
        c.insert("a", "A", 100)
        assertEquals("A", c.acquire("a"))
        assertEquals(1, c.hits)
        assertEquals(1, c.misses)
        assertEquals(100L, c.inUseBytes)
    }

    @Test
    fun entryStaysInUseUntilLastRelease() {
        val c = cache(0) // Budget 0: jede ungenutzte Textur würde sofort verworfen
        c.insert("a", "A", 50)
        c.acquire("a") // zweite Referenz
        c.release("a")
        assertTrue(disposed.isEmpty(), "noch eine Referenz: nicht verwerfen")
        assertEquals(50L, c.inUseBytes)
        c.release("a")
        assertEquals(listOf("A"), disposed, "letzter Release über Budget: verworfen")
        assertEquals(0L, c.idleBytes)
    }

    @Test
    fun idleEntriesSurviveWithinBudgetAndAreReusedByNextMatch() {
        val c = cache(1000)
        c.insert("wood@72|288x23", "W", 300)
        c.release("wood@72|288x23")
        assertEquals(300L, c.idleBytes)
        assertEquals(0L, c.inUseBytes)
        assertTrue(disposed.isEmpty())
        assertEquals("W", c.acquire("wood@72|288x23"))
        assertEquals(0L, c.idleBytes)
        assertEquals(300L, c.inUseBytes)
    }

    @Test
    fun idleBudgetEvictsLeastRecentlyUsedFirst() {
        val c = cache(250)
        for (k in listOf("a", "b", "c")) { c.insert(k, k.uppercase(), 100); c.release(k) }
        // a, b, c idle (300 B) > 250: ältester (a) wird verworfen
        assertEquals(listOf("A"), disposed)
        c.acquire("b"); c.release("b") // b ist jetzt jüngster
        c.insert("d", "D", 100); c.release("d")
        assertEquals(listOf("A", "C"), disposed)
    }

    @Test
    fun trimIdleKeepsInUseEntries() {
        val c = cache(10_000)
        c.insert("a", "A", 10); c.insert("b", "B", 10)
        c.release("b")
        c.trimIdle()
        assertEquals(listOf("B"), disposed)
        assertEquals(1, c.inUseCount)
        assertEquals(0, c.idleCount)
        assertNotNull(c.acquire("a"))
    }

    @Test
    fun clearDisposesEverything() {
        val c = cache(10_000)
        c.insert("a", "A", 10); c.insert("b", "B", 10); c.release("b")
        c.clear()
        assertEquals(setOf("A", "B"), disposed.toSet())
        assertEquals(0, c.size)
        assertEquals(0L, c.inUseBytes + c.idleBytes)
    }

    @Test
    fun releaseOfUnknownKeyIsIgnored() {
        val c = cache(10)
        c.release("nope")
        assertEquals(0, c.size)
    }
}

class TrimPolicyTest {
    @Test
    fun levelsMapToIncreasingActions() {
        assertEquals(TrimAction.NONE, TrimPolicy.actionFor(5)) // RUNNING_MODERATE
        assertEquals(TrimAction.IDLE_TEXTURES, TrimPolicy.actionFor(10)) // RUNNING_LOW
        assertEquals(TrimAction.LAYERS, TrimPolicy.actionFor(15)) // RUNNING_CRITICAL
        assertEquals(TrimAction.LAYERS, TrimPolicy.actionFor(20)) // UI_HIDDEN
        assertEquals(TrimAction.EVERYTHING, TrimPolicy.actionFor(40)) // BACKGROUND
        assertEquals(TrimAction.EVERYTHING, TrimPolicy.actionFor(80)) // COMPLETE
    }
}

class LayerStateTest {
    private fun LayerState.recordNow(key: Long, w: Int = 100, h: Int = 50) { recording(key, w, h); completed() }

    @Test
    fun firstRequestRecordsThenBlits() {
        val s = LayerState()
        assertEquals(LayerDecision.RECORD, s.decide(1L, 100, 50, hasBitmap = false))
        s.recordNow(1L)
        assertEquals(LayerDecision.BLIT, s.decide(1L, 100, 50, hasBitmap = true))
        assertEquals(LayerDecision.BLIT, s.decide(1L, 100, 50, hasBitmap = true))
    }

    @Test
    fun movingCameraDrawsDirectly() {
        val s = LayerState()
        s.decide(1L, 100, 50, false); s.recordNow(1L)
        for (k in 2L..40L) assertEquals(LayerDecision.DIRECT, s.decide(k, 100, 50, true), "Schlüssel $k wechselt: direkt")
        assertFalse(s.valid)
    }

    @Test
    fun recordsOnlyAfterTheKeyWasStableForSixConsecutiveFrames() {
        val s = LayerState()
        s.decide(1L, 100, 50, false); s.recordNow(1L)
        assertEquals(LayerDecision.DIRECT, s.decide(2L, 100, 50, true), "Frame 1 mit neuem Schlüssel")
        for (n in 2..5) assertEquals(LayerDecision.DIRECT, s.decide(2L, 100, 50, true), "Frame $n gleicher Schlüssel: noch direkt")
        assertEquals(LayerDecision.RECORD, s.decide(2L, 100, 50, true), "Frame 6: ruhig genug")
        s.recordNow(2L)
        assertEquals(LayerDecision.BLIT, s.decide(2L, 100, 50, true))
    }

    @Test
    fun singleFramePausesDuringSlowPanDoNotTriggerRecording() {
        // Touch und Vsync sind nicht phasengekoppelt: bei langsamem Pan steht der Schlüssel oft 1–3 Frames still
        val s = LayerState()
        s.decide(1L, 100, 50, false); s.recordNow(1L)
        var key = 1L
        repeat(60) { i ->
            if (i % 3 == 0) key++ // Kamera bewegt sich nur jeden dritten Frame
            assertEquals(LayerDecision.DIRECT, s.decide(key, 100, 50, true), "Frame $i")
        }
    }

    @Test
    fun anyKeyChangeRestartsTheStabilityCount() {
        val s = LayerState()
        s.decide(1L, 100, 50, false); s.recordNow(1L)
        s.decide(2L, 100, 50, true)
        repeat(4) { s.decide(2L, 100, 50, true) } // 5 gleiche
        assertEquals(LayerDecision.DIRECT, s.decide(3L, 100, 50, true), "Ruck: von vorn")
        repeat(4) { assertEquals(LayerDecision.DIRECT, s.decide(3L, 100, 50, true)) }
        assertEquals(LayerDecision.RECORD, s.decide(3L, 100, 50, true))
    }

    @Test
    fun customThresholdIsHonoured() {
        val s = LayerState(stableFramesToRecord = 2)
        s.decide(1L, 100, 50, false); s.recordNow(1L)
        assertEquals(LayerDecision.DIRECT, s.decide(2L, 100, 50, true))
        assertEquals(LayerDecision.RECORD, s.decide(2L, 100, 50, true))
    }

    @Test
    fun sizeChangeInvalidatesLikeKeyChange() {
        val s = LayerState()
        s.decide(1L, 100, 50, false); s.recordNow(1L)
        assertEquals(LayerDecision.DIRECT, s.decide(1L, 120, 50, true))
        repeat(4) { assertEquals(LayerDecision.DIRECT, s.decide(1L, 120, 50, true)) }
        assertEquals(LayerDecision.RECORD, s.decide(1L, 120, 50, true))
    }

    @Test
    fun invalidateMakesNextRequestRecordImmediately() {
        val s = LayerState()
        s.decide(1L, 100, 50, false); s.recordNow(1L)
        s.invalidate()
        assertEquals(LayerDecision.RECORD, s.decide(9L, 100, 50, true))
    }

    @Test
    fun droppedBitmapForcesRerecordWhenKeyHasBeenStillForAWhile() {
        val s = LayerState()
        s.decide(1L, 100, 50, false); s.recordNow(1L)
        repeat(10) { s.decide(1L, 100, 50, true) } // lange geblittet: Schlüssel ruhig
        s.dropped()
        assertEquals(LayerDecision.RECORD, s.decide(1L, 100, 50, hasBitmap = false))
    }

    @Test
    fun droppedBitmapWhileCameraMovesDrawsDirectlyUntilStable() {
        val s = LayerState()
        s.decide(1L, 100, 50, false); s.recordNow(1L)
        s.dropped()
        assertEquals(LayerDecision.DIRECT, s.decide(2L, 100, 50, hasBitmap = false))
    }
}

class DrawMathTest {
    @Test
    fun alphaConversionRoundsAndClamps() {
        assertEquals(0, DrawMath.alpha255(-1f))
        assertEquals(255, DrawMath.alpha255(2f))
        assertEquals(128, DrawMath.alpha255(0.5f))
    }

    @Test
    fun normalizeAlphaMakesFireGradientsShareOneShader() {
        val a = intArrayOf(0x66FF8800, 0x00FFAA00)
        val b = intArrayOf(0x33FF8800, 0x00FFAA00)
        val na = IntArray(2); val nb = IntArray(2)
        val ma = DrawMath.normalizeAlpha(a, 2, na)
        val mb = DrawMath.normalizeAlpha(b, 2, nb)
        assertEquals(0x66, ma)
        assertEquals(0x33, mb)
        assertEquals(na.toList(), nb.toList())
        assertEquals(0xFFFF8800.toInt(), na[0])
        assertEquals(0x00FFAA00, na[1])
    }

    @Test
    fun normalizeAlphaOfTransparentGradientIsZero() {
        assertEquals(0, DrawMath.normalizeAlpha(intArrayOf(0x00112233, 0x00445566), 2, IntArray(2)))
    }

    @Test
    fun regionTransformMapsTexelRectOntoDestination() {
        val o = FloatArray(4)
        // Holzplanke 288 × 23 Texel, Ausschnitt u 0,25..0,75 (144 Texel) → 2 m × 0,32 m Ziel ab (10, 5)
        assertTrue(DrawMath.regionTransform(0.25f, 0f, 0.75f, 1f, 10f, 5f, 2f, 0.32f, 288, 23, o))
        val (tx, ty, sx, sy) = o.toList()
        fun dx(texel: Float) = tx + texel * sx
        fun dy(texel: Float) = ty + texel * sy
        assertEquals(10f, dx(0.25f * 288), 1e-3f)
        assertEquals(12f, dx(0.75f * 288), 1e-3f)
        assertEquals(5f, dy(0f), 1e-3f)
        assertEquals(5.32f, dy(23f), 1e-3f)
    }

    @Test
    fun regionTransformRejectsEmptyRegions() {
        val o = FloatArray(4)
        assertFalse(DrawMath.regionTransform(0.5f, 0f, 0.5f, 1f, 0f, 0f, 1f, 1f, 10, 10, o))
        assertFalse(DrawMath.regionTransform(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 10, 10, o))
        assertFalse(DrawMath.regionTransform(0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, 0, 10, o))
    }

    @Test
    fun dashSegmentsFollowDashAndGapPattern() {
        val out = FloatArray(64)
        val n = DrawMath.dashSegments(0f, 0f, 10f, 0f, 2f, 1f, out)
        // Striche bei 0–2, 3–5, 6–8, 9–10
        assertEquals(4, n)
        assertEquals(listOf(0f, 0f, 2f, 0f), out.slice(0..3))
        assertEquals(listOf(3f, 0f, 5f, 0f), out.slice(4..7))
        assertEquals(listOf(9f, 0f, 10f, 0f), out.slice(12..15), "letzter Strich endet an der Linie")
    }

    @Test
    fun dashSegmentsWorkDiagonallyAndAreCapped() {
        val out = FloatArray(8) // höchstens 2 Striche
        val n = DrawMath.dashSegments(0f, 0f, 3f, 4f, 1f, 1f, out)
        assertEquals(2, n)
        assertEquals(0.6f, out[2], 1e-4f)
        assertEquals(0.8f, out[3], 1e-4f)
    }

    @Test
    fun dashSegmentsOfZeroLengthLineAreEmpty() {
        assertEquals(0, DrawMath.dashSegments(1f, 1f, 1f, 1f, 1f, 1f, FloatArray(16)))
    }

    @Test
    fun segmentsNeededCoversDashSegmentsAndIsBounded() {
        val need = DrawMath.segmentsNeeded(0f, 0f, 10f, 0f, 2f, 1f)
        assertTrue(need >= DrawMath.dashSegments(0f, 0f, 10f, 0f, 2f, 1f, FloatArray(need * 4)))
        assertEquals(DrawMath.MAX_DASHES, DrawMath.segmentsNeeded(0f, 0f, 1e9f, 0f, 1f, 1f))
    }
}

class GradientCacheTest {
    private var created = 0
    private fun cache() = GradientCache(8, GradientFactory<Any> { _, _, _, _, _, _, _ -> created++; Any() })

    @Test
    fun identicalRequestsHitAndNeverAllocateNewShaders() {
        val c = cache()
        val cols = intArrayOf(0xFFFF8800.toInt(), 0x00FFAA00)
        val stops = floatArrayOf(0f, 1f)
        val first = c.get(0f, 1f, 0f, -1f, cols, 2, stops)
        repeat(100) { assertSame(first, c.get(0f, 1f, 0f, -1f, cols, 2, stops)) }
        assertEquals(1, created)
        assertEquals(100, c.hits)
    }

    @Test
    fun differentGeometryOrColorsMiss() {
        val c = cache()
        val stops = floatArrayOf(0f, 1f)
        val a = c.get(0f, 0f, 0f, 1f, intArrayOf(1, 2), 2, stops)
        val b = c.get(0f, 0f, 0f, 2f, intArrayOf(1, 2), 2, stops)
        val d = c.get(0f, 0f, 0f, 1f, intArrayOf(1, 3), 2, stops)
        assertTrue(a !== b && a !== d && b !== d)
        assertEquals(3, created)
    }

    @Test
    fun mutatedColorArrayIsComparedByValue() {
        val c = cache()
        val cols = intArrayOf(1, 2)
        val stops = floatArrayOf(0f, 1f)
        c.get(0f, 0f, 1f, 1f, cols, 2, stops)
        cols[0] = 7 // wie `fireGrad[0] = …` im Renderer
        c.get(0f, 0f, 1f, 1f, cols, 2, stops)
        assertEquals(2, created)
        cols[0] = 1
        c.get(0f, 0f, 1f, 1f, cols, 2, stops)
        c.get(0f, 0f, 1f, 1f, cols, 2, stops)
        assertTrue(c.hits >= 1)
    }

    @Test
    fun intValueCacheReturnsSameInstancePerKeyAndClearsWhenFull() {
        var made = 0
        val c = IntValueCache(8, IntFactory<Any> { made++; Any() })
        val a = c.get(0xFF8800)
        assertSame(a, c.get(0xFF8800))
        assertEquals(1, made)
        for (k in 1..20) c.get(k) // läuft über die Hälfte der Kapazität → leert
        assertTrue(c.count <= 4)
        assertNotNull(c.get(0xFF8800))
    }
}
