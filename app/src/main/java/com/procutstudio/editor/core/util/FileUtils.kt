package com.procutstudio.editor.core.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/** App-private storage layout. Imported media is copied here so projects survive URI revocation. */
object FileUtils {

    fun mediaDir(context: Context): File = File(context.filesDir, "media").apply { mkdirs() }

    fun exportsDir(context: Context): File = File(context.filesDir, "exports").apply { mkdirs() }

    fun thumbnailsDir(context: Context): File = File(context.cacheDir, "thumbnails").apply { mkdirs() }

    fun waveformsDir(context: Context): File = File(context.cacheDir, "waveforms").apply { mkdirs() }

    /** Project posters, kept in `files/` because they must survive cache eviction. */
    fun projectThumbnailsDir(context: Context): File =
        File(context.filesDir, "thumbnails").apply { mkdirs() }

    fun recordingsDir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    /** Returns the display name advertised by a content provider, without the file extension. */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    fun querySizeBytes(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: 0L
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
            }
        }.getOrNull() ?: 0L
    }

    fun extensionOf(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ".$ext" else ""
    }

    /**
     * Streams [source] into app-private storage. The copy is atomic: it is written to a `.part`
     * file first and renamed only after a fully successful copy, so a cancelled import can never
     * leave a truncated file behind for the exporter to trip over.
     */
    fun copyToPrivateStorage(
        context: Context,
        source: Uri,
        destinationName: String,
        onProgress: ((bytesCopied: Long) -> Unit)? = null,
    ): File {
        val target = File(mediaDir(context), destinationName)
        val temp = File(target.parentFile, "${target.name}.part")
        val input: InputStream = context.contentResolver.openInputStream(source)
            ?: error("Cannot open input stream for $source")
        input.use { stream ->
            temp.outputStream().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    onProgress?.invoke(copied)
                }
                out.flush()
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    fun deleteQuietly(file: File?) {
        if (file != null && file.exists()) runCatching { file.delete() }
    }

    fun totalBytes(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Free space on the volume that hosts [dir], in bytes. */
    fun freeSpaceBytes(dir: File): Long = dir.usableSpace
}
