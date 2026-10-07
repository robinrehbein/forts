package de.bollwerk.app.ui.game.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Namen der Elemente, die der Coach-Mark hervorheben kann. Toolbar-Einträge heißen `tool:<Content-ID>`. */
object TutorialAnchorIds {
    const val FIRE = "fire"
    const val AIM_MODE = "mode:aim"
    const val BUILD_MODE = "mode:build"

    fun tool(contentId: String) = "tool:$contentId"
}

/**
 * Bildschirmrechtecke (Wurzelkoordinaten) der HUD-Elemente, die das Tutorial hervorhebt. Die Elemente melden sich über
 * [tutorialAnchor], solange sie im Bild sind; der Coach-Mark liest sie beim Zeichnen (Zustandsleser: Änderungen zeichnen neu).
 */
@Stable
class TutorialAnchors {
    private val rects = mutableStateMapOf<String, Rect>()

    operator fun get(id: String): Rect? = rects[id]

    fun report(id: String, rect: Rect) {
        if (rects[id] != rect) rects[id] = rect
    }

    fun remove(id: String) {
        rects.remove(id)
    }
}

/** Nur im Tutorial-Gefecht gesetzt; sonst `null` und [tutorialAnchor] ist wirkungslos. */
val LocalTutorialAnchors = staticCompositionLocalOf<TutorialAnchors?> { null }

/** Meldet die Fläche dieses Elements unter [id] an den Coach-Mark (ohne Tutorial keine Wirkung, keine Kosten). */
@Composable
fun Modifier.tutorialAnchor(id: String): Modifier {
    val anchors = LocalTutorialAnchors.current ?: return this
    DisposableEffect(anchors, id) { onDispose { anchors.remove(id) } }
    return this.onGloballyPositioned { anchors.report(id, it.boundsInRoot()) }
}
