package de.bollwerk.engine

import de.bollwerk.engine.sim.SimStepper
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.SystemSlot
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.FxBuffer
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import de.bollwerk.engine.view.SnapshotBuilder
import de.bollwerk.engine.view.SnapshotExchange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SnapshotTest {
    private fun hit(tick: Long) = FxEvent.Hit(tick, 0f, 0f, HitTarget.TERRAIN, -1, 1f, 0)

    @Test
    fun copiesPoolsDerivedRatiosAndHud() {
        val s = TestWorld.state(seed = 3L)
        val a = s.nodes.alloc(1f, 2f, 0)
        val b = s.nodes.alloc(5f, 2f, 0)
        s.beams.alloc(a, b, TestWorld.WOOD, 4f, 200f, 0)
        s.beams.hpOf[0] = 50f
        s.beams.strainOf[0] = -0.0175f // halbe Druckgrenze 0,035
        s.beams.fuelOf[0] = 0.4f
        val r = s.devices.alloc(TestWorld.REACTOR, 0, 0.5f, 300f, 1, 0, false, 0f, 1f)
        s.players[1].reactorDeviceId = r
        s.devices.hpOf[r] = 186f
        val own = s.devices.alloc(TestWorld.REACTOR, 0, 0.2f, 300f, 0, 0, false, 0f, 1f)
        s.players[0].reactorDeviceId = own
        val mortar = s.devices.alloc(TestWorld.MORTAR, 0, 0.8f, 90f, 0, 120, false, 0.9f, 0.75f)
        s.devices.reloadTicksOf[mortar] = 90
        s.players[0].techUnlocked.add(0)
        s.wind = 3.2f

        val snap = SnapshotBuilder(localPlayer = 0).build(s, FrameSnapshot())
        assertEquals(2, snap.nodeCount)
        assertEquals(0.25f, snap.beamHp01[0])
        assertEquals(0.5f, snap.beamLoad01[0], 1e-5f)
        assertEquals(0.4f, snap.beamFuel01[0])
        assertEquals(4f, snap.beamRestLen[0])
        assertEquals(s.beams.uid(0), snap.beamUid[0])
        assertEquals(3, snap.deviceCount)
        assertEquals(0.75f, snap.deviceReload01[mortar], 1e-6f) // 90 von 360 übrig
        assertEquals(0.5f, snap.deviceBuild01[mortar], 1e-6f) // 120 von 240 übrig
        assertEquals(1f, snap.deviceBuild01[r])
        assertEquals(90f, snap.deviceMaxHp[mortar])
        assertEquals(0.62f, snap.hud.enemyReactor01, 1e-6f)
        assertEquals(1f, snap.hud.ownReactor01)
        assertEquals(3.2f, snap.hud.windSpeed)
        assertEquals(listOf(0), snap.hud.unlockedTechs)
        val w = snap.hud.weapons.single()
        assertEquals(s.devices.ref(mortar), w.deviceRef)
        assertEquals(0.75f, w.reload01, 1e-6f)
        assertTrue(!w.ready)
    }

    @Test
    fun interpolationStartComesFromTickStartIndependentOfBufferReuse() {
        val s = TestWorld.state()
        val n = s.nodes.alloc(1f, 2f, 0)
        val p = s.projectiles.alloc(0f, 0f, 10f, 0f, 0, 0, 100)
        val move = SimSystem { st, _ -> st.nodes.x[n] += 1f; st.projectiles.x[p] += 0.5f }
        val stepper = SimStepper(mapOf(SystemSlot.PHYSICS to move))
        val ctx = StepContext()
        val builder = SnapshotBuilder()
        val snap = FrameSnapshot()
        repeat(3) { stepper.step(s, ctx) } // 3 Ticks in einem Frame
        builder.build(s, snap)
        assertEquals(3f, snap.nodePrevX[n])
        assertEquals(4f, snap.nodeX[n])
        assertEquals(1f, snap.projPrevX[p])
        assertEquals(1.5f, snap.projX[p])
        // Neuer Knoten mitten im Tick: prev == aktuell (kein Hineinfliegen)
        val m = s.nodes.alloc(9f, 9f, 0)
        builder.build(s, snap)
        assertEquals(9f, snap.nodePrevX[m])
    }

    @Test
    fun fxBufferIsDrainedAndCarriedWhenUnread() {
        val s = TestWorld.state()
        val buf = FxBuffer()
        val ex = SnapshotExchange()
        val builder = SnapshotBuilder()
        assertNull(ex.latest())

        buf.addAll(listOf(hit(0)))
        builder.build(s, ex.beginWrite(), buf); ex.publish()
        // Leser verpasst diesen Frame; Sim veröffentlicht erneut
        buf.addAll(listOf(hit(1)))
        builder.build(s, ex.beginWrite(), buf); ex.publish()
        buf.addAll(listOf(hit(2)))
        builder.build(s, ex.beginWrite(), buf); ex.publish()

        val seen = ArrayList<Long>()
        var lastSeq = -1L
        repeat(3) {
            val snap = ex.latest()!!
            if (snap.seq != lastSeq) { lastSeq = snap.seq; snap.fx.forEach { seen.add(it.tick) } }
            builder.build(s, ex.beginWrite(), buf); ex.publish()
        }
        assertEquals(listOf(0L, 1L, 2L), seen.sorted())
        assertEquals(0, buf.size)
    }

    @Test
    fun exchangeReturnsSameBufferUntilNewPublish() {
        val s = TestWorld.state()
        val ex = SnapshotExchange()
        val b = SnapshotBuilder()
        b.build(s, ex.beginWrite()); ex.publish()
        val first = ex.latest()!!
        assertSame(first, ex.latest())
        b.build(s, ex.beginWrite()); ex.publish()
        val second = ex.latest()!!
        assertTrue(second.seq > first.seq)
    }

    @Test
    fun fxBufferDropsOldestBeyondCapacity() {
        val buf = FxBuffer(capacity = 2)
        buf.addAll(listOf(hit(1), hit(2), hit(3)))
        val out = ArrayList<FxEvent>()
        buf.drainTo(out)
        assertEquals(listOf(2L, 3L), out.map { it.tick })
    }

    @Test
    fun growsForLargePools() {
        val s = TestWorld.state()
        repeat(1000) { s.nodes.alloc(it.toFloat(), 0f, 0) }
        val snap = SnapshotBuilder().build(s, FrameSnapshot())
        assertEquals(1000, snap.nodeCount)
        assertEquals(999f, snap.nodeX[999])
    }
}
