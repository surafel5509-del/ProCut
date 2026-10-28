package com.procutstudio.editor.domain.model

import kotlinx.serialization.Serializable

/** Which container/codec combination an export uses. Only combinations Media3 can mux are listed. */
@Serializable
enum class VideoCodec(val mimeType: String, val label: String) {
    H264("video/avc", "H.264"),
}

@Serializable
enum class AudioCodec(val mimeType: String, val label: String) {
    AAC("audio/mp4a-latm", "AAC"),
}

@Serializable
data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.FHD_1080,
    val frameRate: FrameRate = FrameRate.FPS_30,
    /** 0 means "choose automatically from resolution and frame rate". */
    val videoBitrate: Int = 0,
    val audioBitrate: Int = 192_000,
    val includeAudio: Boolean = true,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val audioCodec: AudioCodec = AudioCodec.AAC,
) {
    fun toOutputSpec(aspectRatio: AspectRatio): OutputSpec {
        val shortSide = resolution.heightPx
        val wide = aspectRatio.ratio >= 1f
        val width: Int
        val height: Int
        if (wide) {
            height = AspectRatio.even(shortSide)
            width = aspectRatio.widthForHeight(height)
        } else {
            width = AspectRatio.even(shortSide)
            height = AspectRatio.even((width / aspectRatio.ratio).toInt())
        }
        val bitrate = if (videoBitrate > 0) videoBitrate else automaticBitrate(width, height, frameRate.fps)
        return OutputSpec(
            width = width,
            height = height,
            fps = frameRate.fps,
            videoBitrate = bitrate,
            audioBitrate = audioBitrate,
            includeAudio = includeAudio,
        )
    }

    companion object {
        /** ~0.1 bits per pixel per frame keeps H.264 output visually clean for phone footage. */
        fun automaticBitrate(width: Int, height: Int, fps: Int): Int {
            val bits = width.toDouble() * height.toDouble() * fps.toDouble() * 0.1
            return bits.coerceIn(2_000_000.0, 45_000_000.0).toInt()
        }
    }
}

/** Fully resolved output parameters handed to the export engine. */
data class OutputSpec(
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val includeAudio: Boolean,
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
    val label: String get() = "$width×$height · $fps fps"
}
