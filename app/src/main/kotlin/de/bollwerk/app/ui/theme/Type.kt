package de.bollwerk.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.bollwerk.app.R

/** Stil-Bibel §3: Bebas Neue für Wordmark/Titel, Rajdhani 500/600/700 für alles andere. */
val BebasNeue = FontFamily(Font(R.font.bebas_neue_regular, FontWeight.Normal))

val Rajdhani = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)

/** Tabellenziffern für Zahlen (Stil-Bibel §3). */
private const val TABULAR = "tnum"

/** Eigene Stile; die Material-[Typography] wird daraus abgeleitet. */
object BollwerkType {
    val Wordmark = TextStyle(fontFamily = BebasNeue, fontSize = 96.sp, letterSpacing = 0.02.em)
    val Display = TextStyle(fontFamily = BebasNeue, fontSize = 40.sp, letterSpacing = 0.06.em)
    val Title = TextStyle(fontFamily = BebasNeue, fontSize = 30.sp, letterSpacing = 0.04.em)
    val Headline = TextStyle(fontFamily = BebasNeue, fontSize = 22.sp, letterSpacing = 0.04.em)
    val Countdown = TextStyle(fontFamily = BebasNeue, fontSize = 120.sp, fontFeatureSettings = TABULAR)

    /** Versalien-Label mit weiter Laufweite (Stil-Bibel: 0,12–0,18 em). */
    val Label = TextStyle(
        fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.18.em,
    )
    val LabelSmall = TextStyle(
        fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.14.em,
    )
    val Button = TextStyle(
        fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.18.em,
    )
    val ButtonLarge = TextStyle(
        fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 0.18.em,
    )
    /** Hauptmenü-Buttons (Mockup 1). */
    val MenuButton = TextStyle(
        fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 0.18.em,
    )

    /** Segmentierte Auswahl (KI-Stärke, Ressourcen, Teamfarbe). */
    val Segment = TextStyle(
        fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.16.em,
    )
    val Body = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    val BodyStrong = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    val Number = TextStyle(
        fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 24.sp, fontFeatureSettings = TABULAR,
    )
}

val BollwerkTypography = Typography(
    displayLarge = BollwerkType.Wordmark,
    displayMedium = BollwerkType.Display,
    displaySmall = BollwerkType.Title,
    headlineLarge = BollwerkType.Display,
    headlineMedium = BollwerkType.Title,
    headlineSmall = BollwerkType.Headline,
    titleLarge = BollwerkType.Title,
    titleMedium = BollwerkType.BodyStrong,
    titleSmall = BollwerkType.Label,
    bodyLarge = BollwerkType.Body,
    bodyMedium = BollwerkType.Body.copy(fontSize = 15.sp),
    bodySmall = BollwerkType.Body.copy(fontSize = 13.sp),
    labelLarge = BollwerkType.Button,
    labelMedium = BollwerkType.Label,
    labelSmall = BollwerkType.LabelSmall,
)
