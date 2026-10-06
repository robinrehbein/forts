package de.bollwerk.app.ui.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import de.bollwerk.app.ui.theme.BollwerkColors
import kotlin.math.PI
import kotlin.math.abs

/** Deterministischer Pseudo-Zufall 0..1 aus einem Int (nur für Deko, kein Sim-Pfad). */
internal fun hash01(i: Int): Float {
    var x = i * -0x61c88647 + 0x7f4a7c15
    x = x xor (x ushr 15)
    x *= -0x7a143595
    x = x xor (x ushr 13)
    x *= -0x3d4d51cb
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16777215f
}

private fun tri(x: Float): Float {
    val m = ((x % 2f) + 2f) % 2f
    return abs(m - 1f)
}

/** Abenddämmerungs-Himmel (Stil-Bibel §2) über die ganze Fläche. */
fun DrawScope.drawDuskSky(bottom: Float = size.height) {
    drawRect(
        Brush.verticalGradient(BollwerkColors.Sky, startY = 0f, endY = bottom),
        size = Size(size.width, bottom),
    )
}

/** Sterne nur im oberen Drittel. */
fun DrawScope.drawStars(count: Int = 40, seed: Int = 7) {
    for (i in 0 until count) {
        val x = hash01(seed + i * 3) * size.width
        val y = hash01(seed + i * 3 + 1) * size.height * 0.33f
        val a = 0.25f + 0.5f * hash01(seed + i * 3 + 2)
        drawCircle(BollwerkColors.Text.copy(alpha = a), 0.8f + 1.2f * hash01(seed + i), Offset(x, y))
    }
}

/** Sonne mit weichem Halo. */
fun DrawScope.drawSun(center: Offset, radius: Float) {
    drawCircle(
        Brush.radialGradient(listOf(BollwerkColors.Sun.copy(alpha = 0.5f), Color.Transparent), center, radius * 4.2f),
        radius * 4.2f, center,
    )
    drawCircle(BollwerkColors.Sun, radius, center)
}

/** Eine Bergebene (Silhouette mit spitzen Gipfeln) von [baseY] bis zum unteren Rand. */
fun DrawScope.drawMountainLayer(baseY: Float, amp: Float, freq: Float, phase: Float, color: Color, haze: Color? = null) {
    val p = Path()
    p.moveTo(0f, size.height)
    val steps = 56
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val h = 0.55f * tri(t * freq + phase) + 0.30f * tri(t * freq * 2.3f + phase * 1.7f) + 0.15f * tri(t * freq * 5.1f)
        p.lineTo(size.width * t, baseY - amp * h)
    }
    p.lineTo(size.width, size.height)
    p.close()
    drawPath(p, color)
    if (haze != null) {
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, haze), baseY - amp * 0.4f, baseY + amp * 0.3f),
            Offset(0f, baseY - amp * 0.4f), Size(size.width, amp * 0.7f),
        )
    }
}

/** Drei Bergebenen: fern heller/violetter, nah dunkler. */
fun DrawScope.drawMountains(horizon: Float, amp: Float) {
    drawMountainLayer(horizon, amp, 3.1f, 0.4f, BollwerkColors.MountainFar, BollwerkColors.SkyHaze)
    drawMountainLayer(horizon + amp * 0.35f, amp * 0.85f, 4.3f, 1.1f, BollwerkColors.MountainMid, BollwerkColors.SkyHaze.copy(alpha = 0.25f))
    drawMountainLayer(horizon + amp * 0.75f, amp * 0.7f, 5.7f, 2.3f, BollwerkColors.MountainNear)
}

/** Langgestrecktes, flach schattiertes Wolkenband (2 Töne + helle Oberkante). */
fun DrawScope.drawCloudBand(center: Offset, width: Float, height: Float, body: Color, light: Color) {
    val p = Path().apply {
        moveTo(center.x - width / 2, center.y)
        cubicTo(center.x - width * 0.30f, center.y - height, center.x + width * 0.10f, center.y - height * 0.9f, center.x + width / 2, center.y)
        cubicTo(center.x + width * 0.20f, center.y + height * 0.45f, center.x - width * 0.25f, center.y + height * 0.5f, center.x - width / 2, center.y)
        close()
    }
    drawPath(p, body)
    drawPath(
        Path().apply {
            moveTo(center.x - width * 0.45f, center.y - height * 0.15f)
            cubicTo(center.x - width * 0.28f, center.y - height * 0.85f, center.x + width * 0.08f, center.y - height * 0.8f, center.x + width * 0.45f, center.y - height * 0.1f)
        },
        light, style = Stroke(height * 0.12f, cap = StrokeCap.Round),
    )
}

