package com.procutstudio.editor.ui.editor.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procutstudio.editor.domain.history.EditorCommands
import com.procutstudio.editor.domain.model.AnimationGroup
import com.procutstudio.editor.domain.model.ChromaKey
import com.procutstudio.editor.domain.model.ClipAnimationPreset
import com.procutstudio.editor.domain.model.ClipMask
import com.procutstudio.editor.domain.model.KeyframeEasing
import com.procutstudio.editor.domain.model.MaskType
import com.procutstudio.editor.domain.model.MotionState
import com.procutstudio.editor.ui.editor.EditorUiState
import com.procutstudio.editor.ui.editor.EditorViewModel
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceFaint
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutPrimary
import kotlin.math.abs

// ------------------------------------------------------------------------------ keyframes

/** Keyframe panel: add/remove a keyframe at the playhead, jump between them, tune the values. */
@Composable
fun KeyframePanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = state.selectedClip ?: state.clipAtPlayhead
    val motion = viewModel.motionAtPlayhead()
    val local = if (clip == null) 0L else (state.playheadUs - clip.startUs).coerceIn(0L, clip.durationUs)
    val keyframeHere = clip?.keyframes?.firstOrNull { abs(it.timeUs - local) <= 40_000L }
    val easing = keyframeHere?.easing

    ToolPanelFrame(title = "Keyframe", onConfirm = onClose, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelPill("\u25C0", onClick = { viewModel.jumpToKeyframe(false) }, enabled = clip?.keyframes?.isNotEmpty() == true)
            PanelPill(
                label = if (keyframeHere != null) "\u25C6 Remove" else "\u25C7 Add keyframe",
                onClick = { viewModel.toggleKeyframeAtPlayhead() },
                selected = keyframeHere != null,
            )
            PanelPill("\u25B6", onClick = { viewModel.jumpToKeyframe(true) }, enabled = clip?.keyframes?.isNotEmpty() == true)
            Box(modifier = Modifier.weight(1f))
            PanelPill("Clear all", onClick = { viewModel.clearKeyframes() }, enabled = clip?.keyframes?.isNotEmpty() == true)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyframeEasing.entries.forEach { option ->
                PanelPill(
                    label = option.label,
                    onClick = { viewModel.setKeyframeEasing(option) },
                    selected = easing == option,
                    enabled = keyframeHere != null,
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
            item {
                KeyframeSlider("Scale", motion.scale * 100f, 10f..400f, { "${it.toInt()}%" }, viewModel) { motion.copy(scale = it / 100f) }
                KeyframeSlider("Position X", motion.offsetX * 100f, -100f..100f, { it.toInt().toString() }, viewModel) { motion.copy(offsetX = it / 100f) }
                KeyframeSlider("Position Y", motion.offsetY * 100f, -100f..100f, { it.toInt().toString() }, viewModel) { motion.copy(offsetY = it / 100f) }
                KeyframeSlider("Rotation", motion.rotationDegrees, -180f..180f, { "${it.toInt()}\u00B0" }, viewModel) { motion.copy(rotationDegrees = it) }
                KeyframeSlider("Opacity", motion.opacity * 100f, 0f..100f, { "${it.toInt()}%" }, viewModel) { motion.copy(opacity = it / 100f) }
            }
        }
    }
}

@Composable
private fun KeyframeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    viewModel: EditorViewModel,
    write: (Float) -> MotionState,
) {
    CompactSlider(
        label = label,
        value = value,
        range = range,
        valueLabel = format(value),
        onValueChange = { viewModel.previewKeyframeState(write(it)) },
        onValueChangeFinished = { viewModel.commitTransient(EditorCommands.KEYFRAME) },
    )
}

// ------------------------------------------------------------------------------ mask

private fun maskGlyph(type: MaskType): String = when (type) {
    MaskType.NONE -> "\u2298"
    MaskType.LINEAR -> "\u25E7"
    MaskType.MIRROR -> "\u2550"
    MaskType.CIRCLE -> "\u25CF"
    MaskType.RECTANGLE -> "\u25A0"
    MaskType.HEART -> "\u2665"
    MaskType.STAR -> "\u2605"
}

