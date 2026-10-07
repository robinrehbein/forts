package de.bollwerk.app.tutorial

import de.bollwerk.app.game.FakeClock
import de.bollwerk.app.game.GameController
import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.HudPresenter
import de.bollwerk.app.game.MatchSessions
import de.bollwerk.app.match.AiLevel
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.StartResources
import de.bollwerk.app.match.TeamColor
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandResult
import de.bollwerk.engine.loop.CommandOutcome
import de.bollwerk.engine.sim.Controller
import de.bollwerk.engine.tools.PointerPhase
import de.bollwerk.engine.tools.ToolSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Das Tutorial-Gefecht mit echter Sim: ruhiger Aufbau, Start-Minen abgeräumt, KI ruht bis zur Freigabe, die Ziele des
 * Coach-Marks sind mit dem echten Werkzeug (Tipp bzw. Zug) erreichbar, und der Durchlauf aller drei Schritte endet mit dem
 * Mörserschuss, der die KI freigibt.
 */
class TutorialMatchTest {
    private val clock = FakeClock()
    private val config = MatchConfig.tutorial()
    private val session = MatchSessions.create(HudFixtures.db, config)
    private val controller = GameController(session, clock)
    private val driver = assertNotNull(session.tutorial)
    private val runner = session.runner
    private val outcomes = CopyOnWriteArrayList<CommandOutcome>()
    private val signals = CopyOnWriteArrayList<TutorialSignal>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    init {
        runner.addResultListener { outcomes += it }
        scope.launch { driver.signals.collect { signals += it } }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        controller.stop()
    }

    private fun frames(n: Int) = repeat(n) { clock.advanceMs(1000.0 / 60.0); controller.stepOnce() }

    private val mineIndex = HudFixtures.device("mine")
    private val woodIndex = HudFixtures.material("wood")
    private val mortarDevice = HudFixtures.device("mortar")

    private fun ownDevices(type: Int, owner: Int): Int {
        val d = runner.state.deviceView
        var n = 0
        for (i in 0 until d.size) if (d.isAlive(i) && d.owner(i) == owner && d.type(i) == type) n++
        return n
    }

    private fun tap(x: Float, y: Float, gesture: Long) {
        controller.pointer(PointerPhase.DOWN, x, y, 1f, gesture)
        frames(1)
        controller.pointer(PointerPhase.UP, x, y, 1f, gesture)
        frames(2)
    }

    private fun accepted(pred: (Command) -> Boolean) = outcomes.any { it.accepted && pred(it.command) }

    @Test
    fun configIsACalmGuidedSetup() {
        assertTrue(config.tutorial)
        assertEquals(GameMode.VS_AI, config.mode)
        assertEquals("schlucht", config.map.id)
        assertEquals(TeamColor.BLUE, config.team)
        assertEquals(AiLevel.EASY, config.aiLevel)
        assertEquals(StartResources.RICH, config.resources)
        assertEquals(listOf(Controller.HUMAN, Controller.AI), config.toMatchSetup().players.map { it.controller })
        assertTrue(abs(runner.state.wind) <= 1f, "weak wind, got ${runner.state.wind}")
        val wind = runner.state.wind
        runner.runTicks(3600)
        assertEquals(wind, runner.state.wind, "the wind stays constant over the whole tutorial")
        assertTrue(runner.state.player(0).metal >= 400f - 1f, "generous start resources")
    }

    @Test
    fun normalMatchesAreUnaffected() {
        val normal = MatchSessions.create(HudFixtures.db, MatchConfig(seed = 8))
        assertEquals(null, normal.tutorial)
        assertEquals(SimDefaults.windInterval, normal.runner.state.config.wind.changeIntervalTicks)
    }

    @Test
    fun startMinesAreClearedSoTheOreSpotIsFree() {
        assertEquals(2, ownDevices(mineIndex, 0), "the blueprint fort starts with two mines")
        runner.runTicks(2)
        assertEquals(0, ownDevices(mineIndex, 0))
        assertEquals(2, ownDevices(mineIndex, 1), "the enemy keeps his")
        assertTrue(runner.state.player(0).reactorDeviceId >= 0, "the reactor stays")
    }

