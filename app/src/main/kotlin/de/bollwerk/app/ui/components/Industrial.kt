package de.bollwerk.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkType

/** Rechteck mit abgeschrägter unterer rechter Ecke (Mockups: Buttons, Banner, Panels). */
class ChamferShape(private val cut: Dp = 10.dp, private val topCorner: Dp = 3.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(size.height / 2f)
        val t = with(density) { topCorner.toPx() }
        val w = size.width
        val h = size.height
        return Outline.Generic(
            Path().apply {
                moveTo(t, 0f)
                lineTo(w, 0f)
                lineTo(w, h - c)
                lineTo(w - c, h)
                lineTo(0f, h)
                lineTo(0f, t)
                close()
            },
        )
    }
}

/** Farbwelt eines Buttons (Stil-Bibel §2: rust = primär, steel = sekundär). */
enum class ButtonStyle(val light: Color, val base: Color, val deep: Color, val hazard: Boolean) {
    Primary(BollwerkColors.RustLight, BollwerkColors.Rust, BollwerkColors.RustDeep, hazard = true),
    Selected(BollwerkColors.RustLight, BollwerkColors.Rust, BollwerkColors.RustDeep, hazard = false),
    Secondary(BollwerkColors.SteelLight, BollwerkColors.Steel, BollwerkColors.SteelDeep, hazard = false),
    Blue(BollwerkColors.BlueLight, BollwerkColors.TeamBlue, BollwerkColors.BlueDeep, hazard = false),
    Danger(BollwerkColors.RedLight, BollwerkColors.TeamRed, BollwerkColors.RedDeep, hazard = true),
}

/** Niete: dunkler Ring, helles Zentrum (Stil-Bibel §4 Knoten/Nieten). */
fun DrawScope.drawRivet(center: Offset, radius: Float, ring: Color = BollwerkColors.Dark, core: Color = BollwerkColors.Text) {
    drawCircle(ring, radius, center)
    drawCircle(core.copy(alpha = 0.9f), radius * 0.5f, center)
}

/** Diagonale Warnstreifen (hazard/dark) im Rechteck [topLeft]/[size]. */
fun DrawScope.drawHazardStripes(
    topLeft: Offset,
    size: Size,
    stripe: Float,
    a: Color = BollwerkColors.Hazard,
    b: Color = BollwerkColors.Dark,
) {
    if (stripe < 0.5f || size.width <= 0f || size.height <= 0f) return
    clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
        drawRect(b, topLeft, size)
        var x = topLeft.x - size.height
        val bottom = topLeft.y + size.height
        while (x < topLeft.x + size.width) {
            val p = Path().apply {
                moveTo(x, bottom)
                lineTo(x + stripe, bottom)
                lineTo(x + stripe + size.height, topLeft.y)
                lineTo(x + size.height, topLeft.y)
                close()
            }
            drawPath(p, a)
            x += stripe * 2f
        }
    }
}

/** Zeichnet die Stahl-/Rost-Fläche eines Buttons: Verlauf, Lichtkante, Schattenkante, Outline, Nieten, Warnstreifen. */
private fun DrawScope.drawButtonFace(
    style: ButtonStyle,
    shape: Shape,
    cutPx: Float,
    rivets: Boolean,
    hazard: Boolean,
    pressed: Boolean,
    enabled: Boolean,
) {
    val outline = shape.createOutline(size, LayoutDirection.Ltr, this)
    val sat = if (enabled) 1f else 0.45f
    fun c(col: Color) = if (enabled) col else col.copy(alpha = 0.6f)
    drawOutline(outline, Brush.verticalGradient(listOf(c(style.light), c(style.base), c(style.deep))))
    // helle Oberkante, dunkle Unterkante (Bevel)
    drawRect(Color.White.copy(alpha = 0.28f * sat), Offset(0f, 0f), Size(size.width, 1.5.dp.toPx()))
    drawRect(Color.Black.copy(alpha = 0.28f), Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()))
    drawOutline(outline, BollwerkColors.Dark.copy(alpha = 0.85f), style = Stroke(1.5.dp.toPx()))
    if (rivets) {
        val r = 2.6.dp.toPx()
        val cy = size.height * 0.46f
        drawRivet(Offset(14.dp.toPx(), cy), r)
        drawRivet(Offset(size.width - 14.dp.toPx() - cutPx * 0.2f, cy), r)
    }
    if (hazard) {
        val h = 5.dp.toPx()
        drawHazardStripes(
            Offset(10.dp.toPx(), size.height - h - 3.dp.toPx()),
            Size((size.width - 10.dp.toPx() - cutPx - 4.dp.toPx()).coerceAtLeast(0f), h),
            stripe = 5.dp.toPx(),
        )
    }
    if (pressed) drawOutline(outline, Color.Black.copy(alpha = 0.22f))
}

/**
 * Industrie-Button: Verlauf mit Bevel, Nieten links/rechts, Fase unten rechts, optional Warnstreifen.
 * Mindesthöhe 48 dp (Touch-Ziel).
 */
@Composable
fun IndustrialButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Secondary,
    icon: Painter? = null,
    textStyle: TextStyle = BollwerkType.Button,
    rivets: Boolean = true,
    hazard: Boolean = style.hazard,
    enabled: Boolean = true,
    minHeight: Dp = 52.dp,
    cut: Dp = 10.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Abstand zwischen Icon/[leading], Text und [trailing]. */
    gap: Dp = 12.dp,
) {
    val shape = remember(cut) { ChamferShape(cut) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .heightIn(min = maxOf(minHeight, 48.dp))
            .shadow(3.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .drawBehind { drawButtonFace(style, shape, cut.toPx(), rivets, hazard, pressed, enabled) }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (rivets) 26.dp else 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.offset(y = if (pressed) 1.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        ) {
            leading?.invoke()
            if (icon != null) {
                Image(icon, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(BollwerkColors.Text))
            }
            // Schmale Bildschirme (640 dp): Laufweite/Größe passen sich an, statt das Label abzuschneiden
            FitText(
                text = text.uppercase(),
                modifier = Modifier.weight(1f, fill = false),
                style = textStyle,
                color = BollwerkColors.Text.copy(alpha = if (enabled) 1f else 0.5f),
                textAlign = TextAlign.Center,
                minFontSize = 12.sp,
            )
            trailing?.invoke()
        }
    }
}

/** Quadratischer Icon-Button in Stahl (Zurück, Pause); 48 dp. */
@Composable
fun IndustrialIconButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    style: ButtonStyle = ButtonStyle.Secondary,
) {
    val shape = remember { ChamferShape(8.dp) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(maxOf(size, 48.dp))
            .shadow(3.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .drawBehind { drawButtonFace(style, shape, 8.dp.toPx(), rivets = false, hazard = false, pressed = pressed, enabled = true) }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            icon,
            contentDescription,
            Modifier.size(26.dp),
            colorFilter = ColorFilter.tint(BollwerkColors.Text),
        )
    }
}

/** Warnstreifen-Band (Rand von Hotseat-Übergabe, Dekor an Panels). */
@Composable
fun HazardStripe(
    modifier: Modifier = Modifier,
    a: Color = BollwerkColors.Hazard,
    b: Color = BollwerkColors.Dark,
    stripeWidth: Dp = 14.dp,
) {
    Box(modifier.drawBehind { drawHazardStripes(Offset.Zero, size, stripeWidth.toPx(), a, b) })
}

/** Nur zum Verschlucken von Taps auf einem Scrim (Dialoge). */
fun Modifier.consumeTaps(): Modifier =
    this.clickable(interactionSource = null, indication = null, onClick = {})
