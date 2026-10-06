package de.bollwerk.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkType
import androidx.compose.foundation.shape.RoundedCornerShape

/** Blaupausen-Hintergrund der Nicht-Spiel-Screens: dunkler Verlauf, feines Raster, Vignette (Mockups 2/7). */
@Composable
fun BlueprintBackground(
    modifier: Modifier = Modifier,
    tint: Color = BollwerkColors.TeamBlue,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF10151B), Color(0xFF18212B), Color(0xFF10151B))))
            .drawBehind {
                val step = 24.dp.toPx()
                val line = Color.White.copy(alpha = 0.035f)
                var x = 0f
                while (x < size.width) {
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }
                drawRect(
                    Brush.radialGradient(
                        listOf(tint.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.25f),
                        radius = size.width * 0.7f,
                    ),
                )
            },
    ) { content() }
}

/** Außenabstand für Edge-to-Edge: Display-Cutout/Systemleisten plus Grundrand. */
@Composable
fun Modifier.screenPadding(base: Dp = 24.dp): Modifier =
    this
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(horizontal = base, vertical = base * 0.6f)

/** Dunkle Karte mit feiner Stahl-Kante (Panels, Tipp-Karte, Statistik). */
@Composable
fun SteelPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    fill: Color = BollwerkColors.PanelFill.copy(alpha = 0.92f),
    border: Color = BollwerkColors.SteelHi.copy(alpha = 0.28f),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .padding(contentPadding),
    ) { content() }
}

/** Versalien-Label mit weiter Laufweite (Abschnittsüberschrift). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = BollwerkColors.SteelHi) {
    Text(text.uppercase(), modifier, style = BollwerkType.Label, color = color)
}

/** Kleiner Chip mit dunklem Rahmen (BREITE 120 M, WIND MITTEL, 400 ⚙ 200 ⚡). */
@Composable
fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = BollwerkColors.Text,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, BollwerkColors.SteelHi.copy(alpha = 0.3f), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text.uppercase(),
            style = BollwerkType.LabelSmall,
            color = color, maxLines = 1, softWrap = false,
        )
    }
}

/** Kopfzeile: Zurück-Button, Bebas-Titel, Untertitel (Mockups 2 und Einstellungen). */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        IndustrialIconButton(painterResource(R.drawable.ic_back), stringResource(R.string.cd_back), onBack)
        Column {
            Text(title.uppercase(), style = BollwerkType.Display, color = BollwerkColors.Text)
            if (subtitle != null) {
                Text(subtitle.uppercase(), style = BollwerkType.Label, color = BollwerkColors.SteelHi)
            }
        }
    }
}
