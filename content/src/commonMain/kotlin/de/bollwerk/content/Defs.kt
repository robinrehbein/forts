package de.bollwerk.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Content-Definitionen (JSON unter content/src/commonMain/resources/content/).
 * Zahlen stammen aus docs/design/STILBIBEL.md; physikalische Kennwerte aus dem Prototyp
 * (physik-spielplatz.html, MAT/DEV). IDs sind stabile, kleingeschriebene Schlüssel; zur Laufzeit
 * werden sie in ContentDb auf Indizes aufgelöst (Reihenfolge = Ladereihenfolge).
 * Anzeigenamen kommen über `nameKey` aus den App-Ressourcen (DE/EN).
 */

/** Balkenmaterial. */
@Serializable
data class MaterialDef(
    val id: String,
    val nameKey: String,
    /** ⚙ pro Meter. */
    val costPerM: Float,
    val hp: Float,
    /** Masse je m in kg (Prototyp `dens`). */
    val density: Float,
    /** Dehnsteifigkeit EA in N. */
    val stiffness: Float,
    /** Grenzdehnung Zug. */
    val tensionLimit: Float,
    /** Grenzdehnung Druck; ignoriert bei [tensionOnly]. */
    val compressionLimit: Float = 0f,
    val damageFactor: Float = 1f,
    /** Dicke in m (Stil-Bibel §4). */
    val thickness: Float,
    val flammable: Boolean = false,
    /** Axiale Dämpfung. */
    val damping: Float = 0.1f,
    /** Seil: nur Zug. */
    val tensionOnly: Boolean = false,
    /** Ruhelängenfaktor beim Bau (Seil 1,04). */
    val restLengthFactor: Float = 1f,
    /** Tür: öffnen/schließen möglich. */
    val isDoor: Boolean = false,
    /** Benötigte Tech-ID oder null. */
    val requiresTech: String? = null,
)

/** Rolle eines Geräts (spiegelt `DeviceRole` der Engine, als String im JSON). */
@Serializable
enum class DeviceCategory {
    @SerialName("reactor") REACTOR,
    @SerialName("mine") MINE,
    @SerialName("turbine") TURBINE,
    @SerialName("tech") TECH,
    @SerialName("weapon") WEAPON,
    @SerialName("storage") STORAGE,
    @SerialName("other") OTHER,
}

/** Erlaubte Montageseite (spiegelt `MountRule` der Engine). */
@Serializable
enum class MountRuleDef {
    @SerialName("any") ANY,
    /** Nur oben (Turbine). */
    @SerialName("top") TOP_ONLY,
}

/**
 * Gerät (Reaktor, Mine, Turbine, Techgebäude, Waffenträger). **Jede Waffe ist genau ein Gerät** mit
 * `category = weapon` und [weapon] = Waffen-ID; bei 7 Nicht-Waffen-Geräten und 6 Waffen enthält
 * `devices.json` also 13 Einträge.
 */
@Serializable
data class DeviceDef(
    val id: String,
    val nameKey: String,
    val category: DeviceCategory,
    val costMetal: Float = 0f,
    val costEnergy: Float = 0f,
    val hp: Float,
    val mass: Float,
    /** Trefferradius in m. */
    val hitRadius: Float,
    /** Montagepunkt → Trefferzentrum in m. */
    val mountOffset: Float,
    /** Rohr-Drehpunkt über Montagepunkt in m (0 = kein Rohr). */
    val pivotOffset: Float = 0f,
    /** Rohrlänge Drehpunkt → Mündung in m (Prototyp `BARREL`). */
    val barrelLength: Float = 0f,
    val buildSeconds: Float = 0f,
    val metalPerSec: Float = 0f,
    val energyPerSec: Float = 0f,
    /** Waffen-ID, wenn das Gerät eine Waffe ist. */
    val weapon: String? = null,
    /** Benötigte Tech-ID oder null. */
    val requiresTech: String? = null,
    /** Tech, die dieses Gebäude bei Fertigstellung gewährt (solange es steht). */
    val grantsTech: String? = null,
    /** Höchstens eines pro Spieler (Reaktor). */
    val unique: Boolean = false,
    /** Nur über eigenem Erz (Mine). */
    val requiresOre: Boolean = false,
    /** Erz-Suchradius in m (Prototyp 2,4). */
    val oreRadius: Float = 2.4f,
    val mountRule: MountRuleDef = MountRuleDef.ANY,
    /** Mindestabstand zu anderen Geräten in m (Prototyp 1,5). */
    val minSpacing: Float = 1.5f,
)

