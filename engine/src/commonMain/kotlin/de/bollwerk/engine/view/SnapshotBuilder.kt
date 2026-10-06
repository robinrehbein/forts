package de.bollwerk.engine.view

import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameState

/**
 * Füllt einen wiederverwendbaren [FrameSnapshot] aus dem [GameState] (nur im Sim-Thread aufrufen).
 * Allokiert Arrays nur, wenn ein Pool über die bisherige Snapshot-Kapazität wächst.
 *
 * Interpolations-Startpunkte kommen aus `NodePool.tickX/tickY` bzw. `ProjectilePool.tickX/tickY`
 * (Position zu Tickbeginn), nicht aus einem früheren Snapshot – der Snapshot ist damit unabhängig von
 * Pufferwiederverwendung und von der Anzahl Ticks pro Frame.
 */
class SnapshotBuilder(
    /** Spieler, für den das HUD befüllt wird (−1 = kein HUD). */
    var localPlayer: Int = 0,
) {
    private var seq = 0L

    /**
     * @param fx optional: Sammelpuffer der Session; wird in `out.fx` entleert. `out.fx` wird vorher geleert,
     *   außer der Puffer wurde nie gelesen (`out.carryFx`, dann bleiben die alten Ereignisse erhalten).
     */
    fun build(state: GameState, out: FrameSnapshot, fx: FxBuffer? = null): FrameSnapshot {
        val tables = state.tables
        out.seq = ++seq
        out.tick = state.tick
        out.wind = state.wind
        out.result = state.result
        val turn = state.turn
        out.turnMode = turn.mode; out.turnActivePlayer = turn.activePlayer
        out.turnNumber = turn.turnNumber; out.turnTicksLeft = turn.ticksLeft; out.turnPhase = turn.phase

        // Knoten
        val n = state.nodes
        val nc = n.size
        if (out.nodeX.size < nc) growNodes(out, capFor(nc))
        for (i in 0 until nc) {
            out.nodeX[i] = n.x[i]; out.nodeY[i] = n.y[i]
            out.nodePrevX[i] = n.tickX[i]; out.nodePrevY[i] = n.tickY[i]
            out.nodeFlags[i] = n.flags[i]
            out.nodeOwner[i] = n.ownerOf[i]
            out.nodeUid[i] = n.uidOf[i]
        }
        out.nodeCount = nc

        // Balken
        val b = state.beams
        val bc = b.size
        if (out.beamA.size < bc) growBeams(out, capFor(bc))
        for (i in 0 until bc) {
            out.beamA[i] = b.a[i]; out.beamB[i] = b.b[i]
            val m = b.materialOf[i]
            out.beamMaterial[i] = m
            out.beamUid[i] = b.uidOf[i]
            out.beamRestLen[i] = b.restLen[i]
            val mh = b.maxHpOf[i]
            out.beamHp01[i] = if (mh > 0f) b.hpOf[i] / mh else 0f
            out.beamFire01[i] = b.fireOf[i]
            out.beamFuel01[i] = b.fuelOf[i]
            val s = b.strainOf[i]
            var load = 0f
            if (m >= 0 && m < tables.materials.size) {
                val mat = tables.materials[m]
                val lim = if (s >= 0f) mat.tensionLimit else mat.compressionLimit
                if (lim > 0f && lim != Float.POSITIVE_INFINITY) load = (if (s < 0f) -s else s) / lim
            }
            out.beamLoad01[i] = load
            out.beamTexOffset[i] = b.texOffset[i]
            out.beamFlags[i] = b.flags[i]
            out.beamOwner[i] = b.ownerOf[i]
        }
        out.beamCount = bc

        // Geräte
        val d = state.devices
        val dc = d.size
        if (out.deviceType.size < dc) growDevices(out, capFor(dc))
        for (i in 0 until dc) {
            val type = d.typeOf[i]
            out.deviceType[i] = type; out.deviceUid[i] = d.uidOf[i]
            out.deviceBeam[i] = d.beamId[i]; out.deviceT[i] = d.tOf[i]
            val mh = d.maxHpOf[i]
            out.deviceHp01[i] = if (mh > 0f) d.hpOf[i] / mh else 0f
            out.deviceMaxHp[i] = mh
            out.deviceOwner[i] = d.ownerOf[i]
            out.deviceAim[i] = d.aimAngle[i]; out.devicePower[i] = d.power[i]
            out.deviceReload01[i] = reload01(state, i)
            out.deviceBuild01[i] = build01(state, i)
            out.deviceX[i] = d.x[i]; out.deviceY[i] = d.y[i]
            out.deviceNX[i] = d.nx[i]; out.deviceNY[i] = d.ny[i]
            out.deviceLaserEndX[i] = d.laserEndX[i]; out.deviceLaserEndY[i] = d.laserEndY[i]
            out.deviceFlags[i] = d.flags[i]
        }
        out.deviceCount = dc

        // Projektile
        val p = state.projectiles
        val pc = p.size
        if (out.projX.size < pc) growProjectiles(out, capFor(pc))
        for (i in 0 until pc) {
            out.projX[i] = p.x[i]; out.projY[i] = p.y[i]
            out.projPrevX[i] = p.tickX[i]; out.projPrevY[i] = p.tickY[i]
            out.projVx[i] = p.vx[i]; out.projVy[i] = p.vy[i]
            out.projKind[i] = p.kindOf[i]; out.projOwner[i] = p.ownerOf[i]
            out.projUid[i] = p.uidOf[i]
            out.projFlags[i] = p.flags[i]
        }
        out.projectileCount = pc

        if (out.carryFx) out.carryFx = false else out.fx.clear()
        fx?.drainTo(out.fx)
        out.hud = buildHud(state)
        return out
    }

    /** HUD-Werte für [localPlayer]; gegnerischer Reaktor = erster anderer Spieler mit Reaktor. */
    fun buildHud(state: GameState): HudModel {
        val lp = localPlayer
        if (lp < 0 || lp >= state.players.size) return HudModel.EMPTY
        val me = state.players[lp]
        var enemy01 = 1f
        for (pl in state.players) {
            if (pl.id == lp) continue
            enemy01 = reactor01(state, pl.reactorDeviceId)
            break
        }
        val techs = ArrayList<Int>()
        val ts = me.techUnlocked
        for (w in 0 until ts.wordCount) {
            val bits = ts.word(w)
            if (bits == 0L) continue
            for (k in 0 until 64) if ((bits and (1L shl k)) != 0L) techs.add(w * 64 + k)
        }
        val weapons = ArrayList<HudWeapon>()
        val building = ArrayList<HudTechBuild>()
        val d = state.devices
        val tables = state.tables
        for (i in 0 until d.size) {
            if (!d.isAlive(i) || d.ownerOf[i] != lp) continue
            val type = d.typeOf[i]
            if (type < 0 || type >= tables.devices.size) continue
            val props = tables.devices[type]
            if (props.weapon >= 0) {
                val r = reload01(state, i)
                val ready = r >= 1f && d.buildTicks[i] == 0 && (d.flags[i] and DeviceFlags.DISABLED) == 0
                weapons.add(HudWeapon(d.ref(i), type, r, ready))
            }
            if (props.role == DeviceRole.TECH && d.buildTicks[i] > 0) building.add(HudTechBuild(d.ref(i), type, build01(state, i)))
        }
        val dt = state.config.dt
        return HudModel(
            metal = me.metal, metalRate = me.metalRate, metalCap = me.metalCap,
            energy = me.energy, energyCap = me.energyCap, energyRate = me.energyRate,
            timeSeconds = state.tick * dt,
            windSpeed = state.wind,
            ownReactor01 = reactor01(state, me.reactorDeviceId),
            enemyReactor01 = enemy01,
            localPlayer = lp,
            activePlayer = state.turn.activePlayer,
            turnNumber = state.turn.turnNumber,
            turnSecondsLeft = state.turn.ticksLeft * dt,
            result = state.result,
            unlockedTechs = techs,
            weapons = weapons,
            buildingTech = building,
            undoCount = me.undoCount,
        )
    }

    private fun reactor01(state: GameState, r: Int): Float {
        if (r < 0) return 1f
        val d = state.devices
        if (!d.isAlive(r)) return 0f
        val mh = d.maxHpOf[r]
        return if (mh > 0f) d.hpOf[r] / mh else 0f
    }

    private fun reload01(state: GameState, i: Int): Float {
        val d = state.devices
        val type = d.typeOf[i]
        val tables = state.tables
        if (type < 0 || type >= tables.devices.size) return 1f
        val w = tables.devices[type].weapon
        if (w < 0) return 1f
        val total = tables.weapons[w].reloadTicks
        if (total <= 0) return 1f
        val left = d.reloadTicksOf[i]
        return if (left <= 0) 1f else 1f - left.toFloat() / total.toFloat()
    }

    private fun build01(state: GameState, i: Int): Float {
        val d = state.devices
        val type = d.typeOf[i]
        val tables = state.tables
        if (type < 0 || type >= tables.devices.size) return 1f
        val total = tables.devices[type].buildTicks
        val left = d.buildTicks[i]
        if (total <= 0 || left <= 0) return 1f
        return 1f - left.toFloat() / total.toFloat()
    }

    private fun capFor(n: Int): Int {
        var c = 16
        while (c < n) c *= 2
        return c
    }

    private fun growNodes(s: FrameSnapshot, c: Int) {
        s.nodeX = s.nodeX.copyOf(c); s.nodeY = s.nodeY.copyOf(c)
        s.nodePrevX = s.nodePrevX.copyOf(c); s.nodePrevY = s.nodePrevY.copyOf(c)
        s.nodeFlags = s.nodeFlags.copyOf(c); s.nodeOwner = s.nodeOwner.copyOf(c)
        s.nodeUid = s.nodeUid.copyOf(c)
    }

    private fun growBeams(s: FrameSnapshot, c: Int) {
        s.beamA = s.beamA.copyOf(c); s.beamB = s.beamB.copyOf(c)
        s.beamMaterial = s.beamMaterial.copyOf(c); s.beamUid = s.beamUid.copyOf(c)
        s.beamRestLen = s.beamRestLen.copyOf(c); s.beamHp01 = s.beamHp01.copyOf(c)
        s.beamFire01 = s.beamFire01.copyOf(c); s.beamFuel01 = s.beamFuel01.copyOf(c)
        s.beamLoad01 = s.beamLoad01.copyOf(c); s.beamTexOffset = s.beamTexOffset.copyOf(c)
        s.beamFlags = s.beamFlags.copyOf(c); s.beamOwner = s.beamOwner.copyOf(c)
    }

    private fun growDevices(s: FrameSnapshot, c: Int) {
        s.deviceType = s.deviceType.copyOf(c); s.deviceUid = s.deviceUid.copyOf(c)
        s.deviceBeam = s.deviceBeam.copyOf(c); s.deviceT = s.deviceT.copyOf(c)
        s.deviceHp01 = s.deviceHp01.copyOf(c); s.deviceMaxHp = s.deviceMaxHp.copyOf(c)
        s.deviceOwner = s.deviceOwner.copyOf(c); s.deviceAim = s.deviceAim.copyOf(c)
        s.devicePower = s.devicePower.copyOf(c); s.deviceReload01 = s.deviceReload01.copyOf(c)
        s.deviceBuild01 = s.deviceBuild01.copyOf(c)
        s.deviceX = s.deviceX.copyOf(c); s.deviceY = s.deviceY.copyOf(c)
        s.deviceNX = s.deviceNX.copyOf(c); s.deviceNY = s.deviceNY.copyOf(c)
        s.deviceLaserEndX = s.deviceLaserEndX.copyOf(c); s.deviceLaserEndY = s.deviceLaserEndY.copyOf(c)
        s.deviceFlags = s.deviceFlags.copyOf(c)
    }

    private fun growProjectiles(s: FrameSnapshot, c: Int) {
        s.projX = s.projX.copyOf(c); s.projY = s.projY.copyOf(c)
        s.projPrevX = s.projPrevX.copyOf(c); s.projPrevY = s.projPrevY.copyOf(c)
        s.projVx = s.projVx.copyOf(c); s.projVy = s.projVy.copyOf(c)
        s.projKind = s.projKind.copyOf(c); s.projOwner = s.projOwner.copyOf(c)
        s.projUid = s.projUid.copyOf(c); s.projFlags = s.projFlags.copyOf(c)
    }
}
