package de.bollwerk.engine.loop

import de.bollwerk.engine.GameInfo
import de.bollwerk.engine.command.Command
import de.bollwerk.engine.command.CommandJson
import de.bollwerk.engine.sim.MatchSetup
import de.bollwerk.engine.sim.SimConfig
import kotlinx.serialization.Serializable

/**
 * Aufzeichnung einer Partie: Setup (Seed, Karte, Spieler, Zugmodus, Startressourcen) + Sim-Konfiguration +
 * Content-Fingerabdruck + alle angenommenen Commands genügen, um die Partie bitgleich nachzuspielen.
 * [checkpoints] erlaubt Desync-Suche (Tick → StateHash).
 *
 * Commands enthalten Content-**Indizes**; deshalb müssen [contentHash] (`ContentDb.fingerprint`) und
 * [engineVersion] beim Abspielen und im Lockstep-Handshake übereinstimmen ([incompatibility]).
 */
@Serializable
data class Replay(
    val formatVersion: Int = FORMAT_VERSION,
    val engineVersion: Int = GameInfo.ENGINE_VERSION,
    /** Menschlich lesbare Content-Version (nur Anzeige). */
    val contentVersion: String,
    /** FNV-1a über die kanonische Content-Kodierung (maßgeblich). */
    val contentHash: Long,
    val setup: MatchSetup,
    val config: SimConfig = SimConfig.DEFAULT,
    val commands: List<Command> = emptyList(),
    /** Anzahl simulierter Ticks. */
    val ticks: Long = 0L,
    val checkpoints: List<HashCheckpoint> = emptyList(),
) {
    fun toJson(): String = CommandJson.encodeToString(serializer(), this)

    /** @return Grund, warum dieses Replay mit dem laufenden Build nicht abspielbar ist, oder null. */
    fun incompatibility(contentHash: Long, engineVersion: Int = GameInfo.ENGINE_VERSION): String? = when {
        formatVersion != FORMAT_VERSION -> "replay format $formatVersion != $FORMAT_VERSION"
        engineVersion != this.engineVersion -> "engine version ${this.engineVersion} != $engineVersion"
        contentHash != this.contentHash -> "content hash ${StateHash.hex(this.contentHash)} != ${StateHash.hex(contentHash)}"
        else -> null
    }

    companion object {
        const val FORMAT_VERSION: Int = 2

        fun fromJson(text: String): Replay = CommandJson.decodeFromString(serializer(), text)
    }
}

/** StateHash zu einem Tick. */
@Serializable
data class HashCheckpoint(val tick: Long, val hash: Long)
