package de.bollwerk.app.ui.game.hud

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import de.bollwerk.app.ui.components.FitText
import de.bollwerk.engine.sim.TurnPhase
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R
import de.bollwerk.app.game.HudUiState
import de.bollwerk.app.game.SelectedInfo
import de.bollwerk.app.game.ToolbarItem
import de.bollwerk.app.ui.game.tutorial.TutorialAnchorIds
import de.bollwerk.app.ui.game.tutorial.tutorialAnchor
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.engine.tools.ToolSelection

/** Höhe der unteren Leiste (Daumenzone). */
val ToolbarHeight: Dp = 58.dp

/** Deckkraft gesperrter Bedienelemente (nicht am Zug, Auflösungsphase, Spielende): ausgegraut, nie braun. */
const val InactiveAlpha: Float = 0.45f

/**
 * Untere Leiste im Baumodus (Mockup 3): links die Kostenbox des gewählten Werkzeugs, Mitte die Werkzeuge in Gruppen
 * (Materialien | Geräte inkl. Techbaum und „Waffen >" | Zurück, Reparatur), rechts (Hotseat) ZUG ENDE und der Modusschalter
 * ZIELEN. [weaponsOpen] zeigt die Waffen-Unterleiste darüber. Ohne Befehlsrecht (`hud.canCommand`, z. B. Hotseat-Auflösung)
 * ist die Leiste ausgegraut und nimmt keine Eingaben an.
 *
 * Breite: Die Einträge teilen sich die Mitte nach Gewicht, solange jeder mindestens [MinToolbarUnit] bekommt; auf schmaleren
 * Geräten (640–720 dp) scrollt die Mitte waagerecht und jeder Eintrag behält seine natürliche Breite (mind. 48 dp Touch-Ziel).
 */
@Composable
fun BuildToolbar(
    hud: HudUiState,
    weaponsOpen: Boolean,
    onToggleWeapons: () -> Unit,
    actions: HudActions,
    modifier: Modifier = Modifier,
) {
    val active = hud.canCommand
    // Unterleiste „Mehr": Reparatur, Abreißen, Tür auf/zu (wie „Waffen ›" eine Ebene darüber, damit die Hauptleiste nicht breiter wird)
    var toolsOpen by remember { mutableStateOf(false) }
    val toolsShown = moreToolsVisible(toolsOpen, active)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (weaponsOpen && active) {
            Row(Modifier.fillMaxWidth().padding(start = 64.dp, end = 64.dp), horizontalArrangement = Arrangement.End) {
                HudPanel {
                    Row(Modifier.height(ToolbarHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (w in hud.weapons) ToolItem(w, { actions.selectTool(ToolSelection.Device(w.index)) }, Modifier.width(64.dp), active)
                    }
                }
            }
        }
        if (toolsShown) {
            Row(Modifier.fillMaxWidth().padding(end = 64.dp), horizontalArrangement = Arrangement.End) {
                MoreToolsBar(hud, actions) { toolsOpen = false }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(ToolbarHeight).blockTouches().alpha(if (active) 1f else InactiveAlpha),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CostBox(hud.selected, Modifier.width(58.dp).fillMaxHeight())
            HudPanel(Modifier.weight(1f).fillMaxHeight()) {
                // Rechts fest stehend (nie scrollend): „Mehr ›"; links scrollen Material, Geräte, Zurück. Gleiche Breitenrechnung wie vor
                // der Unterleiste (das feste Element belegt den Platz des früheren Reparatur-Eintrags), damit auf 720/800 dp nichts wegfällt.
                BoxWithConstraints(Modifier.fillMaxHeight()) {
                    val totalWeight = hud.materials.size + hud.economy.size + TECH_WEIGHT + WEAPONS_WEIGHT + UNDO_WEIGHT
                    val unit = (maxWidth - ToolbarRowPadding * 2 - GroupDividerWidth * 2 - PinnedToolWidth) / totalWeight
                    val compact = unit < MinToolbarUnit
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ToolbarItems(hud, weaponsOpen, { toolsOpen = false; onToggleWeapons() }, actions, active, compact)
                        }
                        PlainItem(
                            label = stringResource(R.string.tool_more) + " ›",
                            selected = toolsShown || hud.repairSelected || hud.deleteSelected || hud.doorSelected,
                            onClick = {
                                if (!toolsShown && weaponsOpen) onToggleWeapons()
                                toolsOpen = !toolsShown
                            },
                            modifier = Modifier.width(PinnedToolWidth), boxed = true, interactive = active,
                            labelPadding = 2.dp,
                        ) { VectorIcon(R.drawable.ic_wrench, BollwerkColors.Text) }
                    }
                }
            }
            if (hud.showEndTurn) EndTurnButton(actions::endTurn, Modifier.width(64.dp).fillMaxHeight(), enabled = active)
            ModeButton(aimTarget = true, onClick = actions::enterAimMode, modifier = Modifier.width(64.dp).fillMaxHeight(), enabled = active)
        }
    }
}

