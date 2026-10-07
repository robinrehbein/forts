package de.bollwerk.ai

import de.bollwerk.engine.math.ClosestParams
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.math.Geometry
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.view.GameView
import kotlin.math.sqrt

/** Ergebnis eines [Obstacles.cast]. */
class CastHit {
    var kind: Int = NONE
    /** Balken- bzw. Geräte-Slot. */
    var id: Int = -1
    var owner: Int = -1
    /** Parameter 0..1 auf dem Segment. */
    var t: Float = 2f
    var x: Float = 0f
    var y: Float = 0f
    /** Erste eigene geschlossene Tür, die überquert (aber als passierbar gewertet) wurde, oder −1. */
    var doorCrossed: Int = -1

    fun reset() { kind = NONE; id = -1; owner = -1; t = 2f; doorCrossed = -1 }

    companion object {
        const val NONE = 0
        const val BEAM = 1
        const val DEVICE = 2
        const val TERRAIN = 3
    }
}

/**
 * Momentaufnahme aller Hindernisse (Balken als Kapseln, Geräte als Kreise) für die Schusslinien-Prüfung der KI.
 * Je Denkschritt einmal aus der [GameView] aufgebaut (nie aus dem `GameState`), danach allokationsfrei abfragbar.
 * Die Tests entsprechen dem Swept-Test der Simulation (`Geometry.sweptCapsule`/`sweptCircle`, offene Türen
 * passierbar), damit Vorhersage und Flug übereinstimmen.
 */
class Obstacles {
    var beamCount: Int = 0; private set
    private var bId = IntArray(INITIAL)
    private var bOwner = IntArray(INITIAL)
    private var bDoor = BooleanArray(INITIAL)
    private var bAx = FloatArray(INITIAL)
    private var bAy = FloatArray(INITIAL)
    private var bBx = FloatArray(INITIAL)
    private var bBy = FloatArray(INITIAL)
    private var bHalf = FloatArray(INITIAL)
    private var bMinX = FloatArray(INITIAL)
    private var bMinY = FloatArray(INITIAL)
    private var bMaxX = FloatArray(INITIAL)
    private var bMaxY = FloatArray(INITIAL)

    var deviceCount: Int = 0; private set
    private var dId = IntArray(INITIAL)
    private var dOwner = IntArray(INITIAL)
    private var dBeam = IntArray(INITIAL)
    private var dX = FloatArray(INITIAL)
    private var dY = FloatArray(INITIAL)
    private var dR = FloatArray(INITIAL)

    private lateinit var lastView: GameView
    private val cp = ClosestParams()
    private val geo = FloatArray(DeviceGeometry.SIZE)

    /** Aufbau aus dem aktuellen Zustand (alle lebenden Balken außer offenen Türen, alle lebenden Geräte). */
    fun rebuild(view: GameView) {
        lastView = view
        val beams = view.beamView
        val nodes = view.nodeView
        val mats = view.tables.materials
        beamCount = 0
        for (j in 0 until beams.size) {
            if (!beams.isAlive(j)) continue
            val f = beams.flags(j)
            if ((f and BeamFlags.DOOR_OPEN) != 0) continue
            val m = beams.material(j)
            if (m < 0 || m >= mats.size) continue
            if (beamCount == bId.size) growBeams()
            val k = beamCount++
            val a = beams.nodeA(j); val b = beams.nodeB(j)
            val ax = nodes.x(a); val ay = nodes.y(a); val bx = nodes.x(b); val by = nodes.y(b)
            val half = mats[m].thickness * 0.5f
            bId[k] = j; bOwner[k] = beams.owner(j); bDoor[k] = mats[m].isDoor
            bAx[k] = ax; bAy[k] = ay; bBx[k] = bx; bBy[k] = by; bHalf[k] = half
            val pad = half + PAD
            bMinX[k] = FloatMath.min(ax, bx) - pad; bMaxX[k] = FloatMath.max(ax, bx) + pad
            bMinY[k] = FloatMath.min(ay, by) - pad; bMaxY[k] = FloatMath.max(ay, by) + pad
        }
        val d = view.deviceView
        val props = view.tables.devices
        val scale = view.simConfig.combat.deviceRayRadiusScale
        deviceCount = 0
        for (i in 0 until d.size) {
            if (!d.isAlive(i)) continue
            val ty = d.type(i)
            if (ty < 0 || ty >= props.size) continue
            if (deviceCount == dId.size) growDevices()
            val k = deviceCount++
            val p = props[ty]
            dId[k] = i; dOwner[k] = d.owner(i); dBeam[k] = d.beam(i)
            // aus den Knoten berechnet (die DERIVED-Felder des Pools sind vor dem ersten Tick noch leer)
            val b = d.beam(i)
            if (b >= 0 && b < view.beamView.size) {
                DeviceGeometry.mount(view, i, geo)
                dX[k] = geo[DeviceGeometry.CX]; dY[k] = geo[DeviceGeometry.CY]
            } else {
                dX[k] = d.x(i) + d.nx(i) * p.mountOffset
                dY[k] = d.y(i) + d.ny(i) * p.mountOffset
            }
            dR[k] = p.hitRadius * scale
        }
    }

