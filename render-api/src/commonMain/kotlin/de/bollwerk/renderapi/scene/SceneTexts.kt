package de.bollwerk.renderapi.scene

import de.bollwerk.engine.command.RejectReason

/**
 * Texte, die der Szenen-Renderer selbst auf die Spielfläche schreibt (Ghost-Grund, Scheitelhöhe, Längenchip).
 * Standard: Deutsch (Versalien wie im Mockup). Die App kann eine Unterklasse mit lokalisierten Texten
 * (`strings.xml`, [RejectReason.displayKey]) übergeben. Rückgaben sollten gecachte Konstanten sein
 * (kein Aufbau je Frame).
 */
open class SceneTexts {
    private val reasons: Array<String> = Array(RejectReason.entries.size) { defaultReason(RejectReason.entries[it]) }

    /** Kurzer Grund, warum ein Ghost ungültig ist (Versalien). */
    open fun reason(r: RejectReason?): String = if (r == null) "UNGÜLTIG" else reasons[r.ordinal]

    /** Scheitelhöhe der Flugbahn (ganze Meter). */
    open fun apex(heightM: Int): String = "SCHEITEL $heightM m"

    /** Länge eines Ghost-Balkens, z. B. `4,6 m` (Dezimalkomma). */
    open fun length(meters: Float): String {
        val t = (meters * 10f + 0.5f).toInt()
        return "${t / 10},${t % 10} m"
    }

    /** Kosten als ganze Zahl. */
    open fun cost(v: Float): String = (v + 0.5f).toInt().toString()

    companion object {
        fun defaultReason(r: RejectReason): String = when (r) {
            RejectReason.NOT_ENOUGH_METAL -> "ZU WENIG METALL"
            RejectReason.NOT_ENOUGH_ENERGY -> "ZU WENIG ENERGIE"
            RejectReason.TOO_LONG -> "ZU LANG"
            RejectReason.TOO_SHORT -> "ZU KURZ"
            RejectReason.NOT_CONNECTED -> "NICHT VERBUNDEN"
            RejectReason.OUT_OF_BUILD_ZONE -> "AUSSERHALB DER BAUZONE"
            RejectReason.BLOCKED_BY_TERRAIN -> "GELÄNDE IM WEG"
            RejectReason.DUPLICATE_BEAM -> "BALKEN EXISTIERT"
            RejectReason.OCCUPIED -> "PLATZ BELEGT"
            RejectReason.LOCKED_TECH -> "TECH GESPERRT"
            RejectReason.INVALID_TARGET -> "UNGÜLTIGES ZIEL"
            RejectReason.STALE_TARGET -> "ZIEL VERALTET"
            RejectReason.NOT_OWNER -> "NICHT DEIN OBJEKT"
            RejectReason.NOT_YOUR_TURN -> "NICHT DEIN ZUG"
            RejectReason.RELOADING -> "LÄDT NACH"
            RejectReason.STILL_BUILDING -> "NOCH IM BAU"
            RejectReason.NEEDS_ORE -> "NUR ÜBER ERZ"
            RejectReason.TOP_MOUNT_ONLY -> "NUR OBEN MONTIERBAR"
            RejectReason.UNIQUE_LIMIT -> "NUR EINMAL ERLAUBT"
            RejectReason.REACTOR_PROTECTED -> "REAKTOR GESCHÜTZT"
            RejectReason.NOTHING_TO_UNDO -> "NICHTS ZURÜCKZUNEHMEN"
            RejectReason.UNDO_BLOCKED -> "ZURÜCK NICHT MÖGLICH"
            RejectReason.UNKNOWN_CONTENT -> "UNBEKANNT"
            RejectReason.GAME_OVER -> "SPIEL VORBEI"
        }
    }
}