/**
 * Unterleiste „Mehr": Reparatur, Abreißen und Tür auf/zu mit vollem Label (je ein Tipp-Werkzeug, vorher nur per Langdruck
 * erreichbar). Ein Tipp wählt das Werkzeug und schließt die Leiste; ein Tipp auf das gewählte Werkzeug hebt die Wahl auf.
 */
@Composable
internal fun MoreToolsBar(hud: HudUiState, actions: HudActions, close: () -> Unit) {
    fun pick(selected: Boolean, tool: ToolSelection) {
        actions.pickMoreTool(selected, tool)
        close()
    }
    HudPanel {
        Row(Modifier.height(ToolbarHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PlainItem(
                label = stringResource(R.string.tool_repair), selected = hud.repairSelected,
                onClick = { pick(hud.repairSelected, ToolSelection.Repair) }, modifier = Modifier.width(MoreToolWidth),
                boxed = true, labelPadding = 2.dp,
            ) { VectorIcon(R.drawable.ic_wrench, BollwerkColors.Text) }
            PlainItem(
                label = stringResource(R.string.tool_delete), selected = hud.deleteSelected,
                onClick = { pick(hud.deleteSelected, ToolSelection.Delete) }, modifier = Modifier.width(MoreToolWidth),
                boxed = true, labelPadding = 2.dp,
            ) { VectorIcon(R.drawable.ic_delete, BollwerkColors.Text) }
            PlainItem(
                label = stringResource(R.string.tool_door), selected = hud.doorSelected,
                onClick = { pick(hud.doorSelected, ToolSelection.Door) }, modifier = Modifier.width(MoreToolWidth),
                boxed = true, labelPadding = 2.dp,
            ) { VectorIcon(R.drawable.ic_swap_layout, BollwerkColors.Text) }
        }
    }
}

/** Die Unterleiste „Mehr" ist nur mit Befehlsrecht offen (ohne: ausgegraut, keine Eingaben). */
internal fun moreToolsVisible(open: Boolean, canCommand: Boolean): Boolean = open && canCommand

/** Tipp auf ein Werkzeug der Unterleiste: wählt es, ein Tipp auf das gewählte hebt die Wahl auf. */
internal fun HudActions.pickMoreTool(selected: Boolean, tool: ToolSelection) = selectTool(if (selected) ToolSelection.None else tool)

private val MoreToolWidth = 84.dp

/** „Zug beenden" anbieten: Hotseat, eigene Spielphase, mit Befehlsrecht. */
val HudUiState.showEndTurn: Boolean
    get() = hotseat && turn?.phase == TurnPhase.PLAY && canCommand

@Composable
private fun ToolbarItems(
    hud: HudUiState,
    weaponsOpen: Boolean,
    onToggleWeapons: () -> Unit,
    actions: HudActions,
    active: Boolean,
    compact: Boolean,
) {
    val scroll = rememberScrollState()
    val rowModifier = if (compact) Modifier.fillMaxHeight().scrollFade(scroll).horizontalScroll(scroll) else Modifier.fillMaxSize()
    Row(rowModifier.padding(start = ToolbarRowPadding), verticalAlignment = Alignment.CenterVertically) {
        // Gewichtete Breite oder (kompakt) natürliche Breite mit Mindestmaß
        fun RowScope.slot(weight: Float): Modifier = if (compact) Modifier.widthIn(min = MinToolbarUnit) else Modifier.weight(weight)
        val pad = if (compact) CompactLabelPadding else 0.dp
        for (m in hud.materials) {
            ToolItem(m, { actions.selectTool(ToolSelection.Material(m.index)) }, slot(1f), active, labelPadding = pad)
        }
        GroupDivider()
        for (d in hud.economy) ToolItem(d, { actions.selectTool(ToolSelection.Device(d.index)) }, slot(1f), active, labelPadding = pad)
        PlainItem(
            label = stringResource(R.string.tool_techtree), selected = false, onClick = actions::openTechTree, modifier = slot(TECH_WEIGHT),
            badge = hud.anyTechBuilding, interactive = active, labelPadding = pad,
        ) { PartIcon("workshop", Modifier.size(26.dp)) }
        PlainItem(
            label = stringResource(R.string.tool_weapons) + " ›", selected = hud.weaponsSelected || weaponsOpen,
            onClick = onToggleWeapons, modifier = slot(WEAPONS_WEIGHT), interactive = active, labelPadding = pad,
        ) { PartIcon("mortar", Modifier.size(26.dp)) }
        GroupDivider()
        PlainItem(
            label = stringResource(R.string.tool_undo), selected = false, onClick = actions::undo, modifier = slot(UNDO_WEIGHT),
            enabled = hud.canUndo, boxed = true, interactive = active, labelPadding = pad,
        ) { VectorIcon(R.drawable.ic_undo, if (hud.canUndo) BollwerkColors.Text else HudColors.Label) }
    }
}

/** Breite des festen „Mehr ›"-Eintrags (48 dp Touch-Ziel plus Rand); entspricht dem früheren Reparatur-Eintrag der kompakten Leiste. */
private val PinnedToolWidth = 52.dp

private const val TECH_WEIGHT = 1.25f
private const val WEAPONS_WEIGHT = 1.1f
private const val UNDO_WEIGHT = 1f
private val ToolbarRowPadding = 4.dp
private val GroupDividerWidth = 8.dp
private val CompactLabelPadding = 4.dp

/** Kleinste Breite je Gewichtseinheit: ein Material-Eintrag behält so 48 dp Touch-Ziel (2 dp Rand je Seite). */
val MinToolbarUnit: Dp = 52.dp

/**
 * Blendet die Ränder einer waagerecht scrollenden Leiste aus, an denen noch Einträge folgen (Hinweis „hier geht es weiter"
 * statt eines hart abgeschnittenen Labels). Beachtet die Leserichtung (Linkshänder: Rtl, Anfang rechts).
 */
private fun Modifier.scrollFade(scroll: ScrollState): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = ScrollFadeWidth.toPx()
        val rtl = layoutDirection == LayoutDirection.Rtl
        // Rtl: „vorwärts" liegt links, „zurück" rechts
        val fadeRight = if (rtl) scroll.value > 0 else scroll.value < scroll.maxValue
        val fadeLeft = if (rtl) scroll.value < scroll.maxValue else scroll.value > 0
        if (fadeRight) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height), blendMode = BlendMode.DstOut,
            )
        }
        if (fadeLeft) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = 0f, endX = fade),
                size = Size(fade, size.height), blendMode = BlendMode.DstOut,
            )
        }
    }