    /**
     * Frühester Treffer des Kreises (Radius [radius]) auf dem Weg P0 → P1.
     * @param ignoreBeam Balken-Slot, der übersprungen wird (Montagebalken der eigenen Waffe), samt Geräten darauf.
     * @param passOwner eigene **geschlossene** Türen dieses Spielers gelten als passierbar (die KI kann sie öffnen);
     *   die erste davon steht in [CastHit.doorCrossed]. −1 = Türen blockieren.
     * @param autoDoor diese Tür (Slot) öffnet sich beim Schuss ohnehin und wird nicht gemeldet; −1 = keine.
     * @param withTerrain auch das Gelände prüfen.
     * @param ignoreBeam2 weiterer übersprungener Balken (schon durchschlagener Balken beim Scharfschützen), −1 = keiner.
     * @return `true` bei einem Treffer.
     */
    fun cast(
        x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, ignoreBeam: Int, passOwner: Int, autoDoor: Int,
        withTerrain: Boolean, out: CastHit, ignoreBeam2: Int = -1,
    ): Boolean {
        out.reset()
        val minX = FloatMath.min(x0, x1) - radius; val maxX = FloatMath.max(x0, x1) + radius
        val minY = FloatMath.min(y0, y1) - radius; val maxY = FloatMath.max(y0, y1) + radius
        var doorT = 2f
        var door = -1
        for (k in 0 until beamCount) {
            if (bMaxX[k] < minX || bMinX[k] > maxX || bMaxY[k] < minY || bMinY[k] > maxY) continue
            val id = bId[k]
            if (id == ignoreBeam || id == ignoreBeam2 || id == autoDoor) continue
            val t = Geometry.sweptCapsule(x0, y0, x1, y1, radius, bAx[k], bAy[k], bBx[k], bBy[k], bHalf[k], cp)
            if (t < 0f) continue
            if (bDoor[k] && bOwner[k] == passOwner && passOwner >= 0) {
                if (t < doorT) { doorT = t; door = id }
                continue
            }
            if (t < out.t) { out.kind = CastHit.BEAM; out.id = id; out.owner = bOwner[k]; out.t = t }
        }
        for (k in 0 until deviceCount) {
            if (ignoreBeam >= 0 && dBeam[k] == ignoreBeam) continue
            val rr = dR[k] + radius
            if (dX[k] + rr < minX || dX[k] - rr > maxX || dY[k] + rr < minY || dY[k] - rr > maxY) continue
            val t = Geometry.sweptCircle(x0, y0, x1, y1, radius, dX[k], dY[k], dR[k])
            if (t < 0f) continue
            if (t < out.t) { out.kind = CastHit.DEVICE; out.id = dId[k]; out.owner = dOwner[k]; out.t = t }
        }
        if (withTerrain) {
            val tt = terrainHit(x0, y0, x1, y1)
            if (tt >= 0f && tt < out.t) { out.kind = CastHit.TERRAIN; out.id = -1; out.owner = -1; out.t = tt }
        }
        if (door >= 0 && doorT <= out.t) out.doorCrossed = door
        if (out.kind == CastHit.NONE) return false
        out.x = x0 + (x1 - x0) * out.t
        out.y = y0 + (y1 - y0) * out.t
        return true
    }

    private fun terrainHit(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val terrain = lastView.terrain
        if (y0 > terrain.heightAt(x0)) return 0f
        val dx = x1 - x0; val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        var n = (len / TERRAIN_STEP).toInt() + 1
        if (n < 1) n = 1
        for (i in 1..n) {
            val t = i.toFloat() / n
            if (y0 + dy * t >= terrain.heightAt(x0 + dx * t)) return t
        }
        return -1f
    }

    /** Mittelpunkt und Radius eines Geräts aus der Aufnahme; @return false, wenn nicht vorhanden. */
    fun deviceCircle(deviceId: Int, out: FloatArray): Boolean {
        for (k in 0 until deviceCount) if (dId[k] == deviceId) {
            out[0] = dX[k]; out[1] = dY[k]; out[2] = dR[k]
            return true
        }
        return false
    }

    private fun growBeams() {
        val n = bId.size * 2
        bId = bId.copyOf(n); bOwner = bOwner.copyOf(n); bDoor = bDoor.copyOf(n)
        bAx = bAx.copyOf(n); bAy = bAy.copyOf(n); bBx = bBx.copyOf(n); bBy = bBy.copyOf(n); bHalf = bHalf.copyOf(n)
        bMinX = bMinX.copyOf(n); bMinY = bMinY.copyOf(n); bMaxX = bMaxX.copyOf(n); bMaxY = bMaxY.copyOf(n)
    }

    private fun growDevices() {
        val n = dId.size * 2
        dId = dId.copyOf(n); dOwner = dOwner.copyOf(n); dBeam = dBeam.copyOf(n)
        dX = dX.copyOf(n); dY = dY.copyOf(n); dR = dR.copyOf(n)
    }

    private companion object {
        const val INITIAL = 128
        /** Zusätzlicher Rand der Balken-AABB (≥ größter Projektilradius). */
        const val PAD = 0.3f
        const val TERRAIN_STEP = 0.5f
    }
}
