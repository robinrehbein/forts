package de.bollwerk.app.ui.game.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.game.HudUiState
import de.bollwerk.app.game.TurnInfo
import de.bollwerk.app.ui.components.FitText
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.Rajdhani

/** HUD-Schriften (Stil-Bibel §3: Rajdhani, Zahlen tabellarisch, Labels in Versalien mit weiter Laufweite). */
object HudType {
    val ChipLabel = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.2.em)
    val ChipValue = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 25.sp, fontFeatureSettings = "tnum", lineHeight = 26.sp)
    val ChipUnit = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, fontFeatureSettings = "tnum")
    val Rate = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFeatureSettings = "tnum")
    val ItemLabel = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.1.em)
    val Small = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, fontFeatureSettings = "tnum")
}

/** Dunkle HUD-Fläche: abgerundet, feine Stahlkante, leichter Schatten (Mockups 3/4). */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    fill: Color = HudColors.Panel,
    border: Color = BollwerkColors.SteelHi.copy(alpha = 0.22f),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(fill.copy(alpha = 0.97f), HudColors.PanelDeep.copy(alpha = 0.97f))))
            .border(1.dp, border, shape)
            // Berührungen auf Chips, Leisten und Karten gehören dem HUD, nie der Spielfläche darunter
            .blockTouches(),
    ) { content() }
}

object HudColors {
    val Panel = Color(0xFF242C36)
    val PanelDeep = Color(0xFF1A2129)
    val Label = Color(0xFF8E9AAB)
    val Rate = BollwerkColors.Ok
    val Inset = Color(0xFF12171D)
    val EnemyText = Color(0xFFFF7468)
    val Selected = BollwerkColors.Rust
}

/** Ressourcen-Chip: Icon, Label (Versalien), großer Wert, Zusatz (Kapazität), Rate grün; optional Füllbalken. */
@Composable
fun ResourceChip(
    icon: Int,
    iconTint: Color,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    rate: String? = null,
    fill: Float? = null,
    flash: Boolean = false,
    /** Rahmenfarbe als Kennzeichen (Hotseat: Teamfarbe des Spielers am Zug), sonst die Stahlkante. */
    accent: Color? = null,
) {
    HudPanel(modifier, border = if (flash) BollwerkColors.TeamRed else accent ?: BollwerkColors.SteelHi.copy(alpha = 0.22f)) {
        // Breite = Inhalt; der Füllbalken passt sich an (sonst nähme er die ganze Zeile ein)
        Column(Modifier.width(IntrinsicSize.Max)) {
            Row(
                Modifier.heightIn(min = 40.dp).padding(start = 10.dp, end = 12.dp, top = 3.dp, bottom = if (fill != null) 1.dp else 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(iconTint))
                Column {
                    FitText(label.uppercase(), style = HudType.ChipLabel, color = HudColors.Label)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(value, style = HudType.ChipValue, color = BollwerkColors.Text, maxLines = 1)
                        if (unit != null) {
                            Text(unit, Modifier.padding(start = 2.dp, bottom = 2.dp), style = HudType.ChipUnit, color = HudColors.Label, maxLines = 1)
                        }
                        if (rate != null) {
                            Text(rate, Modifier.padding(start = 6.dp, bottom = 3.dp), style = HudType.Rate, color = HudColors.Rate, maxLines = 1)
                        }
                    }
                }
            }
            if (fill != null) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(HudColors.Inset)) {
                    Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).height(4.dp).background(BollwerkColors.Energy))
                }
            }
        }
    }
}

/** Wind-Chip: Pfeil (dreht mit der Richtung), Stärke in m/s. */
@Composable
fun WindChip(hud: HudUiState, modifier: Modifier = Modifier) {
    val cd = stringResource(if (hud.windToRight) R.string.hud_wind_right_cd else R.string.hud_wind_left_cd)
    HudPanel(modifier.semantics { contentDescription = cd }) {
        Row(
            Modifier.heightIn(min = 40.dp).padding(start = 10.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WindArrow(hud.windToRight, Modifier.size(22.dp))
            Column {
                Text(stringResource(R.string.hud_wind).uppercase(), style = HudType.ChipLabel, color = HudColors.Label)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(hud.windText, style = HudType.ChipValue, color = BollwerkColors.Text)
                    Text(stringResource(R.string.hud_wind_unit), Modifier.padding(start = 2.dp, bottom = 2.dp), style = HudType.ChipUnit, color = HudColors.Label)
                }
            }
        }
    }
}

@Composable
private fun WindArrow(toRight: Boolean, modifier: Modifier) {
    Canvas(modifier.rotate(if (toRight) 0f else 180f)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(w * 0.1f, h * 0.62f)
            quadraticTo(w * 0.5f, h * 0.45f, w * 0.88f, h * 0.45f)
            moveTo(w * 0.62f, h * 0.22f)
            lineTo(w * 0.88f, h * 0.45f)
            lineTo(w * 0.62f, h * 0.68f)
        }
        drawPath(p, Color(0xFF6FB8F0), style = stroke)
    }
}

