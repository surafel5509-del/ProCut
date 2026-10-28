package com.procutstudio.editor.ui.editor

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import com.procutstudio.editor.domain.model.ClipTransform
import com.procutstudio.editor.domain.model.FitMode

/**
 * Approximates the export geometry for the live preview.
 *
 * The exporter applies the same values in a GL vertex transform; here they become Compose layer
 * transforms so the preview matches the rendered output without a second GL pipeline.
 */
data class PreviewGeometry(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val rotationZ: Float = 0f,
    val fitMode: FitMode = FitMode.FIT,
) {
    companion object {
        fun from(transform: ClipTransform?): PreviewGeometry {
            if (transform == null) return PreviewGeometry()
            val safe = transform.sanitized()
            val cropWidth = (safe.cropRight - safe.cropLeft).coerceIn(0.02f, 1f)
            val cropHeight = (safe.cropBottom - safe.cropTop).coerceIn(0.02f, 1f)
            val scaleX = (1f / cropWidth) * safe.scale * (if (safe.flipHorizontal) -1f else 1f)
            val scaleY = (1f / cropHeight) * safe.scale * (if (safe.flipVertical) -1f else 1f)
            val cropCenterX = safe.cropLeft + cropWidth / 2f
            val cropCenterY = safe.cropTop + cropHeight / 2f
            return PreviewGeometry(
                scaleX = scaleX,
                scaleY = scaleY,
                translationX = -(cropCenterX - 0.5f) * scaleX + safe.offsetX,
                translationY = -(cropCenterY - 0.5f) * scaleY + safe.offsetY,
                rotationZ = safe.rotationDegrees.toFloat(),
                fitMode = safe.fit,
            )
        }
    }
}

/** Applies [geometry] to a layer, with translations expressed as a fraction of the frame. */
fun Modifier.previewTransform(geometry: PreviewGeometry, frameWidthPx: Float, frameHeightPx: Float): Modifier =
    this.graphicsLayer {
        scaleX = geometry.scaleX
        scaleY = geometry.scaleY
        translationX = geometry.translationX * frameWidthPx
        translationY = geometry.translationY * frameHeightPx
        rotationZ = geometry.rotationZ
        clip = true
    }
