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
    val alpha: Float = 1f,
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
                translationX = -(cropCenterX - 0.5f) * scaleX + safe.offsetX * 0.5f,
                translationY = -(cropCenterY - 0.5f) * scaleY + safe.offsetY * 0.5f,
                rotationZ = safe.rotationDegrees.toFloat(),
                fitMode = safe.fit,
            )
        }

        /**
         * Adds a keyframe / animation [motion] on top of [base]. Offsets use the exporter's
         * convention (1.0 = half a frame), hence the 0.5 factor into frame fractions.
         */
        fun withMotion(base: PreviewGeometry, motion: com.procutstudio.editor.domain.model.MotionState): PreviewGeometry =
            base.copy(
                scaleX = base.scaleX * motion.scale,
                scaleY = base.scaleY * motion.scale,
                translationX = base.translationX + motion.offsetX * 0.5f,
                translationY = base.translationY + motion.offsetY * 0.5f,
                rotationZ = base.rotationZ + motion.rotationDegrees,
                alpha = base.alpha * motion.opacity.coerceIn(0f, 1f),
            )
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
        alpha = geometry.alpha
        clip = true
    }
