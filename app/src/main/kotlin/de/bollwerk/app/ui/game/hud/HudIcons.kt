package de.bollwerk.app.ui.game.hud

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.drawscope.translate
import de.bollwerk.app.ui.theme.BollwerkColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kleine Bauteil-Illustrationen der Toolbar und des Techbaums (Stil-Bibel §4: kanonische Formen, Licht von oben links,
 * dunkle Outline), gezeichnet statt als Bitmap, damit sie in jeder Größe scharf bleiben. [dim] entsättigt (gesperrt).
 */
@Composable
fun PartIcon(id: String, modifier: Modifier = Modifier, dim: Boolean = false) {
    Canvas(modifier) {
        val a = if (dim) 0.38f else 1f
        when (id) {
            "wood" -> beamIcon(BollwerkColors.WoodLight, BollwerkColors.Wood, BollwerkColors.WoodDark, 0.16f, a, joints = true)
            "metal" -> beamIcon(Color(0xFFB4C0CE), BollwerkColors.SteelHi, Color(0xFF55606E), 0.10f, a, joints = true, rivets = true)
            "armour" -> beamIcon(Color(0xFF6A7584), Color(0xFF3F4955), Color(0xFF232A33), 0.18f, a, joints = true, rivets = true)
            "rope" -> ropeIcon(a)
            "door" -> doorIcon(a)
            "mine" -> mineIcon(a)
            "turbine" -> turbineIcon(a)
            "workshop" -> workshopIcon(a)
            "armoury" -> armouryIcon(a)
            "upgrade_center" -> upgradeIcon(a)
            "factory" -> factoryIcon(a)
            "mg" -> mgIcon(a)
            "sniper" -> sniperIcon(a)
            "mortar" -> mortarIcon(a)
            "cannon" -> cannonIcon(a)
            "rocket" -> rocketIcon(a)
            "laser" -> laserIcon(a)
            "reactor" -> reactorIcon(a)
            else -> Unit
        }
    }
}

private val Outline = Color(0xFF0D1116)

private fun DrawScope.u(f: Float) = size.minDimension * f

private fun DrawScope.beamIcon(light: Color, base: Color, deep: Color, thick: Float, a: Float, joints: Boolean, rivets: Boolean = false) {
    val c = center
    val len = size.width * 0.86f
    val t = u(thick)
    rotate(-28f, c) {
        val tl = Offset(c.x - len / 2, c.y - t / 2)
        drawRoundRect(Outline.copy(alpha = a), tl - Offset(1.5f, 1.5f), Size(len + 3f, t + 3f), CornerRadius(3f))
        drawRoundRect(Brush.verticalGradient(listOf(light, base, deep), tl.y, tl.y + t), tl, Size(len, t), CornerRadius(2f), alpha = a)
        if (rivets) {
            var x = tl.x + t
            while (x < tl.x + len - t * 0.5f) {
                drawCircle(Color(0xFF20262E).copy(alpha = a), t * 0.13f, Offset(x, c.y))
                x += t * 1.6f
            }
        }
        if (joints) {
            for (sx in floatArrayOf(tl.x + t * 0.3f, tl.x + len - t * 0.3f)) {
                drawCircle(Outline.copy(alpha = a), t * 0.62f, Offset(sx, c.y))
                drawCircle(Color(0xFF9AA6B4).copy(alpha = a), t * 0.48f, Offset(sx, c.y))
                drawCircle(Color(0xFF2B3440).copy(alpha = a), t * 0.18f, Offset(sx, c.y))
            }
        }
    }
}

private fun DrawScope.ropeIcon(a: Float) {
    val p = Path()
    val w = size.width
    val h = size.height
    p.moveTo(w * 0.08f, h * 0.68f)
    p.cubicTo(w * 0.35f, h * 0.95f, w * 0.55f, h * 0.15f, w * 0.92f, h * 0.30f)
    drawPath(p, Outline.copy(alpha = a), style = Stroke(u(0.15f), cap = StrokeCap.Round))
    drawPath(p, Color(0xFFC9A46A).copy(alpha = a), style = Stroke(u(0.10f), cap = StrokeCap.Round))
    drawPath(p, Color(0xFF7A5A2E).copy(alpha = a), style = Stroke(u(0.10f), cap = StrokeCap.Butt, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(u(0.04f), u(0.06f)))))
    drawCircle(Color(0xFF9AA6B4).copy(alpha = a), u(0.07f), Offset(w * 0.92f, h * 0.30f))
}

