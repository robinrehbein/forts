package de.bollwerk.engine.combat

import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.math.Geometry
import de.bollwerk.engine.physics.BeamBreaker
import de.bollwerk.engine.physics.DeviceKiller
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import kotlin.math.sqrt

/**
 * Schadens- und Wirkungs-Bausteine der Kampf-Systeme (Prototyp `hitBeam`, `hitDevice`, `igniteBeam`, `explode`).
 * Alle Funktionen sind allokationsfrei bis auf die Fx-Ereignisse (und Pool-Zuwachs bei Balkenbrüchen).
 */
object CombatOps {
    /**
     * Schaden auf Balken [j] (× Material-Schadensfaktor, Panzer 0,3) an der Stelle ([hx], [hy]); bei TP ≤ 0 bricht er
     * dort über [BeamBreaker] (Hälften, Geräte darauf sterben).
     * @return `true`, wenn der Balken gebrochen ist.
     */
    fun damageBeam(state: GameState, ctx: StepContext, j: Int, damage: Float, hx: Float, hy: Float): Boolean =
        BeamBreaker.damage(state, ctx, j, damage, hx, hy, BreakCause.DAMAGE)

    /**
     * Schaden auf Gerät [d]; bei TP ≤ 0 wird es zerstört (`FxEvent.DeviceDestroyed`). Ein zerstörter Reaktor löst
     * am Ende des laufenden Kampf-Systems die Reaktor-Explosion aus (siehe [ReactorWatch]).
     * @return `true`, wenn das Gerät dadurch zerstört wurde.
     */
    fun damageDevice(state: GameState, ctx: StepContext, d: Int, damage: Float): Boolean {
        val dev = state.devices
        if (!dev.isAlive(d)) return false
        dev.hpOf[d] -= damage
        if (dev.hpOf[d] > 0f) return false
        return DeviceKiller.kill(state, ctx, d)
    }

    /**
     * Entzündet Balken [j] (Prototyp `igniteBeam`): nur brennbares Material mit Brennstoff, das noch nicht brennt.
     * @return `true`, wenn er jetzt brennt (`FxEvent.Ignited`).
     */
    fun ignite(state: GameState, ctx: StepContext, j: Int): Boolean {
        val beams = state.beams
        if (!beams.isAlive(j)) return false
        if (!state.tables.materials[beams.materialOf[j]].flammable) return false
        if (beams.fireOf[j] > 0f || beams.fuelOf[j] <= 0f) return false
        beams.fireOf[j] = state.config.fire.igniteStart
        val n = state.nodes
        val a = beams.a[j]; val b = beams.b[j]
        ctx.fx.add(FxEvent.Ignited(state.tick, (n.x[a] + n.x[b]) * 0.5f, (n.y[a] + n.y[b]) * 0.5f, beams.uidOf[j]))
        return true
    }

    /**
     * Geschwindigkeitsänderung [dv] (m/s, gedeckelt durch [cap]) eines Knotens entlang (ux, uy): Verlet über `px/py`
     * (Verschiebung pro Substep `dv · h`). Verankerte Knoten bleiben unberührt.
     * @param impulse Impuls J; dv = min(cap, J · invMass).
     */
    fun push(state: GameState, node: Int, ux: Float, uy: Float, impulse: Float, cap: Float) {
        val nodes = state.nodes
        val inv = nodes.invMass[node]
        if (inv == 0f || !nodes.isAlive(node)) return
        val dv = FloatMath.min(cap, impulse * inv)
        val h = state.config.substepDt
        nodes.px[node] -= ux * dv * h
        nodes.py[node] -= uy * dv * h
    }

