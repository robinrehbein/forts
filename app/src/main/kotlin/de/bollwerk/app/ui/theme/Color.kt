package de.bollwerk.app.ui.theme

import androidx.compose.ui.graphics.Color
import de.bollwerk.renderapi.Palette

/** Compose-Farben aus den Stil-Bibel-Tokens (§2); [Palette] ist die einzige Quelle. */
object BollwerkColors {
    val Steel = Color(Palette.STEEL)
    val SteelHi = Color(Palette.STEEL_HI)
    val Dark = Color(Palette.DARK)
    val Rust = Color(Palette.RUST)
    val Hazard = Color(Palette.HAZARD)
    val Wood = Color(Palette.WOOD)
    val WoodLight = Color(Palette.WOOD_LIGHT)
    val WoodDark = Color(Palette.WOOD_DARK)
    val Text = Color(Palette.TEXT)
    val TeamBlue = Color(Palette.TEAM_BLUE)
    val TeamRed = Color(Palette.TEAM_RED)
    val Energy = Color(Palette.ENERGY)
    val Ok = Color(Palette.OK)
    val FireInner = Color(Palette.FIRE_INNER)
    val FireMid = Color(Palette.FIRE_MID)
    val FireOuter = Color(Palette.FIRE_OUTER)
    val Sky = Palette.SKY_GRADIENT.map { Color(it) }
    val Sun = Color(Palette.SUN)
    val MountainFar = Color(Palette.MOUNTAIN_FAR)
    val MountainMid = Color(Palette.MOUNTAIN_MID)
    val MountainNear = Color(Palette.MOUNTAIN_NEAR)

    // Abgeleitete UI-Töne (Licht-/Schattenkanten der Stahl- und Rost-Flächen)
    val SteelLight = Color(0xFF3B4757)
    val SteelDeep = Color(0xFF1E252E)
    val RustLight = Color(0xFFEE8C48)
    val RustDeep = Color(0xFFA9481B)
    val BlueLight = Color(0xFF5BA6EE)
    val BlueDeep = Color(0xFF1F5FA8)
    val RedLight = Color(0xFFE8574F)
    val RedDeep = Color(0xFF9E2D27)
    val SkyHaze = Color(0x66D07A5A)
    val SmokeOld = Color(Palette.SMOKE_OLD)
    val PanelFill = Color(0xFF1A212A)
    val Muted = Color(0xFFA7B2C0)
}
