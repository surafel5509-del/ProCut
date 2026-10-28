package com.procutstudio.editor.media.export.effects

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.procutstudio.editor.domain.model.KenBurnsMotion

/**
 * Slow camera move for still photos.
 *
 * Photos have no motion of their own, so a static image on the timeline can look like a mistake in
 * the finished video. This effect animates a uniform zoom and/or horizontal pan from the first to
 * the last frame of the clip. It runs *after* [ClipGeometryEffect], so it works in the already
 * fitted output frame and never distorts the picture (uniform scale only).
 */
@UnstableApi
class KenBurnsEffect(
    private val motion: KenBurnsMotion,
    private val durationUs: Long,
    private val startZoom: Float = 1.0f,
    private val endZoom: Float = 1.14f,
    private val panFraction: Float = 0.05f,
    /** Effect timestamps include the sequence offset; subtract the clip's timeline start. */
    private val clipStartUs: Long = 0L,
) : MatrixTransformation {

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val matrix = Matrix()
        if (motion == KenBurnsMotion.NONE) return matrix
        val progress = if (durationUs <= 0L) 0f
        else ((presentationTimeUs - clipStartUs).coerceAtLeast(0L).toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)

        when (motion) {
            KenBurnsMotion.ZOOM_IN -> matrix.postScale(lerp(startZoom, endZoom, progress), lerp(startZoom, endZoom, progress))
            KenBurnsMotion.ZOOM_OUT -> matrix.postScale(lerp(endZoom, startZoom, progress), lerp(endZoom, startZoom, progress))
            KenBurnsMotion.PAN_LEFT -> {
                matrix.postScale(endZoom, endZoom)
                matrix.postTranslate(-lerp(panFraction, -panFraction, progress), 0f)
            }
            KenBurnsMotion.PAN_RIGHT -> {
                matrix.postScale(endZoom, endZoom)
                matrix.postTranslate(lerp(-panFraction, panFraction, progress), 0f)
            }
            KenBurnsMotion.NONE -> Unit
        }
        return matrix
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = motion == KenBurnsMotion.NONE

    private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction
}
