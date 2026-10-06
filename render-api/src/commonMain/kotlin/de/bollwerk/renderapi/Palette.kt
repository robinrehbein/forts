package de.bollwerk.renderapi

/**
 * Farb-Tokens der Stil-Bibel §2 als ARGB-Ints (0xAARRGGBB, direkt für `android.graphics.Paint`
 * und Compose `Color(int)` nutzbar). Einzige Quelle für Farben im Spiel.
 */
object Palette {
    private fun rgb(v: Int): Int = v or (0xFF shl 24)
    private fun argb(alpha: Float, v: Int): Int = v or (((alpha * 255f + 0.5f).toInt() and 0xFF) shl 24)

    // ---- UI / Material-Grundtöne ----
    val STEEL: Int = rgb(0x2b3440)
    val STEEL_HI: Int = rgb(0x8a97a8)
    val DARK: Int = rgb(0x141a21)
    val RUST: Int = rgb(0xd2622a)
    val HAZARD: Int = rgb(0xe8b73a)
    val WOOD: Int = rgb(0xa3692f)
    val WOOD_LIGHT: Int = rgb(0xc48a4a)
    val WOOD_DARK: Int = rgb(0x6e4520)
    val TEXT: Int = rgb(0xe9eef4)
    val TEAM_BLUE: Int = rgb(0x3a8dde)
    val TEAM_RED: Int = rgb(0xd9433b)
    val ENERGY: Int = rgb(0x4fd1ff)
    val OK: Int = rgb(0x6bd68a)

    // ---- Feuer (innen → außen) ----
    val FIRE_INNER: Int = rgb(0xffb03a)
    val FIRE_MID: Int = rgb(0xff6a1f)
    val FIRE_OUTER: Int = rgb(0xc2361a)

    // ---- Rauch (alt → jung) ----
    val SMOKE_OLD: Int = argb(0.9f, 0x3a3f47)
    val SMOKE_YOUNG: Int = argb(0.2f, 0x6b7078)

    // ---- Himmel Abenddämmerung (oben → Horizont) ----
    val SKY_0: Int = rgb(0x1b1f3a)
    val SKY_1: Int = rgb(0x3b2f5c)
    val SKY_2: Int = rgb(0x7a4a6e)
    val SKY_3: Int = rgb(0xd07a5a)
    val SKY_4: Int = rgb(0xf2b26b)
    val SKY_GRADIENT: IntArray get() = intArrayOf(SKY_0, SKY_1, SKY_2, SKY_3, SKY_4)
    val SUN: Int = rgb(0xffe7b0)

    // ---- Berge (fern → nah, Stil-Bibel: je weiter weg desto heller/violetter) ----
    val MOUNTAIN_FAR: Int = rgb(0x574467)
    val MOUNTAIN_MID: Int = rgb(0x3d3156)
    val MOUNTAIN_NEAR: Int = rgb(0x2a2440)

    // ---- Materialfarben aus dem Prototyp (matCol) ----
    val ROPE: Int = rgb(0xb99a63)
    val METAL: Int = rgb(0x7e8c9c)
    val ARMOR: Int = rgb(0x3c444f)
    val DOOR: Int = rgb(0x4a525d)

    /** Teamfarbe für Spieler-ID (0 = blau, 1 = rot, sonst Stahl). */
    fun team(playerId: Int): Int = when (playerId) {
        0 -> TEAM_BLUE
        1 -> TEAM_RED
        else -> STEEL_HI
    }

    /** Ersetzt den Alphakanal von [color] durch [alpha] (0..1). */
    fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()) shl 24)
}
