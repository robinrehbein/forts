package de.bollwerk.engine.combat

import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.Ballistics
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FastTrig
import de.bollwerk.engine.math.Geometry
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.DevicePool
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.WeaponMode
import de.bollwerk.engine.sim.WeaponProps
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HitTarget

/**
 * [de.bollwerk.engine.sim.SystemSlot.WEAPONS] (Prototyp `fireWeapon`, `mgShot`, `weaponTick`), je Waffe in ID-Reihenfolge:
 *
 * **Abfeuern** nur auf Anforderung ([DeviceFlags.FIRE_REQUESTED], gesetzt vom Command-System beim Anwenden von
 * `Command.Fire`; das Bit wird immer verbraucht). Voraussetzungen: fertig gebaut, nicht deaktiviert
 * ([DeviceFlags.DISABLED]/[DeviceFlags.BUILDING]), nachgeladen, keine laufende Salve/kein laufender Strahl, Trägerbalken
 * lebt und ist kein Trümmer – sonst still ignoriert. Kosten
 * (`shotMetal`/`shotEnergy`) werden beim Schuss abgezogen; reicht es nicht, kein Schuss und `FxEvent.FireRefused`.
 * Beim Schuss:
 * - Eigene geschlossene Tür im Umkreis `DoorConfig.searchRadius` um den Rohr-Drehpunkt öffnet sich automatisch und
 *   schließt `autoCloseTicks` (2,6 s) nach dem Schuss-Tick (eine bereits automatisch geöffnete Tür bekommt den Timer neu).
 * - Nachladezeit `reloadTicks`, Rückstoß `recoilImpulse` auf beide Knoten des Trägerbalkens entgegen der
 *   Schussrichtung (dv gedeckelt durch `recoilDvCap`).
 * - BALLISTIC: Projektil an der Mündung mit `Ballistics.launch` (Mündungsgeschwindigkeit × Kraft), `sourceDevice` =
 *   uid der Waffe (nie der Slot, CLAUDE.md Regel 7), ignoriert den
 *   eigenen Balken `sourceIgnoreTicks` lang; Brandgeschoss, wenn `igniteRadius > 0`. `FxEvent.Fired`.
 * - HITSCAN: Salve mit `shotsPerBurst` Schüssen im Abstand `burstIntervalTicks` (erster sofort). Jeder Schuss:
 *   Streuung ±`spreadRad` aus `rngWeapon`, Strahl bis `maxRange`, Schaden `damage` (Geräte × `deviceDamageFactor`),
 *   durchschlägt `piercesBeams` Balken, Impuls `directImpulse` auf den getroffenen Balken; `Fired`, `Tracer`, `Hit`.
 * - BEAM (Laser): Strahl für `beamTicks` Ticks, je Tick `damage · dt` am ersten Treffer; `FxEvent.LaserBeam`.
 */
