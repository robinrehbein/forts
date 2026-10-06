package de.bollwerk.engine.sim

/**
 * Aufgelöste, index-basierte Spielwerte für die Simulation. `:engine` hängt nicht von `:content` ab;
 * die Brücke `de.bollwerk.setup.SimTablesFactory` baut diese Tabellen aus `ContentDb`, mit
 * **identischer Index-Reihenfolge**. Damit sind `material`, `type` und `kind` in den Pools zugleich
 * Indizes in `ContentDb` und in diese Tabellen.
 *
 * Zeiten sind bereits in Ticks umgerechnet, Raten in Einheiten pro Sekunde, Winkel in Bogenmaß.
 * Die Tabellen gehören zum [GameState] (`state.tables`) und sind über `GameView.tables` für Regeln,
 * Werkzeuge, KI und Renderer lesbar.
 */
data class SimTables(
    val materials: List<MaterialProps>,
    val devices: List<DeviceProps>,
    val weapons: List<WeaponProps>,
    val techs: List<TechProps>,
    /** Bauvorlagen (Startfestungen, KI-Bauten). */
    val blueprints: List<BlueprintProps> = emptyList(),
) {
    /** Bauvorlage per Schlüssel oder null. */
    fun blueprint(key: String): BlueprintProps? {
        for (i in blueprints.indices) if (blueprints[i].key == key) return blueprints[i]
        return null
    }

    companion object {
        /** Leere Tabellen – nur für Tests ohne Content; muss explizit übergeben werden. */
        val EMPTY: SimTables = SimTables(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** Material-Kennwerte (Prototyp `MAT`). */
data class MaterialProps(
    val key: String,
    val costPerMeter: Float,
    val hp: Float,
    /** Masse je m in kg. */
    val density: Float,
    /** Dehnsteifigkeit EA in N; XPBD-Compliance = restLen / EA. */
    val stiffness: Float,
    /** Grenzdehnung Zug. */
    val tensionLimit: Float,
    /** Grenzdehnung Druck (`Float.POSITIVE_INFINITY` bei Seil). */
    val compressionLimit: Float,
    val damageFactor: Float,
    /** Darstellungs- und Trefferdicke in m. */
    val thickness: Float,
    val flammable: Boolean,
    /** Axiale Dämpfung (muss Energie immer reduzieren). */
    val damping: Float,
    /** Nur Zug (Seil). */
    val tensionOnly: Boolean,
    /** Ruhelängenfaktor beim Bau (Seil 1,04 = Durchhang). */
    val restLengthFactor: Float,
    /** Tür: per `ToggleDoor` öffnen/schließen, offen passieren Projektile. */
    val isDoor: Boolean,
    /** Benötigte Tech oder −1. */
    val requiredTech: Int,
)

/** Rolle eines Geräts für Regeln (kleines Enum statt Content-Nummern). */
enum class DeviceRole { REACTOR, MINE, TURBINE, TECH, WEAPON, STORAGE, OTHER }

/** Erlaubte Montageseite. */
enum class MountRule {
    /** Beliebige Balkenseite. */
    ANY,
    /** Nur nach oben zeigende Normale (Turbine; Prototyp: `ny > −0,5` ungültig). */
    TOP_ONLY,
}

/** Geräte-Kennwerte (Prototyp `DEV`). Jede Waffe ist ein Gerät mit [role] = WEAPON und [weapon] ≥ 0. */
data class DeviceProps(
    val key: String,
    val role: DeviceRole,
    val costMetal: Float,
    val costEnergy: Float,
    val hp: Float,
    val mass: Float,
    val hitRadius: Float,
    /** Abstand Montagepunkt → Trefferzentrum in m. */
    val mountOffset: Float,
    /** Rohr-Drehpunkt über Montagepunkt in m (0 = kein Rohr). */
    val pivotOffset: Float,
    /** Rohrlänge Drehpunkt → Mündung in m (Prototyp `BARREL`: Mörser 1,05, Kanone 1,95, MG 1,2). */
    val barrelLength: Float,
    val buildTicks: Int,
    val metalPerSecond: Float,
    val energyPerSecond: Float,
    /** Waffen-Index oder −1. */
    val weapon: Int,
    /** Benötigte Tech oder −1. */
    val requiredTech: Int,
    /** Tech, die dieses Gebäude bei Fertigstellung (buildTicks = 0) gewährt, oder −1. */
    val grantsTech: Int,
    /** Höchstens eines pro Spieler (Reaktor). */
    val unique: Boolean,
    /** Nur über eigenem Erz platzierbar (Mine; Prototyp: Erz im Umkreis von [oreRadius] m). */
    val requiresOre: Boolean,
    val oreRadius: Float,
    val mountRule: MountRule,
    /** Mindestabstand zu anderen Geräten in m (Prototyp 1,5 m, sonst `OCCUPIED`). */
    val minSpacing: Float,
)

/** Wirkungsart einer Waffe. */
enum class WeaponMode {
    /** Projektil mit Ballistik (Mörser, Kanone, Brandrakete). */
    BALLISTIC,
    /** Sofortiger Strahltreffer (MG, Scharfschütze), Reichweite [WeaponProps.maxRange]. */
    HITSCAN,
    /** Dauerstrahl über [WeaponProps.beamTicks] (Laser); [WeaponProps.damage] ist dann Schaden pro Sekunde. */
    BEAM,
}

/** Waffen-Kennwerte. Impulse sind absolute Werte wie im Prototyp (dv = J · invMass, gedeckelt durch `CombatConfig`). */
data class WeaponProps(
    val key: String,
    val mode: WeaponMode,
    /** Direktschaden pro Treffer (bei [WeaponMode.BEAM]: pro Sekunde). */
    val damage: Float,
    /** Explosionsradius in m (0 = keine Explosion). */
    val splashRadius: Float,
    /** Explosionsschaden im Zentrum (Mörser 120, Kanone 30 zusätzlich zu 90 direkt). */
    val splashDamage: Float,
    val minRange: Float,
    val maxRange: Float,
    val reloadTicks: Int,
    val shotMetal: Float,
    val shotEnergy: Float,
    /** Mündungsgeschwindigkeit bei Kraft 1 in m/s. */
    val muzzleSpeed: Float,
    val shotsPerBurst: Int,
    val burstIntervalTicks: Int,
    /** Streuung ± in Bogenmaß (MG ±1,2°). */
    val spreadRad: Float,
    /** Impuls auf die Knoten des direkt getroffenen Balkens (Prototyp Kanone 2200). */
    val directImpulse: Float,
    /** Explosionsimpuls (Prototyp Mörser 1100, Kanone 650). */
    val explosionImpulse: Float,
    /** Rückstoß auf die Knoten des Trägerbalkens (Prototyp Mörser 260, Kanone 520). */
    val recoilImpulse: Float,
    /** Entzündet Holz in diesem Radius (m). */
    val igniteRadius: Float,
    /** Anzahl durchschlagbarer Balken. */
    val piercesBeams: Int,
    /** Schadensfaktor gegen Geräte. */
    val deviceDamageFactor: Float,
    /** Projektilradius für Swept-Tests in m. */
    val projectileRadius: Float,
    val ttlTicks: Int,
    /** Laser: Strahldauer in Ticks. */
    val beamTicks: Int,
    /** Start-Zielwinkel für Spieler 0 (nach rechts); für Spieler auf der rechten Seite gespiegelt (π − a). */
    val defaultAimRad: Float,
    val defaultPower: Float,
)

/**
 * Tech (Techbaum-Knoten). Techs werden **nur** über Gebäude gewährt: Ein Spieler besitzt Tech T genau dann,
 * wenn er ein lebendes, fertig gebautes Gerät mit `grantsTech == T` hat (jeden Tick neu berechnet, siehe
 * `PlayerState.techUnlocked`). Wird das Gebäude zerstört, ist die Tech wieder weg. Kosten und Bauzeit
 * stehen am Gebäude ([DeviceProps]), nicht hier.
 */
data class TechProps(
    val key: String,
    /** Vorausgesetzte Tech-Indizes (Techbaum-Anzeige; das Bauen prüft `DeviceProps.requiredTech`). */
    val requires: List<Int>,
)

/** Knoten einer Bauvorlage; Koordinaten relativ zum Ursprung (x nach rechts, y nach unten, Boden = 0). */
data class BlueprintNode(val x: Float, val y: Float, val anchored: Boolean)

/** Balken einer Bauvorlage: Knoten-Indizes [a], [b] in [BlueprintProps.nodes]. */
data class BlueprintBeam(val a: Int, val b: Int, val material: Int)

/** Gerät einer Bauvorlage auf Balken [beam] (Index in [BlueprintProps.beams]). */
data class BlueprintDevice(val type: Int, val beam: Int, val t: Float, val sideNegative: Boolean)

/** Bauvorlage (Startfestung, KI-Bauplan). */
data class BlueprintProps(
    val key: String,
    val nodes: List<BlueprintNode>,
    val beams: List<BlueprintBeam>,
    val devices: List<BlueprintDevice>,
    val tags: List<String> = emptyList(),
)
