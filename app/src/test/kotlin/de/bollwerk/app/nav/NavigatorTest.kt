package de.bollwerk.app.nav

import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.MatchResult
import de.bollwerk.app.match.EndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigatorTest {
    private val removed = mutableListOf<NavEntry>()
    private val added = mutableListOf<NavEntry>()
    private val nav = Navigator(onEntryAdded = { added += it }, onEntryRemoved = { removed += it })

    @Test
    fun startsAtMainMenuAndCannotGoBack() {
        assertEquals(Screen.MainMenu, nav.current.screen)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back())
        assertEquals(1, nav.stack.value.size)
    }

    @Test
    fun pushAndBackKeepStackOrder() {
        nav.push(Screen.Setup(GameMode.VS_AI))
        nav.push(Screen.Settings)
        assertEquals(listOf(Screen.MainMenu, Screen.Setup(GameMode.VS_AI), Screen.Settings), nav.stack.value.map { it.screen })
        assertTrue(nav.back())
        assertEquals(Screen.Setup(GameMode.VS_AI), nav.current.screen)
        assertEquals(1, removed.size)
        assertEquals(Screen.Settings, removed.single().screen)
    }

    @Test
    fun entryIdsAreUniqueEvenForEqualScreens() {
        nav.push(Screen.Settings)
        nav.back()
        nav.push(Screen.Settings)
        val ids = (removed.map { it.id } + nav.stack.value.map { it.id })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun replaceTopSwapsGameForResult() {
        val config = MatchConfig(seed = 5)
        nav.push(Screen.Setup(GameMode.VS_AI))
        nav.push(Screen.Game(config))
        val result = MatchResult(config, winnerPlayerId = 0, reason = EndReason.ENEMY_REACTOR_DESTROYED)
        nav.replaceTop(Screen.Result(result))
        assertEquals(3, nav.stack.value.size)
        assertEquals(Screen.Result(result), nav.current.screen)
        assertEquals(Screen.Game(config), removed.single().screen)
    }

    @Test
    fun popToRootClearsEverythingAboveMenuAndNotifiesTopFirst() {
        nav.push(Screen.Setup(GameMode.HOTSEAT))
        nav.push(Screen.Game(MatchConfig(mode = GameMode.HOTSEAT)))
        nav.popToRoot()
        assertEquals(listOf(Screen.MainMenu), nav.stack.value.map { it.screen })
        assertEquals(listOf(Screen.Game(MatchConfig(mode = GameMode.HOTSEAT)), Screen.Setup(GameMode.HOTSEAT)), removed.map { it.screen })
    }

    @Test
    fun popToRootAtRootIsNoOp() {
        nav.popToRoot()
        assertTrue(removed.isEmpty())
        assertEquals(1, nav.stack.value.size)
    }

    @Test
    fun replaceTopAtRootIsRejectedAndKeepsTheMainMenu() {
        val result = MatchResult(MatchConfig(), 0, EndReason.ENEMY_REACTOR_DESTROYED)
        assertFalse(nav.replaceTop(Screen.Result(result)))
        assertEquals(listOf(Screen.MainMenu), nav.stack.value.map { it.screen })
        assertTrue(removed.isEmpty())
    }

    @Test
    fun staleFromIsIgnoredForPushReplaceAndBack() {
        val setup = nav.current.id
        nav.push(Screen.Setup(GameMode.VS_AI), from = setup)
        assertFalse(nav.push(Screen.Settings, from = setup), "double tap on the same entry")
        assertFalse(nav.replaceTop(Screen.Settings, from = setup))
        assertFalse(nav.back(from = setup))
        assertEquals(listOf(Screen.MainMenu, Screen.Setup(GameMode.VS_AI)), nav.stack.value.map { it.screen })
        assertTrue(nav.back(from = nav.current.id))
        assertEquals(Screen.MainMenu, nav.current.screen)
    }

    @Test
    fun entriesAreAnnouncedBeforeTheyBecomeVisible() {
        var ref: Navigator? = null
        val visibleAtAdd = mutableListOf<Boolean>()
        val n = Navigator(onEntryAdded = { e -> visibleAtAdd += ref?.stack?.value?.any { it.id == e.id } ?: false })
        ref = n
        n.push(Screen.Settings)
        n.push(Screen.Setup(GameMode.HOTSEAT))
        n.replaceTop(Screen.Settings)
        assertEquals(listOf(false, false, false, false), visibleAtAdd)
        assertEquals(1, added.size, "root of the default navigator is announced once")
    }

    @Test
    fun concurrentPushesLoseNoUpdate() {
        val threads = (1..8).map {
            Thread { repeat(100) { nav.push(Screen.Settings) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(801, nav.stack.value.size)
        assertEquals(801, nav.stack.value.map { it.id }.toSet().size)
    }
}
