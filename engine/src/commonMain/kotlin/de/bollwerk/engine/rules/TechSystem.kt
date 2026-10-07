package de.bollwerk.engine.rules

import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.view.FxEvent

/**
 * [de.bollwerk.engine.sim.SystemSlot.TECH]: zählt die Bauzeit aller Geräte im Bau herunter (Tech-Gebäude 30–90 s, Minen
 * und Waffen wenige Sekunden, aus `DeviceProps.buildTicks`) und berechnet die Tech-Sets **jeden Tick neu**: Tech T gehört
 * einem Spieler genau dann, wenn er ein lebendes, fertig gebautes Gerät mit `grantsTech == T` hat. Wird das Gebäude
 * zerstört oder abgerissen, ist die Tech wieder gesperrt. Änderungen erzeugen `FxEvent.TechChanged`.
 */
class TechSystem : SimSystem {
    private var granted = BooleanArray(0)

    override fun step(state: GameState, ctx: StepContext) {
        val devices = state.devices
        val n = devices.size
        val props = state.tables.devices
        val techCount = state.tables.techs.size
        val players = state.players.size
        if (granted.size < techCount * players) granted = BooleanArray(techCount * players)
        for (i in 0 until techCount * players) granted[i] = false

        for (i in 0 until n) {
            if (!devices.isAlive(i)) continue
            if (devices.buildTicks[i] > 0) {
                // im Alloc-Tick (COMMANDS) noch nicht herunterzählen: N Ticks Bauzeit = N volle Folge-Ticks
                if (!devices.isNew(i)) devices.buildTicks[i]--
                if (devices.buildTicks[i] <= 0) devices.flags[i] = devices.flags[i] and DeviceFlags.BUILDING.inv()
            }
            if (devices.buildTicks[i] > 0) continue
            // wie in der Wirtschaft: ein Gebäude auf Trümmern (oder einem toten Balken) liefert keine Tech mehr
            if (!Economy.onLiveBeam(state, i)) continue
            val tech = props[devices.typeOf[i]].grantsTech
            val owner = devices.ownerOf[i]
            if (tech in 0 until techCount && owner in 0 until players) granted[owner * techCount + tech] = true
        }

        for (p in state.players) {
            for (t in 0 until techCount) {
                val has = t in p.techUnlocked
                val want = granted[p.id * techCount + t]
                if (has == want) continue
                if (want) p.techUnlocked.add(t) else p.techUnlocked.remove(t)
                ctx.fx.add(FxEvent.TechChanged(state.tick, Float.NaN, Float.NaN, p.id, t, want))
            }
        }
    }
}
