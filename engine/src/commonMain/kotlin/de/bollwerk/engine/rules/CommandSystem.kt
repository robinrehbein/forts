package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.CommandValidator
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.physics.BeamBreaker
import de.bollwerk.engine.physics.DeviceKiller
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.BeamRecord
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.DeviceRemap
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.SplitRecord
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.UndoEntry
import de.bollwerk.engine.sim.UndoKind
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.view.BreakCause
import de.bollwerk.engine.view.FxEvent

/**
 * [de.bollwerk.engine.sim.SystemSlot.COMMANDS]: Prüft jedes Command des Ticks **nacheinander** mit [validator] gegen den
 * sich ändernden Zustand und wendet es an (oder lehnt es ab). Dadurch kann ein Spieler mit zwei Commands im selben Tick
 * nie mehr ausgeben, als er hat, und ein Command darf auf dem Ergebnis des vorigen aufbauen. `ctx.results` bekommt je
 * Eintrag von `ctx.commands` genau ein Ergebnis.
 *
 * Vor den Commands entfernt [UndoRules.prune] unerfüllbare Zurück-Einträge (abgelaufen oder Bauwerk zerstört).
 */
class CommandSystem(private val validator: CommandValidator = RulesValidator) : SimSystem {
    private val undoScratch = UndoRules.Scratch()

    override fun step(state: GameState, ctx: StepContext) {
        UndoRules.prune(state, undoScratch)
        ctx.results.clear()
        for (i in ctx.commands.indices) {
            val cmd = ctx.commands[i]
            val reason = validator.validate(state, cmd)
            if (reason != null) {
                ctx.results.add(CommandResult.Rejected(reason))
                continue
            }
            CommandApplier.apply(state, ctx, cmd)
            ctx.results.add(CommandResult.Accepted)
        }
    }
}

/** Wendet bereits geprüfte Commands an (nur über [CommandSystem] aufrufen; jede Funktion setzt Gültigkeit voraus). */
object CommandApplier {
    fun apply(state: GameState, ctx: StepContext, cmd: Command) {
        when (cmd) {
            is Command.PlaceBeam -> placeBeam(state, ctx, cmd)
            is Command.DeleteBeam -> deleteBeam(state, ctx, cmd)
            is Command.RepairBeam -> repairBeam(state, cmd)
            is Command.PlaceDevice -> placeDevice(state, ctx, cmd)
            is Command.DeleteDevice -> deleteDevice(state, ctx, cmd)
            is Command.ToggleDoor -> toggleDoor(state, ctx, cmd)
            is Command.SetAim -> setAim(state, cmd)
            is Command.Fire -> fire(state, cmd)
            is Command.Undo -> UndoRules.apply(state, ctx, cmd.playerId)
            is Command.EndTurn -> TurnRules.beginResolve(state)
            is Command.Surrender -> surrender(state, cmd.playerId)
        }
    }

    // ---- Balken ----

    private fun placeBeam(state: GameState, ctx: StepContext, cmd: Command.PlaceBeam) {
        val owner = cmd.playerId
        val plan = BeamPlan()
        BeamPlanner.plan(state, cmd, plan)
        val mat = state.tables.materials[cmd.materialId]
        val newNodes = ArrayList<Long>(2)
        val splits = ArrayList<SplitRecord>(2)
        val na = endNode(state, ctx, plan.a, owner, newNodes, splits)
        val nb = endNode(state, ctx, plan.b, owner, newNodes, splits)
        val nodes = state.nodes
        val len = RuleChecks.dist(nodes.x[na], nodes.y[na], nodes.x[nb], nodes.y[nb])
        val id = state.beams.alloc(na, nb, cmd.materialId, len * mat.restLengthFactor, mat.hp, owner)
        // Tür: Scharnier am unteren Ende (Prototyp `hingeAtB = b.y > a.y`)
        if (mat.isDoor && nodes.y[nb] > nodes.y[na]) state.beams.flags[id] = state.beams.flags[id] or BeamFlags.DOOR_HINGE_B
        val cost = RuleCost.beam(len, mat.costPerMeter)
        state.players[owner].metal -= cost
        state.players[owner].undoJournal.push(
            UndoEntry(UndoKind.BEAM, state.tick, state.beams.ref(id), cost, 0f, newNodes, splits),
        )
        state.topologyDirty = true
        ctx.fx.add(
            FxEvent.BeamPlaced(
                state.tick, (nodes.x[na] + nodes.x[nb]) * 0.5f, (nodes.y[na] + nodes.y[nb]) * 0.5f,
                state.beams.uidOf[id], cmd.materialId,
            ),
        )
    }

