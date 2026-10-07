package de.bollwerk.render.android.audio

/**
 * Alle prozeduralen Sounds (Stil-Bibel Abschnitt 8 plus UI/Stinger). [durationSec] ist die exakte Länge des
 * synthetisierten Puffers (inkl. Hall-Nachlauf); [maxVoices] begrenzt gleichzeitige Instanzen, [minGapMs] den
 * Mindestabstand zwischen zwei Starts (Prototyp `gap`). Die Lautstärken zueinander sind die absoluten Prototyp-Gains
 * (kein Normalisieren je Sound), siehe [SfxSynth.SHARED_GAIN].
 */
enum class SfxId(
    val durationSec: Double,
    val maxVoices: Int,
    val minGapMs: Int,
    val loop: Boolean = false,
    /** SoundPool-Priorität (höher = wird bei vollem Pool zuletzt verdrängt). */
    val priority: Int = 1,
) {
    MORTAR(0.35, 3, 0),
    CANNON(1.60, 3, 0),
    MG(0.06, 4, 40),
    EXPLOSION(2.30, 4, 60),
    EXPLOSION_SMALL(2.00, 4, 60),
    WOOD_BREAK(0.15, 4, 50),
    METAL_BREAK(0.80, 3, 70),
    PLACE_WOOD(0.12, 3, 0),
    PLACE_METAL(0.30, 3, 0),
    THUD(0.20, 3, 90),
    CLICK(0.06, 2, 0),
    RELOAD_PING(0.22, 2, 150),
    VICTORY(1.60, 1, 0, priority = 2),
    DEFEAT(1.80, 1, 0, priority = 2),
    FIRE_LOOP(2.00, 1, 0, loop = true, priority = 3),
    /** Kurzer Knackser, vom [SfxPlayer] in Prototyp-Rate zur Feuerschleife gespielt. */
    CRACKLE(0.05, 6, 0, priority = 0),
}
