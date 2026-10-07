package de.bollwerk.app.nav

import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.MatchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Vollbild-Ziele der App. Pause, Einstellungen im Spiel und Hotseat-Übergabe sind Overlays des Game-Ziels. */
sealed interface Screen {
    data object MainMenu : Screen
    data class Setup(val mode: GameMode) : Screen
    data object Settings : Screen
    data class Game(val config: MatchConfig) : Screen
    data class Result(val result: MatchResult) : Screen
}

/** Ein Eintrag im Rückstapel; [id] ist je Eintrag eindeutig (Schlüssel für den ViewModel-Speicher). */
data class NavEntry(val id: Long, val screen: Screen)

/**
 * Einfacher zustandsbasierter Navigator (Rückstapel, kein Framework). Reine Kotlin-Klasse ohne Android-Bezug,
 * damit sie ohne Gerät testbar ist. Der unterste Eintrag ([Screen.MainMenu]) wird nie entfernt oder ersetzt.
 *
 * Alle Änderungen sind über eine Sperre serialisiert (die Spielschleife darf von einem Hintergrund-Thread aus
 * navigieren). Jede Änderung akzeptiert optional die ID des Eintrags, von dem sie ausgeht (`from`): ist dieser
 * Eintrag nicht mehr oben (Doppeltipp, verspäteter Callback), passiert nichts und die Methode liefert `false`.
 *
 * [onEntryAdded] läuft, bevor der neue Stapel sichtbar wird (so existiert der ViewModel-Speicher des Eintrags
 * schon, wenn die Oberfläche ihn liest); [onEntryRemoved] läuft danach, außerhalb der Sperre.
 */
class Navigator(
    root: Screen = Screen.MainMenu,
    private val onEntryAdded: (NavEntry) -> Unit = {},
    private val onEntryRemoved: (NavEntry) -> Unit = {},
) {
    private val lock = Any()
    private var nextId = 0L
    private val _stack: MutableStateFlow<List<NavEntry>>

    init {
        val rootEntry = NavEntry(nextId++, root)
        onEntryAdded(rootEntry)
        _stack = MutableStateFlow(listOf(rootEntry))
    }

    val stack: StateFlow<List<NavEntry>> = _stack.asStateFlow()
    val current: NavEntry get() = _stack.value.last()
    val canGoBack: Boolean get() = _stack.value.size > 1

    private fun fromIsTop(from: Long?) = from == null || _stack.value.last().id == from

    /** Legt ein Ziel oben auf den Stapel; `false`, wenn [from] nicht mehr oben liegt. */
    fun push(screen: Screen, from: Long? = null): Boolean = synchronized(lock) {
        if (!fromIsTop(from)) return false
        val entry = NavEntry(nextId++, screen)
        onEntryAdded(entry)
        _stack.value = _stack.value + entry
        true
    }

    /**
     * Ersetzt das oberste Ziel (z. B. Spiel → Ergebnis, Ergebnis → Revanche). Die Wurzel wird nie ersetzt;
     * dann (und bei veraltetem [from]) passiert nichts und die Methode liefert `false`.
     */
    fun replaceTop(screen: Screen, from: Long? = null): Boolean {
        val old: NavEntry
        synchronized(lock) {
            if (_stack.value.size <= 1 || !fromIsTop(from)) return false
            old = _stack.value.last()
            val entry = NavEntry(nextId++, screen)
            onEntryAdded(entry)
            _stack.value = _stack.value.dropLast(1) + entry
        }
        onEntryRemoved(old)
        return true
    }

    /** Geht einen Schritt zurück; `false`, wenn nur noch die Wurzel übrig ist oder [from] nicht mehr oben liegt. */
    fun back(from: Long? = null): Boolean {
        val old: NavEntry
        synchronized(lock) {
            if (_stack.value.size <= 1 || !fromIsTop(from)) return false
            old = _stack.value.last()
            _stack.value = _stack.value.dropLast(1)
        }
        onEntryRemoved(old)
        return true
    }

    /** Zurück bis zur Wurzel (Hauptmenü). */
    fun popToRoot() {
        val removed: List<NavEntry>
        synchronized(lock) {
            removed = _stack.value.drop(1)
            if (removed.isEmpty()) return
            _stack.value = _stack.value.take(1)
        }
        removed.asReversed().forEach(onEntryRemoved)
    }
}
