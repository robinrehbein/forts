package de.bollwerk.app.ui.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.bollwerk.app.R
import de.bollwerk.app.match.AiLevel
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MapOption
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.StartResources
import de.bollwerk.app.match.TeamColor
import de.bollwerk.app.ui.components.BlueprintBackground
import de.bollwerk.app.ui.components.BodyText
import de.bollwerk.app.ui.components.ButtonStyle
import de.bollwerk.app.ui.components.IndustrialButton
import de.bollwerk.app.ui.components.InfoChip
import de.bollwerk.app.ui.components.ScreenHeader
import de.bollwerk.app.ui.components.SectionLabel
import de.bollwerk.app.ui.components.SegmentedControl
import de.bollwerk.app.ui.components.SteelPanel
import de.bollwerk.app.ui.components.screenPadding
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkTheme
import de.bollwerk.app.ui.theme.BollwerkType

@StringRes
fun MapOption.nameRes(): Int = when (this) {
    MapOption.SCHLUCHT -> R.string.map_schlucht
    MapOption.HUEGEL -> R.string.map_huegel
}

@StringRes
private fun MapOption.descRes(): Int = when (this) {
    MapOption.SCHLUCHT -> R.string.map_schlucht_desc
    MapOption.HUEGEL -> R.string.map_huegel_desc
}

@StringRes
fun AiLevel.nameRes(): Int = when (this) {
    AiLevel.EASY -> R.string.ai_easy
    AiLevel.NORMAL -> R.string.ai_normal
    AiLevel.HARD -> R.string.ai_hard
}

@StringRes
private fun AiLevel.descRes(): Int = when (this) {
    AiLevel.EASY -> R.string.ai_easy_desc
    AiLevel.NORMAL -> R.string.ai_normal_desc
    AiLevel.HARD -> R.string.ai_hard_desc
}

@StringRes
fun StartResources.nameRes(): Int = when (this) {
    StartResources.SCARCE -> R.string.res_scarce
    StartResources.NORMAL -> R.string.res_normal
    StartResources.RICH -> R.string.res_rich
}

@StringRes
fun TeamColor.nameRes(): Int = if (this == TeamColor.BLUE) R.string.team_blue else R.string.team_red

@Composable
fun SetupScreen(viewModel: SetupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SetupContent(
        state = state,
        onBack = viewModel::onBack,
        onMap = viewModel::selectMap,
        onAi = viewModel::selectAiLevel,
        onResources = viewModel::selectResources,
        onTeam = viewModel::selectTeam,
        onStart = viewModel::onStart,
    )
}

/** Gefecht-Setup (Mockup 2). Im Hotseat entfallen KI-Stärke und Teamfarbe. */
@Composable
fun SetupContent(
    state: SetupUiState,
    onBack: () -> Unit,
    onMap: (MapOption) -> Unit,
    onAi: (AiLevel) -> Unit,
    onResources: (StartResources) -> Unit,
    onTeam: (TeamColor) -> Unit,
    onStart: () -> Unit,
) {
    val hotseat = state.mode == GameMode.HOTSEAT
    BlueprintBackground {
        Row(Modifier.fillMaxSize().screenPadding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // Links: Kopf, Kartenwahl, Zusammenfassung
            Column(Modifier.weight(1.25f).fillMaxHeight()) {
                ScreenHeader(
                    title = stringResource(if (hotseat) R.string.setup_title_hotseat else R.string.setup_title),
                    subtitle = stringResource(R.string.setup_subtitle),
                    onBack = onBack,
                )
                Spacer(Modifier.height(4.dp))
                SectionLabel(stringResource(R.string.setup_map))
                Spacer(Modifier.height(4.dp))
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (map in MapOption.entries) {
                        MapCard(map, state.map == map, { onMap(map) }, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Spacer(Modifier.height(8.dp))
                SummaryPanel(state, Modifier.fillMaxWidth(0.9f))
            }
            // Rechts: Optionen und Starten
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!hotseat) {
                        LabeledRow(stringResource(R.string.setup_ai_strength), stringResource(state.aiLevel.descRes()))
                        SegmentedControl(
                            AiLevel.entries.map { it to stringResource(it.nameRes()) }, state.aiLevel, onAi,
                        )
                    } else {
                        SteelPanel { BodyText(stringResource(R.string.setup_hotseat_hint, MatchConfig.HOTSEAT_TURN_SECONDS)) }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.setup_resources), Modifier.weight(1f))
                        ResourceChip(state.resources)
                    }
                    SegmentedControl(
                        StartResources.entries.map { it to stringResource(it.nameRes()) }, state.resources, onResources,
                    )
                    if (!hotseat) {
                        Spacer(Modifier.height(2.dp))
                        LabeledRow(
                            stringResource(R.string.setup_team),
                            stringResource(R.string.setup_team_hint, stringResource(state.team.opponent.nameRes())),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (team in TeamColor.entries) {
                                TeamButton(team, state.team == team, { onTeam(team) }, Modifier.weight(1f))
                            }
                        }
                    }
                }
                IndustrialButton(
                    stringResource(R.string.setup_start), onStart, Modifier.fillMaxWidth(),
                    style = ButtonStyle.Primary, textStyle = BollwerkType.MenuButton.copy(fontSize = 22.sp), minHeight = 52.dp,
                    icon = painterResource(R.drawable.ic_chevron_right),
                )
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, hint: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        Spacer(Modifier.weight(1f))
        Text(hint, style = BollwerkType.Body.copy(fontSize = 14.sp), color = BollwerkColors.Muted, maxLines = 1)
    }
}

