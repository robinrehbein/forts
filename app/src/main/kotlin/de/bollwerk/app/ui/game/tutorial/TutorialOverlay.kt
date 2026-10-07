package de.bollwerk.app.ui.game.tutorial

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.tutorial.TutorialHint
import de.bollwerk.app.tutorial.TutorialStatus
import de.bollwerk.app.tutorial.TutorialStep
import de.bollwerk.app.tutorial.TutorialTargets
import de.bollwerk.app.tutorial.TutorialUiModel
import de.bollwerk.app.tutorial.WorldPoint
import de.bollwerk.app.ui.components.ButtonStyle
import de.bollwerk.app.ui.components.IndustrialButton
import de.bollwerk.app.ui.game.hud.HudColors
import de.bollwerk.app.ui.game.hud.HudPanel
import de.bollwerk.app.ui.game.hud.HudType
import de.bollwerk.app.ui.game.hud.WorldToScreen
import de.bollwerk.app.ui.game.hud.blockTouches
import de.bollwerk.app.ui.game.hud.hudClickable
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Winkel der gezeigten Zielgeste (Grad über der Waagerechten); entspricht dem Standardwinkel des Mörsers. */
private const val DRAG_ELEVATION_DEG = 52f

/**
 * Länge der gezeigten Zuggeste (dp). Kurz gehalten: Die Geste liegt in der freien Fläche zwischen den Festungen unter der
 * Coach-Karte; die Länge der echten Geste bestimmt die Kraft, hier zählt nur die Richtung.
 */
private const val DRAG_LENGTH_DP = 64f

/** Abdunkelung hinter dem Coach-Mark (Fläche außerhalb des Fokus). */
private const val DIM_ALPHA = 0.55f

/** Weniger Abdunkelung, wenn der Spieler die Spielwelt sehen muss (Ziehen, Erz, Knoten). */
private const val DIM_ALPHA_WORLD = 0.38f

/**
 * Coach-Mark-Ebene des Tutorial-Gefechts über der Spielfläche und dem HUD. Läuft der Schritt, dunkelt sie die Umgebung des
 * Fokus ab (ein ausgeschnittener Fokus mit pulsierendem Ring, **ohne** Eingaben zu schlucken: der Spieler bedient das echte
 * HUD und die Spielfläche darunter), zeigt eine Karte mit Schritt, Kurztext und „Überspringen"; ist alles geschafft, zeigt
 * sie die Abschlusskarte.
 *
 * @param anchors Rechtecke der Toolbar-Einträge (HUD meldet sie über [tutorialAnchor]).
 * @param worldToScreen Welt → Bildschirm der Kamera (Knoten, Erz).
 * @param phase Feste Phase 0..1 der Animation (Snapshot-Tests); `null` = läuft mit der Uhr.
 */
@Composable
fun TutorialOverlay(
    model: TutorialUiModel,
    anchors: TutorialAnchors,
    worldToScreen: WorldToScreen,
    onSkip: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    phase: Float? = null,
    /** Einstellung „Loslassen = Feuern": der Schusshinweis sagt dann „loslassen" statt „FEUER tippen". */
    releaseToFire: Boolean = false,
) {
    when (model.state.status) {
        TutorialStatus.RUNNING -> CoachMark(model, anchors, worldToScreen, onSkip, modifier, phase, releaseToFire)
        TutorialStatus.COMPLETED -> CompletionCard(onClose, modifier)
        TutorialStatus.SKIPPED, TutorialStatus.CLOSED -> Unit
    }
}

@Composable
private fun rememberPhase(fixed: Float?): State<Float> {
    if (fixed != null) return remember(fixed) { mutableFloatStateOf(fixed) }
    val transition = rememberInfiniteTransition(label = "coach")
    return transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "phase")
}

