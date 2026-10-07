package de.bollwerk.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkType

/** Abdunkelung hinter Dialogen; schluckt Taps, damit darunterliegende Elemente nicht reagieren. */
@Composable
fun Scrim(modifier: Modifier = Modifier, alpha: Float = 0.62f, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = alpha))
            .consumeTaps()
            .screenPadding(16.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Dialog im Industrie-Stil: Titel (Bebas), Inhalt, Aktionen unten. */
@Composable
fun IndustrialDialog(
    title: String,
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = 460.dp,
    actions: @Composable RowScope.() -> Unit,
    body: @Composable () -> Unit,
) {
    Scrim {
        SteelPanel(
            modifier.widthIn(max = maxWidth).heightIn(max = 340.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            fill = BollwerkColors.PanelFill,
            border = BollwerkColors.SteelHi.copy(alpha = 0.45f),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title.uppercase(), style = BollwerkType.Title, color = BollwerkColors.Text)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { body() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
            }
        }
    }
}

/** Fließtext in Rajdhani 500 (mindestens 12 sp, Stil-Bibel §3). */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, color: Color = BollwerkColors.Muted) {
    Text(text, modifier.padding(vertical = 2.dp), style = BollwerkType.Body, color = color)
}
