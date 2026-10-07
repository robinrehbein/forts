package de.bollwerk.app.tutorial

import de.bollwerk.app.game.HudUiState
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.loop.CommandOutcome
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.view.FxEvent

/** Die drei Schritte des geführten Tutorial-Gefechts (Stil-Bibel „Onboarding"). */
enum class TutorialStep {
    /** Einen Holzbalken setzen. */
    PLACE_BEAM,

    /** Eine Mine auf dem Erzvorkommen bauen. */
    BUILD_MINE,

    /** Den Mörser auf den Gegner abfeuern. */
    FIRE_MORTAR,
}

/**
 * Was der Coach-Mark gerade zeigt (ein Schritt hat zwei Teile: erst das Werkzeug wählen, dann die Aktion ausführen).
 * [BACK_TO_BUILD] gehört zu den Bau-Schritten, wenn der Spieler im Zielmodus steht.
 */
enum class TutorialHint {
    /** Schritt 1a: Toolbar-Eintrag HOLZ hervorheben. */
    PICK_WOOD,

    /** Schritt 1b: pulsierendes Knotenpaar auf der eigenen Festung. */
    DRAW_BEAM,

    /** Schritt 2a: Toolbar-Eintrag MINE hervorheben. */
    PICK_MINE,

    /** Schritt 2b: Erz-Markierung. */
    PLACE_MINE,

    /** Schritt 3a: Modusschalter ZIELEN hervorheben. */
    ENTER_AIM,

    /** Schritt 3b: animierte Hand zeigt die Zielgeste („Zugrichtung = Schussrichtung"), FEUER hervorheben. */
    AIM_AND_FIRE,

    /** Bau-Schritt, aber der Spieler steht im Zielmodus: Modusschalter BAUEN hervorheben. */
    BACK_TO_BUILD,
}

/** Verlauf des Tutorials. [CLOSED] = die Abschlusskarte wurde bestätigt. */
enum class TutorialStatus { RUNNING, COMPLETED, SKIPPED, CLOSED }

/**
 * Zustand des Tutorials. [done] sind die erreichten Meilensteine (auch wenn der Spieler vorgreift, z. B. die Mine vor dem
 * Balken baut); [step] ist der erste noch offene Schritt.
 */
data class TutorialState(
    val status: TutorialStatus = TutorialStatus.RUNNING,
    val step: TutorialStep = TutorialStep.PLACE_BEAM,
    val hint: TutorialHint = TutorialHint.PICK_WOOD,
    val done: Set<TutorialStep> = emptySet(),
) {
    val running: Boolean get() = status == TutorialStatus.RUNNING

    /** Anzahl Schritte insgesamt. */
    val stepCount: Int get() = TutorialStep.entries.size

    /** 1-basierte Nummer des aktuellen Schritts für die Anzeige „SCHRITT 2 / 3". */
    val stepNumber: Int get() = step.ordinal + 1
}

/** Der für das Tutorial relevante Ausschnitt des HUD (aus [HudUiState], 10 Hz). */
data class TutorialHud(
    /** Content-ID des gewählten Werkzeugs (`wood`, `mine`, …) oder `null`. */
    val selectedId: String? = null,
    val inAim: Boolean = false,
    /** Content-ID des Geräts der gewählten Waffe im Zielmodus (`mortar`, …) oder `null`. */
    val aimWeaponId: String? = null,
) {
    companion object {
        val NONE = TutorialHud()

        fun of(hud: HudUiState): TutorialHud = TutorialHud(
            selectedId = hud.selected?.id,
            inAim = hud.mode == ToolMode.AIM,
            aimWeaponId = hud.aim.weaponDeviceId,
        )
    }
}

/**
 * Content-Zuordnung des Tutorials: wer der Spieler ist, welcher Geräte-Index die Mine ist und welcher Waffen-Index
 * (`FxEvent.Fired.weaponId`) der Mörser. IDs der Toolbar-Einträge ([woodId] …) sind Content-IDs.
 */
