package de.bollwerk.engine.rules

import de.bollwerk.engine.command.RejectReason
import de.bollwerk.engine.math.DeviceGeometry
import de.bollwerk.engine.math.FloatMath
import de.bollwerk.engine.sim.DeviceFlags
import de.bollwerk.engine.sim.DeviceProps
import de.bollwerk.engine.view.GameView
import kotlin.math.sqrt

/**
 * Konstanten der Bauregeln (Prototyp `evalBuild`/`evalDevice`). Zahlen aus der Stil-Bibel stehen in `SimConfig`
 * bzw. `SimTables`; hier nur Werte, die der Prototyp fest verdrahtet hat.
 */
object RuleConst {
    /** Beim Teilen eines Balkens (Prototyp `clamp(t, .08, .92)`) wird der Parameter auf diesen Bereich geklemmt. */
    const val SPLIT_T_MIN: Float = 0.08f
    const val SPLIT_T_MAX: Float = 0.92f

    /** Jede Hälfte eines geteilten Balkens muss mindestens so lang sein (m). */
    const val MIN_SPLIT_PIECE: Float = 0.25f

    /** Geräte werden auf diesen Parameterbereich des Balkens geklemmt (Prototyp `clamp(t, .15, .85)`). */
    const val DEVICE_T_MIN: Float = 0.15f
    const val DEVICE_T_MAX: Float = 0.85f

    /** Turbinen: Normale muss mindestens so stark nach oben zeigen (Prototyp `ny > −0,5` ungültig; y wächst nach unten). */
    const val TOP_MOUNT_NY: Float = -0.5f

    /** Toleranz (m), um die ein Punkt unter der Geländeoberfläche liegen darf (Boden-Knoten, flach aufliegende Balken). */
    const val TERRAIN_EPS: Float = 0.05f

    /** Abtastschritt (m) für den Balken-Gelände-Test. */
    const val TERRAIN_STEP: Float = 0.25f

    /** Mine: Erz darf höchstens so weit (m) senkrecht unter dem Montagepunkt liegen (Prototyp `< 3`). */
    const val ORE_MAX_DY: Float = 3f

    /** Geräte-Mindestabstand: Prototyp 1,5 m (`DeviceProps.minSpacing`). */
    const val DEFAULT_SPACING: Float = 1.5f

    /** Turbinen-Höhenfaktor `1 + clamp((baseY − y)/24, 0, 0,5)` (Stil-Bibel: ×1,0 bis ×1,5). */
    const val TURBINE_HEIGHT_RANGE: Float = 24f
    const val TURBINE_BONUS_MAX: Float = 0.5f

    /**
     * Toleranz (m) für Mindest-/Höchstlänge eines Balkens (Prototyp `L > MAXLEN + 0.01`): Ein Endpunkt, den die UI exakt auf
     * die Höchstlänge einrastet, misst in Float-Koordinaten oft 6,0000005 m und darf nicht abgelehnt werden.
     */
    const val LENGTH_EPS: Float = 0.01f

    /** Zurück-Vergleich von TP (Rundung). */
    const val HP_EPS: Float = 1e-3f

    /** Winkel (Bogenmaß) jenseits dieser Grenze gelten als ungültig (wickeln ohne Schleife). */
    const val MAX_ANGLE: Float = 100f
}

/** Geld- und Rückerstattungs-Rechnungen (eine Wahrheit für Validator, Anwendung, Werkzeuge und KI). */
object RuleCost {
    /**
     * Baukosten eines Balkens in ganzem Metall (Prototyp `Math.round(cost · L)`, kaufmännisch gerundet): Länge × Metallkosten
     * je Meter. Ghost-Anzeige, Validator, Anwendung und Zurück-Journal nutzen genau diesen ganzzahligen Wert.
     */
    fun beam(length: Float, costPerMeter: Float): Float = (length * costPerMeter + 0.5f).toInt().toFloat()