/** Dekoratives Gras auf einer Kante. */
fun DrawScope.drawGrassEdge(x0: Float, x1: Float, y: Float, thickness: Float) {
    if (thickness < 0.5f || x1 <= x0) return
    drawRect(Color(0xFF5E7A3A), Offset(x0, y - thickness * 0.3f), Size(x1 - x0, thickness))
    val n = ((x1 - x0) / (thickness * 1.6f)).toInt().coerceAtMost(400)
    for (i in 0 until n) {
        val x = x0 + (i + hash01(i + 90)) * (x1 - x0) / n
        drawLine(Color(0xFF7C9A4A), Offset(x, y - thickness * 0.3f), Offset(x + thickness * 0.2f, y - thickness * (0.9f + hash01(i))), thickness * 0.15f)
    }
}

/** Darstellungsart der Festung. */
enum class FortStyle {
    /** Dunkle Silhouette mit warmer Randlinie (Hauptmenü). */
    Silhouette,
    /** Holz-/Metallbalken wie im Spiel, vereinfacht (Karten-Thumbnails). */
    Beams,
    /** Eine Farbe (Hotseat-Übergabe). */
    Mono,
}

private class Seg(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

private val FORT_SEGMENTS: List<Seg> by lazy {
    val s = ArrayList<Seg>()
    // Turm 3 m x 9 m mit X-Verstrebung je 3 m
    s += Seg(0f, 0f, 0f, 9f); s += Seg(3f, 0f, 3f, 9f)
    for (i in 0..3) s += Seg(0f, i * 3f, 3f, i * 3f)
    for (i in 0 until 3) {
        s += Seg(0f, i * 3f, 3f, i * 3f + 3f)
        s += Seg(3f, i * 3f, 0f, i * 3f + 3f)
    }
    // Flügel: Untergurt, schräger Obergurt, Pfosten, Diagonalen
    s += Seg(3f, 0f, 11f, 0f)
    s += Seg(3f, 5f, 11f, 0.6f)
    for (x in listOf(5f, 7f, 9f, 11f)) s += Seg(x, 0f, x, 5f - (x - 3f) * 0.55f)
    s += Seg(3f, 0f, 5f, 3.9f); s += Seg(5f, 0f, 7f, 3.1f); s += Seg(7f, 0f, 9f, 2.0f)
    s
}

/** Ausdehnung einer Festung in Metern (Breite, Höhe inkl. Windrad). */
const val FORT_WIDTH_M = 11f
const val FORT_HEIGHT_M = 14f

/**
 * Zeichnet eine Truss-Festung (Turm + Flügel + Kanone + Windrad + Fahne).
 * [base] = unten außen am Turm, [s] = Pixel pro Meter, [mirror] = Flügel zeigt nach links.
 */
fun DrawScope.drawFort(
    base: Offset,
    s: Float,
    mirror: Boolean,
    style: FortStyle,
    flag: Color = BollwerkColors.TeamBlue,
    mono: Color = BollwerkColors.Dark,
    bladeAngle: Float = 20f,
    withDevices: Boolean = true,
    withBlades: Boolean = true,
) {
    val dir = if (mirror) -1f else 1f
    fun pt(x: Float, y: Float) = Offset(base.x + dir * x * s, base.y - y * s)
    val rim = BollwerkColors.WoodLight
    val beamW = when (style) { FortStyle.Beams -> 0.34f; else -> 0.38f } * s
    val inner = when (style) {
        FortStyle.Silhouette -> Color(0xFF181421)
        FortStyle.Beams -> BollwerkColors.Wood
        FortStyle.Mono -> mono
    }
    val glow = when (style) {
        FortStyle.Silhouette -> BollwerkColors.FireInner.copy(alpha = 0.75f)
        FortStyle.Beams -> BollwerkColors.WoodDark
        FortStyle.Mono -> mono
    }
    for (seg in FORT_SEGMENTS) {
        val a = pt(seg.x1, seg.y1); val b = pt(seg.x2, seg.y2)
        if (style != FortStyle.Mono) drawLine(glow, a, b, beamW * 1.45f, StrokeCap.Butt)
    }
    for (seg in FORT_SEGMENTS) {
        val a = pt(seg.x1, seg.y1); val b = pt(seg.x2, seg.y2)
        drawLine(inner, a, b, beamW, StrokeCap.Butt)
        if (style == FortStyle.Beams) drawLine(rim.copy(alpha = 0.7f), a, b, beamW * 0.25f, StrokeCap.Butt)
    }
    // Knoten
    val nodeColor = if (style == FortStyle.Beams) BollwerkColors.Steel else inner
    for (seg in FORT_SEGMENTS) {
        drawCircle(nodeColor, 0.3f * s, pt(seg.x1, seg.y1))
        drawCircle(nodeColor, 0.3f * s, pt(seg.x2, seg.y2))
    }
    if (!withDevices) return
    val devColor = if (style == FortStyle.Mono) mono else Color(0xFF14101C)
    // Kanone auf dem Flügel
    drawLine(devColor, pt(4.2f, 3.2f), pt(6.6f, 5.4f), 0.9f * s, StrokeCap.Round)
    drawCircle(devColor, 0.7f * s, pt(4.2f, 3.1f))
    if (style == FortStyle.Silhouette) drawCircle(Color(0xFFFFE9B0), 0.28f * s, pt(6.7f, 5.5f))
    // MG-Aufsatz auf dem Turm
    drawRect(devColor, if (mirror) pt(2.9f, 9.9f) else pt(0.1f, 9.9f), Size(2.8f * s, 0.9f * s))
    // Windrad (Mast; die drehenden Blätter zeichnet [drawFortBlades], damit sie getrennt animierbar sind)
    val hub = pt(1.5f, 13.2f)
    drawLine(devColor, pt(1.5f, 9.9f), hub, 0.22f * s, StrokeCap.Round)
    if (withBlades) drawFortBlades(base, s, mirror, style, mono, bladeAngle)
    // Fahne am Flügelende
    val poleBase = pt(10.6f, 0.6f)
    val poleTop = pt(10.6f, 4.2f)
    drawLine(devColor, poleBase, poleTop, 0.16f * s, StrokeCap.Round)
    val fc = if (style == FortStyle.Mono) mono else flag
    drawPath(
        Path().apply {
            moveTo(poleTop.x, poleTop.y)
            lineTo(poleTop.x + dir * 1.9f * s, poleTop.y + 0.35f * s)
            lineTo(poleTop.x, poleTop.y + 1.1f * s)
            close()
        },
        fc,
    )
}

/** Nur die Windrad-Blätter samt Nabe einer Festung (gleiche Geometrie wie in [drawFort]); billig genug für Animation je Frame. */
fun DrawScope.drawFortBlades(
    base: Offset,
    s: Float,
    mirror: Boolean,
    style: FortStyle,
    mono: Color = BollwerkColors.Dark,
    bladeAngle: Float = 20f,
) {
    val dir = if (mirror) -1f else 1f
    val hub = Offset(base.x + dir * 1.5f * s, base.y - 13.2f * s)
    val devColor = if (style == FortStyle.Mono) mono else Color(0xFF14101C)
    for (k in 0 until 3) {
        rotate(bladeAngle + k * 120f, hub) {
            drawLine(devColor, hub, Offset(hub.x, hub.y - 3.1f * s), 0.3f * s, StrokeCap.Round)
        }
    }
    drawCircle(devColor, 0.3f * s, hub)
}

/** Gepunktete Flugbahn (quadratische Bézier-Kurve), Punkte werden zum Ende kleiner; Rückgabe = Geschossposition. */
fun DrawScope.drawShellArc(p0: Offset, p1: Offset, p2: Offset, dots: Int, maxRadius: Float, color: Color, shellT: Float): Pair<Offset, Float> {
    fun at(t: Float): Offset {
        val u = 1f - t
        return Offset(u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x, u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y)
    }
    for (i in 1..dots) {
        val t = i.toFloat() / dots
        if (t > shellT - 0.02f && t < shellT + 0.03f) continue
        val r = maxRadius * (1f - 0.7f * t)
        drawCircle(color.copy(alpha = 0.85f * (1f - 0.4f * t)), r, at(t))
    }
    val pos = at(shellT)
    val a = at(shellT - 0.01f); val b = at(shellT + 0.01f)
    val ang = (kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()) * 180.0 / PI).toFloat()
    return pos to ang
}

