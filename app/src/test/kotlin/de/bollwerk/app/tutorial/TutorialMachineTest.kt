package de.bollwerk.app.tutorial

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.loop.CommandOutcome
import de.bollwerk.engine.view.FxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Zustandsautomat des Tutorials: Schrittfolge aus HUD-, Outcome- und Fx-Eingaben, Überspringen, Vorgreifen. */
class TutorialMachineTest {
    private val ids = TutorialIds(humanPlayer = 0, mineDevice = 1, mortarWeapon = 4, mortarDevice = 9)
    private val machine = TutorialMachine(ids)

    private val buildWood = TutorialHud(selectedId = "wood")
    private val buildMine = TutorialHud(selectedId = "mine")
    private val aimMortar = TutorialHud(selectedId = null, inAim = true, aimWeaponId = "mortar")

    private fun accepted(cmd: Command) = CommandOutcome(1, cmd, CommandResult.Accepted)
    private fun rejected(cmd: Command) = CommandOutcome(1, cmd, CommandResult.Rejected(RejectReason.NOT_ENOUGH_METAL))
    private fun beam(player: Int = 0) = Command.PlaceBeam(1, player, aNodeRef = 1, bNodeRef = 2, materialId = 0)
    private fun device(type: Int, player: Int = 0) = Command.PlaceDevice(1, player, type, beamRef = 3, t = 0.5f)
    private fun fired(weapon: Int) = FxEvent.Fired(1, 0f, 0f, deviceUid = 5, weaponId = weapon, angle = 0.9f)

    @Test
    fun startsAtStepOneWithTheWoodHint() {
        assertEquals(TutorialStatus.RUNNING, machine.state.status)
        assertEquals(TutorialStep.PLACE_BEAM, machine.state.step)
        assertEquals(TutorialHint.PICK_WOOD, machine.state.hint)
        assertEquals(1, machine.state.stepNumber)
        assertEquals(3, machine.state.stepCount)
        assertTrue(machine.state.done.isEmpty())
    }

    @Test
    fun hintFollowsTheToolSelectionInsideAStep() {
        machine.onHud(buildWood)
        assertEquals(TutorialHint.DRAW_BEAM, machine.state.hint)
        machine.onHud(TutorialHud(selectedId = "metal"))
        assertEquals(TutorialHint.PICK_WOOD, machine.state.hint, "picking another material goes back to the toolbar hint")
        machine.onHud(TutorialHud())
        assertEquals(TutorialHint.PICK_WOOD, machine.state.hint)
        assertEquals(TutorialStep.PLACE_BEAM, machine.state.step, "tool selection never completes a step")
    }

    @Test
    fun aimModeDuringABuildStepPointsBackToBuild() {
        machine.onHud(aimMortar)
        assertEquals(TutorialHint.BACK_TO_BUILD, machine.state.hint)
        machine.onHud(buildWood)
        assertEquals(TutorialHint.DRAW_BEAM, machine.state.hint)
    }

    @Test
    fun acceptedBeamCompletesStepOneAndHintsTheMine() {
        machine.onHud(buildWood)
        machine.onOutcome(accepted(beam()))
        assertEquals(TutorialStep.BUILD_MINE, machine.state.step)
        assertEquals(TutorialHint.PICK_MINE, machine.state.hint, "wood is still selected, the next step starts at its toolbar hint")
        assertEquals(setOf(TutorialStep.PLACE_BEAM), machine.state.done)
    }

    @Test
    fun rejectedBeamAndForeignBeamDoNotCount() {
        machine.onOutcome(rejected(beam()))
        machine.onOutcome(accepted(beam(player = 1)))
        machine.onOutcome(accepted(Command.Undo(1, 0)))
        machine.onOutcome(accepted(device(ids.mineDevice)))
        assertEquals(TutorialStep.PLACE_BEAM, machine.state.step)
        assertEquals(setOf(TutorialStep.BUILD_MINE), machine.state.done, "only the (early) mine counts, as a milestone")
    }

    @Test
    fun mineStepNeedsTheMineDeviceNotAnyDevice() {
        machine.onOutcome(accepted(beam()))
        machine.onHud(buildMine)
        assertEquals(TutorialHint.PLACE_MINE, machine.state.hint)
        machine.onOutcome(accepted(device(type = 7)))
        machine.onOutcome(rejected(device(ids.mineDevice)))
        assertEquals(TutorialStep.BUILD_MINE, machine.state.step)
        machine.onOutcome(accepted(device(ids.mineDevice)))
        assertEquals(TutorialStep.FIRE_MORTAR, machine.state.step)
        assertEquals(TutorialHint.ENTER_AIM, machine.state.hint)
    }

    @Test
    fun aimModeMovesToTheDragHintAndLeavingGoesBack() {
        machine.onOutcome(accepted(beam()))
        machine.onOutcome(accepted(device(ids.mineDevice)))
        machine.onHud(aimMortar)
        assertEquals(TutorialHint.AIM_AND_FIRE, machine.state.hint)
        machine.onHud(buildWood)
        assertEquals(TutorialHint.ENTER_AIM, machine.state.hint)
    }

