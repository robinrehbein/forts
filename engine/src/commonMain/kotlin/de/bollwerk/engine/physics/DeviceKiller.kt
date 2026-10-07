package de.bollwerk.engine.physics

import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.FxEvent

/**
 * Zerstört Geräte (Prototyp `killDevice`): TP auf 0, Slot freigeben, `FxEvent.DeviceDestroyed` (außer bei
 * `silent`), Massen neu ([GameState.topologyDirty]). Der Reaktor-Verlust selbst (Ergebnis,
 * `FxEvent.ReactorDestroyed`) ist Sache des RESULT-Systems: Es erkennt ihn daran, dass
 * `PlayerState.reactorDeviceId` nicht mehr lebt (auch bei stillem Entfernen).
 */
object DeviceKiller {
    /**
     * @param silent ohne `FxEvent.DeviceDestroyed` (Prototyp `killDevice(d, silent)`: Abriss durch den Spieler,
     *   Trümmer-Zerfall/Kill-Grenzen).
     * @return `true`, wenn [deviceId] lebte und jetzt zerstört ist.
     */
    fun kill(state: GameState, ctx: StepContext, deviceId: Int, silent: Boolean = false): Boolean {
        val d = state.devices
        if (!d.isAlive(deviceId)) return false
        var x = Float.NaN
        var y = Float.NaN
        val j = d.beamId[deviceId]
        val beams = state.beams
        if (j >= 0 && j < beams.size) {
            val n = state.nodes
            val a = beams.a[j]; val b = beams.b[j]
            val t = d.tOf[deviceId]
            x = n.x[a] + (n.x[b] - n.x[a]) * t
            y = n.y[a] + (n.y[b] - n.y[a]) * t
        }
        d.hpOf[deviceId] = 0f
        if (!silent) ctx.fx.add(FxEvent.DeviceDestroyed(state.tick, x, y, d.uidOf[deviceId], d.typeOf[deviceId]))
        d.release(deviceId)
        state.topologyDirty = true
        return true
    }
}