private fun DrawScope.doorIcon(a: Float) {
    val w = size.width
    val h = size.height
    val tl = Offset(w * 0.2f, h * 0.12f)
    val sz = Size(w * 0.6f, h * 0.76f)
    drawRect(Outline.copy(alpha = a), tl - Offset(2f, 2f), Size(sz.width + 4f, sz.height + 4f))
    drawRect(Brush.verticalGradient(listOf(Color(0xFF8A6A44), Color(0xFF5C4128)), tl.y, tl.y + sz.height), tl, sz, alpha = a)
    for (i in 1..3) {
        val y = tl.y + sz.height * i / 4f
        drawLine(Color(0xFF3A2816).copy(alpha = a), Offset(tl.x, y), Offset(tl.x + sz.width, y), 2f)
    }
    drawRect(BollwerkColors.Hazard.copy(alpha = a), tl, Size(sz.width, sz.height * 0.1f))
    drawRect(Color(0xFF9AA6B4).copy(alpha = a), Offset(tl.x - 1f, tl.y + sz.height * 0.2f), Size(sz.width * 0.12f, sz.height * 0.12f))
    drawRect(Color(0xFF9AA6B4).copy(alpha = a), Offset(tl.x - 1f, tl.y + sz.height * 0.68f), Size(sz.width * 0.12f, sz.height * 0.12f))
}

private fun DrawScope.mineIcon(a: Float) {
    val w = size.width
    val h = size.height
    // Erz-Kristalle
    for ((x, s) in listOf(0.2f to 0.18f, 0.78f to 0.15f, 0.62f to 0.12f)) {
        val p = Path().apply {
            moveTo(w * x, h * 0.95f - h * s * 1.4f); lineTo(w * x + w * s * 0.4f, h * 0.95f); lineTo(w * x - w * s * 0.4f, h * 0.95f); close()
        }
        drawPath(p, BollwerkColors.Energy.copy(alpha = a))
    }
    // Bohrturm
    val top = Offset(w * 0.5f, h * 0.18f)
    val l = Offset(w * 0.26f, h * 0.9f)
    val r = Offset(w * 0.74f, h * 0.9f)
    val st = Stroke(u(0.06f), cap = StrokeCap.Round)
    val col = Color(0xFFB4C0CE).copy(alpha = a)
    drawLine(col, top, l, st.width); drawLine(col, top, r, st.width)
    drawLine(col, Offset(w * 0.34f, h * 0.65f), Offset(w * 0.66f, h * 0.65f), st.width * 0.8f)
    drawLine(col, Offset(w * 0.40f, h * 0.45f), Offset(w * 0.60f, h * 0.45f), st.width * 0.8f)
    drawLine(col, Offset(w * 0.34f, h * 0.65f), Offset(w * 0.60f, h * 0.45f), st.width * 0.6f)
    drawCircle(Outline.copy(alpha = a), u(0.11f), top)
    drawCircle(BollwerkColors.Rust.copy(alpha = a), u(0.08f), top)
    drawRect(BollwerkColors.Rust.copy(alpha = a), Offset(w * 0.40f, h * 0.84f), Size(w * 0.2f, h * 0.08f))
}

private fun DrawScope.turbineIcon(a: Float) {
    val w = size.width
    val h = size.height
    val hub = Offset(w * 0.5f, h * 0.32f)
    drawLine(Color(0xFFB4C0CE).copy(alpha = a), hub, Offset(w * 0.5f, h * 0.95f), u(0.07f))
    for (k in 0 until 3) {
        val ang = Math.toRadians((k * 120 - 70).toDouble())
        val tip = Offset(hub.x + cos(ang).toFloat() * w * 0.42f, hub.y + sin(ang).toFloat() * h * 0.42f)
        drawLine(Color(0xFFDDE3EA).copy(alpha = a), hub, tip, u(0.07f), StrokeCap.Round)
        val mid = Offset(hub.x + (tip.x - hub.x) * 0.75f, hub.y + (tip.y - hub.y) * 0.75f)
        drawLine(BollwerkColors.Rust.copy(alpha = a), mid, tip, u(0.07f), StrokeCap.Round)
    }
    drawCircle(Outline.copy(alpha = a), u(0.08f), hub)
    drawCircle(Color(0xFF9AA6B4).copy(alpha = a), u(0.05f), hub)
}

