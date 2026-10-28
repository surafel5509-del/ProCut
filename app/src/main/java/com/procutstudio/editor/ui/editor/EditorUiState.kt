package com.procutstudio.editor.ui.editor

import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.domain.model.TimelineItem

/** Which contextual tool drawer is open under the preview. */
enum class EditorPanel {
    NONE,
    CLIP,        // trim / transform / speed / volume for the selected clip
    PROJECT,     // aspect ratio, canvas colour, project info
    EXPORT_HINT,
}

/** Dark bottom panel hosting a CapCut-style tool (filters, effects, stickers, keyframes…). */
enum class ToolPanel { NONE, FILTERS, ADJUST, EFFECTS, STICKERS, KEYFRAME, MASK, CHROMA, ANIMATION, OPACITY }

data class EditorUiState(
    val project: EditorProject? = null,
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val playheadUs: Long = 0L,
    val isPlaying: Boolean = false,
    val selectedItemId: String? = null,
    val pixelsPerSecond: Float = 64f,
    val activePanel: EditorPanel = EditorPanel.NONE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val isSaving: Boolean = false,
    val lastSavedAtMs: Long? = null,
    val isImportingSticker: Boolean = false,
    val isBusyMessage: String? = null,
    val isPreviewMuted: Boolean = false,
    val toolPanel: ToolPanel = ToolPanel.NONE,
    val busyProgress: Float? = null,
) {
    val durationUs: Long get() = project?.durationUs ?: 0L

    val selectedItem: TimelineItem? get() = selectedItemId?.let { project?.item(it) }

    val selectedClip: TimelineClip? get() = selectedItem as? TimelineClip

    /** Clip under the playhead — what the preview is currently showing. */
    val clipAtPlayhead: TimelineClip? get() = project?.clipAt(playheadUs)

    /** The asset shown by the preview when the playhead sits on a photo. */
    val previewImageAsset: MediaAsset?
        get() {
            val clip = clipAtPlayhead ?: return null
            if (clip.mediaType != com.procutstudio.editor.domain.model.MediaType.IMAGE) return null
            return project?.asset(clip.assetId)
        }

    /** True when the playhead sits inside a splittable item (the selection, else the video lane). */
    val canSplit: Boolean
        get() {
            val selection = selectedItem
            val selectedIsSplittable = selection != null &&
                selection !is TimelineClip &&
                playheadUs > selection.startUs &&
                playheadUs < selection.endUs
            val candidate: TimelineItem? = if (selectedIsSplittable) selection else clipAtPlayhead
            val target = candidate ?: return false
            val offset = playheadUs - target.startUs
            return offset >= MIN_SPLIT_US && target.durationUs - offset >= MIN_SPLIT_US
        }

    private companion object {
        /** Both halves must keep at least this much material (100 ms). */
        const val MIN_SPLIT_US = 100_000L
    }
}
