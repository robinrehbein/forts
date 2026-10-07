package de.bollwerk.app.game

import androidx.compose.runtime.Immutable
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.tools.ContextMenu
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.engine.view.HudModel
import kotlin.math.abs
import kotlin.math.roundToInt

/** Art eines Toolbar-Eintrags. */
enum class ItemKind { MATERIAL, DEVICE, WEAPON }

/** Ein Eintrag der Toolbar mit Sperr-/Bezahlbarkeitszustand (Stil-Bibel §7). */
@Immutable
data class ToolbarItem(
    val kind: ItemKind,
    /** Content-Index (Material bzw. Gerät). */
    val index: Int,
    /** Content-ID (Name und Icon in der UI). */
    val id: String,
    val selected: Boolean,
    /** Tech fehlt (Schloss). */
    val locked: Boolean,
    /** Genug Metall/Energie (bei Materialien: für einen Meter). */
    val affordable: Boolean,
    val costMetal: Int,
    val costEnergy: Int,
)

/** Kostenbox links der Toolbar (Mockup 3: „HOLZ 4 ⚙/m"). */
@Immutable
data class SelectedInfo(val id: String, val kind: ItemKind?, val costMetal: Int, val costEnergy: Int, val perMeter: Boolean)

/** Zielen-Panel (Mockup 4). */
@Immutable
data class AimPanelState(
    /** Waffen-Gerät (Content-ID) oder null, wenn keine Waffe gewählt ist. */
    val weaponDeviceId: String?,
    /** Waffen-ID (Untertitel/Splash) oder null. */
    val weaponId: String?,
    val splashText: String?,
    /** Kraft 0..1 (Schieberegler) und in Prozent. */
    val power01: Float,
    val powerPercent: Int,
    val angleDeg: Int,
    val windDriftText: String?,
    /** Nachlade-Ring 0..1 (1 = bereit) und Restzeit-Text („1,4 s"), leer wenn bereit. */
    val reload01: Float,
    val ready: Boolean,
    val reloadText: String,
    val stillBuilding: Boolean,
    val doorCount: Int,
    val doorsOpen: Boolean,
    /** Eigene Waffen insgesamt (Waffenkarte: Tippen wechselt). */
    val weaponCount: Int,
    /** Position der gewählten Waffe unter den eigenen (1-basiert, „WAFFE 1 / 3"), 0 = keine gewählt. */
    val weaponNumber: Int = 0,
)

enum class TechStatus { BUILT, BUILDING, AVAILABLE, LOCKED }

/** Freischaltung in einer Techbaum-Karte (Mockup 5). */
@Immutable
data class UnlockTile(
    val id: String,
    val isWeapon: Boolean,
    val damage: Int,
    val damageSecondary: Int,
    val range: Int,
    val shotMetal: Int,
    val shotEnergy: Int,
    val costPerMeter: Int,
    /** Gewähltes Darstellungsmuster der Schadenszeile. */
    val damageStyle: DamageStyle,
)

enum class DamageStyle { PLAIN, SALVO, INCENDIARY, BEAM, MATERIAL }

@Immutable
data class TechCard(
    val deviceIndex: Int,
    val deviceId: String,
    val status: TechStatus,
    val progress01: Float,
    val secondsLeft: Int,
    val costMetal: Int,
    val costEnergy: Int,
    val buildSeconds: Int,
    val affordable: Boolean,
    /** Fehlende Voraussetzung (Geräte-ID des Gebäudes) bei [TechStatus.LOCKED]. */
    val requiresDeviceId: String?,
    val unlocks: List<UnlockTile>,
)

/** Zug-Chip im Hotseat. */
@Immutable
data class TurnInfo(val phase: TurnPhase, val secondsLeft: Int, val activePlayer: Int, val turnNumber: Int)

/**
 * Alles, was die Compose-HUD zeigt, als stabile, unveränderliche Daten (Rekomposition nur bei geänderten Werten, und durch
 * die 10-Hz-Abtastung der Quelle höchstens 10× je Sekunde).
 */
