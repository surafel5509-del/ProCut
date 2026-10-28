package com.procutstudio.editor.ui.export

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.ExportResolution
import com.procutstudio.editor.domain.model.FrameRate
import com.procutstudio.editor.ui.components.AspectFrame
import com.procutstudio.editor.ui.components.MessageBanner
import com.procutstudio.editor.ui.components.PrimaryButton
import com.procutstudio.editor.ui.components.ProCutChip
import com.procutstudio.editor.ui.components.ProCutSlider
import com.procutstudio.editor.ui.components.ProCutTopBar
import com.procutstudio.editor.ui.components.SecondaryButton
import com.procutstudio.editor.ui.components.SectionTitle
import com.procutstudio.editor.ui.theme.ProCutBackground
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface
import java.util.Locale

/**
 * Export settings: resolution, frame rate, bitrate, audio and a summary of the actual output.
 * Only resolutions the device's H.264 encoder reports as supported are offered.
 */
@Composable
fun ExportSettingsScreen(
    viewModel: ExportViewModel,
    onBack: () -> Unit,
    onStartExport: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val project = state.project
    val context = LocalContext.current

    // Saving to the gallery needs WRITE_EXTERNAL_STORAGE on Android 8.0-9 only (API 26-28); the
    // request is made here, when the user actually taps export, never at launch.
    fun beginExport() {
        viewModel.rememberSettings()
        viewModel.resetToSettings()
        viewModel.startExport()
        onStartExport()
    }

    val legacyWritePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            beginExport()
        } else {
            viewModel.reportMessage(
                "ProCut Studio needs permission to save videos in Movies on this Android version. " +
                    "Grant it in Settings › Apps › ProCut Studio › Permissions.",
            )
        }
    }

    fun startExportWithPermissions() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            legacyWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            beginExport()
        }
    }

    Scaffold(containerColor = ProCutBackground) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ProCutTopBar(title = "Export", subtitle = project?.name, onBack = onBack)

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ProCutPrimary)
                }
                return@Column
            }

            if (project == null) {
                Text(
                    text = state.error ?: "This project could not be opened.",
                    color = ProCutOnSurfaceMuted,
                    modifier = Modifier.padding(24.dp),
                )
                return@Column
            }

            if (project.isEmpty) {
                Text(
                    text = "Add at least one clip before exporting.",
                    color = ProCutOnSurfaceMuted,
                    modifier = Modifier.padding(24.dp),
                )
                return@Column
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                state.previewBitmap?.let { frame ->
                    Text(
                        text = "Preview · first frame of the timeline",
                        style = MaterialTheme.typography.labelSmall,
                        color = ProCutOnSurfaceMuted,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                    AspectFrame(
                        aspectRatio = project.aspectRatio.ratio,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    ) { frameModifier ->
                        Image(
                            bitmap = frame.asImageBitmap(),
                            contentDescription = "Preview of the exported frame",
                            contentScale = ContentScale.Crop,
                            modifier = frameModifier,
                        )
                    }
                }

                SectionTitle("Resolution", modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.supportedResolutions.forEach { resolution ->
                        ProCutChip(
                            label = resolution.label,
                            selected = state.settings.resolution == resolution,
                            onClick = { viewModel.setResolution(resolution) },
                        )
                    }
                }
                state.supportedResolutions.let { supported ->
                    if (supported.size < ExportResolution.entries.size) {
                        val missing = ExportResolution.entries.filterNot { it in supported }
                        Text(
                            text = "${missing.joinToString { it.label }} is not supported by this device's encoder.",
                            style = MaterialTheme.typography.labelSmall,
                            color = ProCutOnSurfaceMuted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                SectionTitle("Frame rate", modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameRate.entries.forEach { rate ->
                        ProCutChip(
                            label = rate.label,
                            selected = state.settings.frameRate == rate,
                            onClick = { viewModel.setFrameRate(rate) },
                        )
                    }
                }

                SectionTitle("Bitrate", modifier = Modifier.padding(top = 12.dp))
                val spec = state.outputSpec
                ProCutSlider(
                    label = "Video bitrate",
                    value = ((spec?.videoBitrate ?: 0) / 1_000_000f),
                    range = 2f..45f,
                    valueLabel = String.format(Locale.US, "%.1f Mbps", (spec?.videoBitrate ?: 0) / 1_000_000f),
                    onValueChange = viewModel::setBitrateMbps,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProCutChip("Automatic", state.settings.videoBitrate == 0, viewModel::setAutoBitrate)
                    ProCutChip("24 Mbps", state.settings.videoBitrate == 24_000_000, { viewModel.setBitrateMbps(24f) })
                    ProCutChip("12 Mbps", state.settings.videoBitrate == 12_000_000, { viewModel.setBitrateMbps(12f) })
                }

                SectionTitle("Audio", modifier = Modifier.padding(top = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Include audio", color = ProCutOnSurface, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "AAC · ${state.settings.audioBitrate / 1000} kbps",
                            color = ProCutOnSurfaceMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(
                        checked = state.settings.includeAudio,
                        onCheckedChange = viewModel::setIncludeAudio,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ProCutOnSurface,
                            checkedTrackColor = ProCutPrimary,
                        ),
                    )
                }

                SectionTitle("Summary", modifier = Modifier.padding(top = 12.dp))
                ExportSummaryCard(
                    aspectLabel = project.aspectRatio.shortLabel,
                    specLabel = spec?.label ?: "—",
                    durationLabel = TimeUtils.formatClock(project.durationUs),
                    sizeLabel = formatBytes(state.estimatedBytes),
                )

                state.error?.let { message ->
                    MessageBanner(
                        message = message,
                        modifier = Modifier.padding(top = 8.dp),
                        isError = true,
                    )
                }

                state.warnings.forEach { warning ->
                    MessageBanner(message = warning, modifier = Modifier.padding(top = 8.dp))
                }

                PrimaryButton(
                    text = if (state.status == ExportStatus.SUCCESS) "Export again" else "Start export",
                    onClick = { startExportWithPermissions() },
                    enabled = !project.isEmpty,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                )
                SecondaryButton(
                    text = "Cancel",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ExportSummaryCard(
    aspectLabel: String,
    specLabel: String,
    durationLabel: String,
    sizeLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ProCutSurface)
            .border(1.dp, ProCutOutline, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SummaryRow("Aspect ratio", aspectLabel)
        SummaryRow("Output", specLabel)
        SummaryRow("Duration", durationLabel)
        SummaryRow("Estimated size", sizeLabel)
        SummaryRow("Container", "MP4 · H.264 + AAC")
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurface,
            textAlign = TextAlign.End,
        )
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1000.0)
}
