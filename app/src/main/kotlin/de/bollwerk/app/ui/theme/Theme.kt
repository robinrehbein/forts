package de.bollwerk.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val BollwerkColorScheme = darkColorScheme(
    primary = BollwerkColors.Rust,
    onPrimary = BollwerkColors.Text,
    secondary = BollwerkColors.Hazard,
    onSecondary = BollwerkColors.Dark,
    tertiary = BollwerkColors.Energy,
    background = BollwerkColors.Dark,
    onBackground = BollwerkColors.Text,
    surface = BollwerkColors.Steel,
    onSurface = BollwerkColors.Text,
    surfaceVariant = BollwerkColors.PanelFill,
    onSurfaceVariant = BollwerkColors.Muted,
    outline = BollwerkColors.SteelHi,
    error = BollwerkColors.TeamRed,
    onError = BollwerkColors.Text,
)

/** App-Theme: immer dunkel (Stil-Bibel), Farben und Schriften aus Palette und Typografie. */
@Composable
fun BollwerkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BollwerkColorScheme, typography = BollwerkTypography, content = content)
}