private fun DrawScope.hut(a: Float, roof: Color, body: Color) {
    val w = size.width
    val h = size.height
    drawRect(Outline.copy(alpha = a), Offset(w * 0.12f, h * 0.4f), Size(w * 0.76f, h * 0.52f))
    drawRect(Brush.verticalGradient(listOf(body, Color(0xFF2B3440))), Offset(w * 0.14f, h * 0.42f), Size(w * 0.72f, h * 0.48f), alpha = a)
    val roofPath = Path().apply {
        moveTo(w * 0.06f, h * 0.44f); lineTo(w * 0.22f, h * 0.22f); lineTo(w * 0.78f, h * 0.22f); lineTo(w * 0.94f, h * 0.44f); close()
    }
    drawPath(roofPath, roof.copy(alpha = a))
    drawPath(roofPath, Outline.copy(alpha = a), style = Stroke(1.5f))
}

private fun DrawScope.gear(c: Offset, r: Float, a: Float) {
    for (k in 0 until 8) {
        val ang = Math.toRadians(k * 45.0)
        drawLine(Color(0xFFB4C0CE).copy(alpha = a), c, Offset(c.x + cos(ang).toFloat() * r * 1.25f, c.y + sin(ang).toFloat() * r * 1.25f), r * 0.45f)
    }
    drawCircle(Color(0xFFB4C0CE).copy(alpha = a), r, c)
    drawCircle(Color(0xFF2B3440).copy(alpha = a), r * 0.45f, c)
}

private fun DrawScope.workshopIcon(a: Float) {
    hut(a, BollwerkColors.Rust, Color(0xFF4A5666))
    val w = size.width
    val h = size.height
    gear(Offset(w * 0.34f, h * 0.66f), u(0.11f), a)
    drawRect(BollwerkColors.Hazard.copy(alpha = a), Offset(w * 0.56f, h * 0.54f), Size(w * 0.22f, h * 0.16f))
    drawLine(Outline.copy(alpha = a), Offset(w * 0.67f, h * 0.54f), Offset(w * 0.67f, h * 0.70f), 1.5f)
}

private fun DrawScope.armouryIcon(a: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(Outline.copy(alpha = a), Offset(w * 0.1f, h * 0.36f), Size(w * 0.8f, h * 0.56f), CornerRadius(u(0.08f)))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF55606E), Color(0xFF2B3440))), Offset(w * 0.12f, h * 0.38f), Size(w * 0.76f, h * 0.52f), CornerRadius(u(0.07f)), alpha = a)
    drawRect(Color(0xFF0D1116).copy(alpha = a), Offset(w * 0.3f, h * 0.55f), Size(w * 0.4f, h * 0.08f))
    drawLine(Color(0xFFB4C0CE).copy(alpha = a), Offset(w * 0.5f, h * 0.36f), Offset(w * 0.5f, h * 0.08f), u(0.04f))
    drawRect(BollwerkColors.TeamRed.copy(alpha = a), Offset(w * 0.5f, h * 0.08f), Size(w * 0.2f, h * 0.12f))
    drawRect(BollwerkColors.Hazard.copy(alpha = a), Offset(w * 0.16f, h * 0.72f), Size(w * 0.14f, h * 0.14f))
    drawRect(BollwerkColors.Hazard.copy(alpha = a), Offset(w * 0.70f, h * 0.72f), Size(w * 0.14f, h * 0.14f))
}

private fun DrawScope.upgradeIcon(a: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF55606E), Color(0xFF2B3440))), Offset(w * 0.14f, h * 0.5f), Size(w * 0.5f, h * 0.42f), alpha = a)
    // Kran
    drawLine(BollwerkColors.Rust.copy(alpha = a), Offset(w * 0.72f, h * 0.92f), Offset(w * 0.72f, h * 0.12f), u(0.06f))
    drawLine(BollwerkColors.Rust.copy(alpha = a), Offset(w * 0.44f, h * 0.14f), Offset(w * 0.9f, h * 0.14f), u(0.06f))
    drawLine(Color(0xFFB4C0CE).copy(alpha = a), Offset(w * 0.48f, h * 0.14f), Offset(w * 0.48f, h * 0.34f), u(0.025f))
    // Pfeil
    val p = Path().apply {
        moveTo(w * 0.39f, h * 0.36f); lineTo(w * 0.52f, h * 0.52f); lineTo(w * 0.44f, h * 0.52f); lineTo(w * 0.44f, h * 0.72f)
        lineTo(w * 0.34f, h * 0.72f); lineTo(w * 0.34f, h * 0.52f); lineTo(w * 0.26f, h * 0.52f); close()
    }
    drawPath(p, BollwerkColors.Hazard.copy(alpha = a))
}