@Composable
private fun CoachMark(
    model: TutorialUiModel,
    anchors: TutorialAnchors,
    worldToScreen: WorldToScreen,
    onSkip: () -> Unit,
    modifier: Modifier,
    fixedPhase: Float?,
    releaseToFire: Boolean,
) {
    val state = model.state
    val phase = rememberPhase(fixedPhase)
    val measurer = rememberTextMeasurer()
    val dragLabel = stringResource(R.string.tutorial_drag_label).uppercase()
    val oreLabel = stringResource(R.string.tutorial_ore_label).uppercase()
    // Hervorgehobenes Toolbar-Element in den sichtbaren Bereich scrollen (schmale Geräte: Leiste scrollt)
    LaunchedEffect(state.hint) {
        anchors.focus = when (state.hint) {
            TutorialHint.PICK_WOOD -> TutorialAnchorIds.tool("wood")
            TutorialHint.PICK_MINE -> TutorialAnchorIds.tool("mine")
            else -> null
        }
    }
    Box(modifier.fillMaxSize()) {
        // Zeichenfläche: nimmt keine Eingaben an (kein pointerInput), Berührungen erreichen HUD und Spielfläche darunter
        Canvas(Modifier.fillMaxSize()) {
            drawCoach(state.hint, model.targets, anchors, worldToScreen, phase.value, measurer, dragLabel, oreLabel)
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)) {
            CoachCard(
                stepNumber = state.stepNumber, stepCount = state.stepCount, step = state.step, hint = state.hint,
                releaseToFire = releaseToFire, onSkip = onSkip,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp, start = 170.dp),
            )
        }
    }
}

/**
 * Text des Coach-Marks zu [hint]. Mit „Loslassen = Feuern" ([releaseToFire]) löst schon das Loslassen der Zielgeste den
 * Schuss aus; der Schusshinweis darf dann nicht „tippe FEUER" verlangen.
 */
@StringRes
internal fun tutorialHintRes(hint: TutorialHint, releaseToFire: Boolean): Int = when (hint) {
    TutorialHint.PICK_WOOD -> R.string.tutorial_pick_wood
    TutorialHint.DRAW_BEAM -> R.string.tutorial_draw_beam
    TutorialHint.PICK_MINE -> R.string.tutorial_pick_mine
    TutorialHint.PLACE_MINE -> R.string.tutorial_place_mine
    TutorialHint.ENTER_AIM -> R.string.tutorial_enter_aim
    TutorialHint.AIM_AND_FIRE -> if (releaseToFire) R.string.tutorial_aim_release else R.string.tutorial_aim_fire
    TutorialHint.BACK_TO_BUILD -> R.string.tutorial_back_to_build
}

/**
 * Karte mit Schritt-Anzeige und „Überspringen" in der Kopfzeile, Titel und Kurztext. Bewusst kompakt (~110 dp): Im Zielmodus
 * bleibt darunter Platz für die Zeige-Hand, und die Winkel-Karte des HUD bleibt frei.
 */
@Composable
private fun CoachCard(
    stepNumber: Int,
    stepCount: Int,
    step: TutorialStep,
    hint: TutorialHint,
    releaseToFire: Boolean,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(
        when (step) {
            TutorialStep.PLACE_BEAM -> R.string.tutorial_step1_title
            TutorialStep.BUILD_MINE -> R.string.tutorial_step2_title
            TutorialStep.FIRE_MORTAR -> R.string.tutorial_step3_title
        },
    )
    val body = stringResource(tutorialHintRes(hint, releaseToFire))
    HudPanel(modifier.widthIn(max = 270.dp), border = BollwerkColors.Hazard.copy(alpha = 0.7f)) {
        Column(
            Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tutorial_step, stepNumber, stepCount).uppercase(),
                    style = HudType.ChipLabel.copy(fontSize = 11.sp), color = BollwerkColors.Hazard,
                )
                Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 1..stepCount) {
                        Box(
                            Modifier.size(width = 12.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (i <= stepNumber) BollwerkColors.Hazard else HudColors.Label.copy(alpha = 0.35f)),
                        )
                    }
                }
                Box(Modifier.weight(1f))
                // Tippfläche 48 dp hoch (Kopfzeile), die Schrift bleibt klein
                Box(Modifier.heightIn(min = 48.dp).hudClickable(onSkip).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        stringResource(R.string.tutorial_skip).uppercase(),
                        style = HudType.ItemLabel.copy(fontSize = 11.sp, letterSpacing = 0.12.em), color = HudColors.Label,
                    )
                }
            }
            Text(
                title.uppercase(), Modifier.semantics { heading() },
                style = HudType.ItemLabel.copy(fontSize = 16.sp, letterSpacing = 0.12.em), color = BollwerkColors.Text,
            )
            Text(
                body, Modifier.padding(top = 2.dp, end = 8.dp),
                style = BollwerkType.Body.copy(fontSize = 14.sp, lineHeight = 17.sp), color = BollwerkColors.Text,
            )
        }
    }
}

