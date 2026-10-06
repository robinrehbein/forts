package de.bollwerk.engine

import de.bollwerk.engine.math.ClosestParams
import de.bollwerk.engine.math.Geometry
import de.bollwerk.engine.math.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometryTest {
    private val eps = 1e-5f

    @Test
    fun crossingSegmentsHaveZeroDistance() {
        val p = ClosestParams()
        val d2 = Geometry.segmentSegmentDistSq(0f, 0f, 2f, 2f, 0f, 2f, 2f, 0f, p)
        assertEquals(0f, d2, eps)
        assertEquals(0.5f, p.s, eps)
        assertEquals(0.5f, p.t, eps)
    }

    @Test
    fun parallelSegments() {
        val d = Geometry.segmentSegmentDist(0f, 0f, 4f, 0f, 1f, 3f, 2f, 3f)
        assertEquals(3f, d, eps)
    }

    @Test
    fun endpointToEndpoint() {
        val d = Geometry.segmentSegmentDist(0f, 0f, 1f, 0f, 4f, 4f, 5f, 4f)
        assertEquals(5f, d, eps) // (1,0) -> (4,4)
    }

    @Test
    fun pointSegment() {
        val p = ClosestParams()
        assertEquals(2f, Geometry.pointSegmentDist(1f, 2f, 0f, 0f, 4f, 0f, p), eps)
        assertEquals(0.25f, p.t, eps)
        assertEquals(5f, Geometry.pointSegmentDist(-3f, 4f, 0f, 0f, 4f, 0f), eps) // klemmt auf A
        assertEquals(1f, Geometry.pointSegmentDist(0f, 1f, 0f, 0f, 0f, 0f), eps) // degeneriert
    }

    @Test
    fun sweptCapsuleHitsVerticalBeamWithoutTunneling() {
        // Projektil fliegt von x=0 nach x=10 bei y=0, Balken senkrecht bei x=5, Halbdicke 0.16, Radius 0.1
        val t = Geometry.sweptCapsule(0f, 0f, 10f, 0f, 0.1f, 5f, -2f, 5f, 2f, 0.16f)
        assertTrue(t >= 0f)
        assertEquals((5f - 0.26f) / 10f, t, 1e-4f)
        // Sehr schneller Flug (großer Schritt) trifft trotzdem
        val fast = Geometry.sweptCapsule(-1000f, 0f, 1000f, 0f, 0.05f, 5f, -1f, 5f, 1f, 0.04f)
        assertTrue(fast in 0f..1f)
    }

    @Test
    fun sweptCapsuleMiss() {
        val t = Geometry.sweptCapsule(0f, 3f, 10f, 3f, 0.1f, 5f, -2f, 5f, 2f, 0.16f)
        assertEquals(-1f, t)
    }

    @Test
    fun sweptCircle() {
        val t = Geometry.sweptCircle(0f, 0f, 10f, 0f, 0f, 6f, 0f, 1f)
        assertEquals(0.5f, t, 1e-5f)
        assertEquals(-1f, Geometry.sweptCircle(0f, 5f, 10f, 5f, 0f, 6f, 0f, 1f))
    }

    @Test
    fun vec2Basics() {
        val a = Vec2(3f, 4f)
        assertEquals(5f, a.length, eps)
        assertEquals(1f, a.normalized().length, eps)
        assertEquals(Vec2(-4f, 3f), a.perp())
        assertEquals(0f, a dot a.perp(), eps)
        assertEquals(Vec2.ZERO, Vec2.ZERO.normalized())
    }
}
