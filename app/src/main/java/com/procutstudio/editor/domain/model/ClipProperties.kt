package com.procutstudio.editor.domain.model

import kotlinx.serialization.Serializable

/** Camera move applied to a still photo over its clip duration. */
@Serializable
enum class KenBurnsMotion(val label: String) {
    NONE("None"),
    ZOOM_IN("Zoom in"),
    ZOOM_OUT("Zoom out"),
    PAN_LEFT("Pan left"),
    PAN_RIGHT("Pan right"),
}

/**
 * Geometry of a clip inside the output frame.
 *
 * All values are normalised so they are resolution independent:
 *  - [scale], [offsetX] / [offsetY] are relative to the output frame (offsets run -1..1),
 *  - the crop rectangle is expressed in 0..1 source coordinates.
 */
@Serializable
data class ClipTransform(
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val fit: FitMode = FitMode.FIT,
    /** Slow motion applied to still photos so they do not look frozen in the exported video. */
    val kenBurns: KenBurnsMotion = KenBurnsMotion.NONE,
) {
    val isIdentity: Boolean
        get() = rotationDegrees % 360 == 0 && !flipHorizontal && !flipVertical &&
            scale == 1f && offsetX == 0f && offsetY == 0f && isFullFrameCrop

    val isFullFrameCrop: Boolean
        get() = cropLeft <= 0.0001f && cropTop <= 0.0001f &&
            cropRight >= 0.9999f && cropBottom >= 0.9999f

    val croppedAspectMultiplier: Float
        get() {
            val w = (cropRight - cropLeft).coerceIn(0.05f, 1f)
            val h = (cropBottom - cropTop).coerceIn(0.05f, 1f)
            return w / h
        }

    fun rotatedClockwise(): ClipTransform = copy(rotationDegrees = ((rotationDegrees / 90 + 1) % 4) * 90)

    fun sanitized(): ClipTransform {
        val left = cropLeft.coerceIn(0f, 0.98f)
        val top = cropTop.coerceIn(0f, 0.98f)
        val right = cropRight.coerceIn(left + 0.02f, 1f)
        val bottom = cropBottom.coerceIn(top + 0.02f, 1f)
        return copy(
            rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
            scale = scale.coerceIn(0.2f, 5f),
            offsetX = offsetX.coerceIn(-2f, 2f),
            offsetY = offsetY.coerceIn(-2f, 2f),
            cropLeft = left,
            cropTop = top,
            cropRight = right,
            cropBottom = bottom,
        )
    }

    companion object {
        val Default = ClipTransform()
    }
}

/**
 * Colour grade of a clip: a preset plus manual adjustments. Manual values are offsets on top of
 * the preset so users can start from a look and fine tune it; [filterIntensity] scales the preset
 * part only (the intensity slider of the filter browser).
 *
 * Values are in the units the UI shows (-100..100). The grading maths lives in `GradeMath`
 * (CPU, thumbnails / photo preview) and the clip GL shader (preview + export).
 */
@Serializable
data class VideoEffect(
    val filter: FilterPreset = FilterPreset.NONE,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val hueDegrees: Float = 0f,
    val vignette: Float = 0f,
    val filterIntensity: Float = 1f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val fade: Float = 0f,
    val sharpen: Float = 0f,
    val grain: Float = 0f,
) {
    val hasManualAdjustments: Boolean
        get() = brightness != 0f || contrast != 0f || saturation != 0f ||
            warmth != 0f || hueDegrees != 0f || vignette != 0f || tint != 0f ||
            highlights != 0f || shadows != 0f || fade != 0f || sharpen != 0f || grain != 0f

    val isNeutral: Boolean get() = (filter == FilterPreset.NONE || filterIntensity <= 0f) && !hasManualAdjustments

    private val presetMix: Float get() = filterIntensity.coerceIn(0f, 1f)

    val effectiveSaturation: Float
        get() = (saturation + filter.saturation * presetMix).coerceIn(-100f, 100f)

    val effectiveContrast: Float
        get() = (contrast + filter.contrast * presetMix).coerceIn(-100f, 100f)

    val effectiveBrightness: Float
        get() = (brightness + filter.brightness * presetMix).coerceIn(-100f, 100f)

    val effectiveWarmth: Float
        get() = (warmth + filter.warmth * presetMix).coerceIn(-100f, 100f)

    val effectiveHue: Float
        get() = (hueDegrees + filter.hueDegrees * presetMix).coerceIn(-180f, 180f)

    val effectiveVignette: Float
        get() = (vignette + filter.vignette * presetMix).coerceIn(0f, 1f)

    val effectiveTint: Float
        get() = (tint + filter.tint * presetMix).coerceIn(-100f, 100f)

    /** 0..1 */
    val effectiveFade: Float
        get() = ((fade + filter.fade * presetMix) / 100f).coerceIn(0f, 1f)

    /** 0..1 */
    val effectiveGrain: Float
        get() = (grain + filter.grain * presetMix).coerceIn(0f, 1f)

    /** 0..1 */
    val effectiveSharpen: Float get() = sharpen.coerceIn(0f, 1f)

    companion object {
        val Neutral = VideoEffect()
    }
}

/** Transition applied at the head of a clip, rendered against whatever came before it. */
@Serializable
data class TransitionConfig(
    val type: TransitionType = TransitionType.NONE,
    val durationUs: Long = 600_000L,
) {
    val isNone: Boolean get() = type == TransitionType.NONE
}

/** Entry/exit animation for text and sticker overlays. */
@Serializable
data class OverlayAnimation(
    val type: TextAnimationType = TextAnimationType.NONE,
    val inDurationUs: Long = 400_000L,
    val outDurationUs: Long = 300_000L,
)
