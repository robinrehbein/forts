package de.bollwerk.app.ui.game.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.game.DamageStyle
import de.bollwerk.app.game.HudFormat
import de.bollwerk.app.game.HudUiState
import de.bollwerk.app.game.TechCard
import de.bollwerk.app.game.TechStatus
import de.bollwerk.app.game.UnlockTile
import de.bollwerk.app.ui.components.ButtonStyle
import de.bollwerk.app.ui.components.ChamferShape
import de.bollwerk.app.ui.components.IndustrialButton
import de.bollwerk.app.ui.components.consumeTaps
import de.bollwerk.app.ui.components.drawHazardStripes
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkType

/**
 * Techbaum als untere Schublade (Mockup 5): vier Gebäude mit Status (gebaut / im Bau / verfügbar / gesperrt), Kosten,
 * Freischaltungen und Fortschritt. „Bauen" wählt das Geräte-Werkzeug für das Gebäude; platziert wird wie jedes Gerät.
 */
@Composable
fun TechTreeSheet(hud: HudUiState, actions: HudActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).consumeTaps()) {
        val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF26303B), Color(0xFF1B232C))))
                .border(1.dp, BollwerkColors.SteelHi.copy(alpha = 0.3f), shape)
                .padding(horizontal = 14.dp)
                .padding(top = 6.dp, bottom = 10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(46.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF55606E)))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.tech_title).uppercase(), Modifier.semantics { heading() },
                    style = BollwerkType.Display.copy(fontSize = 34.sp), color = BollwerkColors.Text,
                )
                Text(
                    stringResource(R.string.tech_subtitle), Modifier.weight(1f),
                    style = HudType.Small.copy(fontSize = 12.sp, letterSpacing = 0.04.em, lineHeight = 14.sp), color = HudColors.Label, maxLines = 2,
                )
                ResourceBox(R.drawable.ic_gear, BollwerkColors.Text, hud.metal)
                ResourceBox(R.drawable.ic_bolt, BollwerkColors.Energy, hud.energy)
                val close = stringResource(R.string.tech_close_cd)
                Box(
                    Modifier.size(40.dp).clip(ChamferShape(8.dp)).background(Color(0xFF3A4450)).hudClickable(actions::closeTechTree, close),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(R.drawable.ic_close), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(BollwerkColors.Text))
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (card in hud.techs) TechCardView(card, actions, Modifier.weight(1f).fillMaxHeight(), hud.canCommand)
            }
        }
    }
}

@Composable
private fun ResourceBox(icon: Int, tint: Color, value: Int) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(HudColors.Inset).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(painterResource(icon), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(tint))
        Text(value.toString(), style = HudType.ChipValue.copy(fontSize = 20.sp, lineHeight = 22.sp), color = BollwerkColors.Text)
    }
}

private fun borderOf(status: TechStatus): Color = when (status) {
    TechStatus.BUILT -> BollwerkColors.Ok.copy(alpha = 0.75f)
    TechStatus.BUILDING -> BollwerkColors.Rust
    TechStatus.AVAILABLE -> BollwerkColors.Hazard
    TechStatus.LOCKED -> BollwerkColors.SteelHi.copy(alpha = 0.25f)
}

@Composable
private fun TechCardView(card: TechCard, actions: HudActions, modifier: Modifier, canCommand: Boolean) {
    val shape = RoundedCornerShape(9.dp)
    val locked = card.status == TechStatus.LOCKED
    Column(
        modifier
            .clip(shape)
            .background(Color(0xFF1E262F))
            .border(1.5.dp, borderOf(card.status), shape)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2E3946)), contentAlignment = Alignment.Center) {
                PartIcon(card.deviceId, Modifier.size(32.dp), dim = locked)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    contentNameRes(card.deviceId)?.let { stringResource(it) } ?: card.deviceId,
                    style = HudType.ChipValue.copy(fontSize = 15.sp, lineHeight = 16.sp, letterSpacing = 0.02.em),
                    color = if (locked) HudColors.Label else BollwerkColors.Text, maxLines = 1, softWrap = false,
                )
                StatusChip(card)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CostText(R.drawable.ic_gear, BollwerkColors.Text, card.costMetal.toString(), locked)
            CostText(R.drawable.ic_bolt, BollwerkColors.Energy, card.costEnergy.toString(), locked)
            CostText(R.drawable.ic_clock, HudColors.Label, stringResource(R.string.tech_seconds, card.buildSeconds), locked)
        }
        val tiles = card.unlocks
        val rows = ((tiles.size + 1) / 2).coerceIn(1, 2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in 0 until rows) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (col in 0 until 2) {
                        val t = tiles.getOrNull(row * 2 + col)
                        if (t != null) UnlockTileView(t, card.status, Modifier.weight(1f).fillMaxHeight())
                        else Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        TechFooter(card, actions, canCommand)
    }
}

