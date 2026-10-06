package de.bollwerk.engine.physics

import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent

/**
 * Balkenbruch (Prototyp `breakBeam`/`hitBeam`), wiederverwendbar für alle Systeme (Dehnung, Trümmer,
 * Projektile/Explosionen WP4, Feuer).
 *
 * Ein Bruch gibt den Balken frei, zerstört alle Geräte darauf und setzt [GameState.topologyDirty]. Kann der
 * Balken sich teilen (Ursache nicht [BreakCause.DECAY]/[BreakCause.DELETED], kein Trümmer, keine Hälfte, kein
 * Seil, Ruhelänge > `SimConfig.minSplitLength`), entstehen wie im Prototyp **zwei freie Hälften** mit vier neuen
 * Knoten: die Endknoten werden mit ihrer Geschwindigkeit kopiert, an der Bruchstelle entstehen zwei neue Knoten
 * mit kleinem Zufallsstoß aus `rngDebris`. Die Hälften haben keine Verbindung zum Bauwerk und werden im nächsten
 * Topologie-Durchlauf zu Trümmern (Stil-Bibel: "zwei Hälften … die als Trümmer fallen"). Die Ruhelängen der
 * Hälften sind `rest · t` und `rest · (1 − t)` (Summe = Original), TP = 60 % von maxHp, Flags [BeamFlags.HALF]
 * und gezackte Bruchkante ([BeamFlags.JAG_B] an Hälfte A, [BeamFlags.JAG_A] an Hälfte B).
 *
 * Jeder Bruch erzeugt `FxEvent.BeamBroken` mit Material, Bruchstelle und Ursache. **Stille Ursachen**
 * ([BreakCause.DECAY], [BreakCause.DELETED], siehe [isSilent], Prototyp `breakBeam(b, silent = true)`): keine
 * Hälften und Geräte darauf verschwinden **ohne** `FxEvent.DeviceDestroyed`. Das `BeamBroken`-Ereignis bleibt (der
 * Renderer braucht es, um den Balken auszublenden bzw. bei DECAY Staub zu zeigen); Renderer/Audio zeigen für stille
 * Ursachen **keine** Splitter, Funken oder Bruch-Sounds (siehe [BreakCause]).
 *
 * Alle Funktionen allokieren nur über die Pools und das Fx-Ereignis (kein Hot-Path).
 */
object BeamBreaker {
    /** Bruchstelle wird auf diesen Bereich geklemmt (Prototyp 0,22 … 0,78). */
    const val T_MIN: Float = 0.22f
    const val T_MAX: Float = 0.78f
    /** Zufällige Bruchstelle ohne Trefferpunkt (Prototyp 0,38 … 0,62). */
    const val RANDOM_T_MIN: Float = 0.38f
    const val RANDOM_T_MAX: Float = 0.62f
    /** TP-Anteil der Hälften (Prototyp 0,6). */
    const val HALF_HP_FRACTION: Float = 0.6f
    /** Zufallsstoß der neuen Bruchknoten in m pro Substep (Prototyp 0,012). */
    const val SPLIT_KICK: Float = 0.012f

    /**
     * Bricht den Balken [beamRef] (stabile Ref, siehe `PoolView.ref`) an Stelle [t] (0..1 von Ende A,
     * geklemmt auf [T_MIN]..[T_MAX]).
     * @return `true`, wenn der Balken lebte und gebrochen wurde.
     */
    fun breakAt(state: GameState, beamRef: Long, t: Float, cause: BreakCause, ctx: StepContext): Boolean {
        val id = state.beams.resolve(beamRef)
        if (id < 0) return false
        return breakBeam(state, ctx, id, t, cause)
    }