private val ScrollFadeWidth = 28.dp

/** Inhalt eines gespiegelten Bereichs (Linkshänder) in Leserichtung: gespiegelt wird nur die Anordnung, nie Text/Zahlen. */
@Composable
fun LtrContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

@Composable
private fun VectorIcon(icon: Int, tint: Color) {
    Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(tint))
}

@Composable
private fun RowScope.GroupDivider() {
    Box(Modifier.padding(horizontal = 3.dp).width(2.dp).height(34.dp).background(Color(0xFF3A4450)))
}

/** Kostenbox: Name des gewählten Werkzeugs und Kosten („HOLZ · 4 ⚙/m"). */
@Composable
fun CostBox(selected: SelectedInfo?, modifier: Modifier = Modifier) = LtrContent {
    HudPanel(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val nameRes = selected?.let { contentNameRes(it.id) } ?: R.string.tool_none
            // Lange Namen (Tür auf/zu, Abreißen) passen auch bei 10 sp nicht in die 58-dp-Box: dann die Kurzfassung
            val short = when (selected?.id) {
                "repair" -> stringResource(R.string.tool_repair_short)
                "delete" -> stringResource(R.string.tool_delete_short)
                "door_tool" -> stringResource(R.string.tool_door_short)
                else -> null
            }
            val name = stringResource(nameRes).uppercase()
            FitText(
                name, style = HudType.ChipLabel.copy(fontSize = 10.sp, letterSpacing = if (name.length > 7) 0.02.em else 0.12.em),
                color = HudColors.Label, textAlign = TextAlign.Center, fallbacks = listOfNotNull(short?.uppercase()),
            )
            if (selected != null && selected.kind != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(selected.costMetal.toString(), style = HudType.ChipValue.copy(fontSize = 20.sp, lineHeight = 22.sp), color = BollwerkColors.Text)
                    Image(painterResource(R.drawable.ic_gear), null, Modifier.padding(start = 1.dp, bottom = 3.dp).size(11.dp), colorFilter = ColorFilter.tint(BollwerkColors.Text))
                    if (selected.perMeter) Text(stringResource(R.string.hud_per_meter), Modifier.padding(bottom = 1.dp), style = HudType.Small, color = HudColors.Label)
                }
                if (selected.costEnergy > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.costEnergy.toString(), style = HudType.Small, color = BollwerkColors.Energy)
                        Image(painterResource(R.drawable.ic_bolt), null, Modifier.size(10.dp), colorFilter = ColorFilter.tint(BollwerkColors.Energy))
                    }
                }
            } else if (selected != null) {
                val icon = when (selected.id) {
                    "delete" -> R.drawable.ic_delete
                    "door_tool" -> R.drawable.ic_swap_layout
                    else -> R.drawable.ic_wrench
                }
                Image(painterResource(icon), null, Modifier.padding(top = 2.dp).size(20.dp), colorFilter = ColorFilter.tint(BollwerkColors.Text))
            }
        }
    }
}

