package de.bollwerk.app.ui.game.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.game.AimPanelState
import de.bollwerk.app.game.HudUiState
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.Rajdhani
import kotlin.math.roundToInt

private val AimBlue = Color(0xFF6FB8F0)
private val WeaponTitle = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = 0.14.em, lineHeight = 22.sp)
private val AngleValue = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 30.sp, fontFeatureSettings = "tnum", lineHeight = 32.sp)

/**
 * Untere Leiste im Zielmodus (Mockup 4): links BAUEN, Mitte Waffenkarte (Tippen = nächste Waffe), Kraft-Regler und
 * Tür-Schalter; rechts bleibt Platz für den runden FEUER-Button ([FireButton]). Ohne Befehlsrecht (`hud.canCommand`) ist
 * die Leiste ausgegraut und sendet nichts (auch kein `SetAim` vom Regler).
 */
@Composable
fun AimToolbar(hud: HudUiState, actions: HudActions, modifier: Modifier = Modifier) {
    val aim = hud.aim
    val active = hud.canCommand
    Row(
        modifier.fillMaxWidth().height(ToolbarHeight + 6.dp).blockTouches().alpha(if (active) 1f else InactiveAlpha),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModeButton(aimTarget = false, onClick = actions::enterBuildMode, modifier = Modifier.width(64.dp).fillMaxHeight(), enabled = active)
        HudPanel(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxHeight().padding(start = 8.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WeaponCard(aim, actions::cycleWeapon, Modifier.weight(1.15f), active)
                PowerControl(aim, actions::setPower, Modifier.weight(1.25f), active)
                if (aim.doorCount > 0) DoorToggle(aim.doorsOpen, { actions.setDoorsOpen(!aim.doorsOpen) }, active)
            }
        }
        Box(Modifier.width(FireButtonSize - 8.dp))
    }
}

@Composable
private fun WeaponCard(aim: AimPanelState, onCycle: () -> Unit, modifier: Modifier, enabled: Boolean) {
    val cd = stringResource(R.string.aim_next_weapon_cd)
    Row(
        modifier.hudClickable(onCycle, cd, enabled = enabled && aim.weaponCount > 0),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val shape = RoundedCornerShape(7.dp)
        Box(
            Modifier.size(46.dp).clip(shape).background(Color(0xFF1B2733)).border(2.dp, AimBlue, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (aim.weaponDeviceId != null) PartIcon(aim.weaponDeviceId, Modifier.size(36.dp))
        }
        Column(Modifier.weight(1f)) {
            val name = aim.weaponDeviceId?.let { contentNameRes(it) }?.let { stringResource(it) } ?: stringResource(R.string.aim_no_weapon)
            Text(name.uppercase(), style = WeaponTitle, color = BollwerkColors.Text, maxLines = 1, softWrap = false)
            val kind = aim.weaponId?.let { weaponKindRes(it) }?.let { stringResource(it) }
            val sub = when {
                aim.weaponDeviceId == null -> stringResource(R.string.aim_pick_weapon)
                kind != null && aim.splashText != null -> stringResource(R.string.aim_splash, kind, aim.splashText)
                else -> kind ?: ""
            }
            Text(sub, style = HudType.Small.copy(fontSize = 13.sp), color = HudColors.Label, maxLines = 1, softWrap = false)
        }
    }
}

/** KRAFT-Regler (Verlauf Rost → Gelb, weißer Knopf); setzt beim Loslassen die Kraft der Waffe. */
@Composable
private fun PowerControl(aim: AimPanelState, onSet: (Float) -> Unit, modifier: Modifier, enabled: Boolean) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(aim.power01) }
    val shown = if (dragging) local else aim.power01
    val set by rememberUpdatedState(onSet)
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.aim_power).uppercase(), Modifier.weight(1f), style = HudType.ChipLabel, color = HudColors.Label)
            Text("${(shown * 100f).roundToInt()} %", style = HudType.ChipValue.copy(fontSize = 18.sp, lineHeight = 20.sp), color = BollwerkColors.Text)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, MIN_POWER..1f)
                    if (enabled) setProgress { v -> set(v.coerceIn(MIN_POWER, 1f)); true }
                }
                .pointerInput(aim.weaponDeviceId, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val pad = 12.dp.toPx()
                        fun frac(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(MIN_POWER, 1f)
                        val down = awaitFirstDown()
                        dragging = true
                        local = frac(down.position.x)
                        drag(down.id) { c ->
                            c.consume()
                            local = frac(c.position.x)
                        }
                        set(local)
                        dragging = false
                    }
                },
        ) {
            val pad = 12.dp.toPx()
            val h = 8.dp.toPx()
            val cy = size.height / 2f
            val w = size.width - 2 * pad
            drawRoundRect(HudColors.Inset, Offset(pad, cy - h / 2), Size(w, h), CornerRadius(h / 2))
            val fw = w * shown.coerceIn(0f, 1f)
            drawRoundRect(
                Brush.horizontalGradient(listOf(BollwerkColors.RustDeep, BollwerkColors.Rust, BollwerkColors.Hazard), pad, pad + w),
                Offset(pad, cy - h / 2), Size(fw, h), CornerRadius(h / 2),
            )
            val kx = pad + fw
            drawCircle(Color.Black.copy(alpha = 0.35f), 11.dp.toPx(), Offset(kx + 1f, cy + 2f))
            drawCircle(Color(0xFFF2F5F8), 10.dp.toPx(), Offset(kx, cy))
            drawCircle(AimBlue, 10.dp.toPx(), Offset(kx, cy), style = Stroke(2.5.dp.toPx()))
        }
    }
}

