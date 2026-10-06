package de.bollwerk.app.ui

import androidx.compose.ui.graphics.Color
import de.bollwerk.renderapi.Palette

/** Compose-Farben aus den Stil-Bibel-Tokens ([Palette] ist die einzige Quelle). */
object BollwerkColors {
    val Steel = Color(Palette.STEEL)
    val SteelHi = Color(Palette.STEEL_HI)
    val Dark = Color(Palette.DARK)
    val Rust = Color(Palette.RUST)
    val Hazard = Color(Palette.HAZARD)
    val Text = Color(Palette.TEXT)
    val Energy = Color(Palette.ENERGY)
    val Ok = Color(Palette.OK)
    val Sky = Palette.SKY_GRADIENT.map { Color(it) }
    val Sun = Color(Palette.SUN)
    val MountainFar = Color(Palette.MOUNTAIN_FAR)
    val MountainMid = Color(Palette.MOUNTAIN_MID)
    val MountainNear = Color(Palette.MOUNTAIN_NEAR)
}
