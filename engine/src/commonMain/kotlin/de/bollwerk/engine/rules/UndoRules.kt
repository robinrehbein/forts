package de.bollwerk.engine.rules

import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.PoolView
import de.bollwerk.engine.sim.SplitRecord
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.UndoEntry
import de.bollwerk.engine.sim.UndoKind
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.GameView
import de.bollwerk.engine.physics.BeamBreaker
import de.bollwerk.engine.physics.DeviceKiller

/**
 * "Zurück" (Prototyp `undo`): nimmt den **neuesten** Bau-Vorgang des Spielers zurück, solange er höchstens
 * `SimConfig.undoWindowTicks` (Standard 10 s) alt ist und alles Gebaute unversehrt ist. Der Vorgang wird vollständig
 * erstattet (`undoRefund`), geteilte Balken werden wieder vereinigt, umgezogene Geräte kehren an ihre alte Stelle zurück.
 *
 * Das Journal ist je Spieler begrenzt (`SimConfig.undoDepth`), gehasht und enthält nur Refs. Einträge, die nie mehr
 * zurückgenommen werden können (zu alt, Bauwerk zerstört), werden in [prune] jeden Tick entfernt, damit sie ältere
 * Einträge nicht blockieren.
 */
object UndoRules {
    /**
     * Index (0 = ältester) des neuesten erfüllbaren Eintrags von [player] oder −1. Rechnet schreibgeschützt und ohne
     * Allokation dasselbe wie [prune]: Einträge über dem Ergebnis würden dort entfernt (vor dem ersten erfüllbaren Eintrag
     * ist die Menge der "ersetzten" Balken ohnehin leer).
     */
    fun liveTop(view: GameView, player: Int): Int {
        val p = view.player(player)
        for (k in p.undoCount - 1 downTo 0) {
            val e = p.undoAt(k) ?: continue
            if (satisfiable(view, e, null)) return k
        }
        return -1
    }

    /** @return `null`, wenn der oberste erfüllbare Eintrag von [player] jetzt zurückgenommen werden kann. */
    fun check(view: GameView, player: Int): RejectReason? {
        val k = liveTop(view, player)
        if (k < 0) return RejectReason.NOTHING_TO_UNDO
        val e = view.player(player).undoAt(k) ?: return RejectReason.NOTHING_TO_UNDO
        val beams = view.beamView
        val devices = view.deviceView
        val nodes = view.nodeView
        when (e.kind) {
            UndoKind.BEAM -> {
                val id = beams.resolve(e.ref)
                if (id < 0 || !intact(view, id, beams.maxHp(id))) return RejectReason.UNDO_BLOCKED
            }
            UndoKind.DEVICE -> {
                val id = devices.resolve(e.ref)
                if (id < 0 || devices.hp(id) < devices.maxHp(id) - RuleConst.HP_EPS) return RejectReason.UNDO_BLOCKED
            }
        }
        for (i in e.newNodeRefs.indices) if (nodes.resolve(e.newNodeRefs[i]) < 0) return RejectReason.UNDO_BLOCKED
        for (i in e.splits.indices) {
            val s = e.splits[i]
            if (nodes.resolve(s.nodeRef) < 0) return RejectReason.UNDO_BLOCKED
            if (nodes.resolve(s.original.nodeARef) < 0 || nodes.resolve(s.original.nodeBRef) < 0) return RejectReason.UNDO_BLOCKED
            val ha = beams.resolve(s.halfARef)
            val hb = beams.resolve(s.halfBRef)
            if (ha < 0 || hb < 0) return RejectReason.UNDO_BLOCKED
            if (!intact(view, ha, s.original.hp) || !intact(view, hb, s.original.hp)) return RejectReason.UNDO_BLOCKED
        }
        return null
    }

    /** Brennt nicht und hat mindestens [minHp] TP. */
    private fun intact(view: GameView, beam: Int, minHp: Float): Boolean {
        val b = view.beamView
        return b.fire(beam) <= 0f && b.hp(beam) >= minHp - RuleConst.HP_EPS
    }

