package de.bollwerk.app.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.ui.components.BodyText
import de.bollwerk.app.ui.components.ButtonStyle
import de.bollwerk.app.ui.components.IndustrialButton
import de.bollwerk.app.ui.components.IndustrialDialog
import de.bollwerk.app.ui.components.Scrim
import de.bollwerk.app.ui.components.SteelPanel
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkType

/** Pause: Fortsetzen groß, darunter Neustart, Aufgeben, Einstellungen, Hauptmenü im Raster. */
@Composable
fun PauseDialog(
    onResume: () -> Unit,
    onRequestConfirm: (PauseAction) -> Unit,
    onSettings: () -> Unit,
) {
    Scrim {
        SteelPanel(
            Modifier.widthIn(max = 460.dp),
            contentPadding = PaddingValues(20.dp),
            fill = BollwerkColors.PanelFill,
            border = BollwerkColors.SteelHi.copy(alpha = 0.45f),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.pause_title).uppercase(),
                    Modifier.semantics { heading() },
                    style = BollwerkType.Title, color = BollwerkColors.Text,
                )
                IndustrialButton(
                    stringResource(R.string.pause_resume), onResume, Modifier.fillMaxWidth(),
                    style = ButtonStyle.Primary, icon = painterResource(R.drawable.ic_play), minHeight = 52.dp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IndustrialButton(
                        stringResource(R.string.pause_restart), { onRequestConfirm(PauseAction.RESTART) }, Modifier.weight(1f),
                        icon = painterResource(R.drawable.ic_refresh), rivets = false, textStyle = BollwerkType.Label.copy(fontSize = 15.sp),
                    )
                    IndustrialButton(
                        stringResource(R.string.pause_surrender), { onRequestConfirm(PauseAction.SURRENDER) }, Modifier.weight(1f),
                        icon = painterResource(R.drawable.ic_flag), rivets = false, textStyle = BollwerkType.Label.copy(fontSize = 15.sp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IndustrialButton(
                        stringResource(R.string.menu_settings), onSettings, Modifier.weight(1f),
                        icon = painterResource(R.drawable.ic_gear), rivets = false, textStyle = BollwerkType.Label.copy(fontSize = 15.sp),
                    )
                    IndustrialButton(
                        stringResource(R.string.menu_to_main), { onRequestConfirm(PauseAction.MAIN_MENU) }, Modifier.weight(1f),
                        icon = painterResource(R.drawable.ic_home), rivets = false, textStyle = BollwerkType.Label.copy(fontSize = 15.sp),
                    )
                }
            }
        }
    }
}


/** Rückfrage vor Neustart, Aufgeben und Hauptmenü (verlieren den Spielstand). */
@Composable
fun ConfirmDialog(action: PauseAction, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val (title, body) = when (action) {
        PauseAction.RESTART -> R.string.confirm_restart_title to R.string.confirm_restart_body
        PauseAction.SURRENDER -> R.string.confirm_surrender_title to R.string.confirm_surrender_body
        PauseAction.MAIN_MENU -> R.string.confirm_menu_title to R.string.confirm_menu_body
    }
    IndustrialDialog(
        title = stringResource(title),
        actions = {
            IndustrialButton(stringResource(R.string.action_cancel), onCancel, Modifier.weight(1f), rivets = false)
            IndustrialButton(stringResource(R.string.action_yes), onConfirm, Modifier.weight(1f), style = ButtonStyle.Primary, rivets = false)
        },
    ) {
        BodyText(stringResource(body))
    }
}
