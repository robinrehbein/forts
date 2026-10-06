package de.bollwerk.engine.view

import de.bollwerk.engine.sim.GameResult

/** Waffe des lokalen Spielers für den FEUER-Ring / Waffenwahl. */
data class HudWeapon(
    val deviceRef: Long,
    val typeId: Int,
    /** Nachlade-Fortschritt 0..1 (1 = bereit). */
    val reload01: Float,
    val ready: Boolean,
)

/** Techgebäude im Bau. */
data class HudTechBuild(val deviceRef: Long, val typeId: Int, val progress01: Float)

/**
 * Sim-abgeleitete Werte für die HUD-Chips (Stil-Bibel §7). Reine Daten; Formatierung (Komma, Einheiten)
 * macht die App. UI-Werkzeugzustand (aktives Werkzeug, Ghost-Kosten) gehört **nicht** hierher, sondern in
 * `OverlayState`/App-State.
 */
data class HudModel(
    val metal: Float = 0f,
    /** ⚙ pro s. */
    val metalRate: Float = 0f,
    val metalCap: Float = 0f,
    val energy: Float = 0f,
    val energyCap: Float = 0f,
    /** ⚡ pro s. */
    val energyRate: Float = 0f,
    /** Spielzeit in s (Tick · dt). */
    val timeSeconds: Float = 0f,
    /** m/s, positiv = nach rechts. */
    val windSpeed: Float = 0f,
    /** TP-Anteil des eigenen Reaktors 0..1. */
    val ownReactor01: Float = 1f,
    /** TP-Anteil des gegnerischen Reaktors 0..1. */
    val enemyReactor01: Float = 1f,
    /** Spieler, dessen Sicht das HUD zeigt. */
    val localPlayer: Int = 0,
    /** Aktiver Spieler im Zugmodus (−1 = Echtzeit). */
    val activePlayer: Int = -1,
    val turnNumber: Int = 0,
    /** Restzeit des Zugs in s (0 = unbegrenzt). */
    val turnSecondsLeft: Float = 0f,
    val result: GameResult = GameResult.Ongoing,
    /** Besessene Tech-Indizes, aufsteigend (Sperr-Schlösser in der Toolbar). */
    val unlockedTechs: List<Int> = emptyList(),
    /** Eigene Waffen in Geräte-Reihenfolge. */
    val weapons: List<HudWeapon> = emptyList(),
    /** Eigene Techgebäude im Bau. */
    val buildingTech: List<HudTechBuild> = emptyList(),
    /** Einträge im Zurück-Journal. */
    val undoCount: Int = 0,
) {
    companion object {
        val EMPTY: HudModel = HudModel()
    }
}
