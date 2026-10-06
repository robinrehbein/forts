package de.bollwerk.app.ui.art

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import de.bollwerk.app.ui.components.drawHazardStripes
import de.bollwerk.app.ui.components.drawRivet
import de.bollwerk.app.ui.theme.BollwerkColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Zerstörter Reaktor (Stil-Bibel §4): Gehäuse mit Kühlrippen, rundes Sichtfenster mit gesplittertem Glas,
 * Warnstreifen, Lämpchen, Flammen und Trümmerbalken. Gezeichnet in einem 100 × 92 Einheiten großen Raum.
 */
fun DrawScope.drawDestroyedReactor() {
    val w = size.width
    val h = size.height
    // Nachthimmel + Berge + Boden
    drawRect(Brush.verticalGradient(listOf(Color(0xFF1B1F3A), Color(0xFF4A3466), Color(0xFF7A4A6E)), 0f, h))
    drawStars(24, seed = 31)
    drawMountainLayer(h * 0.78f, h * 0.18f, 2.2f, 0.8f, BollwerkColors.MountainNear.copy(alpha = 0.9f))
    drawRect(Color(0xFF241E30), Offset(0f, h * 0.88f), Size(w, h * 0.12f))
    drawRect(BollwerkColors.Hazard.copy(alpha = 0.55f), Offset(0f, h * 0.87f), Size(w, 2f))

    val u = minOf(w / 100f, h / 88f) * 0.92f
    val ox = (w - 100f * u) / 2f
    val oy = h * 0.06f
    fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    fun sz(a: Float, b: Float) = Size(a * u, b * u)

    // Kühlrippen
    for (i in 0 until 6) {
        val x = 17f + i * 12.6f
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF8D98A6), Color(0xFF3A424D)), p(0f, 8f).y, p(0f, 24f).y),
            p(x, 8f), sz(7f, 17f), CornerRadius(2f * u),
        )
        drawRoundRect(BollwerkColors.Dark, p(x, 8f), sz(7f, 17f), CornerRadius(2f * u), style = Stroke(1.2f))
    }
    // Gehäuse
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF69737F), Color(0xFF3F4752), Color(0xFF2D343D)), p(0f, 22f).y, p(0f, 70f).y),
        p(9f, 22f), sz(82f, 50f), CornerRadius(7f * u),
    )
    drawRoundRect(BollwerkColors.Dark, p(9f, 22f), sz(82f, 50f), CornerRadius(7f * u), style = Stroke(2.2f))
    drawRoundRect(Color.White.copy(alpha = 0.18f), p(10.5f, 23.5f), sz(79f, 1.4f), CornerRadius(1f * u))
    // Warnstreifen unten
    drawHazardStripes(p(10f, 60f), sz(80f, 10.5f), stripe = 9f * u, a = BollwerkColors.Hazard, b = Color(0xFF14171C))
    // Bolzen
    for ((bx, by) in listOf(17f to 30f, 83f to 30f)) drawRivet(p(bx, by), 2.6f * u, Color(0xFF14171C), Color(0xFFB9C3CF))
    // Sichtfenster
    val c = p(50f, 45f)
    drawCircle(Color(0xFF14171C), 25f * u, c)
    drawCircle(Brush.verticalGradient(listOf(Color(0xFF6C7785), Color(0xFF2B323B)), c.y - 24f * u, c.y + 24f * u), 23.5f * u, c)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF3AA8B0), Color(0xFF0E3D45), Color(0xFF0B1A20)), c, 21f * u), 21f * u, c)
    // Kreuzstreben
    drawLine(Color(0xFF0B1115), Offset(c.x - 21f * u, c.y), Offset(c.x + 21f * u, c.y), 1.6f * u)
    drawLine(Color(0xFF0B1115), Offset(c.x, c.y - 21f * u), Offset(c.x, c.y + 21f * u), 1.6f * u)
    // Splitterglas: radiale Risse + Ringsegmente
    val crack = Color(0xFFF2F6FA)
    for (i in 0 until 16) {
        val a = (i * 22.5f + (hash01(i) - 0.5f) * 10f) * (Math.PI.toFloat() / 180f)
        val len = (9f + 12f * hash01(i + 40)) * u
        drawLine(crack.copy(alpha = 0.9f), c, Offset(c.x + cos(a) * len, c.y + sin(a) * len), 0.9f, StrokeCap.Round)
    }
    for (r in listOf(6f, 11f, 16f)) {
        val path = Path()
        for (i in 0..10) {
            val a = (i * 36f + r * 7f) * (Math.PI.toFloat() / 180f)
            val rr = (r + (hash01(i + r.toInt()) - 0.5f) * 3f) * u
            val pt = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr)
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        drawPath(path, crack.copy(alpha = 0.55f), style = Stroke(0.8f))
    }
    drawCircle(BollwerkColors.FireMid.copy(alpha = 0.9f), 2.6f * u, c, style = Stroke(1.4f))
    // Lämpchen (rot)
    val lamp = p(78f, 30f)
    drawCircle(BollwerkColors.TeamRed.copy(alpha = 0.35f), 7f * u, lamp)
    drawCircle(Color(0xFF14171C), 4.2f * u, lamp)
    drawCircle(BollwerkColors.TeamRed, 3.2f * u, lamp)
    // Füße
    for (fx in listOf(18f, 66f)) {
        val foot = Path().apply {
            moveTo(p(fx, 72f).x, p(fx, 72f).y); lineTo(p(fx + 16f, 72f).x, p(fx + 16f, 72f).y)
            lineTo(p(fx + 19f, 78f).x, p(fx + 19f, 78f).y); lineTo(p(fx - 3f, 78f).x, p(fx - 3f, 78f).y); close()
        }
        drawPath(foot, Color(0xFF59626E))
        drawPath(foot, BollwerkColors.Dark, style = Stroke(1.6f))
        drawCircle(Color(0xFFC9D2DC), 1.8f * u, p(fx + 8f, 75f))
    }
    // Flammen auf zwei Rippen + Rauch
    for (fx in listOf(20f, 38f)) {
        val base = p(fx, 11f)
        val flame = Path().apply {
            moveTo(base.x - 3f * u, base.y)
            cubicTo(base.x - 4f * u, base.y - 6f * u, base.x - 1f * u, base.y - 8f * u, base.x, base.y - 13f * u)
            cubicTo(base.x + 2f * u, base.y - 8f * u, base.x + 5f * u, base.y - 5f * u, base.x + 3f * u, base.y)
            close()
        }
        drawPath(flame, Brush.verticalGradient(listOf(BollwerkColors.FireMid, BollwerkColors.FireOuter), base.y - 13f * u, base.y))
        drawPath(
            Path().apply {
                moveTo(base.x - 1.2f * u, base.y); quadraticBezierTo(base.x, base.y - 7f * u, base.x + 1.2f * u, base.y); close()
            },
            BollwerkColors.FireInner,
        )
    }
    drawSmoke(p(32f, 8f), 3.6f * u, 5, 0.8f)
    // Trümmerbalken
    fun beam(cx: Float, cy: Float, len: Float, angle: Float, color: Color) {
        rotate(angle, p(cx, cy)) {
            drawRect(color, p(cx - len / 2, cy - 1.3f), sz(len, 2.6f))
            drawRect(Color.Black.copy(alpha = 0.3f), p(cx - len / 2, cy + 0.4f), sz(len, 0.9f))
        }
    }
    beam(14f, 82f, 24f, -4f, BollwerkColors.Wood)
    beam(80f, 83f, 18f, 5f, BollwerkColors.WoodDark)
    beam(9f, 48f, 7f, -35f, BollwerkColors.WoodLight)
    beam(92f, 52f, 7f, 38f, BollwerkColors.WoodLight)
}
