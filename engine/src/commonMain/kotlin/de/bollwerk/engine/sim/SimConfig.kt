package de.bollwerk.engine.sim

import kotlinx.serialization.Serializable

/**
 * Feste Simulationskonstanten. Werte aus `physik-spielplatz.html` übernommen
 * (`DT`, `SUB`, `ITERS`, `G0`, `MAXLEN`, `NODE_R`, `WIND_K`, Dämpfung 0,55, Bodenreibung 0,6, Feuer, Trümmer, Türen).
 *
 * Teil des deterministischen Vertrags: Für ein Replay müssen alle Teilnehmer dieselbe Konfiguration nutzen;
 * deshalb ist sie serialisierbar und steht in jedem `Replay`. Content-Werte (Kosten, TP, Waffen) liegen nicht
 * hier, sondern in [SimTables]. Startressourcen hängen von der Schwierigkeit ab und stehen in [MatchSetup].
 */
@Serializable
data class SimConfig(
    /** Fester Tick in Sekunden (1/60 s). */
    val dt: Float = 1f / 60f,
    /** Verlet-/XPBD-Substeps pro Tick. */
    val substeps: Int = 4,
    /** Gauss-Seidel-Iterationen pro Substep. */
    val iterations: Int = 6,
    /** Erdbeschleunigung in m/s², positiv nach unten (y wächst nach unten). */
    val gravity: Float = 9.81f,
    /** Ballistische Windbeschleunigung in m/s² je m/s Windgeschwindigkeit (Prototyp `WIND_K`). */
    val windAccelPerMps: Float = 0.18f,
    /** Globale Geschwindigkeitsdämpfung pro Sekunde (`damp = 1 − k·h`). */
    val linearDamping: Float = 0.55f,
    /**
     * Anteil der Tangentialgeschwindigkeit, der pro Bodenkontakt-Iteration **entfernt** wird
     * (Prototyp: `px += vx · 0,6`, d. h. es bleiben 40 %: `v' = 0,4 · v`).
     */
    val groundFrictionRemoved: Float = 0.6f,
    /** Kollisionsradius eines Knotens am Boden in m. */
    val nodeRadius: Float = 0.12f,
    /** Darstellungsdurchmesser eines Knotens in m (Stil-Bibel 0,42 m). */
    val nodeDisplayDiameter: Float = 0.42f,
    /** Mindestmasse eines Knotens in kg. */
    val minNodeMass: Float = 8f,
    /** Maximale Balkenlänge in m. */
    val maxBeamLength: Float = 6f,
    /** Minimale Balkenlänge in m. */
    val minBeamLength: Float = 0.5f,
    /** Ein neues freies Balkenende in diesem Abstand (m) zu einem eigenen Knoten verwendet diesen Knoten. */
    val nodeMergeRadius: Float = 0.05f,
    /** TP-Verlust pro s = Dehnungsüberschuss · strainDamage · maxHp. */
    val strainDamage: Float = 40f,
    /** Bruchhälften sind nur möglich, wenn die Ruhelänge größer ist (Prototyp 0,8 m). */
    val minSplitLength: Float = 0.8f,
    /** Knarzen (RENDER-Flag `BeamPool.creaking`) ab diesem Anteil der Grenzdehnung (Stil-Bibel 80 %). */
    val creakRatio: Float = 0.8f,
    /** Lagergrenzen Wirtschaft. */
    val metalCap: Float = 1000f,
    val energyCap: Float = 400f,
    /** Projektil-Teilschritte pro Tick (Prototyp 2, semi-implizites Euler). */
    val projectileSubsteps: Int = 2,
    /** Kraftbereich beim Zielen. */
    val minPower: Float = 0.3f,
    val maxPower: Float = 1f,
    /** Rückerstattungsfaktor beim Abreißen (Balken: × TP-Anteil; Geräte: auf die Baukosten). */
    val deleteRefund: Float = 0.5f,
    /** Rückerstattungsfaktor bei "Zurück" (Prototyp: volle Kosten). */
    val undoRefund: Float = 1f,
    /** Länge des Zurück-Journals je Spieler (Prototyp 60). */
    val undoDepth: Int = 60,
    /** Zurück nur innerhalb so vieler Ticks nach dem Bau (Standard 10 s; 0 = unbegrenzt, wie im Prototyp). */
    val undoWindowTicks: Int = 600,
    val fire: FireConfig = FireConfig(),
    val repair: RepairConfig = RepairConfig(),
    val debris: DebrisConfig = DebrisConfig(),
    val door: DoorConfig = DoorConfig(),
    val combat: CombatConfig = CombatConfig(),
    val wind: WindConfig = WindConfig(),
    val turn: TurnConfig = TurnConfig(),
) {
    /** Länge eines Substeps in s. */
    val substepDt: Float get() = dt / substeps

    /** Ticks pro Sekunde (gerundet). */
    val ticksPerSecond: Int get() = (1f / dt + 0.5f).toInt()

    /** Sekunden → Ticks (gerundet). */
    fun secondsToTicks(seconds: Float): Int = (seconds / dt + 0.5f).toInt()

    companion object {
        val DEFAULT: SimConfig = SimConfig()
    }
}

