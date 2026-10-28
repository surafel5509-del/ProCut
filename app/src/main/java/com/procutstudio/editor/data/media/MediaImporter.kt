package com.procutstudio.editor.data.media

import android.content.Context
import android.net.Uri
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.util.FileUtils
import com.procutstudio.editor.core.util.IdGenerator
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportResult {
    data class Success(val asset: MediaAsset) : ImportResult
    data class Failure(val message: String, val cause: Throwable? = null) : ImportResult
}

/**
 * Imports media into the project's private storage.
 *
 * The file is streamed (never loaded into memory), copied under an id-based name, then verified by
 * reading its metadata. Anything that fails verification is deleted again so the media folder only
 * ever contains files the editor can actually use.
 */
@Singleton
class MediaImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataReader: MediaMetadataReader,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun import(uri: Uri, hint: MediaType? = null): ImportResult = withContext(ioDispatcher) {
        val displayName = FileUtils.queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "Imported media"
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val mediaType = hint ?: mediaTypeOf(mime, displayName) ?: return@withContext ImportResult.Failure(
            "Unsupported file type. ProCut Studio imports video, photos and audio files.",
        )
        val assetId = IdGenerator.assetId()
        val extension = FileUtils.extensionOf(displayName).ifBlank { defaultExtension(mediaType) }
        val fileName = "$assetId$extension"
        val target: File = try {
            FileUtils.copyToPrivateStorage(context, uri, fileName)
        } catch (error: Exception) {
            ProCutLogger.e(ProCutLogger.TAG_MEDIA, "Copy failed for $uri", error)
            return@withContext ImportResult.Failure(
                "Could not read “$displayName”. The file may have been moved or access was revoked.",
                error,
            )
        }
        if (target.length() <= 0L) {
            FileUtils.deleteQuietly(target)
            return@withContext ImportResult.Failure("“$displayName” is empty and cannot be imported.")
        }
        val metadata = metadataReader.read(target, mediaType)
        val problem = validate(mediaType, metadata, target)
        if (problem != null) {
            FileUtils.deleteQuietly(target)
            return@withContext ImportResult.Failure(problem)
        }
        ImportResult.Success(
            MediaAsset(
                id = assetId,
                uri = Uri.fromFile(target).toString(),
                displayName = displayName,
                mediaType = mediaType,
                durationUs = if (mediaType == MediaType.IMAGE) 0L else metadata.durationUs,
                width = metadata.width,
                height = metadata.height,
                rotationDegrees = metadata.rotationDegrees,
                hasAudio = metadata.hasAudio,
                sizeBytes = target.length(),
                importedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Wraps a file that already lives in app storage (e.g. a finished microphone recording). */
    suspend fun importLocalFile(file: File, mediaType: MediaType, displayName: String): ImportResult =
        withContext(ioDispatcher) {
            val metadata = metadataReader.read(file, mediaType)
            ImportResult.Success(
                MediaAsset(
                    id = IdGenerator.assetId(),
                    uri = Uri.fromFile(file).toString(),
                    displayName = displayName,
                    mediaType = mediaType,
                    durationUs = if (mediaType == MediaType.IMAGE) 0L else metadata.durationUs,
                    width = metadata.width,
                    height = metadata.height,
                    rotationDegrees = metadata.rotationDegrees,
                    hasAudio = metadata.hasAudio,
                    sizeBytes = file.length(),
                    importedAtMs = System.currentTimeMillis(),
                ),
            )
        }

    private fun validate(mediaType: MediaType, metadata: MediaMetadata, file: File): String? = when {
        mediaType == MediaType.VIDEO && !metadata.hasVideo ->
            "This video uses a format the device cannot decode (${file.extension.uppercase()})."

        mediaType == MediaType.VIDEO && metadata.durationUs < 50_000L ->
            "This video is too short to edit."

        mediaType == MediaType.AUDIO && metadata.durationUs < 100_000L ->
            "This audio file is empty or too short."

        mediaType == MediaType.IMAGE && (metadata.width <= 0 || metadata.height <= 0) ->
            "This image could not be decoded."

        else -> null
    }

    private fun mediaTypeOf(mime: String?, fileName: String): MediaType? {
        val fromMime = when {
            mime == null -> null
            mime.startsWith("video/") -> MediaType.VIDEO
            mime.startsWith("image/") -> MediaType.IMAGE
            mime.startsWith("audio/") -> MediaType.AUDIO
            else -> null
        }
        if (fromMime != null) return fromMime
        return when (FileUtils.extensionOf(fileName)) {
            ".mp4", ".m4v", ".mov", ".mkv", ".webm", ".3gp", ".avi" -> MediaType.VIDEO
            ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp" -> MediaType.IMAGE
            ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".opus", ".flac", ".amr" -> MediaType.AUDIO
            else -> null
        }
    }

    private fun defaultExtension(type: MediaType): String = when (type) {
        MediaType.VIDEO -> ".mp4"
        MediaType.IMAGE -> ".jpg"
        MediaType.AUDIO -> ".m4a"
    }
}