@Immutable
data class HudUiState(
    val metal: Int = 0,
    val metalRate: Int = 0,
    val energy: Int = 0,
    val energyCap: Int = 0,
    val energyRate: Int = 0,
    val energyFill: Float = 0f,
    val timeText: String = "00:00",
    val windText: String = "0,0",
    /** Wind nach rechts (Pfeil), sonst links. */
    val windToRight: Boolean = true,
    val enemyPlayerNumber: Int = 2,
    val enemyIsRed: Boolean = true,
    val enemyPercent: Int = 100,
    val enemyFill: Float = 1f,
    val hotseat: Boolean = false,
    val turn: TurnInfo? = null,
    val canCommand: Boolean = true,
    val paused: Boolean = false,
    val mode: ToolMode = ToolMode.NONE,
    val materials: List<ToolbarItem> = emptyList(),
    val economy: List<ToolbarItem> = emptyList(),
    val weapons: List<ToolbarItem> = emptyList(),
    val weaponsSelected: Boolean = false,
    val repairSelected: Boolean = false,
    val canUndo: Boolean = false,
    val selected: SelectedInfo? = null,
    val aim: AimPanelState = EMPTY_AIM,
    val techs: List<TechCard> = emptyList(),
    val anyTechBuilding: Boolean = false,
    val contextMenu: ContextMenu? = null,
    val previewRejected: RejectReason? = null,
    val localPlayer: Int = 0,
) {
    companion object {
        val EMPTY_AIM = AimPanelState(
            weaponDeviceId = null, weaponId = null, splashText = null, power01 = 0.75f, powerPercent = 75, angleDeg = 0,
            windDriftText = null, reload01 = 1f, ready = false, reloadText = "", stillBuilding = false, doorCount = 0,
            doorsOpen = false, weaponCount = 0,
        )
    }
}

/** Zahlenformate des HUD (locale-gesteuertes Dezimalzeichen, Ziffern immer ASCII). */
object HudFormat {
    /** „mm:ss". */
    fun clock(seconds: Float): String {
        val s = if (seconds.isFinite() && seconds > 0f) seconds.toInt() else 0
        val m = s / 60
        val r = s % 60
        return (if (m < 10) "0$m" else "$m") + ":" + (if (r < 10) "0$r" else "$r")
    }

    /** Eine Nachkommastelle mit [sep] („3,2"). */
    fun decimal1(v: Float, sep: Char): String {
        val t = (abs(v) * 10f).roundToInt()
        val sign = if (v < 0f && t != 0) "-" else ""
        return "$sign${t / 10}$sep${t % 10}"
    }

    /** Ganze Zahl mit Vorzeichen („+3", „-2"). */
    fun signed(v: Float): String {
        val i = v.roundToInt()
        return if (i > 0) "+$i" else "$i"
    }
}

