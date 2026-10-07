package de.bollwerk.app.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Meldet Texte, die selbst in der kleinsten erlaubten Größe nicht in ihren Platz passen (und deshalb mit „…" gekürzt
 * werden). Nur für Tests: die Layout-Prüfungen schmaler Bildschirme (640/720 dp) schlagen damit bei abgeschnittenen
 * Labels fehl, statt sie stillschweigend als Golden aufzunehmen.
 */
fun interface TextFitReporter {
    fun onOverflow(text: String)
}

val LocalTextFitReporter = staticCompositionLocalOf<TextFitReporter?> { null }

/**
 * Text, der sich seinem Platz anpasst, statt abgeschnitten zu werden: zuerst wird die Laufweite verringert, dann die
 * Schriftgröße in 0,5-sp-Schritten bis [minFontSize] (Stil-Bibel §3: Versalien-Labels nie unter 10, Fließtext nie unter
 * 12). Passt er auch dann nicht, wird er mit „…" gekürzt und an [LocalTextFitReporter] gemeldet. Einzeilig ([maxLines] = 1)
 * ohne Umbruch, sonst mit Umbruch über höchstens [maxLines] Zeilen. Misst synchron in einem Layout-Durchgang (keine
 * Subkomposition, Intrinsics funktionieren).
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    minFontSize: TextUnit = 10.sp,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Start,
    /** Kürzere Fassungen von [text] (in dieser Reihenfolge), falls [text] auch verkleinert nicht passt. */
    fallbacks: List<String> = emptyList(),
) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val reporter = LocalTextFitReporter.current
    val merged = LocalTextStyle.current.merge(style)
    val resolved = merged.copy(color = color.takeOrElse { merged.color.takeOrElse { LocalContentColor.current } }, textAlign = textAlign)
    val holder = remember { FitHolder() }
    Layout(
        modifier = modifier
            .semantics { this.text = AnnotatedString(text) }
            .drawBehind { holder.layout?.let { drawText(it, topLeft = Offset(holder.x, 0f)) } },
        content = {},
    ) { _, constraints ->
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val fit = fitFirst(measurer, text, fallbacks, resolved, maxW, maxLines, minFontSize)
        if (fit.overflow) reporter?.onOverflow(text)
        val r = fit.layout
        holder.layout = r
        val w = constraints.constrainWidth(r.size.width)
        val h = constraints.constrainHeight(r.size.height)
        holder.x = when (textAlign) {
            TextAlign.Center -> (w - r.size.width) / 2f
            TextAlign.End, TextAlign.Right -> (w - r.size.width).toFloat()
            else -> 0f
        }
        layout(w, h, mapOf(FirstBaseline to r.firstBaseline.roundToInt(), LastBaseline to r.lastBaseline.roundToInt())) {}
    }
}

private class FitHolder {
    var layout: TextLayoutResult? = null
    var x: Float = 0f
}

/** [fitText] für [text] und danach für jede kürzere Fassung; gemeldet wird nur, wenn auch die letzte nicht passt. */
internal fun fitFirst(
    measurer: TextMeasurer,
    text: String,
    fallbacks: List<String>,
    style: TextStyle,
    maxWidth: Int,
    maxLines: Int,
    minFontSize: TextUnit,
): FitResult {
    var fit = fitText(measurer, text, style, maxWidth, maxLines, minFontSize)
    for (alt in fallbacks) {
        if (!fit.overflow) return fit
        fit = fitText(measurer, alt, style, maxWidth, maxLines, minFontSize)
    }
    return fit
}

/** Ergebnis von [fitText]: das zu zeichnende Layout und ob der Text trotz Verkleinerung gekürzt werden musste. */
internal class FitResult(val layout: TextLayoutResult, val overflow: Boolean)

/**
 * Sucht die größte Darstellung von [text], die in [maxWidth] Pixel (und [maxLines] Zeilen) passt: Originalstil, dann
 * engere Laufweite, dann je 0,5 sp kleiner bis [minFontSize].
 */
internal fun fitText(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidth: Int,
    maxLines: Int,
    minFontSize: TextUnit,
): FitResult {
    val singleLine = maxLines <= 1
    val lines = if (singleLine) 1 else maxLines
    val constraints = Constraints(maxWidth = maxWidth)
    fun measure(s: TextStyle, overflow: TextOverflow) =
        measurer.measure(text, s, overflow = overflow, softWrap = !singleLine, maxLines = lines, constraints = constraints)

    val first = measure(style, TextOverflow.Clip)
    if (!first.hasVisualOverflow || maxWidth == Constraints.Infinity) return FitResult(first, overflow = false)

    val spacing = style.letterSpacing
    val tight = if (spacing.isSpecified && spacing.isEm && spacing.value > TIGHT_EM) TIGHT_EM.em else spacing
    val size = style.fontSize
    val canShrink = size.isSpecified && size.isSp && minFontSize.isSp && size.value > minFontSize.value
    var s = size.value
    var candidate = style.copy(letterSpacing = tight)
    while (true) {
        val r = measure(candidate, TextOverflow.Clip)
        if (!r.hasVisualOverflow) return FitResult(r, overflow = false)
        if (!canShrink || s <= minFontSize.value) break
        s = maxOf(minFontSize.value, s - STEP_SP)
        candidate = candidate.copy(fontSize = s.sp)
    }
    return FitResult(measure(candidate, TextOverflow.Ellipsis), overflow = true)
}

private const val STEP_SP = 0.5f
private const val TIGHT_EM = 0.04f
