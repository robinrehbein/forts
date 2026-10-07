package de.bollwerk.renderapi.scene

import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.MapSpec
import de.bollwerk.engine.view.FrameSnapshot
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Vom Renderer geführter, rein visueller Zustand, der aus [FxEvent]s und der Zeit entsteht und **nicht**
 * in den Partikeln lebt: Kamera-Shake, Treffer-Blitze auf Balken/Geräten, Brandspur-Decals (auf Balken und
 * im Gelände), Rückstoß, Leuchtspuren, Rotorwinkel, Wolkenphase. Alles in festen Arrays (keine Allokation
 * im Betrieb); Objekte werden über ihre **uid** geprüft, ein Slot mit neuer uid beginnt frisch.
 */
internal class FxState(private val reduced: Boolean) {
    // ---- Kamera-Shake / Bildschirmblitz ----
    /** Aktuelle Shake-Amplitude in dp (max. 6, im reduzierten Modus ×0,25). */
    var shakeDp = 0f
    /** Örtlicher Explosions-Blitz: Stärke 0..1, Mittelpunkt und gewünschter Radius in m (Begrenzung beim Zeichnen). */
    var burst = 0f
    var burstX = 0f
    var burstY = 0f
    var burstR = 0f

    // ---- Balken je Slot ----
    var beamUid = IntArray(0)
    var beamFlash = FloatArray(0)
    var scorchCount = IntArray(0)
    /** 3 Decals je Slot: Position 0..1 entlang des Balkens und Radius in m. */
    var scorchT = FloatArray(0)
    var scorchR = FloatArray(0)

    // ---- Geräte je Slot ----
    var devUid = IntArray(0)
    var devFlash = FloatArray(0)
    var devRecoil = FloatArray(0)
    var devSpin = FloatArray(0)
    /** Puls-Phase des Reaktorkerns (integriert, damit eine Änderung der Frequenz keinen Phasensprung macht). */
    var devPhase = FloatArray(0)

    // ---- Gelände-Decals (Ring) ----
    val decalX = FloatArray(MAX_DECALS)
    val decalY = FloatArray(MAX_DECALS)
    val decalR = FloatArray(MAX_DECALS)
    var decalCount = 0
    private var decalHead = 0

    // ---- Leuchtspuren ----
    val trX0 = FloatArray(MAX_TRACERS)
    val trY0 = FloatArray(MAX_TRACERS)
    val trX1 = FloatArray(MAX_TRACERS)
    val trY1 = FloatArray(MAX_TRACERS)
    val trAge = FloatArray(MAX_TRACERS)
    var trCount = 0

    /** Wolkenphase (integriert Wind). */
    var cloudPhase = 0f

    /** Knoten-uid der Fahnenstange je Spieler (-1 = keine); bleibt gültig, solange der Knoten taugt (kein Springen). */
    val flagUid = IntArray(4) { -1 }

    fun reset() {
        flagUid.fill(-1)
        shakeDp = 0f; burst = 0f; decalCount = 0; decalHead = 0; trCount = 0; cloudPhase = 0f
        beamUid.fill(0); beamFlash.fill(0f); scorchCount.fill(0)
        devUid.fill(0); devFlash.fill(0f); devRecoil.fill(0f); devSpin.fill(0f); devPhase.fill(0f)
    }

    private fun ensureBeams(n: Int) {
        if (beamUid.size >= n) return
        val c = maxOf(n, beamUid.size * 2, 64)
        beamUid = beamUid.copyOf(c); beamFlash = beamFlash.copyOf(c); scorchCount = scorchCount.copyOf(c)
        scorchT = scorchT.copyOf(c * 3); scorchR = scorchR.copyOf(c * 3)
    }

    private fun ensureDevices(n: Int) {
        if (devUid.size >= n) return
        val c = maxOf(n, devUid.size * 2, 32)
        devUid = devUid.copyOf(c); devFlash = devFlash.copyOf(c); devRecoil = devRecoil.copyOf(c); devSpin = devSpin.copyOf(c); devPhase = devPhase.copyOf(c)
    }

