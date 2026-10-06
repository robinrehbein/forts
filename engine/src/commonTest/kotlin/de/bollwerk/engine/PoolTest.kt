package de.bollwerk.engine

import de.bollwerk.engine.sim.BeamPool
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.sim.NodePool
import de.bollwerk.engine.sim.PoolView
import de.bollwerk.engine.sim.TechSet
import de.bollwerk.engine.sim.Terrain
import de.bollwerk.engine.sim.UndoEntry
import de.bollwerk.engine.sim.UndoJournal
import de.bollwerk.engine.sim.UndoKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoolTest {
    @Test
    fun allocGrowsAndKeepsData() {
        val p = NodePool(initialCapacity = 2)
        val ids = (0 until 100).map { p.alloc(it.toFloat(), -it.toFloat(), owner = it % 2) }
        assertEquals((0 until 100).toList(), ids)
        assertTrue(p.capacity >= 100)
        assertEquals(42f, p.x[42]); assertEquals(-42f, p.y[42]); assertEquals(42f, p.px[42]); assertEquals(42f, p.tickX[42])
        assertEquals(0, p.ownerOf[42])
        assertEquals(42, p.uid(42))
        assertEquals(100, p.aliveCount)
    }

    @Test
    fun releaseIsDeferredUntilEndTickThenLifo() {
        val p = NodePool()
        repeat(5) { p.alloc(0f, 0f, 0) }
        p.release(1); p.release(3); p.release(3) // doppelt = no-op
        assertEquals(3, p.aliveCount)
        assertFalse(p.isAlive(3))
        // im selben Tick keine Wiederverwendung
        assertEquals(5, p.alloc(1f, 1f, 0))
        assertEquals(2, p.pendingSnapshot().size)
        p.endTick()
        assertEquals(0, p.pendingSnapshot().size)
        assertEquals(3, p.alloc(1f, 1f, 0)) // zuletzt freigegeben zuerst
        assertEquals(1, p.alloc(1f, 1f, 0))
        assertEquals(6, p.alloc(1f, 1f, 0))
        assertEquals(7, p.size)
    }

    @Test
    fun refsDetectSlotReuseAndUidsAreNeverReused() {
        val p = NodePool()
        val id = p.alloc(0f, 0f, 0)
        val ref = p.ref(id)
        val uid = p.uid(id)
        assertEquals(id, p.resolve(ref))
        assertEquals(-1, p.resolve(PoolView.NO_REF))
        p.release(id)
        assertEquals(-1, p.resolve(ref))
        p.endTick()
        val again = p.alloc(1f, 1f, 0)
        assertEquals(id, again)
        assertEquals(-1, p.resolve(ref), "alte Ref darf den neuen Bewohner nicht treffen")
        assertEquals(again, p.resolve(p.ref(again)))
        assertNotEquals(uid, p.uid(again))
        assertEquals(1, p.gen(again))
        assertTrue(p.ref(again) >= 0L)
        assertEquals(-1, p.resolve((5L shl 32) or 999L)) // Slot außerhalb
    }

    @Test
    fun allocTickMarksNewSlots() {
        val p = NodePool()
        p.now = 10
        val a = p.alloc(0f, 0f, 0)
        assertTrue(p.isNew(a))
        p.now = 11
        assertFalse(p.isNew(a))
        assertEquals(10L, p.allocTick[a])
    }

    @Test
    fun anchoredNodeHasNoInverseMass() {
        val p = NodePool()
        val id = p.alloc(1f, 2f, 0, anchored = true)
        assertTrue(p.isAnchored(id))
        assertEquals(0f, p.invMass[id])
        assertTrue((p.flags[id] and NodeFlags.ALIVE) != 0)
    }

    @Test
    fun beamPoolStoresEndpointsAndDerivesTexOffsetFromUid() {
        val b = BeamPool(1)
        val id = b.alloc(3, 7, material = 2, restLength = 4.6f, maxHp = 100f, owner = 1)
        assertEquals(3, b.nodeA(id)); assertEquals(7, b.nodeB(id))
        assertEquals(2, b.material(id)); assertEquals(100f, b.hp(id)); assertEquals(1f, b.fuel(id))
        assertEquals(4.6f, b.restLength(id))
        val id2 = b.alloc(3, 7, 2, 1f, 100f, 1)
        assertEquals((1 * 2.371f) % 4f, b.texOffset[id2])
        val id3 = b.alloc(3, 7, 2, 1f, 100f, 1, texOffset = 1.25f)
        assertEquals(1.25f, b.texOffset[id3])
    }

    @Test
    fun adjacencyIsCsrInBeamOrder() {
        val n = NodePool()
        val b = BeamPool()
        repeat(4) { n.alloc(it.toFloat(), 0f, 0) }
        val b0 = b.alloc(0, 1, 0, 1f, 1f, 0)
        val b1 = b.alloc(1, 2, 0, 1f, 1f, 0)
        val b2 = b.alloc(1, 3, 0, 1f, 1f, 0)
        b.alloc(2, 3, 0, 1f, 1f, 0)
        b.release(3)
        n.rebuildAdjacency(b)
        assertEquals(1, n.beamCount(0))
        assertEquals(3, n.beamCount(1))
        assertEquals(listOf(b0, b1, b2), (0 until n.beamCount(1)).map { n.adjacentBeam(1, it) })
        assertEquals(1, n.beamCount(2))
        assertEquals(1, n.beamCount(3))
        assertEquals(b2, n.adjacentBeam(3, 0))
    }

    @Test
    fun techSet() {
        val t = TechSet(1)
        assertFalse(3 in t)
        t.add(3); t.add(130)
        assertTrue(3 in t); assertTrue(130 in t); assertFalse(4 in t)
        assertEquals(3, t.wordCount)
        t.remove(3)
        assertFalse(3 in t)
    }

    @Test
    fun undoJournalIsBounded() {
        val j = UndoJournal(3)
        assertNull(j.pop())
        for (i in 0 until 5) j.push(UndoEntry(UndoKind.BEAM, i.toLong(), i.toLong(), 1f, 0f))
        assertEquals(3, j.size)
        assertEquals(2L, j[0].tick)
        assertEquals(4L, j.pop()!!.tick)
        assertEquals(3L, j.peek()!!.tick)
        UndoJournal(0).also { it.push(j[0]); assertEquals(0, it.size) }
    }

    @Test
    fun terrainInterpolatesAndClamps() {
        val t = Terrain.fromPolyline(floatArrayOf(0f, 10f, 20f), floatArrayOf(30f, 30f, 50f), step = 0.5f)
        assertEquals(30f, t.heightAt(5f), 1e-4f)
        assertEquals(40f, t.heightAt(15f), 1e-4f)
        assertEquals(30f, t.heightAt(-100f), 1e-4f)
        assertEquals(50f, t.heightAt(100f), 0.05f)
    }
}
