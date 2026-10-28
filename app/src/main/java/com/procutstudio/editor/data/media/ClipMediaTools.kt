package com.procutstudio.editor.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.util.FileUtils
import com.procutstudio.editor.core.util.IdGenerator
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Media-producing helpers behind the pro clip tools:
 *  - full-resolution frame grabs (Freeze frame, manual background remover),
 *  - an offline "reverse" render that writes a real, backwards H.264 file,
 *  - persistence of painted cut-out masks.
 *
 * Everything is real file I/O in the app's private storage; nothing is simulated.
 */
@Singleton
class ClipMediaTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataReader: MediaMetadataReader,
    private val importer: MediaImporter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Full-resolution frame of [asset] at [timeUs] (photos are decoded, videos are seeked exactly). */
    suspend fun extractFrame(asset: MediaAsset, timeUs: Long, maxDimension: Int = 1920): Bitmap? =
        withContext(ioDispatcher) {
            val file = fileOf(asset) ?: return@withContext null
            val raw = if (asset.isImage) {
                decodeSampled(file, maxDimension)
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(timeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (error: Exception) {
                    ProCutLogger.w(ProCutLogger.TAG_MEDIA, "Frame grab failed", error)
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            }
            raw?.let { scaleToFit(it, maxDimension) }
        }

    /** Grabs a frame and stores it as a new photo asset (the still used by Freeze frame). */
    suspend fun createStillAsset(asset: MediaAsset, timeUs: Long): MediaAsset? {
        val frame = extractFrame(asset, timeUs) ?: return null
        return withContext(ioDispatcher) {
            val file = File(FileUtils.mediaDir(context), "freeze_${IdGenerator.assetId()}.jpg")
            val written = runCatching {
                file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }.getOrDefault(false)
            frame.recycle()
            if (!written) return@withContext null
            (importer.importLocalFile(file, MediaType.IMAGE, "Freeze frame") as? ImportResult.Success)?.asset
        }
    }

    /** Saves a painted cut-out (alpha PNG) and returns its file URI. */
    suspend fun saveCutout(mask: Bitmap, clipId: String): String? = withContext(ioDispatcher) {
        val dir = File(context.filesDir, "cutouts").apply { mkdirs() }
        val file = File(dir, "${clipId}_${System.currentTimeMillis()}.png")
        val ok = runCatching { file.outputStream().use { mask.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .getOrDefault(false)
        if (ok) Uri.fromFile(file).toString() else null
    }

    suspend fun loadBitmap(uri: String, maxDimension: Int): Bitmap? = withContext(ioDispatcher) {
        val file = File(uri.removePrefix("file://"))
        if (!file.exists()) null else decodeSampled(file, maxDimension)
    }

    /**
     * Renders `[startUs, endUs)` of [asset] backwards into a new silent MP4 and registers it as an
     * asset. Frames are pulled newest-first from the decoder, drawn onto the encoder's input
     * surface and given sequential timestamps, so the result is an ordinary H.264 file that the
     * preview and the exporter play like any other clip.
     *
     * @return null when the clip is too long, has no readable video or the device encoder failed.
     */
    suspend fun renderReversed(
        asset: MediaAsset,
        startUs: Long,
        endUs: Long,
        onProgress: (Float) -> Unit,
    ): ReverseResult = withContext(ioDispatcher) {
        val source = fileOf(asset) ?: return@withContext ReverseResult.Failure("The source video file is missing.")
        val lengthUs = endUs - startUs
        if (lengthUs > MAX_REVERSE_US) {
            return@withContext ReverseResult.Failure(
                "Reverse works on clips up to ${MAX_REVERSE_US / 1_000_000L} s. Trim the clip first.",
            )
        }
        val output = File(FileUtils.mediaDir(context), "reverse_${IdGenerator.assetId()}.mp4")
        val retriever = MediaMetadataRetriever()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null
        var muxerStarted = false
        try {
            retriever.setDataSource(source.absolutePath)
            val first = retriever.getFrameAtTime(startUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return@withContext ReverseResult.Failure("This video could not be decoded.")
            val fps = (metadataReader.readFrameRate(source) ?: 30).coerceIn(15, 30)
            val (width, height) = evenSize(first.width, first.height, REVERSE_MAX_DIMENSION)
            val frameCount = ((lengthUs * fps) / 1_000_000L).toInt().coerceAtLeast(2)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps / 5).coerceIn(1_500_000, 14_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            inputSurface = surface
            codec.start()
            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val info = MediaCodec.BufferInfo()
            var track = -1
            var written = 0

            fun drain(untilEnd: Boolean) {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000L else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = mux.addTrack(codec.outputFormat)
                            mux.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (buffer != null && info.size > 0 && muxerStarted) {
                                // Canvas-drawn surfaces are stamped with wall-clock time; replace it
                                // with an exact frame-rate grid.
                                info.presentationTimeUs = written * 1_000_000L / fps
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                mux.writeSampleData(track, buffer, info)
                                written++
                            }
                            val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                            if (endOfStream) return
                        }
                    }
                }
            }

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val target = Rect(0, 0, width, height)
            var last: Bitmap = first
            for (i in 0 until frameCount) {
                coroutineContext.ensureActive()
                val timeUs = (endUs - 1L - i * 1_000_000L / fps).coerceAtLeast(startUs)
                val frame = if (i == 0) first else
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: last
                last = frame
                val canvas = try {
                    surface.lockHardwareCanvas()
                } catch (error: Exception) {
                    surface.lockCanvas(null)
                }
                canvas.drawBitmap(frame, null, target, paint)
                surface.unlockCanvasAndPost(canvas)
                drain(untilEnd = false)
                onProgress((i + 1).toFloat() / frameCount)
            }
            codec.signalEndOfInputStream()
            drain(untilEnd = true)
            if (muxerStarted) mux.stop()
            muxerStarted = false
            if (written < 2) {
                FileUtils.deleteQuietly(output)
                return@withContext ReverseResult.Failure("The device encoder produced no frames.")
            }
            when (val imported = importer.importLocalFile(output, MediaType.VIDEO, "${asset.displayName} (reversed)")) {
                is ImportResult.Success -> ReverseResult.Success(imported.asset)
                is ImportResult.Failure -> ReverseResult.Failure(imported.message)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            FileUtils.deleteQuietly(output)
            throw cancel
        } catch (error: Exception) {
            ProCutLogger.e(ProCutLogger.TAG_MEDIA, "Reverse render failed", error)
            FileUtils.deleteQuietly(output)
            ReverseResult.Failure("Reverse failed on this device (${error.javaClass.simpleName}).")
        } finally {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            runCatching { retriever.release() }
        }
    }

    sealed interface ReverseResult {
        data class Success(val asset: MediaAsset) : ReverseResult
        data class Failure(val message: String) : ReverseResult
    }

    // ---------------------------------------------------------------- helpers

    private fun fileOf(asset: MediaAsset): File? {
        val file = File(asset.uri.removePrefix("file://"))
        return if (file.exists()) file else null
    }

    private fun decodeSampled(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scaleToFit(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDimension) return source
        val factor = maxDimension.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun evenSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        val factor = minOf(1f, maxDimension.toFloat() / maxOf(width, height).coerceAtLeast(1))
        val w = ((width * factor).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((height * factor).toInt() / 2 * 2).coerceAtLeast(2)
        return w to h
    }

    companion object {
        const val MAX_REVERSE_US = 20_000_000L
        private const val REVERSE_MAX_DIMENSION = 1280
    }
}
