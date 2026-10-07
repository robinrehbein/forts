package de.bollwerk.engine.tools

import de.bollwerk.engine.math.ClosestParams
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.math.Geometry
import de.bollwerk.engine.rules.RuleChecks
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.view.GameView
import kotlin.math.sqrt

/** Welche Balken kommen als Ziel in Frage? */
internal enum class BeamFilter {
    /** Eigener, lebender Balken, kein Trümmer, keine Tür (Bauen: Teilen). */
    SPLITTABLE,
    /** Eigener Balken, auf dem ein Gerät stehen kann (kein Trümmer, keine Tür, kein Seil). */
    DEVICE_HOST,
    /** Eigener Balken, kein Trümmer. */
    ANY,
    /** Eigene Tür (Material mit `isDoor`), kein Trümmer. */
    DOOR,
}

/**
 * Auswahl von Knoten, Balken und Waffen in Welt-Koordinaten (Prototyp `nearestNode`/`nearestBeam`/`pickWeapon`).
 * Allokationsfrei; ein Exemplar je Werkzeug (nicht thread-sicher, Trefferparameter in [hitT]).
 */
internal class Picker {
    private val cp = ClosestParams()
    private val geo = FloatArray(DeviceGeometry.SIZE)

    /** Balkenparameter (0..1 von Ende A) des letzten Treffers von [nearestBeam]. */
    var hitT: Float = 0f
        private set

    /** Nächster eigener Knoten (Slot) innerhalb [radius] um ([x], [y]) oder −1; [exclude] wird übersprungen. */
    fun nearestNode(view: GameView, owner: Int, x: Float, y: Float, radius: Float, exclude: Int): Int {
        val nodes = view.nodeView
        var best = -1
        var bestD2 = radius * radius
        for (i in 0 until nodes.size) {
            if (i == exclude || !nodes.isAlive(i) || nodes.owner(i) != owner || (nodes.flags(i) and NodeFlags.DEBRIS) != 0) continue
            val dx = nodes.x(i) - x
            val dy = nodes.y(i) - y
            val d2 = dx * dx + dy * dy
            if (d2 <= bestD2) { bestD2 = d2; best = i }
        }
        return best
    }

    /**
     * Nächster passender eigener Balken (Slot) mit Abstand zur Außenkante (Mitte − halbe Dicke) unter [radius] oder −1.
     * [hitT] ist der Parameter des Fußpunkts. [exclude] (Slot) wird übersprungen.
     */
    fun nearestBeam(view: GameView, owner: Int, x: Float, y: Float, radius: Float, filter: BeamFilter, exclude: Int): Int {
        val beams = view.beamView
        val nodes = view.nodeView
        val mats = view.tables.materials
        var best = -1
        var bestD = radius
        var bestT = 0f
        for (i in 0 until beams.size) {
            if (i == exclude || !beams.isAlive(i) || beams.owner(i) != owner) continue
            if ((beams.flags(i) and BeamFlags.DEBRIS) != 0) continue
            val mat = mats[beams.material(i)]
            when (filter) {
                BeamFilter.SPLITTABLE -> if (mat.isDoor) continue
                BeamFilter.DEVICE_HOST -> if (mat.isDoor || mat.tensionOnly) continue
                BeamFilter.ANY -> {}
                BeamFilter.DOOR -> if (!mat.isDoor) continue
            }
            val a = beams.nodeA(i)
            val b = beams.nodeB(i)
            val d = sqrt(Geometry.pointSegmentDistSq(x, y, nodes.x(a), nodes.y(a), nodes.x(b), nodes.y(b), cp)) - mat.thickness * 0.5f
            if (d < bestD) { bestD = d; best = i; bestT = cp.t }
        }
        hitT = bestT
        return best
    }

    /**
     * Nächstes eigenes Gerät (Slot) mit Trefferzentrum innerhalb `max(minRadius, hitRadius)`; [weaponsOnly] beschränkt auf
     * Waffen. Bei mehreren das mit dem geringsten Abstand. Geräte auf Trümmern zählen nicht.
     */
    fun nearestDevice(view: GameView, owner: Int, x: Float, y: Float, minRadius: Float, weaponsOnly: Boolean): Int {
        val devices = view.deviceView
        val beams = view.beamView
        var best = -1
        var bestD2 = Float.MAX_VALUE
        for (i in 0 until devices.size) {
            if (!devices.isAlive(i) || devices.owner(i) != owner) continue
            val props = view.tables.devices[devices.type(i)]
            if (weaponsOnly && props.weapon < 0) continue
            val beam = devices.beam(i)
            if (beam < 0 || !beams.isAlive(beam) || (beams.flags(beam) and BeamFlags.DEBRIS) != 0) continue
            DeviceGeometry.mount(view, i, geo)
            val dx = geo[DeviceGeometry.CX] - x
            val dy = geo[DeviceGeometry.CY] - y
            val d2 = dx * dx + dy * dy
            val r = FloatMath.max(minRadius, props.hitRadius)
            if (d2 < r * r && d2 < bestD2) { bestD2 = d2; best = i }
        }
        return best
    }

    /** Trefferzentrum von Gerät [id] (Welt) in [out] (x, y). */
    fun deviceCenter(view: GameView, id: Int, out: FloatArray) {
        DeviceGeometry.mount(view, id, geo)
        out[0] = geo[DeviceGeometry.CX]
        out[1] = geo[DeviceGeometry.CY]
    }

    /** Punkt auf Balken [beam] bei [t] (Welt) in [out] (x, y). */
    fun beamPoint(view: GameView, beam: Int, t: Float, out: FloatArray) {
        val beams = view.beamView
        val nodes = view.nodeView
        val a = beams.nodeA(beam)
        val b = beams.nodeB(beam)
        out[0] = nodes.x(a) + (nodes.x(b) - nodes.x(a)) * t
        out[1] = nodes.y(a) + (nodes.y(b) - nodes.y(a)) * t
    }

    /** Länge des Balkens zwischen seinen Knoten. */
    fun beamLength(view: GameView, beam: Int): Float {
        val beams = view.beamView
        val nodes = view.nodeView
        val a = beams.nodeA(beam)
        val b = beams.nodeB(beam)
        return RuleChecks.dist(nodes.x(a), nodes.y(a), nodes.x(b), nodes.y(b))
    }
}
