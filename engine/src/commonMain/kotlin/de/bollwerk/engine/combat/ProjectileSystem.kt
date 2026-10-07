package de.bollwerk.engine.combat

import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.ProjectileFlags
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.WeaponProps
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget
import kotlin.math.sqrt

/**
 * [de.bollwerk.engine.sim.SystemSlot.PROJECTILES] (Prototyp `projTick`, `onProjHit`), je Projektil in ID-Reihenfolge
 * (im laufenden Tick abgefeuerte fliegen erst ab dem nächsten Tick):
 * - Integration **ausschließlich** über [Ballistics.step] mit `h = dt / projectileSubsteps` und dem `gravityScale` der
 *   Waffe – bitgleich zu [Ballistics.tick]/[Ballistics.predict] (Zielvorschau, KI). Wind aus `state.wind`.
 * - Nach jedem Teilschritt Swept-Test des Segments (Radius `projectileRadius`) gegen Balken (Broadphase-Raster, ohne
 *   offene Türen und – solange `ageTicks < sourceIgnoreTicks` – ohne den eigenen Montagebalken samt Geräten darauf),
 *   Geräte und Gelände; frühester Treffer gewinnt → kein Tunneling auch durch dünne Balken.
 * - Treffer: Direktschaden `damage` (Balken × Materialfaktor, Geräte × `deviceDamageFactor`) mit Impuls
 *   `directImpulse` (gedeckelt `directDvCap`), danach Explosion (`splashRadius`, `splashDamage`, `explosionImpulse`,
 *   Brandgeschoss entzündet Holz in `igniteRadius`). Mörser: nur Explosion (2,5 m, 120); Kanone: 90 direkt + 1,2 m/30.
 * - Kill-Grenzen der Karte oder abgelaufene Lebensdauer (`ttl`) → still entfernt.
 */
class ProjectileSystem(private val world: CombatWorld) : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        world.invalidateGrid()
        val p = state.projectiles
        if (p.aliveCount == 0) {
            world.reactors.check(state, ctx, world)
            return
        }
        val cfg = state.config
        val weapons = state.tables.weapons
        val beams = state.beams
        val map = state.map
        val wind = state.wind
        val subs = cfg.projectileSubsteps
        val h = cfg.dt / subs
        val ignoreTicks = cfg.combat.sourceIgnoreTicks
        val st = world.ballistic
        val hit = world.hit
        val n = p.size
        for (i in 0 until n) {
            if (!p.isAlive(i) || p.isNew(i)) continue
            val wp = weapons[p.kindOf[i]]
            st[Ballistics.X] = p.x[i]; st[Ballistics.Y] = p.y[i]
            st[Ballistics.VX] = p.vx[i]; st[Ballistics.VY] = p.vy[i]
            val ignore = if (p.ageTicks[i] < ignoreTicks) beams.resolve(p.ignoreBeamRef[i]) else -1
            var x0 = st[Ballistics.X]; var y0 = st[Ballistics.Y]
            var gone = false
            for (s in 0 until subs) {
                x0 = st[Ballistics.X]; y0 = st[Ballistics.Y]
                Ballistics.step(st, h, wind, cfg, wp.gravityScale)
                val x1 = st[Ballistics.X]; val y1 = st[Ballistics.Y]
                if (world.caster.cast(state, x0, y0, x1, y1, wp.projectileRadius, ignore, -1, ignore, true, hit)) {
                    p.px[i] = x0; p.py[i] = y0
                    p.x[i] = hit.x; p.y[i] = hit.y
                    p.vx[i] = st[Ballistics.VX]; p.vy[i] = st[Ballistics.VY]
                    p.ageTicks[i]++
                    onHit(state, ctx, i, wp)
                    p.release(i)
                    gone = true
                    break
                }
                if (map.isOutOfBounds(x1, y1)) {
                    p.release(i)
                    gone = true
                    break
                }
            }
            if (gone) continue
            p.px[i] = x0; p.py[i] = y0
            p.x[i] = st[Ballistics.X]; p.y[i] = st[Ballistics.Y]
            p.vx[i] = st[Ballistics.VX]; p.vy[i] = st[Ballistics.VY]
            p.ageTicks[i]++
            p.ttl[i]--
            if (p.ttl[i] <= 0) p.release(i)
        }
        world.reactors.check(state, ctx, world)
    }

    /** Wirkung eines Projektils [i] am Treffer `world.hit` (Prototyp `onProjHit`, verallgemeinert über [WeaponProps]). */
    private fun onHit(state: GameState, ctx: StepContext, i: Int, wp: WeaponProps) {
        val p = state.projectiles
        val beams = state.beams
        val hit = world.hit
        val weaponId = p.kindOf[i]
        val hx = hit.x; val hy = hit.y
        val vx = p.vx[i]; val vy = p.vy[i]
        val sp = sqrt(vx * vx + vy * vy)
        val ux = if (sp > 0f) vx / sp else 0f
        val uy = if (sp > 0f) vy / sp else 1f
        var hitMat = -2
        var hitBeamUid = -1
        var hitBeamT = -1f
        when (hit.kind) {
            RayHit.BEAM -> {
                val j = hit.id
                hitMat = beams.materialOf[j]
                hitBeamUid = beams.uidOf[j]
                hitBeamT = CombatOps.beamParam(state, j, hx, hy)
                if (wp.damage > 0f) {
                    val na = beams.a[j]; val nb = beams.b[j]
                    ctx.fx.add(FxEvent.Hit(state.tick, hx, hy, HitTarget.BEAM, hitBeamUid, wp.damage, weaponId))
                    CombatOps.damageBeam(state, ctx, j, wp.damage, hx, hy)
                    if (wp.directImpulse > 0f) {
                        val cap = state.config.combat.directDvCap
                        CombatOps.push(state, na, ux, uy, wp.directImpulse, cap)
                        CombatOps.push(state, nb, ux, uy, wp.directImpulse, cap)
                    }
                }
            }
            RayHit.DEVICE -> {
                if (wp.damage > 0f) {
                    val dmg = wp.damage * wp.deviceDamageFactor
                    ctx.fx.add(FxEvent.Hit(state.tick, hx, hy, HitTarget.DEVICE, state.devices.uidOf[hit.id], dmg, weaponId))
                    CombatOps.damageDevice(state, ctx, hit.id, dmg)
                }
            }
            else -> {
                hitMat = -1
                if (wp.splashRadius <= 0f) ctx.fx.add(FxEvent.Hit(state.tick, hx, hy, HitTarget.TERRAIN, -1, wp.damage, weaponId))
            }
        }
        val incendiary = (p.flags[i] and ProjectileFlags.INCENDIARY) != 0
        val igniteR = if (incendiary) wp.igniteRadius else 0f
        if (wp.splashRadius > 0f) {
            CombatOps.explode(
                state, ctx, world, hx, hy, wp.splashRadius, wp.splashDamage, wp.explosionImpulse, igniteR,
                weaponId, hitMat, hitBeamUid, hitBeamT, wp.deviceDamageFactor,
            )
        } else if (igniteR > 0f && hit.kind == RayHit.BEAM && beams.isAlive(hit.id)) {
            CombatOps.ignite(state, ctx, hit.id)
        }
    }
}
