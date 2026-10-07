package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.rules.RuleCost
import de.bollwerk.engine.rules.UndoRules
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.view.GameView

/**
 * Tipp-Werkzeuge Reparatur, Löschen und Tür (Prototyp `tapAction`) als Zustandsautomat mit Vorschau:
 *
 * - **Down/Move** zeigen das Ziel samt Gültigkeit und Grund ([preview]: Rückerstattung beim Löschen, Kosten bei Reparatur,
 *   Brennt-Hinweis, Tür offen/zu). Bewegt sich der Finger mehr als die Tipp-Toleranz, wird die Geste zum Kameraschwenk
 *   ([panning]; kein Command).
 * - **Up** liefert `RepairBeam` / `DeleteDevice` bzw. `DeleteBeam` / `ToggleDoor` für das Ziel an der Up-Position.
 *
 * Ziele sind **eigene** Objekte: Reparatur und Löschen alle Balken (Löschen zuerst Geräte, dann Balken, Prototyp), Tür nur
 * Tür-Balken. Gültigkeit kommt vom echten Validator (`ToolContext.validator`).
 * [onTap] ist der zustandslose Einmalaufruf (Contract [TapTool]).
 */
class DefaultTapTool : TapTool {
    /** Vorschau des Ziels unter dem Finger oder `null`. */
    var preview: TargetHint? = null
        private set

    /** Die laufende Geste ist (ab jetzt) ein Kameraschwenk. */
    var panning: Boolean = false
        private set

    /** Finger liegt. */
    var pressed: Boolean = false
        private set

    /** Grund, warum das letzte Loslassen kein Command ergab (Ziel vorhanden, aber ungültig), sonst `null`. */
    var lastReject: RejectReason? = null
        private set

    private val picker = Picker()
    private val tmp = FloatArray(2)
    private val refund = FloatArray(2)
    private var downX = 0f
    private var downY = 0f

    /** Aktion eines Werkzeugs der Toolbar oder `null` (kein Tipp-Werkzeug). */
    fun actionOf(selection: ToolSelection): TapAction? = when (selection) {
        ToolSelection.Repair -> TapAction.REPAIR
        ToolSelection.Delete -> TapAction.DELETE
        ToolSelection.Door -> TapAction.DOOR
        else -> null
    }

    fun onDown(worldX: Float, worldY: Float, selection: ToolSelection, ctx: ToolContext) {
        lastReject = null
        panning = false
        downX = worldX; downY = worldY
        val action = actionOf(selection)
        if (action == null) { pressed = false; preview = null; return }
        pressed = true
        preview = evaluate(worldX, worldY, action, ctx)
    }

    fun onMove(worldX: Float, worldY: Float, selection: ToolSelection, ctx: ToolContext) {
        if (!pressed) return
        if (!panning && dist(downX, downY, worldX, worldY) > ctx.pickRadiusM * ToolConst.DRAG_SLOP_FACTOR) {
            panning = true
            preview = null
        }
        if (panning) return
        val action = actionOf(selection) ?: return
        preview = evaluate(worldX, worldY, action, ctx)
    }

    fun onUp(worldX: Float, worldY: Float, selection: ToolSelection, ctx: ToolContext): Command? {
        lastReject = null
        val wasPressed = pressed
        val wasPanning = panning
        pressed = false
        panning = false
        preview = null
        if (!wasPressed || wasPanning) return null
        val action = actionOf(selection) ?: return null
        val hint = evaluate(worldX, worldY, action, ctx) ?: return null
        if (!hint.valid) { lastReject = hint.reason; return null }
        return commandFor(hint, ctx)
    }

    fun cancel() {
        pressed = false
        panning = false
        preview = null
    }

    override fun onTap(worldX: Float, worldY: Float, selection: ToolSelection, ctx: ToolContext): Command? {
        lastReject = null
        val action = actionOf(selection) ?: return null
        val hint = evaluate(worldX, worldY, action, ctx) ?: return null
        if (!hint.valid) { lastReject = hint.reason; return null }
        return commandFor(hint, ctx)
    }

    /** Ziel von [action] an ([x], [y]) samt Vorschau oder `null`, wenn dort nichts Passendes liegt. */
    fun evaluate(x: Float, y: Float, action: TapAction, ctx: ToolContext): TargetHint? {
        val view = ctx.view
        val owner = ctx.playerId
        val r = ctx.pickRadiusM
        when (action) {
            TapAction.DOOR -> {
                val b = picker.nearestBeam(view, owner, x, y, r * ToolConst.DOOR_PICK_FACTOR, BeamFilter.DOOR, -1)
                return if (b >= 0) beamHint(action, b, ctx) else null
            }
            TapAction.REPAIR -> {
                val b = picker.nearestBeam(view, owner, x, y, r * ToolConst.BEAM_SNAP_FACTOR, BeamFilter.ANY, -1)
                return if (b >= 0) beamHint(action, b, ctx) else null
            }
            TapAction.DELETE -> {
                val d = picker.nearestDevice(view, owner, x, y, r * ToolConst.DEVICE_PICK_FACTOR, false)
                if (d >= 0) return deviceHint(d, ctx)
                val b = picker.nearestBeam(view, owner, x, y, r * ToolConst.BEAM_SNAP_FACTOR, BeamFilter.ANY, -1)
                return if (b >= 0) beamHint(action, b, ctx) else null
            }
        }
    }