@Composable
private fun StatusChip(card: TechCard) {
    val (text, color) = when (card.status) {
        TechStatus.BUILT -> stringResource(R.string.tech_built) to BollwerkColors.Ok
        TechStatus.BUILDING -> stringResource(R.string.tech_building, (card.progress01 * 100f).toInt()) to BollwerkColors.RustLight
        TechStatus.AVAILABLE -> stringResource(R.string.tech_available) to BollwerkColors.Hazard
        TechStatus.LOCKED -> stringResource(R.string.tech_locked) to HudColors.Label
    }
    Row(
        Modifier.padding(top = 2.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (card.status == TechStatus.BUILT) Image(painterResource(R.drawable.ic_check), null, Modifier.size(10.dp), colorFilter = ColorFilter.tint(color))
        if (card.status == TechStatus.LOCKED) Image(painterResource(R.drawable.ic_lock), null, Modifier.size(9.dp), colorFilter = ColorFilter.tint(color))
        Text(text.uppercase(), style = HudType.ChipLabel.copy(fontSize = 9.sp, letterSpacing = 0.14.em), color = color, maxLines = 1)
    }
}

@Composable
private fun CostText(icon: Int, tint: Color, text: String, dim: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Image(painterResource(icon), null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(tint.copy(alpha = if (dim) 0.5f else 1f)))
        Text(text, style = HudType.Small.copy(fontSize = 13.sp), color = BollwerkColors.Text.copy(alpha = if (dim) 0.5f else 1f), maxLines = 1)
    }
}

@Composable
private fun UnlockTileView(t: UnlockTile, status: TechStatus, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val built = status == TechStatus.BUILT
    Box(
        modifier
            .clip(shape)
            .background(if (built) Color(0xFF1F3329) else Color(0xFF222B35))
            .border(1.dp, if (built) BollwerkColors.Ok.copy(alpha = 0.5f) else Color(0xFF323D49), shape)
            .padding(3.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            PartIcon(t.id, Modifier.size(width = 30.dp, height = 18.dp), dim = status == TechStatus.LOCKED)
            Text(
                contentNameRes(t.id)?.let { stringResource(it) } ?: t.id,
                style = HudType.ItemLabel.copy(fontSize = 11.sp, letterSpacing = 0.02.em), color = BollwerkColors.Text, maxLines = 1, softWrap = false,
            )
            val line1 = when (t.damageStyle) {
                DamageStyle.PLAIN -> stringResource(R.string.tech_damage, t.damage)
                DamageStyle.SALVO -> stringResource(R.string.tech_salvo, t.damage, t.damageSecondary)
                DamageStyle.INCENDIARY -> stringResource(R.string.tech_incendiary, t.damage)
                DamageStyle.BEAM -> stringResource(R.string.tech_beam, t.damage, t.damageSecondary)
                DamageStyle.MATERIAL -> null
            }
            if (line1 != null) Text(line1, style = TileText, color = HudColors.Label, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
            if (t.isWeapon) Text(stringResource(R.string.tech_range, t.range), style = TileText, color = HudColors.Label, maxLines = 1, softWrap = false)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.isWeapon) {
                    if (t.shotMetal > 0) MiniCost(R.drawable.ic_gear, BollwerkColors.Text, t.shotMetal.toString())
                    if (t.shotEnergy > 0) MiniCost(R.drawable.ic_bolt, BollwerkColors.Energy, t.shotEnergy.toString())
                } else {
                    MiniCost(R.drawable.ic_gear, BollwerkColors.Text, t.costPerMeter.toString() + " " + stringResource(R.string.hud_per_meter))
                }
            }
        }
        if (built) CheckBadge(Modifier.align(Alignment.TopEnd)) else LockBadge(Modifier.align(Alignment.TopEnd))
    }
}