    private fun endNode(
        state: GameState, ctx: StepContext, e: EndPoint, owner: Int, newNodes: MutableList<Long>, splits: MutableList<SplitRecord>,
    ): Int = when (e.kind) {
        EndKind.NODE -> e.node
        EndKind.SPLIT -> splitBeam(state, ctx, e.beam, e.t, splits)
        EndKind.FREE -> {
            val n = state.nodes.alloc(e.x, e.y, owner, anchored = false)
            newNodes.add(state.nodes.ref(n))
            n
        }
    }

    /**
     * Teilt Balken [id] bei [t] (Prototyp `splitBeam`): neuer Knoten (Geschwindigkeit interpoliert), zwei Hälften mit
     * TP/Feuer/Brennstoff/Maserungs-Versatz des Originals und Ruhelängen `rest·t` bzw. `rest·(1−t)`; Geräte ziehen mit
     * umgerechnetem Parameter auf die passende Hälfte um.
     * @return der neue Knoten (Slot); der Journal-Eintrag wird an [splits] angehängt.
     */
    private fun splitBeam(state: GameState, ctx: StepContext, id: Int, t: Float, splits: MutableList<SplitRecord>): Int {
        val beams = state.beams
        val nodes = state.nodes
        val devices = state.devices
        val na = beams.a[id]
        val nb = beams.b[id]
        val owner = beams.ownerOf[id]
        val x = nodes.x[na] + (nodes.x[nb] - nodes.x[na]) * t
        val y = nodes.y[na] + (nodes.y[nb] - nodes.y[na]) * t
        val n = nodes.alloc(x, y, owner, anchored = false)
        nodes.px[n] = nodes.px[na] + (nodes.px[nb] - nodes.px[na]) * t
        nodes.py[n] = nodes.py[na] + (nodes.py[nb] - nodes.py[na]) * t

        val rest = beams.restLen[id]
        val material = beams.materialOf[id]
        val maxHp = beams.maxHpOf[id]
        val flags = beams.flags[id]
        val tex = beams.texOffset[id]
        val record = BeamRecord(
            nodeARef = nodes.ref(na), nodeBRef = nodes.ref(nb), material = material, owner = owner, restLen = rest,
            hp = beams.hpOf[id], maxHp = maxHp, fire = beams.fireOf[id], fuel = beams.fuelOf[id], flags = flags,
            texOffset = tex, ref = beams.ref(id),
        )
        val h1 = beams.alloc(na, n, material, rest * t, maxHp, owner, tex)
        val h2 = beams.alloc(n, nb, material, rest * (1f - t), maxHp, owner, tex + rest * t)
        val common = flags and (BeamFlags.JAG_A or BeamFlags.JAG_B).inv()
        for (h in intArrayOf(h1, h2)) {
            beams.hpOf[h] = record.hp
            beams.fireOf[h] = record.fire
            beams.fuelOf[h] = record.fuel
        }
        beams.flags[h1] = common or BeamFlags.ALIVE or (flags and BeamFlags.JAG_A)
        beams.flags[h2] = common or BeamFlags.ALIVE or (flags and BeamFlags.JAG_B)

        val remaps = ArrayList<DeviceRemap>(2)
        for (d in 0 until devices.size) {
            if (!devices.isAlive(d) || devices.beamId[d] != id) continue
            val dt = devices.tOf[d]
            remaps.add(DeviceRemap(devices.ref(d), dt))
            if (dt < t) {
                devices.beamId[d] = h1
                devices.tOf[d] = dt / t
            } else {
                devices.beamId[d] = h2
                devices.tOf[d] = (dt - t) / (1f - t)
            }
        }
        ctx.fx.add(FxEvent.BeamSplit(state.tick, x, y, beams.uidOf[id], beams.uidOf[h1], beams.uidOf[h2]))
        splits.add(SplitRecord(nodes.ref(n), beams.ref(h1), beams.ref(h2), record, remaps))
        beams.release(id)
        state.topologyDirty = true
        return n
    }

    private fun deleteBeam(state: GameState, ctx: StepContext, cmd: Command.DeleteBeam) {
        val id = state.beams.resolve(cmd.beamRef)
        val beams = state.beams
        if (beams.fireOf[id] > 0f) {
            // Prototyp: ein brennender Balken wird nur gelöscht, nicht abgerissen
            beams.fireOf[id] = 0f
            return
        }
        val p = state.players[cmd.playerId]
        p.metal += RuleCost.beamRefund(state, id)
        val devices = state.devices
        for (d in 0 until devices.size) {
            if (devices.isAlive(d) && devices.beamId[d] == id) refundDevice(state, p.id, d)
        }
        BeamBreaker.breakBeam(state, ctx, id, 0.5f, BreakCause.DELETED)
    }