    /** Gleicht die Slot-Arrays mit den uids des Snapshots ab (neue uid → Zustand zurücksetzen). */
    fun sync(snap: FrameSnapshot) {
        ensureBeams(snap.beamCount)
        ensureDevices(snap.deviceCount)
        for (i in 0 until snap.beamCount) {
            val u = snap.beamUid[i]
            if (beamUid[i] != u) {
                beamUid[i] = u; beamFlash[i] = 0f; scorchCount[i] = 0
            }
        }
        for (i in 0 until snap.deviceCount) {
            val u = snap.deviceUid[i]
            if (devUid[i] != u) {
                devUid[i] = u; devFlash[i] = 0f; devRecoil[i] = 0f
                // Start-Phase des Rotors je Gerät unterschiedlich (wie `spin: rnd(6)` im Prototyp)
                devSpin[i] = ((u * 2654435761L.toInt()) ushr 8 and 0xFFFF) / 65536f * SceneContext.TAU
                devPhase[i] = ((u * 40503) and 0xFFFF) / 65536f * SceneContext.TAU
            }
        }
    }

    private fun beamSlot(snap: FrameSnapshot, uid: Int): Int {
        if (uid < 0) return -1
        for (i in 0 until snap.beamCount) if (snap.beamUid[i] == uid) return i
        return -1
    }

    private fun deviceSlot(snap: FrameSnapshot, uid: Int): Int {
        if (uid < 0) return -1
        for (i in 0 until snap.deviceCount) if (snap.deviceUid[i] == uid) return i
        return -1
    }

    /** Verarbeitet ein Ereignis (genau einmal je Ereignis; der Renderer sorgt über `seq` dafür). */
    fun onEvent(e: FxEvent, snap: FrameSnapshot, map: MapSpec, weaponKind: IntArray) {
        when (e) {
            is FxEvent.Explosion -> {
                addShake(minOf(6f, 1f + e.damage / 22f))
                if (!reduced && e.damage >= 40f) {
                    burst = 1f; burstX = e.x; burstY = e.y; burstR = e.radius * 2.2f
                }
                // Brandspur auf dem getroffenen (oder nächsten) Balken und im Gelände
                var slot = beamSlot(snap, e.hitBeamUid)
                var t = e.hitBeamT
                if (slot < 0) {
                    slot = nearestBeam(snap, e.x, e.y, e.radius * 0.35f)
                    t = -1f
                }
                if (slot >= 0) addScorch(snap, slot, e.x, e.y, e.radius, t)
                if (e.y >= map.terrain.heightAt(e.x) - 0.6f) addDecal(e.x, map.terrain.heightAt(e.x), e.radius * 0.75f)
            }
            is FxEvent.Hit -> when (e.target) {
                HitTarget.BEAM -> { val s = beamSlot(snap, e.targetUid); if (s >= 0) beamFlash[s] = 1f }
                HitTarget.DEVICE -> { val s = deviceSlot(snap, e.targetUid); if (s >= 0) devFlash[s] = 1f }
                HitTarget.TERRAIN -> Unit
            }
            is FxEvent.Fired -> {
                val s = deviceSlot(snap, e.deviceUid)
                if (s >= 0) {
                    val wk = if (e.weaponId >= 0 && e.weaponId < weaponKind.size) weaponKind[e.weaponId] else DevKind.GENERIC
                    devRecoil[s] = if (wk == DevKind.MG) 0.55f else 1f
                }
            }
            is FxEvent.Tracer -> addTracer(e.x, e.y, e.x1, e.y1)
            is FxEvent.DeviceDestroyed -> addShake(2f)
            is FxEvent.ReactorDestroyed -> addShake(6f)
            else -> Unit
        }
    }

