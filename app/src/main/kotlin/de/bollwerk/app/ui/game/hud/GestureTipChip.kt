package de.bollwerk.app.ui.game.hud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.settings.GestureTip
import de.bollwerk.app.ui.theme.BollwerkColors

/** Text eines einmaligen Gesten-Hinweises. */
fun gestureTipRes(tip: GestureTip): Int = when (tip) {
    GestureTip.PINCH_ZOOM -> R.string.tip_pinch_zoom
    GestureTip.DOUBLE_TAP -> R.string.tip_double_tap
    GestureTip.LONG_PRESS -> R.string.tip_long_press
}

/**
 * Einmaliger, nicht blockierender Gesten-Hinweis: Glühbirne, Text, Schließen-Kreuz; Tippen irgendwo auf den Chip schließt
 * ihn (48 dp hoch). Die Spielfläche daneben bleibt voll bedienbar.
 */
@Composable
fun GestureTipChip(tip: GestureTip, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val text = stringResource(gestureTipRes(tip))
    Row(
        modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .widthIn(max = 380.dp)
            .clip(shape)
            .background(Color(0xEE1B2733))
            .border(1.5.dp, BollwerkColors.Energy.copy(alpha = 0.8f), shape)
            .hudClickable(onDismiss, stringResource(R.string.tip_dismiss_cd))
            .sizeIn(minHeight = 48.dp)
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(painterResource(R.drawable.ic_bulb), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(BollwerkColors.Hazard))
        Text(text, Modifier.weight(1f, fill = false), style = HudType.Small.copy(fontSize = 14.sp, lineHeight = 17.sp), color = BollwerkColors.Text, maxLines = 2)
        Image(painterResource(R.drawable.ic_close), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(HudColors.Label))
    }
}
