package de.bollwerk.app.tutorial

import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.HudPresenter
import de.bollwerk.app.game.HudUiState
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.loop.CommandOutcome
import de.bollwerk.engine.tools.AimInfo
import de.bollwerk.engine.tools.ToolMode
import de.bollwerk.engine.tools.ToolSelection
import de.bollwerk.engine.view.FxEvent
import de.bollwerk.engine.view.HudWeapon
import de.bollwerk.app.game.ToolUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [TutorialCoach]: HUD-Stände (echte [HudPresenter]-Abbildung) und Signale → Wirkungen (Mörser vorwählen, KI freigeben, Abschluss melden). */
class TutorialCoachTest {
    private val ids = TutorialIds(
        humanPlayer = 0, mineDevice = HudFixtures.device("mine"), mortarWeapon = 4, mortarDevice = HudFixtures.device("mortar"),
    )
    private val targets = TutorialTargets(WorldPoint(1f, 1f), WorldPoint(2f, 2f), WorldPoint(3f, 3f), WorldPoint(3f, 2f))

    private class Recorder : TutorialEffects {
        val selected = ArrayList<Long>()
        var gatesOpened = 0
        val finished = ArrayList<Boolean>()
        override fun selectWeapon(ref: Long) { selected += ref }
        override fun openGates() { gatesOpened++ }
        override fun finished(completed: Boolean) { finished += completed }
    }

    private val fx = Recorder()
    private val coach = TutorialCoach(ids, targets, fx)

    private val weapons = listOf(
        HudWeapon(HudFixtures.MORTAR_REF, HudFixtures.device("mortar"), reload01 = 1f, ready = true),
        HudWeapon(78L, HudFixtures.device("cannon"), reload01 = 1f, ready = true),
    )

    private fun hud(tools: ToolUiState): HudUiState =
        HudPresenter.present(HudFixtures.mockupHud(weapons = weapons), tools, HudFixtures.catalog, hotseat = false)

    private fun build(material: String? = null) = ToolUiState(
        mode = ToolMode.BUILD, selection = material?.let { ToolSelection.Material(HudFixtures.material(it)) } ?: ToolSelection.None,
    )

    private fun aim(weaponRef: Long) = ToolUiState(
        mode = ToolMode.AIM, weaponRef = weaponRef,
        aim = AimInfo(
            deviceRef = weaponRef, angle = 0.9f, power = 0.78f, elevationDeg = 52f, powerPercent = 78f, splashRadiusM = 2.5f,
            apexX = 0f, apexY = 0f, apexHeightM = 0f, windDriftM = 0f, hasImpact = true,
        ),
    )

    private fun outcome(cmd: Command) = TutorialSignal.Outcome(CommandOutcome(1, cmd, CommandResult.Accepted))

    @Test
    fun hudStatesDriveTheHint() {
        coach.onHud(hud(build()), weapons)
        assertEquals(TutorialHint.PICK_WOOD, coach.state.hint)
        coach.onHud(hud(build("wood")), weapons)
        assertEquals(TutorialHint.DRAW_BEAM, coach.state.hint)
        assertEquals(TutorialHint.DRAW_BEAM, coach.model.value.state.hint, "the UI model follows")
        assertEquals(targets, coach.model.value.targets)
    }

    @Test
    fun signalsAdvanceStepsAndTheFinishRunsTheEffectsOnce() {
        coach.onSignal(outcome(Command.PlaceBeam(1, 0, aNodeRef = 1, bNodeRef = 2, materialId = 0)))
        assertEquals(TutorialStep.BUILD_MINE, coach.state.step)
        coach.onSignal(outcome(Command.PlaceDevice(1, 0, ids.mineDevice, beamRef = 3, t = 0.5f)))
        assertEquals(TutorialStep.FIRE_MORTAR, coach.state.step)
        assertEquals(0, fx.gatesOpened, "the enemy rests until the mortar has fired")
        coach.onSignal(TutorialSignal.Fx(FxEvent.Fired(1, 0f, 0f, 5, ids.mortarWeapon, 0.9f)))
        assertEquals(TutorialStatus.COMPLETED, coach.state.status)
        assertEquals(1, fx.gatesOpened)
        assertEquals(listOf(true), fx.finished)
        // Doppelte Meldungen und die Bestätigung lösen nichts mehr aus
        coach.onSignal(TutorialSignal.Fx(FxEvent.Fired(2, 0f, 0f, 5, ids.mortarWeapon, 0.9f)))
        coach.close()
        assertEquals(TutorialStatus.CLOSED, coach.state.status)
        assertEquals(1, fx.gatesOpened)
        assertEquals(listOf(true), fx.finished)
    }

    @Test
    fun skipOpensTheGatesAndReportsASkip() {
        coach.skip()
        assertEquals(TutorialStatus.SKIPPED, coach.state.status)
        assertEquals(1, fx.gatesOpened)
        assertEquals(listOf(false), fx.finished)
        coach.skip()
        assertEquals(1, fx.gatesOpened, "second skip is a no-op")
        assertEquals(TutorialStatus.SKIPPED, coach.model.value.state.status)
    }

    @Test
    fun mortarIsPreselectedWhenAnotherWeaponIsInTheAimCard() {
        coach.onSignal(outcome(Command.PlaceBeam(1, 0, aNodeRef = 1, bNodeRef = 2, materialId = 0)))
        coach.onSignal(outcome(Command.PlaceDevice(1, 0, ids.mineDevice, beamRef = 3, t = 0.5f)))
        coach.onHud(hud(aim(weaponRef = 78L)), weapons)
        assertEquals(listOf(HudFixtures.MORTAR_REF), fx.selected)
        coach.onHud(hud(aim(HudFixtures.MORTAR_REF)), weapons)
        assertEquals(1, fx.selected.size, "no re-selection once the mortar is chosen")
        assertEquals(TutorialHint.AIM_AND_FIRE, coach.state.hint)
    }

    @Test
    fun mortarIsNotPreselectedBeforeStepThree() {
        coach.onHud(hud(aim(weaponRef = 78L)), weapons)
        assertTrue(fx.selected.isEmpty())
        assertEquals(TutorialHint.BACK_TO_BUILD, coach.state.hint)
    }
}