/** Abschlusskarte in der Mitte; „Weiter" gibt die Partie frei (die KI greift nun an). */
@Composable
private fun CompletionCard(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.Center) {
        HudPanel(Modifier.widthIn(max = 360.dp), corner = 10.dp, border = BollwerkColors.Hazard.copy(alpha = 0.8f)) {
            Column(
                Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.tutorial_done_title).uppercase(), Modifier.semantics { heading() },
                    style = BollwerkType.Headline, color = BollwerkColors.Hazard, textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.tutorial_done_body), style = BollwerkType.Body.copy(fontSize = 15.sp),
                    color = BollwerkColors.Text, textAlign = TextAlign.Center,
                )
                IndustrialButton(
                    stringResource(R.string.tutorial_done_continue), onClose, Modifier.fillMaxWidth(), style = ButtonStyle.Primary,
                )
            }
        }
    }
}

// =================================================================================================================
// Zeichnen
// =================================================================================================================

/** Ein Fokusbereich: wird aus der Abdunkelung ausgeschnitten und mit einem pulsierenden Ring betont. */
private sealed interface Spot {
    data class Box(val rect: Rect, val corner: Float) : Spot
    data class Disc(val center: Offset, val radius: Float) : Spot
}

private fun DrawScope.drawCoach(
    hint: TutorialHint,
    targets: TutorialTargets,
    anchors: TutorialAnchors,
    worldToScreen: WorldToScreen,
    phase: Float,
    measurer: TextMeasurer,
    dragLabel: String,
    oreLabel: String,
) {
    val dp = density
    val spots = ArrayList<Spot>(2)
    fun anchorSpot(id: String) {
        val r = anchors[id] ?: return
        val pad = 4f * dp
        spots += Spot.Box(Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad), 9f * dp)
    }

    fun world(p: WorldPoint): Offset? = worldToScreen.toScreen(p.x, p.y)

    val nodeA = world(targets.beamA)
    val nodeB = world(targets.beamB)
    val mine = world(targets.mineSpot)
    val ore = world(targets.ore)
    var dim = DIM_ALPHA
    when (hint) {
        TutorialHint.PICK_WOOD -> anchorSpot(TutorialAnchorIds.tool("wood"))
        TutorialHint.PICK_MINE -> anchorSpot(TutorialAnchorIds.tool("mine"))
        TutorialHint.ENTER_AIM -> anchorSpot(TutorialAnchorIds.AIM_MODE)
        TutorialHint.BACK_TO_BUILD -> anchorSpot(TutorialAnchorIds.BUILD_MODE)
        TutorialHint.DRAW_BEAM -> {
            dim = DIM_ALPHA_WORLD
            if (nodeA != null) spots += Spot.Disc(nodeA, 26f * dp)
            if (nodeB != null) spots += Spot.Disc(nodeB, 26f * dp)
        }
        TutorialHint.PLACE_MINE -> {
            dim = DIM_ALPHA_WORLD
            if (mine != null) spots += Spot.Disc(mine, 34f * dp)
        }
        TutorialHint.AIM_AND_FIRE -> {
            dim = DIM_ALPHA_WORLD
            anchorSpot(TutorialAnchorIds.FIRE)
        }
    }

    // Abdunkelung mit Ausschnitten (gerade-ungerade Füllregel: ohne Offscreen-Ebene und ohne Löschmodus)
    val dimPath = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(0f, 0f, size.width, size.height))
        for (s in spots) when (s) {
            is Spot.Box -> addRoundRect(RoundRect(s.rect, CornerRadius(s.corner)))
            is Spot.Disc -> addOval(Rect(s.center.x - s.radius, s.center.y - s.radius, s.center.x + s.radius, s.center.y + s.radius))
        }
    }
    drawPath(dimPath, Color.Black.copy(alpha = dim))

    // Pulsierender Ring: wächst nach außen und blendet aus; dazu ein fester Rand
    val pulse = phase
    for (s in spots) {
        val grow = pulse * 10f * dp
        val ringAlpha = 1f - pulse
        when (s) {
            is Spot.Box -> {
                drawRoundRect(
                    BollwerkColors.Hazard, s.rect.topLeft, s.rect.size, CornerRadius(s.corner), style = Stroke(2.5f * dp),
                )
                drawRoundRect(
                    BollwerkColors.Hazard.copy(alpha = 0.7f * ringAlpha),
                    Offset(s.rect.left - grow, s.rect.top - grow), Size(s.rect.width + 2 * grow, s.rect.height + 2 * grow),
                    CornerRadius(s.corner + grow), style = Stroke(2f * dp),
                )
            }
            is Spot.Disc -> {
                drawCircle(BollwerkColors.Hazard, s.radius, s.center, style = Stroke(2.5f * dp))
                drawCircle(BollwerkColors.Hazard.copy(alpha = 0.7f * ringAlpha), s.radius + grow, s.center, style = Stroke(2f * dp))
            }
        }
    }

    when (hint) {
        TutorialHint.DRAW_BEAM -> if (nodeA != null && nodeB != null) drawBeamHint(nodeA, nodeB, phase)
        TutorialHint.PLACE_MINE -> if (mine != null) drawOreHint(mine, ore, phase, measurer, oreLabel)
        TutorialHint.AIM_AND_FIRE -> drawDragHint(targets.facing, phase, measurer, dragLabel)
        else -> Unit
    }
}