/** Werkzeug mit Bauteil-Icon, Sperr- und Kostenzustand. */
@Composable
fun ToolItem(item: ToolbarItem, onClick: () -> Unit, modifier: Modifier = Modifier, interactive: Boolean = true, labelPadding: Dp = 0.dp) {
    val label = contentNameRes(item.id)?.let { stringResource(it) } ?: item.id
    PlainItem(
        label = label,
        selected = item.selected,
        onClick = onClick,
        modifier = modifier.tutorialAnchor(TutorialAnchorIds.tool(item.id)),
        enabled = !item.locked,
        warn = !item.affordable && !item.locked,
        locked = item.locked,
        interactive = interactive,
        labelPadding = labelPadding,
    ) { PartIcon(item.id, Modifier.size(width = 34.dp, height = 26.dp), dim = item.locked) }
}

/**
 * Grundform eines Toolbar-Eintrags: Icon über Versalien-Label; gewählt = Rost-Rahmen (Mockup 3: HOLZ). [boxed] gibt eine
 * leicht abgesetzte Fläche (Zurück/Reparatur). [interactive] = `false` sperrt den Eintrag ganz (kein Befehlsrecht).
 */
@Composable
fun PlainItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    warn: Boolean = false,
    locked: Boolean = false,
    boxed: Boolean = false,
    badge: Boolean = false,
    interactive: Boolean = true,
    /** Innenabstand des Labels (kompakte, scrollende Leiste: Einträge so breit wie ihr Label). */
    labelPadding: Dp = 0.dp,
    icon: @Composable BoxScope.() -> Unit,
) = LtrContent {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(shape)
            .then(if (boxed) Modifier.background(Color(0xFF2C3540)) else Modifier)
            .then(if (selected) Modifier.background(Color(0x332A1A10)).border(2.dp, HudColors.Selected, shape) else Modifier)
            .semantics { this.selected = selected }
            .hudClickable(onClick, label, enabled = interactive && (enabled || locked)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = labelPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) { icon() }
            // Passt sich der Breite an (enger, dann kleiner bis 10 sp), statt abzuschneiden
            FitText(
                label.uppercase(),
                style = HudType.ItemLabel.copy(letterSpacing = if (label.length > 8) 0.04.em else 0.1.em),
                color = when {
                    locked -> HudColors.Label.copy(alpha = 0.6f)
                    warn -> Color(0xFFE87A6E)
                    selected -> BollwerkColors.Text
                    enabled -> Color(0xFFD5DCE4)
                    else -> HudColors.Label
                },
                textAlign = TextAlign.Center,
            )
        }
        if (locked) LockBadge(Modifier.align(Alignment.TopEnd).padding(2.dp))
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(7.dp).clip(RoundedCornerShape(4.dp)).background(BollwerkColors.Hazard))
    }
}

/** Modusschalter ZIELEN (Fadenkreuz) bzw. BAUEN (Schraubenschlüssel). */
@Composable
fun ModeButton(aimTarget: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = LtrContent {
    val label = stringResource(if (aimTarget) R.string.mode_aim else R.string.mode_build)
    HudPanel(modifier.tutorialAnchor(if (aimTarget) TutorialAnchorIds.AIM_MODE else TutorialAnchorIds.BUILD_MODE)) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight().hudClickable(onClick, label, enabled = enabled),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painterResource(if (aimTarget) R.drawable.ic_target else R.drawable.ic_wrench), null, Modifier.size(28.dp),
                colorFilter = ColorFilter.tint(if (aimTarget) BollwerkColors.Hazard else BollwerkColors.Text),
            )
            FitText(label.uppercase(), Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp), style = HudType.ItemLabel.copy(letterSpacing = 0.16.em), color = BollwerkColors.Text)
        }
    }
}

/**
 * ZUG ENDE (Hotseat) in der unteren Leiste neben dem Modusschalter: beschriftet, in der Daumenzone und weit weg von Pause
 * (vorher ein unbeschrifteter Knopf oben neben Pause).
 */
@Composable
fun EndTurnButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = LtrContent {
    val label = stringResource(R.string.end_turn_short)
    val description = stringResource(R.string.end_turn)
    HudPanel(modifier, border = BollwerkColors.Hazard.copy(alpha = 0.55f)) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight().hudClickable(onClick, description, enabled = enabled),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.drawable.ic_end_turn), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(BollwerkColors.Hazard))
            FitText(label.uppercase(), Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp), style = HudType.ItemLabel.copy(letterSpacing = 0.1.em), color = BollwerkColors.Text)
        }
    }
}