/** Feuer (Prototyp `fireTick`). Raten pro Sekunde. */
@Serializable
data class FireConfig(
    /** Zunahme der Brandstärke pro s. */
    val growthPerSec: Float = 0.55f,
    /** Brennstoffverbrauch pro s. */
    val fuelPerSec: Float = 0.075f,
    /** TP-Verlust pro s = (hpLossBase + hpLossScale · fire) · maxHp. */
    val hpLossBase: Float = 0.03f,
    val hpLossScale: Float = 0.09f,
    /** Grund-Wahrscheinlichkeit pro s, einen Nachbarbalken (gemeinsamer Knoten) zu entzünden. */
    val spreadChancePerSec: Float = 0.13f,
    /** Ausbreitung erst ab dieser Brandstärke. */
    val spreadThreshold: Float = 0.5f,
    /** Windfaktor: `clamp(1 + windBias · wind · sign(dx), windBiasMin, windBiasMax)`. */
    val windBias: Float = 0.11f,
    val windBiasMin: Float = 0.25f,
    val windBiasMax: Float = 2.3f,
    /** Faktor, wenn der Nachbar höher liegt (Feuer steigt). */
    val upwardBias: Float = 1.35f,
    /** Brandstärke eines frisch entzündeten Balkens. */
    val igniteStart: Float = 0.05f,
)

/** Reparatur (Prototyp: maxHp/2 pro s, pausiert solange der Balken brennt). */
@Serializable
data class RepairConfig(
    /** Geheilter TP-Anteil von maxHp pro s. */
    val hpFractionPerSec: Float = 0.5f,
    /** Metallkosten = costFactor · Baukosten des Balkens · reparierter TP-Anteil. */
    val costFactor: Float = 0.5f,
)

/** Trümmer (Prototyp `debrisTick`). */
@Serializable
data class DebrisConfig(
    /** Unter der Oberfläche (tiefer als [belowGroundDepth]) länger als das → entfernen (6 s). */
    val belowGroundTicks: Int = 360,
    val belowGroundDepth: Float = 0.35f,
    /** Zufälliger Zerfall frühestens nach so vielen Trümmer-Ticks (14 s). */
    val decayAfterTicks: Int = 840,
    /** Zerfalls-Wahrscheinlichkeit pro s danach. */
    val decayChancePerSec: Float = 0.6f,
    /** Einschlag auf fremde Balken erst ab dieser Geschwindigkeit (m/s). */
    val impactMinSpeed: Float = 4f,
    /** Schaden = clamp((v − 3) · Masse · 0,01, min, max). */
    val impactDamageMin: Float = 4f,
    val impactDamageMax: Float = 90f,
    /** Sperrzeit eines getroffenen Balkens (0,25 s). */
    val hitCooldownTicks: Int = 15,
    /** Staub-Fx (`FxEvent.DebrisLanded`) nur, wenn der Knoten im Vortick schneller war (m/s, Prototyp 3). */
    val landedFxMinSpeed: Float = 3f,
    /** Zusätzlicher Trefferradius eines Trümmerknotens zur halben Balkendicke (m, Prototyp 0,2). */
    val impactRadius: Float = 0.2f,
    /** Harte Altersgrenze: Trümmer werden spätestens nach so vielen Ticks still entfernt (40 s). */
    val maxAgeTicks: Int = 2400,
)

