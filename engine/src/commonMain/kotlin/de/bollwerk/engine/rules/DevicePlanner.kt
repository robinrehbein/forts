package de.bollwerk.engine.rules

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.BeamFlags
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.MountRule
import de.bollwerk.engine.view.GameView

/** Aufgelöstes `Command.PlaceDevice`: Balken-Slot, geklemmter Parameter, Seite und Montage-Geometrie ([DeviceGeometry]). */
class DevicePlan {
    var beam: Int = -1
    var t: Float = 0f
    var sideNegative: Boolean = false
    val geo: FloatArray = FloatArray(DeviceGeometry.SIZE)
    /** Arbeitspuffer für die Platzprüfung (damit die Vorschau je Frame nichts alloziert, wenn der Plan wiederverwendet wird). */
    val scratch: FloatArray = FloatArray(DeviceGeometry.SIZE)
}

/**
 * Regeln für `PlaceDevice` (Prototyp `evalDevice`). Reihenfolge (erste Verletzung gewinnt): Besitz/Balkenart → Tech →
 * Einzigartigkeit → Montage oben → Baubereich → Gelände → Platz → Erz → Metall → Energie.
 */
object DevicePlanner {
    fun plan(view: GameView, cmd: Command.PlaceDevice, out: DevicePlan = DevicePlan()): RejectReason? {
        val owner = cmd.playerId
        val tables = view.tables
        if (cmd.deviceTypeId !in tables.devices.indices) return RejectReason.UNKNOWN_CONTENT
        val props = tables.devices[cmd.deviceTypeId]
        val beams = view.beamView
        val beam = beams.resolve(cmd.beamRef)
        if (beam < 0) return RejectReason.STALE_TARGET
        if (beams.owner(beam) != owner) return RejectReason.NOT_OWNER
        if ((beams.flags(beam) and BeamFlags.DEBRIS) != 0) return RejectReason.INVALID_TARGET
        val mat = tables.materials[beams.material(beam)]
        if (mat.isDoor || mat.tensionOnly) return RejectReason.INVALID_TARGET

        RuleChecks.techBlock(view, owner, props.requiredTech)?.let { return it }
        // Reaktoren gibt es nur in der Startfestung (kostenlos, einmalig); nie per Command, auch nicht nach Verlust
        if (props.role == DeviceRole.REACTOR) return RejectReason.UNIQUE_LIMIT
        if (props.unique && ownsType(view, owner, cmd.deviceTypeId)) return RejectReason.UNIQUE_LIMIT

        val t = FloatMath.clamp(cmd.t, RuleConst.DEVICE_T_MIN, RuleConst.DEVICE_T_MAX)
        out.beam = beam; out.t = t; out.sideNegative = cmd.sideNegative
        RuleChecks.mountOn(view, beam, t, cmd.sideNegative, props, 0f, out.geo)
        val mx = out.geo[DeviceGeometry.X]
        val my = out.geo[DeviceGeometry.Y]
        if (props.mountRule == MountRule.TOP_ONLY && out.geo[DeviceGeometry.NY] > RuleConst.TOP_MOUNT_NY) {
            return RejectReason.TOP_MOUNT_ONLY
        }
        if (!view.map.inBuildZone(owner, mx)) return RejectReason.OUT_OF_BUILD_ZONE
        if (out.geo[DeviceGeometry.CY] > view.terrain.heightAt(out.geo[DeviceGeometry.CX]) + RuleConst.TERRAIN_EPS) {
            return RejectReason.BLOCKED_BY_TERRAIN
        }
        if (occupied(view, props, mx, my, out.scratch)) return RejectReason.OCCUPIED
        if (props.requiresOre && !oreBelow(view, owner, props, mx, my)) return RejectReason.NEEDS_ORE
        if (props.costMetal > RuleChecks.availableMetal(view, owner)) return RejectReason.NOT_ENOUGH_METAL
        if (props.costEnergy > RuleChecks.availableEnergy(view, owner)) return RejectReason.NOT_ENOUGH_ENERGY
        return null
    }

    /** Besitzt [owner] schon ein lebendes Gerät des Typs [type] (Reaktor: Höchstens eines)? */
    fun ownsType(view: GameView, owner: Int, type: Int): Boolean {
        val d = view.deviceView
        for (i in 0 until d.size) {
            if (d.isAlive(i) && d.owner(i) == owner && d.type(i) == type) return true
        }
        return false
    }

    /** Liegt ein anderes Gerät näher als der Mindestabstand am Montagepunkt ([mx], [my])? */
    fun occupied(view: GameView, props: DeviceProps, mx: Float, my: Float, tmp: FloatArray): Boolean {
        val d = view.deviceView
        for (i in 0 until d.size) {
            if (!d.isAlive(i)) continue
            DeviceGeometry.mount(view, i, tmp)
            val other = view.tables.devices[d.type(i)]
            val spacing = FloatMath.max(props.minSpacing, other.minSpacing)
            if (RuleChecks.dist(tmp[DeviceGeometry.X], tmp[DeviceGeometry.Y], mx, my) < spacing) return true
        }
        return false
    }

    /** Eigenes Erz im Umkreis [DeviceProps.oreRadius] (horizontal) knapp unter dem Montagepunkt (Prototyp `evalDevice`). */
    fun oreBelow(view: GameView, owner: Int, props: DeviceProps, mx: Float, my: Float): Boolean {
        val ores = view.map.ores
        for (i in ores.indices) {
            val o = ores[i]
            if (o.owner != owner) continue
            val dx = o.x - mx
            if (dx > props.oreRadius || dx < -props.oreRadius) continue
            val dy = view.terrain.heightAt(o.x) - my
            if (dy < RuleConst.ORE_MAX_DY && dy > -RuleConst.ORE_MAX_DY) return true
        }
        return false
    }
}
