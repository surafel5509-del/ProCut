package com.procutstudio.editor.media.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.util.ProCutLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes a finished export into the device gallery.
 *
 * The row is inserted with `IS_PENDING = 1`, the file is streamed in, and only then is the flag
 * cleared — so a half-written video never becomes visible to other apps. A failure removes the
 * pending row again.
 */
@Singleton
class MediaStorePublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun publish(videoFile: File, displayName: String): Uri = withContext(ioDispatcher) {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/${ProCutFolders.MOVIES_FOLDER}",
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw ExportFailure("Could not create the video in the gallery. Check available storage.")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                videoFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw ExportFailure("Could not write the exported video to the gallery.")
            val finalizeValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, finalizeValues, null, null)
            uri
        } catch (error: Exception) {
            ProCutLogger.e(ProCutLogger.TAG_EXPORT, "Publishing to MediaStore failed", error)
            runCatching { resolver.delete(uri, null, null) }
            if (error is ExportFailure) throw error
            throw ExportFailure("Saving to the gallery failed: ${error.message ?: "unknown error"}", error)
        }
    }
}

object ProCutFolders {
    const val MOVIES_FOLDER = "ProCut Studio"
}