/** Shape mask panel: pick a shape, then position, size, rotate and feather it. */
@Composable
fun MaskPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = state.selectedClip ?: state.clipAtPlayhead
    val mask = clip?.mask ?: ClipMask()

    ToolPanelFrame(
        title = "Mask",
        onConfirm = onClose,
        modifier = modifier,
        footerStart = {
            PanelPill(
                label = "Invert",
                onClick = { viewModel.setMask(mask.copy(invert = !mask.invert)) },
                selected = mask.invert,
                enabled = mask.isActive,
            )
        },
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(MaskType.entries.toList(), key = { it.name }) { type ->
                ThumbCard(
                    label = type.label,
                    selected = mask.type == type,
                    onClick = { viewModel.setMask(if (type == MaskType.NONE) ClipMask() else mask.copy(type = type)) },
                    size = 52.dp,
                ) {
                    Text(maskGlyph(type), fontSize = 22.sp, color = ProCutOnSurface)
                }
            }
        }
        if (!mask.isActive) {
            Text(
                "Pick a shape to reveal only part of the clip. The area outside the shape shows the canvas behind it.",
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurfaceFaint,
                modifier = Modifier.padding(14.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
                item {
                    MaskSlider("Position X", mask.centerX * 100f, 0f..100f, viewModel) { mask.copy(centerX = it / 100f) }
                    MaskSlider("Position Y", mask.centerY * 100f, 0f..100f, viewModel) { mask.copy(centerY = it / 100f) }
                    MaskSlider("Width", mask.width * 100f, 5f..250f, viewModel) { mask.copy(width = it / 100f) }
                    MaskSlider("Height", mask.height * 100f, 5f..250f, viewModel) { mask.copy(height = it / 100f) }
                    MaskSlider("Rotation", mask.rotationDegrees, -180f..180f, viewModel, "\u00B0") { mask.copy(rotationDegrees = it) }
                    MaskSlider("Feather", mask.feather * 100f, 0f..100f, viewModel) { mask.copy(feather = it / 100f) }
                }
            }
        }
    }
}

@Composable
private fun MaskSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    viewModel: EditorViewModel,
    suffix: String = "",
    write: (Float) -> ClipMask,
) {
    CompactSlider(
        label = label,
        value = value,
        range = range,
        valueLabel = value.toInt().toString() + suffix,
        onValueChange = { viewModel.previewMask(write(it)) },
        onValueChangeFinished = { viewModel.commitTransient(EditorCommands.MASK) },
    )
}

// ------------------------------------------------------------------------------ chroma key

private val KeySwatches = listOf(0xFF00D84AL, 0xFF0A3CFFL, 0xFFE11D48L, 0xFFFFFFFFL, 0xFF000000L, 0xFFFF00FFL)

/** Chroma key panel: tap the frame to pick the colour to remove, then tune the key. */
@Composable
fun ChromaPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = state.selectedClip ?: state.clipAtPlayhead
    val key = clip?.chromaKey ?: ChromaKey()
    val frame by produceState<Bitmap?>(initialValue = null, clip?.id) { value = viewModel.loadFilterSource() }

    ToolPanelFrame(
        title = "Chroma key",
        onConfirm = onClose,
        modifier = modifier,
        footerStart = {
            PanelPill(
                label = if (key.enabled) "Key on" else "Key off",
                onClick = { viewModel.setChroma(key.copy(enabled = !key.enabled)) },
                selected = key.enabled,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val bitmap = frame
            Box(
                modifier = Modifier
                    .height(84.dp)
                    .aspectRatio(if (bitmap != null) bitmap.width.toFloat() / bitmap.height else 16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Tap to pick the colour to remove",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize().pointerInput(bitmap) {
                            detectTapGestures { tap ->
                                val x = (tap.x / size.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                                val y = (tap.y / size.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                                val argb = bitmap.getPixel(x, y).toLong() and 0xFFFFFFFFL
                                viewModel.setChroma(key.copy(enabled = true, colorArgb = argb or 0xFF000000L))
                            }
                        },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Tap the picture to pick a colour",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProCutOnSurfaceMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeySwatches.forEach { argb ->
                        val selected = key.colorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(argb.toInt()))
                                .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else ProCutOutline, CircleShape)
                                .clickable { viewModel.setChroma(key.copy(enabled = true, colorArgb = argb)) },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(key.colorArgb.toInt())),
                )
            }
        }
        ChromaSlider("Intensity", key.similarity * 100f, viewModel) { key.copy(enabled = true, similarity = it / 100f) }
        ChromaSlider("Smoothness", key.smoothness * 100f, viewModel) { key.copy(smoothness = it / 100f) }
        ChromaSlider("Spill", key.spill * 100f, viewModel) { key.copy(spill = it / 100f) }
    }
}