/** Türen (Prototyp `doorFor`, `doorTick`). */
@Serializable
data class DoorConfig(
    /** Automatisch geöffnete Tür schließt nach 2,6 s. */
    val autoCloseTicks: Int = 156,
    /** Eigene Tür im Umkreis (m) des Rohr-Drehpunkts öffnet beim Schuss automatisch. */
    val searchRadius: Float = 4.2f,
)

/** Kampf (Prototyp `explode`, `rayHit`, `projTick`). */
@Serializable
data class CombatConfig(
    /** Obergrenze der Geschwindigkeitsänderung eines Knotens durch eine Explosion (m/s). */
    val explosionDvCap: Float = 16f,
    /** Obergrenze bei Direkttreffer (m/s, Prototyp 18). */
    val directDvCap: Float = 18f,
    /** Obergrenze Rückstoß (m/s, Prototyp 5). */
    val recoilDvCap: Float = 5f,
    /** Gerät-Trefferradius-Faktor für Strahlen/Projektile. */
    val deviceRayRadiusScale: Float = 0.85f,
    /** Gerät-Trefferradius-Faktor für Explosionen. */
    val deviceSplashRadiusScale: Float = 0.6f,
    /** So lange ignoriert ein Projektil den Balken seiner Waffe (0,25 s). */
    val sourceIgnoreTicks: Int = 15,
    // ---- WP4, additiv (Prototyp `explode`, `reactorDown`, `terrainHit`) ----
    /** Schadensfaktor für Balken hinter einer geschlossenen Tür (Sichtlinie Explosion → Balken schneidet die Tür). */
    val doorShieldFactor: Float = 0.3f,
    /** Abstand (m), ab dem die Sichtlinie eine geschlossene Tür "schneidet" (Prototyp: Abstand² < 0,04). */
    val doorShieldDistance: Float = 0.2f,
    /** Faktor auf den Explosionsschaden an Geräten (Prototyp 0,9). */
    val splashDeviceFactor: Float = 0.9f,
    /** Reaktor-Explosion (Prototyp `reactorDown`: Radius 6,5 m, 280 Schaden, Impuls 2600, entzündet Holz in 2 m). */
    val reactorBlastRadius: Float = 6.5f,
    val reactorBlastDamage: Float = 280f,
    val reactorBlastImpulse: Float = 2600f,
    val reactorBlastIgniteRadius: Float = 2f,
    /** Abtastschritt (m) des Strahl-Gelände-Tests, danach 6 Bisektionsschritte (Prototyp 0,25). */
    val terrainSampleStep: Float = 0.25f,
    /** Zellgröße (m) des Balken-Rasters (Broadphase) für Projektile und Strahlen. */
    val broadphaseCellSize: Float = 2f,
)

/** Wind: Startwert und Änderungen innerhalb `MapSpec.windMin..windMax` (Strom `RngStreams.WIND`). */
@Serializable
data class WindConfig(
    /** Abstand der Windänderungen in Ticks (0 = konstant). */
    val changeIntervalTicks: Int = 1800,
    /** Größte Änderung je Wechsel in m/s. */
    val maxChange: Float = 2f,
)

/**
 * Zugmodus (Hotseat, WP3). Die Zuglänge selbst kommt aus `MatchSetup.turnTicks` (App: 45 s = 2700 Ticks);
 * hier stehen die Phasen zwischen den Zügen.
 */
@Serializable
data class TurnConfig(
    /** Nachlaufzeit nach Zugende, in der die Simulation weiterläuft (Projektile landen, Einsturz): 6 s. */
    val resolveTicks: Int = 360,
    /** Übergabe-Phase vor dem nächsten Zug (mirrort den 3-2-1-Countdown der App): 3 s. */
    val handoverTicks: Int = 180,
) {
    companion object {
        /** Standard-Zuglänge Hotseat (Stil-Bibel/Prototyp): 45 s bei 60 Ticks/s. */
        const val DEFAULT_PLAY_TICKS: Int = 45 * 60
    }
}
