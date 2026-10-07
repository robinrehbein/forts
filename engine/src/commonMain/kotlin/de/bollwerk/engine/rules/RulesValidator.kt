package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandChecks
import de.bollwerk.engine.command.CommandValidator
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.view.GameView

/**
 * Der echte [CommandValidator] (WP3): prüft jedes Command gegen die Spielregeln, **ohne** den Zustand zu verändern.
 * Er liest nur die [GameView] und liefert für Command-System, Ghost-Vorschau (Werkzeuge) und KI dieselbe Wahrheit.
 * Zuerst läuft [CommandChecks.precheck] (Spielende, Zug, endliche Floats, Content-Indizes, veraltete Refs).
 *
 * **Nicht als `GameSession.precheck` verwenden:** Die Session prüft alle Commands eines Ticks gegen den Zustand
 * **vor** dem Tick; ein Command, das erst durch ein früheres im selben Tick gültig wird (Balken an den Endpunkt des
 * vorigen), würde dort fälschlich abgelehnt. Das Command-System ([CommandSystem], `SystemSlot.COMMANDS`) ruft diesen
 * Validator stattdessen **sequenziell** gegen den sich ändernden Zustand auf; die Session behält `BasicValidator`.
 */
object RulesValidator : CommandValidator {
    override fun validate(view: GameView, cmd: Command): RejectReason? {
        CommandChecks.precheck(view, cmd)?.let { return it }
        return when (cmd) {
            is Command.PlaceBeam -> BeamPlanner.plan(view, cmd)
            is Command.DeleteBeam -> deleteBeam(view, cmd)
            is Command.RepairBeam -> repairBeam(view, cmd)
            is Command.PlaceDevice -> DevicePlanner.plan(view, cmd)
            is Command.DeleteDevice -> deleteDevice(view, cmd)
            is Command.ToggleDoor -> toggleDoor(view, cmd)
            is Command.SetAim -> setAim(view, cmd)
            is Command.Fire -> fire(view, cmd)
            is Command.Undo -> UndoRules.check(view, cmd.playerId)
            is Command.EndTurn -> if (view.turn.mode == TurnMode.TURNS) null else RejectReason.INVALID_TARGET
            is Command.Surrender -> null
        }
    }

    private fun deleteBeam(view: GameView, cmd: Command.DeleteBeam): RejectReason? {
        val beams = view.beamView
        val id = beams.resolve(cmd.beamRef)
        if (id < 0) return RejectReason.STALE_TARGET
        if (beams.owner(id) != cmd.playerId) return RejectReason.NOT_OWNER
        if (carriesReactor(view, id)) return RejectReason.REACTOR_PROTECTED
        return null
    }

    private fun repairBeam(view: GameView, cmd: Command.RepairBeam): RejectReason? {
        val beams = view.beamView
        val id = beams.resolve(cmd.beamRef)
        if (id < 0) return RejectReason.STALE_TARGET
        if (beams.owner(id) != cmd.playerId) return RejectReason.NOT_OWNER
        if ((beams.flags(id) and BeamFlags.DEBRIS) != 0) return RejectReason.INVALID_TARGET
        if (beams.hp(id) >= beams.maxHp(id)) return RejectReason.INVALID_TARGET
        return null
    }

    private fun deleteDevice(view: GameView, cmd: Command.DeleteDevice): RejectReason? {
        val devices = view.deviceView
        val id = devices.resolve(cmd.deviceRef)
        if (id < 0) return RejectReason.STALE_TARGET
        if (devices.owner(id) != cmd.playerId) return RejectReason.NOT_OWNER
        if (view.tables.devices[devices.type(id)].role == DeviceRole.REACTOR) return RejectReason.REACTOR_PROTECTED
        return null
    }

    private fun toggleDoor(view: GameView, cmd: Command.ToggleDoor): RejectReason? {
        val beams = view.beamView
        val id = beams.resolve(cmd.beamRef)
        if (id < 0) return RejectReason.STALE_TARGET
        if (beams.owner(id) != cmd.playerId) return RejectReason.NOT_OWNER
        if (!view.tables.materials[beams.material(id)].isDoor) return RejectReason.INVALID_TARGET
        if ((beams.flags(id) and BeamFlags.DEBRIS) != 0) return RejectReason.INVALID_TARGET
        return null
    }

