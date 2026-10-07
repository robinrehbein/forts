package de.bollwerk.app.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.bollwerk.app.BuildInfo
import de.bollwerk.app.R
import de.bollwerk.app.ui.components.BodyText
import de.bollwerk.app.ui.components.ButtonStyle
import de.bollwerk.app.ui.components.IndustrialButton
import de.bollwerk.app.ui.components.IndustrialDialog
import de.bollwerk.app.ui.components.screenPadding
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkTheme
import de.bollwerk.app.ui.theme.BollwerkType

@Composable
fun MainMenuScreen(viewModel: MainMenuViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MainMenuContent(
        state = state,
        onBattle = viewModel::onBattle,
        onHotseat = viewModel::onHotseat,
        onTutorial = viewModel::onTutorial,
        onTutorialOfferStart = viewModel::onTutorialOfferStart,
        onTutorialOfferLater = viewModel::onTutorialOfferLater,
        onSettings = viewModel::onSettings,
        onCredits = viewModel::onCredits,
        onDismissDialog = viewModel::onDismissDialog,
        animate = !state.reducedEffects,
    )
}

/** Hauptmenü (Mockup 1): Szene dahinter, links Wordmark, Slogan, vier Buttons, Version/Credits. */
@Composable
fun MainMenuContent(
    state: MainMenuUiState,
    onBattle: () -> Unit,
    onHotseat: () -> Unit,
    onTutorial: () -> Unit,
    onSettings: () -> Unit,
    onCredits: () -> Unit,
    onDismissDialog: () -> Unit,
    animate: Boolean = true,
    onTutorialOfferStart: () -> Unit = {},
    onTutorialOfferLater: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(BollwerkColors.Dark)) {
        MenuScene(animate = animate)
        BoxWithConstraints(Modifier.fillMaxSize().screenPadding(28.dp)) {
            val wordHeight = (maxHeight.value * 0.15f).coerceIn(40f, 84f).dp
            val buttonH = (maxHeight.value * 0.12f).coerceIn(48f, 58f).dp
            // 37 % der Breite, aber nie schmaler als 260 dp (sonst schneiden 640-dp-Geräte „EINSTELLUNGEN" ab)
            val columnWidth = (maxWidth * 0.37f).coerceIn(MENU_MIN_WIDTH, MENU_MAX_WIDTH)
            Column(Modifier.width(columnWidth), verticalArrangement = Arrangement.Top) {
                Wordmark(stringResource(R.string.title_wordmark), wordHeight)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(18.dp).height(2.dp).background(BollwerkColors.Rust))
                    Text(
                        stringResource(R.string.menu_tagline).uppercase(),
                        style = BollwerkType.Label,
                        color = BollwerkColors.Rust,
                    )
                    Box(Modifier.weight(1f).height(2.dp).background(BollwerkColors.Rust.copy(alpha = 0.7f)))
                }
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    IndustrialButton(
                        stringResource(R.string.menu_battle), onBattle, Modifier.fillMaxWidth(),
                        style = ButtonStyle.Primary, icon = painterResource(R.drawable.ic_target),
                        textStyle = BollwerkType.MenuButton, minHeight = buttonH,
                    )
                    IndustrialButton(
                        stringResource(R.string.menu_hotseat), onHotseat, Modifier.fillMaxWidth(),
                        icon = painterResource(R.drawable.ic_hotseat), textStyle = BollwerkType.MenuButton, minHeight = buttonH,
                    )
                    IndustrialButton(
                        stringResource(R.string.menu_tutorial), onTutorial, Modifier.fillMaxWidth(),
                        icon = painterResource(R.drawable.ic_wrench), textStyle = BollwerkType.MenuButton, minHeight = buttonH,
                    )
                    IndustrialButton(
                        stringResource(R.string.menu_settings), onSettings, Modifier.fillMaxWidth(),
                        icon = painterResource(R.drawable.ic_gear), textStyle = BollwerkType.MenuButton, minHeight = buttonH,
                    )
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).offset(y = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    stringResource(R.string.menu_version, BuildInfo.VERSION_NAME).uppercase(),
                    style = BollwerkType.Label,
                    color = BollwerkColors.SteelHi,
                )
                Text(
                    stringResource(R.string.menu_credits).uppercase(),
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onCredits)
                        .padding(vertical = 14.dp),
                    style = BollwerkType.Label,
                    color = BollwerkColors.Text,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
        when (state.dialog) {
            MenuDialog.CREDITS -> IndustrialDialog(
                title = stringResource(R.string.credits_title),
                actions = {
                    IndustrialButton(stringResource(R.string.action_ok), onDismissDialog, Modifier.weight(1f), style = ButtonStyle.Primary)
                },
            ) {
                BodyText(stringResource(R.string.credits_body))
            }
            MenuDialog.TUTORIAL_OFFER -> IndustrialDialog(
                title = stringResource(R.string.tutorial_offer_title),
                actions = {
                    IndustrialButton(stringResource(R.string.tutorial_offer_later), onTutorialOfferLater, Modifier.weight(1f), rivets = false)
                    IndustrialButton(
                        stringResource(R.string.tutorial_offer_start), onTutorialOfferStart, Modifier.weight(1f), style = ButtonStyle.Primary,
                    )
                },
            ) {
                BodyText(stringResource(R.string.tutorial_offer_body))
            }
            null -> Unit
        }
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun MainMenuPreview() {
    BollwerkTheme {
        MainMenuContent(MainMenuUiState(), {}, {}, {}, {}, {}, {}, animate = false)
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun MainMenuCreditsPreview() {
    BollwerkTheme {
        MainMenuContent(MainMenuUiState(MenuDialog.CREDITS), {}, {}, {}, {}, {}, {}, animate = false)
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun MainMenuTutorialOfferPreview() {
    BollwerkTheme {
        MainMenuContent(MainMenuUiState(MenuDialog.TUTORIAL_OFFER), {}, {}, {}, {}, {}, {}, animate = false)
    }
}

private val MENU_MIN_WIDTH = 260.dp
private val MENU_MAX_WIDTH = 310.dp
