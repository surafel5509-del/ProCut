package com.procutstudio.editor.domain.model

import kotlinx.serialization.Serializable

/**
 * Shape mask applied to a clip. All sizes are expressed in "frame heights" so a circle stays a
 * circle on any aspect ratio; [centerX]/[centerY] are 0..1 positions in the source frame.
 */
@Serializable
data class ClipMask(
    val type: MaskType = MaskType.NONE,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val width: Float = 0.6f,
    val height: Float = 0.6f,
    val rotationDegrees: Float = 0f,
    /** 0 = hard edge, 1 = very soft edge. */
    val feather: Float = 0.1f,
    val invert: Boolean = false,
) {
    val isActive: Boolean get() = type != MaskType.NONE

    fun sanitized(): ClipMask = copy(
        centerX = centerX.coerceIn(0f, 1f),
        centerY = centerY.coerceIn(0f, 1f),
        width = width.coerceIn(0.05f, 2.5f),
        height = height.coerceIn(0.05f, 2.5f),
        rotationDegrees = rotationDegrees.coerceIn(-180f, 180f),
        feather = feather.coerceIn(0f, 1f),
    )
}

/** Green-screen style colour removal. Removed pixels reveal the canvas colour behind the clip. */
@Serializable
data class ChromaKey(
    val enabled: Boolean = false,
    val colorArgb: Long = 0xFF00D84A,
    /** How far from the key colour a pixel may be and still be removed (0..1). */
    val similarity: Float = 0.35f,
    /** Width of the soft transition at the edge of the key (0..1). */
    val smoothness: Float = 0.12f,
    /** Amount of key-colour spill removed from the remaining pixels (0..1). */
    val spill: Float = 0.3f,
) {
    fun sanitized(): ChromaKey = copy(
        similarity = similarity.coerceIn(0f, 1f),
        smoothness = smoothness.coerceIn(0f, 1f),
        spill = spill.coerceIn(0f, 1f),
    )
}

/**
 * One keyframe of a clip. Values are *relative to the clip's own transform*: [scale] and
 * [opacity] multiply it, [offsetX]/[offsetY] (fractions of the frame) and [rotationDegrees] add to
 * it. [timeUs] is measured from the start of the clip on the timeline. [easing] shapes the
 * movement from this keyframe to the next one.
 */
@Serializable
data class Keyframe(
    val timeUs: Long,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    val easing: KeyframeEasing = KeyframeEasing.EASE_IN_OUT,
) {
    fun sanitized(): Keyframe = copy(
        timeUs = timeUs.coerceAtLeast(0L),
        scale = scale.coerceIn(0.05f, 8f),
        offsetX = offsetX.coerceIn(-2f, 2f),
        offsetY = offsetY.coerceIn(-2f, 2f),
        rotationDegrees = rotationDegrees.coerceIn(-720f, 720f),
        opacity = opacity.coerceIn(0f, 1f),
    )

    fun state(): MotionState = MotionState(scale, offsetX, offsetY, rotationDegrees, opacity)
}

/** Evaluated motion of a clip at one instant. Identity = [MotionState.Identity]. */
data class MotionState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
) {
    /** Stacks two motions: scale/opacity multiply, offsets and rotation add. */
    operator fun plus(other: MotionState): MotionState = MotionState(
        scale = scale * other.scale,
        offsetX = offsetX + other.offsetX,
        offsetY = offsetY + other.offsetY,
        rotationDegrees = rotationDegrees + other.rotationDegrees,
        opacity = opacity * other.opacity,
    )

    val isIdentity: Boolean
        get() = scale == 1f && offsetX == 0f && offsetY == 0f && rotationDegrees == 0f && opacity == 1f

    companion object {
        val Identity = MotionState()
    }
}