    @Test
    fun onlyTheMortarShotCompletesTheTutorial() {
        machine.onOutcome(accepted(beam()))
        machine.onOutcome(accepted(device(ids.mineDevice)))
        machine.onHud(aimMortar)
        machine.onFx(fired(weapon = 2))
        assertEquals(TutorialStatus.RUNNING, machine.state.status)
        machine.onFx(FxEvent.Explosion(1, 0f, 0f, 2f, 10f, 4, -1, false, -1, 0f))
        assertEquals(TutorialStatus.RUNNING, machine.state.status)
        machine.onFx(fired(ids.mortarWeapon))
        assertEquals(TutorialStatus.COMPLETED, machine.state.status)
        assertEquals(TutorialStep.entries.toSet(), machine.state.done)
        assertFalse(machine.state.running)
    }

    @Test
    fun fullRunThroughAllThreeSteps() {
        val seen = ArrayList<TutorialHint>()
        fun hud(h: TutorialHud) { machine.onHud(h); seen += machine.state.hint }
        hud(TutorialHud())
        hud(buildWood)
        machine.onOutcome(accepted(beam()))
        hud(buildMine)
        machine.onOutcome(accepted(device(ids.mineDevice)))
        hud(TutorialHud())
        hud(aimMortar)
        machine.onFx(fired(ids.mortarWeapon))
        assertEquals(
            listOf(
                TutorialHint.PICK_WOOD, TutorialHint.DRAW_BEAM, TutorialHint.PLACE_MINE, TutorialHint.ENTER_AIM, TutorialHint.AIM_AND_FIRE,
            ),
            seen,
        )
        assertEquals(TutorialStatus.COMPLETED, machine.state.status)
    }

    @Test
    fun milestonesReachedEarlyAreSkippedLater() {
        // Mine und Mörserschuss vor dem Balken: Schritt 1 bleibt offen, danach ist das Tutorial sofort fertig
        machine.onOutcome(accepted(device(ids.mineDevice)))
        machine.onFx(fired(ids.mortarWeapon))
        assertEquals(TutorialStep.PLACE_BEAM, machine.state.step)
        assertEquals(TutorialStatus.RUNNING, machine.state.status)
        machine.onOutcome(accepted(beam()))
        assertEquals(TutorialStatus.COMPLETED, machine.state.status)
    }

    @Test
    fun earlyMineSkipsStepTwoAfterTheBeam() {
        machine.onOutcome(accepted(device(ids.mineDevice)))
        machine.onOutcome(accepted(beam()))
        assertEquals(TutorialStep.FIRE_MORTAR, machine.state.step)
    }

    @Test
    fun skipEndsTheTutorialAndIgnoresLaterEvents() {
        machine.onHud(buildWood)
        machine.skip()
        assertEquals(TutorialStatus.SKIPPED, machine.state.status)
        val frozen = machine.state
        machine.onOutcome(accepted(beam()))
        machine.onHud(aimMortar)
        machine.onFx(fired(ids.mortarWeapon))
        machine.close()
        assertEquals(frozen, machine.state)
    }

    @Test
    fun skipAfterCompletionChangesNothingAndCloseNeedsCompletion() {
        machine.close()
        assertEquals(TutorialStatus.RUNNING, machine.state.status, "close only acknowledges the completion card")
        machine.onOutcome(accepted(beam()))
        machine.onOutcome(accepted(device(ids.mineDevice)))
        machine.onFx(fired(ids.mortarWeapon))
        machine.skip()
        assertEquals(TutorialStatus.COMPLETED, machine.state.status)
        machine.close()
        assertEquals(TutorialStatus.CLOSED, machine.state.status)
    }

    @Test
    fun mortarIsPreselectedOnlyForStepThreeInAimMode() {
        assertFalse(machine.wantsMortar())
        machine.onOutcome(accepted(beam()))
        machine.onOutcome(accepted(device(ids.mineDevice)))
        machine.onHud(TutorialHud(inAim = true, aimWeaponId = "cannon"))
        assertTrue(machine.wantsMortar())
        machine.onHud(aimMortar)
        assertFalse(machine.wantsMortar())
        machine.onHud(TutorialHud(selectedId = "wood"))
        assertFalse(machine.wantsMortar(), "not in aim mode")
    }

    @Test
    fun hintDerivesOnlyFromStepAndHud() {
        // gleiche Eingaben, gleiche Hinweise: der Hinweis ist eine reine Funktion von Schritt und HUD
        val other = TutorialMachine(ids)
        for (m in listOf(machine, other)) {
            m.onHud(buildWood)
            m.onOutcome(accepted(beam()))
            m.onHud(buildMine)
        }
        assertEquals(machine.state, other.state)
        assertEquals(TutorialHint.PLACE_MINE, machine.state.hint)
    }
}