/** Kleines Geschoss (Rost-Spitze, dunkler Körper) in Flugrichtung [angleDeg]. */
fun DrawScope.drawShell(pos: Offset, angleDeg: Float, len: Float) {
    rotate(angleDeg, pos) {
        drawRoundRect(Color(0xFF2B2430), Offset(pos.x - len / 2, pos.y - len * 0.22f), Size(len, len * 0.44f), androidx.compose.ui.geometry.CornerRadius(len * 0.2f))
        drawRoundRect(BollwerkColors.Rust, Offset(pos.x + len * 0.1f, pos.y - len * 0.22f), Size(len * 0.4f, len * 0.44f), androidx.compose.ui.geometry.CornerRadius(len * 0.2f))
    }
}

/** Rauchsäule aus überlappenden grauen Kreisen. */
fun DrawScope.drawSmoke(base: Offset, r: Float, puffs: Int, drift: Float) {
    for (i in 0 until puffs) {
        val t = i.toFloat() / puffs
        val c = Offset(base.x + drift * t * r * 3f + (hash01(i + 3) - 0.5f) * r * 0.6f, base.y - t * r * 5.5f)
        drawCircle(BollwerkColors.SmokeOld.copy(alpha = 0.85f - 0.6f * t), r * (0.7f + 0.9f * t), c)
    }
}

