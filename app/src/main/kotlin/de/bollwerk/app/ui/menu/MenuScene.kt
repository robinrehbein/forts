package de.bollwerk.app.ui.menu

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import de.bollwerk.app.ui.art.drawFortBlades
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import de.bollwerk.app.ui.art.FortStyle
import de.bollwerk.app.ui.art.drawCloudBand
import de.bollwerk.app.ui.art.drawDuskSky
import de.bollwerk.app.ui.art.drawFort
import de.bollwerk.app.ui.art.drawGrassEdge
import de.bollwerk.app.ui.art.drawMountains
import de.bollwerk.app.ui.art.drawShell
import de.bollwerk.app.ui.art.drawShellArc
import de.bollwerk.app.ui.art.drawSmoke
import de.bollwerk.app.ui.art.drawStars
import de.bollwerk.app.ui.art.drawSun
import de.bollwerk.app.ui.theme.BollwerkColors

/**
 * Hintergrundszene des Hauptmenüs (Mockup 1): Dämmerung, Berge, Wolken, Schlucht mit zwei Festungen, Granatbogen.
 *
 * Drei Ebenen: die statische Szene (eigene Grafikebene, wird nur bei Größenänderung neu gezeichnet), die
 * Windrad-Blätter (einzige Ebene, die den animierten Winkel liest, 6 Linien je Frame) und der Lesbarkeits-Verlauf.
 * Mit [animate] = false (Einstellung „Reduzierte Effekte", Vorschau) gibt es keine laufende Animation.
 */
@Composable
fun MenuScene(modifier: Modifier = Modifier, animate: Boolean = true) {
    val angle: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "wind").animateFloat(
            0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart), label = "blades",
        )
    } else {
        remember { mutableFloatStateOf(20f) }
    }
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawStaticScene() }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val a = angle.value
            drawFortBlades(leftFortBase(size.width, size.height), fortScale(size.width), false, FortStyle.Silhouette, bladeAngle = a)
            drawFortBlades(rightFortBase(size.width, size.height), fortScale(size.width), true, FortStyle.Silhouette, bladeAngle = a * 0.8f + 40f)
        }
        // Lesbarkeit links: dunkler Verlauf
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to BollwerkColors.Dark.copy(alpha = 0.88f),
                    0.28f to BollwerkColors.Dark.copy(alpha = 0.55f),
                    0.5f to Color.Transparent,
                ),
            ),
        )
    }
}

private fun fortScale(w: Float) = w * 0.0165f
private fun leftFortBase(w: Float, h: Float) = Offset(w * 0.417f, h * 0.785f)
private fun rightFortBase(w: Float, h: Float) = Offset(w * 0.985f, h * 0.75f)

private fun DrawScope.drawStaticScene() {
    val w = size.width
    val h = size.height
    drawDuskSky()
    drawStars(50)
    drawSun(Offset(w * 0.72f, h * 0.58f), h * 0.065f)
    // Wolkenbänder
    val cloud = Color(0xFFB4698A).copy(alpha = 0.85f)
    val cloudLight = Color(0xFFF2B26B).copy(alpha = 0.8f)
    drawCloudBand(Offset(w * 0.62f, h * 0.31f), w * 0.40f, h * 0.075f, cloud, cloudLight)
    drawCloudBand(Offset(w * 0.80f, h * 0.43f), w * 0.26f, h * 0.05f, cloud, cloudLight)
    drawCloudBand(Offset(w * 0.52f, h * 0.465f), w * 0.22f, h * 0.04f, cloud, cloudLight)
    drawMountains(h * 0.66f, h * 0.20f)

    // Wasser/Nebel in der Kluft
    val chasmL = w * 0.60f
    val chasmR = w * 0.78f
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFF3B2F52), Color(0xFF1E1A2E)), h * 0.78f, h),
        Offset(chasmL - 10f, h * 0.78f), Size(chasmR - chasmL + 20f, h * 0.22f),
    )
    for (i in 0 until 4) {
        val y = h * (0.83f + i * 0.045f)
        drawLine(Color.White.copy(alpha = 0.07f), Offset(chasmL, y), Offset(chasmR, y), 2f)
    }

    // Klippen
    val earthTop = Color(0xFF7A573E)
    val earthBottom = Color(0xFF3A2A2E)
    val leftTop = h * 0.785f
    val rightTop = h * 0.75f
    val left = Path().apply {
        moveTo(0f, leftTop); lineTo(chasmL, leftTop)
        lineTo(chasmL + 8f, leftTop + h * 0.07f); lineTo(chasmL - 12f, leftTop + h * 0.12f)
        lineTo(chasmL + 4f, h); lineTo(0f, h); close()
    }
    drawPath(left, Brush.verticalGradient(listOf(earthTop, earthBottom), leftTop, h))
    val right = Path().apply {
        moveTo(w, rightTop); lineTo(chasmR, rightTop)
        lineTo(chasmR - 8f, rightTop + h * 0.08f); lineTo(chasmR + 10f, rightTop + h * 0.14f)
        lineTo(chasmR - 4f, h); lineTo(w, h); close()
    }
    drawPath(right, Brush.verticalGradient(listOf(earthTop, earthBottom), rightTop, h))
    for (i in 1..3) {
        drawLine(Color.Black.copy(alpha = 0.18f), Offset(0f, leftTop + h * 0.07f * i), Offset(chasmL, leftTop + h * 0.07f * i), 2f)
        drawLine(Color.Black.copy(alpha = 0.18f), Offset(chasmR, rightTop + h * 0.08f * i), Offset(w, rightTop + h * 0.08f * i), 2f)
    }
    drawGrassEdge(w * 0.36f, chasmL, leftTop, h * 0.012f)
    drawGrassEdge(chasmR, w, rightTop, h * 0.012f)

    // Festungen
    val s = fortScale(w)
    drawFort(leftFortBase(w, h), s, mirror = false, style = FortStyle.Silhouette, flag = BollwerkColors.TeamBlue, withBlades = false)
    drawFort(rightFortBase(w, h), s, mirror = true, style = FortStyle.Silhouette, flag = BollwerkColors.TeamRed, withBlades = false)
    drawSmoke(Offset(w * 0.92f, rightTop - 8.5f * s), s * 0.85f, 6, 0.6f)

    // Granatbogen von links nach rechts
    val p0 = Offset(w * 0.512f, h * 0.555f)
    val p1 = Offset(w * 0.66f, h * -0.02f)
    val p2 = Offset(w * 0.89f, h * 0.50f)
    val (pos, ang) = drawShellArc(p0, p1, p2, dots = 34, maxRadius = h * 0.0075f, color = Color(0xFFFFD9B0), shellT = 0.60f)
    drawShell(pos, ang, h * 0.045f)
}
