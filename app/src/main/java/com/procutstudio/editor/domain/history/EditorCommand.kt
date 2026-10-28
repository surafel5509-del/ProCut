package com.procutstudio.editor.domain.history

/**
 * Labels for every user-visible editing action. Keeping them in one place means the undo toast and
 * the "last edit" chip always match what the user did, and it documents the edit surface.
 */
object EditorCommands {
    const val IMPORT_MEDIA = "Import media"
    const val ADD_CLIP = "Add clip"
    const val SPLIT = "Split clip"
    const val DELETE = "Delete"
    const val DUPLICATE = "Duplicate"
    const val REORDER = "Reorder clips"
    const val TRIM = "Trim clip"
    const val ADJUST = "Adjust clip"
    const val FILTER = "Apply filter"
    const val TRANSFORM = "Transform clip"
    const val SPEED = "Change speed"
    const val VOLUME = "Change volume"
    const val MUTE = "Toggle mute"
    const val FADE = "Adjust fade"
    const val TRANSITION = "Change transition"
    const val ASPECT_RATIO = "Change aspect ratio"
    const val CANVAS = "Change canvas"
    const val ADD_TEXT = "Add text"
    const val EDIT_TEXT = "Edit text"
    const val ADD_OVERLAY = "Add overlay"
    const val EDIT_OVERLAY = "Edit overlay"
    const val ADD_AUDIO = "Add audio"
    const val EDIT_AUDIO = "Edit audio"
    const val REPLACE_CLIP = "Replace clip"
    const val RENAME = "Rename project"
    const val UNDO = "Undo"
    const val REDO = "Redo"
    const val RECORD_VOICE = "Voice-over"
    const val SUBTITLE_IMPORT = "Import subtitles"
}
