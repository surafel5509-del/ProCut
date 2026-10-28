package com.procutstudio.editor.ui.editor.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.history.EditorCommands
import com.procutstudio.editor.domain.model.ClipTransform
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.FilterPreset
import com.procutstudio.editor.domain.model.FitMode
import com.procutstudio.editor.domain.model.KenBurnsMotion
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.domain.model.TransitionType
import com.procutstudio.editor.domain.model.VideoEffect
import com.procutstudio.editor.ui.components.PrimaryButton
import com.procutstudio.editor.ui.components.ProCutChip
import com.procutstudio.editor.ui.components.ProCutSlider
import com.procutstudio.editor.ui.components.SecondaryButton
import com.procutstudio.editor.ui.components.SectionTitle
import com.procutstudio.editor.ui.editor.EditorViewModel
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutSurface
import kotlin.math.roundToLong

enum class ClipToolTab(val label: String) {
    TRIM("Trim"),
    TRANSFORM("Transform"),
    ADJUST("Adjust"),
    FILTER("Filter"),
    SPEED("Speed"),
    TRANSITION("Transition"),
}

/**
 * Contextual editor for the selected clip: trimming, geometry, colour, filters, speed and
 * transitions. Sliders update the preview live and commit a single undo step when released.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipToolSheet(
    project: EditorProject,
    clip: TimelineClip,
    playheadUs: Long,
    initialTab: ClipToolTab,
    onDismiss: () -> Unit,
    viewModel: EditorViewModel,
    onRequestReplace: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember(initialTab) { mutableStateOf(initialTab) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ProCutSurface,
        contentColor = ProCutOnSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = clip.label.ifBlank { "Clip" },
                style = MaterialTheme.typography.titleMedium,
                color = ProCutOnSurface,
            )
            Text(
                text = "${TimeUtils.formatClock(clip.durationUs, showMillis = true)} on timeline · source " +
                    "${TimeUtils.formatClock(clip.sourceStartUs, true)} → ${TimeUtils.formatClock(clip.sourceEndUs, true)}",
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurfaceMuted,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClipToolTab.entries.forEach { entry ->
                    ProCutChip(
                        label = entry.label,
                        selected = tab == entry,
                        onClick = { tab = entry },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            when (tab) {
                ClipToolTab.TRIM -> TrimTab(project, clip, playheadUs, viewModel, onRequestReplace)
                ClipToolTab.TRANSFORM -> TransformTab(project, clip, viewModel)
                ClipToolTab.ADJUST -> AdjustTab(clip, viewModel)
                ClipToolTab.FILTER -> FilterTab(clip, viewModel)
                ClipToolTab.SPEED -> SpeedTab(clip, viewModel)
                ClipToolTab.TRANSITION -> TransitionTab(clip, viewModel)
            }
        }
    }
}

@Composable
private fun TrimTab(
    project: EditorProject,
    clip: TimelineClip,
    playheadUs: Long,
    viewModel: EditorViewModel,
    onRequestReplace: () -> Unit,
) {
    val maxFade = (clip.durationUs / 2).coerceAtLeast(1L)
    Column {
        Text(
            "Drag the white handles on the timeline, or trim to the playhead.",
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurfaceMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                text = "Start → playhead",
                onClick = {
                    if (playheadUs > clip.startUs) {
                        viewModel.trimSelectedStartBy(playheadUs - clip.startUs)
                        viewModel.commitTransient(EditorCommands.TRIM)
                    }
                },
                enabled = playheadUs > clip.startUs + TimeUtils.MIN_CLIP_DURATION_US,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "End → playhead",
                onClick = {
                    if (playheadUs in (clip.startUs + TimeUtils.MIN_CLIP_DURATION_US) until clip.endUs) {
                        viewModel.trimSelectedEndBy(clip.endUs - playheadUs)
                        viewModel.commitTransient(EditorCommands.TRIM)
                    }
                },
                enabled = playheadUs in (clip.startUs + TimeUtils.MIN_CLIP_DURATION_US) until clip.endUs,
                modifier = Modifier.weight(1f),
            )
        }
        SectionTitle("Fades", modifier = Modifier.padding(top = 12.dp))
        ProCutSlider(
            label = "Fade in",
            value = clip.fadeInUs / 1_000f,
            range = 0f..(maxFade / 1_000f),
            valueLabel = TimeUtils.formatSeconds(clip.fadeInUs),
            onValueChange = { value -> viewModel.previewFades((value * 1_000).roundToLong(), clip.fadeOutUs) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.FADE) },
        )
        ProCutSlider(
            label = "Fade out",
            value = clip.fadeOutUs / 1_000f,
            range = 0f..(maxFade / 1_000f),
            valueLabel = TimeUtils.formatSeconds(clip.fadeOutUs),
            onValueChange = { value -> viewModel.previewFades(clip.fadeInUs, (value * 1_000).roundToLong()) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.FADE) },
        )
        if (clip.mediaType == com.procutstudio.editor.domain.model.MediaType.VIDEO) {
            SectionTitle("Original audio")
            ProCutSlider(
                label = "Clip volume",
                value = clip.volume,
                range = 0f..2f,
                valueLabel = "${(clip.volume * 100).toInt()}%",
                onValueChange = { viewModel.setVolume(it) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProCutChip(
                    label = if (clip.isMuted) "Unmute clip" else "Mute clip",
                    selected = clip.isMuted,
                    onClick = { viewModel.toggleMute() },
                )
                Text(
                    text = if (project.asset(clip.assetId)?.hasAudio == true) "Source has audio" else "No audio in source",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProCutOnSurfaceMuted,
                )
            }
        }
        SectionTitle("Replace", modifier = Modifier.padding(top = 12.dp))
        Text(
            "Swap the source media while keeping trims, filters and speed.",
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurfaceMuted,
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            text = if (clip.mediaType == com.procutstudio.editor.domain.model.MediaType.IMAGE) {
                "Replace photo"
            } else {
                "Replace video"
            },
            onClick = onRequestReplace,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TransformTab(project: EditorProject, clip: TimelineClip, viewModel: EditorViewModel) {
    val transform = clip.transform
    val asset = project.asset(clip.assetId)
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Rotate 90°", onClick = { viewModel.rotateBy(90) }, modifier = Modifier.weight(1f))
            SecondaryButton(
                text = "Flip H",
                onClick = { viewModel.toggleFlipHorizontal() },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "Flip V",
                onClick = { viewModel.toggleFlipVertical() },
                modifier = Modifier.weight(1f),
            )
        }
        SectionTitle("Fit", modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FitMode.entries.forEach { mode ->
                ProCutChip(
                    label = mode.label,
                    selected = transform.fit == mode,
                    onClick = { viewModel.setFitMode(mode) },
                )
            }
        }
        SectionTitle("Scale & position")
        ProCutSlider(
            label = "Zoom",
            value = transform.scale,
            range = 0.4f..3f,
            valueLabel = "${(transform.scale * 100).toInt()}%",
            onValueChange = { viewModel.previewTransform(transform.copy(scale = it)) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.TRANSFORM) },
        )
        ProCutSlider(
            label = "Offset X",
            value = transform.offsetX,
            range = -1f..1f,
            valueLabel = "%+.2f".format(transform.offsetX),
            onValueChange = { viewModel.previewTransform(transform.copy(offsetX = it)) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.TRANSFORM) },
        )
        ProCutSlider(
            label = "Offset Y",
            value = transform.offsetY,
            range = -1f..1f,
            valueLabel = "%+.2f".format(transform.offsetY),
            onValueChange = { viewModel.previewTransform(transform.copy(offsetY = it)) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.TRANSFORM) },
        )
        SectionTitle("Crop", modifier = Modifier.padding(top = 8.dp))
        Text(
            "Crop rectangles are stored as fractions of the source frame and applied in the render.",
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurfaceMuted,
        )
        Spacer(Modifier.height(8.dp))
        ProCutSlider(
            label = "Crop left",
            value = transform.cropLeft,
            range = 0f..0.9f,
            valueLabel = "${(transform.cropLeft * 100).toInt()}%",
            onValueChange = { value ->
                viewModel.previewTransform(
                    transform.copy(cropLeft = value.coerceAtMost(transform.cropRight - 0.05f)),
                )
            },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.TRANSFORM) },
        )
        ProCutSlider(
            label = "Crop right",
            value = transform.cropRight,
            range = 0.1f..1f,
            valueLabel = "${(transform.cropRight * 100).toInt()}%",
            onValueChange = { value ->
                viewModel.previewTransform(
                    transform.copy(cropRight = value.coerceAtLeast(transform.cropLeft + 0.05f)),
                )
            },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.TRANSFORM) },
        )
        ProCutSlider(
            label = "Crop top",
            value = transform.cropTop,
            range = 0f..0.9f,
            valueLabel = "${(transform.cropTop * 100).toInt()}%",
            onValueChange = { value ->
                viewModel.previewTransform(
                    transform.copy(cropTop = value.coerceAtMost(transform.cropBottom - 0.05f)),
                )
            },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.TRANSFORM) },
        )
        ProCutSlider(
            label = "Crop bottom",
            value = transform.cropBottom,
            range = 0.1f..1f,
            valueLabel = "${(transform.cropBottom * 100).toInt()}%",
            onValueChange = { value ->
                viewModel.previewTransform(
                    transform.copy(cropBottom = value.coerceAtLeast(transform.cropTop + 0.05f)),
                )
            },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.TRANSFORM) },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProCutChip("Reset crop", selected = transform.isFullFrameCrop, onClick = {
                viewModel.setTransform(transform.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f))
            })
            ProCutChip("1:1", selected = false, onClick = {
                asset?.let { viewModel.setTransform(cropForAspect(transform, it.aspectRatio, 1f)) }
            })
            ProCutChip("16:9", selected = false, onClick = {
                asset?.let { viewModel.setTransform(cropForAspect(transform, it.aspectRatio, 16f / 9f)) }
            })
            ProCutChip("9:16", selected = false, onClick = {
                asset?.let { viewModel.setTransform(cropForAspect(transform, it.aspectRatio, 9f / 16f)) }
            })
            ProCutChip("4:5", selected = false, onClick = {
                asset?.let { viewModel.setTransform(cropForAspect(transform, it.aspectRatio, 4f / 5f)) }
            })
        }
        if (asset?.isImage == true) {
            SectionTitle("Motion (photos)", modifier = Modifier.padding(top = 14.dp))
            Text(
                "A slow camera move so still photos do not look frozen in the exported video.",
                style = MaterialTheme.typography.bodySmall,
                color = ProCutOnSurfaceMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KenBurnsMotion.entries.forEach { motion ->
                    ProCutChip(
                        label = motion.label,
                        selected = transform.kenBurns == motion,
                        onClick = { viewModel.setKenBurns(motion) },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SecondaryButton(
            text = "Reset transform",
            onClick = { viewModel.setTransform(ClipTransform()) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Centre crop that converts a source of [sourceAspect] into [targetAspect]. */
