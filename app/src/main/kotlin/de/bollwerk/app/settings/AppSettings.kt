package de.bollwerk.app.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.bollwerk.app.match.AiLevel
import de.bollwerk.app.match.MapOption
import de.bollwerk.app.match.StartResources
import de.bollwerk.app.match.TeamColor

/** Einstellungen aus dem Einstellungs-Screen (Sprache folgt immer dem System und wird nicht gespeichert). */
data class AppSettings(
    /** Effekt-Lautstärke 0..1. */
    val soundVolume: Float = 0.8f,
    /** Musik-Lautstärke 0..1. */
    val musicVolume: Float = 0.5f,
    /** Spiegelt Toolbar und Feuer-Button im HUD. */
    val leftHanded: Boolean = false,
    /** Schuss löst beim Loslassen der Zielgeste aus. */
    val releaseToFire: Boolean = false,
    /** Weniger Partikel, kein Kamera-Shake. */
    val reducedEffects: Boolean = false,
)

/** Zuletzt gewählte Gefecht-Setup-Optionen (damit das Setup beim nächsten Mal wieder passt). */
data class SetupPrefs(
    val map: MapOption = MapOption.SCHLUCHT,
    val aiLevel: AiLevel = AiLevel.NORMAL,
    val resources: StartResources = StartResources.NORMAL,
    val team: TeamColor = TeamColor.BLUE,
)

/** DataStore-Schlüssel und die reine Abbildung Preferences ⇄ Datenklassen (ohne Android testbar). */
object PrefKeys {
    val SOUND = floatPreferencesKey("sound_volume")
    val MUSIC = floatPreferencesKey("music_volume")
    val LEFT_HANDED = booleanPreferencesKey("left_handed")
    val RELEASE_TO_FIRE = booleanPreferencesKey("release_to_fire")
    val REDUCED_FX = booleanPreferencesKey("reduced_effects")

    val SETUP_MAP = stringPreferencesKey("setup_map")
    val SETUP_AI = stringPreferencesKey("setup_ai")
    val SETUP_RESOURCES = stringPreferencesKey("setup_resources")
    val SETUP_TEAM = stringPreferencesKey("setup_team")
}

private inline fun <reified E : Enum<E>> Preferences.enumOrDefault(
    key: Preferences.Key<String>,
    default: E,
): E = this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

fun Preferences.toAppSettings(): AppSettings {
    val d = AppSettings()
    return AppSettings(
        soundVolume = (this[PrefKeys.SOUND] ?: d.soundVolume).coerceIn(0f, 1f),
        musicVolume = (this[PrefKeys.MUSIC] ?: d.musicVolume).coerceIn(0f, 1f),
        leftHanded = this[PrefKeys.LEFT_HANDED] ?: d.leftHanded,
        releaseToFire = this[PrefKeys.RELEASE_TO_FIRE] ?: d.releaseToFire,
        reducedEffects = this[PrefKeys.REDUCED_FX] ?: d.reducedEffects,
    )
}

fun androidx.datastore.preferences.core.MutablePreferences.write(settings: AppSettings) {
    this[PrefKeys.SOUND] = settings.soundVolume.coerceIn(0f, 1f)
    this[PrefKeys.MUSIC] = settings.musicVolume.coerceIn(0f, 1f)
    this[PrefKeys.LEFT_HANDED] = settings.leftHanded
    this[PrefKeys.RELEASE_TO_FIRE] = settings.releaseToFire
    this[PrefKeys.REDUCED_FX] = settings.reducedEffects
}

fun Preferences.toSetupPrefs(): SetupPrefs {
    val d = SetupPrefs()
    return SetupPrefs(
        map = enumOrDefault(PrefKeys.SETUP_MAP, d.map),
        aiLevel = enumOrDefault(PrefKeys.SETUP_AI, d.aiLevel),
        resources = enumOrDefault(PrefKeys.SETUP_RESOURCES, d.resources),
        team = enumOrDefault(PrefKeys.SETUP_TEAM, d.team),
    )
}

fun androidx.datastore.preferences.core.MutablePreferences.write(prefs: SetupPrefs) {
    this[PrefKeys.SETUP_MAP] = prefs.map.name
    this[PrefKeys.SETUP_AI] = prefs.aiLevel.name
    this[PrefKeys.SETUP_RESOURCES] = prefs.resources.name
    this[PrefKeys.SETUP_TEAM] = prefs.team.name
}
