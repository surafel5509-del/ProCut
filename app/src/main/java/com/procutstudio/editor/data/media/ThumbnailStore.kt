package com.procutstudio.editor.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.util.FileUtils
import com.procutstudio.editor.domain.model.MediaAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Frame thumbnails for the timeline and the preview poster.
 *
 * Frames are extracted at a requested time, scaled down, written to a disk cache and also kept in a
 * small in-memory LRU so scrolling the timeline does not hit the decoder repeatedly. All work
 * happens off the main thread.
 */
@Singleton
class ThumbnailStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    /**
     * Returns a thumbnail no larger than [targetWidthPx] for the frame at [timeUs].
     * Images are decoded directly; videos ask the retriever for the closest sync frame.
     */
    suspend fun thumbnail(asset: MediaAsset, timeUs: Long, targetWidthPx: Int): Bitmap? =
        withContext(ioDispatcher) {
            val bucketUs = (timeUs / BUCKET_US) * BUCKET_US
            val key = "${asset.id}_${bucketUs}_$targetWidthPx"
            memoryCache.get(key)?.let { return@withContext it }
            val lock = inFlight.getOrPut(key) { Mutex() }
            lock.withLock {
                memoryCache.get(key)?.let { return@withContext it }
                val diskFile = cacheFile(key)
                val bitmap = if (diskFile.exists()) {
                    BitmapFactory.decodeFile(diskFile.absolutePath)
                } else {
                    extract(asset, bucketUs, targetWidthPx)?.also { writeCache(it, diskFile) }
                }
                bitmap?.also { memoryCache.put(key, it) }
            }
        }

    /** Poster frame used for project cards and the "no preview yet" state. */
    suspend fun posterFrame(asset: MediaAsset, targetWidthPx: Int = 512): Bitmap? =
        thumbnail(asset, timeUs = 0L, targetWidthPx = targetWidthPx)

    private fun extract(asset: MediaAsset, timeUs: Long, targetWidthPx: Int): Bitmap? {
        val file = fileOf(asset) ?: return null
        if (asset.isImage) {
            return decodeSampledBitmap(file, targetWidthPx)
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            scaleDown(frame, targetWidthPx)
        } catch (error: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun fileOf(asset: MediaAsset): File? {
        val path = asset.uri.removePrefix("file://")
        val file = File(path)
        return if (file.exists()) file else null
    }

    private fun decodeSampledBitmap(file: File, targetWidthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetWidthPx) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return scaleDown(decoded, targetWidthPx)
    }

    private fun scaleDown(source: Bitmap, targetWidthPx: Int): Bitmap {
        if (source.width <= targetWidthPx) return source
        val height = (source.height * (targetWidthPx.toFloat() / source.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetWidthPx, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun cacheFile(key: String): File = File(FileUtils.thumbnailsDir(context), "$key.jpg")

    private fun writeCache(bitmap: Bitmap, file: File) {
        runCatching {
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out) }
        }
    }

    /** Deletes cached frames (Settings → Clear cache). */
    suspend fun clearCache() = withContext(ioDispatcher) {
        memoryCache.evictAll()
        FileUtils.thumbnailsDir(context).listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val BUCKET_US = 1_000_000L
        const val MAX_MEMORY_BYTES = 32 * 1024 * 1024
    }
}