internal fun cropForAspect(transform: ClipTransform, sourceAspect: Float, targetAspect: Float): ClipTransform {
    return if (targetAspect < sourceAspect) {
        val width = (targetAspect / sourceAspect).coerceIn(0.05f, 1f)
        val inset = (1f - width) / 2f
        transform.copy(cropLeft = inset, cropTop = 0f, cropRight = 1f - inset, cropBottom = 1f)
    } else {
        val height = (sourceAspect / targetAspect).coerceIn(0.05f, 1f)
        val inset = (1f - height) / 2f
        transform.copy(cropLeft = 0f, cropTop = inset, cropRight = 1f, cropBottom = 1f - inset)
    }
}

@Composable
private fun AdjustTab(clip: TimelineClip, viewModel: EditorViewModel) {
    val effects = clip.effects
    Column {
        ProCutSlider("Brightness", effects.brightness, -100f..100f,
            onValueChange = { viewModel.previewEffects(effects.copy(brightness = it)) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.ADJUST) })
        ProCutSlider("Contrast", effects.contrast, -100f..100f,
            onValueChange = { viewModel.previewEffects(effects.copy(contrast = it)) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.ADJUST) })
        ProCutSlider("Saturation", effects.saturation, -100f..100f,
            onValueChange = { viewModel.previewEffects(effects.copy(saturation = it)) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.ADJUST) })
        ProCutSlider("Warmth", effects.warmth, -100f..100f,
            onValueChange = { viewModel.previewEffects(effects.copy(warmth = it)) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.ADJUST) })
        ProCutSlider("Hue", effects.hueDegrees, -180f..180f,
            onValueChange = { viewModel.previewEffects(effects.copy(hueDegrees = it)) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.ADJUST) })
        ProCutSlider("Vignette", effects.vignette * 100f, 0f..100f,
            onValueChange = { viewModel.previewEffects(effects.copy(vignette = it / 100f)) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.ADJUST) })
        SecondaryButton(
            text = "Reset adjustments",
            onClick = { viewModel.setEffects(VideoEffect(filter = effects.filter)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FilterTab(clip: TimelineClip, viewModel: EditorViewModel) {
    val presets = FilterPreset.entries
    Column {
        presets.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { preset ->
                    ProCutChip(
                        label = preset.label,
                        selected = clip.effects.filter == preset,
                        onClick = { viewModel.setEffects(clip.effects.copy(filter = preset)) },
                    )
                }
            }
        }
        Text(
            "Filters combine HSL, RGB, contrast and brightness effects and are rendered by the GPU " +
                "in both preview and export.",
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurfaceMuted,
        )
    }
}

@Composable
private fun SpeedTab(clip: TimelineClip, viewModel: EditorViewModel) {
    val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            speeds.take(4).forEach { speed ->
                ProCutChip(
                    label = "${speed}×",
                    selected = clip.speed == speed,
                    onClick = { viewModel.setSpeed(speed) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            speeds.drop(4).forEach { speed ->
                ProCutChip(
                    label = "${speed}×",
                    selected = clip.speed == speed,
                    onClick = { viewModel.setSpeed(speed) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ProCutSlider(
            label = "Speed",
            value = clip.speed,
            range = 0.25f..4f,
            valueLabel = "%.2f×".format(clip.speed),
            onValueChange = { viewModel.previewSpeed(it) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.SPEED) },
        )
        Text(
            "Speed retimes both video and audio. New duration: ${TimeUtils.formatClock(clip.durationUs)}.",
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurfaceMuted,
        )
    }
}

@Composable
private fun TransitionTab(clip: TimelineClip, viewModel: EditorViewModel) {
    val transition = clip.transition
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransitionType.entries.forEach { type ->
                ProCutChip(
                    label = type.label,
                    selected = transition.type == type,
                    onClick = { viewModel.previewTransition(transition.durationUs, type) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ProCutSlider(
            label = "Duration",
            value = transition.durationUs / 1_000f,
            range = 200f..2_000f,
            valueLabel = TimeUtils.formatSeconds(transition.durationUs),
            onValueChange = { viewModel.previewTransition((it * 1_000).roundToLong(), transition.type) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.TRANSITION) },
        )
        Text(
            "Dissolve is rendered as a dip through black: Media3 cannot composite two clips with " +
                "time-varying alpha in one pass. Fade transitions are rendered exactly.",
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurfaceMuted,
        )
    }
}
