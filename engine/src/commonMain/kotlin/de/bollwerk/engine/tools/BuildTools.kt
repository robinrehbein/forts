package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.rules.BeamPlan
import de.bollwerk.engine.rules.BeamPlanner
import de.bollwerk.engine.rules.EndKind
import de.bollwerk.engine.rules.RuleCost
import de.bollwerk.engine.rules.RuleChecks
import de.bollwerk.engine.sim.NodeFlags
import de.bollwerk.engine.view.GameView
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Bauwerkzeug (Stil-Bibel §7, Prototyp `snapStart`/`evalBuild`/`commitBuild`):
 *
 * - **Down** rastet am nächsten eigenen Knoten innerhalb `pickRadiusM` ein, sonst teilt es den nächsten eigenen Balken
 *   (Teilungspunkt, `t` auf 0,08..0,92 geklemmt). Ohne Treffer beginnt keine Geste (`state` bleibt [BuildToolState.Idle],
 *   der Eingabe-Controller darf die Kamera schwenken), außer `ToolSettings.requireConnectedStart` ist aus.
 * - **Move** zeigt den Ghost-Balken: Ende B rastet an einem Knoten (Vorrang), an einem Balken (Teilung) oder frei, mit
 *   15°-Winkelrasten, Längenrasten auf die Höchstlänge (6 m) und Bodenrasten. Gültigkeit und Grund liefert **derselbe
 *   Validator wie die Sim** (`ToolContext.validator`, normal `RulesValidator`) auf dem `Command.PlaceBeam`; Enden, Länge
 *   und Kosten kommen aus `BeamPlanner.plan` (dieselbe Auflösung wie in der Sim, inkl. Verschmelzen und Teilungs-Klemmung).
 * - **Up** liefert bei gültigem Ghost das `PlaceBeam`. Im Kettenmodus (`ToolSettings.chainMode`) beginnt danach der nächste
 *   Balken am neuen Ende (State `FirstNodeSelected`, [chainArmed]); ein Loslassen nahe dem Kettenanker beendet die Kette.
 *   Rastet das Ende an einem Knoten ein, ist der Anker dessen Ref (folgt dem Knoten, auch wenn die Physik ihn bewegt). Bei
 *   Teilung/freiem Ende gibt es den neuen Knoten erst, wenn die Sim das Command ausgeführt hat: der Anker bleibt bis dahin
 *   "ausstehend" (Ghost `pending`, kein Command) und wird in [refresh]/Down/Move über die Knoten-Uid auf den neu
 *   entstandenen eigenen Knoten abgebildet; ohne Knoten nach [ToolConst.CHAIN_PENDING_TICKS] Ticks endet die Kette.
 */
class DefaultBuildTool(private val settings: ToolSettings = ToolSettings()) : BuildTool {
    override var state: BuildToolState = BuildToolState.Idle
        private set
    override var material: Int = 0

    /** Kette aktiv: `state` ist `FirstNodeSelected` am Ende des letzten Balkens, kein Finger unten. */
    var chainArmed: Boolean = false
        private set

    /** Finger liegt (Geste läuft). */
    var pressed: Boolean = false
        private set

    /** Fingerposition (Welt) der laufenden Geste. */
    var fingerX: Float = 0f
        private set
    var fingerY: Float = 0f
        private set

    /** Grund, warum das letzte Loslassen kein Command ergab (ungültiger Ghost), sonst `null`. */
    var lastReject: RejectReason? = null
        private set

    /** Einrast-Markierungen für das Overlay (Start, ggf. Ende). */
    var snaps: List<SnapMark> = emptyList()
        private set

    private val picker = Picker()
    private val plan = BeamPlan()
    private val tmp = FloatArray(2)
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastCommand: Command.PlaceBeam? = null

    /** Start der laufenden Geste bzw. Kettenanker (Quelle der Wahrheit; [state] spiegelt ihn). */
    private var anchor: BuildToolState.FirstNodeSelected? = null

    /** Kette wartet auf den neuen Endknoten: Uid-Grenze (Knoten mit größerer Uid sind neu) oder [NO_PENDING]. */
    private var pendingBaseUid: Int = NO_PENDING
    private var pendingX = 0f
    private var pendingY = 0f
    private var pendingTick = 0L

