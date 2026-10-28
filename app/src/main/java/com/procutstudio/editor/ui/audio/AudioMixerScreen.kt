package com.procutstudio.editor.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.AudioClip
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.ui.components.EmptyState
import com.procutstudio.editor.ui.components.MessageBanner
import com.procutstudio.editor.ui.components.PrimaryButton
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
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated
import com.procutstudio.editor.ui.theme.TrackAudioColor

/**
 * Audio screen: background music, sound effects, voice-over recording and per-clip volume control
 * for both the music lane and the original audio of video clips.
 */
@Composable
fun AudioMixerScreen(
    onBack: () -> Unit,
    viewModel: AudioMixerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val musicPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importMusic(uris) }

    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = ProCutBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ProCutTopBar(
                title = "Audio & music",
                subtitle = state.project?.let { "${it.audioClips.size} audio clips · ${TimeUtils.formatDurationLong(it.durationUs)}" },
                onBack = onBack,
            )
            val project = state.project
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ProCutPrimary)
                }
                return@Column
            }
            if (project == null) {
                EmptyState(
                    icon = Icons.Outlined.LibraryMusic,
                    title = "Audio unavailable",
                    message = state.error ?: "This project could not be opened.",
                    actionLabel = "Go back",
                    onAction = onBack,
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = 32.dp,
                ),
            ) {
                item {
                    SectionTitle("Playhead")
                    ProCutSlider(
                        label = "Add audio at",
                        value = state.playheadUs / 1_000_000f,
                        range = 0f..(project.durationUs / 1_000_000f).coerceAtLeast(1f),
                        valueLabel = TimeUtils.formatClock(state.playheadUs),
                        onValueChange = { viewModel.setPlayhead((it * 1_000_000).toLong()) },
                    )
                }

                item {
                    SectionTitle("Add audio", modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(
                            text = "Music & SFX",
                            icon = Icons.Outlined.LibraryMusic,
                            onClick = { musicPicker.launch(arrayOf("audio/*")) },
                            modifier = Modifier.weight(1f),
                        )
                        if (state.isRecording) {
                            SecondaryButton(
                                text = "Stop (${state.recordingSeconds}s)",
                                icon = Icons.Outlined.StopCircle,
                                onClick = { viewModel.stopRecording() },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            SecondaryButton(
                                text = "Record",
                                icon = Icons.Outlined.Mic,
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        viewModel.startRecording()
                                    } else {
                                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (state.isImporting) {
                        Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ProCutPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("Importing audio…", style = MaterialTheme.typography.labelSmall, color = ProCutOnSurfaceMuted)
                        }
                    }
                    if (state.isRecording) {
                        Spacer(Modifier.height(8.dp))
                        MessageBanner("Recording from the microphone. Tap Stop to add the take at the playhead.")
                    }
                }

                if (project.audioClips.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.LibraryMusic,
                            title = "No audio yet",
                            message = "Add background music, a sound effect or record a voice-over.",
                        )
                    }
                } else {
                    item { SectionTitle("Music & voice-over", modifier = Modifier.padding(top = 14.dp)) }
                    items(items = project.audioClips, key = { it.id }) { clip ->
                        AudioClipCard(
                            clip = clip,
                            peaks = state.waveforms[clip.assetId],
                            viewModel = viewModel,
                            project = project,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }

                if (project.videoClips.isNotEmpty()) {
                    item { SectionTitle("Original clip audio", modifier = Modifier.padding(top = 12.dp)) }
                    items(items = project.videoClips, key = { it.id }) { clip ->
                        VideoAudioRow(clip = clip, hasAudio = project.asset(clip.assetId)?.hasAudio == true, viewModel = viewModel)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioClipCard(
    clip: AudioClip,
    peaks: FloatArray?,
    viewModel: AudioMixerViewModel,
    project: com.procutstudio.editor.domain.model.EditorProject,
) {
    val asset = project.asset(clip.assetId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ProCutSurface)
            .border(1.dp, ProCutOutline, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TrackAudioColor.copy(alpha = 0.35f)),
            ) {
                if (peaks != null) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
                        val bars = (size.width / 3f).toInt().coerceAtLeast(1)
                        repeat(bars) { index ->
                            val value = peaks[(index.toFloat() / bars * peaks.size).toInt().coerceIn(0, peaks.size - 1)]
                            val h = (value * size.height).coerceAtLeast(1f)
                            drawLine(
                                color = Color.White.copy(alpha = 0.7f),
                                start = Offset(index * 3f, size.height / 2 - h / 2),
                                end = Offset(index * 3f, size.height / 2 + h / 2),
                                strokeWidth = 1.5f,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    clip.label.ifBlank { asset?.displayName ?: "Audio" },
                    style = MaterialTheme.typography.titleSmall,
                    color = ProCutOnSurface,
                    maxLines = 1,
                )
                Text(
                    "starts ${TimeUtils.formatClock(clip.startUs)} · ${TimeUtils.formatClock(clip.durationUs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProCutOnSurfaceMuted,
                )
            }
            IconButton(onClick = { viewModel.toggleMute(clip.id) }) {
                Icon(
                    if (clip.isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    contentDescription = "Mute",
                    tint = if (clip.isMuted) ProCutOnSurfaceMuted else ProCutOnSurface,
                )
            }
            IconButton(onClick = { viewModel.delete(clip.id) }) {
                Icon(Icons.Outlined.Deselect, contentDescription = "Remove", tint = ProCutOnSurfaceMuted)
            }
        }

        ProCutSlider(
            label = "Volume",
            value = clip.volume,
            range = 0f..2f,
            valueLabel = "${(clip.volume * 100).toInt()}%",
            onValueChange = { viewModel.setVolume(clip.id, it) },
        )
        ProCutSlider(
            label = "Fade in",
            value = clip.fadeInUs / 1_000f,
            range = 0f..(clip.durationUs / 2_000f).coerceAtLeast(1f),
            valueLabel = TimeUtils.formatSeconds(clip.fadeInUs),
            onValueChange = { viewModel.setFades(clip.id, (it * 1_000).toLong(), clip.fadeOutUs) },
        )
        ProCutSlider(
            label = "Fade out",
            value = clip.fadeOutUs / 1_000f,
            range = 0f..(clip.durationUs / 2_000f).coerceAtLeast(1f),
            valueLabel = TimeUtils.formatSeconds(clip.fadeOutUs),
            onValueChange = { viewModel.setFades(clip.id, clip.fadeInUs, (it * 1_000).toLong()) },
        )
        if (asset != null) {
            val maxStart = (asset.durationUs - clip.durationUs).coerceAtLeast(0L)
            ProCutSlider(
                label = "Trim from source",
                value = clip.sourceStartUs / 1_000f,
                range = 0f..(maxStart / 1_000f).coerceAtLeast(1f),
                valueLabel = TimeUtils.formatClock(clip.sourceStartUs),
                onValueChange = { value ->
                    viewModel.trim(clip.id, (value * 1_000).toLong(), clip.durationUs)
                },
            )
            val maxLength = (asset.durationUs - clip.sourceStartUs).coerceAtLeast(100_000L)
            ProCutSlider(
                label = "Length",
                value = clip.durationUs / 1_000f,
                range = 100f..(maxLength / 1_000f).coerceAtLeast(200f),
                valueLabel = TimeUtils.formatClock(clip.durationUs),
                onValueChange = { value -> viewModel.trim(clip.id, clip.sourceStartUs, (value * 1_000).toLong()) },
            )
        }
    }
}

@Composable
private fun VideoAudioRow(clip: TimelineClip, hasAudio: Boolean, viewModel: AudioMixerViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ProCutSurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                clip.label.ifBlank { "Clip" },
                style = MaterialTheme.typography.labelMedium,
                color = ProCutOnSurface,
                maxLines = 1,
            )
            Text(
                if (hasAudio) {
                    "Volume ${(clip.volume * 100).toInt()}%"
                } else {
                    "No audio track in source"
                },
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurfaceMuted,
            )
        }
        if (hasAudio) {
            Box(modifier = Modifier.width(150.dp)) {
                ProCutSlider(
                    label = "",
                    value = clip.volume,
                    range = 0f..2f,
                    valueLabel = "${(clip.volume * 100).toInt()}%",
                    onValueChange = { viewModel.setVideoVolume(clip.id, it) },
                )
            }
            IconButton(onClick = { viewModel.toggleVideoMute(clip.id) }) {
                Icon(
                    if (clip.isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    contentDescription = "Mute clip",
                    tint = if (clip.isMuted) ProCutOnSurfaceMuted else ProCutOnSurface,
                )
            }
        }
    }
}
