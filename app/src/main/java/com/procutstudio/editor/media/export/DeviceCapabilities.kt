package com.procutstudio.editor.media.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.ExportResolution
import com.procutstudio.editor.domain.model.FrameRate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the platform which encode configurations are actually available.
 *
 * Export settings are clamped to what the device can encode before a job starts, so a 4K request on
 * a 1080p-max encoder fails with a clear message instead of a mid-render codec error.
 */
@Singleton
class DeviceCapabilities @Inject constructor() {

    private val encoderInfos: List<MediaCodecInfo> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder }
        }.getOrElse {
            ProCutLogger.w(ProCutLogger.TAG_EXPORT, "Unable to query media codecs", it)
            emptyList()
        }
    }

    fun supportsH264Encoding(): Boolean = avcVideoCapabilities.isNotEmpty()

    fun hasAacEncoder(): Boolean = encoderInfos.any { info ->
        info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) }
    }

    fun supportsResolution(width: Int, height: Int, fps: Int): Boolean {
        if (!supportsH264Encoding()) return false
        for (capabilities in avcVideoCapabilities) {
            val supported: Boolean = try {
                capabilities.isSizeAndRateSupported(width, height, fps.toDouble())
            } catch (error: IllegalArgumentException) {
                // Encoders reject frame rates outside their supported range with this exception;
                // fall back to a size-only check instead of failing the whole query.
                capabilities.isSizeSupported(width, height)
            }
            if (supported) return true
        }
        return false
    }

    /** Largest supported export resolution for [aspectRatio], checked smallest to largest. */
    fun bestResolution(aspectRatio: AspectRatio, frameRate: FrameRate): ExportResolution {
        for (resolution in ExportResolution.entries.reversed()) {
            val spec = com.procutstudio.editor.domain.model.ExportSettings(
                resolution = resolution,
                frameRate = frameRate,
            ).toOutputSpec(aspectRatio)
            if (supportsResolution(spec.width, spec.height, spec.fps)) return resolution
        }
        return ExportResolution.HD_720
    }

    fun supportedResolutions(aspectRatio: AspectRatio, frameRate: FrameRate): List<ExportResolution> =
        ExportResolution.entries.filter { resolution ->
            val spec = com.procutstudio.editor.domain.model.ExportSettings(
                resolution = resolution,
                frameRate = frameRate,
            ).toOutputSpec(aspectRatio)
            supportsResolution(spec.width, spec.height, spec.fps)
        }.ifEmpty { listOf(ExportResolution.HD_720) }

    /** H.264 codec capabilities, resolved once; encoders that cannot be queried are skipped. */
    private val avcVideoCapabilities: List<MediaCodecInfo.VideoCapabilities> by lazy {
        val result = mutableListOf<MediaCodecInfo.VideoCapabilities>()
        for (info in encoderInfos) {
            val isAvc: Boolean = info.supportedTypes.any { type: String ->
                type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
            }
            if (!isAvc) continue
            val codecCapabilities: MediaCodecInfo.CodecCapabilities? = try {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (error: IllegalArgumentException) {
                ProCutLogger.w(ProCutLogger.TAG_EXPORT, "Cannot query ${info.name} capabilities", error)
                null
            }
            val video: MediaCodecInfo.VideoCapabilities? = codecCapabilities?.videoCapabilities
            if (video != null) result += video
        }
        result
    }
}
