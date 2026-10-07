package de.bollwerk.app.ui.menu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import de.bollwerk.app.ui.components.drawRivet
import de.bollwerk.app.ui.theme.BollwerkColors

/**
 * Wordmark im Stil des Mockups: Bebas-Neue-Buchstaben als Vektorpfad ([WordmarkData]) mit Stahl-Rost-Verlauf,
 * gebürsteten Linien, Lichtkante, dunkler Kontur, warmem Schein und Nieten. [height] = Höhe der Buchstaben.
 */
@Composable
fun Wordmark(text: String, height: Dp, modifier: Modifier = Modifier) {
    val path = remember { PathParser().parsePathString(WordmarkData.PATH).toPath() }
    val aspect = WordmarkData.WIDTH / WordmarkData.HEIGHT
    // Rand für Kontur und Schein: 6 % der Höhe
    val pad = height * 0.08f
    Canvas(
        Modifier
            .size(height * aspect + pad * 2, height + pad * 2)
            .semantics { contentDescription = text }
            .then(modifier),
    ) {
        val s = (size.height - pad.toPx() * 2) / WordmarkData.HEIGHT
        translate(pad.toPx(), pad.toPx()) {
            scale(s, s, pivot = Offset.Zero) {
                val h = WordmarkData.HEIGHT
                val w = WordmarkData.WIDTH
                val unit = 1f
                // warmer Schein und dunkle Kontur unter den Buchstaben
                translate(0f, 3.2f * unit) { drawPath(path, BollwerkColors.Rust.copy(alpha = 0.6f), style = Stroke(5f, join = StrokeJoin.Round)) }
                translate(0f, 1.6f * unit) { drawPath(path, BollwerkColors.Dark, style = Stroke(4f, join = StrokeJoin.Round)) }
                drawPath(
                    path,
                    Brush.verticalGradient(
                        0f to Color(0xFFE2E8F0),
                        0.30f to BollwerkColors.SteelHi,
                        0.52f to Color(0xFFC3CBD6),
                        0.72f to Color(0xFF9C8478),
                        1f to BollwerkColors.Rust,
                        startY = 0f, endY = h,
                    ),
                )
                clipPath(path) {
                    // gebürstete Linien
                    var y = 0.5f
                    var i = 0
                    while (y < h) {
                        val a = 0.04f + 0.05f * ((i * 7) % 5) / 4f
                        drawLine(Color.White.copy(alpha = a), Offset(0f, y), Offset(w, y), 0.5f)
                        y += 1.4f
                        i++
                    }
                    drawPath(path, Color.White.copy(alpha = 0.5f), style = Stroke(1.6f))
                    val r = WordmarkData.RIVETS
                    for (k in 0 until r.size / 2) {
                        drawRivet(Offset(r[2 * k], r[2 * k + 1]), WordmarkData.RIVET_RADIUS, Color(0xFF3A4350), Color(0xFFE9EEF4))
                    }
                }
                drawPath(path, BollwerkColors.Dark.copy(alpha = 0.9f), style = Stroke(0.8f, join = StrokeJoin.Round))
            }
        }
    }
}
