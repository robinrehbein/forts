package de.bollwerk.app.ui.game.hud

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import de.bollwerk.app.R
import de.bollwerk.app.game.HudFixtures
import de.bollwerk.app.game.HudPresenter
import de.bollwerk.app.tutorial.TutorialHint
import de.bollwerk.app.ui.game.tutorial.tutorialHintRes
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.engine.sim.TurnPhase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reine Layout- und Auswahlregeln des HUD (ohne Rendering). */
class HudLayoutRulesTest {
    // ---- Kontextmenü (ux-06) ----

    private val screen = IntRect(16, 16, 1584, 704) // 800 × 360 dp bei xhdpi, 8 dp Rand
    private val menu = IntSize(400, 220)

    private fun inside(p: IntOffset, size: IntSize = menu, b: IntRect = screen) =
        p.x >= b.left && p.y >= b.top && p.x + size.width <= b.right && p.y + size.height <= b.bottom

    @Test
    fun contextMenuStaysOnScreenAtTheRightEdge() {
        // Langdruck auf die rote Festung (x ≈ 745 dp): vorher ragte das Menü bis zu 70 dp aus dem Bild
        val p = contextMenuPosition(Offset(1490f, 500f), menu, screen, gap = 40)
        assertTrue(inside(p), "$p")
        assertEquals(screen.right - menu.width, p.x)
        assertEquals(500 - 40 - menu.height, p.y, "über dem Finger")
    }

    @Test
    fun contextMenuFlipsBelowWithoutRoomAboveAndClampsAtTheBottom() {
        val top = contextMenuPosition(Offset(800f, 120f), menu, screen, gap = 40)
        assertEquals(160, top.y, "kein Platz darüber → darunter")
        assertEquals(800 - menu.width / 2, top.x, "mittig zum Finger")
        assertTrue(inside(top))
        val bottomRight = contextMenuPosition(Offset(1600f, 720f), menu, screen, gap = 40)
        assertTrue(inside(bottomRight), "$bottomRight")
        val left = contextMenuPosition(Offset(0f, 400f), menu, screen, gap = 40)
        assertEquals(screen.left, left.x)
        assertTrue(inside(left))
    }

    @Test
    fun contextMenuRespectsSafeInsetsAndCentersWithoutAnchor() {
        val safe = IntRect(120, 16, 1480, 704) // Display-Ausschnitt links/rechts
        assertTrue(inside(contextMenuPosition(Offset(1590f, 400f), menu, safe, 40), b = safe))
        val c = contextMenuPosition(null, menu, safe, 40)
        assertEquals(IntOffset(120 + (1360 - 400) / 2, 16 + (688 - 220) / 2), c)
        // größer als der Bereich: links/oben gewinnt, nichts Negatives
        val huge = contextMenuPosition(Offset(10f, 10f), IntSize(4000, 4000), safe, 40)
        assertEquals(IntOffset(safe.left, safe.top), huge)
    }

    // ---- Zug beenden / Spieler am Zug (ux-08, ux-12) ----

    @Test
    fun endTurnIsOfferedOnlyToTheHotseatPlayerInTheirPlayPhase() {
        val catalog = HudFixtures.catalog
        val play = HudPresenter.present(HudFixtures.hotseatHud(), HudFixtures.buildTools(), catalog, hotseat = true)
        assertTrue(play.showEndTurn)
        assertFalse(HudPresenter.present(HudFixtures.hotseatHud(phase = TurnPhase.RESOLVE), HudFixtures.buildTools(), catalog, true).showEndTurn)
        assertFalse(HudPresenter.present(HudFixtures.mockupHud(), HudFixtures.buildTools(), catalog, hotseat = false).showEndTurn)
    }

    @Test
    fun turnChipUsesTheTeamColourOfTheActivePlayer() {
        assertEquals(BollwerkColors.TeamBlue, teamColorOf(0))
        assertEquals(BollwerkColors.TeamRed, teamColorOf(1))
        val ui = HudPresenter.present(HudFixtures.hotseatHud(active = 1, turn = 4), HudFixtures.buildTools(), HudFixtures.catalog, true)
        assertEquals(1, ui.turn?.activePlayer)
        assertEquals(4, ui.turn?.turnNumber)
    }

    @Test
    fun hudButtonsAreAtLeastFortyEightDp() {
        assertTrue(HudButtonSize.value >= 48f)
        assertTrue(MinToolbarUnit.value - 4f >= 48f, "Material-Eintrag nach 2 dp Rand je Seite")
    }

    // ---- Tutorial-Text (ux-11) ----

    @Test
    fun aimHintFollowsTheReleaseToFireSetting() {
        assertEquals(R.string.tutorial_aim_fire, tutorialHintRes(TutorialHint.AIM_AND_FIRE, releaseToFire = false))
        assertEquals(R.string.tutorial_aim_release, tutorialHintRes(TutorialHint.AIM_AND_FIRE, releaseToFire = true))
        assertEquals(R.string.tutorial_enter_aim, tutorialHintRes(TutorialHint.ENTER_AIM, releaseToFire = true))
    }

    @Test
    fun tutorialWordingMatchesTheHud() {
        val de = strings("values")
        val en = strings("values-en")
        assertFalse("Kampfmodus" in de.getValue("tutorial_enter_aim"), "die HUD-Modi heißen ZIELEN/BAUEN")
        assertTrue("Zielmodus" in de.getValue("tutorial_enter_aim"))
        assertTrue("aim mode" in en.getValue("tutorial_enter_aim"))
        assertTrue("FEUER" !in de.getValue("tutorial_aim_release") && "FIRE" !in en.getValue("tutorial_aim_release"))
    }

    // ---- Mindestschriftgrößen (ux-09, Stil-Bibel §3) ----

    /**
     * Keine Schrift im UI unter 10 sp (Versalien-Labels) und keine Fließtext-Stile unter 12 sp. Prüft die Quelltexte, damit
     * neue Stellen nicht wieder bei 9 sp landen.
     */
    @Test
    fun noTextBelowTheStyleBibleMinimum() {
        val root = File("src/main/kotlin/de/bollwerk/app/ui")
        assertTrue(root.isDirectory, "läuft im Modulverzeichnis app/")
        val size = Regex("""fontSize = (\d+(?:\.\d+)?)\.sp""")
        val tooSmall = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().mapNotNull { (i, line) ->
                val v = size.find(line)?.groupValues?.get(1)?.toFloat() ?: return@mapNotNull null
                if (v < 10f) "${f.name}:${i + 1}: $v sp" else null
            }
        }.toList()
        assertTrue(tooSmall.isEmpty(), "Schrift unter 10 sp: $tooSmall")
        assertTrue(HudType.Small.fontSize.value >= 12f)
        assertTrue(TechTileText.fontSize.value >= 12f, "Fließtext der Tech-Kacheln")
    }

    private fun strings(dir: String): Map<String, String> {
        val re = Regex("""<string name="([^"]+)">(.*?)</string>""")
        return re.findAll(File("src/main/res/$dir/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }
}
