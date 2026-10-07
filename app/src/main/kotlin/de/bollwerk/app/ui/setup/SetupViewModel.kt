package de.bollwerk.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bollwerk.app.match.AiLevel
import de.bollwerk.app.match.GameMode
import de.bollwerk.app.match.MapOption
import de.bollwerk.app.match.MatchConfig
import de.bollwerk.app.match.StartResources
import de.bollwerk.app.match.TeamColor
import de.bollwerk.app.nav.Navigator
import de.bollwerk.app.nav.Screen
import de.bollwerk.app.settings.SettingsRepository
import de.bollwerk.app.settings.SetupPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val mode: GameMode,
    val map: MapOption = MapOption.SCHLUCHT,
    val aiLevel: AiLevel = AiLevel.NORMAL,
    val resources: StartResources = StartResources.NORMAL,
    val team: TeamColor = TeamColor.BLUE,
) {
    fun toPrefs() = SetupPrefs(map, aiLevel, resources, team)
}

/** Gefecht-Setup (Mockup 2): sammelt die Optionen und erzeugt die [MatchConfig]. */
class SetupViewModel(
    mode: GameMode,
    private val settings: SettingsRepository,
    private val navigator: Navigator,
    private val entryId: Long = navigator.current.id,
    private val seedSource: () -> Long,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState(mode))
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    /** Felder, die der Nutzer schon geändert hat, bevor die gespeicherten Werte eintrafen; sie bleiben unangetastet. */
    private enum class Field { MAP, AI, RESOURCES, TEAM }

    private val edited = mutableSetOf<Field>()

    init {
        viewModelScope.launch {
            val saved = settings.setupPrefs.first()
            // Feldweise zusammenführen: nur Felder übernehmen, die der Nutzer noch nicht angefasst hat.
            _state.update {
                it.copy(
                    map = if (Field.MAP in edited) it.map else saved.map,
                    aiLevel = if (Field.AI in edited) it.aiLevel else saved.aiLevel,
                    resources = if (Field.RESOURCES in edited) it.resources else saved.resources,
                    team = if (Field.TEAM in edited) it.team else saved.team,
                )
            }
        }
    }

    fun selectMap(map: MapOption) = edit(Field.MAP) { it.copy(map = map) }
    fun selectAiLevel(level: AiLevel) = edit(Field.AI) { it.copy(aiLevel = level) }
    fun selectResources(resources: StartResources) = edit(Field.RESOURCES) { it.copy(resources = resources) }
    fun selectTeam(team: TeamColor) = edit(Field.TEAM) { it.copy(team = team) }

    private inline fun edit(field: Field, block: (SetupUiState) -> SetupUiState) {
        edited += field
        _state.update { block(it) }
    }

    fun buildConfig(): MatchConfig = _state.value.let {
        MatchConfig(
            map = it.map,
            mode = it.mode,
            aiLevel = it.aiLevel,
            resources = it.resources,
            team = it.team,
            seed = seedSource(),
        )
    }

    fun onStart() {
        val config = buildConfig()
        // Ein zweiter Tipp, während das Spiel schon oben liegt, startet keine zweite Partie.
        if (!navigator.push(Screen.Game(config), from = entryId)) return
        val prefs = _state.value.toPrefs()
        viewModelScope.launch { settings.saveSetup(prefs) }
    }

    fun onBack() {
        navigator.back(from = entryId)
    }
}