    /**
     * Explosion (Prototyp `explode`) am Punkt ([x], [y]) mit Radius [radius], Zentrumsschaden [damage] und Impuls [impulse]:
     * 1. Radialer Verlet-Impuls auf alle beweglichen Knoten im Radius: `dv = min(explosionDvCap, J · (1 − d/R) · invMass)`.
     * 2. Balken (Abstand zur Kapsel-Oberfläche < R): Schaden `damage · (1 − d/R)` × Material-Schadensfaktor; liegt eine
     *    **geschlossene** Tür zwischen Zentrum und nächstem Balkenpunkt, nur `doorShieldFactor` (0,3) davon. Brennbares in
     *    [igniteRadius] fängt Feuer. Balken mit TP ≤ 0 brechen an der Projektion des Zentrums (BeamBreaker).
     * 3. Geräte: `damage · (1 − d/R) · splashDeviceFactor · deviceFactor`, d = Abstand zur Trefferzone (× 0,6).
     * 4. `FxEvent.Explosion` (Material, Brand-Flag, getroffener Balken + Stelle für die Brandspur), außer bei
     *    [emitFx] = false (Reaktor-Explosion: die Darstellung kommt über `FxEvent.ReactorDestroyed`).
     * Jeder beschädigte Balken/jedes beschädigte Gerät bekommt ein `FxEvent.Hit(splash = true)` (Treffer-Blitz wie
     * Prototyp `hitBeam(…, fx = false)`/`hitDevice`), mit dem Schaden vor Materialfaktor bzw. dem Geräteschaden.
     * Im laufenden Tick entstandene Balken/Geräte (z. B. eben entstandene Bruchhälften) werden übersprungen.
     */
    fun explode(
        state: GameState, ctx: StepContext, world: CombatWorld,
        x: Float, y: Float, radius: Float, damage: Float, impulse: Float, igniteRadius: Float,
        weaponId: Int, hitMaterialId: Int, hitBeamUid: Int, hitBeamT: Float, deviceFactor: Float,
        emitFx: Boolean = true,
    ) {
        if (!(radius > 0f)) return
        val cfg = state.config
        val cc = cfg.combat
        val nodes = state.nodes
        val beams = state.beams
        val mats = state.tables.materials
        val r2 = radius * radius
        val h = cfg.substepDt

        // 1. Impuls auf Knoten
        val nn = nodes.size
        val inv = nodes.invMass
        for (i in 0 until nn) {
            if (!nodes.isAlive(i) || inv[i] == 0f) continue
            val dx = nodes.x[i] - x; val dy = nodes.y[i] - y
            val d2 = dx * dx + dy * dy
            if (d2 >= r2) continue
            val d = sqrt(d2)
            val f = 1f - d / radius
            val dv = FloatMath.min(cc.explosionDvCap, impulse * f * inv[i])
            var ux = 0f; var uy = -1f
            if (d > 1e-3f) { ux = dx / d; uy = dy / d }
            nodes.px[i] -= ux * dv * h
            nodes.py[i] -= uy * dv * h
        }

        // geschlossene Türen (Abschirmung)
        val bn = beams.size
        var doorCount = 0
        for (j in 0 until bn) {
            if (!beams.isAlive(j) || (beams.flags[j] and BeamFlags.DOOR_OPEN) != 0) continue
            if (!mats[beams.materialOf[j]].isDoor) continue
            if (doorCount == world.doors.size) world.doors = world.doors.copyOf(doorCount * 2)
            world.doors[doorCount++] = j
        }
        val doors = world.doors
        val shield2 = cc.doorShieldDistance * cc.doorShieldDistance
        val cp = world.cp

        // 2. Balken
        for (j in 0 until bn) {
            if (!beams.isAlive(j) || beams.isNew(j)) continue
            val mat = mats[beams.materialOf[j]]
            val a = beams.a[j]; val b = beams.b[j]
            val ax = nodes.x[a]; val ay = nodes.y[a]; val bx = nodes.x[b]; val by = nodes.y[b]
            val dist = sqrt(Geometry.pointSegmentDistSq(x, y, ax, ay, bx, by, cp)) - mat.thickness * 0.5f
            if (dist >= radius) continue
            val f = 1f - FloatMath.max(0f, dist) / radius
            var m = 1f
            if (doorCount > 0) {
                val t = cp.t
                val qx = ax + (bx - ax) * t; val qy = ay + (by - ay) * t
                for (k in 0 until doorCount) {
                    val dr = doors[k]
                    if (dr == j || !beams.isAlive(dr)) continue
                    val da = beams.a[dr]; val db = beams.b[dr]
                    if (Geometry.segmentSegmentDistSq(x, y, qx, qy, nodes.x[da], nodes.y[da], nodes.x[db], nodes.y[db]) < shield2) {
                        m = cc.doorShieldFactor
                        break
                    }
                }
            }
            if (igniteRadius > 0f && mat.flammable && dist < igniteRadius) ignite(state, ctx, j)
            val dmg = damage * f * m
            ctx.fx.add(FxEvent.Hit(state.tick, x, y, HitTarget.BEAM, beams.uidOf[j], dmg, weaponId, splash = true))
            damageBeam(state, ctx, j, dmg, x, y)
        }

        // 3. Geräte
        val dev = state.devices
        val props = state.tables.devices
        val dn = dev.size
        for (i in 0 until dn) {
            if (!dev.isAlive(i) || dev.isNew(i)) continue
            val p = props[dev.typeOf[i]]
            val cx = dev.x[i] + dev.nx[i] * p.mountOffset
            val cy = dev.y[i] + dev.ny[i] * p.mountOffset
            val ex = cx - x; val ey = cy - y
            val dd = sqrt(ex * ex + ey * ey) - p.hitRadius * cc.deviceSplashRadiusScale
            if (dd >= radius) continue
            val dmg = damage * (1f - FloatMath.max(0f, dd) / radius) * cc.splashDeviceFactor * deviceFactor
            ctx.fx.add(FxEvent.Hit(state.tick, x, y, HitTarget.DEVICE, dev.uidOf[i], dmg, weaponId, splash = true))
            damageDevice(state, ctx, i, dmg)
        }

        if (emitFx) ctx.fx.add(
            FxEvent.Explosion(
                state.tick, x, y, radius, damage, weaponId, hitMaterialId, igniteRadius > 0f, hitBeamUid, hitBeamT,
            ),
        )
    }

    /** Parameter 0..1 der Projektion von (px, py) auf Balken [j] (Brandspur-Stelle). */
    fun beamParam(state: GameState, j: Int, px: Float, py: Float): Float {
        val nodes = state.nodes
        val a = state.beams.a[j]; val b = state.beams.b[j]
        val ax = nodes.x[a]; val ay = nodes.y[a]
        val dx = nodes.x[b] - ax; val dy = nodes.y[b] - ay
        val l2 = dx * dx + dy * dy
        if (l2 <= 1e-12f) return 0.5f
        return FloatMath.clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0f, 1f)
    }
}
