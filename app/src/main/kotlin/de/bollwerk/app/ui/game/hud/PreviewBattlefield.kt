package de.bollwerk.app.ui.game.hud

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import de.bollwerk.app.ui.art.FortStyle
import de.bollwerk.app.ui.art.drawCloudBand
import de.bollwerk.app.ui.art.drawDuskSky
import de.bollwerk.app.ui.art.drawFort
import de.bollwerk.app.ui.art.drawGrassEdge
import de.bollwerk.app.ui.art.drawMountains
import de.bollwerk.app.ui.art.drawStars
import de.bollwerk.app.ui.art.drawSun
import de.bollwerk.app.ui.theme.BollwerkColors

/**
 * Statische Schlacht-Kulisse (Himmel, Berge, zwei Festungen) als Hintergrund für HUD-Vorschauen und Snapshot-Tests,
 * an Stelle der echten Spielfläche (die nur mit Render-Thread und Surface existiert).
 */
@Composable
fun PreviewBattlefield(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawDuskSky()
        drawStars(40)
        drawSun(Offset(w * 0.55f, h * 0.62f), h * 0.06f)
        val cloud = Color(0xFFB4698A).copy(alpha = 0.85f)
        val light = Color(0xFFF2B26B).copy(alpha = 0.8f)
        drawCloudBand(Offset(w * 0.36f, h * 0.22f), w * 0.24f, h * 0.05f, cloud, light)
        drawCloudBand(Offset(w * 0.75f, h * 0.27f), w * 0.30f, h * 0.05f, cloud, light)
        drawCloudBand(Offset(w * 0.55f, h * 0.44f), w * 0.30f, h * 0.04f, cloud, light)
        drawMountains(h * 0.72f, h * 0.2f)
        val ground = h * 0.78f
        val earth = Brush.verticalGradient(listOf(Color(0xFF6E4E36), Color(0xFF2E2226)), ground, h)
        drawRect(earth, Offset(0f, ground), Size(w * 0.37f, h - ground))
        drawRect(earth, Offset(w * 0.63f, ground), Size(w * 0.37f, h - ground))
        drawGrassEdge(0f, w * 0.37f, ground, h * 0.012f)
        drawGrassEdge(w * 0.63f, w, ground, h * 0.012f)
        val s = w * 0.022f
        drawFort(Offset(w * 0.07f, ground), s, mirror = false, style = FortStyle.Beams, flag = BollwerkColors.TeamBlue)
        drawFort(Offset(w * 0.93f, ground), s, mirror = true, style = FortStyle.Beams, flag = BollwerkColors.TeamRed)
    }
}
