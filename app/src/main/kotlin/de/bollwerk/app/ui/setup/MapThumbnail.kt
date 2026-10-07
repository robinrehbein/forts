package de.bollwerk.app.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.bollwerk.app.R
import de.bollwerk.app.match.MapOption
import de.bollwerk.app.ui.art.FortStyle
import de.bollwerk.app.ui.art.drawDuskSky
import de.bollwerk.app.ui.art.drawFort
import de.bollwerk.app.ui.art.drawGrassEdge
import de.bollwerk.app.ui.art.drawMountains
import de.bollwerk.app.ui.art.drawSun
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkType
import androidx.compose.foundation.shape.RoundedCornerShape

/** Gezeichnete Karten-Vorschau (Mockup 2): Himmel, Berge, Gelände, zwei Festungen, Tiefenangabe. */
@Composable
fun MapThumbnail(map: MapOption, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawDuskSky()
            drawSun(Offset(w * 0.5f, h * 0.55f), h * 0.07f)
            drawMountains(h * 0.50f, h * 0.22f)
            val earthTop = Color(0xFF7A573E)
            val earthBottom = Color(0xFF3A2A2E)
            val top = h * 0.70f
            val s = w * 0.0105f
            when (map) {
                MapOption.SCHLUCHT -> {
                    val cl = w * 0.30f
                    val cr = w * 0.70f
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF3B2F52), Color(0xFF1E1A2E)), top, h), Offset(cl, top), Size(cr - cl, h - top))
                    drawRect(Brush.verticalGradient(listOf(earthTop, earthBottom), top, h), Offset(0f, top), Size(cl, h - top))
                    drawRect(Brush.verticalGradient(listOf(earthTop, earthBottom), top, h), Offset(cr, top), Size(w - cr, h - top))
                    drawGrassEdge(0f, cl, top, h * 0.025f)
                    drawGrassEdge(cr, w, top, h * 0.025f)
                    for (i in 1..3) drawLine(Color.Black.copy(alpha = 0.2f), Offset(0f, top + (h - top) * i / 4f), Offset(cl, top + (h - top) * i / 4f), 2f)
                    for (i in 1..3) drawLine(Color.Black.copy(alpha = 0.2f), Offset(cr, top + (h - top) * i / 4f), Offset(w, top + (h - top) * i / 4f), 2f)
                    drawFort(Offset(w * 0.03f, top), s, false, FortStyle.Beams, BollwerkColors.TeamBlue, bladeAngle = 25f)
                    drawFort(Offset(w * 0.97f, top), s, true, FortStyle.Beams, BollwerkColors.TeamRed, bladeAngle = 70f)
                }
                MapOption.HUEGEL -> {
                    // Geländekante: Sinus-Mulde zwischen 28 % und 72 % der Breite. Boden und Gras nutzen
                    // dieselben Stützpunkte, damit das Gras nirgends über dem Boden schwebt.
                    val dip = (0..22).map { k ->
                        val t = 0.28f + 0.44f * k / 22f
                        Offset(w * t, top + kotlin.math.sin(k / 22f * Math.PI.toFloat()) * h * 0.09f)
                    }
                    val ground = Path().apply {
                        moveTo(0f, top)
                        for (p in dip) lineTo(p.x, p.y)
                        lineTo(w, top); lineTo(w, h); lineTo(0f, h); close()
                    }
                    drawPath(ground, Brush.verticalGradient(listOf(earthTop, earthBottom), top, h))
                    drawGrassEdge(0f, w * 0.28f, top, h * 0.025f)
                    drawGrassEdge(w * 0.72f, w, top, h * 0.025f)
                    val dipGrass = Path().apply {
                        moveTo(dip.first().x, dip.first().y)
                        for (p in dip) lineTo(p.x, p.y)
                    }
                    drawPath(dipGrass, Color(0xFF7C9A4A), style = androidx.compose.ui.graphics.drawscope.Stroke(h * 0.02f))
                    drawFort(Offset(w * 0.03f, top), s, false, FortStyle.Beams, BollwerkColors.TeamBlue, bladeAngle = 25f)
                    drawFort(Offset(w * 0.97f, top), s, true, FortStyle.Beams, BollwerkColors.TeamRed, bladeAngle = 70f)
                }
            }
        }
        Text(
            stringResource(R.string.map_depth_label, map.depthMeters),
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (map == MapOption.SCHLUCHT) 6.dp else 18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            style = BollwerkType.LabelSmall,
            color = BollwerkColors.Muted,
        )
    }
}
