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
    EFFECT("Effects"),
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

/** Groups used by the filter browser (the chip row above the thumbnails). */
@Serializable
enum class FilterCategory(val label: String) {
    FEATURED("Featured"),
    LIFE("Life"),
    SCENERY("Scenery"),
    MOVIE("Movie"),
    PORTRAIT("Portrait"),
    BW("B&W"),
    RETRO("Retro"),
    NIGHT("Night"),
}

/**
 * Built-in looks. Every value maps onto the same grading maths in the GL shader that renders the
 * live preview and the export, and in the CPU mirror that draws the thumbnails (`GradeMath`), so
 * what the thumbnail promises is what the video gets.
 *
 * [shadowTint] / [highlightTint] are optional split-tone colours (ARGB, 0 = none) that push the
 * dark and bright parts of the picture towards a colour without changing their brightness.
 */
@Serializable
enum class FilterPreset(
    val label: String,
    val category: FilterCategory,
    val saturation: Float,
    val contrast: Float,
    val brightness: Float,
    val warmth: Float,
    val hueDegrees: Float,
    val vignette: Float,
    val tint: Float = 0f,
    val fade: Float = 0f,
    val shadowTint: Long = 0L,
    val highlightTint: Long = 0L,
    val grain: Float = 0f,
) {
    NONE("None", FilterCategory.FEATURED, 0f, 0f, 0f, 0f, 0f, 0f),

    // ---- Featured
    VIVID("Vivid", FilterCategory.FEATURED, 35f, 12f, 3f, 5f, 0f, 0f),
    PUNCH("Punch", FilterCategory.FEATURED, 18f, 24f, 0f, 0f, 0f, 0.12f),
    GLOW("Glow", FilterCategory.FEATURED, 14f, -6f, 10f, 8f, 0f, 0.08f, fade = 6f, highlightTint = 0xFFFFD9B0),
    CLEAR("Clear", FilterCategory.FEATURED, 12f, 14f, 5f, -4f, 0f, 0f),
    FRESH("Fresh", FilterCategory.FEATURED, 22f, 6f, 6f, -8f, 0f, 0f, tint = -6f),
    POP("Pop", FilterCategory.FEATURED, 48f, 20f, 2f, 4f, 0f, 0.1f),
    INSTANT("Instant", FilterCategory.FEATURED, 10f, 10f, 4f, 14f, 0f, 0.22f, fade = 10f, grain = 0.15f),

    // ---- Life
    WARM("Warm", FilterCategory.LIFE, 10f, 5f, 2f, 22f, 0f, 0f),
    COOL("Cool", FilterCategory.LIFE, 6f, 8f, 1f, -18f, 0f, 0f),
    FADE("Faded", FilterCategory.LIFE, -28f, -18f, 12f, 0f, 0f, 0.10f, fade = 14f),
    DAILY("Daily", FilterCategory.LIFE, 8f, 6f, 4f, 6f, 0f, 0f),
    COZY("Cozy", FilterCategory.LIFE, 6f, 4f, 2f, 30f, 0f, 0.16f, tint = 4f, fade = 6f),
    CAFE("Cafe", FilterCategory.LIFE, -8f, 14f, -2f, 26f, 4f, 0.24f, fade = 8f, shadowTint = 0xFF5A3A22),
    BRUNCH("Brunch", FilterCategory.LIFE, 24f, 8f, 8f, 14f, 0f, 0f),
    GENTLE("Gentle", FilterCategory.LIFE, -10f, -10f, 10f, 6f, 0f, 0f, fade = 10f),

    // ---- Scenery
    SUNSET("Sunset", FilterCategory.SCENERY, 20f, 8f, 4f, 26f, -10f, 0.14f),
    ALPINE("Alpine", FilterCategory.SCENERY, 16f, 16f, 2f, -14f, 0f, 0.12f, tint = -4f, shadowTint = 0xFF1B3A5C),
    EMERALD("Emerald", FilterCategory.SCENERY, 30f, 12f, 0f, -4f, 0f, 0.14f, tint = -14f, shadowTint = 0xFF0E4D3A),
    OCEAN("Ocean", FilterCategory.SCENERY, 28f, 10f, 2f, -24f, 0f, 0.10f, shadowTint = 0xFF0B4F6C),
    GOLDEN_HOUR("Golden Hour", FilterCategory.SCENERY, 24f, 10f, 6f, 38f, -6f, 0.16f, highlightTint = 0xFFFFC46B),
    AUTUMN("Autumn", FilterCategory.SCENERY, 26f, 12f, 0f, 30f, -8f, 0.2f, shadowTint = 0xFF6B2D12),
    MISTY("Misty", FilterCategory.SCENERY, -18f, -14f, 12f, -6f, 0f, 0.1f, fade = 16f, highlightTint = 0xFFCFE3EE),

    // ---- Movie
    CINEMATIC("Cinematic", FilterCategory.MOVIE, -12f, 20f, -6f, 10f, -4f, 0.30f),
    TEAL_ORANGE("Teal & Orange", FilterCategory.MOVIE, 14f, 18f, -2f, 8f, 0f, 0.22f, shadowTint = 0xFF0F6E7E, highlightTint = 0xFFFF9A4D),
    BLOCKBUSTER("Blockbuster", FilterCategory.MOVIE, 8f, 28f, -6f, -4f, 0f, 0.3f, shadowTint = 0xFF12303F, highlightTint = 0xFFFFB27A),
    ATOMIC("Atomic", FilterCategory.MOVIE, -34f, 24f, -4f, 16f, 0f, 0.4f, fade = 6f, grain = 0.2f),
    SAND_STORM("Sand Storm", FilterCategory.MOVIE, -10f, 18f, 2f, 34f, 0f, 0.3f, tint = 6f, highlightTint = 0xFFFFC98A),
    NEO_GREEN("Neo Green", FilterCategory.MOVIE, -6f, 22f, -8f, -4f, 0f, 0.3f, tint = -34f, shadowTint = 0xFF0D3B1E),
    THRILLER("Thriller", FilterCategory.MOVIE, -40f, 30f, -10f, -10f, 0f, 0.45f, shadowTint = 0xFF0C1B2E),
    WESTERN("Western", FilterCategory.MOVIE, -4f, 20f, 0f, 36f, 0f, 0.35f, fade = 8f, grain = 0.14f),
    CYBER("Cyber", FilterCategory.MOVIE, 40f, 22f, -4f, -6f, 0f, 0.2f, tint = 14f, shadowTint = 0xFF3B0F6E, highlightTint = 0xFF39E6FF),

    // ---- Portrait
    DREAMY("Dreamy", FilterCategory.PORTRAIT, -14f, -8f, 14f, 8f, 0f, 0.16f, fade = 8f),
    PORCELAIN("Porcelain", FilterCategory.PORTRAIT, -12f, -6f, 12f, 2f, 0f, 0.06f, fade = 6f),
    PEACH("Peach", FilterCategory.PORTRAIT, 6f, 2f, 8f, 16f, 0f, 0.08f, tint = 8f, highlightTint = 0xFFFFD2BD),
    ROSY("Rosy", FilterCategory.PORTRAIT, 12f, 0f, 8f, 6f, 0f, 0.1f, tint = 18f),
    BRONZE("Bronze", FilterCategory.PORTRAIT, 4f, 14f, -2f, 30f, 0f, 0.18f, shadowTint = 0xFF5B3A1E),

    // ---- B&W
    MONO("Mono", FilterCategory.BW, -100f, 10f, 0f, 0f, 0f, 0.10f),
    NOIR("Noir", FilterCategory.BW, -100f, 28f, -8f, 0f, 0f, 0.38f),
    SILVER("Silver", FilterCategory.BW, -100f, 4f, 8f, 0f, 0f, 0.1f, fade = 8f),
    GRAPHITE("Graphite", FilterCategory.BW, -100f, 34f, -12f, 0f, 0f, 0.3f, grain = 0.2f),
    BLUE_MONO("Cold Steel", FilterCategory.BW, -100f, 18f, -2f, 0f, 0f, 0.22f, shadowTint = 0xFF1C3552, highlightTint = 0xFFD7E8F5),

    // ---- Retro
    SEPIA("Sepia", FilterCategory.RETRO, -55f, 6f, 4f, 28f, 0f, 0.22f),
    VINTAGE("Vintage", FilterCategory.RETRO, -32f, -8f, -4f, 24f, 8f, 0.30f),
    POLAROID("Polaroid", FilterCategory.RETRO, -10f, -4f, 6f, 16f, 0f, 0.18f, tint = 6f, fade = 16f, grain = 0.1f),
    FILM_80S("Film 80s", FilterCategory.RETRO, 12f, 6f, 2f, 18f, 0f, 0.26f, tint = 12f, fade = 12f, grain = 0.22f),
    LOMO("Lomo", FilterCategory.RETRO, 28f, 30f, -4f, 8f, 0f, 0.55f, grain = 0.14f),
    FADED_70S("Faded 70s", FilterCategory.RETRO, -18f, -10f, 2f, 28f, 6f, 0.2f, fade = 20f, grain = 0.18f),

    // ---- Night
    CITY_NIGHT("City Night", FilterCategory.NIGHT, 14f, 22f, -8f, -8f, 0f, 0.3f, shadowTint = 0xFF14204A, highlightTint = 0xFFFFC38A),
    NEON_NIGHTS("Neon Nights", FilterCategory.NIGHT, 36f, 24f, -6f, -4f, 0f, 0.28f, tint = 16f, shadowTint = 0xFF2A0E5E, highlightTint = 0xFFFF5BD0),
    MOONLIGHT("Moonlight", FilterCategory.NIGHT, -10f, 14f, -10f, -26f, 0f, 0.34f, shadowTint = 0xFF0E2850),
    BLUE_HOUR("Blue Hour", FilterCategory.NIGHT, 8f, 12f, -6f, -30f, 0f, 0.2f, shadowTint = 0xFF1B2F6B, highlightTint = 0xFFB9D4FF),
    ;

    companion object {
        val visualPresets: List<FilterPreset> = entries.filter { it != NONE }

        fun inCategory(category: FilterCategory): List<FilterPreset> =
            entries.filter { it != NONE && it.category == category }
    }
}

