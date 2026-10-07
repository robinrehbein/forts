package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.view.GameView

/** Wie ein Balkenende aufgelöst wurde. */
enum class EndKind {
    /** Bestehender eigener Knoten (auch: freie Position im Verschmelz-Radius). */
    NODE,
    /** Punkt auf einem bestehenden eigenen Balken: Balken wird geteilt. */
    SPLIT,
    /** Neuer freier Knoten. */
    FREE,
}

/** Aufgelöstes Balkenende. [node] (Slot) bei [EndKind.NODE], [beam] (Slot) und [t] bei [EndKind.SPLIT]. */
class EndPoint {
    var kind: EndKind = EndKind.FREE
    var node: Int = -1
    var beam: Int = -1
    var t: Float = 0f
    var x: Float = 0f
    var y: Float = 0f
}

/**
 * Ergebnis der Auflösung eines `Command.PlaceBeam` gegen einen Zustand: die zwei Enden, Länge und Kosten.
 * Gültig nur, wenn [BeamPlanner.plan] `null` zurückgab (für Werkzeuge: bei einer Ablehnung sind Enden/Länge/Kosten,
 * soweit schon berechnet, trotzdem gefüllt, damit der Ghost-Balken Länge und Kosten anzeigen kann).
 */
class BeamPlan {
    val a: EndPoint = EndPoint()
    val b: EndPoint = EndPoint()
    var length: Float = 0f
    var cost: Float = 0f

    /**
     * Beide Enden sind aufgelöst ([a]/[b] gültig und aktuell), auch wenn [BeamPlanner.plan] danach ablehnt (z. B.
     * `OUT_OF_BUILD_ZONE`, `TOO_LONG`): Werkzeuge zeigen dann Ende und Länge trotzdem. `false` bei Ablehnung davor
     * (unbekanntes Material, Tech, veraltete/fremde Refs).
     */
    var resolved: Boolean = false
}

/**
 * Regeln für `PlaceBeam` (Prototyp `evalBuild`): ein und dieselbe Auflösung für Validator, Anwendung, Ghost-Vorschau
 * (Werkzeuge) und KI. Reihenfolge der Prüfungen (die erste Verletzung gewinnt): Tech → Besitz/Verbindung der Enden →
 * Baubereich → Länge → Gelände → Duplikat → Metall.
 */
object BeamPlanner {
    /**
     * Löst [cmd] auf und prüft die Bauregeln.
     * @return `null`, wenn gültig, sonst der Grund. [out] wird (teilweise) gefüllt.
     */
    fun plan(view: GameView, cmd: Command.PlaceBeam, out: BeamPlan = BeamPlan()): RejectReason? {
        val owner = cmd.playerId
        val tables = view.tables
        out.resolved = false
        if (cmd.materialId !in tables.materials.indices) return RejectReason.UNKNOWN_CONTENT
        val mat = tables.materials[cmd.materialId]
        RuleChecks.techBlock(view, owner, mat.requiredTech)?.let { return it }

        resolve(view, owner, cmd.aNodeRef, cmd.aBeamRef, cmd.aBeamT, cmd.aX, cmd.aY, out.a)?.let { return it }
        resolve(view, owner, cmd.bNodeRef, cmd.bBeamRef, cmd.bBeamT, cmd.bX, cmd.bY, out.b)?.let { return it }
        out.resolved = true
        val a = out.a
        val b = out.b
        if (a.kind == EndKind.NODE && b.kind == EndKind.NODE && a.node == b.node) return RejectReason.INVALID_TARGET
        if (a.kind == EndKind.SPLIT && b.kind == EndKind.SPLIT && a.beam == b.beam) return RejectReason.INVALID_TARGET
        if (a.kind == EndKind.FREE && b.kind == EndKind.FREE) return RejectReason.NOT_CONNECTED

        val map = view.map
        if (a.kind != EndKind.NODE && !map.inBuildZone(owner, a.x)) return RejectReason.OUT_OF_BUILD_ZONE
        if (b.kind != EndKind.NODE && !map.inBuildZone(owner, b.x)) return RejectReason.OUT_OF_BUILD_ZONE

        val len = RuleChecks.dist(a.x, a.y, b.x, b.y)
        out.length = len
        out.cost = RuleCost.beam(len, mat.costPerMeter)
        val cfg = view.simConfig
        if (len < cfg.minBeamLength - RuleConst.LENGTH_EPS) return RejectReason.TOO_SHORT
        if (len > cfg.maxBeamLength + RuleConst.LENGTH_EPS) return RejectReason.TOO_LONG

        if (crossesTerrain(view, a.x, a.y, b.x, b.y, len)) return RejectReason.BLOCKED_BY_TERRAIN

        if (duplicates(view, a, b)) return RejectReason.DUPLICATE_BEAM
        if (out.cost > RuleChecks.availableMetal(view, owner)) return RejectReason.NOT_ENOUGH_METAL
        return null
    }