@Composable
private fun ChromaSlider(label: String, value: Float, viewModel: EditorViewModel, write: (Float) -> ChromaKey) {
    CompactSlider(
        label = label,
        value = value,
        range = 0f..100f,
        onValueChange = { viewModel.previewChroma(write(it)) },
        onValueChangeFinished = { viewModel.commitTransient(EditorCommands.CHROMA) },
    )
}

// ------------------------------------------------------------------------------ animations

/** In / Out / Combo animation picker with a shared duration slider. */
@Composable
fun AnimationPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = state.selectedClip ?: state.clipAtPlayhead
    var tabIndex by remember { mutableStateOf(0) }
    val groups = AnimationGroup.entries
    val group = groups[tabIndex.coerceIn(0, groups.lastIndex)]
    val current: ClipAnimationPreset? = when (group) {
        AnimationGroup.IN -> clip?.animationIn
        AnimationGroup.OUT -> clip?.animationOut
        AnimationGroup.COMBO -> clip?.animationCombo
    }

    ToolPanelFrame(title = "Animations", onConfirm = onClose, modifier = modifier) {
        PanelTabs(groups.map { it.label }, tabIndex, { tabIndex = it })
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "none") {
                ThumbCard("None", current == null, { viewModel.setAnimation(group, null) }, size = 58.dp) { NoneGlyph() }
            }
            items(ClipAnimationPreset.inGroup(group), key = { it.name }) { preset ->
                ThumbCard(preset.label, current == preset, { viewModel.setAnimation(group, preset) }, size = 58.dp) {
                    Text(animationGlyph(preset), fontSize = 22.sp, color = ProCutOnSurface)
                }
            }
        }
        val durationMs = (clip?.animationDurationUs ?: 600_000L) / 1000f
        CompactSlider(
            label = "Duration",
            value = durationMs,
            range = 100f..3000f,
            valueLabel = String.format(java.util.Locale.US, "%.1fs", durationMs / 1000f),
            enabled = clip?.animationIn != null || clip?.animationOut != null || clip?.animationCombo != null,
            onValueChange = { viewModel.previewAnimationDuration((it * 1000f).toLong()) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.ANIMATION) },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun animationGlyph(preset: ClipAnimationPreset): String = when (preset) {
    ClipAnimationPreset.FADE_IN, ClipAnimationPreset.FADE_OUT -> "\u25D0"
    ClipAnimationPreset.ZOOM_IN_IN -> "\u2922"
    ClipAnimationPreset.ZOOM_OUT_OUT -> "\u2921"
    ClipAnimationPreset.SLIDE_IN_LEFT, ClipAnimationPreset.SLIDE_OUT_LEFT -> "\u2190"
    ClipAnimationPreset.SLIDE_IN_RIGHT, ClipAnimationPreset.SLIDE_OUT_RIGHT -> "\u2192"
    ClipAnimationPreset.SLIDE_IN_UP -> "\u2191"
    ClipAnimationPreset.SLIDE_OUT_DOWN -> "\u2193"
    ClipAnimationPreset.SPIN_IN, ClipAnimationPreset.SPIN_OUT -> "\u21BB"
    ClipAnimationPreset.POP_IN -> "\u2736"
    ClipAnimationPreset.PULSE -> "\u25C9"
    ClipAnimationPreset.SWING -> "\u223F"
    ClipAnimationPreset.SLOW_ZOOM -> "\u2316"
    ClipAnimationPreset.DRIFT -> "\u2248"
    ClipAnimationPreset.HEARTBEAT_COMBO -> "\u2665"
}

// ------------------------------------------------------------------------------ opacity

@Composable
fun OpacityPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = state.selectedClip ?: state.clipAtPlayhead
    val opacity = clip?.opacity ?: 1f
    ToolPanelFrame(title = "Opacity", onConfirm = onClose, modifier = modifier) {
        Box(modifier = Modifier.padding(top = 12.dp))
        CompactSlider(
            label = "Opacity",
            value = opacity * 100f,
            range = 0f..100f,
            valueLabel = "${(opacity * 100f).toInt()}%",
            onValueChange = { viewModel.previewOpacity(it / 100f) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.OPACITY) },
        )
        Text(
            "Lower the opacity to let the canvas colour show through, or animate it with keyframes for fades.",
            style = MaterialTheme.typography.labelSmall,
            color = ProCutOnSurfaceFaint,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