/** Bildet [HudModel] (Sim) + [ToolUiState] (Werkzeuge) + [GameCatalog] (Content) auf [HudUiState] ab. Rein, testbar. */
object HudPresenter {
    fun present(
        hud: HudModel,
        tools: ToolUiState,
        catalog: GameCatalog,
        hotseat: Boolean,
        decimalSeparator: Char = ',',
    ): HudUiState {
        val metal = hud.metal
        val energy = hud.energy
        val unlocked = hud.unlockedTechs
        fun locked(tech: Int) = tech >= 0 && tech !in unlocked
        val sel = tools.selection
        val materials = catalog.materials.map { m ->
            ToolbarItem(
                ItemKind.MATERIAL, m.index, m.id,
                selected = sel is ToolSelection.Material && sel.index == m.index,
                locked = locked(m.requiredTech),
                affordable = metal >= m.costPerM,
                costMetal = m.costPerM.roundToInt(), costEnergy = 0,
            )
        }
        fun deviceItem(kind: ItemKind, d: DeviceItem) = ToolbarItem(
            kind, d.index, d.id,
            selected = sel is ToolSelection.Device && sel.index == d.index,
            locked = locked(d.requiredTech),
            affordable = metal >= d.costMetal && energy >= d.costEnergy,
            costMetal = d.costMetal.roundToInt(), costEnergy = d.costEnergy.roundToInt(),
        )
        val economy = catalog.economy.map { deviceItem(ItemKind.DEVICE, it) }
        val weapons = catalog.weapons.map { deviceItem(ItemKind.WEAPON, it.device) }
        val selected = when (sel) {
            is ToolSelection.Material -> catalog.materialByIndex(sel.index)?.let {
                SelectedInfo(it.id, ItemKind.MATERIAL, it.costPerM.roundToInt(), 0, perMeter = true)
            }
            is ToolSelection.Device -> catalog.devices.getOrNull(sel.index)?.let {
                val k = if (catalog.weaponByDevice(it.index) != null) ItemKind.WEAPON else ItemKind.DEVICE
                SelectedInfo(it.id, k, it.costMetal.roundToInt(), it.costEnergy.roundToInt(), perMeter = false)
            }
            ToolSelection.Repair -> SelectedInfo("repair", null, 0, 0, perMeter = false)
            ToolSelection.Delete -> SelectedInfo("delete", null, 0, 0, perMeter = false)
            ToolSelection.Door -> SelectedInfo("door_tool", null, 0, 0, perMeter = false)
            ToolSelection.None -> null
        }
        val turns = hud.turnMode == TurnMode.TURNS
        val turn = if (!turns) null else TurnInfo(
            phase = hud.turnPhase,
            secondsLeft = ceilSeconds(
                when (hud.turnPhase) {
                    TurnPhase.PLAY -> hud.turnSecondsLeft
                    TurnPhase.RESOLVE -> hud.resolveSecondsLeft
                    TurnPhase.HANDOVER -> hud.handoverSecondsLeft
                },
            ),
            activePlayer = hud.activePlayer,
            turnNumber = hud.turnNumber,
        )
        val enemy = if (hud.localPlayer == 0) 1 else 0
        return HudUiState(
            metal = metal.toInt(),
            metalRate = hud.metalRate.roundToInt(),
            energy = energy.toInt(),
            energyCap = hud.energyCap.roundToInt(),
            energyRate = hud.energyRate.roundToInt(),
            energyFill = if (hud.energyCap > 0f) (energy / hud.energyCap).coerceIn(0f, 1f) else 0f,
            timeText = HudFormat.clock(hud.timeSeconds),
            windText = HudFormat.decimal1(abs(hud.windSpeed), decimalSeparator),
            windToRight = hud.windSpeed >= 0f,
            enemyPlayerNumber = enemy + 1,
            enemyIsRed = enemy == 1,
            enemyPercent = (hud.enemyReactor01 * 100f).roundToInt().coerceIn(0, 100),
            enemyFill = hud.enemyReactor01.coerceIn(0f, 1f),
            hotseat = hotseat,
            turn = turn,
            canCommand = hud.canCommand,
            paused = hud.paused,
            mode = tools.mode,
            materials = materials,
            economy = economy,
            weapons = weapons,
            weaponsSelected = sel is ToolSelection.Device && catalog.weaponByDevice(sel.index) != null,
            repairSelected = sel == ToolSelection.Repair,
            canUndo = tools.canUndo,
            selected = selected,
            aim = aimPanel(hud, tools, catalog, decimalSeparator),
            techs = techCards(hud, catalog),
            anyTechBuilding = hud.buildingTech.isNotEmpty(),
            contextMenu = tools.contextMenu,
            previewRejected = tools.previewRejected,
            localPlayer = hud.localPlayer,
        )
    }

    private fun ceilSeconds(s: Float): Int {
        if (!(s > 0f)) return 0
        val i = s.toInt()
        return if (s - i > 1e-3f) i + 1 else i
    }

