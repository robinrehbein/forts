package de.bollwerk.app.ui.handover

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.match.teamOf
import de.bollwerk.app.ui.art.FortStyle
import de.bollwerk.app.ui.art.drawFort
import de.bollwerk.app.ui.components.ButtonStyle
import de.bollwerk.app.ui.components.HazardStripe
import de.bollwerk.app.ui.components.IndustrialButton
import de.bollwerk.app.ui.components.consumeTaps
import de.bollwerk.app.ui.components.screenPadding
import de.bollwerk.app.ui.game.HandoverState
import de.bollwerk.app.ui.setup.nameRes
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkTheme
import de.bollwerk.app.ui.theme.BollwerkType

/** Tipp-Index: die erste Übergabe (Zug 2) zeigt den ersten Tipp, danach der Reihe nach. */
fun handoverTipIndex(turn: Int, tipCount: Int): Int = (turn - 2).mod(tipCount)

private val HandoverBg = Color(0xFF0E0A0D)
private val HandoverRed = BollwerkColors.TeamRed
private val HandoverSalmon = Color(0xFFF0877E)

/**
 * Hotseat-Übergabe (Mockup 6): verdeckt die Spielfläche, bis der nächste Spieler „Bereit" getippt hat und der
 * Start-Countdown abgelaufen ist (ohne Eingabe bleibt sie stehen). Warnstreifen oben und unten, Countdown-Rad, Tipp-Karte.
 */
@Composable
fun HotseatHandoverScreen(
    state: HandoverState,
    turnSeconds: Int,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tips = stringArrayResource(R.array.handover_tips)
    // Die erste Übergabe (Zug 2) zeigt den ersten Tipp (Mockup 6: „Türen schließen…").
    val tip = tips[handoverTipIndex(state.turn, tips.size)]
    val team = teamOf(state.player)
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(HandoverBg, Color(0xFF200B0E), HandoverBg)))
            .consumeTaps(),
    ) {
        // Raster + Silhouette einer Festung hinter dem Rad
        Canvas(Modifier.fillMaxSize()) {
            val step = 24.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(Color.White.copy(alpha = 0.03f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = 0f
            while (y < size.height) { drawLine(Color.White.copy(alpha = 0.03f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
            val s = size.width * 0.016f
            drawFort(
                Offset(size.width * 0.66f, size.height * 0.92f), s * 1.45f, mirror = false, style = FortStyle.Mono,
                mono = HandoverRed.copy(alpha = 0.26f), flag = HandoverRed, bladeAngle = 15f,
            )
            drawRect(
                Brush.radialGradient(listOf(HandoverRed.copy(alpha = 0.22f), Color.Transparent), Offset(size.width * 0.80f, size.height * 0.45f), size.width * 0.35f),
            )
        }
        HazardStripe(Modifier.fillMaxWidth().height(14.dp).align(Alignment.TopCenter), a = HandoverRed, b = HandoverBg)
        HazardStripe(Modifier.fillMaxWidth().height(14.dp).align(Alignment.BottomCenter), a = HandoverRed, b = HandoverBg)

        BoxWithConstraints(Modifier.fillMaxSize().screenPadding(28.dp)) {
            val availH = maxHeight.value
            val titleSp = (availH * 0.135f).coerceIn(34f, 64f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // Links: Titel, Chips, Hinweis, Tipp
                Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(R.drawable.ic_hotseat), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(HandoverSalmon))
                        Text(
                            stringResource(R.string.handover_label, state.turn).uppercase(),
                            style = BollwerkType.Label, color = HandoverSalmon,
                        )
                    }
                    Text(
                        stringResource(R.string.handover_title, state.player + 1).uppercase(),
                        Modifier.semantics { heading() },
                        style = BollwerkType.Display.copy(fontSize = titleSp.sp, lineHeight = (titleSp * 0.98f).sp, letterSpacing = 0.02.em),
                        color = BollwerkColors.Text,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chip(borderColor = HandoverRed.copy(alpha = 0.7f), fill = HandoverRed.copy(alpha = 0.16f)) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(HandoverRed).border(2.dp, HandoverSalmon, CircleShape))
                            Text(
                                stringResource(R.string.handover_player_chip, state.player + 1, stringResource(team.nameRes())).uppercase(),
                                style = BollwerkType.Button.copy(fontSize = 18.sp), color = BollwerkColors.Text,
                            )
                        }
                        Chip(borderColor = BollwerkColors.SteelHi.copy(alpha = 0.35f), fill = Color.Black.copy(alpha = 0.35f)) {
                            Image(painterResource(R.drawable.ic_clock), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(BollwerkColors.Hazard))
                            Text(
                                stringResource(R.string.handover_turn_chip, turnSeconds).uppercase(),
                                style = BollwerkType.Button.copy(fontSize = 18.sp), color = BollwerkColors.Hazard,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.handover_hidden, state.player + 1),
                        style = BollwerkType.Body.copy(fontSize = 17.sp), color = BollwerkColors.Muted,
                    )
                    Spacer(Modifier.weight(1f))
                    TipCard(tip)
                }
                // Rechts: Countdown-Rad, Beschriftung, Bereit
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    CountdownDial(state.secondsLeft ?: state.totalSeconds, state.totalSeconds, Modifier.weight(1f, fill = false).size((availH * 0.56f).coerceIn(120f, 230f).dp))
                    Text(
                        stringResource(if (state.isCounting) R.string.handover_countdown_label else R.string.handover_waiting_label).uppercase(),
                        style = BollwerkType.Label, color = BollwerkColors.Muted,
                    )
                    IndustrialButton(
                        stringResource(R.string.handover_ready), onReady, Modifier.fillMaxWidth(),
                        style = ButtonStyle.Danger, icon = painterResource(R.drawable.ic_check), enabled = !state.isCounting,
                        textStyle = BollwerkType.ButtonLarge, minHeight = 56.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(borderColor: Color, fill: Color, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .clip(shape)
            .background(fill)
            .border(1.5.dp, borderColor, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun TipCard(tip: String) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF10151B))
            .border(1.dp, BollwerkColors.SteelHi.copy(alpha = 0.25f), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(56.dp).background(BollwerkColors.Hazard))
        Image(painterResource(R.drawable.ic_bulb), null, Modifier.padding(start = 14.dp).size(26.dp), colorFilter = ColorFilter.tint(BollwerkColors.Hazard))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.handover_tip_label).uppercase(), style = BollwerkType.LabelSmall, color = BollwerkColors.Hazard)
            Text(tip, style = BollwerkType.BodyStrong.copy(fontSize = 19.sp), color = BollwerkColors.Text)
        }
    }
}