    override fun onDown(worldX: Float, worldY: Float, ctx: ToolContext) {
        lastReject = null
        pressed = false
        moved = false
        if (chainArmed && !settings.chainMode) reset()
        fingerX = worldX; fingerY = worldY
        downX = worldX; downY = worldY
        if (chainArmed && trackChain(ctx)) {
            val a = anchor
            if (a != null) {
                pressed = true
                previewAt(worldX, worldY, ctx, a)
                return
            }
        }
        // frischer Start (auch, wenn die Kette gerade wegen eines verschwundenen Ankers endete)
        anchor = null
        state = BuildToolState.Idle
        snaps = emptyList()
        var from = snapStart(worldX, worldY, ctx)
        if (from == null) {
            if (settings.requireConnectedStart) return
            from = BuildToolState.FirstNodeSelected(-1L, -1L, 0f, worldX, worldY)
        }
        pressed = true
        anchor = from
        state = from
        setSnaps(from.x, from.y, from.nodeRef, false, 0f, 0f, -1L)
    }

    override fun onMove(worldX: Float, worldY: Float, ctx: ToolContext) {
        if (!pressed) return
        fingerX = worldX; fingerY = worldY
        if (chainArmed && !trackChain(ctx)) { pressed = false; return }
        val from = anchor ?: return
        if (!moved && !chainArmed) {
            if (dist(downX, downY, worldX, worldY) < slop(ctx)) return
            moved = true
        }
        previewAt(worldX, worldY, ctx, from)
    }

    override fun onUp(worldX: Float, worldY: Float, ctx: ToolContext): Command? {
        lastReject = null
        if (!pressed) return null
        pressed = false
        fingerX = worldX; fingerY = worldY
        val wasChain = chainArmed
        if (wasChain && !trackChain(ctx)) return null
        val from = anchor
        if (from == null) { reset(); return null }
        if (wasChain) {
            startPos(ctx.view, from, tmp)
            if (dist(tmp[0], tmp[1], worldX, worldY) <= ctx.pickRadiusM) { reset(); return null }
        } else if (!moved && dist(downX, downY, worldX, worldY) < slop(ctx)) {
            reset()
            return null
        }
        val ghost = evalEnd(worldX, worldY, ctx, from)
        val cmd = lastCommand
        if (ghost.pending) {
            // Anker noch nicht in der Sim: weder Command noch Ablehnung, die Kette bleibt
            keepAnchor(from)
            return null
        }
        if (!ghost.valid || cmd == null) {
            lastReject = ghost.reason
            if (wasChain) keepAnchor(from) else reset()
            return null
        }
        if (settings.chainMode) {
            chainArmed = true
            if (cmd.bNodeRef >= 0L) {
                // Ende rastet an einem bestehenden Knoten: Anker = Ref, folgt dem Knoten
                setAnchor(BuildToolState.FirstNodeSelected(cmd.bNodeRef, -1L, 0f, ghost.bx, ghost.by))
                pendingBaseUid = NO_PENDING
            } else {
                // neuer Knoten entsteht erst beim Ausführen: Anker ausstehend
                setAnchor(BuildToolState.FirstNodeSelected(-1L, -1L, 0f, ghost.bx, ghost.by))
                pendingBaseUid = maxUid(ctx.view)
                pendingX = ghost.bx; pendingY = ghost.by
                pendingTick = ctx.view.tick
            }
        } else {
            reset()
        }
        return cmd
    }

    /**
     * Bricht die laufende Geste ab (kein Command). Eine armierte Kette bleibt dabei bestehen (z. B. wenn ein zweiter Finger
     * zum Zoomen kommt oder die App pausiert, auch ohne Finger); [reset] beendet auch die Kette.
     */
    override fun cancel() {
        val a = anchor
        if (chainArmed && a != null) {
            pressed = false
            moved = false
            keepAnchor(a)
            return
        }
        reset()
    }

    /** Verwirft Geste **und** Kette (Werkzeugwechsel). */
    fun reset() {
        pressed = false
        moved = false
        chainArmed = false
        anchor = null
        pendingBaseUid = NO_PENDING
        state = BuildToolState.Idle
        snaps = emptyList()
        lastCommand = null
    }