class WeaponSystem(private val world: CombatWorld) : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        world.reactors.capture(state)
        world.invalidateGrid()
        val d = state.devices
        val props = state.tables.devices
        val weapons = state.tables.weapons
        val n = d.size
        for (i in 0 until n) {
            if (!d.isAlive(i)) continue
            val f = d.flags[i]
            val requested = (f and DeviceFlags.FIRE_REQUESTED) != 0
            if (requested) d.flags[i] = f and DeviceFlags.FIRE_REQUESTED.inv()
            if (d.isNew(i)) continue
            val p = props[d.typeOf[i]]
            if (p.weapon < 0) continue
            val wp = weapons[p.weapon]
            if (requested) tryFire(state, ctx, i, p, wp)
            if (!d.isAlive(i)) continue
            if (d.burstLeft[i] > 0) burst(state, ctx, i, p, wp)
            if (!d.isAlive(i)) continue
            if (d.beamTicksLeft[i] > 0) laser(state, ctx, i, p, wp)
        }
        world.reactors.check(state, ctx, world)
    }

    private fun tryFire(state: GameState, ctx: StepContext, i: Int, p: DeviceProps, wp: WeaponProps) {
        val d = state.devices
        val beams = state.beams
        val beam = d.beamId[i]
        if (!canFire(d, i)) return
        if (!beams.isAlive(beam) || (beams.flags[beam] and BeamFlags.DEBRIS) != 0) return
        val geo = world.geo
        DeviceGeometry.mount(state, i, geo)
        val mx = geo[DeviceGeometry.MUZZLE_X]; val my = geo[DeviceGeometry.MUZZLE_Y]
        val owner = d.ownerOf[i]
        if (owner < 0 || owner >= state.players.size) return
        val pl = state.players[owner]
        if (pl.metal < wp.shotMetal) {
            ctx.fx.add(FxEvent.FireRefused(state.tick, mx, my, d.uidOf[i], owner, RejectReason.NOT_ENOUGH_METAL))
            return
        }
        if (pl.energy < wp.shotEnergy) {
            ctx.fx.add(FxEvent.FireRefused(state.tick, mx, my, d.uidOf[i], owner, RejectReason.NOT_ENOUGH_ENERGY))
            return
        }
        pl.metal -= wp.shotMetal
        pl.energy -= wp.shotEnergy
        // geschossen ist unumkehrbar: kein "Zurück" mit voller Erstattung mehr (sonst Nachladen per Neu-Platzieren umgehbar)
        pl.undoJournal.forgetDevice(d.ref(i))

        autoOpenDoor(state, ctx, owner, geo[DeviceGeometry.PIVOT_X], geo[DeviceGeometry.PIVOT_Y])
        d.reloadTicksOf[i] = wp.reloadTicks
        d.flags[i] = d.flags[i] and DeviceFlags.READY.inv()

        val angle = d.aimAngle[i]
        val dx = FastTrig.cos(angle); val dy = -FastTrig.sin(angle)
        if (wp.recoilImpulse > 0f) {
            val cap = state.config.combat.recoilDvCap
            // Rückstoß entgegen der Schussrichtung (Prototyp: px += dir · dv · H)
            CombatOps.push(state, beams.a[beam], -dx, -dy, wp.recoilImpulse, cap)
            CombatOps.push(state, beams.b[beam], -dx, -dy, wp.recoilImpulse, cap)
        }
        when (wp.mode) {
            WeaponMode.BALLISTIC -> {
                val st = world.ballistic
                Ballistics.launch(mx, my, angle, d.power[i], wp.muzzleSpeed, st)
                state.projectiles.alloc(
                    st[Ballistics.X], st[Ballistics.Y], st[Ballistics.VX], st[Ballistics.VY],
                    kind = p.weapon, owner = owner, ttlTicks = if (wp.ttlTicks > 0) wp.ttlTicks else Int.MAX_VALUE,
                    sourceDevice = d.uidOf[i], ignoreBeamRef = beams.ref(beam), pierceLeft = wp.piercesBeams,
                    incendiary = wp.igniteRadius > 0f,
                )
                ctx.fx.add(FxEvent.Fired(state.tick, mx, my, d.uidOf[i], p.weapon, angle))
            }
            WeaponMode.HITSCAN -> {
                d.burstLeft[i] = if (wp.shotsPerBurst > 1) wp.shotsPerBurst else 1
                d.burstTicks[i] = 0
            }
            WeaponMode.BEAM -> {
                d.beamTicksLeft[i] = if (wp.beamTicks > 1) wp.beamTicks else 1
                d.flags[i] = d.flags[i] or DeviceFlags.FIRING_BEAM
                ctx.fx.add(FxEvent.Fired(state.tick, mx, my, d.uidOf[i], p.weapon, angle))
            }
        }
    }

    internal companion object {
        /** Gesperrt, im Bau oder deaktiviert (z. B. keine Energie): feuert nicht und ist nicht READY. */
        const val BLOCKING_FLAGS: Int = DeviceFlags.DISABLED or DeviceFlags.BUILDING

        /**
         * Darf Waffe [i] jetzt einen neuen Schuss beginnen? Fertig gebaut (weder `buildTicks` noch [DeviceFlags.BUILDING]),
         * nicht [DeviceFlags.DISABLED], nachgeladen, keine laufende Salve/kein laufender Strahl. Gleiche Bedingung wie
         * [DeviceFlags.READY] (DEVICES) und `SnapshotBuilder`.
         */
        fun canFire(d: DevicePool, i: Int): Boolean =
            d.buildTicks[i] == 0 && (d.flags[i] and BLOCKING_FLAGS) == 0 && d.reloadTicksOf[i] == 0 &&
                d.burstLeft[i] == 0 && d.beamTicksLeft[i] == 0

        /**
         * Tür-Timer beim automatischen Öffnen im Tick T: DOORS zählt noch im selben Tick herunter, daher +1, damit die
         * Tür genau `autoCloseTicks` (2,6 s) nach dem Schuss-Tick schließt (bei T + 156).
         */
        fun autoCloseTimer(autoCloseTicks: Int): Int = autoCloseTicks + 1
    }

    /** Prototyp `doorFor`: nächste eigene Tür (kein Trümmer) im Umkreis um den Drehpunkt; geschlossen → öffnen. */
    private fun autoOpenDoor(state: GameState, ctx: StepContext, owner: Int, gx: Float, gy: Float) {
        val beams = state.beams
        val nodes = state.nodes
        val mats = state.tables.materials
        val dc = state.config.door
        var best = -1
        var bd = dc.searchRadius * dc.searchRadius
        val n = beams.size
        for (j in 0 until n) {
            if (!beams.isAlive(j) || beams.ownerOf[j] != owner) continue
            if ((beams.flags[j] and BeamFlags.DEBRIS) != 0 || !mats[beams.materialOf[j]].isDoor) continue
            val a = beams.a[j]; val b = beams.b[j]
            val d2 = Geometry.pointSegmentDistSq(gx, gy, nodes.x[a], nodes.y[a], nodes.x[b], nodes.y[b])
            if (d2 < bd) { bd = d2; best = j }
        }
        if (best < 0) return
        val f = beams.flags[best]
        if ((f and BeamFlags.DOOR_OPEN) == 0) {
            beams.flags[best] = f or BeamFlags.DOOR_OPEN
            beams.doorTimerTicks[best] = autoCloseTimer(dc.autoCloseTicks)
            val a = beams.a[best]; val b = beams.b[best]
            ctx.fx.add(
                FxEvent.DoorToggled(
                    state.tick, (nodes.x[a] + nodes.x[b]) * 0.5f, (nodes.y[a] + nodes.y[b]) * 0.5f, beams.uidOf[best], true,
                ),
            )
        } else if ((f and BeamFlags.DOOR_PINNED) == 0 && beams.doorTimerTicks[best] > 0) {
            beams.doorTimerTicks[best] = autoCloseTimer(dc.autoCloseTicks)
        }
    }

    private fun burst(state: GameState, ctx: StepContext, i: Int, p: DeviceProps, wp: WeaponProps) {
        val d = state.devices
        if (d.burstTicks[i] > 0) d.burstTicks[i]--
        if (d.burstTicks[i] > 0) return
        hitscanShot(state, ctx, i, p, wp)
        d.burstLeft[i]--
        d.burstTicks[i] = wp.burstIntervalTicks
    }

    /** Ein Hitscan-Schuss (Prototyp `mgShot`), deterministische Streuung aus `rngWeapon`. */
    private fun hitscanShot(state: GameState, ctx: StepContext, i: Int, p: DeviceProps, wp: WeaponProps) {
        val d = state.devices
        val beams = state.beams
        val spread = if (wp.spreadRad > 0f) state.rngWeapon.nextFloat(-wp.spreadRad, wp.spreadRad) else 0f
        val angle = d.aimAngle[i] + spread
        if (!beams.isAlive(d.beamId[i])) return
        val geo = world.geo
        DeviceGeometry.mount(state, i, geo)
        val dx = FastTrig.cos(angle); val dy = -FastTrig.sin(angle)
        val gx = geo[DeviceGeometry.PIVOT_X]; val gy = geo[DeviceGeometry.PIVOT_Y]
        val x0 = gx + dx * p.barrelLength; val y0 = gy + dy * p.barrelLength
        val x1 = x0 + dx * wp.maxRange; val y1 = y0 + dy * wp.maxRange
        val mount = d.beamId[i]
        var pierced = -1
        var pierceLeft = wp.piercesBeams
        var endX = x1; var endY = y1
        val hit = world.hit
        while (true) {
            if (!world.caster.cast(state, x0, y0, x1, y1, wp.projectileRadius, mount, pierced, mount, true, hit)) break
            val hx = hit.x; val hy = hit.y
            when (hit.kind) {
                RayHit.BEAM -> {
                    val j = hit.id
                    val uid = beams.uidOf[j]
                    val na = beams.a[j]; val nb = beams.b[j]
                    CombatOps.damageBeam(state, ctx, j, wp.damage, hx, hy)
                    if (wp.directImpulse > 0f) {
                        val cap = state.config.combat.directDvCap
                        CombatOps.push(state, na, dx, dy, wp.directImpulse, cap)
                        CombatOps.push(state, nb, dx, dy, wp.directImpulse, cap)
                    }
                    ctx.fx.add(FxEvent.Hit(state.tick, hx, hy, HitTarget.BEAM, uid, wp.damage, p.weapon))
                    if (pierceLeft > 0) {
                        pierceLeft--
                        pierced = j
                        continue
                    }
                }
                RayHit.DEVICE -> {
                    val dmg = wp.damage * wp.deviceDamageFactor
                    ctx.fx.add(FxEvent.Hit(state.tick, hx, hy, HitTarget.DEVICE, d.uidOf[hit.id], dmg, p.weapon))
                    CombatOps.damageDevice(state, ctx, hit.id, dmg)
                }
                else -> ctx.fx.add(FxEvent.Hit(state.tick, hx, hy, HitTarget.TERRAIN, -1, wp.damage, p.weapon))
            }
            endX = hx; endY = hy
            break
        }
        ctx.fx.add(FxEvent.Fired(state.tick, x0, y0, d.uidOf[i], p.weapon, angle))
        ctx.fx.add(FxEvent.Tracer(state.tick, x0, y0, endX, endY, p.weapon))
    }

    /** Laser: ein Tick Dauerstrahl (Schaden `damage · dt` am ersten Treffer). */
    private fun laser(state: GameState, ctx: StepContext, i: Int, p: DeviceProps, wp: WeaponProps) {
        val d = state.devices
        val beams = state.beams
        val mount = d.beamId[i]
        if (!beams.isAlive(mount)) return
        val geo = world.geo
        DeviceGeometry.mount(state, i, geo)
        val angle = d.aimAngle[i]
        val dx = FastTrig.cos(angle); val dy = -FastTrig.sin(angle)
        val x0 = geo[DeviceGeometry.MUZZLE_X]; val y0 = geo[DeviceGeometry.MUZZLE_Y]
        val x1 = x0 + dx * wp.maxRange; val y1 = y0 + dy * wp.maxRange
        val hit = world.hit
        var endX = x1; var endY = y1
        val dmg = wp.damage * state.config.dt
        if (world.caster.cast(state, x0, y0, x1, y1, wp.projectileRadius, mount, -1, mount, true, hit)) {
            endX = hit.x; endY = hit.y
            when (hit.kind) {
                RayHit.BEAM -> CombatOps.damageBeam(state, ctx, hit.id, dmg, endX, endY)
                RayHit.DEVICE -> CombatOps.damageDevice(state, ctx, hit.id, dmg * wp.deviceDamageFactor)
                else -> Unit
            }
        }
        d.laserEndX[i] = endX; d.laserEndY[i] = endY
        ctx.fx.add(FxEvent.LaserBeam(state.tick, x0, y0, endX, endY, d.uidOf[i]))
        if (!d.isAlive(i)) return
        d.beamTicksLeft[i]--
        if (d.beamTicksLeft[i] <= 0) {
            d.beamTicksLeft[i] = 0
            d.flags[i] = d.flags[i] and DeviceFlags.FIRING_BEAM.inv()
        }
    }
}
