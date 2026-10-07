package de.bollwerk.engine.tools

import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.sim.UndoKind

/** Einrast-Markierung (Snap-Ring) in Welt-Metern; [nodeRef] = -1 bei einem Teilungspunkt auf einem Balken. */
data class SnapMark(val x: Float, val y: Float, val nodeRef: Long, val active: Boolean)

/**
 * Lupen-Anforderung (Stil-Bibel §7: Lupe 2x, 80 dp über dem Finger, Fadenkreuz). Die Engine kennt keine Bildschirmpunkte:
 * [worldX]/[worldY] ist die Fingerposition in Welt-Metern (die App rechnet in Bildschirmkoordinaten um und setzt die Lupe
 * darüber); [targetX]/[targetY] ist der eingerastete Zielpunkt (Fadenkreuz-Mitte, falls vom Finger abweichend).
 */
data class LoupeRequest(val worldX: Float, val worldY: Float, val targetX: Float, val targetY: Float)

/**
 * Zielvorschau (Stil-Bibel Zielen-Mockup: Mörser 52° · 78 %, Scheitel 24 m, Winddrift +3 m).
 * Alle Längen in Metern, Winkel in Bogenmaß (0 = rechts, positiv = nach oben; wie `SetAim`).
 */
data class AimInfo(
    val deviceRef: Long,
    val angle: Float,
    val power: Float,
    /** Winkel in Grad als Elevation von der Waagerechten zur Feindseite (Anzeige "52°"), immer in −180..180. */
    val elevationDeg: Float,
    /** Kraft in Prozent (30..100). */
    val powerPercent: Float,
    /** Splash-Radius des Schusses für den Einschlag-Kreis (0 ohne Splash). */
    val splashRadiusM: Float,
    /** Scheitelpunkt der Bahn (NaN ohne Flugbahn). */
    val apexX: Float,
    val apexY: Float,
    /** Höhe des Scheitels über der Mündung. */
    val apexHeightM: Float,
    /** Windversatz am Einschlag in Schussrichtung (positiv = Wind trägt weiter), 0 ohne Wind. */
    val windDriftM: Float,
    /** Die Bahn endet auf dem Gelände (Einschlag-Fadenkreuz und Splash-Kreis zeichnen); sonst abgeschnitten oder [exitedMap]. */
    val hasImpact: Boolean,
    /** Die Bahn verlässt die Karte ohne Geländetreffer (kein Einschlag, nichts in die Luft zeichnen). */
    val exitedMap: Boolean = false,
    /**
     * FX1: erster Treffer der Bahn mit derselben Kollision wie die Sim ([ShotSweep]). [TrajectoryOutcome.BLOCKED_OWN]:
     * der Schuss explodiert in der eigenen Festung (Bahn rot, Warnmarke am Einschlag).
     */
    val outcome: TrajectoryOutcome = TrajectoryOutcome.CLEAR,
    /**
     * Kurzer Grund für die Zielkarte als Ressourcen-Schlüssel, z. B. [ShotSweep.REASON_BLOCKED_OWN]
     * (`aim_blocked_own_fort`: de "eigene Festung im Weg", en "own fort in the way"); null = kein Hinweis.
     */
    val blockedReasonKey: String? = null,
) {
    /** Der Schuss träfe zuerst die eigene Festung. */
    val blockedOwn: Boolean get() = outcome == TrajectoryOutcome.BLOCKED_OWN
}

/** Aktion eines Tipp-Werkzeugs bzw. Kontextmenü-Eintrags. */
enum class TapAction { REPAIR, DELETE, DOOR }

/**
 * Vorschau eines Tipp-Ziels (Reparatur/Löschen/Tür): Welches Objekt würde getroffen, ob es geht, was es kostet/erstattet.
 * [ref] ist die Balken- bzw. Geräte-Ref ([isDevice]).
 */
data class TargetHint(
    val action: TapAction,
    val isDevice: Boolean,
    val ref: Long,
    val valid: Boolean,
    val reason: RejectReason?,
    /** Ankerpunkt für Chips (Mitte des Balkens bzw. Gerätezentrum). */
    val x: Float,
    val y: Float,
    /** Löschen: erstattetes Metall/Energie (Balken inkl. darauf stehender Geräte). */
    val refundMetal: Float = 0f,
    val refundEnergy: Float = 0f,
    /** Reparatur: Metallkosten bis voll. */
    val costMetal: Float = 0f,
    /** Löschen eines brennenden Balkens löscht nur das Feuer (keine Erstattung); Reparatur pausiert beim Brennen. */
    val burning: Boolean = false,
    /** Tür: aktuell offen. */
    val doorOpen: Boolean = false,
)

