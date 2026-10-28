package com.procutstudio.editor.media.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.util.ProCutLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Publishes a finished export into the device gallery.
 *
 * **Android 10+ (API 29+)**: the row is inserted with `IS_PENDING = 1`, the file is streamed into
 * the MediaStore, and only then is the flag cleared — a half-written video never becomes visible to
 * other apps. A failure removes the pending row again.
 *
 * **Android 8.0–9 (API 26–28)**: those versions have no `IS_PENDING` / `RELATIVE_PATH`, so the
 * verified file is copied into the public `Movies/ProCut Studio` folder with the legacy
 * `WRITE_EXTERNAL_STORAGE` permission (declared with `maxSdkVersion="28"` and requested by the export
 * screen only) and then registered with the media scanner, so it shows up in the gallery and other
 * apps immediately. If the scanner does not answer, the file is inserted into the media database
 * directly instead of reporting a save that the gallery cannot see.
 */
@Singleton
class MediaStorePublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun publish(videoFile: File, displayName: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithScopedStorage(videoFile, displayName)
        } else {
            publishToPublicMovies(videoFile, displayName)
        }

    /** True when saving needs the legacy storage permission (Android 8.0–9 only). */
    fun requiresLegacyWritePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    // ------------------------------------------------------------------ Android 10+

    private suspend fun publishWithScopedStorage(videoFile: File, displayName: String): Uri =
        withContext(ioDispatcher) {
            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
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

    // ------------------------------------------------------------------ Android 8.0–9

    private suspend fun publishToPublicMovies(videoFile: File, displayName: String): Uri {
        val destination = withContext(ioDispatcher) {
            if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
                throw ExportFailure(
                    "Shared storage is not available on this device, so the video cannot be saved to Movies.",
                )
            }
            @Suppress("DEPRECATION")
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val folder = File(moviesDir, ProCutFolders.MOVIES_FOLDER)
            if (!folder.isDirectory && !folder.mkdirs()) {
                throw ExportFailure(
                    "Could not create the “${ProCutFolders.MOVIES_FOLDER}” folder in Movies. " +
                        "Check that storage is not full and the permission was granted.",
                )
            }
            val target = uniqueDestination(folder, displayName)
            try {
                videoFile.copyTo(target, overwrite = false)
            } catch (error: Exception) {
                target.delete()
                ProCutLogger.e(ProCutLogger.TAG_EXPORT, "Copying the export to Movies failed", error)
                throw ExportFailure(
                    "Saving the video to Movies/${ProCutFolders.MOVIES_FOLDER} failed: " +
                        (error.message ?: "unknown error"),
                    error,
                )
            }
            target
        }
        return registerLegacyVideo(destination)
    }

    /**
     * Makes a file written outside MediaStore visible to the gallery. The media scanner is
     * asynchronous, so it is awaited with a timeout; if it never answers the media database is
     * written directly, which is the documented pre-29 way of publishing a file.
     */
    private suspend fun registerLegacyVideo(file: File): Uri {
        val scanned = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine<Uri?> { continuation ->
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(MIME_TYPE),
                ) { _, uri ->
                    if (continuation.isActive) continuation.resume(uri)
                }
            }
        }
        if (scanned != null) return scanned

        ProCutLogger.w(
            ProCutLogger.TAG_EXPORT,
            "Media scanner did not return a Uri; inserting the saved file into the media database",
        )
        val values = ContentValues().apply {
            @Suppress("DEPRECATION")
            put(MediaStore.Video.Media.DATA, file.absolutePath)
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1_000L)
        }
        @Suppress("DEPRECATION")
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return context.contentResolver.insert(collection, values)
            ?: throw ExportFailure(
                "The video was written to ${file.parentFile?.absolutePath ?: "Movies"}, but it could not " +
                    "be added to the gallery. The file is still on the device.",
            )
    }

    private fun uniqueDestination(folder: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "mp4")
        var candidate = File(folder, "$base.$extension")
        var index = 2
        while (candidate.exists()) {
            candidate = File(folder, "$base ($index).$extension")
            index++
        }
        return candidate
    }

    private companion object {
        const val MIME_TYPE = "video/mp4"
        const val SCAN_TIMEOUT_MS = 5_000L
    }
}

object ProCutFolders {
    const val MOVIES_FOLDER = "ProCut Studio"
}
