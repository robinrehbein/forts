package de.bollwerk.app.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.bollwerk.app.R
import de.bollwerk.app.settings.AppSettings
import de.bollwerk.app.ui.components.BlueprintBackground
import de.bollwerk.app.ui.components.IndustrialSlider
import de.bollwerk.app.ui.components.SliderDragState
import de.bollwerk.app.ui.components.IndustrialSwitch
import de.bollwerk.app.ui.components.ScreenHeader
import de.bollwerk.app.ui.components.SectionLabel
import de.bollwerk.app.ui.components.SteelPanel
import de.bollwerk.app.ui.components.percentOf
import de.bollwerk.app.ui.components.screenPadding
import de.bollwerk.app.ui.theme.BollwerkColors
import de.bollwerk.app.ui.theme.BollwerkTheme
import de.bollwerk.app.ui.theme.BollwerkType

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onSound = viewModel::setSoundVolume,
        onMusic = viewModel::setMusicVolume,
        onLeftHanded = viewModel::setLeftHanded,
        onReleaseToFire = viewModel::setReleaseToFire,
        onReducedEffects = viewModel::setReducedEffects,
    )
}

/**
 * Einstellungen: Ton, Steuerung, Anzeige; die Sprache folgt dem System (nur Info-Zeile).
 * Solange [settings] `null` ist (DataStore hat noch nichts geliefert), bleibt der Inhalt leer, damit keine
 * Standardwerte aufblitzen und keine Eingabe auf einen falsch angezeigten Zustand trifft.
 */
@Composable
fun SettingsContent(
    settings: AppSettings?,
    onBack: () -> Unit,
    onSound: (Float) -> Unit,
    onMusic: (Float) -> Unit,
    onLeftHanded: (Boolean) -> Unit,
    onReleaseToFire: (Boolean) -> Unit,
    onReducedEffects: (Boolean) -> Unit,
) {
    BlueprintBackground {
        Column(Modifier.fillMaxSize().screenPadding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ScreenHeader(
                title = stringResource(R.string.menu_settings),
                subtitle = stringResource(R.string.settings_subtitle),
                onBack = onBack,
            )
            if (settings != null) {
                Row(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel(stringResource(R.string.settings_section_audio))
                        SteelPanel(contentPadding = PaddingValues0) {
                            Column {
                                VolumeRow(R.drawable.ic_volume, stringResource(R.string.settings_sound), settings.soundVolume, onSound)
                                VolumeRow(R.drawable.ic_music, stringResource(R.string.settings_music), settings.musicVolume, onMusic)
                            }
                        }
                        SteelPanel(contentPadding = PaddingValues0) {
                            InfoRow(R.drawable.ic_globe, stringResource(R.string.settings_language), stringResource(R.string.settings_language_value))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel(stringResource(R.string.settings_section_controls))
                        SteelPanel(contentPadding = PaddingValues0) {
                            Column {
                                ToggleRow(
                                    R.drawable.ic_swap_layout, stringResource(R.string.settings_left_handed),
                                    stringResource(R.string.settings_left_handed_desc), settings.leftHanded, onLeftHanded,
                                )
                                ToggleRow(
                                    R.drawable.ic_release, stringResource(R.string.settings_release_fire),
                                    stringResource(R.string.settings_release_fire_desc), settings.releaseToFire, onReleaseToFire,
                                )
                            }
                        }
                        SectionLabel(stringResource(R.string.settings_section_display))
                        SteelPanel(contentPadding = PaddingValues0) {
                            ToggleRow(
                                R.drawable.ic_effects, stringResource(R.string.settings_reduced_fx),
                                stringResource(R.string.settings_reduced_fx_desc), settings.reducedEffects, onReducedEffects,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val PaddingValues0 = PaddingValues(0.dp)

@Composable
private fun RowIcon(@DrawableRes icon: Int) {
    Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(BollwerkColors.SteelHi))
}

@Composable
private fun VolumeRow(@DrawableRes icon: Int, title: String, value: Float, onChange: (Float) -> Unit) {
    val drag = remember { SliderDragState(value) }
    LaunchedEffect(value) { drag.syncExternal(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RowIcon(icon)
            Text(title, Modifier.weight(1f), style = BollwerkType.BodyStrong, color = BollwerkColors.Text)
            Text(
                stringResource(R.string.percent_format, percentOf(drag.value)),
                style = BollwerkType.Number.copy(fontSize = 20.sp),
                color = BollwerkColors.Text,
            )
        }
        IndustrialSlider(drag.value, drag::onDrag, { onChange(drag.onFinish()) })
    }
}

@Composable
private fun ToggleRow(@DrawableRes icon: Int, title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = BollwerkType.BodyStrong, color = BollwerkColors.Text)
            Text(desc, style = BollwerkType.Body.copy(fontSize = 12.sp, lineHeight = 14.sp), color = BollwerkColors.Muted)
        }
        IndustrialSwitch(checked, onChange)
    }
}

@Composable
private fun InfoRow(@DrawableRes icon: Int, title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowIcon(icon)
        Text(title, Modifier.weight(1f), style = BollwerkType.BodyStrong, color = BollwerkColors.Text)
        Spacer(Modifier.width(8.dp))
        Text(value, style = BollwerkType.Body, color = BollwerkColors.Muted)
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun SettingsPreview() {
    BollwerkTheme {
        SettingsContent(AppSettings(leftHanded = true), {}, {}, {}, {}, {}, {})
    }
}
