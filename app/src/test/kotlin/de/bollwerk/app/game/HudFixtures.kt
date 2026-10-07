package de.bollwerk.app.game

import de.bollwerk.content.ClasspathContent
import de.bollwerk.content.ContentDb
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase
import de.bollwerk.engine.tools.AimInfo
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.engine.view.HudModel
import de.bollwerk.engine.view.HudTechBuild
import de.bollwerk.engine.view.HudWeapon

/** Gemeinsame Test-Daten: Content aus dem Klassenpfad und HUD-Stände mit den Mockup-Werten (Stil-Bibel §1). */
object HudFixtures {
    val db: ContentDb by lazy { ClasspathContent.load() }
    val catalog: GameCatalog by lazy { GameCatalog.from(db) }

    fun device(id: String): Int = db.deviceIndex(id)
    fun tech(id: String): Int = db.techIndex(id)
    fun material(id: String): Int = db.materialIndex(id)

    const val MORTAR_REF = 77L

    /** Metall 340 (+12/s), Energie 180/400 (+8/s), Zeit 02:14, Wind 3,2 m/s, Gegner 62 %, Werkstatt gebaut. */
    fun mockupHud(
        unlocked: List<Int> = listOf(tech("workshop")),
        building: List<HudTechBuild> = listOf(HudTechBuild(90L, device("armoury"), 0.62f, 20.3f)),
        weapons: List<HudWeapon> = listOf(
            HudWeapon(MORTAR_REF, device("mortar"), reload01 = 1f - 1.4f / 6f, ready = false, aimAngle = 0.9f, power = 0.78f),
            HudWeapon(78L, device("cannon"), reload01 = 1f, ready = true),
        ),
    ) = HudModel(
        metal = 340f, metalRate = 12f, metalCap = 1000f,
        energy = 180f, energyCap = 400f, energyRate = 8f,
        timeSeconds = 134.4f, windSpeed = 3.2f,
        ownReactor01 = 1f, enemyReactor01 = 0.62f,
        localPlayer = 0, activePlayer = -1,
        unlockedTechs = unlocked, weapons = weapons, buildingTech = building, undoCount = 3,
    )

    fun buildTools() = ToolUiState(
        mode = ToolMode.BUILD, selection = ToolSelection.Material(material("wood")), canUndo = true, localPlayer = 0,
    )

    fun aimTools() = ToolUiState(
        mode = ToolMode.AIM,
        weaponRef = MORTAR_REF,
        aim = AimInfo(
            deviceRef = MORTAR_REF, angle = 0.9076f, power = 0.78f, elevationDeg = 52f, powerPercent = 78f, splashRadiusM = 2.5f,
            apexX = 60f, apexY = 6f, apexHeightM = 24f, windDriftM = 3f, hasImpact = true,
        ),
        doorCount = 2, doorsOpen = 2, canUndo = true, localPlayer = 0,
    )

    fun hotseatHud(phase: TurnPhase = TurnPhase.PLAY, active: Int = 0, turn: Int = 1, handoverCountdown: Int = 0) = mockupHud().copy(
        turnMode = TurnMode.TURNS, turnPhase = phase, activePlayer = active, turnNumber = turn, localPlayer = if (active >= 0) active else 0,
        turnSecondsLeft = if (phase == TurnPhase.PLAY) 32.2f else 0f, turnSecondsTotal = 45f,
        handover = phase == TurnPhase.HANDOVER, handoverCountdown = handoverCountdown,
        handoverSecondsLeft = handoverCountdown.toFloat(),
        canCommand = phase == TurnPhase.PLAY,
    )
}
