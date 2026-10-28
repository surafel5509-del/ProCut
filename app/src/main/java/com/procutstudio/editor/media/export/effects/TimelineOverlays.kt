package com.procutstudio.editor.media.export.effects

import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.procutstudio.editor.domain.model.ImageOverlay
import com.procutstudio.editor.domain.model.TextAnimationType
import com.procutstudio.editor.domain.model.TextOverlay
import com.procutstudio.editor.media.render.OverlayAnimationMath
import com.procutstudio.editor.media.render.TextBitmapRenderer

/** Shared behaviour for overlays that only exist inside a timeline window. */
@UnstableApi
abstract class TimedBitmapOverlay(
    protected val windowStartUs: Long,
    protected val windowEndUs: Long,
    protected val outputWidth: Int,
    protected val outputHeight: Int,
    private val transparent: Bitmap,
) : BitmapOverlay() {

    protected fun inWindow(presentationTimeUs: Long): Boolean =
        presentationTimeUs >= windowStartUs && presentationTimeUs < windowEndUs

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (!inWindow(presentationTimeUs)) return transparent
        return bitmapAt(presentationTimeUs - windowStartUs) ?: transparent
    }

    abstract fun bitmapAt(localTimeUs: Long): Bitmap?

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val local = (presentationTimeUs - windowStartUs).coerceAtLeast(0L)
        return settingsAt(local)
    }

    abstract fun settingsAt(localTimeUs: Long): OverlaySettings

    protected fun frameAnchor(xFraction: Float, yFraction: Float): Pair<Float, Float> =
        Pair(xFraction.coerceIn(0f, 1f) * 2f - 1f, 1f - yFraction.coerceIn(0f, 1f) * 2f)

    /**
     * Builds overlay settings from pixel geometry. Overlay scale is relative to the output frame,
     * so converting from pixels keeps preview and export pixel-accurate.
     */
    protected fun baseSettings(
        anchorX: Float,
        anchorY: Float,
        widthPx: Float,
        heightPx: Float,
        alpha: Float,
        rotationDegrees: Float,
    ): OverlaySettings {
        val sx = (widthPx / outputWidth.toFloat()).coerceIn(0.001f, 8f)
        val sy = (heightPx / outputHeight.toFloat()).coerceIn(0.001f, 8f)
        val (ndcX, ndcY) = frameAnchor(anchorX, anchorY)
        return OverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(ndcX, ndcY)
            .setScale(sx, sy)
            .setAlphaScale(alpha.coerceIn(0f, 1f))
            .setRotationDegrees(rotationDegrees)
            .build()
    }
}

/**
 * Burns a text overlay into the exported video.
 *
 * The bitmap comes from [TextBitmapRenderer] — the very same rasteriser the editor preview uses —
 * and the entry/exit animation is driven by [OverlayAnimationMath]. Typewriter frames render the
 * revealed substring on demand and are cached by length.
 */
@UnstableApi
class TextTimelineOverlay(
    private val overlay: TextOverlay,
    private val renderer: TextBitmapRenderer,
    private val transparent: Bitmap,
    outputWidth: Int,
    outputHeight: Int,
) : TimedBitmapOverlay(
    windowStartUs = overlay.startUs,
    windowEndUs = overlay.endUs,
    outputWidth = outputWidth,
    outputHeight = outputHeight,
    transparent = transparent,
) {

    private val typewriterCache = HashMap<Int, Bitmap>()
    private val lock = Any()

    override fun bitmapAt(localTimeUs: Long): Bitmap? {
        if (overlay.text.isBlank()) return null
        val state = animationState(localTimeUs)
        if (state.alpha <= 0.01f) return null
        val content = if (state.revealFraction >= 1f) {
            overlay.text
        } else {
            val visibleChars = (overlay.text.length * state.revealFraction).toInt()
            if (visibleChars <= 0) return null
            overlay.text.take(visibleChars)
        }
        if (overlay.animation.type != TextAnimationType.TYPEWRITER) {
            return renderer.render(content, overlay.style, outputWidth)
        }
        synchronized(lock) {
            return typewriterCache.getOrPut(content.length) {
                renderer.render(content, overlay.style, outputWidth) ?: transparent
            }
        }
    }

    override fun settingsAt(localTimeUs: Long): OverlaySettings {
        val state = animationState(localTimeUs)
        val bitmap = bitmapAt(localTimeUs) ?: transparent
        val style = overlay.style
        return baseSettings(
            anchorX = style.positionX + state.offsetX,
            anchorY = style.positionY + state.offsetY,
            widthPx = bitmap.width * style.scale * state.scaleFactor,
            heightPx = bitmap.height * style.scale * state.scaleFactor,
            alpha = state.alpha,
            rotationDegrees = style.rotationDegrees,
        )
    }

    private fun animationState(localTimeUs: Long) =
        OverlayAnimationMath.stateAt(overlay.animation, localTimeUs, overlay.durationUs)
}

/** Burns a sticker, logo or picture overlay into the exported video. */
@UnstableApi
class ImageTimelineOverlay(
    private val overlay: ImageOverlay,
    private val bitmap: Bitmap,
    private val transparent: Bitmap,
    outputWidth: Int,
    outputHeight: Int,
) : TimedBitmapOverlay(
    windowStartUs = overlay.startUs,
    windowEndUs = overlay.endUs,
    outputWidth = outputWidth,
    outputHeight = outputHeight,
    transparent = transparent,
) {

    override fun bitmapAt(localTimeUs: Long): Bitmap = bitmap

    override fun settingsAt(localTimeUs: Long): OverlaySettings {
        val state = OverlayAnimationMath.stateAt(overlay.animation, localTimeUs, overlay.durationUs)
        val widthPx = outputWidth * overlay.scale
        val heightPx = widthPx * bitmap.height / bitmap.width.toFloat()
        return baseSettings(
            anchorX = overlay.positionX + state.offsetX,
            anchorY = overlay.positionY + state.offsetY,
            widthPx = widthPx * state.scaleFactor,
            heightPx = heightPx * state.scaleFactor,
            alpha = overlay.opacity * state.alpha,
            rotationDegrees = overlay.rotationDegrees,
        )
    }
}

/**
 * Full-frame colour curtain used for fades and transitions.
 *
 * A clip fades in by starting fully covered and ramping the curtain alpha to zero; the same overlay
 * covers the tail for fade-out and dip transitions. Timestamps are clip-relative and arrive after
 * the speed effect, so the envelope always matches what the user sees on the timeline.
 */
@UnstableApi
class CurtainOverlay(
    private val bits: Bitmap,
    private val clipDurationUs: Long,
    private val headUs: Long,
    private val tailUs: Long,
) : BitmapOverlay() {

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bits

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        var alpha = 0f
        if (headUs > 0L && presentationTimeUs < headUs) {
            alpha = 1f - (presentationTimeUs.toFloat() / headUs).coerceIn(0f, 1f)
        }
        if (tailUs > 0L) {
            val remaining = clipDurationUs - presentationTimeUs
            if (remaining < tailUs) {
                alpha = maxOf(alpha, 1f - (remaining.toFloat() / tailUs).coerceIn(0f, 1f))
            }
        }
        return OverlaySettings.Builder()
            .setScale(1f, 1f)
            .setAlphaScale(alpha.coerceIn(0f, 1f))
            .build()
    }
}