    fun aimPanel(hud: HudModel, tools: ToolUiState, catalog: GameCatalog, sep: Char): AimPanelState {
        val w = hud.weapons.firstOrNull { it.deviceRef == tools.weaponRef }
        val item = w?.let { catalog.weaponByDevice(it.typeId) }
        val aim = tools.aim?.takeIf { it.deviceRef == tools.weaponRef }
        val power = aim?.power ?: w?.power ?: 0.75f
        val reloadLeft = if (w == null || item == null) 0f else (1f - w.reload01).coerceIn(0f, 1f) * item.reloadSeconds
        return AimPanelState(
            weaponDeviceId = item?.device?.id,
            weaponId = item?.weaponId,
            splashText = item?.takeIf { it.splashRadius > 0f }?.let { HudFormat.decimal1(it.splashRadius, sep) },
            power01 = power.coerceIn(0f, 1f),
            powerPercent = (power * 100f).roundToInt(),
            angleDeg = (aim?.elevationDeg ?: 0f).roundToInt(),
            windDriftText = aim?.takeIf { it.hasImpact && abs(it.windDriftM) >= 0.5f }?.let { HudFormat.signed(it.windDriftM) },
            reload01 = w?.reload01?.coerceIn(0f, 1f) ?: 1f,
            ready = w?.ready == true && hud.canCommand,
            reloadText = if (reloadLeft > 0.05f) HudFormat.decimal1(reloadLeft, sep) else "",
            stillBuilding = w != null && w.build01 < 1f,
            doorCount = tools.doorCount,
            doorsOpen = tools.doorCount > 0 && tools.doorsOpen * 2 >= tools.doorCount,
            weaponCount = hud.weapons.size,
            weaponNumber = hud.weapons.indexOfFirst { it.deviceRef == tools.weaponRef } + 1,
        )
    }

    fun techCards(hud: HudModel, catalog: GameCatalog): List<TechCard> {
        val unlocked = hud.unlockedTechs
        return catalog.techBuildings.map { t ->
            val d = t.device
            val building = hud.buildingTech.firstOrNull { it.typeId == d.index }
            val missing = t.requires.firstOrNull { it !in unlocked }
            val status = when {
                t.techIndex in unlocked -> TechStatus.BUILT
                building != null -> TechStatus.BUILDING
                missing == null -> TechStatus.AVAILABLE
                else -> TechStatus.LOCKED
            }
            TechCard(
                deviceIndex = d.index,
                deviceId = d.id,
                status = status,
                progress01 = building?.progress01?.coerceIn(0f, 1f) ?: if (status == TechStatus.BUILT) 1f else 0f,
                secondsLeft = building?.let { ceilSeconds(it.secondsLeft) } ?: 0,
                costMetal = d.costMetal.roundToInt(),
                costEnergy = d.costEnergy.roundToInt(),
                buildSeconds = d.buildSeconds.roundToInt(),
                affordable = hud.metal >= d.costMetal && hud.energy >= d.costEnergy,
                requiresDeviceId = missing?.let { catalog.techBuildingByTech(it)?.device?.id },
                unlocks = t.unlockWeapons.map { wi ->
                    val w = catalog.weapons[wi]
                    UnlockTile(
                        id = w.device.id, isWeapon = true,
                        damage = (if (w.damage > 0f) w.damage else w.splashDamage).roundToInt(),
                        damageSecondary = when {
                            w.shotsPerBurst > 1 -> w.shotsPerBurst
                            w.beamSeconds > 0f -> w.beamSeconds.roundToInt()
                            else -> 0
                        },
                        range = w.maxRange.roundToInt(),
                        shotMetal = w.shotMetal.roundToInt(),
                        shotEnergy = w.shotEnergy.roundToInt(),
                        costPerMeter = 0,
                        damageStyle = when {
                            w.shotsPerBurst > 1 -> DamageStyle.SALVO
                            w.igniteRadius > 0f -> DamageStyle.INCENDIARY
                            w.beamSeconds > 0f -> DamageStyle.BEAM
                            else -> DamageStyle.PLAIN
                        },
                    )
                } + t.unlockMaterials.map { mi ->
                    val m = catalog.materials[mi]
                    UnlockTile(m.id, false, 0, 0, 0, 0, 0, m.costPerM.roundToInt(), DamageStyle.MATERIAL)
                },
            )
        }
    }
}
