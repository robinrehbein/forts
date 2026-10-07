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

    // ---- Konturen, Overlay und UI-Flächen im Spiel (Szenen-Renderer) ----
    val WHITE: Int = rgb(0xffffff)
    val BLACK: Int = rgb(0x000000)
    /** Panel-Fläche der Chips (dark, 94 %). */
    val PANEL: Int = argb(0.94f, 0x141a21)
    val INVALID: Int = rgb(0xe5534b)
    val INVALID_TEXT: Int = rgb(0xffb0a8)
    val CRITICAL: Int = rgb(0xeb3c30)
    /** Dunkle Tinte für Scrims, Schatten und Schlitze. */
    val INK: Int = rgb(0x0a0c10)
    val OUTLINE: Int = rgb(0x0d1116)
    val OUTLINE_BEAM: Int = rgb(0x0f1318)
    val SOOT: Int = rgb(0x0a0806)
    val CHAR: Int = rgb(0x1e0c02)

    // ---- Stahl-Rampe (dunkel → hell), Geräte und Beschläge ----
    val STEEL_DEEP: Int = rgb(0x1b2129)
    val STEEL_DARK: Int = rgb(0x232b35)
    val STEEL_SHADE: Int = rgb(0x3a4552)
    val STEEL_MID: Int = rgb(0x4a5564)
    val STEEL_BODY: Int = rgb(0x5d6876)
    val STEEL_LIGHT: Int = rgb(0x6c7888)
    val BOLT: Int = rgb(0x9aa6b4)
    val STEEL_PALE: Int = rgb(0xc3ccd6)
    val STEEL_WHITE: Int = rgb(0xe4e9ee)
    val RUST_DARK: Int = rgb(0xa8481c)
    val RUST_LIGHT: Int = rgb(0xee7a3c)
    val BRASS: Int = rgb(0xc9a24a)
    val OLIVE: Int = rgb(0x3d4a2c)

    // ---- Holz- und Seiltöne ----
    val WOOD_WARM: Int = rgb(0x8a5a2c)
    val WOOD_FRAME: Int = rgb(0x7a5226)
    val WOOD_DEEP: Int = rgb(0x4a3320)
    val WOOD_BLACK: Int = rgb(0x2b2018)
    val ROPE_DARK: Int = rgb(0x462c10)
    val ROPE_OUTLINE: Int = rgb(0x1a1208)

    // ---- Licht, Feuer, Funken ----
    val FIRE_ORANGE: Int = rgb(0xff8a2a)
    val SPARK: Int = rgb(0xffd98a)
    val LAMP: Int = rgb(0xffd36a)
    val LAMP_FRAME: Int = rgb(0x3a2a10)
    val CREAM: Int = rgb(0xfff0c8)
    val GLOW_PEACH: Int = rgb(0xffc496)
    val SUN_CORE: Int = rgb(0xffd38f)

    // ---- Energie (Reaktorkern, Laser, Erz) ----
    val ENERGY_HI: Int = rgb(0xe6fcff)
    val ENERGY_LIGHT: Int = rgb(0x8eeaff)
    val ENERGY_MID: Int = rgb(0x1482aa)
    val ENERGY_DEEP: Int = rgb(0x0a3c5a)
    val CORE_BG: Int = rgb(0x06141b)
    val GLASS: Int = rgb(0xc8f0ff)
    val ORE_DARK: Int = rgb(0x0d3a4a)
    val ORE_MID: Int = rgb(0x1f8fb0)

    // ---- Rauch und Staub ----
    val SMOKE_HI: Int = rgb(0x5a6068)
    val DUST_DARK: Int = rgb(0x96766a)
    val DUST_LIGHT: Int = rgb(0xb08c6e)

    // ---- Gelände ----
    val DIRT: Int = rgb(0x5a3c2c)
    val DIRT_DARK: Int = rgb(0x34201a)
    val EARTH: Int = rgb(0x5c3b2e)
    val EARTH_DEEP: Int = rgb(0x3a2430)
    val GROUND_DARK: Int = rgb(0x1e1418)
    val GRASS_DARK: Int = rgb(0x4b6a2e)
    val GRASS_MID: Int = rgb(0x5f8236)
    val GRASS_LIGHT: Int = rgb(0x7f9e48)
    val GRASS_TIP: Int = rgb(0x86a84c)
    val CONCRETE_HI: Int = rgb(0x9aa0a6)
    val CONCRETE_MID: Int = rgb(0x7b8187)
    val CONCRETE_LO: Int = rgb(0x5a6066)
    /** Erdschichten von der Oberfläche nach unten (Stil-Bibel §4, Gelände). */
    val STRATA: IntArray get() = intArrayOf(
        rgb(0x5c3b2e), rgb(0x4e3236), rgb(0x57383a), rgb(0x45293a), rgb(0x4d2f3c), rgb(0x3d2536),
        rgb(0x45293a), rgb(0x352031), rgb(0x3b2334), rgb(0x2e1b2b), rgb(0x2a1828),
    )

    // ---- Hintergrund: Sterne, Plateau-Wand, Kluft, Nebel ----
    val STAR: Int = rgb(0xf0ecff)
    val PLATEAU_0: Int = rgb(0x6a4562)
    val PLATEAU_1: Int = rgb(0x4a3150)
    val PLATEAU_2: Int = rgb(0x2c1f36)
    val ABYSS_0: Int = rgb(0x1a1222)
    val ABYSS_1: Int = rgb(0x0e0a14)
    val SPIRE: Int = rgb(0x3e2a44)
    val FOG_ROSE: Int = rgb(0xbe6e6e)
    val FOG_MAUVE: Int = rgb(0x5a3c5a)
    val HAZE_ROSE: Int = rgb(0xe89669)
    val CLIFF_0: Int = rgb(0x463054)
    val CLIFF_1: Int = rgb(0x281c36)
    val FOG_PLUM: Int = rgb(0x4a345c)
    val FOG_PLUM_DEEP: Int = rgb(0x302242)

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
