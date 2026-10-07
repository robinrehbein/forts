package de.bollwerk.app.tutorial

import de.bollwerk.app.game.HudUiState
import de.bollwerk.engine.view.HudWeapon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Was die Oberfläche vom Tutorial zeigt: der Zustand und die Orte in der Welt, auf die der Coach-Mark zeigt. */
data class TutorialUiModel(val state: TutorialState, val targets: TutorialTargets)

/** Wirkungen des Tutorials auf die Partie und die Ablage (vom [TutorialCoach] aufgerufen, UI-Thread). */
interface TutorialEffects {
    /** Waffe (Ref aus dem HUD) im Zielmodus wählen. */
    fun selectWeapon(ref: Long)

    /** Die KI darf handeln. */
    fun openGates()

    /** Das Tutorial ist zu Ende: [completed] = alle Schritte gespielt, sonst übersprungen. */
    fun finished(completed: Boolean)
}

/**
 * UI-Thread-Hälfte des Tutorials: verbindet den [TutorialMachine] mit HUD-Ständen und Sim-Signalen und löst die Wirkungen
 * aus ([TutorialEffects]): im Zielmodus den Mörser vorwählen, am Ende die KI freigeben und den Abschluss melden.
 */
class TutorialCoach(
    private val ids: TutorialIds,
    val targets: TutorialTargets,
    private val effects: TutorialEffects,
    private val machine: TutorialMachine = TutorialMachine(ids),
) {
    private val _model = MutableStateFlow(TutorialUiModel(machine.state, targets))
    val model: StateFlow<TutorialUiModel> = _model.asStateFlow()

    val state: TutorialState get() = machine.state

    /** HUD-Stand (10 Hz) samt eigener Waffen. */
    fun onHud(hud: HudUiState, weapons: List<HudWeapon>) {
        advance { machine.onHud(TutorialHud.of(hud)) }
        // Schritt 3 gilt dem Mörser: steht im Zielmodus eine andere Waffe, wird er vorgewählt (die Waffenkarte schaltet sonst weiter)
        if (machine.wantsMortar()) {
            weapons.firstOrNull { it.typeId == ids.mortarDevice }?.let { effects.selectWeapon(it.deviceRef) }
        }
    }

    fun onSignal(signal: TutorialSignal) {
        advance {
            when (signal) {
                is TutorialSignal.Outcome -> machine.onOutcome(signal.outcome)
                is TutorialSignal.Fx -> machine.onFx(signal.event)
            }
        }
    }

    /** „Überspringen". */
    fun skip() = advance { machine.skip() }

    /** Abschlusskarte bestätigt. */
    fun close() = advance { machine.close() }

    private inline fun advance(step: () -> TutorialState) {
        val before = machine.state
        val after = step()
        if (after == before) return
        _model.value = TutorialUiModel(after, targets)
        if (before.running && !after.running) {
            effects.openGates()
            effects.finished(completed = after.status == TutorialStatus.COMPLETED)
        }
    }
}