private fun DrawScope.factoryIcon(a: Float) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * 0.08f, h * 0.92f); lineTo(w * 0.08f, h * 0.5f); lineTo(w * 0.3f, h * 0.36f); lineTo(w * 0.3f, h * 0.5f)
        lineTo(w * 0.52f, h * 0.36f); lineTo(w * 0.52f, h * 0.5f); lineTo(w * 0.74f, h * 0.36f); lineTo(w * 0.74f, h * 0.92f); close()
    }
    drawPath(p, Color(0xFF4A5666).copy(alpha = a))
    drawPath(p, Outline.copy(alpha = a), style = Stroke(1.5f))
    drawRect(Color(0xFF6B7078).copy(alpha = a), Offset(w * 0.78f, h * 0.18f), Size(w * 0.1f, h * 0.74f))
    drawCircle(Color(0xFF8A8F98).copy(alpha = a * 0.6f), u(0.08f), Offset(w * 0.85f, h * 0.1f))
    drawRect(BollwerkColors.Hazard.copy(alpha = a), Offset(w * 0.16f, h * 0.62f), Size(w * 0.12f, h * 0.1f))
    drawRect(BollwerkColors.Rust.copy(alpha = a), Offset(w * 0.42f, h * 0.62f), Size(w * 0.12f, h * 0.1f))
}

private fun DrawScope.base(a: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(Outline.copy(alpha = a), Offset(w * 0.24f, h * 0.76f), Size(w * 0.52f, h * 0.14f), CornerRadius(3f))
    drawRoundRect(Color(0xFF55606E).copy(alpha = a), Offset(w * 0.26f, h * 0.78f), Size(w * 0.48f, h * 0.1f), CornerRadius(3f))
}

private fun DrawScope.barrel(pivot: Offset, angleDeg: Float, len: Float, thick: Float, a: Float, color: Color = Color(0xFF6A7584)) {
    rotate(-angleDeg, pivot) {
        drawRoundRect(Outline.copy(alpha = a), Offset(pivot.x - thick * 0.3f, pivot.y - thick / 2 - 1.5f), Size(len + 3f, thick + 3f), CornerRadius(thick * 0.3f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFB4C0CE), color, Color(0xFF2B3440)), pivot.y - thick / 2, pivot.y + thick / 2), Offset(pivot.x - thick * 0.3f + 1.5f, pivot.y - thick / 2), Size(len, thick), CornerRadius(thick * 0.3f), alpha = a)
    }
}

private fun DrawScope.mortarIcon(a: Float) {
    base(a)
    val pivot = Offset(size.width * 0.42f, size.height * 0.68f)
    barrel(pivot, 52f, u(0.5f), u(0.24f), a)
    rotate(-52f, pivot) { drawRect(BollwerkColors.Rust.copy(alpha = a), Offset(pivot.x + u(0.12f), pivot.y - u(0.13f)), Size(u(0.07f), u(0.26f))) }
    drawCircle(Outline.copy(alpha = a), u(0.11f), pivot)
    drawCircle(Color(0xFF9AA6B4).copy(alpha = a), u(0.08f), pivot)
}

private fun DrawScope.cannonIcon(a: Float) {
    val pivot = Offset(size.width * 0.38f, size.height * 0.56f)
    barrel(pivot, 18f, u(0.58f), u(0.17f), a)
    val wheel = Offset(size.width * 0.36f, size.height * 0.72f)
    drawCircle(Outline.copy(alpha = a), u(0.19f), wheel)
    drawCircle(BollwerkColors.Wood.copy(alpha = a), u(0.16f), wheel)
    for (k in 0 until 6) {
        val ang = Math.toRadians(k * 60.0)
        drawLine(BollwerkColors.WoodDark.copy(alpha = a), wheel, Offset(wheel.x + cos(ang).toFloat() * u(0.15f), wheel.y + sin(ang).toFloat() * u(0.15f)), 2f)
    }
    drawCircle(BollwerkColors.Rust.copy(alpha = a), u(0.05f), wheel)
}

