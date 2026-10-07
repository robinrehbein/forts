package de.bollwerk.engine.rules

import de.bollwerk.engine.sim.DeviceRole
import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.WinReason
import de.bollwerk.engine.view.FxEvent

/** Ergebnis-Auswertung (Stil-Bibel: Sieg = gegnerischen Reaktor zerstören). */
object RulesResult {
    /** Lebt der Reaktor von Spieler [p] noch (richtiger Besitzer und Rolle, falls der Slot wiederverwendet wurde)? */
    fun reactorAlive(state: GameState, p: Int): Boolean {
        val id = state.players[p].reactorDeviceId
        if (id < 0) return true
        val d = state.devices
        return d.isAlive(id) && d.ownerOf[id] == p && state.tables.devices[d.typeOf[id]].role == DeviceRole.REACTOR
    }

    /**
     * Setzt [GameState.result] aus den noch lebenden Spielern: keiner → [GameResult.Draw] (beide Reaktoren in
     * derselben Runde/demselben Tick zerstört), genau einer bei mehr als einem Spieler → [GameResult.Winner]
     * ([WinReason.REACTOR_DESTROYED]), sonst weiter.
     */
    fun evaluate(state: GameState) {
        if (state.result != GameResult.Ongoing) return
        var left = -1
        var count = 0
        for (p in state.players) if (p.alive) { left = p.id; count++ }
        if (count == 0) state.result = GameResult.Draw
        else if (count == 1 && state.players.size > 1) state.result = GameResult.Winner(left, WinReason.REACTOR_DESTROYED)
    }
}

/**
 * [de.bollwerk.engine.sim.SystemSlot.RESULT]: erkennt zerstörte Reaktoren (auch stilles Entfernen) einmalig
 * (`PlayerState.alive = false`, `FxEvent.ReactorDestroyed`). Im Echtzeitmodus wird sofort ausgewertet; im Zugmodus erst
 * am Rundenende ([TurnSystem]), damit beide Spieler gleich viele Züge haben.
 */
class ResultSystem : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        if (state.result != GameResult.Ongoing) return
        for (p in state.players) {
            if (!p.alive || RulesResult.reactorAlive(state, p.id)) continue
            p.alive = false
            // Das Kampf-System (WP4) meldet Reaktoren, die in seinen Slots sterben, samt Explosion selbst; hier nur der Rest
            // (Einsturz, stilles Entfernen), damit das Ereignis nicht doppelt erscheint.
            if (ctx.fx.any { it is FxEvent.ReactorDestroyed && it.playerId == p.id }) continue
            val r = p.reactorDeviceId
            ctx.fx.add(FxEvent.ReactorDestroyed(state.tick, state.devices.x[r], state.devices.y[r], p.id))
        }
        if (state.turnState.mode != TurnMode.TURNS) RulesResult.evaluate(state)
    }
}
