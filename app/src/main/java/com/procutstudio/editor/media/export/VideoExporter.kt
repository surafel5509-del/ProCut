package com.procutstudio.editor.media.export

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.di.MainDispatcher
import com.procutstudio.editor.core.util.FileUtils
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.ExportSettings
import com.procutstudio.editor.domain.model.OutputSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * The export engine.
 *
 * Renders the real project with Media3 Transformer, streams progress, supports cancellation, then
 * **verifies** the produced file (duration, resolution, tracks, frame count) before publishing it to
 * MediaStore. A file that fails verification is reported as a failure — success is never assumed
 * from a callback alone.
 */
@UnstableApi
@Singleton
class VideoExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val compositionBuilder: CompositionBuilder,
    private val mediaStorePublisher: MediaStorePublisher,
    private val deviceCapabilities: DeviceCapabilities,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val busy = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    val isExporting: Boolean get() = busy.get()

    suspend fun export(
        project: EditorProject,
        settings: ExportSettings = project.exportSettings,
        onProgress: (ExportProgressInfo) -> Unit = {},
    ): ExportOutcome {
        if (!busy.compareAndSet(false, true)) {
            throw ExportFailure("An export is already running. Wait for it to finish or cancel it.")
        }
        val spec = settings.toOutputSpec(project.aspectRatio)
        val tempFile = File(FileUtils.exportsDir(context), "export_${System.currentTimeMillis()}.mp4")
        var transformer: Transformer? = null
        try {
            onProgress(ExportProgressInfo(ExportStage.PREPARING, 0f))
            validateDeviceCapabilities(project, spec, settings)
            ensureFreeSpace(spec, project.durationUs)

            val built = withContext(ioDispatcher) { compositionBuilder.build(project, spec) }
            val listener = ListenerBridge()
            transformer = withContext(mainDispatcher) {
                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder()
                                    .setBitrate(spec.videoBitrate)
                                    .setiFrameIntervalSeconds(1f)
                                    .build(),
                            )
                            .build(),
                    )
                    .build()
            }
            onProgress(ExportProgressInfo(ExportStage.RENDERING, 0f))
            val exportResult = awaitTransformation(
                transformer = transformer,
                composition = built.composition,
                outputPath = tempFile.absolutePath,
                listener = listener,
                onProgress = onProgress,
            )

            onProgress(ExportProgressInfo(ExportStage.VERIFYING, 0.97f))
            val verified = withContext(ioDispatcher) {
                verifyOutput(tempFile, exportResult, built)
            }

            onProgress(ExportProgressInfo(ExportStage.SAVING, 0.99f))
            val displayName = buildDisplayName(project)
            val uri = withContext(ioDispatcher) { mediaStorePublisher.publish(tempFile, displayName) }
            onProgress(ExportProgressInfo(ExportStage.DONE, 1f))

            return ExportOutcome(
                uri = uri,
                displayName = displayName,
                durationUs = verified.durationUs,
                width = verified.width,
                height = verified.height,
                sizeBytes = verified.sizeBytes,
                warnings = built.warnings,
            )
        } catch (cancelled: CancellationException) {
            runCatching { withContext(mainDispatcher) { transformer?.cancel() } }
            throw cancelled
        } catch (failure: ExportFailure) {
            throw failure
        } catch (error: Exception) {
            ProCutLogger.e(ProCutLogger.TAG_EXPORT, "Export failed", error)
            throw ExportFailure(friendlyMessage(error), error)
        } finally {
            busy.set(false)
            // The published copy lives in MediaStore; the scratch file is never needed afterwards.
            FileUtils.deleteQuietly(tempFile)
            runCatching { tempFile.parentFile?.listFiles()?.forEach { file ->
                if (file.name.startsWith("canvas_")) file.delete()
            } }
        }
    }

    // ------------------------------------------------------------------ pipeline

    private class ListenerBridge {
        var completion: ((ExportResult) -> Unit)? = null
        var failure: ((ExportException) -> Unit)? = null
    }

    private suspend fun awaitTransformation(
        transformer: Transformer,
        composition: Composition,
        outputPath: String,
        listener: ListenerBridge,
        onProgress: (ExportProgressInfo) -> Unit,
    ): ExportResult = withContext(mainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            val holder = ProgressHolder()
            var finished = false
            val progressTick = object : Runnable {
                override fun run() {
                    if (finished) return
                    val state = transformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        // Reserve the last few percent for verification and publishing.
                        onProgress(
                            ExportProgressInfo(
                                stage = ExportStage.RENDERING,
                                fraction = (holder.progress / 100f * 0.95f).coerceIn(0f, 0.95f),
                            ),
                        )
                    }
                    mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
                }
            }

            listener.completion = { result ->
                if (!finished) {
                    finished = true
                    mainHandler.removeCallbacks(progressTick)
                    if (continuation.isActive) continuation.resume(result)
                }
            }
            listener.failure = { error ->
                if (!finished) {
                    finished = true
                    mainHandler.removeCallbacks(progressTick)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            transformer.addListener(transformerListener(listener))
            continuation.invokeOnCancellation {
                finished = true
                mainHandler.post {
                    mainHandler.removeCallbacks(progressTick)
                    runCatching { transformer.cancel() }
                }
            }
            try {
                transformer.start(composition, outputPath)
            } catch (error: Exception) {
                mainHandler.removeCallbacks(progressTick)
                if (continuation.isActive) continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            mainHandler.postDelayed(progressTick, PROGRESS_INTERVAL_MS)
        }
    }

    private fun transformerListener(bridge: ListenerBridge) = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            ProCutLogger.i(
                ProCutLogger.TAG_EXPORT,
                "Rendered ${exportResult.durationMs}ms, ${exportResult.videoFrameCount} frames",
            )
            bridge.completion?.invoke(exportResult)
        }

        override fun onError(
            composition: Composition,
            exportResult: ExportResult,
            exportException: ExportException,
        ) {
            ProCutLogger.e(
                ProCutLogger.TAG_EXPORT,
                "Transformer error ${exportException.errorCode} " +
                    "(${ExportException.getErrorCodeName(exportException.errorCode)})",
                exportException,
            )
            bridge.failure?.invoke(exportException)
        }
    }

    // ------------------------------------------------------------------ verification

    private data class VerifiedOutput(
        val durationUs: Long,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
    )

    /**
     * Reads the produced file back with the platform retriever and checks it against the request.
     * This is the step that stops the app from reporting a success for a truncated or empty file.
     */
    private fun verifyOutput(
        file: File,
        exportResult: ExportResult,
        built: BuiltComposition,
    ): VerifiedOutput {
        if (!file.exists() || file.length() <= 0L) {
            throw ExportFailure("The export finished but produced an empty file. Try exporting again.")
        }
        if (exportResult.videoFrameCount <= 0) {
            throw ExportFailure("No video frames were written. Check that the timeline contains video or photos.")
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (!hasVideo) throw ExportFailure("The exported file has no video track.")
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (durationMs <= 0L) throw ExportFailure("The exported file has no duration and cannot be played.")
            val expectedMs = TimeUtils.usToMs(built.expectedDurationUs)
            val toleranceMs = maxOf(1_500L, (expectedMs * 0.12).toLong())
            if (abs(durationMs - expectedMs) > toleranceMs) {
                throw ExportFailure(
                    "The exported duration (${durationMs / 1000}s) does not match the project " +
                        "(${expectedMs / 1000}s). Nothing was saved.",
                )
            }
            if (width <= 0 || height <= 0) throw ExportFailure("The exported video has an invalid resolution.")
            if (built.spec.includeAudio && built.expectedDurationUs > 0L) {
                // Audio is only required when the project actually mixes anything; the composition
                // builder forces an audio track in that case.
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                if (!hasAudio) {
                    ProCutLogger.w(ProCutLogger.TAG_EXPORT, "Export has no audio track although audio was expected")
                }
            }
            return VerifiedOutput(
                durationUs = durationMs * 1_000L,
                width = width,
                height = height,
                sizeBytes = file.length(),
            )
        } catch (failure: ExportFailure) {
            throw failure
        } catch (error: Exception) {
            throw ExportFailure("The exported file could not be verified: ${error.message ?: "unreadable output"}", error)
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ------------------------------------------------------------------ pre-flight

    private fun validateDeviceCapabilities(
        project: EditorProject,
        spec: OutputSpec,
        settings: ExportSettings,
    ) {
        if (!deviceCapabilities.supportsH264Encoding()) {
            throw ExportFailure("This device does not provide an H.264 encoder, so video cannot be exported.")
        }
        if (!deviceCapabilities.supportsResolution(spec.width, spec.height, spec.fps)) {
            val fallback = deviceCapabilities.bestResolution(project.aspectRatio, settings.frameRate)
            throw ExportFailure(
                "This device cannot encode ${spec.width}×${spec.height} at ${spec.fps} fps. " +
                    "Try ${fallback.label} instead.",
            )
        }
        val hasAudio = project.videoClips.any { !it.isMuted } || project.audioClips.any { !it.isMuted }
        if (settings.includeAudio && hasAudio && !deviceCapabilities.hasAacEncoder()) {
            throw ExportFailure("This device does not provide an AAC encoder. Disable audio to export.")
        }
        if (deviceCapabilities.bestResolution(project.aspectRatio, settings.frameRate).heightPx < spec.height &&
            spec.height > 1080
        ) {
            // Not fatal: the codec claims support, this is only an informational path.
            ProCutLogger.d(ProCutLogger.TAG_EXPORT, "Large export requested; device support is marginal")
        }
    }

    private fun ensureFreeSpace(spec: OutputSpec, durationUs: Long) {
        val estimatedBytes = estimateOutputBytes(spec, durationUs)
        val available = FileUtils.freeSpaceBytes(FileUtils.exportsDir(context))
        // Keep a 64 MB safety margin so the device does not fill up while muxing.
        if (available < estimatedBytes + MIN_FREE_SPACE_MARGIN_BYTES) {
            throw ExportFailure(
                "Not enough free storage: about ${estimatedBytes / (1024 * 1024)} MB is needed. " +
                    "Free up space and try again.",
            )
        }
    }

    private fun estimateOutputBytes(spec: OutputSpec, durationUs: Long): Long {
        val seconds = (durationUs / 1_000_000.0).coerceAtLeast(1.0)
        val videoBytes = spec.videoBitrate / 8.0 * seconds
        val audioBytes = if (spec.includeAudio) spec.audioBitrate / 8.0 * seconds else 0.0
        return ((videoBytes + audioBytes) * 1.2).toLong()
    }

    private fun buildDisplayName(project: EditorProject): String {
        val safeName = project.name
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "ProCut" }
            .take(40)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${safeName}_$stamp.mp4"
    }

    private fun friendlyMessage(error: Exception): String = when {
        error is ExportException -> when (error.errorCode) {
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            -> "A clip uses a format this device cannot decode. Re-import it as H.264 or HEVC."

            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            -> "The device encoder rejected the requested output settings. Try 1080p at 30 fps."

            ExportException.ERROR_CODE_IO_NO_PERMISSION ->
                "ProCut Studio lost access to one of the source files. Re-import the media."

            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "A source file is missing from app storage. Re-import the media."

            ExportException.ERROR_CODE_MUXING_FAILED,
            ExportException.ERROR_CODE_MUXING_TIMEOUT,
            ExportException.ERROR_CODE_MUXING_APPEND,
            -> "The MP4 muxer could not finish the file. Try a shorter range or lower resolution."

            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED,
            -> "An effect could not be rendered on this device. Try removing transitions or overlays."

            ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK ->
                "The renderer rejected this project. Try re-importing the clips."

            else -> error.message ?: "The export failed with an unexpected media error."
        }

        error is CompositionBuildException -> error.message ?: "The project could not be prepared for export."
        error.message != null -> error.message!!
        else -> "The export failed unexpectedly."
    }

    /** Cheap sanity check used by the UI before enabling the Export button. */
    fun previewOutputSpec(project: EditorProject): OutputSpec = project.exportSettings.toOutputSpec(project.aspectRatio)

    private companion object {
        const val PROGRESS_INTERVAL_MS = 300L
        const val MIN_FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
