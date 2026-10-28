package com.procutstudio.editor.media.render

import com.procutstudio.editor.domain.model.OverlayAnimation
import com.procutstudio.editor.domain.model.TextAnimationType
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The single implementation of overlay entry/exit animation.
 *
 * Both the export overlay (`BitmapOverlay`) and the Compose preview call this, so an animation that
 * looks right in the editor renders identically in the exported file.
 */
object OverlayAnimationMath {

    data class State(
        val alpha: Float = 1f,
        val scaleFactor: Float = 1f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        /** 0..1 progress used by the typewriter animation. */
        val revealFraction: Float = 1f,
    )

    fun stateAt(
        animation: OverlayAnimation,
        localTimeUs: Long,
        durationUs: Long,
    ): State {
        if (durationUs <= 0L) return State()
        val inUs = animation.inDurationUs.coerceIn(0L, durationUs / 2)
        val outUs = animation.outDurationUs.coerceIn(0L, durationUs / 2)
        val inProgress = if (inUs > 0L) (localTimeUs.toFloat() / inUs).coerceIn(0f, 1f) else 1f
        val outProgress = if (outUs > 0L) {
            ((durationUs - localTimeUs).toFloat() / outUs).coerceIn(0f, 1f)
        } else {
            1f
        }
        val entry = easeOutCubic(inProgress)
        val exit = easeOutCubic(outProgress)

        return when (animation.type) {
            TextAnimationType.NONE -> State(alpha = exit)

            TextAnimationType.FADE -> State(alpha = min(entry, exit))

            TextAnimationType.SLIDE_UP -> State(
                alpha = min(entry, exit),
                offsetY = 0.10f * (1f - entry),
            )

            TextAnimationType.SLIDE_LEFT -> State(
                alpha = min(entry, exit),
                offsetX = 0.14f * (1f - entry),
            )

            TextAnimationType.SCALE_IN -> State(
                alpha = min(entry, exit),
                scaleFactor = 0.55f + 0.45f * entry,
            )

            TextAnimationType.POP -> State(
                alpha = min(entry, exit),
                scaleFactor = popScale(entry),
            )

            TextAnimationType.TYPEWRITER -> State(
                alpha = exit,
                revealFraction = entry.coerceIn(0f, 1f),
            )
        }
    }

    private fun popScale(progress: Float): Float {
        // Overshoot once, then settle at 1.
        val overshoot = 0.16f * sin((PI * progress).toFloat())
        return if (progress >= 1f) 1f else 0.82f + 0.18f * progress + overshoot
    }

    private fun easeOutCubic(value: Float): Float {
        val inverted = 1f - value.coerceIn(0f, 1f)
        return 1f - inverted * inverted * inverted
    }
}
