package de.bollwerk.app.ui.game.hud

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import de.bollwerk.engine.tools.TapAction
import de.bollwerk.engine.tools.ToolSelection

/**
 * Aktionen der HUD-Schicht. Der `GameViewModel` implementiert sie (Weitergabe an den `GameController`), Vorschauen und
 * Snapshot-Tests nehmen [NONE].
 */
@Stable
interface HudActions {
    fun pause() {}
    fun selectTool(selection: ToolSelection) {}
    fun enterAimMode() {}
    fun enterBuildMode() {}
    fun undo() {}
    fun fire() {}
    fun cycleWeapon() {}
    fun setPower(power: Float) {}
    fun setDoorsOpen(open: Boolean) {}
    fun openTechTree() {}
    fun closeTechTree() {}
    fun buildTech(deviceIndex: Int) {}
    fun endTurn() {}
    fun chooseContext(action: TapAction) {}
    fun dismissContext() {}

    companion object {
        val NONE: HudActions = object : HudActions {}
    }
}

/** Tipp-Ziel ohne Ripple (HUD-Flächen zeichnen ihren Zustand selbst), mit Rolle und Beschreibung für Bedienungshilfen. */
fun Modifier.hudClickable(onClick: () -> Unit, description: String? = null, enabled: Boolean = true): Modifier =
    this
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .clickable(interactionSource = null, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)

/**
 * Macht eine HUD-Fläche „undurchlässig" für Berührungen: Ohne Zeiger-Eingabe überspringt das Compose-Hit-Testing einen
 * Knoten, und die Berührung landete bei der Spielfläche darunter (`AndroidView` → `InputController` → aktives Werkzeug:
 * Bauen, Schwenken, Zielen/Feuern). Mit diesem Modifier ist die Fläche ein Treffer und verdeckt ihre Geschwister darunter;
 * nicht verbrauchte Ereignisse werden nach den Kindern (Buttons, Regler) verbraucht. Keine Semantik (kein Button für
 * Bedienungshilfen).
 */
fun Modifier.blockTouches(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            for (c in event.changes) if (!c.isConsumed) c.consume()
        }
    }
}