// ---------------------------------------------------------------------------------------------
// Effects lane (timeline-range video effects)
// ---------------------------------------------------------------------------------------------

@Serializable
enum class FxCategory(val label: String) {
    TRENDING("Trending"),
    BASIC("Basic"),
    GLITCH("Glitch"),
    RETRO("Retro"),
    NIGHTCLUB("Nightclub"),
    LENS("Lens"),
    NATURE("Nature"),
}

/**
 * Real-time GL effects that can be dropped on the effects lane. Each one is a section of a single
 * fragment shader (see `FxShaderSource`); an effect item simply drives that section's intensity
 * between its start and end, so effects stack and run identically in preview and export.
 */
@Serializable
enum class VideoFxType(val label: String, val category: FxCategory, val emoji: String, val trending: Boolean = false) {
    // Basic
    BLUR("Blur", FxCategory.BASIC, "🌫", trending = true),
    ZOOM_PULSE("Beat Zoom", FxCategory.BASIC, "💥", trending = true),
    SHAKE("Shake", FxCategory.BASIC, "📳"),
    FLASH("Flash", FxCategory.BASIC, "⚡", trending = true),
    SOFT_GLOW("Soft Glow", FxCategory.BASIC, "✨", trending = true),
    MIRROR("Mirror", FxCategory.BASIC, "🪞"),
    KALEIDOSCOPE("Kaleido", FxCategory.BASIC, "🔮"),
    PIXELATE("Pixelate", FxCategory.BASIC, "🟦"),
    INVERT("Invert", FxCategory.BASIC, "🌓"),

