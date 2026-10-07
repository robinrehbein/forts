package de.bollwerk.app.ui.game.hud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.game.HudUiState
import de.bollwerk.app.ui.components.consumeTaps
import de.bollwerk.app.ui.game.GameToast
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.engine.tools.ContextMenu
import de.bollwerk.engine.tools.TapAction
import de.bollwerk.engine.tools.ToolMode
import kotlin.math.roundToInt

/** Rechnet einen Weltpunkt in Bildschirm-Pixel der Spielfläche um (Kamera der Partie); null = unbekannt. */
fun interface WorldToScreen {
    fun toScreen(worldX: Float, worldY: Float): Offset?
}

/**
 * Das Spiel-HUD über der Spielfläche (Mockups 3/4): Chips oben, Gegner-Chip, Hinweis-Toast, untere Leiste (Bauen bzw.
 * Zielen mit FEUER), Winkel-Karte, Kontextmenü (Langdruck). [leftHanded] spiegelt die untere Leiste samt FEUER-Button.
 * Liest nur [hud] (10-Hz-Abtastung, stabile Datenklassen).
 */
@Composable
fun GameHud(
    hud: HudUiState,
    toast: GameToast?,
    leftHanded: Boolean,
    actions: HudActions,
    modifier: Modifier = Modifier,
    worldToScreen: WorldToScreen = WorldToScreen { _, _ -> null },
) {
    var weaponsOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            HudTopBar(hud, actions::pause, actions::endTurn, Modifier.align(Alignment.TopCenter))
            EnemyChip(hud, Modifier.align(Alignment.TopEnd).padding(top = 64.dp))
            if (toast != null) ToastChip(toast, Modifier.align(Alignment.TopCenter).padding(top = 54.dp))

            val aimMode = hud.mode == ToolMode.AIM
            if (aimMode && hud.aim.weaponDeviceId != null) {
                AngleCard(hud.aim, Modifier.align(Alignment.TopStart).offset(x = w * 0.25f, y = h * 0.27f))
            }
            val dir = if (leftHanded) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides dir) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    if (aimMode) {
                        AimToolbar(hud, actions, Modifier.align(Alignment.BottomCenter))
                        Box(Modifier.align(Alignment.BottomEnd).offset(y = 6.dp)) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                FireButton(hud.aim, actions::fire, canCommand = hud.canCommand)
                            }
                        }
                    } else {
                        val closing = remember(actions) { ToolbarClosingActions(actions) { weaponsOpen = false } }
                        BuildToolbar(hud, weaponsOpen, { weaponsOpen = !weaponsOpen }, closing)
                    }
                }
            }
        }
        val menu = hud.contextMenu
        if (menu != null) ContextMenuPopup(menu, worldToScreen, actions)
    }
}

/** Schließt die Waffen-Unterleiste nach einer Werkzeugwahl. */
private class ToolbarClosingActions(private val base: HudActions, private val close: () -> Unit) : HudActions by base {
    override fun selectTool(selection: de.bollwerk.engine.tools.ToolSelection) {
        close()
        base.selectTool(selection)
    }

    override fun enterAimMode() {
        close()
        base.enterAimMode()
    }

    override fun openTechTree() {
        close()
        base.openTechTree()
    }
}

/** Hinweis-Chip (Ablehnungsgrund), rot umrandet, verschwindet nach kurzer Zeit (ViewModel). */
@Composable
fun ToastChip(toast: GameToast, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clip(shape)
            .background(Color(0xEE2A1416))
            .border(1.5.dp, BollwerkColors.TeamRed, shape)
            .blockTouches()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(BollwerkColors.TeamRed))
        Text(
            stringResource(rejectReasonRes(toast.reason)).uppercase(),
            style = HudType.ItemLabel.copy(fontSize = 13.sp, letterSpacing = 0.14.em),
            color = BollwerkColors.Text,
            maxLines = 1,
        )
    }
}

/** Kontextmenü (Langdruck) am Ziel: Reparieren / Abreißen / Tür, gesperrte Einträge mit Grund. Tippen daneben schließt. */
@Composable
fun ContextMenuPopup(menu: ContextMenu, worldToScreen: WorldToScreen, actions: HudActions) {
    val anchor = worldToScreen.toScreen(menu.x, menu.y)
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().consumeTapsThen(actions::dismissContext)) {
        val pos = if (anchor != null) {
            with(density) { IntOffset((anchor.x - 90.dp.toPx()).roundToInt().coerceAtLeast(8), (anchor.y - 150.dp.toPx()).roundToInt().coerceAtLeast(8)) }
        } else null
        HudPanel(
            (if (pos != null) Modifier.offset { pos } else Modifier.align(Alignment.Center)).widthIn(min = 180.dp),
            border = BollwerkColors.Rust.copy(alpha = 0.8f),
        ) {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (opt in menu.options) {
                    val (icon, label) = when (opt.action) {
                        TapAction.REPAIR -> R.drawable.ic_wrench to stringResource(R.string.ctx_repair)
                        TapAction.DELETE -> R.drawable.ic_delete to stringResource(
                            if (opt.hint.burning) R.string.ctx_extinguish else R.string.ctx_delete,
                        )
                        TapAction.DOOR -> R.drawable.ic_swap_layout to stringResource(
                            if (opt.hint.doorOpen) R.string.ctx_door_close else R.string.ctx_door_open,
                        )
                    }
                    val shape = RoundedCornerShape(6.dp)
                    Row(
                        Modifier
                            .clip(shape)
                            .background(Color(0xFF2C3540))
                            .hudClickable({ actions.chooseContext(opt.action) }, label, enabled = opt.enabled)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .widthIn(min = 160.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val c = if (opt.enabled) BollwerkColors.Text else HudColors.Label.copy(alpha = 0.6f)
                        Image(painterResource(icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(c))
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(label.uppercase(), style = HudType.ItemLabel.copy(fontSize = 13.sp, letterSpacing = 0.12.em), color = c)
                            val reason = opt.reason
                            if (!opt.enabled && reason != null) {
                                Text(stringResource(rejectReasonRes(reason)), style = HudType.Small.copy(fontSize = 11.sp), color = Color(0xFFE87A6E))
                            }
                        }
                        val amount = when (opt.action) {
                            TapAction.REPAIR -> opt.hint.costMetal.takeIf { it >= 0.5f }?.let { "−${it.roundToInt()}" }
                            TapAction.DELETE -> opt.hint.refundMetal.takeIf { it >= 0.5f }?.let { "+${it.roundToInt()}" }
                            TapAction.DOOR -> null
                        }
                        if (amount != null) {
                            Text(amount, style = HudType.Small, color = if (opt.action == TapAction.DELETE) BollwerkColors.Ok else BollwerkColors.Text)
                            Image(painterResource(R.drawable.ic_gear), null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(BollwerkColors.Text))
                        }
                    }
                }
            }
        }
    }
}

/** Fängt Taps neben dem Menü ab und schließt es. */
private fun Modifier.consumeTapsThen(onTap: () -> Unit): Modifier = this.hudClickable(onTap)

/** Ladeanzeige, während die Partie angelegt wird. */
@Composable
fun LoadingOverlay(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).consumeTaps(), contentAlignment = Alignment.Center) {
        HudPanel {
            Text(
                stringResource(R.string.game_loading).uppercase(), Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = HudType.ItemLabel.copy(fontSize = 15.sp, letterSpacing = 0.18.em), color = BollwerkColors.Text,
            )
        }
    }
}