    private fun nearestBeam(snap: FrameSnapshot, x: Float, y: Float, r: Float): Int {
        var best = -1
        var bd = r
        for (i in 0 until snap.beamCount) {
            if ((snap.beamFlags[i] and BeamFlags.ALIVE) == 0) continue
            val a = snap.beamA[i]; val b = snap.beamB[i]
            val d = pointSegDist(x, y, snap.nodeX[a], snap.nodeY[a], snap.nodeX[b], snap.nodeY[b])
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun pointSegDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        var t = if (l2 > 0f) ((px - ax) * dx + (py - ay) * dy) / l2 else 0f
        if (t < 0f) t = 0f else if (t > 1f) t = 1f
        val qx = ax + dx * t - px; val qy = ay + dy * t - py
        return sqrt(qx * qx + qy * qy)
    }

    private fun addScorch(snap: FrameSnapshot, slot: Int, x: Float, y: Float, radius: Float, tHint: Float) {
        val a = snap.beamA[slot]; val b = snap.beamB[slot]
        var t = tHint
        if (t < 0f) {
            val dx = snap.nodeX[b] - snap.nodeX[a]; val dy = snap.nodeY[b] - snap.nodeY[a]
            val l2 = dx * dx + dy * dy
            t = if (l2 > 0f) ((x - snap.nodeX[a]) * dx + (y - snap.nodeY[a]) * dy) / l2 else 0.5f
        }
        t = SceneContext.clamp(t, 0f, 1f)
        val r = SceneContext.clamp(radius * 0.45f, 0.35f, 1.2f)
        var n = scorchCount[slot]
        if (n == 3) {
            // ältestes herausschieben
            for (k in 0 until 2) { scorchT[slot * 3 + k] = scorchT[slot * 3 + k + 1]; scorchR[slot * 3 + k] = scorchR[slot * 3 + k + 1] }
            n = 2
        }
        scorchT[slot * 3 + n] = t
        scorchR[slot * 3 + n] = r
        scorchCount[slot] = n + 1
    }

    fun addDecal(x: Float, y: Float, r: Float) {
        decalX[decalHead] = x; decalY[decalHead] = y; decalR[decalHead] = r
        decalHead = (decalHead + 1) % MAX_DECALS
        if (decalCount < MAX_DECALS) decalCount++
    }

    private fun addTracer(x0: Float, y0: Float, x1: Float, y1: Float) {
        val i = if (trCount < MAX_TRACERS) trCount++ else 0
        trX0[i] = x0; trY0[i] = y0; trX1[i] = x1; trY1[i] = y1; trAge[i] = 0f
    }

    fun addShake(dp: Float) {
        val v = minOf(6f, dp) * (if (reduced) 0.25f else 1f)
        if (v > shakeDp) shakeDp = v
    }

    /** Zeitschritt: Abklingen, Rotorwinkel, Wolkenphase. */
    fun update(dt: Float, snap: FrameSnapshot, devKindOfType: IntArray) {
        if (dt <= 0f) return
        shakeDp *= 0.002f.pow(dt)
        if (shakeDp < 0.05f) shakeDp = 0f
        burst = maxOf(0f, burst - dt * 4f)
        val fl = dt * 4f
        for (i in 0 until snap.beamCount) if (beamFlash[i] > 0f) beamFlash[i] = maxOf(0f, beamFlash[i] - fl)
        for (i in 0 until snap.deviceCount) {
            if (devFlash[i] > 0f) devFlash[i] = maxOf(0f, devFlash[i] - fl)
            if (devRecoil[i] > 0f) devRecoil[i] = maxOf(0f, devRecoil[i] - dt * 3.2f)
            if ((snap.deviceFlags[i] and DeviceFlags.ALIVE) != 0) {
                val t = snap.deviceType[i]
                val kind = if (t >= 0 && t < devKindOfType.size) devKindOfType[t] else DevKind.GENERIC
                val w = snap.wind
                if (kind == DevKind.TURBINE) devSpin[i] += (0.4f + (if (w < 0f) -w else w) * 0.7f) * (if (w < 0f) -1f else 1f) * dt
                else if (kind == DevKind.MINE) devSpin[i] += 2.4f * dt
                else if (kind == DevKind.REACTOR) {
                    // Puls wird schneller, je mehr Schaden der Reaktor hat (Phase integriert, kein Strobo bei Dauerschaden)
                    val hp = snap.deviceHp01[i].coerceIn(0f, 1f)
                    devPhase[i] += (2.6f + (1f - hp) * 7f) * dt
                    if (devPhase[i] > SceneContext.TAU * 64f) devPhase[i] -= SceneContext.TAU * 64f
                }
            }
        }
        var i = 0
        while (i < trCount) {
            trAge[i] += dt
            if (trAge[i] >= TRACER_LIFE) {
                val l = trCount - 1
                trX0[i] = trX0[l]; trY0[i] = trY0[l]; trX1[i] = trX1[l]; trY1[i] = trY1[l]; trAge[i] = trAge[l]
                trCount--
            } else i++
        }
        cloudPhase += (0.004f + snap.wind * 0.0016f) * dt
    }

    companion object {
        const val MAX_DECALS = 28
        const val MAX_TRACERS = 24
        const val TRACER_LIFE = 0.09f
    }
}