    /** Wie [breakAt], aber mit Slot-ID (nur innerhalb desselben Ticks verwenden). */
    fun breakBeam(state: GameState, ctx: StepContext, id: Int, t: Float, cause: BreakCause): Boolean {
        val beams = state.beams
        if (!beams.isAlive(id)) return false
        val nodes = state.nodes
        val ia = beams.a[id]; val ib = beams.b[id]
        val ax = nodes.x[ia]; val ay = nodes.y[ia]
        val bx = nodes.x[ib]; val by = nodes.y[ib]
        val dx = bx - ax; val dy = by - ay
        val tt = FloatMath.clamp(if (t.isNaN()) 0.5f else t, T_MIN, T_MAX)
        val mx = ax + dx * tt; val my = ay + dy * tt

        val material = beams.materialOf[id]
        val mat = state.tables.materials[material]
        val flags = beams.flags[id]
        val rest = beams.restLen[id]
        val maxHp = beams.maxHpOf[id]
        val owner = beams.ownerOf[id]
        val fire = beams.fireOf[id]
        val fuel = beams.fuelOf[id]
        val tex = beams.texOffset[id]
        val uid = beams.uidOf[id]
        val silent = isSilent(cause)
        val canSplit = !silent &&
            (flags and (BeamFlags.DEBRIS or BeamFlags.HALF)) == 0 &&
            !mat.tensionOnly && rest > state.config.minSplitLength

        // Geräte auf dem Balken sterben (Prototyp: alle Geräte mit d.beam === b)
        val devices = state.devices
        val dn = devices.size
        for (d in 0 until dn) {
            if (devices.isAlive(d) && devices.beamId[d] == id) DeviceKiller.kill(state, ctx, d, silent)
        }
        beams.release(id)
        state.topologyDirty = true

        if (canSplit) {
            // Verlet-Geschwindigkeiten (Verschiebung pro Substep)
            val vax = ax - nodes.px[ia]; val vay = ay - nodes.py[ia]
            val vbx = bx - nodes.px[ib]; val vby = by - nodes.py[ib]
            val vmx = vax + (vbx - vax) * tt; val vmy = vay + (vby - vay) * tt
            val rng = state.rngDebris
            val k1x = rng.nextFloat(-SPLIT_KICK, SPLIT_KICK); val k1y = SPLIT_KICK * rng.nextFloat(0.3f, 1f)
            val k2x = rng.nextFloat(-SPLIT_KICK, SPLIT_KICK); val k2y = SPLIT_KICK * rng.nextFloat(0.3f, 1f)
            val a1 = movingNode(state, ax, ay, vax, vay, owner)
            val m1 = movingNode(state, mx, my, vmx + k1x, vmy + k1y, owner)
            val m2 = movingNode(state, mx, my, vmx + k2x, vmy + k2y, owner)
            val b1 = movingNode(state, bx, by, vbx, vby, owner)
            val keep = flags and (BeamFlags.DOOR_OPEN or BeamFlags.DOOR_PINNED or BeamFlags.DOOR_HINGE_B)
            val h1 = beams.alloc(a1, m1, material, rest * tt, maxHp, owner, tex)
            val h2 = beams.alloc(m2, b1, material, rest * (1f - tt), maxHp, owner, tex + rest * tt)
            initHalf(state, h1, maxHp, fire, fuel, keep or BeamFlags.JAG_B)
            initHalf(state, h2, maxHp, fire, fuel, keep or BeamFlags.JAG_A)
        }
        ctx.fx.add(FxEvent.BeamBroken(state.tick, mx, my, uid, material, tt, cause))
        return true
    }

    /**
     * Schaden auf Balken [id] (Prototyp `hitBeam`): `hp −= damage · damageFactor`; bei `hp ≤ 0` Bruch an der
     * Projektion von ([hx], [hy]) auf den Balken (bzw. zufällig, wenn NaN).
     * @return `true`, wenn der Balken dadurch gebrochen ist.
     */
    fun damage(state: GameState, ctx: StepContext, id: Int, damage: Float, hx: Float, hy: Float, cause: BreakCause = BreakCause.DAMAGE): Boolean {
        val beams = state.beams
        if (!beams.isAlive(id)) return false
        val mat = state.tables.materials[beams.materialOf[id]]
        beams.hpOf[id] -= damage * mat.damageFactor
        if (beams.hpOf[id] > 0f) return false
        val t = if (hx.isNaN() || hy.isNaN()) randomT(state) else projectT(state, id, hx, hy)
        return breakBeam(state, ctx, id, t, cause)
    }

    /** Stilles Entfernen (kein Split, keine Geräte-Zerstörungs-Fx): Zerfall/Kill-Grenzen und Abriss. */
    fun isSilent(cause: BreakCause): Boolean = cause == BreakCause.DECAY || cause == BreakCause.DELETED

    /** Zufällige Bruchstelle aus `rngDebris` (Prototyp `rnd(0,38, 0,62)`). */
    fun randomT(state: GameState): Float = state.rngDebris.nextFloat(RANDOM_T_MIN, RANDOM_T_MAX)

    /** Parameter der Projektion von (px, py) auf Balken [id], geklemmt auf [T_MIN]..[T_MAX]. */
    fun projectT(state: GameState, id: Int, px: Float, py: Float): Float {
        val nodes = state.nodes
        val ia = state.beams.a[id]; val ib = state.beams.b[id]
        val ax = nodes.x[ia]; val ay = nodes.y[ia]
        val dx = nodes.x[ib] - ax; val dy = nodes.y[ib] - ay
        val l2 = dx * dx + dy * dy
        if (l2 <= 1e-12f) return 0.5f
        return FloatMath.clamp(((px - ax) * dx + (py - ay) * dy) / l2, T_MIN, T_MAX)
    }

    private fun movingNode(state: GameState, x: Float, y: Float, vx: Float, vy: Float, owner: Int): Int {
        val nodes = state.nodes
        val id = nodes.alloc(x, y, owner)
        nodes.px[id] = x - vx
        nodes.py[id] = y - vy
        return id
    }

    private fun initHalf(state: GameState, id: Int, maxHp: Float, fire: Float, fuel: Float, extraFlags: Int) {
        val beams = state.beams
        beams.hpOf[id] = maxHp * HALF_HP_FRACTION
        beams.maxHpOf[id] = maxHp
        beams.fireOf[id] = fire
        beams.fuelOf[id] = fuel
        beams.flags[id] = beams.flags[id] or BeamFlags.HALF or extraFlags
    }
}