    private fun setAim(view: GameView, cmd: Command.SetAim): RejectReason? {
        val devices = view.deviceView
        val id = devices.resolve(cmd.deviceRef)
        if (id < 0) return RejectReason.STALE_TARGET
        if (devices.owner(id) != cmd.playerId) return RejectReason.NOT_OWNER
        if (view.tables.devices[devices.type(id)].weapon < 0) return RejectReason.INVALID_TARGET
        if (FloatMath.abs(cmd.angle) > RuleConst.MAX_ANGLE) return RejectReason.INVALID_TARGET
        return null
    }

    private fun fire(view: GameView, cmd: Command.Fire): RejectReason? {
        val devices = view.deviceView
        val id = devices.resolve(cmd.deviceRef)
        if (id < 0) return RejectReason.STALE_TARGET
        if (devices.owner(id) != cmd.playerId) return RejectReason.NOT_OWNER
        val props = view.tables.devices[devices.type(id)]
        if (props.weapon < 0) return RejectReason.INVALID_TARGET
        // eine Waffe auf Trümmern (oder totem Balken) schießt nicht; lieber ablehnen als still verschlucken
        if (!Economy.onLiveBeam(view, id)) return RejectReason.INVALID_TARGET
        val flags = devices.flags(id)
        if (devices.buildTicks(id) > 0 || (flags and DeviceFlags.BUILDING) != 0) return RejectReason.STILL_BUILDING
        if ((flags and DeviceFlags.FIRE_REQUESTED) != 0 || devices.reloadTicks(id) > 0 || devices.burstLeft(id) > 0 ||
            (flags and DeviceFlags.FIRING_BEAM) != 0
        ) return RejectReason.RELOADING
        val w = view.tables.weapons[props.weapon]
        if (w.shotMetal > RuleChecks.availableMetal(view, cmd.playerId)) return RejectReason.NOT_ENOUGH_METAL
        if (w.shotEnergy > RuleChecks.availableEnergy(view, cmd.playerId)) return RejectReason.NOT_ENOUGH_ENERGY
        return null
    }

    /** Sitzt der Reaktor eines Spielers auf Balken [beamId]? */
    fun carriesReactor(view: GameView, beamId: Int): Boolean {
        val d = view.deviceView
        for (i in 0 until d.size) {
            if (d.isAlive(i) && d.beam(i) == beamId && view.tables.devices[d.type(i)].role == DeviceRole.REACTOR) return true
        }
        return false
    }

    /**
     * Zielwinkel (Bogenmaß) und Kraft so begrenzen, wie `SetAim` sie anwendet: Elevation (von der Waagerechten zur
     * Feindseite) in `WeaponProps.minAimRad..maxAimRad`, Kraft in `minPower..maxPower`. Für Spieler mit `facing < 0`
     * ist der Winkel `π − Elevation`. Gemeinsam für Command-System, Zielvorschau (Werkzeuge) und KI.
     * @return geklemmter Winkel
     */
    fun clampAim(view: GameView, deviceId: Int, angle: Float): Float {
        val devices = view.deviceView
        val props = view.tables.devices[devices.type(deviceId)]
        if (props.weapon < 0) return angle
        val w = view.tables.weapons[props.weapon]
        val facing = view.player(devices.owner(deviceId)).facing
        var e = if (facing >= 0) angle else FloatMath.PI - angle
        while (e > FloatMath.PI) e -= FloatMath.TWO_PI
        while (e <= -FloatMath.PI) e += FloatMath.TWO_PI
        e = FloatMath.clamp(e, w.minAimRad, w.maxAimRad)
        return if (facing >= 0) e else FloatMath.PI - e
    }

    /** Kann der oberste Zurück-Eintrag von [player] jetzt zurückgenommen werden (UI: "Zurück" aktiv)? */
    fun canUndo(view: GameView, player: Int): Boolean = UndoRules.check(view, player) == null
}
