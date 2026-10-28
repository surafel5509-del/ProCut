package com.procutstudio.editor.domain.model

import kotlinx.serialization.Serializable

/**
 * A piece of source media that has been imported into the project.
 *
 * [uri] always points at the app-private copy created during import, therefore it stays readable
 * for the whole life of the project even if the user later revokes access to the original file.
 * Nothing here is ever mutated: editing only changes the timeline document.
 */
@Serializable
data class MediaAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val mediaType: MediaType,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val hasAudio: Boolean = false,
    val sizeBytes: Long = 0L,
    val importedAtMs: Long = 0L,
) {
    val isImage: Boolean get() = mediaType == MediaType.IMAGE
    val isVideo: Boolean get() = mediaType == MediaType.VIDEO
    val isAudio: Boolean get() = mediaType == MediaType.AUDIO

    /** Width/height after the container rotation is applied. */
    val displayWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val displayHeight: Int get() = if (rotationDegrees % 180 == 0) height else width

    val aspectRatio: Float
        get() = if (displayHeight > 0) displayWidth.toFloat() / displayHeight else 16f / 9f
}