    @Test
    fun enemyRestsUntilTheGatesOpen() {
        fun enemyCommands() = outcomes.count { it.playerId == 1 }
        runner.runTicks(3600)
        assertEquals(0, enemyCommands(), "no enemy command in the first minute")
        assertTrue(!driver.gatesOpen)
        driver.openGates()
        assertTrue(driver.gatesOpen)
        runner.runTicks(3600)
        assertTrue(enemyCommands() > 0, "the AI acts after the release")
    }

    @Test
    fun targetsAreOnTheOwnFortAndTheOre() {
        val t = driver.targets
        val map = runner.state.map
        assertTrue(map.inBuildZone(0, t.beamA.x) && map.inBuildZone(0, t.beamB.x), "beam nodes in the own build zone")
        assertTrue(map.ores.any { it.owner == 0 && it.x == t.ore.x }, "ore marker sits on an own ore deposit")
        assertTrue(t.mineSpot.y < t.ore.y, "the tap point is above the ground (y grows downwards)")
        assertEquals(1, t.facing)
    }

    @Test
    fun draggingBetweenTheTargetNodesBuildsAWoodBeam() {
        frames(2)
        controller.selectTool(ToolSelection.Material(woodIndex))
        frames(1)
        val t = driver.targets
        controller.pointer(PointerPhase.DOWN, t.beamA.x, t.beamA.y, 1f, 1L)
        frames(1)
        controller.pointer(PointerPhase.MOVE, (t.beamA.x + t.beamB.x) / 2, (t.beamA.y + t.beamB.y) / 2, 1f, 1L)
        frames(1)
        controller.pointer(PointerPhase.MOVE, t.beamB.x, t.beamB.y, 1f, 1L)
        frames(1)
        controller.pointer(PointerPhase.UP, t.beamB.x, t.beamB.y, 1f, 1L)
        frames(2)
        val placed = outcomes.filter { it.command is Command.PlaceBeam }
        assertTrue(placed.isNotEmpty() && placed.all { it.accepted }, "beam accepted: $placed")
        assertTrue(signals.any { it is TutorialSignal.Outcome && it.outcome.command is Command.PlaceBeam })
    }

    @Test
    fun tappingTheMarkerAboveTheOreBuildsTheMine() {
        frames(2)
        controller.selectTool(ToolSelection.Device(mineIndex))
        frames(1)
        tap(driver.targets.mineSpot.x, driver.targets.mineSpot.y, 1L)
        val placed = outcomes.filter { (it.command as? Command.PlaceDevice)?.deviceTypeId == mineIndex }
        assertTrue(placed.isNotEmpty() && placed.all { it.accepted }, "mine accepted: $placed")
        runner.runTicks(2)
        assertEquals(1, ownDevices(mineIndex, 0))
    }

    @Test
    fun theMortarShotIsReportedAsAFxSignal() {
        frames(2)
        controller.enterAimMode()
        frames(1)
        controller.selectWeapon(mortarRef())
        frames(1)
        controller.fire()
        frames(5)
        val fired = signals.filterIsInstance<TutorialSignal.Fx>().map { it.event }
        assertTrue(fired.isNotEmpty(), "a Fired signal arrives")
        assertEquals(driver.ids.mortarWeapon, assertIs<de.bollwerk.engine.view.FxEvent.Fired>(fired.first()).weaponId)
    }

    @Test
    fun otherWeaponsDoNotProduceTheMortarSignal() {
        frames(2)
        controller.enterAimMode()
        frames(1)
        val cannon = controller.hud.value.weapons.first { it.typeId == HudFixtures.device("cannon") }
        controller.selectWeapon(cannon.deviceRef)
        frames(1)
        controller.fire()
        frames(5)
        assertTrue(accepted { it is Command.Fire }, "the cannon shot itself went through")
        assertTrue(signals.none { it is TutorialSignal.Fx }, "only the mortar counts")
    }

    private fun mortarRef(): Long = controller.hud.value.weapons.first { it.typeId == mortarDevice }.deviceRef