    /**
     * Pro Frame/Tick aufrufen: führt den Kettenanker nach (ausstehenden Endknoten auf den neuen Knoten abbilden, Anker
     * folgt dem Knoten, Kette endet bei Verlust) und aktualisiert bei laufender Geste den Ghost.
     */
    fun refresh(ctx: ToolContext) {
        if (!trackChain(ctx)) return
        val a = anchor ?: return
        if (pressed) {
            previewAt(fingerX, fingerY, ctx, a)
        } else if (chainArmed) {
            startPos(ctx.view, a, tmp)
            if (FloatMath.abs(tmp[0] - a.x) > ANCHOR_EPS || FloatMath.abs(tmp[1] - a.y) > ANCHOR_EPS) {
                setAnchor(BuildToolState.FirstNodeSelected(a.nodeRef, a.beamRef, a.beamT, tmp[0], tmp[1]))
            }
        }
    }

    private fun slop(ctx: ToolContext): Float = ctx.pickRadiusM * ToolConst.DRAG_SLOP_FACTOR

    private fun setAnchor(a: BuildToolState.FirstNodeSelected) {
        anchor = a
        state = a
        setSnaps(a.x, a.y, a.nodeRef, false, 0f, 0f, -1L)
    }

    /** Ohne Finger auf den Anker zurück (Kette bleibt). */
    private fun keepAnchor(a: BuildToolState.FirstNodeSelected) {
        state = a
        setSnaps(a.x, a.y, a.nodeRef, false, 0f, 0f, -1L)
    }

    /**
     * Kettenanker nachführen. @return `false`, wenn die Kette dabei endete (Anker-Knoten zerstört bzw. neuer Endknoten
     * nie entstanden); sonst `true` (auch ohne Kette).
     */
    private fun trackChain(ctx: ToolContext): Boolean {
        if (!chainArmed) return true
        val a = anchor ?: run { reset(); return false }
        val view = ctx.view
        if (a.nodeRef >= 0L) {
            val s = view.nodeView.resolve(a.nodeRef)
            if (s < 0) { reset(); return false }
            val nx = view.nodeView.x(s)
            val ny = view.nodeView.y(s)
            if (FloatMath.abs(nx - a.x) > ANCHOR_EPS || FloatMath.abs(ny - a.y) > ANCHOR_EPS) {
                val moved = BuildToolState.FirstNodeSelected(a.nodeRef, -1L, 0f, nx, ny)
                anchor = moved
                if (!pressed) { state = moved; setSnaps(nx, ny, a.nodeRef, false, 0f, 0f, -1L) }
            }
            return true
        }
        if (pendingBaseUid == NO_PENDING) return true
        val n = findNewNode(view, ctx.playerId)
        if (n >= 0) {
            pendingBaseUid = NO_PENDING
            val resolved = BuildToolState.FirstNodeSelected(view.nodeView.ref(n), -1L, 0f, view.nodeView.x(n), view.nodeView.y(n))
            anchor = resolved
            if (!pressed) { state = resolved; setSnaps(resolved.x, resolved.y, resolved.nodeRef, false, 0f, 0f, -1L) }
            return true
        }
        if (view.tick - pendingTick > ToolConst.CHAIN_PENDING_TICKS) { reset(); return false }
        return true
    }

    /** Größte Uid aller lebenden Knoten (−1 ohne Knoten): später entstandene Knoten haben eine größere. */
    private fun maxUid(view: GameView): Int {
        val nodes = view.nodeView
        var m = -1
        for (i in 0 until nodes.size) if (nodes.isAlive(i) && nodes.uid(i) > m) m = nodes.uid(i)
        return m
    }

