package com.procutstudio.editor.media.export.effects

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.procutstudio.editor.domain.model.ClipTransform
import com.procutstudio.editor.domain.model.FitMode
import kotlin.math.max
import kotlin.math.min

/**
 * Maps a decoded clip frame into the export frame: crop, fit/fill, zoom, pan, flip and rotation.
 *
 * Implemented as a [MatrixTransformation], i.e. a vertex-shader transform working in normalised
 * device coordinates. The output frame is always the export size, so every clip in the composition
 * produces frames of the same size and the muxer sees a consistent stream.
 *
 * Matrix order (Android `Matrix.post*` semantics — each call applies *after* the previous one):
 *  1. normalise the crop rectangle to the full [-1, 1] square,
 *  2. scale to fit / fill / stretch the output frame's aspect ratio,
 *  3. apply the user's zoom and flips,
 *  4. rotate,
 *  5. translate by the user's pan offset.
 */
@UnstableApi
class ClipGeometryEffect(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val transform: ClipTransform,
) : MatrixTransformation {

    private var inputWidth = 0
    private var inputHeight = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        return Size(outputWidth, outputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val safe = transform.sanitized()
        val matrix = Matrix()
        val cropWidth = (safe.cropRight - safe.cropLeft).coerceIn(0.02f, 1f)
        val cropHeight = (safe.cropBottom - safe.cropTop).coerceIn(0.02f, 1f)

        // 1. Zoom into the crop rectangle and re-centre it.
        matrix.postScale(1f / cropWidth, 1f / cropHeight)
        val cropCenterX = safe.cropLeft + cropWidth / 2f
        val cropCenterY = safe.cropTop + cropHeight / 2f
        // NDC: x in [-1, 1] left→right, y in [-1, 1] bottom→top.
        val cropCenterNdcX = cropCenterX * 2f - 1f
        val cropCenterNdcY = 1f - cropCenterY * 2f
        matrix.postTranslate(-cropCenterNdcX / cropWidth, -cropCenterNdcY / cropHeight)

        // 2. Fit / fill / stretch into the output frame.
        val sourceAspect = aspectOf(inputWidth, inputHeight) * (cropWidth / cropHeight)
        val outputAspect = aspectOf(outputWidth, outputHeight)
        when (safe.fit) {
            FitMode.STRETCH -> Unit
            FitMode.FIT -> {
                val scale = min(1f, outputAspect / sourceAspect)
                matrix.postScale(scale * sourceAspect / outputAspect, scale)
            }
            FitMode.FILL -> {
                val scale = max(1f, outputAspect / sourceAspect)
                matrix.postScale(scale * sourceAspect / outputAspect, scale)
            }
        }

        // 3. User zoom and flips.
        val flipX = if (safe.flipHorizontal) -1f else 1f
        val flipY = if (safe.flipVertical) -1f else 1f
        matrix.postScale(safe.scale * flipX, safe.scale * flipY)

        // 4. Rotation. NDC has its y axis pointing up, hence the negated angle.
        if (safe.rotationDegrees % 360 != 0) {
            matrix.postRotate(-safe.rotationDegrees.toFloat())
        }

        // 5. Pan (UI y grows downwards, NDC y grows upwards).
        matrix.postTranslate(safe.offsetX, -safe.offsetY)
        return matrix
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        transform.isIdentity && inputWidth == outputWidth && inputHeight == outputHeight

    private fun aspectOf(width: Int, height: Int): Float =
        if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
}