private fun DrawScope.mgIcon(a: Float) {
    val w = size.width
    val h = size.height
    val c = Color(0xFF9AA6B4).copy(alpha = a)
    drawLine(c, Offset(w * 0.45f, h * 0.5f), Offset(w * 0.22f, h * 0.9f), u(0.05f))
    drawLine(c, Offset(w * 0.45f, h * 0.5f), Offset(w * 0.68f, h * 0.9f), u(0.05f))
    drawRoundRect(Color(0xFF3F4955).copy(alpha = a), Offset(w * 0.24f, h * 0.36f), Size(w * 0.34f, h * 0.18f), CornerRadius(3f))
    drawLine(Color(0xFF55606E).copy(alpha = a), Offset(w * 0.55f, h * 0.43f), Offset(w * 0.95f, h * 0.43f), u(0.06f))
    drawRect(Color(0xFF6E7A3A).copy(alpha = a), Offset(w * 0.30f, h * 0.30f), Size(w * 0.14f, h * 0.07f))
}

private fun DrawScope.sniperIcon(a: Float) {
    val w = size.width
    val h = size.height
    val c = Color(0xFF9AA6B4).copy(alpha = a)
    drawLine(c, Offset(w * 0.4f, h * 0.56f), Offset(w * 0.26f, h * 0.9f), u(0.04f))
    drawLine(c, Offset(w * 0.4f, h * 0.56f), Offset(w * 0.56f, h * 0.9f), u(0.04f))
    rotate(-14f, Offset(w * 0.4f, h * 0.55f)) {
        drawRoundRect(BollwerkColors.WoodDark.copy(alpha = a), Offset(w * 0.08f, h * 0.5f), Size(w * 0.36f, h * 0.12f), CornerRadius(3f))
        drawLine(Color(0xFF55606E).copy(alpha = a), Offset(w * 0.4f, h * 0.55f), Offset(w * 0.98f, h * 0.55f), u(0.045f))
        drawRoundRect(Color(0xFF2B3440).copy(alpha = a), Offset(w * 0.36f, h * 0.38f), Size(w * 0.26f, h * 0.09f), CornerRadius(4f))
        drawCircle(BollwerkColors.Energy.copy(alpha = a), u(0.04f), Offset(w * 0.62f, h * 0.425f))
    }
}

private fun DrawScope.rocketIcon(a: Float) {
    val w = size.width
    val h = size.height
    drawLine(Color(0xFF9AA6B4).copy(alpha = a), Offset(w * 0.3f, h * 0.9f), Offset(w * 0.5f, h * 0.55f), u(0.05f))
    for (k in 0 until 3) {
        val o = k * u(0.09f)
        translate(o, o) {
            rotate(-38f, Offset(w * 0.36f, h * 0.6f)) {
                drawRoundRect(Color(0xFF3F4955).copy(alpha = a), Offset(w * 0.2f, h * 0.56f), Size(w * 0.62f, u(0.08f)), CornerRadius(2f))
                drawRect(BollwerkColors.TeamRed.copy(alpha = a), Offset(w * 0.78f, h * 0.56f), Size(w * 0.08f, u(0.08f)))
            }
        }
    }
    drawLine(BollwerkColors.Hazard.copy(alpha = a), Offset(w * 0.5f, h * 0.8f), Offset(w * 0.85f, h * 0.8f), u(0.04f))
}

private fun DrawScope.laserIcon(a: Float) {
    base(a)
    val pivot = Offset(size.width * 0.44f, size.height * 0.62f)
    barrel(pivot, 30f, u(0.4f), u(0.26f), a, Color(0xFF55606E))
    rotate(-30f, pivot) {
        drawCircle(BollwerkColors.Energy.copy(alpha = a), u(0.12f), Offset(pivot.x + u(0.42f), pivot.y))
        drawCircle(Color.White.copy(alpha = a * 0.7f), u(0.05f), Offset(pivot.x + u(0.40f), pivot.y - u(0.03f)))
    }
}

private fun DrawScope.reactorIcon(a: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(Color(0xFF55606E).copy(alpha = a), Offset(w * 0.12f, h * 0.18f), Size(w * 0.76f, h * 0.7f), CornerRadius(4f))
    drawCircle(Outline.copy(alpha = a), u(0.24f), Offset(w * 0.5f, h * 0.5f))
    drawCircle(BollwerkColors.Energy.copy(alpha = a), u(0.18f), Offset(w * 0.5f, h * 0.5f))
}
