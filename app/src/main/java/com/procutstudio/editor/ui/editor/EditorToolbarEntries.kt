package com.procutstudio.editor.ui.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Wallpaper
import com.procutstudio.editor.domain.model.AudioClip
import com.procutstudio.editor.domain.model.EffectItem
import com.procutstudio.editor.domain.model.FilterPreset
import com.procutstudio.editor.domain.model.ImageOverlay
import com.procutstudio.editor.domain.model.TextOverlay
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.ui.editor.panels.ClipToolTab

/** Everything the toolbar buttons need from the screen. */
class ToolbarActions(
    val viewModel: EditorViewModel,
    val tick: () -> Unit,
    val openClipSheet: (ClipToolTab) -> Unit,
    val onAddMedia: () -> Unit,
    val onAddText: () -> Unit,
    val onEditText: (String) -> Unit,
    val onAudio: () -> Unit,
    val onCanvas: () -> Unit,
    val onImportOverlay: () -> Unit,
    val onDelete: () -> Unit,
    val onRemoveBackground: () -> Unit,
    val onReplace: () -> Unit,
)

/** Builds the toolbar for the current selection (project tools, clip tools, text tools...). */
fun buildToolbarEntries(state: EditorUiState, actions: ToolbarActions): List<ToolbarEntry> {
    val vm = actions.viewModel
    val selected = state.selectedItem
    val split = ToolbarEntry("Split", { actions.tick(); vm.splitAtPlayhead() }, icon = Icons.Outlined.ContentCut, enabled = state.canSplit)
    val duplicate = ToolbarEntry("Duplicate", { actions.tick(); vm.duplicateSelected() }, icon = Icons.Outlined.ContentCopy)
    val delete = ToolbarEntry("Delete", { actions.tick(); actions.onDelete() }, icon = Icons.Outlined.Delete)

    return when (selected) {
        null -> listOf(
            ToolbarEntry("Add media", actions.onAddMedia, icon = Icons.Outlined.PhotoLibrary),
            ToolbarEntry("Audio", actions.onAudio, icon = Icons.Outlined.MusicNote),
            ToolbarEntry("Text", actions.onAddText, icon = Icons.Outlined.TextFields),
            ToolbarEntry("Sticker", { vm.openTool(ToolPanel.STICKERS) }, icon = Icons.Outlined.EmojiEmotions),
            ToolbarEntry("Overlay", actions.onImportOverlay, icon = Icons.Outlined.Layers),
            ToolbarEntry("Effects", { vm.openTool(ToolPanel.EFFECTS) }, icon = Icons.Outlined.AutoAwesome),
            ToolbarEntry("Filters", { vm.openTool(ToolPanel.FILTERS) }, icon = Icons.Outlined.FilterVintage),
            ToolbarEntry("Canvas", actions.onCanvas, icon = Icons.Outlined.Wallpaper),
        )

        is TimelineClip -> clipEntries(selected, state, actions, split, duplicate, delete)

        is TextOverlay -> listOf(
            split,
            ToolbarEntry("Edit text", { actions.onEditText(selected.id) }, icon = Icons.Outlined.TextFields),
            duplicate,
            delete,
        )

        is ImageOverlay -> listOf(
            split,
            ToolbarEntry("Stickers", { vm.openTool(ToolPanel.STICKERS) }, icon = Icons.Outlined.EmojiEmotions),
            duplicate,
            delete,
        )

        is AudioClip -> listOf(
            split,
            ToolbarEntry("Mixer", actions.onAudio, icon = Icons.Outlined.MusicNote),
            duplicate,
            delete,
        )

        is EffectItem -> listOf(
            split,
            ToolbarEntry("Effects", { vm.openTool(ToolPanel.EFFECTS) }, icon = Icons.Outlined.AutoAwesome),
            duplicate,
            delete,
        )

        else -> listOf(split, duplicate, delete)
    }
}