    /** Hinweis für Balken-Slot [beam] und [action] (auch fürs Kontextmenü). */
    fun beamHint(action: TapAction, beam: Int, ctx: ToolContext): TargetHint {
        val view = ctx.view
        val beams = view.beamView
        val ref = beams.ref(beam)
        val cmd: Command = when (action) {
            TapAction.REPAIR -> Command.RepairBeam(view.tick, ctx.playerId, ref)
            TapAction.DELETE -> Command.DeleteBeam(view.tick, ctx.playerId, ref)
            TapAction.DOOR -> Command.ToggleDoor(view.tick, ctx.playerId, ref)
        }
        val reason = ctx.validator.validate(view, cmd)
        picker.beamPoint(view, beam, 0.5f, tmp)
        val burning = beams.fire(beam) > 0f
        var refundMetal = 0f
        var refundEnergy = 0f
        var cost = 0f
        if (action == TapAction.DELETE && !burning) {
            RuleCost.deleteBeamRefund(view, beam, refund)
            refundMetal = refund[0]
            refundEnergy = refund[1]
        }
        if (action == TapAction.REPAIR) cost = RuleCost.repairCostToFull(view, beam)
        return TargetHint(
            action = action, isDevice = false, ref = ref, valid = reason == null, reason = reason, x = tmp[0], y = tmp[1],
            refundMetal = refundMetal, refundEnergy = refundEnergy, costMetal = cost, burning = burning,
            doorOpen = (beams.flags(beam) and BeamFlags.DOOR_OPEN) != 0,
        )
    }

    /** Löschen-Hinweis für Geräte-Slot [device]. */
    fun deviceHint(device: Int, ctx: ToolContext): TargetHint {
        val view = ctx.view
        val devices = view.deviceView
        val ref = devices.ref(device)
        val reason = ctx.validator.validate(view, Command.DeleteDevice(view.tick, ctx.playerId, ref))
        picker.deviceCenter(view, device, tmp)
        return TargetHint(
            action = TapAction.DELETE, isDevice = true, ref = ref, valid = reason == null, reason = reason, x = tmp[0], y = tmp[1],
            refundMetal = RuleCost.deviceRefundMetal(view, device), refundEnergy = RuleCost.deviceRefundEnergy(view, device),
        )
    }

    /** Das Command zu einem Hinweis (nur bei [TargetHint.valid] sinnvoll). */
    fun commandFor(hint: TargetHint, ctx: ToolContext): Command {
        val tick = ctx.view.tick
        val p = ctx.playerId
        return when (hint.action) {
            TapAction.REPAIR -> Command.RepairBeam(tick, p, hint.ref)
            TapAction.DELETE -> if (hint.isDevice) Command.DeleteDevice(tick, p, hint.ref) else Command.DeleteBeam(tick, p, hint.ref)
            TapAction.DOOR -> Command.ToggleDoor(tick, p, hint.ref)
        }
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

/** "Zurück"-Werkzeug (Button): `Command.Undo`, Vorschau für Aktiv/Inaktiv und Hervorhebung. */
class DefaultUndoTool {
    /** Grund, warum [press] kein Command ergab, sonst `null`. */
    var lastReject: RejectReason? = null
        private set

    /** Zustand des Zurück-Buttons (Validator + oberster erfüllbarer Journal-Eintrag). */
    fun preview(ctx: ToolContext): UndoPreview {
        val view = ctx.view
        val reason = ctx.validator.validate(view, Command.Undo(view.tick, ctx.playerId))
        val player = view.player(ctx.playerId)
        val k = UndoRules.liveTop(view, ctx.playerId)
        val e = if (k >= 0) player.undoAt(k) else null
        val refund = view.simConfig.undoRefund
        return UndoPreview(
            canUndo = reason == null, reason = reason, kind = e?.kind, ref = e?.ref ?: -1L,
            refundMetal = (e?.metal ?: 0f) * refund, refundEnergy = (e?.energy ?: 0f) * refund, entries = player.undoCount,
        )
    }

    /** `Undo`, wenn möglich, sonst `null` mit [lastReject]. */
    fun press(ctx: ToolContext): Command? {
        lastReject = null
        val view = ctx.view
        val cmd = Command.Undo(view.tick, ctx.playerId)
        val reason = ctx.validator.validate(view, cmd)
        if (reason != null) { lastReject = reason; return null }
        return cmd
    }
}
