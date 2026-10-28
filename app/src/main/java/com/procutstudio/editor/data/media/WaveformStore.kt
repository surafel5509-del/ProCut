package com.procutstudio.editor.data.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.content.Context
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.util.FileUtils
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.domain.model.MediaAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Waveform peaks for the audio lane.
 *
 * The audio track is decoded to 16-bit PCM with `MediaCodec` and reduced to one peak value per
 * bucket, so a five minute song becomes a few kilobytes of floats. Results are cached on disk; a
 * decode is only ever performed once per file. Decoding is bounded in time and cancellable.
 */
@Singleton
class WaveformStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun peaks(asset: MediaAsset, bucketCount: Int = DEFAULT_BUCKETS): FloatArray? =
        withContext(ioDispatcher) {
            val file = File(asset.uri.removePrefix("file://"))
            if (!file.exists()) return@withContext null
            val cacheFile = File(FileUtils.waveformsDir(context), "${asset.id}_$bucketCount.wave")
            if (cacheFile.exists()) {
                readCache(cacheFile)?.let { return@withContext it }
            }
            val computed = runCatching { computePeaks(file, bucketCount) }
                .onFailure { ProCutLogger.w(ProCutLogger.TAG_MEDIA, "Waveform decode failed for ${asset.displayName}", it) }
                .getOrNull()
            if (computed != null) writeCache(cacheFile, computed)
            computed
        }

    fun clearCache() {
        FileUtils.waveformsDir(context).listFiles()?.forEach { it.delete() }
    }

    private suspend fun computePeaks(file: File, bucketCount: Int): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val samples = ArrayList<Pair<Long, Float>>(2048)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0
            while (!outputDone && guard++ < MAX_CODEC_ITERATIONS) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            var peak = 0
                            while (buffer.remaining() >= 2) {
                                val sample = buffer.short.toInt()
                                val magnitude = if (sample == Short.MIN_VALUE.toInt()) 32768 else kotlin.math.abs(sample)
                                if (magnitude > peak) peak = magnitude
                            }
                            samples.add(info.presentationTimeUs to (peak / 32768f))
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (samples.isEmpty()) return null
            val totalUs = if (durationUs > 0) durationUs else samples.last().first.coerceAtLeast(1L)
            val peaks = FloatArray(bucketCount)
            for ((timeUs, peak) in samples) {
                val bucket = ((timeUs.toDouble() / totalUs.toDouble()) * bucketCount).toInt()
                    .coerceIn(0, bucketCount - 1)
                if (peak > peaks[bucket]) peaks[bucket] = peak
            }
            return peaks
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writeCache(file: File, peaks: FloatArray) {
        runCatching {
            DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
                out.writeInt(peaks.size)
                peaks.forEach { out.writeFloat(it) }
            }
        }
    }

    private fun readCache(file: File): FloatArray? = runCatching {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val count = input.readInt()
            if (count !in 1..MAX_BUCKETS) return null
            FloatArray(count) { input.readFloat() }
        }
    }.getOrNull()

    companion object {
        const val DEFAULT_BUCKETS = 360
        private const val MAX_BUCKETS = 4_000
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_CODEC_ITERATIONS = 200_000
    }
}
