package de.bollwerk.engine.tools

/** Phase eines Zeigers (Finger/Maus) für [ToolController.pointer]. */
enum class PointerPhase { DOWN, MOVE, UP, CANCEL }

/**
 * Interaktionsmodus (Stil-Bibel §7: Modusschalter ZIELEN / BAUEN). Namen entsprechen `renderapi.InteractionMode`,
 * damit die App sie 1:1 mit `InteractionMode.valueOf(mode.name)` abbilden kann.
 */
enum class ToolMode { NONE, BUILD, AIM }

/**
 * Einstellungen der Werkzeuge (vom Einstellungsmenü gesetzt, zur Laufzeit änderbar; nur auf dem Thread lesen/schreiben,
 * der die Werkzeuge treibt).
 */
class ToolSettings {
    /** Kettenmodus: Nach einem gesetzten Balken beginnt der nächste automatisch am neuen Endknoten. */
    var chainMode: Boolean = false

    /** "Loslassen = Feuern": Beim Loslassen einer Zielgeste folgt auf `SetAim` sofort `Fire`. */
    var releaseToFire: Boolean = false

    /**
     * Live-Zielen: Während des Ziehens höchstens alle so viele Ticks ein `SetAim` senden (Rohr folgt dem Finger). 0 = nur
     * beim Loslassen (Standard; hält Replays/Netzwerk klein).
     */
    var liveAimIntervalTicks: Int = 0

    /** Winkelrasten (15°) beim freien Balkenende. */
    var angleSnap: Boolean = true

    /** Bauen muss an einem eigenen Knoten oder Balken beginnen (Prototyp). Aus: freier Start, Ende muss dann einrasten. */
    var requireConnectedStart: Boolean = true
}

/** Feste Werte der Werkzeuge (Prototyp `snapStart`/`evalBuild`/`evalDevice`, Pixel durch Maßstab ersetzt: Radien als Faktor). */
object ToolConst {
    /** Balken-Fangradius relativ zu `ToolContext.pickRadiusM` (Prototyp 14 px zu 24 px). */
    const val BEAM_SNAP_FACTOR: Float = 0.6f

    /** Geräte-Fangradius zum Balken relativ zu `pickRadiusM` (Prototyp 26 px zu 24 px). */
    const val DEVICE_BEAM_FACTOR: Float = 1.1f

    /** Tür-Fangradius relativ zu `pickRadiusM` (Prototyp 16 px zu 24 px). */
    const val DOOR_PICK_FACTOR: Float = 0.7f

    /** Waffen-Auswahlradius relativ zu `pickRadiusM` (Prototyp 34 px zu 24 px), mindestens [WEAPON_PICK_MIN_M]. */
    const val WEAPON_PICK_FACTOR: Float = 1.4f
    const val WEAPON_PICK_MIN_M: Float = 1.6f

    /** Ziehen ab diesem Abstand (relativ zu `pickRadiusM`) zählt als Geste (Prototyp 10-12 px). */
    const val DRAG_SLOP_FACTOR: Float = 0.45f

    /** Winkelraster 15°. */
    const val ANGLE_STEP: Float = 0.2617994f

    /** Einrasten, wenn der Winkel höchstens so weit (rad, Prototyp 0,1) vom Raster abweicht. */
    const val ANGLE_SNAP_TOLERANCE: Float = 0.1f

    /** Winkelrasten nur ab dieser Länge (m). */
    const val ANGLE_SNAP_MIN_LENGTH: Float = 0.5f

    /** Längenrasten auf die Höchstlänge, wenn die Länge in `(max − 0,6, max + 1,3)` liegt (Prototyp). */
    const val LENGTH_SNAP_BELOW: Float = 0.6f
    const val LENGTH_SNAP_ABOVE: Float = 1.3f

    /** Endknoten näher als so viel (m) über dem Boden wird auf den Boden gesetzt (Prototyp 0,5). */
    const val GROUND_SNAP: Float = 0.5f

    /** Zieh-Totzone der Zielgeste relativ zu `pickRadiusM` (Prototyp 14 px). */
    const val AIM_DEAD_ZONE_FACTOR: Float = 0.55f

    /** Zuglänge (relativ zu `pickRadiusM`) hinter der Totzone, ab der die volle Kraft erreicht ist (Prototyp 140-260 px). */
    const val AIM_FULL_POWER_FACTOR: Float = 7f

    /** Höchstzahl der Flugbahn-Punkte (ein Punkt je Tick, 10 s). */
    const val TRAJECTORY_MAX_POINTS: Int = 600

    /** Der letzte Bahnpunkt gilt als Geländetreffer, wenn er höchstens so weit (m) über der Geländelinie liegt. */
    const val IMPACT_TERRAIN_EPS: Float = 0.1f

    /** Nach dem Loslassen bleibt die Zielvorschau so lange stehen, bis die Sim den Winkel übernommen hat (Ticks). */
    const val AIM_SETTLE_TICKS: Int = 30

    /** Tipp-Werkzeuge: Reaktionsradius für Geräte (Löschen) relativ zu `pickRadiusM`, mindestens der Trefferradius. */
    const val DEVICE_PICK_FACTOR: Float = 1.0f

    /**
     * Kette: so lange (Ticks, 2 s) wartet der Anker auf den neuen Endknoten, den die Sim für das zuletzt gesendete
     * `PlaceBeam` anlegt (Eingabe-Verzögerung); danach gilt das Command als abgelehnt und die Kette endet.
     */
    const val CHAIN_PENDING_TICKS: Int = 120
}
