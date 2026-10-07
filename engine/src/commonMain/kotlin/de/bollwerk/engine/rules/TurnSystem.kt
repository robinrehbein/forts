package de.bollwerk.engine.rules

import de.bollwerk.engine.sim.GameState
import de.bollwerk.engine.sim.SimSystem
import de.bollwerk.engine.sim.StepContext
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.sim.GameResult

/**
 * Zugwechsel im Hotseat-Modus. Phasen eines Zugs:
 * 1. [TurnPhase.PLAY]: Der aktive Spieler plant/baut/zielt (`TurnState.lengthTicks` = 45 s in der App, 0 = unbegrenzt).
 *    Es zählt nur seine Commands (`NOT_YOUR_TURN` für alle anderen, Aufgeben ausgenommen). Die Simulation läuft weiter.
 * 2. [TurnPhase.RESOLVE]: Zug beendet (Command `EndTurn` oder Zeit abgelaufen); `TurnConfig.resolveTicks` Nachlauf (Projektile
 *    fliegen, Einsturz). Niemand darf Commands geben; aktiver Spieler bleibt der bisherige.
 * 3. [TurnPhase.HANDOVER]: aktiver Spieler ist jetzt der **nächste**; `TurnConfig.handoverTicks` Übergabe-Bildschirm, `turnNumber`
 *    wurde erhöht. Danach beginnt dessen PLAY-Phase.
 *
 * **Gleiche Zugzahl:** Im Zugmodus wird ein Sieg erst am Ende der Runde festgestellt (nach der RESOLVE-Phase des letzten
 * Spielers in der Reihenfolge), damit beide Spieler gleich viele Züge hatten ([RulesResult.evaluate]); Aufgeben gilt sofort.
 */
object TurnRules {
    /** PLAY → RESOLVE (EndTurn oder Zeitablauf). */
    fun beginResolve(state: GameState) {
        val t = state.turnState
        t.phase = TurnPhase.RESOLVE
        t.ticksLeft = state.config.turn.resolveTicks
    }

    /**
     * Nächster Spieler nach [from]: innerhalb einer Runde der nächste Index (auch ein Spieler, dessen Reaktor in dieser
     * Runde fiel, hat noch seinen letzten Zug), nach dem Wechsel zur nächsten Runde der erste Spieler, der noch im Spiel ist.
     */
    fun nextPlayer(state: GameState, from: Int): Int {
        val n = state.players.size
        if (from + 1 < n) return from + 1
        for (c in 0 until n) if (state.players[c].alive) return c
        return 0
    }
}

/** [de.bollwerk.engine.sim.SystemSlot.TURN]: Zeit und Phasen des Zugmodus, siehe [TurnRules]. */
class TurnSystem : SimSystem {
    override fun step(state: GameState, ctx: StepContext) {
        val t = state.turnState
        if (t.mode != TurnMode.TURNS || state.result != GameResult.Ongoing) return
        when (t.phase) {
            TurnPhase.PLAY -> {
                if (t.lengthTicks <= 0) return
                if (t.ticksLeft > 0) t.ticksLeft--
                if (t.ticksLeft <= 0) TurnRules.beginResolve(state)
            }
            TurnPhase.RESOLVE -> {
                if (t.ticksLeft > 0) t.ticksLeft--
                if (t.ticksLeft > 0) return
                // Ende der Runde (letzter Spieler hat gespielt): erst jetzt wird ein Sieg/Unentschieden festgestellt
                if (t.activePlayer >= state.players.size - 1) {
                    RulesResult.evaluate(state)
                    if (state.result != GameResult.Ongoing) return
                }
                t.activePlayer = TurnRules.nextPlayer(state, t.activePlayer)
                t.turnNumber++
                val handover = state.config.turn.handoverTicks
                if (handover > 0) {
                    t.phase = TurnPhase.HANDOVER
                    t.ticksLeft = handover
                } else {
                    t.phase = TurnPhase.PLAY
                    t.ticksLeft = t.lengthTicks
                }
            }
            TurnPhase.HANDOVER -> {
                if (t.ticksLeft > 0) t.ticksLeft--
                if (t.ticksLeft > 0) return
                t.phase = TurnPhase.PLAY
                t.ticksLeft = t.lengthTicks
            }
        }
    }
}