    private fun repairBeam(state: GameState, cmd: Command.RepairBeam) {
        val id = state.beams.resolve(cmd.beamRef)
        state.beams.flags[id] = state.beams.flags[id] or BeamFlags.REPAIRING
    }

    // ---- Geräte ----

    private fun placeDevice(state: GameState, ctx: StepContext, cmd: Command.PlaceDevice) {
        val owner = cmd.playerId
        val plan = DevicePlan()
        DevicePlanner.plan(state, cmd, plan)
        val props = state.tables.devices[cmd.deviceTypeId]
        var aim = 0f
        var power = state.config.maxPower
        if (props.weapon >= 0) {
            val w = state.tables.weapons[props.weapon]
            aim = if (state.players[owner].facing >= 0) w.defaultAimRad else FloatMath.PI - w.defaultAimRad
            power = w.defaultPower
        }
        val id = state.devices.alloc(
            type = cmd.deviceTypeId, beam = plan.beam, t = plan.t, maxHp = props.hp, owner = owner,
            buildTicks = props.buildTicks, sideNegative = plan.sideNegative, aimAngle = aim, power = power,
        )
        val p = state.players[owner]
        p.metal -= props.costMetal
        p.energy -= props.costEnergy
        if (props.role == DeviceRole.REACTOR && p.reactorDeviceId < 0) p.reactorDeviceId = id
        p.undoJournal.push(UndoEntry(UndoKind.DEVICE, state.tick, state.devices.ref(id), props.costMetal, props.costEnergy))
        state.topologyDirty = true
        ctx.fx.add(
            FxEvent.DevicePlaced(
                state.tick, plan.geo[DeviceGeometry.X], plan.geo[DeviceGeometry.Y], state.devices.uidOf[id], cmd.deviceTypeId,
            ),
        )
    }

    private fun deleteDevice(state: GameState, ctx: StepContext, cmd: Command.DeleteDevice) {
        val id = state.devices.resolve(cmd.deviceRef)
        refundDevice(state, cmd.playerId, id)
        DeviceKiller.kill(state, ctx, id, silent = true)
    }

    /** Erstattet `deleteRefund ·` Baukosten von Gerät [id]. */
    private fun refundDevice(state: GameState, player: Int, id: Int) {
        val props = state.tables.devices[state.devices.typeOf[id]]
        val p = state.players[player]
        p.metal += state.config.deleteRefund * props.costMetal
        p.energy += state.config.deleteRefund * props.costEnergy
    }

    private fun toggleDoor(state: GameState, ctx: StepContext, cmd: Command.ToggleDoor) {
        val beams = state.beams
        val id = beams.resolve(cmd.beamRef)
        val open = (beams.flags[id] and BeamFlags.DOOR_OPEN) == 0
        if (open) {
            // vom Spieler geöffnet = fixiert, kein automatisches Schließen
            beams.flags[id] = beams.flags[id] or BeamFlags.DOOR_OPEN or BeamFlags.DOOR_PINNED
        } else {
            beams.flags[id] = beams.flags[id] and (BeamFlags.DOOR_OPEN or BeamFlags.DOOR_PINNED).inv()
        }
        beams.doorTimerTicks[id] = 0
        val nodes = state.nodes
        val a = beams.a[id]
        val b = beams.b[id]
        ctx.fx.add(
            FxEvent.DoorToggled(
                state.tick, (nodes.x[a] + nodes.x[b]) * 0.5f, (nodes.y[a] + nodes.y[b]) * 0.5f, beams.uidOf[id], open,
            ),
        )
    }

    // ---- Waffen ----

    private fun setAim(state: GameState, cmd: Command.SetAim) {
        val id = state.devices.resolve(cmd.deviceRef)
        state.devices.aimAngle[id] = RulesValidator.clampAim(state, id, cmd.angle)
        state.devices.power[id] = FloatMath.clamp(cmd.power, state.config.minPower, state.config.maxPower)
    }

    /** Setzt die Schuss-Anforderung; das Waffen-System (WP4) verbraucht sie im selben Tick und zieht die Kosten ab. */
    private fun fire(state: GameState, cmd: Command.Fire) {
        val id = state.devices.resolve(cmd.deviceRef)
        state.devices.flags[id] = state.devices.flags[id] or DeviceFlags.FIRE_REQUESTED
    }

    // ---- Aufgeben ----

    private fun surrender(state: GameState, player: Int) {
        state.players[player].alive = false
        if (state.result != GameResult.Ongoing) return
        var left = -1
        var count = 0
        for (p in state.players) if (p.alive) { left = p.id; count++ }
        if (count == 1) state.result = GameResult.Winner(left, WinReason.SURRENDER)
        else if (count == 0) state.result = GameResult.Draw
    }
}
