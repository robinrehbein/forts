package de.bollwerk.engine.view

import de.bollwerk.engine.sim.GameResult
import de.bollwerk.engine.sim.TurnMode
import de.bollwerk.engine.sim.TurnPhase

/** Waffe des lokalen Spielers für den FEUER-Ring / Waffenwahl. */
data class HudWeapon(
    val deviceRef: Long,
    val typeId: Int,
    /** Nachlade-Fortschritt 0..1 (1 = bereit). */
    val reload01: Float,
    /** Schussbereit: nachgeladen, fertig gebaut und nicht ausgeschaltet. */
    val ready: Boolean,
    /** Bau-Fortschritt 0..1 (1 = fertig). */
    val build01: Float = 1f,
    /** Aktueller Zielwinkel (Bogenmaß) und Kraft. */
    val aimAngle: Float = 0f,
    val power: Float = 0f,
)

/** Techgebäude im Bau. */
data class HudTechBuild(
    val deviceRef: Long,
    val typeId: Int,
    val progress01: Float,
    /** Restliche Bauzeit in s. */
    val secondsLeft: Float = 0f,
)

/**
 * Sim-abgeleitete Werte für die HUD-Chips (Stil-Bibel §7). Reine Daten; Formatierung (Komma, Einheiten)
 * macht die App. UI-Werkzeugzustand (aktives Werkzeug, Ghost-Kosten) gehört **nicht** hierher, sondern in
 * `OverlayState`/App-State.
 *
 * **Hotseat:** Das HUD zeigt die Sicht des **aktiven** Spielers (`SnapshotBuilder.followActivePlayer`, vom `MatchRunner`
 * gesetzt). In der Phase [TurnPhase.HANDOVER] ist der nächste Spieler bereits aktiv; die App deckt dann alles mit dem
 * Übergabe-Bildschirm ab und zeigt [handoverCountdown] (3-2-1), damit der vorherige Spieler nichts vom nächsten sieht.
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
    /** TP-Anteil des eigenen Reaktors 0..1 (0 = zerstört). */
    val ownReactor01: Float = 1f,
    /** TP-Anteil des gegnerischen Reaktors 0..1 (0 = zerstört). */
    val enemyReactor01: Float = 1f,
    /** Spieler, dessen Sicht das HUD zeigt. */
    val localPlayer: Int = 0,
    /** Aktiver Spieler im Zugmodus (−1 = Echtzeit). */
    val activePlayer: Int = -1,
    val turnNumber: Int = 0,
    /** Restzeit des Spielzugs ([TurnPhase.PLAY]) in s; 0 = unbegrenzt oder gerade keine Spielphase. */
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
    // ---- WP5, additiv ----
    val turnMode: TurnMode = TurnMode.REALTIME,
    val turnPhase: TurnPhase = TurnPhase.PLAY,
    /** Zuglänge in s (0 = unbegrenzt), für den Zeitbalken. */
    val turnSecondsTotal: Float = 0f,
    /** Restzeit der Nachlauf-Phase ([TurnPhase.RESOLVE]) in s, sonst 0. */
    val resolveSecondsLeft: Float = 0f,
    /** Restzeit der Übergabe ([TurnPhase.HANDOVER]) in s, sonst 0. */
    val handoverSecondsLeft: Float = 0f,
    /** Angezeigte Zahl der Übergabe (aufgerundete Sekunden: 3, 2, 1), sonst 0. */
    val handoverCountdown: Int = 0,
    /** Übergabe läuft: Wer jetzt dran ist, steht in [activePlayer]. */
    val handover: Boolean = false,
    /** Darf der HUD-Spieler jetzt Commands geben (Spiel läuft; im Zugmodus: eigener Zug in der Spielphase)? */
    val canCommand: Boolean = true,
    /** Eigener Reaktor steht noch (Spieler lebt). */
    val localAlive: Boolean = true,
    val playerCount: Int = 2,
    /** Sim pausiert (Loop-Zustand, nicht Teil des Sim-States). */
    val paused: Boolean = false,
    /** Sim-Geschwindigkeit (1 = Echtzeit). */
    val speed: Float = 1f,
) {
    companion object {
        val EMPTY: HudModel = HudModel()
    }
}
