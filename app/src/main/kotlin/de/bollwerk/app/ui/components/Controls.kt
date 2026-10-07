package de.bollwerk.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkType
import kotlin.math.roundToInt

/**
 * Industrie-Schieberegler (0..1). [onValueChange] feuert während des Ziehens, [onValueChangeFinished]
 * beim Loslassen (dort wird gespeichert). Touch-Höhe 48 dp.
 */
@Composable
fun IndustrialSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(value)
    // Der Gesten-Block läuft über Recompositions hinweg weiter; ohne diese Referenzen riefe er die Lambdas der
    // ersten Komposition auf (veralteter Zustand: Griff und Prozentanzeige folgen dem Finger nicht mehr).
    val changeState by rememberUpdatedState(onValueChange)
    val finishedState by rememberUpdatedState(onValueChangeFinished)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f, steps = 19)
                setProgress { v ->
                    onValueChange(v.coerceIn(0f, 1f))
                    onValueChangeFinished()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val pad = 14.dp.toPx()
                    fun frac(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                    val down = awaitFirstDown()
                    changeState(frac(down.position.x))
                    drag(down.id) { change ->
                        change.consume()
                        changeState(frac(change.position.x))
                    }
                    finishedState()
                }
            },
    ) {
        val pad = 14.dp.toPx()
        val trackH = 10.dp.toPx()
        val cy = size.height / 2f
        val w = size.width - 2 * pad
        // Rinne
        drawRoundRect(BollwerkColors.Dark, Offset(pad, cy - trackH / 2), Size(w, trackH), CornerRadius(3.dp.toPx()))
        drawRoundRect(
            BollwerkColors.SteelHi.copy(alpha = 0.25f), Offset(pad, cy - trackH / 2), Size(w, trackH),
            CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
        )
        // Füllung
        val fillW = w * current.coerceIn(0f, 1f)
        if (fillW > 0f) {
            drawRoundRect(
                Brush.verticalGradient(listOf(BollwerkColors.RustLight, BollwerkColors.RustDeep), cy - trackH / 2, cy + trackH / 2),
                Offset(pad, cy - trackH / 2), Size(fillW, trackH), CornerRadius(3.dp.toPx()),
            )
        }
        // Teilstriche
        for (i in 0..10) {
            val x = pad + w * i / 10f
            drawLine(BollwerkColors.SteelHi.copy(alpha = 0.35f), Offset(x, cy + trackH / 2 + 3.dp.toPx()), Offset(x, cy + trackH / 2 + 6.dp.toPx()), 1.dp.toPx())
        }
        // Griff: Stahlknopf mit Niete
        val tx = pad + fillW
        drawCircle(Color.Black.copy(alpha = 0.4f), 13.dp.toPx(), Offset(tx + 1.dp.toPx(), cy + 2.dp.toPx()))
        drawCircle(
            Brush.verticalGradient(listOf(BollwerkColors.SteelLight, BollwerkColors.SteelDeep), cy - 12.dp.toPx(), cy + 12.dp.toPx()),
            12.dp.toPx(), Offset(tx, cy),
        )
        drawCircle(BollwerkColors.SteelHi, 12.dp.toPx(), Offset(tx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        drawRivet(Offset(tx, cy), 3.5.dp.toPx())
    }
}

/**
 * Anzeigewert eines Schiebereglers während des Ziehens. Hält genau ein Zustandsobjekt über die gesamte Lebensdauer
 * des Reglers; ein von außen eintreffender Wert ([syncExternal], z. B. aus DataStore) überschreibt ihn nur, solange
 * nicht gezogen wird, damit der Griff dem Finger folgt.
 */
class SliderDragState(initial: Float) {
    var value by mutableFloatStateOf(initial.coerceIn(0f, 1f))
        private set
    var dragging: Boolean = false
        private set

    fun onDrag(v: Float) {
        dragging = true
        value = v.coerceIn(0f, 1f)
    }

    /** Ende der Geste; liefert den Wert, der gespeichert werden soll. */
    fun onFinish(): Float {
        dragging = false
        return value
    }

    fun syncExternal(v: Float) {
        if (!dragging) value = v.coerceIn(0f, 1f)
    }
}

/** Industrie-Schalter (Ein/Aus) mit 48 dp hohem Touch-Ziel. */
@Composable
fun IndustrialSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knobX by animateDpAsState(if (checked) 28.dp else 0.dp, label = "knob")
    Box(
        modifier
            .size(width = 64.dp, height = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.width(56.dp).height(28.dp).align(Alignment.Center)) {
            val r = size.height / 2f
            drawRoundRect(
                if (checked) Brush.verticalGradient(listOf(BollwerkColors.RustLight, BollwerkColors.RustDeep))
                else Brush.verticalGradient(listOf(BollwerkColors.SteelDeep, BollwerkColors.Dark)),
                size = size, cornerRadius = CornerRadius(r),
            )
            drawRoundRect(
                BollwerkColors.SteelHi.copy(alpha = 0.35f), size = size, cornerRadius = CornerRadius(r),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
            )
            val cx = r + knobX.toPx()
            drawCircle(Color.Black.copy(alpha = 0.35f), r - 2.dp.toPx(), Offset(cx + 1.dp.toPx(), r + 1.5.dp.toPx()))
            drawCircle(
                Brush.verticalGradient(listOf(BollwerkColors.SteelHi, BollwerkColors.Steel)),
                r - 3.dp.toPx(), Offset(cx, r),
            )
            drawRivet(Offset(cx, r), 3.dp.toPx())
        }
    }
}

/** Rundet auf ganze Prozent (Anzeige neben dem Schieberegler). */
fun percentOf(value: Float): Int = (value.coerceIn(0f, 1f) * 100f).roundToInt()

/**
 * Segmentierte Auswahl aus Industrie-Buttons (KI-Stärke, Startressourcen). Genau eine Option ist gewählt;
 * die gewählte ist rost-orange, die anderen Stahl.
 */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 48.dp,
) {
    androidx.compose.foundation.layout.Row(
        modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        for ((value, label) in options) {
            val isSelected = value == selected
            IndustrialButton(
                text = label,
                onClick = { onSelect(value) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { this.selected = isSelected },
                style = if (isSelected) ButtonStyle.Selected else ButtonStyle.Secondary,
                rivets = false,
                textStyle = BollwerkType.Segment,
                minHeight = minHeight,
            )
        }
    }
}
