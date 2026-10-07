package de.bollwerk.app.ui.result

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.match.EndReason
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.MatchResult
import de.bollwerk.app.match.MatchStats
import de.bollwerk.app.match.TeamColor
import de.bollwerk.app.match.formatDuration
import de.bollwerk.app.match.teamOf
import de.bollwerk.app.ui.art.drawDestroyedReactor
import de.bollwerk.app.ui.components.BlueprintBackground
import de.bollwerk.app.ui.components.ButtonStyle
import de.bollwerk.app.ui.components.IndustrialButton
import de.bollwerk.app.ui.components.SteelPanel
import de.bollwerk.app.ui.components.drawRivet
import de.bollwerk.app.ui.components.screenPadding
import de.bollwerk.app.ui.setup.nameRes
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkTheme
import de.bollwerk.app.ui.theme.BollwerkType
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer


@Composable
fun ResultScreen(viewModel: ResultViewModel) {
    ResultContent(viewModel.result, viewModel::onRematch, viewModel::onMainMenu)
}

/** Ergebnis (Mockup 7): Banner SIEG/NIEDERLAGE, Reaktor-Illustration, Gefechtsbericht, Revanche/Hauptmenü. */
@Composable
fun ResultContent(result: MatchResult, onRematch: () -> Unit, onMainMenu: () -> Unit) {
    val team = teamOf(result.bannerPlayerId)
    BlueprintBackground(tint = if (result.isVictory) BollwerkColors.TeamBlue else BollwerkColors.TeamRed) {
        Column(Modifier.fillMaxSize().screenPadding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ResultBanner(result.isVictory, result.isDraw, team, result.bannerPlayerId, Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ReactorPanel(result.reason, Modifier.weight(0.9f).fillMaxHeight())
                Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReportPanel(result, Modifier.weight(1f).fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IndustrialButton(
                            stringResource(R.string.result_rematch), onRematch, Modifier.weight(1f),
                            style = ButtonStyle.Primary, icon = painterResource(R.drawable.ic_refresh),
                            textStyle = BollwerkType.Button.copy(fontSize = 17.sp, letterSpacing = 0.12.em), minHeight = 48.dp,
                        )
                        IndustrialButton(
                            stringResource(R.string.menu_to_main), onMainMenu, Modifier.weight(1f),
                            textStyle = BollwerkType.Button.copy(fontSize = 17.sp, letterSpacing = 0.12.em), minHeight = 48.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultBanner(victory: Boolean, draw: Boolean, team: TeamColor, playerId: Int, modifier: Modifier) {
    val base = if (team == TeamColor.BLUE) BollwerkColors.TeamBlue else BollwerkColors.TeamRed
    val light = if (team == TeamColor.BLUE) BollwerkColors.BlueLight else BollwerkColors.RedLight
    val deep = if (team == TeamColor.BLUE) BollwerkColors.BlueDeep else BollwerkColors.RedDeep
    val k = if (victory) 0f else 0.45f
    val cLight = lerp(light, BollwerkColors.SteelHi, k)
    val cBase = lerp(base, BollwerkColors.Steel, k)
    val cDeep = lerp(deep, BollwerkColors.Dark, k)
    Box(modifier.height(66.dp).fillMaxWidth(0.62f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val tail = size.width * 0.07f
            val bodyL = tail * 0.9f
            val bodyR = size.width - tail * 0.9f
            // Bandenden mit V-Kerbe
            fun tailPath(left: Boolean) = Path().apply {
                val x0 = if (left) 0f else size.width
                val x1 = if (left) bodyL + 4f else bodyR - 4f
                val notch = if (left) tail * 0.45f else size.width - tail * 0.45f
                moveTo(x1, size.height * 0.2f); lineTo(x0, size.height * 0.2f); lineTo(notch, size.height * 0.55f)
                lineTo(x0, size.height * 0.9f); lineTo(x1, size.height * 0.9f); close()
            }
            drawPath(tailPath(true), cDeep); drawPath(tailPath(false), cDeep)
            // Mittelteil
            drawRect(Brush.verticalGradient(listOf(cLight, cBase, cDeep)), Offset(bodyL, 0f), Size(bodyR - bodyL, size.height))
            drawRect(Color.White.copy(alpha = 0.3f), Offset(bodyL, 0f), Size(bodyR - bodyL, 2.dp.toPx()))
            drawRect(Color.Black.copy(alpha = 0.3f), Offset(bodyL, size.height - 3.dp.toPx()), Size(bodyR - bodyL, 3.dp.toPx()))
            drawRivet(Offset(bodyL + 14.dp.toPx(), size.height * 0.5f), 3.5.dp.toPx(), cDeep, Color.White)
            drawRivet(Offset(bodyR - 14.dp.toPx(), size.height * 0.5f), 3.5.dp.toPx(), cDeep, Color.White)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.result_player_label, playerId + 1, stringResource(team.nameRes())).uppercase(),
                style = BollwerkType.LabelSmall,
                color = BollwerkColors.Text.copy(alpha = 0.9f),
            )
            Text(
                stringResource(
                    when {
                        draw -> R.string.result_draw
                        victory -> R.string.result_victory
                        else -> R.string.result_defeat
                    },
                ).uppercase(),
                Modifier.semantics { heading() },
                style = BollwerkType.Wordmark.copy(fontSize = 46.sp, letterSpacing = if (victory) 0.34.em else 0.16.em),
                color = BollwerkColors.Text,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReactorPanel(reason: EndReason, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .shadow(4.dp, shape)
            .clip(shape)
            .border(1.5.dp, BollwerkColors.SteelHi.copy(alpha = 0.35f), shape),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawDestroyedReactor() }
        Text(
            stringResource(
                when (reason) {
                    EndReason.ENEMY_REACTOR_DESTROYED -> R.string.reason_enemy_reactor
                    EndReason.OWN_REACTOR_DESTROYED -> R.string.reason_own_reactor
                    EndReason.SURRENDER -> R.string.reason_surrender
                    EndReason.TIMEOUT -> R.string.reason_timeout
                    EndReason.DRAW -> R.string.reason_draw
                },
            ).uppercase(),
            Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            style = BollwerkType.LabelSmall.copy(letterSpacing = 0.18.em),
            color = BollwerkColors.Hazard,
        )
    }
}

@Composable
private fun ReportPanel(result: MatchResult, modifier: Modifier) {
    val config = result.config
    val mapName = stringResource(config.map.nameRes()).uppercase()
    val context = if (config.mode == GameMode.HOTSEAT) {
        stringResource(R.string.result_context_hotseat, mapName)
    } else {
        stringResource(R.string.result_context_ai, mapName, stringResource(config.aiLevel.nameRes()).uppercase())
    }
    val stats = result.stats
    SteelPanel(modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(R.string.result_report).uppercase(), Modifier.weight(1f),
                    style = BollwerkType.Title.copy(fontSize = 24.sp), color = BollwerkColors.Text,
                )
                Text(context, style = BollwerkType.LabelSmall.copy(letterSpacing = 0.18.em), color = BollwerkColors.SteelHi, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(BollwerkColors.Rust))
            StatRow(R.drawable.ic_clock, R.string.stat_duration, formatDuration(stats.durationSeconds), Modifier.weight(1f))
            StatRow(R.drawable.ic_target, R.string.stat_shots, stats.shots.toString(), Modifier.weight(1f))
            StatRow(R.drawable.ic_hits, R.string.stat_hits, stats.hits.toString(), Modifier.weight(1f))
            StatRow(R.drawable.ic_beam_built, R.string.stat_built, stats.beamsBuilt.toString(), Modifier.weight(1f))
            StatRow(R.drawable.ic_beam_lost, R.string.stat_lost, stats.beamsLost.toString(), Modifier.weight(1f), divider = false)
        }
    }
}

@Composable
private fun StatRow(@DrawableRes icon: Int, label: Int, value: String, modifier: Modifier, divider: Boolean = true) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(BollwerkColors.SteelHi))
            Text(stringResource(label), Modifier.weight(1f), style = BollwerkType.BodyStrong.copy(fontSize = 17.sp, letterSpacing = 0.04.em), color = BollwerkColors.Muted)
            Text(value, style = BollwerkType.Number.copy(fontSize = 22.sp), color = BollwerkColors.Text)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(BollwerkColors.SteelHi.copy(alpha = 0.18f)))
    }
}

private val previewConfig = MatchConfig(seed = 1)
private val previewStats = MatchStats(522, 23, 17, 64, 19)

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun ResultVictoryPreview() {
    BollwerkTheme { ResultContent(MatchResult(previewConfig, 0, EndReason.ENEMY_REACTOR_DESTROYED, previewStats), {}, {}) }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun ResultDefeatPreview() {
    BollwerkTheme { ResultContent(MatchResult(previewConfig, 1, EndReason.OWN_REACTOR_DESTROYED, previewStats), {}, {}) }
}