    /** Nächster eigener Knoten, der nach dem Absenden des Commands entstanden ist (Uid > Grenze), zur ausstehenden Position. */
    private fun findNewNode(view: GameView, owner: Int): Int {
        val nodes = view.nodeView
        var best = -1
        var bestD2 = Float.MAX_VALUE
        for (i in 0 until nodes.size) {
            if (!nodes.isAlive(i) || nodes.owner(i) != owner || nodes.uid(i) <= pendingBaseUid) continue
            if ((nodes.flags(i) and NodeFlags.DEBRIS) != 0) continue
            val dx = nodes.x(i) - pendingX
            val dy = nodes.y(i) - pendingY
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) { bestD2 = d2; best = i }
        }
        return best
    }

    private fun previewAt(x: Float, y: Float, ctx: ToolContext, from: BuildToolState.FirstNodeSelected) {
        val ghost = evalEnd(x, y, ctx, from)
        val cur = state
        // unveränderten Ghost nicht neu verpacken (weniger Müll pro Frame)
        if (!(cur is BuildToolState.Previewing && cur.from == from && cur.ghost == ghost)) {
            state = BuildToolState.Previewing(from, ghost)
        }
        val hasEnd = ghost.snapNodeRef >= 0L || ghost.snapBeamRef >= 0L
        setSnaps(ghost.ax, ghost.ay, from.nodeRef, hasEnd, ghost.bx, ghost.by, ghost.snapNodeRef)
    }

    /** Setzt die Snap-Marken (Start, optional Ende); legt nur dann eine neue Liste an, wenn sich etwas geändert hat. */
    private fun setSnaps(sx: Float, sy: Float, sRef: Long, hasEnd: Boolean, ex: Float, ey: Float, eRef: Long) {
        val cur = snaps
        if (cur.size == (if (hasEnd) 2 else 1)) {
            val s0 = cur[0]
            var same = s0.x == sx && s0.y == sy && s0.nodeRef == sRef
            if (same && hasEnd) {
                val e0 = cur[1]
                same = e0.x == ex && e0.y == ey && e0.nodeRef == eRef
            }
            if (same) return
        }
        val start = SnapMark(sx, sy, sRef, true)
        snaps = if (hasEnd) listOf(start, SnapMark(ex, ey, eRef, true)) else listOf(start)
    }

    /** Erstes Ende: eigener Knoten, sonst Teilungspunkt eines eigenen Balkens, sonst `null`. */
    private fun snapStart(x: Float, y: Float, ctx: ToolContext): BuildToolState.FirstNodeSelected? {
        val view = ctx.view
        val owner = ctx.playerId
        val n = picker.nearestNode(view, owner, x, y, ctx.pickRadiusM, -1)
        if (n >= 0) {
            val nodes = view.nodeView
            return BuildToolState.FirstNodeSelected(nodes.ref(n), -1L, 0f, nodes.x(n), nodes.y(n))
        }
        val b = picker.nearestBeam(view, owner, x, y, ctx.pickRadiusM * ToolConst.BEAM_SNAP_FACTOR, BeamFilter.SPLITTABLE, -1)
        if (b >= 0) {
            val t = BeamPlanner.splitT(picker.hitT)
            picker.beamPoint(view, b, t, tmp)
            return BuildToolState.FirstNodeSelected(-1L, view.beamView.ref(b), t, tmp[0], tmp[1])
        }
        return null
    }

    /** Aktuelle Weltposition des ersten Endes (Knoten können sich bewegen) in [out]. */
    private fun startPos(view: GameView, from: BuildToolState.FirstNodeSelected, out: FloatArray) {
        if (from.nodeRef >= 0L) {
            val s = view.nodeView.resolve(from.nodeRef)
            if (s >= 0) { out[0] = view.nodeView.x(s); out[1] = view.nodeView.y(s); return }
        } else if (from.beamRef >= 0L) {
            val s = view.beamView.resolve(from.beamRef)
            if (s >= 0) { picker.beamPoint(view, s, from.beamT, out); return }
        }
        out[0] = from.x; out[1] = from.y
    }

    /**
     * Wertet Ende B an ([x], [y]) aus (Prototyp `evalBuild`): Rasten, dann Enden/Länge/Kosten aus [BeamPlanner.plan] und
     * Grund vom Validator. Setzt [lastCommand] auf das zugehörige `PlaceBeam`.
     */
    private fun evalEnd(x: Float, y: Float, ctx: ToolContext, from: BuildToolState.FirstNodeSelected): GhostBeam {
        val view = ctx.view
        val owner = ctx.playerId
        val cfg = view.simConfig
        val nodes = view.nodeView
        startPos(view, from, tmp)
        var ax = tmp[0]
        var ay = tmp[1]
        val startNode = if (from.nodeRef >= 0L) nodes.resolve(from.nodeRef) else -1
        val startBeam = if (from.beamRef >= 0L) view.beamView.resolve(from.beamRef) else -1
        val r = ctx.pickRadiusM

        var bx = x
        var by = y
        var bNode = -1L
        var bBeam = -1L
        var bT = 0f
        var angleSnapped = false
        var lengthSnapped = false

        val nn = picker.nearestNode(view, owner, x, y, r, startNode)
        if (nn >= 0) {
            bx = nodes.x(nn); by = nodes.y(nn); bNode = nodes.ref(nn)
        } else {
            val nb = picker.nearestBeam(view, owner, x, y, r * ToolConst.BEAM_SNAP_FACTOR, BeamFilter.SPLITTABLE, startBeam)
            if (nb >= 0) {
                bT = BeamPlanner.splitT(picker.hitT)
                picker.beamPoint(view, nb, bT, tmp)
                bx = tmp[0]; by = tmp[1]; bBeam = view.beamView.ref(nb)
            } else {
                val dx = x - ax
                val dy = y - ay
                val len0 = sqrt(dx * dx + dy * dy)
                if (len0 > 0f) {
                    var len = len0
                    var ang = FastTrig.atan2(dy, dx)
                    if (settings.angleSnap && len0 > ToolConst.ANGLE_SNAP_MIN_LENGTH) {
                        val sn = floor(ang / ToolConst.ANGLE_STEP + 0.5f) * ToolConst.ANGLE_STEP
                        if (FloatMath.abs(sn - ang) < ToolConst.ANGLE_SNAP_TOLERANCE) { ang = sn; angleSnapped = true }
                    }
                    val max = cfg.maxBeamLength
                    if (len0 > max - ToolConst.LENGTH_SNAP_BELOW && len0 < max + ToolConst.LENGTH_SNAP_ABOVE) {
                        len = max; lengthSnapped = true
                    }
                    if (angleSnapped) {
                        bx = ax + FastTrig.cos(ang) * len
                        by = ay + FastTrig.sin(ang) * len
                    } else if (lengthSnapped) {
                        val k = len / len0
                        bx = ax + dx * k
                        by = ay + dy * k
                    }
                    val gy = view.terrain.heightAt(bx)
                    if (FloatMath.abs(by - gy) < ToolConst.GROUND_SNAP) {
                        // Bodenrasten darf die Höchstlänge nicht reißen: dann auf den Kreis um A projizieren (oder weglassen)
                        val sy = gy - cfg.nodeRadius
                        val dyS = sy - ay
                        if (lengthSnapped || RuleChecks.dist(ax, ay, bx, sy) > max) {
                            val k = max * max - dyS * dyS
                            if (k > 0f) {
                                bx = ax + (if (bx >= ax) 1f else -1f) * sqrt(k)
                                by = sy
                                angleSnapped = false
                            }
                        } else {
                            if (FloatMath.abs(sy - by) > ANCHOR_EPS) angleSnapped = false
                            by = sy
                        }
                    }
                }
            }
        }

        var cmd = Command.PlaceBeam(
            tick = view.tick, playerId = owner,
            aNodeRef = from.nodeRef, aBeamRef = from.beamRef, aBeamT = from.beamT, aX = ax, aY = ay,
            bNodeRef = bNode, bBeamRef = bBeam, bBeamT = bT, bX = bx, bY = by,
            materialId = material,
        )
        // Geometrie wie die Sim sie auflöst: freie Enden im Verschmelz-Radius werden zum bestehenden Knoten, t geklemmt
        BeamPlanner.plan(view, cmd, plan)
        if (plan.resolved) {
            if (plan.a.kind == EndKind.NODE && cmd.aNodeRef < 0L) cmd = cmd.copy(aNodeRef = nodes.ref(plan.a.node))
            if (plan.b.kind == EndKind.NODE && cmd.bNodeRef < 0L) { bNode = nodes.ref(plan.b.node); cmd = cmd.copy(bNodeRef = bNode) }
            ax = plan.a.x; ay = plan.a.y
            bx = plan.b.x; by = plan.b.y
            if (plan.b.kind == EndKind.SPLIT) bT = plan.b.t
        }
        val tables = view.tables
        val costPerMeter = if (material in tables.materials.indices) tables.materials[material].costPerMeter else 0f
        val length = RuleChecks.dist(ax, ay, bx, by)
        val cost = RuleCost.beam(length, costPerMeter)
        lastCommand = cmd
        val pending = chainArmed && pendingBaseUid != NO_PENDING
        val reason = if (pending) null else ctx.validator.validate(view, cmd)
        return GhostBeam(
            ax = ax, ay = ay, bx = bx, by = by, materialId = material, valid = !pending && reason == null, reason = reason,
            lengthM = length, cost = cost, snapNodeRef = bNode, snapBeamRef = bBeam, snapBeamT = bT,
            angleSnapped = angleSnapped, lengthSnapped = lengthSnapped, pending = pending,
        )
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float = RuleChecks.dist(ax, ay, bx, by)

    private companion object {
        const val NO_PENDING: Int = Int.MIN_VALUE

        /** Bewegung (m), ab der ein Anker/Marker als verschoben gilt. */
        const val ANCHOR_EPS: Float = 1e-3f
    }
}