data class TutorialIds(
    val humanPlayer: Int,
    val mineDevice: Int,
    val mortarWeapon: Int,
    val mortarDevice: Int,
    val woodId: String = "wood",
    val mineId: String = "mine",
    val mortarId: String = "mortar",
)

/**
 * Zustandsautomat des Tutorials. Reine Logik ohne Android, Thread und Uhr: nur der UI-Thread ruft ihn auf, gespeist aus
 * drei Quellen. HUD ([onHud], Werkzeugwahl und Modus), Command-Ergebnisse ([onOutcome]: angenommenes `PlaceBeam`,
 * `PlaceDevice` der Mine) und Fx ([onFx]: `Fired` des Mörsers). Ein Schritt endet nur durch sein Ereignis; die
 * Werkzeugwahl steuert lediglich den [TutorialHint] innerhalb des Schritts (zurück zum Werkzeug, wenn der Spieler umwählt).
 */
class TutorialMachine(private val ids: TutorialIds, initial: TutorialState = TutorialState()) {
    var state: TutorialState = initial
        private set
    private var hud: TutorialHud = TutorialHud.NONE

    /** Muss der Mörser im Zielmodus nachgewählt werden (Schritt 3, falls eine andere Waffe vorgewählt ist)? */
    fun wantsMortar(): Boolean =
        state.running && state.step == TutorialStep.FIRE_MORTAR && hud.inAim && hud.aimWeaponId != ids.mortarId

    fun onHud(newHud: TutorialHud): TutorialState {
        hud = newHud
        return refresh()
    }

    fun onOutcome(outcome: CommandOutcome): TutorialState {
        if (!state.running || !outcome.accepted || outcome.playerId != ids.humanPlayer) return state
        when (val c = outcome.command) {
            is Command.PlaceBeam -> reach(TutorialStep.PLACE_BEAM)
            is Command.PlaceDevice -> if (c.deviceTypeId == ids.mineDevice) reach(TutorialStep.BUILD_MINE)
            else -> Unit
        }
        return state
    }

    fun onFx(event: FxEvent): TutorialState {
        if (state.running && event is FxEvent.Fired && event.weaponId == ids.mortarWeapon) reach(TutorialStep.FIRE_MORTAR)
        return state
    }

    /** „Überspringen". */
    fun skip(): TutorialState {
        if (state.running) state = state.copy(status = TutorialStatus.SKIPPED)
        return state
    }

    /** Abschlusskarte bestätigt. */
    fun close(): TutorialState {
        if (state.status == TutorialStatus.COMPLETED) state = state.copy(status = TutorialStatus.CLOSED)
        return state
    }

    private fun reach(step: TutorialStep) {
        val done = state.done + step
        val next = TutorialStep.entries.firstOrNull { it !in done }
        state = if (next == null) {
            state.copy(status = TutorialStatus.COMPLETED, done = done)
        } else {
            state.copy(step = next, done = done, hint = hintFor(next, hud))
        }
    }

    private fun refresh(): TutorialState {
        if (state.running) {
            val hint = hintFor(state.step, hud)
            if (hint != state.hint) state = state.copy(hint = hint)
        }
        return state
    }

    private fun hintFor(step: TutorialStep, hud: TutorialHud): TutorialHint = when (step) {
        TutorialStep.PLACE_BEAM -> when {
            hud.inAim -> TutorialHint.BACK_TO_BUILD
            hud.selectedId == ids.woodId -> TutorialHint.DRAW_BEAM
            else -> TutorialHint.PICK_WOOD
        }
        TutorialStep.BUILD_MINE -> when {
            hud.inAim -> TutorialHint.BACK_TO_BUILD
            hud.selectedId == ids.mineId -> TutorialHint.PLACE_MINE
            else -> TutorialHint.PICK_MINE
        }
        TutorialStep.FIRE_MORTAR -> if (hud.inAim) TutorialHint.AIM_AND_FIRE else TutorialHint.ENTER_AIM
    }
}