    // Glitch
    RGB_SPLIT("RGB Split", FxCategory.GLITCH, "🔴", trending = true),
    GLITCH("Glitch", FxCategory.GLITCH, "👾", trending = true),
    JITTER("Jitter", FxCategory.GLITCH, "〰"),
    DIGITAL_NOISE("Static", FxCategory.GLITCH, "📺"),

    // Retro
    VHS("VHS", FxCategory.RETRO, "📼", trending = true),
    FILM_GRAIN("Film Grain", FxCategory.RETRO, "🎞"),
    OLD_FILM("Old Film", FxCategory.RETRO, "🎬"),
    LIGHT_LEAK("Light Leak", FxCategory.RETRO, "🌅"),

    // Nightclub
    STROBE("Strobe", FxCategory.NIGHTCLUB, "💡"),
    DISCO("Disco", FxCategory.NIGHTCLUB, "🪩", trending = true),
    NEON_EDGE("Neon Edge", FxCategory.NIGHTCLUB, "🟣"),
    HEARTBEAT("Heartbeat", FxCategory.NIGHTCLUB, "💓"),

    // Lens
    FISHEYE("Fisheye", FxCategory.LENS, "🐟"),
    BULGE("Magnify", FxCategory.LENS, "🔍"),
    ZOOM_BLUR("Zoom Blur", FxCategory.LENS, "🌀", trending = true),
    DARK_CORNERS("Dark Corners", FxCategory.LENS, "⚫"),
    TILT_SHIFT("Tilt Shift", FxCategory.LENS, "🏙"),