    /** Arbeitspuffer für [prune] (vom Command-System gehalten, damit der Tick nichts alloziert). */
    class Scratch {
        internal var covered = LongArray(8)
        internal var coveredCount = 0
        internal var keep = BooleanArray(8)
        internal val kept = ArrayList<UndoEntry>(8)

        internal fun cover(ref: Long) {
            if (coveredCount == covered.size) covered = covered.copyOf(covered.size * 2)
            covered[coveredCount++] = ref
        }

        internal fun covers(ref: Long): Boolean {
            for (i in 0 until coveredCount) if (covered[i] == ref) return true
            return false
        }
    }

    /**
     * Entfernt Einträge, die nie mehr zurückgenommen werden können: abgelaufen, Bauwerk zerstört oder (bei einem Split)
     * eine der Hälften zerstört. Ein Balken, der **nur** deshalb nicht mehr lebt, weil ein neuerer, noch gültiger Eintrag ihn
     * geteilt hat, zählt als vorhanden (Zurück vereinigt ihn dann wieder). Auswertung von neu nach alt. Allokationsfrei,
     * solange nichts herausfällt (und [scratch] wiederverwendet wird).
     */
    fun prune(state: GameState, scratch: Scratch = Scratch()) {
        for (pi in 0 until state.players.size) {
            val j = state.players[pi].undoJournal
            val n = j.size
            if (n == 0) continue
            if (scratch.keep.size < n) scratch.keep = BooleanArray(n)
            scratch.coveredCount = 0
            var dropped = false
            for (k in n - 1 downTo 0) {
                val e = j[k]
                val ok = satisfiable(state, e, scratch)
                if (ok) for (i in e.splits.indices) scratch.cover(e.splits[i].original.ref)
                scratch.keep[k] = ok
                if (!ok) dropped = true
            }
            if (!dropped) continue
            scratch.kept.clear()
            for (k in 0 until n) if (scratch.keep[k]) scratch.kept.add(j[k])
            j.clear()
            for (i in scratch.kept.indices) j.push(scratch.kept[i])
            scratch.kept.clear()
        }
    }

    /** Kann [e] überhaupt noch zurückgenommen werden (nicht abgelaufen, Bauwerk und Split-Hälften vorhanden)? */
    private fun satisfiable(view: GameView, e: UndoEntry, scratch: Scratch?): Boolean {
        val window = view.simConfig.undoWindowTicks
        if (window > 0 && view.tick - e.tick > window.toLong()) return false
        if (!present(view, e.kind == UndoKind.BEAM, e.ref, scratch)) return false
        for (i in e.splits.indices) {
            val s = e.splits[i]
            if (!present(view, true, s.halfARef, scratch) || !present(view, true, s.halfBRef, scratch)) return false
        }
        return true
    }

    /** Lebt das Objekt [ref] noch, oder ist es durch einen neueren gültigen Split ([scratch]) nur ersetzt? */
    private fun present(view: GameView, beam: Boolean, ref: Long, scratch: Scratch?): Boolean {
        if (beam) return view.beamView.resolve(ref) >= 0 || (scratch != null && scratch.covers(ref))
        return view.deviceView.resolve(ref) >= 0
    }

    /**
     * Nimmt den obersten Eintrag von [player] zurück (nach [check] == `null` aufrufen).
     */
    fun apply(state: GameState, ctx: StepContext, player: Int) {
        val p = state.players[player]
        // unerfüllbare Einträge über dem Ziel (im selben Tick entstanden) fallen weg, wie in prune
        val top = liveTop(state, player)
        while (p.undoJournal.size - 1 > top) p.undoJournal.pop()
        val e = p.undoJournal.pop() ?: return
        val beams = state.beams
        when (e.kind) {
            UndoKind.BEAM -> {
                val id = beams.resolve(e.ref)
                if (id >= 0) BeamBreaker.breakBeam(state, ctx, id, 0.5f, BreakCause.DELETED)
            }
            UndoKind.DEVICE -> {
                val id = state.devices.resolve(e.ref)
                if (id >= 0) DeviceKiller.kill(state, ctx, id, silent = true)
            }
        }
        for (k in e.splits.indices.reversed()) merge(state, ctx, p.id, e.splits[k])
        for (r in e.newNodeRefs) releaseIfOrphan(state, state.nodes.resolve(r))
        val refund = state.config.undoRefund
        p.metal += e.metal * refund
        p.energy += e.energy * refund
        state.topologyDirty = true
    }

