package com.procutstudio.editor.media

import com.procutstudio.editor.domain.model.OverlayAnimation
import com.procutstudio.editor.domain.model.TextAnimationType
import com.procutstudio.editor.media.render.OverlayAnimationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayAnimationMathTest {

    private val duration = 3_000_000L

    @Test
    fun `fade starts transparent and becomes opaque`() {
        val animation = OverlayAnimation(TextAnimationType.FADE, inDurationUs = 500_000, outDurationUs = 500_000)

        val atStart = OverlayAnimationMath.stateAt(animation, 0L, duration)
        val atMiddle = OverlayAnimationMath.stateAt(animation, duration / 2, duration)
        val atEnd = OverlayAnimationMath.stateAt(animation, duration, duration)

        assertEquals(0f, atStart.alpha, 0.001f)
        assertEquals(1f, atMiddle.alpha, 0.001f)
        assertEquals(0f, atEnd.alpha, 0.001f)
    }

    @Test
    fun `alpha is monotonic during the entry ramp`() {
        val animation = OverlayAnimation(TextAnimationType.FADE, inDurationUs = 800_000, outDurationUs = 0)
        var previous = -1f
        for (step in 0..8) {
            val alpha = OverlayAnimationMath.stateAt(animation, step * 100_000L, duration).alpha
            assertTrue("alpha must not decrease", alpha >= previous - 0.0001f)
            previous = alpha
        }
    }

    @Test
    fun `slide up offsets vertically and settles at zero`() {
        val animation = OverlayAnimation(TextAnimationType.SLIDE_UP, inDurationUs = 600_000, outDurationUs = 0)

        val start = OverlayAnimationMath.stateAt(animation, 0L, duration)
        val settled = OverlayAnimationMath.stateAt(animation, 700_000L, duration)

        assertTrue(start.offsetY > 0f)
        assertEquals(0f, settled.offsetY, 0.001f)
    }

    @Test
    fun `slide left offsets horizontally`() {
        val animation = OverlayAnimation(TextAnimationType.SLIDE_LEFT, inDurationUs = 600_000, outDurationUs = 0)
        val start = OverlayAnimationMath.stateAt(animation, 0L, duration)
        assertTrue(start.offsetX > 0f)
        assertEquals(0f, OverlayAnimationMath.stateAt(animation, 800_000L, duration).offsetX, 0.001f)
    }

    @Test
    fun `scale in grows from below one to one`() {
        val animation = OverlayAnimation(TextAnimationType.SCALE_IN, inDurationUs = 500_000, outDurationUs = 0)

        val start = OverlayAnimationMath.stateAt(animation, 0L, duration)
        val settled = OverlayAnimationMath.stateAt(animation, 600_000L, duration)

        assertTrue(start.scaleFactor < 1f)
        assertEquals(1f, settled.scaleFactor, 0.001f)
    }

    @Test
    fun `pop overshoots then settles at one`() {
        val animation = OverlayAnimation(TextAnimationType.POP, inDurationUs = 400_000, outDurationUs = 0)
        val mid = OverlayAnimationMath.stateAt(animation, 200_000L, duration)
        val settled = OverlayAnimationMath.stateAt(animation, 450_000L, duration)

        assertTrue(mid.scaleFactor > 1f)
        assertEquals(1f, settled.scaleFactor, 0.001f)
    }

    @Test
    fun `typewriter reveals text progressively`() {
        val animation = OverlayAnimation(TextAnimationType.TYPEWRITER, inDurationUs = 1_000_000, outDurationUs = 0)

        val start = OverlayAnimationMath.stateAt(animation, 0L, duration)
        val half = OverlayAnimationMath.stateAt(animation, 500_000L, duration)
        val done = OverlayAnimationMath.stateAt(animation, 1_200_000L, duration)

        assertEquals(0f, start.revealFraction, 0.001f)
        assertTrue(half.revealFraction in 0.2f..0.9f)
        assertEquals(1f, done.revealFraction, 0.001f)
    }

    @Test
    fun `zero duration animation is safe`() {
        val state = OverlayAnimationMath.stateAt(OverlayAnimation(), 0L, 0L)
        assertEquals(1f, state.alpha, 0.001f)
        assertEquals(1f, state.scaleFactor, 0.001f)
    }

    @Test
    fun `no animation stays fully visible`() {
        val animation = OverlayAnimation(TextAnimationType.NONE, inDurationUs = 0, outDurationUs = 0)
        val state = OverlayAnimationMath.stateAt(animation, 1_000_000L, duration)
        assertEquals(1f, state.alpha, 0.001f)
    }
}
