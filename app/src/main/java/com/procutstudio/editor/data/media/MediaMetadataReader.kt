package com.procutstudio.editor.data.media

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.domain.model.MediaType
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Metadata extracted from a media file without decoding it. */
data class MediaMetadata(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
)

/**
 * Reads container metadata with `MediaMetadataRetriever`. All calls run on an IO dispatcher by the
 * caller; nothing here touches the main thread and no frame is decoded.
 */
@Singleton
class MediaMetadataReader @Inject constructor() {

    fun read(file: File, mediaType: MediaType): MediaMetadata {
        if (mediaType == MediaType.IMAGE) return readImage(file)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val hasVideo = mediaType == MediaType.VIDEO && width > 0 && height > 0
            MediaMetadata(
                durationUs = durationMs * 1_000L,
                width = width,
                height = height,
                rotationDegrees = rotation,
                hasAudio = hasAudio,
                hasVideo = hasVideo,
            )
        } catch (error: Exception) {
            ProCutLogger.w(ProCutLogger.TAG_MEDIA, "Metadata read failed for ${file.name}", error)
            MediaMetadata(0L, 0, 0, 0, false, false)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readImage(file: File): MediaMetadata {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return MediaMetadata(
            durationUs = 0L,
            width = options.outWidth.coerceAtLeast(0),
            height = options.outHeight.coerceAtLeast(0),
            rotationDegrees = 0,
            hasAudio = false,
            hasVideo = false,
        )
    }

    /** Best-effort frame rate of a video track, used to pick sensible export defaults. */
    fun readFrameRate(file: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
                ?.toInt()
                ?.takeIf { it in 1..240 }
        } catch (error: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