private val TileText = HudType.Small.copy(fontSize = 10.sp, lineHeight = 11.sp)

@Composable
private fun MiniCost(icon: Int, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        Image(painterResource(icon), null, Modifier.size(10.dp), colorFilter = ColorFilter.tint(tint))
        Text(text, style = HudType.Small.copy(fontSize = 11.sp, lineHeight = 12.sp), color = BollwerkColors.Text, maxLines = 1)
    }
}

@Composable
private fun TechFooter(card: TechCard, actions: HudActions, canCommand: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    when (card.status) {
        TechStatus.BUILT -> Row(
            Modifier.fillMaxWidth().height(34.dp).clip(shape).background(Color(0xFF1F3329)).border(1.dp, BollwerkColors.Ok.copy(alpha = 0.5f), shape).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(BollwerkColors.Ok))
            Text(
                stringResource(R.string.tech_active).uppercase(), Modifier.padding(start = 6.dp).weight(1f),
                style = HudType.ItemLabel.copy(fontSize = 14.sp, letterSpacing = 0.16.em), color = BollwerkColors.Ok,
            )
            Text(stringResource(R.string.tech_level), style = HudType.Small, color = BollwerkColors.Text)
        }
        TechStatus.BUILDING -> Column(
            Modifier.fillMaxWidth().height(34.dp).clip(shape).background(Color(0xFF2E2118)).border(1.dp, BollwerkColors.Rust, shape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tech_ready_in, HudFormat.clock(card.secondsLeft.toFloat()).removePrefix("0")), Modifier.weight(1f),
                    style = HudType.Small.copy(fontSize = 12.sp, lineHeight = 13.sp), color = BollwerkColors.Hazard, maxLines = 1,
                )
                Text("${(card.progress01 * 100f).toInt()} %", style = HudType.Small.copy(fontSize = 12.sp, lineHeight = 13.sp), color = BollwerkColors.Text)
            }
            Canvas(Modifier.fillMaxWidth().height(7.dp).padding(top = 1.dp).clip(RoundedCornerShape(3.dp))) {
                drawRect(Color(0xFF14181D))
                val w = size.width * card.progress01
                if (w > 0f) drawHazardStripes(Offset.Zero, androidx.compose.ui.geometry.Size(w, size.height), stripe = 4.dp.toPx(), a = BollwerkColors.RustLight, b = BollwerkColors.Rust)
            }
        }
        TechStatus.AVAILABLE -> IndustrialButton(
            stringResource(R.string.tech_build), { actions.buildTech(card.deviceIndex) }, Modifier.fillMaxWidth(),
            style = ButtonStyle.Primary, rivets = false, hazard = false, enabled = card.affordable && canCommand, minHeight = 34.dp,
            textStyle = BollwerkType.Button.copy(fontSize = 16.sp),
        )
        TechStatus.LOCKED -> Row(
            Modifier.fillMaxWidth().height(34.dp).clip(ChamferShape(8.dp)).background(Color(0xFF2A323C)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.drawable.ic_lock), null, Modifier.size(13.dp), colorFilter = ColorFilter.tint(HudColors.Label))
            val req = card.requiresDeviceId?.let { contentNameRes(it) }?.let { stringResource(it) } ?: ""
            Text(
                stringResource(R.string.tech_requires, req).uppercase(), Modifier.padding(start = 6.dp),
                style = HudType.ItemLabel.copy(fontSize = 10.sp, letterSpacing = 0.05.em), color = HudColors.Label, maxLines = 1, softWrap = false,
            )
        }
    }
}
