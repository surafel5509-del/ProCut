package com.procutstudio.editor.media.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.procutstudio.editor.domain.model.ChromaKey
import com.procutstudio.editor.domain.model.ClipMask
import com.procutstudio.editor.domain.model.ClipMotion
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.media.render.GradeParams

/**
 * Everything the clip shader needs, frozen at the moment the effect is created so the GL thread
 * never touches mutable editor state.
 */
class ClipLookSpec(
    val clip: TimelineClip,
    val grade: GradeParams,
    val mask: ClipMask,
    val chroma: ChromaKey,
    val cutoutPath: String?,
    val canvasRgb: FloatArray,
    /** Timeline start of the clip: effect timestamps include the sequence offset. */
    val clipStartUs: Long,
    /**
     * Export applies clip opacity (static, keyframed, animated) inside the shader. The live
     * preview leaves it to a Compose layer alpha instead, so the player surface can fade.
     */
    val includeMotionOpacity: Boolean,
) {
    companion object {
        fun of(clip: TimelineClip, canvasArgb: Long, clipStartUs: Long, includeMotionOpacity: Boolean): ClipLookSpec =
            ClipLookSpec(
                clip = clip,
                grade = GradeParams.from(clip.effects),
                mask = clip.mask.sanitized(),
                chroma = clip.chromaKey.sanitized(),
                cutoutPath = clip.cutoutUri?.let { runCatching { Uri.parse(it).path }.getOrNull() },
                canvasRgb = floatArrayOf(
                    Color.red(canvasArgb.toInt()) / 255f,
                    Color.green(canvasArgb.toInt()) / 255f,
                    Color.blue(canvasArgb.toInt()) / 255f,
                ),
                clipStartUs = clipStartUs,
                includeMotionOpacity = includeMotionOpacity,
            )
    }
}

/**
 * Per-clip GL pass: colour grade (filter + adjustments), shape mask, chroma key, painted cut-out
 * and opacity — one shader, identical in the live preview and in the export.
 */
@UnstableApi
class ClipShaderEffect(private val spec: ClipLookSpec) : GlEffect {

    constructor(
        clip: TimelineClip,
        canvasArgb: Long,
        clipStartUs: Long,
        includeMotionOpacity: Boolean,
    ) : this(ClipLookSpec.of(clip, canvasArgb, clipStartUs, includeMotionOpacity))

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ClipShaderProgram(spec, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

@UnstableApi
private class ClipShaderProgram(
    private val spec: ClipLookSpec,
    useHdr: Boolean,
) : QuadShaderProgram(useHdr, ShaderSources.CLIP_FRAGMENT) {

    private val hue = spec.grade.hueMatrixColumnMajor()
    private val cutoutBitmap: Bitmap? = spec.cutoutPath?.let { decode(it) }
    private var cutoutTexture = 0

    override fun setUniforms(presentationTimeUs: Long) {
        val g = spec.grade
        uniform("uTexel", 1f / frameWidth, 1f / frameHeight)
        uniform("uAspect", frameWidth.toFloat() / frameHeight)
        uniform("uTime", presentationTimeUs / 1_000_000f)

        uniform("uGain", g.gainR, g.gainG, g.gainB)
        uniform("uContrast", g.contrast)
        uniform("uBrightness", g.brightness)
        uniform("uFade", g.fade)
        uniform("uSat", g.saturation)
        program.setFloatsUniformIfPresent("uHue", hue)
        uniform("uShadows", g.shadows)
        uniform("uHighlights", g.highlights)
        program.setFloatsUniformIfPresent("uShadowChroma", g.shadowChroma)
        program.setFloatsUniformIfPresent("uHighlightChroma", g.highlightChroma)
        uniform("uVignette", g.vignette)
        uniform("uGrain", g.grain)
        uniform("uSharpen", g.sharpen)

        val mask = spec.mask
        uniform("uMaskType", mask.type.ordinal.toFloat())
        uniform("uMaskA", mask.centerX, mask.centerY, mask.width, mask.height)
        uniform(
            "uMaskB",
            Math.toRadians(mask.rotationDegrees.toDouble()).toFloat(),
            mask.feather,
            if (mask.invert) 1f else 0f,
        )

        val key = spec.chroma
        uniform("uChromaOn", if (key.enabled) 1f else 0f)
        uniform(
            "uChromaKey",
            Color.red(key.colorArgb.toInt()) / 255f,
            Color.green(key.colorArgb.toInt()) / 255f,
            Color.blue(key.colorArgb.toInt()) / 255f,
        )
        uniform("uChromaParams", key.similarity, key.smoothness, key.spill)

        uniform("uCutoutOn", if (cutoutBitmap != null) 1f else 0f)
        program.setSamplerTexIdUniform("uCutoutTex", cutoutTextureId(), 1)

        uniform("uCanvas", spec.canvasRgb[0], spec.canvasRgb[1], spec.canvasRgb[2])
        val opacity = if (spec.includeMotionOpacity) {
            ClipMotion.stateAt(spec.clip, (presentationTimeUs - spec.clipStartUs).coerceAtLeast(0L)).opacity
        } else {
            1f
        }
        uniform("uOpacity", opacity.coerceIn(0f, 1f))
    }

    /** Uploads the cut-out (or a 1×1 white pixel) the first time it is needed, on the GL thread. */
    private fun cutoutTextureId(): Int {
        if (cutoutTexture != 0) return cutoutTexture
        val bitmap = cutoutBitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (bitmap !== cutoutBitmap) bitmap.recycle()
        cutoutTexture = ids[0]
        return cutoutTexture
    }

    override fun release() {
        if (cutoutTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(cutoutTexture), 0)
            cutoutTexture = 0
        }
        cutoutBitmap?.recycle()
        super.release()
    }

    private companion object {
        fun decode(path: String): Bitmap? = runCatching {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeFile(path, options)
        }.getOrNull()
    }
}
