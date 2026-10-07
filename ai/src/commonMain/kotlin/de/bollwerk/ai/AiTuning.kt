package de.bollwerk.ai

/**
 * Entscheidungsqualität je [Difficulty] (zusätzlich zu Zielfehler und Denk-Intervall, die am Enum selbst stehen).
 *
 * @property maxCommandsPerThink höchstens so viele Commands je Denkschritt (Vertrag: 3).
 * @property candidateTargets so viele gegnerische Geräte werden je Waffe genau (Bahn + Deckung) bewertet.
 * @property targetNoise multiplikatives Rauschen auf die Zielbewertung (0 = immer das beste Ziel).
 * @property powerSteps Kraftstufen, die der ballistische Löser durchprobiert (mehr = bessere Bahnwahl).
 * @property tryBothArcs auch den nicht bevorzugten Bogen (flach/steil) prüfen, wenn der bevorzugte verdeckt ist.
 * @property repairThreshold Balken unter diesem TP-Anteil werden repariert.
 * @property useDoors Türen bei anfliegenden Geschossen schließen und für verdeckte Schüsse öffnen.
 * @property rebuild zerstörte Schlüsselbalken und Geräte der Bauvorlage wieder aufbauen.
 * @property extinguish brennende eigene Balken löschen.
 * @property minExposure Mindest-Deckungswert (0..1), ab dem eine Waffe überhaupt feuert.
 * @property reactionChance Wahrscheinlichkeit, mit der eine erkannte Reaktion (Reparatur/Tür/Löschen) im Denkschritt
 *   tatsächlich erwogen wird (Leicht reagiert träger).
 * @property weaponEvalsPerThink höchstens so viele feuerbereite Waffen werden je Denkschritt ballistisch bewertet
 *   (reihum; Rechenzeit auf dem Sim-Thread). Die erste mit lohnendem Schuss beendet die Suche.
 */
class AiTuning(
    val maxCommandsPerThink: Int,
    val candidateTargets: Int,
    val targetNoise: Float,
    val powerSteps: FloatArray,
    val tryBothArcs: Boolean,
    val repairThreshold: Float,
    val useDoors: Boolean,
    val rebuild: Boolean,
    val extinguish: Boolean,
    val minExposure: Float,
    val reactionChance: Float,
    val weaponEvalsPerThink: Int = 4,
) {
    companion object {
        fun of(d: Difficulty): AiTuning = when (d) {
            Difficulty.EASY -> AiTuning(
                maxCommandsPerThink = 3, candidateTargets = 2, targetNoise = 0.6f, powerSteps = floatArrayOf(1f, 0.8f),
                tryBothArcs = false, repairThreshold = 0.35f, useDoors = false, rebuild = false, extinguish = false,
                minExposure = 0.25f, reactionChance = 0.5f, weaponEvalsPerThink = 2,
            )
            Difficulty.NORMAL -> AiTuning(
                maxCommandsPerThink = 3, candidateTargets = 3, targetNoise = 0.2f, powerSteps = floatArrayOf(1f, 0.85f, 0.7f),
                tryBothArcs = true, repairThreshold = 0.5f, useDoors = true, rebuild = true, extinguish = true,
                minExposure = 0.25f, reactionChance = 0.85f,
            )
            Difficulty.HARD -> AiTuning(
                maxCommandsPerThink = 3, candidateTargets = 5, targetNoise = 0f, powerSteps = floatArrayOf(1f, 0.9f, 0.8f, 0.7f),
                tryBothArcs = true, repairThreshold = 0.65f, useDoors = true, rebuild = true, extinguish = true,
                minExposure = 0.25f, reactionChance = 1f,
            )
        }
    }
}