/** Gegner-Chip „SPIELER 2 · 62 %" mit Reaktor-TP-Balken (Stil-Bibel §7). */
@Composable
fun EnemyChip(hud: HudUiState, modifier: Modifier = Modifier) {
    val team = if (hud.enemyIsRed) BollwerkColors.TeamRed else BollwerkColors.TeamBlue
    HudPanel(modifier.width(112.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(team))
                Text(
                    stringResource(R.string.hud_enemy, hud.enemyPlayerNumber).uppercase(),
                    Modifier.weight(1f),
                    style = HudType.ChipLabel.copy(fontSize = 10.sp, letterSpacing = 0.1.em), color = BollwerkColors.Text, maxLines = 1, softWrap = false,
                )
                Text("${hud.enemyPercent} %", style = HudType.Rate.copy(fontSize = 12.sp), color = HudColors.EnemyText, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(HudColors.Inset)) {
                Box(Modifier.fillMaxWidth(hud.enemyFill).height(4.dp).background(team))
            }
        }
    }
}

/** Quadratischer HUD-Button (Pause), 48 dp Touch-Ziel. */
@Composable
fun HudSquareButton(icon: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = BollwerkColors.Text) {
    HudPanel(modifier.size(HudButtonSize)) {
        Box(
            Modifier
                .size(HudButtonSize)
                .hudClickable(onClick, contentDescription),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(tint))
        }
    }
}

/** Mindestgröße der HUD-Buttons (Touch-Ziel). */
val HudButtonSize = 48.dp

/**
 * Zeile der oberen HUD-Leiste: links Metall/Energie, rechts Zeit (Hotseat: Spieler und Zug in Teamfarbe), Wind, Pause. Der
 * Zug-Chip nimmt nur den freien Platz; auf schmalen Geräten wird sein Label enger gesetzt statt die Leiste zu sprengen.
 * „Zug beenden" sitzt in der unteren Leiste (Daumenzone, siehe [EndTurnButton]).
 */
@Composable
fun HudTopBar(
    hud: HudUiState,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResourceChip(
            R.drawable.ic_gear, BollwerkColors.Text, stringResource(R.string.hud_metal), hud.metal.toString(),
            Modifier.widthIn(min = 104.dp),
            rate = stringResource(R.string.hud_rate, signedRate(hud.metalRate)),
        )
        ResourceChip(
            R.drawable.ic_bolt, BollwerkColors.Energy, stringResource(R.string.hud_energy), hud.energy.toString(),
            Modifier.widthIn(min = 120.dp),
            unit = stringResource(R.string.hud_energy_cap, hud.energyCap),
            rate = stringResource(R.string.hud_rate, signedRate(hud.energyRate)),
            fill = hud.energyFill,
        )
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            val turn = hud.turn
            if (turn != null) TurnChip(turn, Modifier.weight(1f, fill = false).widthIn(min = 92.dp))
            else ResourceChip(R.drawable.ic_clock, BollwerkColors.Text, stringResource(R.string.hud_time), hud.timeText, Modifier.widthIn(min = 92.dp))
            WindChip(hud, Modifier.widthIn(min = 98.dp))
            HudSquareButton(R.drawable.ic_pause, stringResource(R.string.game_pause_cd), onPause)
        }
    }
}

/** Teamfarbe eines Spielers (Spieler 1 = Blau, Spieler 2 = Rot). */
fun teamColorOf(player: Int): Color = if (player == 1) BollwerkColors.TeamRed else BollwerkColors.TeamBlue

/**
 * Zug-Chip im Hotseat: „SPIELER 1 · ZUG 3" mit Restzeit, Rahmen und Uhr in der Teamfarbe des Spielers am Zug (Mockup 6),
 * damit nach der Übergabe sofort klar ist, wer dran ist. In der Auflösungsphase „AUFLÖSUNG" ohne Teamfarbe.
 */
@Composable
fun TurnChip(turn: TurnInfo, modifier: Modifier = Modifier) {
    val play = turn.phase == TurnPhase.PLAY && turn.activePlayer >= 0
    val team = teamColorOf(turn.activePlayer)
    val label = if (play) stringResource(R.string.hud_turn_player, turn.activePlayer + 1, turn.turnNumber)
    else stringResource(R.string.hud_resolve)
    val iconTint = when {
        turn.secondsLeft <= 10 -> BollwerkColors.Hazard
        play -> team
        else -> BollwerkColors.Text
    }
    ResourceChip(
        R.drawable.ic_clock, iconTint, label, stringResource(R.string.hud_turn_time, turn.secondsLeft), modifier,
        accent = if (play) team.copy(alpha = 0.85f) else null,
    )
}

private fun signedRate(v: Int): String = if (v > 0) "+$v" else "$v"

/** Kleines Abzeichen-Icon (Schloss) in einer Ecke. */
@Composable
fun LockBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A4350)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_lock), null, Modifier.size(10.dp), colorFilter = ColorFilter.tint(BollwerkColors.SteelHi))
    }
}

/** Grüner Haken (gebaut/freigeschaltet). */
@Composable
fun CheckBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(BollwerkColors.Ok.copy(green = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_check), null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(Color.White))
    }
}
