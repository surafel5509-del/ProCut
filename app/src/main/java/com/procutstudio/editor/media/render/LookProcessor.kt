package com.procutstudio.editor.media.render

import android.graphics.Bitmap
import com.procutstudio.editor.domain.model.ChromaKey
import com.procutstudio.editor.domain.model.ClipMask
import com.procutstudio.editor.domain.model.VideoEffect

/**
 * CPU renderer of the clip look. Photos in the live preview and the filter thumbnails go through
 * here; videos use the GL shader. Both consume the same [GradeParams] and [GradeMath], so a filter
 * thumbnail, the photo preview and the exported frame agree.
 */
object LookProcessor {

    /** Grades a thumbnail-sized [source] with [effect] (filters panel). */
    fun graded(source: Bitmap, effect: VideoEffect): Bitmap =
        process(source, GradeParams.from(effect), ClipMask(), ChromaKey(), null, 0xFF000000)

    /**
     * Full look: grade, mask, chroma key and painted cut-out. Transparent areas are mixed towards
     * [canvasArgb] so the result is opaque, matching the GL shader's output.
     *
     * @param cutout optional alpha mask (any size; it is scaled to [source]).
     * @param checkCancelled invoked once per row so a superseded render can stop early.
     */
    fun process(
        source: Bitmap,
        grade: GradeParams,
        mask: ClipMask,
        chroma: ChromaKey,
        cutout: Bitmap?,
        canvasArgb: Long,
        checkCancelled: () -> Unit = {},
    ): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val cutoutAlpha: IntArray? = cutout?.let {
            val scaled = if (it.width == width && it.height == height) it else Bitmap.createScaledBitmap(it, width, height, true)
            IntArray(width * height).also { out -> scaled.getPixels(out, 0, width, 0, 0, width, height) }
        }
        val canvasR = ((canvasArgb shr 16) and 0xFF) / 255f
        val canvasG = ((canvasArgb shr 8) and 0xFF) / 255f
        val canvasB = (canvasArgb and 0xFF) / 255f
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val rgb = FloatArray(3)
        val usesMask = mask.isActive
        val usesChroma = chroma.enabled

        for (y in 0 until height) {
            checkCancelled()
            val v = (y + 0.5f) / height
            for (x in 0 until width) {
                val index = y * width + x
                val argb = pixels[index]
                val u = (x + 0.5f) / width
                var alpha = ((argb ushr 24) and 0xFF) / 255f
                rgb[0] = ((argb shr 16) and 0xFF) / 255f
                rgb[1] = ((argb shr 8) and 0xFF) / 255f
                rgb[2] = (argb and 0xFF) / 255f

                if (cutoutAlpha != null) alpha *= ((cutoutAlpha[index] ushr 24) and 0xFF) / 255f
                if (usesChroma) alpha *= GradeMath.chromaAlpha(chroma, rgb)
                if (usesMask) alpha *= GradeMath.maskAlpha(mask, u, v, aspect)

                GradeMath.gradePixel(grade, rgb[0], rgb[1], rgb[2], u, v, rgb)

                val r = canvasR + (rgb[0] - canvasR) * alpha
                val g = canvasG + (rgb[1] - canvasG) * alpha
                val b = canvasB + (rgb[2] - canvasB) * alpha
                pixels[index] = (0xFF shl 24) or
                    ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (b * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
}