    // Nature
    SNOW("Snowfall", FxCategory.NATURE, "❄"),
    RAIN("Rain", FxCategory.NATURE, "🌧"),
    SPARKLE("Sparkle", FxCategory.NATURE, "🌟", trending = true),
    RIPPLE("Ripple", FxCategory.NATURE, "💧"),
    WAVE("Wave", FxCategory.NATURE, "🌊"),
    ;

    /** Name of the float uniform that carries this effect's intensity in the shader. */
    val uniformName: String get() = "uFx" + name

    companion object {
        fun inCategory(category: FxCategory): List<VideoFxType> =
            if (category == FxCategory.TRENDING) entries.filter { it.trending } else entries.filter { it.category == category }
    }
}

// ---------------------------------------------------------------------------------------------
// Clip tools
// ---------------------------------------------------------------------------------------------

@Serializable
enum class MaskType(val label: String) {
    NONE("None"),
    LINEAR("Linear"),
    MIRROR("Mirror"),
    CIRCLE("Circle"),
    RECTANGLE("Rectangle"),
    HEART("Heart"),
    STAR("Star"),
}

@Serializable
enum class KeyframeEasing(val label: String) {
    LINEAR("Linear"),
    EASE_IN_OUT("Smooth"),
    EASE_IN("Ease in"),
    EASE_OUT("Ease out"),
    HOLD("Hold"),
}

@Serializable
enum class AnimationGroup(val label: String) { IN("In"), OUT("Out"), COMBO("Combo") }

/**
 * Ready-made motion for a clip. They are evaluated procedurally (see `ClipMotion`) and stack with
 * manual keyframes, so an "In" animation, an "Out" animation and a "Combo" can all run together.
 */
@Serializable
enum class ClipAnimationPreset(val label: String, val group: AnimationGroup) {
    FADE_IN("Fade in", AnimationGroup.IN),
    ZOOM_IN_IN("Zoom in", AnimationGroup.IN),
    SLIDE_IN_LEFT("Slide left", AnimationGroup.IN),
    SLIDE_IN_RIGHT("Slide right", AnimationGroup.IN),
    SLIDE_IN_UP("Slide up", AnimationGroup.IN),
    SPIN_IN("Spin in", AnimationGroup.IN),
    POP_IN("Pop in", AnimationGroup.IN),

    FADE_OUT("Fade out", AnimationGroup.OUT),
    ZOOM_OUT_OUT("Zoom out", AnimationGroup.OUT),
    SLIDE_OUT_LEFT("Slide left", AnimationGroup.OUT),
    SLIDE_OUT_RIGHT("Slide right", AnimationGroup.OUT),
    SLIDE_OUT_DOWN("Slide down", AnimationGroup.OUT),
    SPIN_OUT("Spin out", AnimationGroup.OUT),

    PULSE("Pulse", AnimationGroup.COMBO),
    SWING("Swing", AnimationGroup.COMBO),
    SLOW_ZOOM("Slow zoom", AnimationGroup.COMBO),
    DRIFT("Drift", AnimationGroup.COMBO),
    HEARTBEAT_COMBO("Heartbeat", AnimationGroup.COMBO),
    ;

    companion object {
        fun inGroup(group: AnimationGroup): List<ClipAnimationPreset> = entries.filter { it.group == group }
    }
}
