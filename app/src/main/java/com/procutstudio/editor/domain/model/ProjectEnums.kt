package com.procutstudio.editor.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Output frame shapes supported by the editor. */
@Serializable
enum class AspectRatio(val ratio: Float, val label: String, val shortLabel: String) {
    RATIO_16_9(16f / 9f, "Landscape", "16:9"),
    RATIO_9_16(9f / 16f, "Portrait", "9:16"),
    RATIO_1_1(1f, "Square", "1:1"),
    RATIO_4_5(4f / 5f, "Portrait 4:5", "4:5"),
    ;

    /** Returns width for a given output height, rounded to an even number of pixels. */
    fun widthForHeight(height: Int): Int {
        val wide = (height * ratio).toInt()
        return even(wide)
    }

    companion object {
        fun even(value: Int): Int = if (value % 2 == 0) value else value + 1

        fun closestTo(width: Int, height: Int): AspectRatio {
            if (width <= 0 || height <= 0) return RATIO_16_9
            val target = width.toFloat() / height.toFloat()
            return entries.minByOrNull { abs(it.ratio - target) } ?: RATIO_16_9
        }
    }
}

/** Media kinds the importer understands. */
@Serializable
enum class MediaType { VIDEO, IMAGE, AUDIO }

/** Which lane of the timeline an item belongs to. */
@Serializable
enum class TrackType(val displayName: String) {
    VIDEO("Video"),
    OVERLAY("Overlay"),
    TEXT("Text"),
    AUDIO("Audio"),
}

/** How a clip is mapped into the output frame. */
@Serializable
enum class FitMode(val label: String) {
    /** Whole clip visible, letterboxed. */
    FIT("Fit"),

    /** Clip fills the frame, edges cropped. */
    FILL("Fill"),

    /** Clip stretched to the frame, aspect ratio not preserved. */
    STRETCH("Stretch"),
}

@Serializable
enum class ExportResolution(val heightPx: Int, val label: String) {
    HD_720(720, "720p"),
    FHD_1080(1080, "1080p"),
    UHD_4K(2160, "4K"),
    ;

    companion object {
        fun fromLabel(label: String): ExportResolution =
            entries.firstOrNull { it.label == label } ?: FHD_1080
    }
}

@Serializable
enum class FrameRate(val fps: Int) {
    FPS_24(24),
    FPS_30(30),
    FPS_60(60),
    ;

    val label: String get() = "$fps fps"
}

/** Transition into a clip, rendered against the previous clip. */
@Serializable
enum class TransitionType(val label: String) {
    NONE("None"),
    FADE_TO_BLACK("Fade to black"),
    FADE_TO_WHITE("Fade to white"),

    /**
     * Video cross-dissolve needs two clips composited at once. Media3 Transformer 1.5 mixes
     * sequences but does not expose per-input, time-varying alpha to the compositor, so this is
     * rendered as a fast dip through black instead of being advertised as a true dissolve.
     */
    CROSS_DISSOLVE("Dissolve (dip)"),
}

@Serializable
enum class TextPreset(val label: String) {
    TITLE("Title"),
    HEADLINE("Headline"),
    CAPTION("Caption"),
    SUBTITLE("Subtitle"),
    LOWER_THIRD("Lower third"),
    NEON("Neon"),
}

@Serializable
enum class TextAnimationType(val label: String) {
    NONE("None"),
    FADE("Fade"),
    SLIDE_UP("Slide up"),
    SLIDE_LEFT("Slide left"),
    SCALE_IN("Scale in"),
    TYPEWRITER("Typewriter"),
    POP("Pop"),
}

@Serializable
enum class TextAlignOption(val label: String) {
    START("Left"),
    CENTER("Center"),
    END("Right"),
}

@Serializable
enum class FontOption(val label: String) {
    SANS("Sans"),
    SERIF("Serif"),
    MONO("Mono"),
    CURSIVE("Cursive"),
}

@Serializable
enum class CanvasMode(val label: String) {
    /** Solid colour behind letterboxed footage. */
    SOLID("Solid colour"),
}

/**
 * Built-in looks. Every value here maps to a real GL effect (`HslAdjustment`, `RgbAdjustment`,
 * `Brightness`, `Contrast`) plus an optional vignette overlay, and the preview uses exactly the
 * same numbers, so what you see is what gets rendered.
 */
@Serializable
enum class FilterPreset(
    val label: String,
    val saturation: Float,
    val contrast: Float,
    val brightness: Float,
    val warmth: Float,
    val hueDegrees: Float,
    val vignette: Float,
) {
    NONE("None", 0f, 0f, 0f, 0f, 0f, 0f),
    VIVID("Vivid", 35f, 12f, 3f, 5f, 0f, 0f),
    PUNCH("Punch", 18f, 24f, 0f, 0f, 0f, 0.12f),
    MONO("Mono", -100f, 10f, 0f, 0f, 0f, 0.10f),
    NOIR("Noir", -100f, 28f, -8f, 0f, 0f, 0.38f),
    SEPIA("Sepia", -55f, 6f, 4f, 28f, 0f, 0.22f),
    WARM("Warm", 10f, 5f, 2f, 22f, 0f, 0f),
    COOL("Cool", 6f, 8f, 1f, -18f, 0f, 0f),
    FADE("Faded", -28f, -18f, 12f, 0f, 0f, 0.10f),
    CINEMATIC("Cinematic", -12f, 20f, -6f, 10f, -4f, 0.30f),
    VINTAGE("Vintage", -32f, -8f, -4f, 24f, 8f, 0.30f),
    SUNSET("Sunset", 20f, 8f, 4f, 26f, -10f, 0.14f),
    DREAMY("Dreamy", -14f, -8f, 14f, 8f, 0f, 0.16f),
    ;

    companion object {
        val visualPresets: List<FilterPreset> = entries.filter { it != NONE }
    }
}