/** Gestrichelte Soll-Linie zwischen den beiden Knoten (wie der Ghost-Balken, grün) mit wanderndem Punkt. */
private fun DrawScope.drawBeamHint(a: Offset, b: Offset, phase: Float) {
    val dp = density
    drawLine(
        BollwerkColors.Ok.copy(alpha = 0.9f), a, b, strokeWidth = 3f * dp, cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * dp, 8f * dp), phase * 36f * dp),
    )
    val p = easeInOut(phase)
    val dot = Offset(a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p)
    drawCircle(Color.White.copy(alpha = 0.95f), 5f * dp, dot)
    drawCircle(BollwerkColors.Ok, 5f * dp, dot, style = Stroke(1.5f * dp))
}

/** Marker über dem Erz: Pfeil nach unten zum Erz, Beschriftung „ERZ". */
private fun DrawScope.drawOreHint(mine: Offset, ore: Offset?, phase: Float, measurer: TextMeasurer, label: String) {
    val dp = density
    val bob = sin(phase * 2f * PI.toFloat()) * 3f * dp
    val tip = Offset(mine.x, mine.y + 14f * dp + bob)
    val arrow = Path().apply {
        moveTo(tip.x, tip.y + 12f * dp)
        lineTo(tip.x - 8f * dp, tip.y)
        lineTo(tip.x + 8f * dp, tip.y)
        close()
    }
    drawPath(arrow, BollwerkColors.Hazard)
    drawLabel(measurer, label, Offset(mine.x, mine.y - 50f * dp), BollwerkColors.Hazard)
    if (ore != null) drawCircle(BollwerkColors.Hazard.copy(alpha = 0.8f), 3f * dp, ore)
}

/**
 * Animierte Hand, die in Schussrichtung zieht („Zugrichtung = Schussrichtung"): gestrichelter Pfeil vom Startpunkt in
 * Schussrichtung, darauf die Hand mit Berührungspunkt; nach dem Zug kurz halten und ausblenden.
 */
