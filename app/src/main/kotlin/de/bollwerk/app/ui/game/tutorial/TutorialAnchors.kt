package de.bollwerk.app.ui.game.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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

    /** Anker, den der Coach-Mark gerade hervorhebt: ein Eintrag in einer scrollenden Leiste scrollt sich dann selbst ins Bild. */
    var focus: String? by mutableStateOf(null)

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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tutorialAnchor(id: String): Modifier {
    val anchors = LocalTutorialAnchors.current ?: return this
    DisposableEffect(anchors, id) { onDispose { anchors.remove(id) } }
    val requester = remember { BringIntoViewRequester() }
    val focused = anchors.focus == id
    LaunchedEffect(focused) { if (focused) requester.bringIntoView() }
    return this.bringIntoViewRequester(requester).onGloballyPositioned { anchors.report(id, it.boundsInRoot()) }
}