/** Eintrag des Kontextmenüs (Langdruck). */
data class ContextOption(val action: TapAction, val enabled: Boolean, val reason: RejectReason?, val hint: TargetHint)

/** Kontextmenü am Ziel (Langdruck auf Balken bzw. Gerät): Auswahl Reparatur / Löschen / Tür. */
data class ContextMenu(
    val isDevice: Boolean,
    val targetRef: Long,
    /** Ankerpunkt in Welt-Metern (Fingerposition beim Langdruck). */
    val x: Float,
    val y: Float,
    val options: List<ContextOption>,
)

/** Zustand von "Zurück" für den Button (aktiv/inaktiv) und die Hervorhebung des Bauteils. */
data class UndoPreview(
    val canUndo: Boolean,
    val reason: RejectReason?,
    val kind: UndoKind?,
    /** Ref des Balkens/Geräts, das zurückgenommen würde (−1 ohne). */
    val ref: Long,
    val refundMetal: Float,
    val refundEnergy: Float,
    /** Länge des Zurück-Journals. */
    val entries: Int,
)

/**
 * Vollständige Beschreibung dessen, was die Werkzeuge über der Spielwelt zeigen. Reine Daten in Welt-Metern, unveränderlich
 * (an den Render-Thread übergebbar). `renderapi.OverlayState` entsteht daraus 1:1: `mode`, `tool`, `ghost`, `ghostDevice`,
 * `snaps` (→ `SnapHighlight`), `loupe` (→ `Loupe` mit Bildschirmkoordinaten aus [LoupeRequest]), `trajectory`, `impactX/Y`,
 * `selectedDeviceRef`, `selectedBeamRef`. Zusätze ([aim], [hint], [contextMenu], [rejected]) füllt die HUD-Schicht.
 */
data class ToolOverlay(
    val mode: ToolMode = ToolMode.NONE,
    val tool: ToolSelection = ToolSelection.None,
    val ghost: GhostBeam? = null,
    val ghostDevice: GhostDevice? = null,
    val snaps: List<SnapMark> = emptyList(),
    val loupe: LoupeRequest? = null,
    val trajectory: Trajectory? = null,
    val impactX: Float = Float.NaN,
    val impactY: Float = Float.NaN,
    val selectedDeviceRef: Long = -1,
    val selectedBeamRef: Long = -1,
    val aim: AimInfo? = null,
    val hint: TargetHint? = null,
    val contextMenu: ContextMenu? = null,
    /** Grund, warum die **laufende** Vorschau (Ghost, Gerät, Ziel) ungültig ist (für Text/Farbe), sonst null. */
    val rejected: RejectReason? = null,
    val localPlayer: Int = 0,
    /**
     * Grund der zuletzt abgelehnten Aktion (Geste ohne Command, Kontextmenü-Wahl, FEUER, Zurück) für den Ablehnungs-Flash;
     * bleibt stehen. Neu zu zeigen, wenn [lastRejectSeq] größer ist als beim letzten gezeigten Flash.
     */
    val lastReject: RejectReason? = null,
    /** Zähler der Ablehnungen (0 = noch keine). */
    val lastRejectSeq: Int = 0,
    /** Sim-Tick der letzten Ablehnung. */
    val lastRejectTick: Long = 0L,
) {
    companion object {
        val NONE: ToolOverlay = ToolOverlay()
    }
}

/**
 * Ergebnis eines Aufrufs von [ToolController]. **Wiederverwendetes Objekt**: gilt nur bis zum nächsten Aufruf; die
 * Commands sofort an `LocalInputSource.push` übergeben.
 */
class ToolResult {
    /** Zu sendende Commands in Reihenfolge (meist 0 oder 1; bei "Loslassen = Feuern" `SetAim` + `Fire`). */
    val commands: ArrayList<Command> = ArrayList(2)

    /**
     * Hat ein Werkzeug die Geste übernommen? `false`: der Eingabe-Controller darf sie als Kamera-Schwenk behandeln (Bauen
     * ohne Startpunkt, Zielen ohne Waffe, Tipp-Werkzeug nach Überschreiten der Tipp-Toleranz).
     */
    var consumed: Boolean = false

    /** Ablehnungsgrund, wenn die Geste ohne Command endete, obwohl ein Ziel da war (z. B. "zu wenig Metall"-Blinken). */
    var rejected: RejectReason? = null

    /** Das erste Command oder `null`. */
    val command: Command? get() = if (commands.isEmpty()) null else commands[0]

    internal fun clear() {
        commands.clear()
        consumed = false
        rejected = null
    }
}
