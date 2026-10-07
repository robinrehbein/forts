package de.bollwerk.engine

/** Projektweite Konstanten. Der Arbeitstitel steht nur hier (und in `strings.xml` der App). */
object GameInfo {
    /** Arbeitstitel des Spiels. */
    const val TITLE: String = "Bollwerk"

    /** Wordmark in Versalien (Titelbildschirm). */
    const val WORDMARK: String = "BOLLWERK"

    /**
     * Version des Sim-Verhaltens. Bei jeder Änderung, die bei gleichem Seed/gleichen Commands einen anderen
     * StateHash ergibt (neues System, geänderte Formel), erhöhen; steht in jedem Replay.
     */
    const val ENGINE_VERSION: Int = 2
}
