package com.procutstudio.editor.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.procutstudio.editor.BuildConfig
import com.procutstudio.editor.data.local.prefs.AppSettings
import com.procutstudio.editor.data.local.prefs.SettingsRepository
import com.procutstudio.editor.domain.model.ExportResolution
import com.procutstudio.editor.domain.model.FrameRate
import com.procutstudio.editor.ui.components.ProCutChip
import com.procutstudio.editor.ui.components.ProCutTopBar
import com.procutstudio.editor.ui.components.SectionTitle
import com.procutstudio.editor.ui.theme.ProCutBackground
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setResolution(resolution: ExportResolution) = viewModelScope.launch {
        settingsRepository.setDefaultResolution(resolution)
    }

    fun setFrameRate(frameRate: FrameRate) = viewModelScope.launch {
        settingsRepository.setDefaultFrameRate(frameRate)
    }

    fun setHaptics(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setHapticsEnabled(enabled)
    }

    fun setSafeArea(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setShowSafeArea(enabled)
    }

    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setKeepScreenOn(enabled)
    }
}

/** Settings & about: default export preferences, editor behaviour and app information. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(containerColor = ProCutBackground) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ProCutTopBar(title = "Settings", subtitle = "Preferences & about", onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                SectionTitle("Default export")
                Text(
                    text = "New projects start with these values. Each project can override them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProCutOnSurfaceMuted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportResolution.entries.forEach { resolution ->
                        ProCutChip(
                            label = resolution.label,
                            selected = settings.defaultResolution == resolution,
                            onClick = { viewModel.setResolution(resolution) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameRate.entries.forEach { rate ->
                        ProCutChip(
                            label = rate.label,
                            selected = settings.defaultFrameRate == rate,
                            onClick = { viewModel.setFrameRate(rate) },
                        )
                    }
                }

                SectionTitle("Editor", modifier = Modifier.padding(top = 12.dp))
                SettingsToggle(
                    title = "Haptics",
                    subtitle = "Vibrate when a trim, split or move is applied.",
                    checked = settings.hapticsEnabled,
                    onCheckedChange = viewModel::setHaptics,
                )
                SettingsToggle(
                    title = "Show safe area",
                    subtitle = "Draw 5 % title-safe and 10 % action-safe guides over the editor preview.",
                    checked = settings.showSafeArea,
                    onCheckedChange = viewModel::setSafeArea,
                )
                SettingsToggle(
                    title = "Keep screen on",
                    subtitle = "Prevent the display from sleeping while editing.",
                    checked = settings.keepScreenOnWhileEditing,
                    onCheckedChange = viewModel::setKeepScreenOn,
                )

                SectionTitle("Privacy", modifier = Modifier.padding(top = 12.dp))
                InfoCard(
                    title = "Everything stays on your phone",
                    body = "Photos and videos are picked through the Android Photo Picker or the system " +
                        "file browser. ProCut copies the selection into app storage, processes it locally " +
                        "and never uploads anything. The microphone is used only while you record a voice-over.",
                )

                SectionTitle("About", modifier = Modifier.padding(top = 12.dp))
                InfoCard(
                    title = "ProCut Studio",
                    body = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
                        "Preview: Media3 ExoPlayer · Export: Media3 Transformer\n" +
                        "Package: ${context.packageName}",
                )
                Text(
                    text = "Transitions in export are fade-through-black/white. A true cross-dissolve between " +
                        "two simultaneous video streams is not exposed by Media3 Transformer 1.5, so ProCut ships " +
                        "the closest supported transition instead of faking one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProCutOnSurfaceMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ProCutSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = ProCutOnSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ProCutOnSurface,
                checkedTrackColor = ProCutPrimary,
            ),
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ProCutSurface)
            .border(1.dp, ProCutOutline, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = ProCutOnSurface)
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurfaceMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