    /** Löst ein Ende nach der Priorität Knoten → Balken (Teilen) → freie Position auf. */
    private fun resolve(
        view: GameView, owner: Int, nodeRef: Long, beamRef: Long, beamT: Float, fx: Float, fy: Float, out: EndPoint,
    ): RejectReason? {
        val nodes = view.nodeView
        val beams = view.beamView
        if (nodeRef >= 0L) {
            val id = nodes.resolve(nodeRef)
            if (id < 0) return RejectReason.STALE_TARGET
            if (nodes.owner(id) != owner) return RejectReason.NOT_OWNER
            if ((nodes.flags(id) and NodeFlags.DEBRIS) != 0) return RejectReason.NOT_CONNECTED
            out.kind = EndKind.NODE; out.node = id; out.beam = -1; out.t = 0f
            out.x = nodes.x(id); out.y = nodes.y(id)
            return null
        }
        if (beamRef >= 0L) {
            val id = beams.resolve(beamRef)
            if (id < 0) return RejectReason.STALE_TARGET
            if (beams.owner(id) != owner) return RejectReason.NOT_OWNER
            if ((beams.flags(id) and BeamFlags.DEBRIS) != 0) return RejectReason.NOT_CONNECTED
            val mat = view.tables.materials[beams.material(id)]
            if (mat.isDoor) return RejectReason.INVALID_TARGET
            val t = splitT(beamT)
            val na = beams.nodeA(id)
            val nb = beams.nodeB(id)
            val ax = nodes.x(na); val ay = nodes.y(na)
            val bx = nodes.x(nb); val by = nodes.y(nb)
            val geo = RuleChecks.dist(ax, ay, bx, by)
            if (geo * FloatMath.min(t, 1f - t) < RuleConst.MIN_SPLIT_PIECE) return RejectReason.INVALID_TARGET
            out.kind = EndKind.SPLIT; out.node = -1; out.beam = id; out.t = t
            out.x = ax + (bx - ax) * t; out.y = ay + (by - ay) * t
            return null
        }
        if (!fx.isFinite() || !fy.isFinite()) return RejectReason.INVALID_TARGET
        if (view.map.isOutOfBounds(fx, fy)) return RejectReason.INVALID_TARGET
        val merge = view.simConfig.nodeMergeRadius
        val r2 = merge * merge
        var best = -1
        var bestD = r2
        for (i in 0 until nodes.size) {
            if (!nodes.isAlive(i) || nodes.owner(i) != owner || (nodes.flags(i) and NodeFlags.DEBRIS) != 0) continue
            val dx = nodes.x(i) - fx
            val dy = nodes.y(i) - fy
            val d2 = dx * dx + dy * dy
            if (d2 <= bestD) { bestD = d2; best = i }
        }
        if (best >= 0) {
            out.kind = EndKind.NODE; out.node = best; out.beam = -1; out.t = 0f
            out.x = nodes.x(best); out.y = nodes.y(best)
        } else {
            out.kind = EndKind.FREE; out.node = -1; out.beam = -1; out.t = 0f
            out.x = fx; out.y = fy
        }
        return null
    }

    /**
     * Würde der neue Balken einen bestehenden verdoppeln? Knoten ↔ Knoten mit schon vorhandenem Balken, oder Knoten ↔
     * Teilungspunkt auf einem Balken, an dem der Knoten selbst hängt (der neue Balken läge genau auf einer der beiden Hälften).
     */
    private fun duplicates(view: GameView, a: EndPoint, b: EndPoint): Boolean {
        if (a.kind == EndKind.NODE && b.kind == EndKind.NODE) return beamBetween(view, a.node, b.node)
        if (a.kind == EndKind.NODE && b.kind == EndKind.SPLIT) return touches(view, b.beam, a.node)
        if (b.kind == EndKind.NODE && a.kind == EndKind.SPLIT) return touches(view, a.beam, b.node)
        return false
    }

    private fun touches(view: GameView, beam: Int, node: Int): Boolean =
        view.beamView.nodeA(beam) == node || view.beamView.nodeB(beam) == node

    /** Geklemmter Teilungsparameter (nach `Command.PlaceBeam` 2.). */
    fun splitT(t: Float): Float = FloatMath.clamp(t, RuleConst.SPLIT_T_MIN, RuleConst.SPLIT_T_MAX)

    /** Verläuft das Segment unter der Geländeoberfläche (Toleranz [RuleConst.TERRAIN_EPS])? */
    fun crossesTerrain(view: GameView, ax: Float, ay: Float, bx: Float, by: Float, len: Float): Boolean {
        val terrain = view.terrain
        val steps = (len / RuleConst.TERRAIN_STEP).toInt() + 1
        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val x = ax + (bx - ax) * t
            val y = ay + (by - ay) * t
            if (y > terrain.heightAt(x) + RuleConst.TERRAIN_EPS) return true
        }
        return false
    }

    /**
     * Verbindet schon ein lebender Balken die Knoten [na] und [nb]? Scannt den Pool (die Adjazenz ist erst nach dem
     * Topologie-Neuaufbau aktuell, aber im selben Tick können schon Balken von früheren Commands existieren).
     */
    fun beamBetween(view: GameView, na: Int, nb: Int): Boolean {
        val beams = view.beamView
        for (j in 0 until beams.size) {
            if (!beams.isAlive(j)) continue
            val a = beams.nodeA(j)
            val b = beams.nodeB(j)
            if ((a == na && b == nb) || (a == nb && b == na)) return true
        }
        return false
    }
}
