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

    fun supportsH264Encoding(): Boolean = videoCapabilities().isNotEmpty()

    fun hasAacEncoder(): Boolean = encoderInfos.any { info ->
        info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) }
    }

    fun supportsResolution(width: Int, height: Int, fps: Int): Boolean {
        if (!supportsH264Encoding()) return false
        return videoCapabilities().any { capabilities ->
            runCatching { capabilities.isSizeAndRateSupported(width, height, fps.toDouble()) }
                .getOrElse { capabilities.isSizeSupported(width, height) }
        }
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

    private fun videoCapabilities(): List<MediaCodecInfo.VideoCapabilities> = encoderInfos.mapNotNull { info ->
        if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) return@mapNotNull null
        runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull()
    }
}