private fun DrawScope.drawDragHint(facing: Int, phase: Float, measurer: TextMeasurer, label: String) {
    val dp = density
    val rad = DRAG_ELEVATION_DEG * PI.toFloat() / 180f
    val dir = Offset(facing * cos(rad), -sin(rad))
    // Startpunkt in der Mitte unten (zwischen den Festungen, unter der Karte, über der Leiste)
    val start = Offset(size.width * 0.5f - facing * 60f * dp, size.height * 0.675f)
    val len = DRAG_LENGTH_DP * dp
    val end = Offset(start.x + dir.x * len, start.y + dir.y * len)

    // Feste, schwache Führung: gestrichelter Pfeil in Schussrichtung
    drawLine(
        Color.White.copy(alpha = 0.45f), start, end, strokeWidth = 3f * dp, cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * dp, 9f * dp)),
    )
    val head = 11f * dp
    val side = Offset(-dir.y, dir.x)
    val arrow = Path().apply {
        moveTo(end.x + dir.x * head, end.y + dir.y * head)
        lineTo(end.x + side.x * head * 0.7f, end.y + side.y * head * 0.7f)
        lineTo(end.x - side.x * head * 0.7f, end.y - side.y * head * 0.7f)
        close()
    }
    drawPath(arrow, Color.White.copy(alpha = 0.6f))

    // Hand: 0..0,7 zieht, danach hält sie am Ende und blendet aus
    val move = (phase / 0.7f).coerceIn(0f, 1f)
    val t = easeInOut(move)
    val alpha = when {
        phase < 0.08f -> phase / 0.08f
        phase > 0.88f -> (1f - phase) / 0.12f
        else -> 1f
    }.coerceIn(0f, 1f)
    val tip = Offset(start.x + dir.x * len * t, start.y + dir.y * len * t)
    // Spur hinter der Hand
    drawLine(BollwerkColors.Hazard.copy(alpha = 0.85f * alpha), start, tip, strokeWidth = 4f * dp, cap = StrokeCap.Round)
    drawCircle(Color.White.copy(alpha = 0.35f * alpha), 16f * dp, tip)
    drawCircle(BollwerkColors.Hazard.copy(alpha = alpha), 16f * dp, tip, style = Stroke(2.5f * dp))
    drawHand(tip, alpha)

    val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
    // Beschriftung in Schussrichtung neben dem Pfeil
    drawLabel(measurer, label, Offset(mid.x + facing * 142f * dp, mid.y + 12f * dp), Color.White)
}

/** Einfache Zeigehand (Zeigefinger, Handballen, Daumen), Fingerspitze bei [tip]. */
private fun DrawScope.drawHand(tip: Offset, alpha: Float) {
    val dp = density
    val fill = Color.White.copy(alpha = 0.96f * alpha)
    val line = Color(0xFF1A212A).copy(alpha = alpha)
    val k = 0.85f * dp
    fun part(x: Float, y: Float, w: Float, h: Float, r: Float) {
        val tl = Offset(tip.x + x * k, tip.y + y * k)
        val sz = Size(w * k, h * k)
        drawRoundRect(fill, tl, sz, CornerRadius(r * k))
        drawRoundRect(line, tl, sz, CornerRadius(r * k), style = Stroke(1.4f * dp))
    }
    part(-14f, 22f, 28f, 24f, 9f) // Handballen
    part(5f, 14f, 8f, 16f, 4f) // Mittelfinger (angelegt)
    part(-13f, 28f, 9f, 14f, 4.5f) // Daumen
    part(-4.5f, 0f, 9f, 28f, 4.5f) // Zeigefinger
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, center: Offset, accent: Color) {
    val dp = density
    val result = measurer.measure(text, HudType.ItemLabel.copy(fontSize = 13.sp, letterSpacing = 0.14.em, color = accent))
    val padX = 8f * dp
    val padY = 4f * dp
    val w = result.size.width + 2 * padX
    val h = result.size.height + 2 * padY
    // Innerhalb der Fläche halten
    val x = (center.x - w / 2f).coerceIn(4f * dp, max(4f * dp, size.width - w - 4f * dp))
    val y = (center.y - h / 2f).coerceIn(4f * dp, max(4f * dp, size.height - h - 4f * dp))
    drawRoundRect(Color(0xE61A212A), Offset(x, y), Size(w, h), CornerRadius(8f * dp))
    drawRoundRect(accent.copy(alpha = 0.8f), Offset(x, y), Size(w, h), CornerRadius(8f * dp), style = Stroke(1.2f * dp))
    drawText(result, topLeft = Offset(x + padX, y + padY))
}

private fun easeInOut(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}
