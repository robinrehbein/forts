package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.rules.RuleChecks
import de.bollwerk.engine.rules.RuleConst

/**
 * Geräte-Werkzeug (Prototyp `evalDevice`): Down/Move über einen eigenen Balken zeigt das [GhostDevice] (Montagepunkt, Normale,
 * grün/rot mit Grund aus dem echten Validator: gesperrte Tech, Platz belegt, "nur über Erz", "nur oben montierbar", zu wenig
 * Metall/Energie, Bauzone …), Up liefert bei gültigem Ghost `PlaceDevice`. Die Seite ([GhostDevice.sideNegative]) folgt der
 * Seite des Balkens, auf der der Finger liegt; `t` ist auf den Montagebereich (0,15..0,85) geklemmt wie in der Sim.
 *
 * Ohne Balken unter dem Finger gibt es einen roten Ghost am Finger mit `INVALID_TARGET` (Prototyp "Balken wählen").
 * [hover] zeigt den Ghost auch ohne gedrückten Finger (Maus).
 */
class DefaultDeviceTool : DeviceTool {
    override var state: DeviceToolState = DeviceToolState.Idle
        private set
    override var deviceType: Int = 0

    /** Finger liegt. */
    var pressed: Boolean = false
        private set
    var fingerX: Float = 0f
        private set
    var fingerY: Float = 0f
        private set

    /** Grund, warum das letzte Loslassen kein Command ergab, sonst `null`. */
    var lastReject: RejectReason? = null
        private set

    private val picker = Picker()
    private val geo = FloatArray(DeviceGeometry.SIZE)
    private var lastCommand: Command.PlaceDevice? = null

    override fun onDown(worldX: Float, worldY: Float, ctx: ToolContext) {
        lastReject = null
        pressed = true
        fingerX = worldX; fingerY = worldY
        state = DeviceToolState.Previewing(eval(worldX, worldY, ctx))
    }

    override fun onMove(worldX: Float, worldY: Float, ctx: ToolContext) {
        if (!pressed) return
        fingerX = worldX; fingerY = worldY
        state = DeviceToolState.Previewing(eval(worldX, worldY, ctx))
    }

    /** Maus-Hover: Ghost ohne gedrückten Finger. */
    fun hover(worldX: Float, worldY: Float, ctx: ToolContext) {
        if (pressed) return
        fingerX = worldX; fingerY = worldY
        state = DeviceToolState.Previewing(eval(worldX, worldY, ctx))
    }

    override fun onUp(worldX: Float, worldY: Float, ctx: ToolContext): Command? {
        lastReject = null
        if (!pressed) return null
        pressed = false
        fingerX = worldX; fingerY = worldY
        val ghost = eval(worldX, worldY, ctx)
        val cmd = lastCommand
        state = DeviceToolState.Idle
        lastCommand = null
        if (!ghost.valid || cmd == null) {
            lastReject = ghost.reason
            return null
        }
        return cmd
    }

    override fun cancel() {
        pressed = false
        state = DeviceToolState.Idle
        lastCommand = null
    }

    /** Ghost an ([x], [y]) auswerten; setzt [lastCommand]. */
    private fun eval(x: Float, y: Float, ctx: ToolContext): GhostDevice {
        val view = ctx.view
        val owner = ctx.playerId
        val tables = view.tables
        lastCommand = null
        val type = deviceType
        if (type !in tables.devices.indices) {
            return GhostDevice(x, y, 0f, -1f, type, false, RejectReason.UNKNOWN_CONTENT, -1L, 0f, false, 0f, 0f)
        }
        val props = tables.devices[type]
        val beam = picker.nearestBeam(
            view, owner, x, y, ctx.pickRadiusM * ToolConst.DEVICE_BEAM_FACTOR, BeamFilter.DEVICE_HOST, -1,
        )
        if (beam < 0) {
            return GhostDevice(x, y, 0f, -1f, type, false, RejectReason.INVALID_TARGET, -1L, 0f, false, props.costMetal, props.costEnergy)
        }
        val beams = view.beamView
        val nodes = view.nodeView
        val t = FloatMath.clamp(picker.hitT, RuleConst.DEVICE_T_MIN, RuleConst.DEVICE_T_MAX)
        // Seite: Normale n = (−dy, dx)/L von A nach B (wie DeviceGeometry); Finger auf der negativen Seite → sideNegative
        val a = beams.nodeA(beam)
        val b = beams.nodeB(beam)
        val dx = nodes.x(b) - nodes.x(a)
        val dy = nodes.y(b) - nodes.y(a)
        val qx = nodes.x(a) + dx * t
        val qy = nodes.y(a) + dy * t
        val side = (x - qx) * -dy + (y - qy) * dx < 0f
        val ref = beams.ref(beam)
        val cmd = Command.PlaceDevice(view.tick, owner, type, ref, t, side)
        lastCommand = cmd
        val reason = ctx.validator.validate(view, cmd)
        RuleChecks.mountOn(view, beam, t, side, props, 0f, geo)
        return GhostDevice(
            x = geo[DeviceGeometry.X], y = geo[DeviceGeometry.Y], nx = geo[DeviceGeometry.NX], ny = geo[DeviceGeometry.NY],
            typeId = type, valid = reason == null, reason = reason, beamRef = ref, t = t, sideNegative = side,
            costMetal = props.costMetal, costEnergy = props.costEnergy,
        )
    }
}
