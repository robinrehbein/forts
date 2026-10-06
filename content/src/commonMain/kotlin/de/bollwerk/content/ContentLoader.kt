package de.bollwerk.content

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Lädt Content aus JSON-Texten (kein Datei-IO in commonMain; Texte liefert die Plattform, siehe
 * `ClasspathContent` auf der JVM/Android).
 */
object ContentLoader {
    /** JSON-Konfiguration für Content (unbekannte Felder sind ein Fehler, damit Tippfehler auffallen). */
    val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    /** Kanonische Kodierung für [ContentDb.fingerprint] (alle Felder inkl. Standardwerte, kompakt). */
    val canonicalJson: Json = Json {
        encodeDefaults = true
        prettyPrint = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * @param texts Dateiname → JSON-Text, jeweils ein [ContentPack]. Dateien werden nach Schlüssel
     *   sortiert zusammengeführt, damit die Index-Reihenfolge unabhängig von der Map-Implementierung ist.
     * @param version Content-Version (nur Anzeige; maßgeblich für Replays ist [ContentDb.fingerprint]).
     */
    fun fromJson(texts: Map<String, String>, version: String = "dev"): ContentDb {
        val p = mergePack(texts)
        return ContentDb(version, p.materials, p.devices, p.weapons, p.techs, p.maps, p.blueprints)
    }

    /**
     * Führt alle Dateien (nach Schlüssel sortiert) zu einem [ContentPack] zusammen, **ohne** Querverweise
     * aufzulösen. Grundlage für [ContentValidator], der auch kaputte Daten beschreiben können muss.
     */
    fun mergePack(texts: Map<String, String>): ContentPack {
        val mats = ArrayList<MaterialDef>()
        val devs = ArrayList<DeviceDef>()
        val weps = ArrayList<WeaponDef>()
        val techs = ArrayList<TechDef>()
        val maps = ArrayList<MapDef>()
        val blueprints = ArrayList<BlueprintDef>()
        for (key in texts.keys.sorted()) {
            val pack = try {
                json.decodeFromString(ContentPack.serializer(), texts.getValue(key))
            } catch (e: SerializationException) {
                throw ContentException("invalid content file '$key': ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw ContentException("invalid content file '$key': ${e.message}")
            }
            mats += pack.materials; devs += pack.devices; weps += pack.weapons
            techs += pack.techs; maps += pack.maps; blueprints += pack.blueprints
        }
        return ContentPack(mats, devs, weps, techs, maps, blueprints)
    }

    /** Parst `content/index.json`. */
    fun parseIndex(text: String): ContentIndex = json.decodeFromString(ContentIndex.serializer(), text)
}