/** Ballistik-/Projektiltyp. */
@Serializable
enum class ProjectileKind {
    @SerialName("shell") SHELL,
    @SerialName("ball") BALL,
    @SerialName("bullet") BULLET,
    @SerialName("rocket") ROCKET,
    @SerialName("beam") BEAM,
}

/** Wirkungsart (spiegelt `WeaponMode` der Engine). */
@Serializable
enum class WeaponModeDef {
    @SerialName("ballistic") BALLISTIC,
    @SerialName("hitscan") HITSCAN,
    @SerialName("beam") BEAM,
}

/**
 * Waffenwerte (Stil-Bibel §1 Waffen-Tabelle; Impulse absolut wie im Prototyp, dv = J · invMass).
 * Bei `mode = beam` ist [damage] Schaden pro Sekunde über [beamSeconds].
 */
@Serializable
data class WeaponDef(
    val id: String,
    val nameKey: String,
    /** Darstellung des Geschosses. */
    val projectile: ProjectileKind,
    val mode: WeaponModeDef = WeaponModeDef.BALLISTIC,
    /** Direktschaden pro Treffer. */
    val damage: Float = 0f,
    val splashRadius: Float = 0f,
    /** Explosionsschaden im Zentrum (Mörser 120, Kanone 30). */
    val splashDamage: Float = 0f,
    val minRange: Float = 0f,
    /**
     * Reichweite in m. **Hitscan/Strahl:** harte Grenze (Strahl endet dort). **Ballistisch:** nur ein
     * Anzeige-/KI-Wert (Reichweitenring, größte sinnvolle Zielentfernung), die Simulation erzwingt ihn nicht;
     * das Geschoss fliegt bis zum Treffer, bis [lifetimeSeconds] oder bis zu den Kill-Grenzen. Der Validator
     * verlangt, dass er die physikalische Reichweite `v²/(g·gravityScale)` nicht übersteigt.
     */
    val maxRange: Float,
    val reloadSeconds: Float,
    val shotMetal: Float = 0f,
    val shotEnergy: Float = 0f,
    /** Mündungsgeschwindigkeit bei 100 % Kraft in m/s. */
    val muzzleSpeed: Float,
    val shotsPerBurst: Int = 1,
    val burstIntervalSeconds: Float = 0f,
    /** Streuung ± in Grad (MG 1,2). */
    val spreadDeg: Float = 0f,
    /** Impuls auf den direkt getroffenen Balken (Kanone 2200). */
    val directImpulse: Float = 0f,
    /** Explosionsimpuls (Mörser 1100, Kanone 650). */
    val explosionImpulse: Float = 0f,
    /** Rückstoß auf den Trägerbalken (Mörser 260, Kanone 520). */
    val recoilImpulse: Float = 0f,
    /** Entzündet Holz in diesem Radius (m). */
    val igniteRadius: Float = 0f,
    /** Anzahl durchschlagener Balken. */
    val piercesBeams: Int = 0,
    /** Schadensfaktor gegen Geräte. */
    val deviceDamageFactor: Float = 1f,
    val projectileRadius: Float = 0.1f,
    /**
     * Maximale Flugzeit in s (ballistisch > 0; muss für den steilsten Schuss bei voller Kraft samt Fall in
     * tiefere Karten reichen, sonst verschwinden Geschosse mitten im Flug – der Validator prüft das).
     */
    val lifetimeSeconds: Float = 8f,
    /** Laser: Dauer des Strahls in s. */
    val beamSeconds: Float = 0f,
    /** Start-Zielwinkel in Grad für den linken Spieler (Prototyp `buildFort`: MG 0, Kanone 13, Mörser 52). */
    val defaultAimDeg: Float = 50f,
    val defaultPower: Float = 0.75f,
    /** Faktor auf die Erdbeschleunigung für das Geschoss (Brandrakete 0,6; Hitscan/Strahl 0 = ignoriert). */
    val gravityScale: Float = 1f,
    /** Kleinster Elevationswinkel in Grad, von der Waagerechten zur Feindseite (positiv = nach oben). */
    val minAimDeg: Float = -90f,
    /** Größter Elevationswinkel in Grad. */
    val maxAimDeg: Float = 90f,
)

/**
 * Tech (Techbaum-Knoten). Techs werden nur durch Gebäude gewährt (`DeviceDef.grantsTech`), solange das
 * fertige Gebäude steht; Kosten und Bauzeit stehen am Gebäude. Ein Gebäude, das Tech T gewährt, muss als
 * `requiresTech` eine der Techs aus `T.requires` verlangen (Loader prüft das).
 */
@Serializable
data class TechDef(
    val id: String,
    val nameKey: String,
    /** Vorausgesetzte Tech-IDs. */
    val requires: List<String> = emptyList(),
    /** IDs von Materialien/Geräten/Waffen, die freigeschaltet werden (nur zur Anzeige im Techbaum). */
    val unlocks: List<String> = emptyList(),
)