    /** Vereinigt die Hälften eines Splits wieder zum Originalbalken (neue uid) und setzt Geräte zurück. */
    private fun merge(state: GameState, ctx: StepContext, player: Int, s: SplitRecord) {
        val beams = state.beams
        val nodes = state.nodes
        val devices = state.devices
        val rec = s.original
        val ha = beams.resolve(s.halfARef)
        val hb = beams.resolve(s.halfBRef)
        val na = nodes.resolve(rec.nodeARef)
        val nb = nodes.resolve(rec.nodeBRef)
        val splitNode = nodes.resolve(s.nodeRef)
        if (ha < 0 || hb < 0 || na < 0 || nb < 0) return
        val id = beams.alloc(na, nb, rec.material, rec.restLen, rec.maxHp, rec.owner, rec.texOffset)
        beams.hpOf[id] = rec.hp
        beams.fireOf[id] = rec.fire
        beams.fuelOf[id] = rec.fuel
        beams.flags[id] = rec.flags or BeamFlags.ALIVE
        val newRef = beams.ref(id)
        for (dm in s.devices) {
            val d = devices.resolve(dm.deviceRef)
            if (d < 0) continue
            devices.beamId[d] = id
            devices.tOf[d] = dm.t
        }
        // Geräte, die (entgegen der LIFO-Reihenfolge) noch auf den Hälften sitzen, verschwinden still mit ihnen
        beams.release(ha)
        beams.release(hb)
        for (d in 0 until devices.size) {
            if (devices.isAlive(d) && (devices.beamId[d] == ha || devices.beamId[d] == hb)) DeviceKiller.kill(state, ctx, d, silent = true)
        }
        releaseIfOrphan(state, splitNode)
        remap(state, player, rec.ref, newRef)
    }

    /** Ersetzt in allen Journal-Einträgen von [player] den Beam-Ref [oldRef] durch [newRef]. */
    private fun remap(state: GameState, player: Int, oldRef: Long, newRef: Long) {
        if (oldRef == PoolView.NO_REF) return
        val j = state.players[player].undoJournal
        if (j.size == 0) return
        val all = ArrayList<UndoEntry>(j.size)
        for (k in 0 until j.size) all.add(j[k])
        j.clear()
        for (e in all) {
            val ref = if (e.kind == UndoKind.BEAM && e.ref == oldRef) newRef else e.ref
            var changed = ref != e.ref
            val splits = ArrayList<SplitRecord>(e.splits.size)
            for (s in e.splits) {
                val a = if (s.halfARef == oldRef) newRef else s.halfARef
                val b = if (s.halfBRef == oldRef) newRef else s.halfBRef
                if (a != s.halfARef || b != s.halfBRef) { splits.add(s.copy(halfARef = a, halfBRef = b)); changed = true } else splits.add(s)
            }
            j.push(if (changed) e.copy(ref = ref, splits = splits) else e)
        }
    }

    /** Gibt einen unverankerten Knoten ohne lebenden Balken frei (Pool-Scan; die Adjazenz ist im Tick evtl. veraltet). */
    internal fun releaseIfOrphan(state: GameState, node: Int) {
        if (node < 0 || !state.nodes.isAlive(node) || state.nodes.isAnchored(node)) return
        val b = state.beams
        for (j in 0 until b.size) {
            if (b.isAlive(j) && (b.a[j] == node || b.b[j] == node)) return
        }
        state.nodes.release(node)
    }
}