    /** Alle drei Schritte mit echtem HUD, echten Werkzeugen und echter Sim; die KI wird mit dem Schuss freigegeben. */
    @Test
    fun playingAllThreeStepsCompletesAndReleasesTheEnemy() {
        val selectedByCoach = ArrayList<Long>()
        val finished = ArrayList<Boolean>()
        val coach = TutorialCoach(
            driver.ids, driver.targets,
            object : TutorialEffects {
                override fun selectWeapon(ref: Long) { selectedByCoach += ref; controller.selectWeapon(ref) }
                override fun openGates() = driver.openGates()
                override fun finished(completed: Boolean) { finished += completed }
            },
        )
        var consumed = 0
        /** Sim ein Stück weiterlaufen lassen und HUD sowie Signale in den Coach speisen (wie das GameViewModel). */
        fun pump(n: Int = 3) {
            frames(n)
            val h = controller.hud.value
            coach.onHud(HudPresenter.present(h, controller.toolState.value, session.catalog, hotseat = false), h.weapons)
            while (consumed < signals.size) coach.onSignal(signals[consumed++])
        }
        pump()
        assertEquals(TutorialHint.PICK_WOOD, coach.state.hint)

        // Schritt 1: Holz wählen, Balken zwischen den Zielknoten ziehen
        controller.selectTool(ToolSelection.Material(woodIndex))
        pump()
        assertEquals(TutorialHint.DRAW_BEAM, coach.state.hint)
        val t = driver.targets
        controller.pointer(PointerPhase.DOWN, t.beamA.x, t.beamA.y, 1f, 1L); pump(1)
        controller.pointer(PointerPhase.MOVE, t.beamB.x, t.beamB.y, 1f, 1L); pump(1)
        controller.pointer(PointerPhase.UP, t.beamB.x, t.beamB.y, 1f, 1L); pump()
        assertEquals(TutorialStep.BUILD_MINE, coach.state.step)
        assertEquals(TutorialHint.PICK_MINE, coach.state.hint)

        // Schritt 2: Mine wählen und über dem Erz setzen
        controller.selectTool(ToolSelection.Device(mineIndex))
        pump()
        assertEquals(TutorialHint.PLACE_MINE, coach.state.hint)
        controller.pointer(PointerPhase.DOWN, t.mineSpot.x, t.mineSpot.y, 1f, 2L); pump(1)
        controller.pointer(PointerPhase.UP, t.mineSpot.x, t.mineSpot.y, 1f, 2L); pump()
        assertEquals(TutorialStep.FIRE_MORTAR, coach.state.step)
        assertEquals(TutorialHint.ENTER_AIM, coach.state.hint)
        assertTrue(!driver.gatesOpen, "the enemy still rests")

        // Schritt 3: Zielmodus (die Waffenkarte startet bei der Kanone, der Coach wählt den Mörser vor), feuern
        controller.enterAimMode()
        pump()
        pump()
        assertEquals(listOf(mortarRef()), selectedByCoach.distinct(), "the coach preselects the mortar")
        assertEquals(TutorialHint.AIM_AND_FIRE, coach.state.hint)
        controller.fire()
        pump(6)
        assertEquals(TutorialStatus.COMPLETED, coach.state.status)
        assertEquals(listOf(true), finished)
        assertTrue(driver.gatesOpen, "the enemy is released")
        // Der Gegner handelt jetzt
        val before = outcomes.count { it.playerId == 1 }
        runner.runTicks(3600)
        assertTrue(outcomes.count { it.playerId == 1 } > before)
    }

    @Test
    fun rejectedResultsOfOtherCommandsAreNotConfusedWithProgress() {
        val coach = TutorialCoach(driver.ids, driver.targets, object : TutorialEffects {
            override fun selectWeapon(ref: Long) = Unit
            override fun openGates() = Unit
            override fun finished(completed: Boolean) = Unit
        })
        coach.onSignal(
            TutorialSignal.Outcome(
                CommandOutcome(1, Command.PlaceBeam(1, 0, aNodeRef = 1, bNodeRef = 2, materialId = woodIndex), CommandResult.Rejected(de.bollwerk.engine.command.RejectReason.TOO_LONG)),
            ),
        )
        assertEquals(TutorialStep.PLACE_BEAM, coach.state.step)
    }

    private object SimDefaults {
        val windInterval = de.bollwerk.engine.sim.SimConfig.DEFAULT.wind.changeIntervalTicks
    }
}
