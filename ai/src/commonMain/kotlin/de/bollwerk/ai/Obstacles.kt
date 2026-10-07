package de.bollwerk.ai

import de.bollwerk.engine.tools.ShotSweep
import de.bollwerk.engine.view.GameView

/** Ergebnis eines [Obstacles.cast]. */
class CastHit {
    var kind: Int = NONE
    /** Balken- bzw. Geräte-Slot. */
    var id: Int = -1
    var owner: Int = -1
    /** Parameter 0..1 auf dem Segment (FX1: nicht mehr gefüllt, siehe [x]/[y]). */
    var t: Float = 2f
    var x: Float = 0f
    var y: Float = 0f
    /** Erste eigene geschlossene Tür, die überquert (aber als passierbar gewertet) wurde, oder −1. */
    var doorCrossed: Int = -1

    fun reset() { kind = NONE; id = -1; owner = -1; t = 2f; doorCrossed = -1 }

    companion object {
        const val NONE = 0
        const val BEAM = 1
        const val DEVICE = 2
        const val TERRAIN = 3
    }
}

/**
 * Momentaufnahme aller Hindernisse (Balken als Kapseln, Geräte als Kreise) für die Schusslinien-Prüfung der KI.
 * Je Denkschritt einmal aus der [GameView] aufgebaut (nie aus dem `GameState`), danach allokationsfrei abfragbar.
 *
 * FX1: Die Kollision steckt **ausschließlich** im gemeinsamen [ShotSweep] der Engine (derselbe Swept-Test wie
 * `ProjectileSystem`, auch von der Zielvorschau benutzt); diese Klasse übersetzt nur in [CastHit]. Der [AimController]
 * verfolgt ganze Bahnen direkt über [sweep] (`ShotSweep.ballistic`), damit Vorhersage und Flug übereinstimmen.
 */
class Obstacles {
    /** Gemeinsame Bahnprüfung der Engine mit der Momentaufnahme des letzten [rebuild]. */
    val sweep: ShotSweep = ShotSweep()

    /** Anzahl erfasster Balken/Geräte der Momentaufnahme. */
    var beamCount: Int = 0; private set
    var deviceCount: Int = 0; private set

    /** Aufbau aus dem aktuellen Zustand (alle lebenden Balken samt Türzustand, alle lebenden Geräte). */
    fun rebuild(view: GameView) {
        sweep.capture(view)
        var nb = 0
        val beams = view.beamView
        for (j in 0 until beams.size) if (beams.isAlive(j)) nb++
        beamCount = nb
        var nd = 0
        val d = view.deviceView
        for (i in 0 until d.size) if (d.isAlive(i)) nd++
        deviceCount = nd
    }

    /**
     * Frühester Treffer des Kreises (Radius [radius]) auf dem Weg P0 → P1 (Türzustand jetzt).
     * @param ignoreBeam Balken-Slot, der übersprungen wird (Montagebalken der eigenen Waffe), samt Geräten darauf.
     * @param passOwner eigene **geschlossene** Türen dieses Spielers gelten als passierbar (die KI kann sie öffnen);
     *   die erste davon steht in [CastHit.doorCrossed]. −1 = Türen blockieren.
     * @param autoDoor diese Tür (Slot) öffnet sich beim Schuss ohnehin und wird nicht gemeldet; −1 = keine.
     * @param withTerrain auch das Gelände prüfen.
     * @param ignoreBeam2 weiterer übersprungener Balken (schon durchschlagener Balken beim Scharfschützen), −1 = keiner.
     * @return `true` bei einem Treffer.
     */
    fun cast(
        x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, ignoreBeam: Int, passOwner: Int, autoDoor: Int,
        withTerrain: Boolean, out: CastHit, ignoreBeam2: Int = -1,
    ): Boolean {
        out.reset()
        val hit = sweep.cast(x0, y0, x1, y1, radius, ignoreBeam, ignoreBeam2, ignoreBeam, withTerrain, autoDoor, passOwner)
        out.doorCrossed = sweep.doorCrossed
        if (!hit) return false
        copyHit(sweep, out)
        return true
    }

    /** Mittelpunkt und Radius eines Geräts aus der Aufnahme; @return false, wenn nicht vorhanden. */
    fun deviceCircle(deviceId: Int, out: FloatArray): Boolean = sweep.deviceCircle(deviceId, out)

    companion object {
        /** Ersten Treffer der letzten [ShotSweep]-Abfrage nach [out] übernehmen (gleiche Art-Konstanten). */
        fun copyHit(s: ShotSweep, out: CastHit) {
            out.kind = s.hitKind
            out.id = s.hitId
            out.owner = s.hitOwner
            out.x = s.hitX
            out.y = s.hitY
            out.t = 0f
            out.doorCrossed = s.doorCrossed
        }
    }
}
