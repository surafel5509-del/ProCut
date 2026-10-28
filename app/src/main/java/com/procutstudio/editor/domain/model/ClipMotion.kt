package com.procutstudio.editor.domain.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure maths that turns a clip's keyframes and animation presets into a [MotionState] for a given
 * clip-local time. It is used by the export effects *and* by the preview, so both animate the
 * same way.
 */
object ClipMotion {

    /** Default length of an in/out animation. */
    const val DEFAULT_ANIMATION_US = 600_000L

    /** Full motion of [clip] at [localTimeUs] (time since the clip's first frame on the timeline). */
    fun stateAt(clip: TimelineClip, localTimeUs: Long): MotionState {
        var state = MotionState(opacity = clip.opacity)
        if (clip.keyframes.isNotEmpty()) state += keyframeState(clip.keyframes, localTimeUs)
        val duration = clip.durationUs.coerceAtLeast(1L)
        val animLength = clip.animationDurationUs.coerceIn(100_000L, (duration / 2).coerceAtLeast(100_000L))
        clip.animationIn?.let { state += inState(it, (localTimeUs.toFloat() / animLength).coerceIn(0f, 1f)) }
        clip.animationOut?.let {
            val remaining = (duration - localTimeUs).toFloat() / animLength
            state += outState(it, (1f - remaining).coerceIn(0f, 1f))
        }
        clip.animationCombo?.let {
            state += comboState(it, localTimeUs.toFloat() / duration, localTimeUs / 1_000_000f)
        }
        return state
    }

    /** Interpolated keyframe state; holds the first / last keyframe outside their range. */
    fun keyframeState(keyframes: List<Keyframe>, localTimeUs: Long): MotionState {
        if (keyframes.isEmpty()) return MotionState.Identity
        val sorted = if (keyframes.size < 2) keyframes else keyframes.sortedBy { it.timeUs }
        val first = sorted.first()
        if (localTimeUs <= first.timeUs) return first.state()
        val last = sorted.last()
        if (localTimeUs >= last.timeUs) return last.state()
        var index = 0
        while (index < sorted.size - 1 && sorted[index + 1].timeUs <= localTimeUs) index++
        val from = sorted[index]
        val to = sorted[index + 1]
        val span = (to.timeUs - from.timeUs).coerceAtLeast(1L)
        val raw = (localTimeUs - from.timeUs).toFloat() / span
        val t = ease(from.easing, raw)
        return MotionState(
            scale = lerp(from.scale, to.scale, t),
            offsetX = lerp(from.offsetX, to.offsetX, t),
            offsetY = lerp(from.offsetY, to.offsetY, t),
            rotationDegrees = lerp(from.rotationDegrees, to.rotationDegrees, t),
            opacity = lerp(from.opacity, to.opacity, t),
        )
    }

    fun ease(easing: KeyframeEasing, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (easing) {
            KeyframeEasing.LINEAR -> x
            KeyframeEasing.EASE_IN_OUT -> x * x * (3f - 2f * x)
            KeyframeEasing.EASE_IN -> x * x
            KeyframeEasing.EASE_OUT -> 1f - (1f - x) * (1f - x)
            KeyframeEasing.HOLD -> if (x >= 1f) 1f else 0f
        }
    }

    /** [p] runs 0 → 1 over the animation. */
    private fun inState(preset: ClipAnimationPreset, p: Float): MotionState {
        val e = ease(KeyframeEasing.EASE_OUT, p)
        val remaining = 1f - e
        return when (preset) {
            ClipAnimationPreset.FADE_IN -> MotionState(opacity = e)
            ClipAnimationPreset.ZOOM_IN_IN -> MotionState(scale = lerp(0.6f, 1f, e), opacity = e)
            ClipAnimationPreset.SLIDE_IN_LEFT -> MotionState(offsetX = -remaining, opacity = lerp(0.3f, 1f, e))
            ClipAnimationPreset.SLIDE_IN_RIGHT -> MotionState(offsetX = remaining, opacity = lerp(0.3f, 1f, e))
            ClipAnimationPreset.SLIDE_IN_UP -> MotionState(offsetY = remaining, opacity = lerp(0.3f, 1f, e))
            ClipAnimationPreset.SPIN_IN -> MotionState(scale = lerp(0.3f, 1f, e), rotationDegrees = -180f * remaining, opacity = e)
            ClipAnimationPreset.POP_IN -> {
                // Overshoot then settle.
                val overshoot = 1f + 0.14f * sin(PI.toFloat() * p) * (1f - p)
                MotionState(scale = lerp(0.2f, 1f, e) * overshoot, opacity = (p * 3f).coerceIn(0f, 1f))
            }
            else -> MotionState.Identity
        }
    }

    /** [p] runs 0 → 1 as the clip leaves. */
    private fun outState(preset: ClipAnimationPreset, p: Float): MotionState {
        val e = ease(KeyframeEasing.EASE_IN, p)
        return when (preset) {
            ClipAnimationPreset.FADE_OUT -> MotionState(opacity = 1f - e)
            ClipAnimationPreset.ZOOM_OUT_OUT -> MotionState(scale = lerp(1f, 0.6f, e), opacity = 1f - e)
            ClipAnimationPreset.SLIDE_OUT_LEFT -> MotionState(offsetX = -e, opacity = lerp(1f, 0.3f, e))
            ClipAnimationPreset.SLIDE_OUT_RIGHT -> MotionState(offsetX = e, opacity = lerp(1f, 0.3f, e))
            ClipAnimationPreset.SLIDE_OUT_DOWN -> MotionState(offsetY = e, opacity = lerp(1f, 0.3f, e))
            ClipAnimationPreset.SPIN_OUT -> MotionState(scale = lerp(1f, 0.3f, e), rotationDegrees = 180f * e, opacity = 1f - e)
            else -> MotionState.Identity
        }
    }

    private fun comboState(preset: ClipAnimationPreset, progress: Float, seconds: Float): MotionState {
        val twoPi = (2.0 * PI).toFloat()
        return when (preset) {
            ClipAnimationPreset.PULSE -> MotionState(scale = 1f + 0.06f * (0.5f - 0.5f * kotlin.math.cos(twoPi * seconds * 1.2f)))
            ClipAnimationPreset.SWING -> MotionState(rotationDegrees = 4f * sin(twoPi * seconds * 0.7f))
            ClipAnimationPreset.SLOW_ZOOM -> MotionState(scale = lerp(1f, 1.18f, progress.coerceIn(0f, 1f)))
            ClipAnimationPreset.DRIFT -> MotionState(
                scale = 1.08f,
                offsetX = 0.03f * sin(twoPi * seconds * 0.35f),
                offsetY = 0.02f * sin(twoPi * seconds * 0.5f),
            )
            ClipAnimationPreset.HEARTBEAT_COMBO -> {
                val phase = (seconds * 1.1f) % 1f
                val beat = if (phase < 0.12f) sin(PI.toFloat() * phase / 0.12f) else if (phase in 0.2f..0.32f) 0.6f * sin(PI.toFloat() * (phase - 0.2f) / 0.12f) else 0f
                MotionState(scale = 1f + 0.08f * abs(beat))
            }
            else -> MotionState.Identity
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