@Composable
private fun DoorToggle(open: Boolean, onToggle: () -> Unit, enabled: Boolean) {
    val label = stringResource(if (open) R.string.aim_door_open else R.string.aim_door_closed)
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier
            .clip(shape)
            .background(HudColors.Inset)
            .border(1.dp, if (open) BollwerkColors.Hazard.copy(alpha = 0.7f) else BollwerkColors.SteelHi.copy(alpha = 0.3f), shape)
            .hudClickable(onToggle, label, enabled = enabled)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        PartIcon("door", Modifier.size(18.dp))
        Text(label.uppercase(), style = HudType.ItemLabel.copy(fontSize = 12.sp, letterSpacing = 0.16.em), color = BollwerkColors.Hazard.copy(alpha = if (open) 1f else 0.7f), maxLines = 1)
    }
}

val FireButtonSize = 86.dp

/**
 * FEUER-Button (Stil-Bibel §7): rund, Rost-Orange mit hellem Rand, Nachlade-Ring in hazard-Gelb, Restzeit darunter;
 * deaktiviert = entsättigt mit Ring-Fortschritt. [canCommand] = `false` (nicht am Zug) sperrt ihn ganz.
 */
@Composable
fun FireButton(aim: AimPanelState, onFire: () -> Unit, modifier: Modifier = Modifier, canCommand: Boolean = true) {
    val label = stringResource(R.string.aim_fire)
    val enabled = aim.weaponDeviceId != null && canCommand
    Box(
        modifier
            .size(FireButtonSize)
            .clip(RoundedCornerShape(50))
            .blockTouches()
            .hudClickable(onFire, label, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        val ready = aim.ready
        Canvas(Modifier.size(FireButtonSize)) {
            val c = center
            val outer = size.minDimension / 2f
            val ringW = 6.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.45f), outer, c)
            drawCircle(Color(0xFF3A3020), outer - ringW / 2, c, style = Stroke(ringW))
            val sweep = 360f * (if (ready) 1f else aim.reload01)
            if (sweep > 0f) {
                drawArc(
                    BollwerkColors.Hazard, -90f, sweep, false,
                    topLeft = Offset(c.x - outer + ringW / 2, c.y - outer + ringW / 2), size = Size(2 * outer - ringW, 2 * outer - ringW),
                    style = Stroke(ringW, cap = StrokeCap.Round),
                )
            }
            val r = outer - ringW - 3.dp.toPx()
            // Nicht bereit: entsättigt (Stahlgrau), nie braun-matt (Stil-Bibel §7)
            val top = if (ready) BollwerkColors.RustLight else Color(0xFF9AA3AE)
            val mid = if (ready) BollwerkColors.Rust else Color(0xFF66707C)
            val deep = if (ready) BollwerkColors.RustDeep else Color(0xFF3A424C)
            drawCircle(Brush.radialGradient(listOf(top, mid, deep), Offset(c.x, c.y - r * 0.35f), r * 1.4f), r, c)
            drawCircle(Color.White.copy(alpha = if (ready) 0.55f else 0.25f), r, c, style = Stroke(2.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label.uppercase(),
                style = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.12.em),
                color = BollwerkColors.Text.copy(alpha = if (ready) 1f else 0.75f),
                textAlign = TextAlign.Center,
            )
            val sub = when {
                aim.stillBuilding -> stringResource(R.string.aim_building)
                aim.reloadText.isNotEmpty() -> stringResource(R.string.aim_seconds, aim.reloadText)
                else -> null
            }
            if (sub != null) Text(sub, style = HudType.Small, color = BollwerkColors.Text.copy(alpha = 0.85f))
        }
    }
}

/** „WINKEL · KRAFT 52° · 78 %" (Mockup 4) mit Winddrift. */
@Composable
fun AngleCard(aim: AimPanelState, modifier: Modifier = Modifier) {
    HudPanel(modifier.widthIn(min = 118.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.aim_angle_power).uppercase(), style = HudType.ChipLabel, color = AimBlue)
            Text("${aim.angleDeg}° · ${aim.powerPercent} %", style = AngleValue, color = BollwerkColors.Text, maxLines = 1)
            if (aim.windDriftText != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Image(painterResource(R.drawable.ic_end_turn), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(AimBlue))
                    Text(stringResource(R.string.aim_wind_drift, aim.windDriftText), style = HudType.Small, color = BollwerkColors.Text)
                }
            }
        }
    }
}

private const val MIN_POWER = 0.3f
