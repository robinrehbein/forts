package de.bollwerk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.bollwerk.app.BuildInfo
import de.bollwerk.app.R

/**
 * Platzhalter-Titelbild (WP0): Dämmerungshimmel, Sonne, drei Bergebenen, Wordmark.
 * Schriften (Bebas Neue / Rajdhani) und das echte Hauptmenü kommen mit WP10.
 */
@Composable
fun TitleScreen(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(BollwerkColors.Sky)),
    ) {
        Backdrop(Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.title_wordmark),
                color = BollwerkColors.Text,
                fontSize = 88.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.12.em,
                textAlign = TextAlign.Center,
            )
            Box(
                Modifier
                    .width(220.dp)
                    .height(4.dp)
                    .background(BollwerkColors.Rust),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.title_tagline).uppercase(),
                color = BollwerkColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.16.em,
            )
            Spacer(Modifier.height(36.dp))
            Text(
                text = stringResource(R.string.title_tap_to_start).uppercase(),
                color = BollwerkColors.Hazard,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.18.em,
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.title_placeholder_note) + "  ·  " +
                    stringResource(R.string.title_version, BuildInfo.VERSION_NAME),
                color = BollwerkColors.SteelHi,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Backdrop(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Sonne tief am Horizont mit weichem Halo
        val sun = Offset(w * 0.72f, h * 0.70f)
        drawCircle(Brush.radialGradient(listOf(BollwerkColors.Sun.copy(alpha = 0.45f), Color.Transparent), sun, h * 0.35f), h * 0.35f, sun)
        drawCircle(BollwerkColors.Sun, h * 0.06f, sun)
        // Drei Bergebenen: fern (hell/violett) → nah (dunkel)
        mountain(w, h, 0.62f, 0.10f, 3.1f, BollwerkColors.MountainFar)
        mountain(w, h, 0.72f, 0.09f, 4.7f, BollwerkColors.MountainMid)
        mountain(w, h, 0.82f, 0.07f, 6.3f, BollwerkColors.MountainNear)
        drawRect(BollwerkColors.Dark, Offset(0f, h * 0.92f), androidx.compose.ui.geometry.Size(w, h * 0.08f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.mountain(
    w: Float,
    h: Float,
    base: Float,
    amp: Float,
    freq: Float,
    color: Color,
) {
    val p = Path()
    p.moveTo(0f, h)
    val steps = 48
    for (i in 0..steps) {
        val x = w * i / steps
        val t = i.toFloat() / steps
        val y = h * (base - amp * (0.6f * kotlin.math.sin(t * freq * 3.1416f).toFloat() + 0.4f * kotlin.math.sin(t * freq * 7.3f + 1.3f).toFloat()))
        p.lineTo(x, y)
    }
    p.lineTo(w, h)
    p.close()
    drawPath(p, color)
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun TitleScreenPreview() {
    TitleScreen()
}