private fun clipEntries(
    clip: TimelineClip,
    state: EditorUiState,
    actions: ToolbarActions,
    split: ToolbarEntry,
    duplicate: ToolbarEntry,
    delete: ToolbarEntry,
): List<ToolbarEntry> {
    val vm = actions.viewModel
    val asset = state.project?.asset(clip.assetId)
    val isVideo = asset?.isVideo == true
    val hasAudio = isVideo && asset?.hasAudio == true
    return listOf(
        split,
        ToolbarEntry("Edit", { actions.openClipSheet(ClipToolTab.TRIM) }, icon = Icons.Outlined.Tune),
        ToolbarEntry("Speed", { actions.openClipSheet(ClipToolTab.SPEED) }, icon = Icons.Outlined.Speed, highlighted = clip.speed != 1f),
        ToolbarEntry("Volume", { actions.openClipSheet(ClipToolTab.TRIM) }, icon = Icons.Outlined.VolumeUp, enabled = isVideo),
        ToolbarEntry("Animations", { vm.openTool(ToolPanel.ANIMATION) }, icon = Icons.Outlined.Animation,
            highlighted = clip.animationIn != null || clip.animationOut != null || clip.animationCombo != null),
        ToolbarEntry("Keyframe", { vm.openTool(ToolPanel.KEYFRAME) }, glyph = "\u25C6", highlighted = clip.keyframes.isNotEmpty()),
        ToolbarEntry("Mask", { vm.openTool(ToolPanel.MASK) }, icon = Icons.Outlined.Vignette, highlighted = clip.mask.isActive),
        ToolbarEntry("Filters", { vm.openTool(ToolPanel.FILTERS) }, icon = Icons.Outlined.FilterVintage,
            highlighted = clip.effects.filter != FilterPreset.NONE),
        ToolbarEntry("Adjust", { vm.openTool(ToolPanel.ADJUST) }, icon = Icons.Outlined.Tune, highlighted = clip.effects.hasManualAdjustments),
        ToolbarEntry("Effects", { vm.openTool(ToolPanel.EFFECTS) }, icon = Icons.Outlined.AutoAwesome),
        ToolbarEntry("Opacity", { vm.openTool(ToolPanel.OPACITY) }, icon = Icons.Outlined.Opacity, highlighted = clip.opacity < 0.999f),
        ToolbarEntry("Cutout", actions.onRemoveBackground, icon = Icons.Outlined.AutoFixHigh, highlighted = clip.cutoutUri != null),
        ToolbarEntry("Chroma key", { vm.openTool(ToolPanel.CHROMA) }, icon = Icons.Outlined.Colorize, highlighted = clip.chromaKey.enabled),
        ToolbarEntry("Freeze", { actions.tick(); vm.freezeFrame() }, icon = Icons.Outlined.AcUnit),
        ToolbarEntry("Reverse", { actions.tick(); vm.reverseSelectedClip() }, icon = Icons.Outlined.FastRewind,
            enabled = isVideo, highlighted = clip.isReversed),
        ToolbarEntry("Rotate", { actions.tick(); vm.rotateBy(90) }, icon = Icons.Outlined.RotateRight),
        ToolbarEntry("Mirror", { actions.tick(); vm.toggleFlipHorizontal() }, icon = Icons.Outlined.Flip),
        ToolbarEntry("Crop", { actions.openClipSheet(ClipToolTab.TRANSFORM) }, icon = Icons.Outlined.Crop),
        ToolbarEntry("Transition", { actions.openClipSheet(ClipToolTab.TRANSITION) }, icon = Icons.Outlined.SwapHoriz,
            highlighted = !clip.transition.isNone),
        ToolbarEntry("Extract audio", { actions.tick(); vm.extractAudioFromSelected() }, icon = Icons.Outlined.LibraryMusic, enabled = hasAudio),
        ToolbarEntry("Replace", actions.onReplace, icon = Icons.Outlined.Cached),
        duplicate,
        delete,
    )
}