    /** Rückerstattung beim Abreißen eines Balkens: `deleteRefund · costPerMeter · Länge · TP-Anteil`. */
    fun beamRefund(view: GameView, beamId: Int): Float {
        val beams = view.beamView
        val mat = view.tables.materials[beams.material(beamId)]
        val factor = if (mat.restLengthFactor > 0f) mat.restLengthFactor else 1f
        val length = beams.restLength(beamId) / factor
        val maxHp = beams.maxHp(beamId)
        val frac = if (maxHp > 0f) FloatMath.clamp(beams.hp(beamId) / maxHp, 0f, 1f) else 0f
        return view.simConfig.deleteRefund * mat.costPerMeter * length * frac
    }
}

/** Gemeinsame Prüfungen. */
object RuleChecks {
    /** Euklidischer Abstand. */
    fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Ist [tech] für [player] gesperrt, warum? `STILL_BUILDING`, wenn ein Gebäude, das diese Tech gewährt, des Spielers
     * noch im Bau ist, sonst `LOCKED_TECH`. @return `null`, wenn die Tech freigeschaltet (oder [tech] < 0) ist.
     */
    fun techBlock(view: GameView, player: Int, tech: Int): RejectReason? {
        if (tech < 0 || view.player(player).hasTech(tech)) return null
        val d = view.deviceView
        val devices = view.tables.devices
        for (i in 0 until d.size) {
            if (!d.isAlive(i) || d.owner(i) != player) continue
            if (devices[d.type(i)].grantsTech == tech && d.buildTicks(i) > 0) return RejectReason.STILL_BUILDING
        }
        return RejectReason.LOCKED_TECH
    }

    /** Metall, das der Spieler tatsächlich ausgeben kann (abzüglich bereits angeforderter Schüsse dieses Ticks). */
    fun availableMetal(view: GameView, player: Int): Float = view.player(player).metal - reservedMetal(view, player)

    /** Energie, die der Spieler tatsächlich ausgeben kann (abzüglich bereits angeforderter Schüsse dieses Ticks). */
    fun availableEnergy(view: GameView, player: Int): Float = view.player(player).energy - reservedEnergy(view, player)

    /**
     * Kosten der in diesem Tick angeforderten, noch nicht verbrauchten Schüsse (`DeviceFlags.FIRE_REQUESTED`): Das
     * Waffen-System zieht die Schusskosten erst beim Feuern ab; bis dahin sind sie für Bauen und weitere Schüsse reserviert,
     * damit zwei Commands im selben Tick nie mehr ausgeben, als da ist.
     */
    fun reservedMetal(view: GameView, player: Int): Float {
        var sum = 0f
        val d = view.deviceView
        for (i in 0 until d.size) {
            if (!d.isAlive(i) || d.owner(i) != player || (d.flags(i) and DeviceFlags.FIRE_REQUESTED) == 0) continue
            val w = view.tables.devices[d.type(i)].weapon
            if (w >= 0) sum += view.tables.weapons[w].shotMetal
        }
        return sum
    }

    fun reservedEnergy(view: GameView, player: Int): Float {
        var sum = 0f
        val d = view.deviceView
        for (i in 0 until d.size) {
            if (!d.isAlive(i) || d.owner(i) != player || (d.flags(i) and DeviceFlags.FIRE_REQUESTED) == 0) continue
            val w = view.tables.devices[d.type(i)].weapon
            if (w >= 0) sum += view.tables.weapons[w].shotEnergy
        }
        return sum
    }

    /** Montage-Geometrie eines Geräts [props] auf Balken [beamId] (Welt, aktueller Zustand). */
    fun mountOn(
        view: GameView, beamId: Int, t: Float, sideNegative: Boolean, props: DeviceProps, aim: Float, out: FloatArray,
    ) {
        val beams = view.beamView
        val nodes = view.nodeView
        val mat = view.tables.materials[beams.material(beamId)]
        val a = beams.nodeA(beamId)
        val b = beams.nodeB(beamId)
        DeviceGeometry.mountAt(
            nodes.x(a), nodes.y(a), nodes.x(b), nodes.y(b), t, sideNegative,
            mat.thickness, props.mountOffset, props.pivotOffset, props.barrelLength, aim, out,
        )
    }
}