@Composable
private fun ResourceChip(resources: StartResources) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, BollwerkColors.SteelHi.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(painterResource(R.drawable.ic_gear), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(BollwerkColors.Text))
        Text(resources.metal.toString(), style = BollwerkType.Number.copy(fontSize = 20.sp), color = BollwerkColors.Text)
        Spacer(Modifier.size(6.dp))
        Image(painterResource(R.drawable.ic_bolt), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(BollwerkColors.Energy))
        Text(resources.energy.toString(), style = BollwerkType.Number.copy(fontSize = 20.sp), color = BollwerkColors.Text)
    }
}

@Composable
private fun TeamButton(team: TeamColor, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val color = if (team == TeamColor.BLUE) BollwerkColors.TeamBlue else BollwerkColors.TeamRed
    IndustrialButton(
        text = stringResource(team.nameRes()),
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        style = if (selected) (if (team == TeamColor.BLUE) ButtonStyle.Blue else ButtonStyle.Danger) else ButtonStyle.Secondary,
        rivets = false,
        hazard = false,
        minHeight = 52.dp,
        textStyle = BollwerkType.Segment,
        leading = {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(2.5.dp, if (selected) Color.White.copy(alpha = 0.9f) else BollwerkColors.Dark.copy(alpha = 0.8f), CircleShape),
            )
        },
        trailing = if (selected) {
            { Image(painterResource(R.drawable.ic_check), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(BollwerkColors.Text)) }
        } else null,
    )
}

@Composable
private fun MapCard(map: MapOption, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val borderColor = if (selected) BollwerkColors.Rust else BollwerkColors.SteelHi.copy(alpha = 0.3f)
    Column(
        modifier
            .shadow(if (selected) 8.dp else 2.dp, shape, ambientColor = BollwerkColors.Rust, spotColor = BollwerkColors.Rust)
            .clip(shape)
            .background(BollwerkColors.PanelFill)
            .border(if (selected) 2.5.dp else 1.dp, borderColor, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapThumbnail(map)
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(BollwerkColors.Rust),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(BollwerkColors.Text))
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(map.nameRes()).uppercase(), style = BollwerkType.Title.copy(fontSize = 26.sp), color = BollwerkColors.Text)
            Text(stringResource(map.descRes()), style = BollwerkType.Body.copy(fontSize = 13.sp, lineHeight = 15.sp), color = BollwerkColors.Muted, maxLines = 2, minLines = 2)
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoChip(stringResource(R.string.map_width_chip, map.widthMeters))
                InfoChip(stringResource(if (map.strongWind) R.string.map_wind_strong else R.string.map_wind_medium))
            }
        }
    }
}

@Composable
private fun SummaryPanel(state: SetupUiState, modifier: Modifier) {
    val mapName = stringResource(state.map.nameRes()).uppercase()
    val text = (if (state.mode == GameMode.HOTSEAT) {
        stringResource(R.string.setup_summary_hotseat, mapName, stringResource(state.resources.nameRes()).uppercase())
    } else {
        stringResource(
            R.string.setup_summary_ai, mapName, stringResource(state.aiLevel.nameRes()).uppercase(),
            stringResource(state.team.nameRes()).uppercase(),
        )
    }).uppercase()
    SteelPanel(modifier, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        Column {
            SectionLabel(stringResource(R.string.setup_summary), color = BollwerkColors.SteelHi)
            Text(
                text,
                style = BollwerkType.BodyStrong.copy(
                    fontSize = 20.sp,
                    letterSpacing = 0.12.em,
                    fontWeight = FontWeight.Bold,
                ),
                color = BollwerkColors.Text,
                maxLines = 1,
            )
        }
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun SetupPreview() {
    BollwerkTheme { SetupContent(SetupUiState(GameMode.VS_AI), {}, {}, {}, {}, {}, {}) }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun SetupHotseatPreview() {
    BollwerkTheme { SetupContent(SetupUiState(GameMode.HOTSEAT, map = MapOption.HUEGEL), {}, {}, {}, {}, {}, {}) }
}