/** Countdown-Rad: Skalenstriche, Restzeit-Bogen in Rot, große Bebas-Zahl in der Mitte. */
@Composable
fun CountdownDial(secondsLeft: Int, totalSeconds: Int, modifier: Modifier = Modifier) {
    val target = if (totalSeconds <= 0) 0f else (secondsLeft.toFloat() / totalSeconds).coerceIn(0f, 1f)
    val progress by animateFloatAsState(target, tween(1000, easing = LinearEasing), label = "dial")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val outer = size.minDimension / 2f
            // Skala
            for (i in 0 until 60) {
                val major = i % 5 == 0
                val len = if (major) outer * 0.09f else outer * 0.045f
                rotate(i * 6f, c) {
                    drawLine(
                        BollwerkColors.Text.copy(alpha = if (major) 0.8f else 0.35f),
                        Offset(c.x, c.y - outer), Offset(c.x, c.y - outer + len), if (major) 2.5f else 1.2f, StrokeCap.Round,
                    )
                }
            }
            val ring = outer * 0.80f
            val stroke = outer * 0.085f
            drawCircle(Color.Black.copy(alpha = 0.45f), ring + stroke / 2, c)
            drawCircle(Color(0xFF2A1013), ring, c, style = Stroke(stroke))
            drawArc(
                HandoverRed, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                topLeft = Offset(c.x - ring, c.y - ring), size = Size(ring * 2, ring * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawCircle(Brush.radialGradient(listOf(HandoverRed.copy(alpha = 0.25f), Color.Transparent), c, ring * 0.9f), ring * 0.9f, c)
        }
        Text(
            secondsLeft.toString(),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = BollwerkType.Countdown.copy(fontSize = 120.sp),
            color = BollwerkColors.Text,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun HandoverPreview() {
    BollwerkTheme { HotseatHandoverScreen(HandoverState(player = 1, turn = 2, secondsLeft = null, totalSeconds = 3), 45, {}) }
}
