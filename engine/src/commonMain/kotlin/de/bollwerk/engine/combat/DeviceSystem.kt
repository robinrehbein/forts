package de.bollwerk.engine.combat

import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext

/**
 * [de.bollwerk.engine.sim.SystemSlot.DEVICES] (Prototyp `devicePos` + Nachladen in `weaponTick`), je Gerät in ID-Reihenfolge:
 * - DERIVED-Lage (`x`, `y`, `nx`, `ny`) über [DeviceGeometry] (gemeinsam mit Renderer, Werkzeugen und KI), auch für
 *   im laufenden Tick platzierte Geräte (Treffertests in WEAPONS/PROJECTILES brauchen sie).
 * - Nachladen: `reloadTicks` zählt bis 0 herunter.
 * - [DeviceFlags.READY] für Waffen: fertig gebaut, nicht deaktiviert ([DeviceFlags.DISABLED]/[DeviceFlags.BUILDING]),
 *   nachgeladen, keine laufende Salve/kein laufender Strahl ([WeaponSystem.canFire]).
 * Geräte auf toten Balken überspringt das System (das Topologie-System räumt sie ab).
 */
class DeviceSystem(private val world: CombatWorld) : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        val d = state.devices
        val beams = state.beams
        val props = state.tables.devices
        val geo = world.geo
        val n = d.size
        for (i in 0 until n) {
            if (!d.isAlive(i) || !beams.isAlive(d.beamId[i])) continue
            DeviceGeometry.mount(state, i, geo)
            d.x[i] = geo[DeviceGeometry.X]; d.y[i] = geo[DeviceGeometry.Y]
            d.nx[i] = geo[DeviceGeometry.NX]; d.ny[i] = geo[DeviceGeometry.NY]
            if (d.reloadTicksOf[i] > 0) d.reloadTicksOf[i]--
            if (props[d.typeOf[i]].weapon < 0) continue
            val ready = WeaponSystem.canFire(d, i)
            d.flags[i] = if (ready) d.flags[i] or DeviceFlags.READY else d.flags[i] and DeviceFlags.READY.inv()
        }
    }
}