/** Knoten einer Bauvorlage relativ zum Ursprung (Boden = 0, y nach unten). */
@Serializable
data class BpNode(val x: Float, val y: Float, val anchored: Boolean = false)

/** Balken einer Bauvorlage zwischen Knoten-Indizes [a] und [b]. */
@Serializable
data class BpBeam(val a: Int, val b: Int, val material: String)

/** Gerät einer Bauvorlage auf Balken-Index [beam]. */
@Serializable
data class BpDevice(val type: String, val beam: Int, val t: Float = 0.5f, val sideNegative: Boolean = false)

/**
 * Bauschritt eines KI-Plans: [phase] (siehe [ContentValidator.PHASES], in dieser Reihenfolge) mit Indizes in
 * `beams`/`devices` der Bauvorlage. Erst Balken, dann Geräte, jeweils in Listenreihenfolge.
 */
@Serializable
data class BpStep(val phase: String, val beams: List<Int> = emptyList(), val devices: List<Int> = emptyList())

/**
 * Bauvorlage: Startfestung (Tag `start`) oder KI-Bauplan (Tags `ai` und `easy`/`normal`/`hard`).
 * [steps] ordnen bei KI-Plänen die Balken/Geräte zu einer Bau-Reihenfolge (leer bei Startfestungen).
 */
@Serializable
data class BlueprintDef(
    val id: String,
    val nameKey: String,
    val nodes: List<BpNode>,
    val beams: List<BpBeam>,
    val devices: List<BpDevice> = emptyList(),
    val tags: List<String> = emptyList(),
    val steps: List<BpStep> = emptyList(),
)

/** Punkt in Welt-Metern (y nach unten). */
@Serializable
data class PointDef(val x: Float, val y: Float)

/** Plateau (ebene Baufläche) von [x0] bis [x1] auf Höhe [y]. */
@Serializable
data class PlateauDef(val x0: Float, val x1: Float, val y: Float)

/** Erzvorkommen für Minen. */
@Serializable
data class OreSpotDef(val x: Float, val owner: Int)

/** Vorgegebener Fundamentknoten (verankert). */
@Serializable
data class FoundationDef(val owner: Int, val x: Float, val y: Float)

/** Baubereich eines Spielers (x-Intervall). */
@Serializable
data class BuildZoneDef(val owner: Int, val x0: Float, val x1: Float)

/** Startfestung: Bauvorlage [blueprint] am Ursprung ([originX], `baseY[owner]`), optional gespiegelt. */
@Serializable
data class StartFortDef(val owner: Int, val blueprint: String, val originX: Float, val mirror: Boolean = false)

/** Kill-Grenzen (Projektile/Trümmer werden außerhalb entfernt). */
@Serializable
data class BoundsDef(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

/** Karte. */
@Serializable
data class MapDef(
    val id: String,
    val nameKey: String,
    val width: Float,
    val height: Float,
    /** Geländeoberfläche als Polygonzug, x aufsteigend. */
    val terrain: List<PointDef>,
    val plateaus: List<PlateauDef> = emptyList(),
    val ores: List<OreSpotDef> = emptyList(),
    val foundations: List<FoundationDef> = emptyList(),
    val buildZones: List<BuildZoneDef> = emptyList(),
    val startForts: List<StartFortDef> = emptyList(),
    /** Bodenhöhe je Spieler (Startfestung, Turbinen-Höhenfaktor); leer = Gelände in der Mitte der Bauzone. */
    val baseY: List<Float> = emptyList(),
    /** null = Prototyp-Standard (x −40 … Breite+40, y −120 … Höhe+20). */
    val bounds: BoundsDef? = null,
    val windMin: Float = -6f,
    val windMax: Float = 6f,
    val playerCount: Int = 2,
)

/**
 * Inhalt einer JSON-Datei. Jede Datei darf beliebige Listen enthalten; der Loader führt alle
 * Dateien in sortierter Schlüssel-Reihenfolge zusammen.
 */
@Serializable
data class ContentPack(
    val materials: List<MaterialDef> = emptyList(),
    val devices: List<DeviceDef> = emptyList(),
    val weapons: List<WeaponDef> = emptyList(),
    val techs: List<TechDef> = emptyList(),
    val maps: List<MapDef> = emptyList(),
    val blueprints: List<BlueprintDef> = emptyList(),
)

/** `content/index.json`: Version und Dateiliste (Classpath-Verzeichnisse sind nicht auflistbar). */
@Serializable
data class ContentIndex(
    val version: String,
    val files: List<String>,
)
