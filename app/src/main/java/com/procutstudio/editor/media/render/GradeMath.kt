package com.procutstudio.editor.media.render

import com.procutstudio.editor.domain.model.ChromaKey
import com.procutstudio.editor.domain.model.ClipMask
import com.procutstudio.editor.domain.model.MaskType
import com.procutstudio.editor.domain.model.VideoEffect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The colour-grade numbers of one clip, in the exact form both renderers consume.
 *
 *  - the clip GL shader (live preview + export) reads them as uniforms,
 *  - [GradeMath] applies them per pixel on the CPU for filter thumbnails and photo previews.
 *
 * Keeping a single description is what makes a filter thumbnail honest about the final video.
 */
class GradeParams private constructor(
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val gainR: Float,
    val gainG: Float,
    val gainB: Float,
    val fade: Float,
    val shadows: Float,
    val highlights: Float,
    val hueRadians: Float,
    val shadowChroma: FloatArray,
    val highlightChroma: FloatArray,
    val vignette: Float,
    val grain: Float,
    val sharpen: Float,
) {
    /** True when [GradeMath] / the shader has anything to do for colour. */
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 1f && saturation == 1f && gainR == 1f && gainG == 1f &&
            gainB == 1f && fade == 0f && shadows == 0f && highlights == 0f && hueRadians == 0f &&
            shadowChroma.all { it == 0f } && highlightChroma.all { it == 0f } && vignette == 0f &&
            grain == 0f && sharpen == 0f

    /** Hue-rotation matrix, column-major (what `glUniformMatrix3fv` with transpose=false expects). */
    fun hueMatrixColumnMajor(): FloatArray {
        val c = cos(hueRadians)
        val s = sin(hueRadians)
        return floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f, 0.213f - c * 0.213f + s * 0.143f, 0.213f - c * 0.213f - s * 0.787f,
            0.715f - c * 0.715f - s * 0.715f, 0.715f + c * 0.285f + s * 0.140f, 0.715f - c * 0.715f + s * 0.715f,
            0.072f - c * 0.072f + s * 0.928f, 0.072f - c * 0.072f - s * 0.283f, 0.072f + c * 0.928f + s * 0.072f,
        )
    }

    companion object {
        val Neutral: GradeParams = from(VideoEffect())

        fun from(effect: VideoEffect): GradeParams {
            val warmth = effect.effectiveWarmth / 100f
            val tint = effect.effectiveTint / 100f
            val strength = effect.filterIntensity.coerceIn(0f, 1f)
            val preset = effect.filter
            return GradeParams(
                brightness = effect.effectiveBrightness / 100f,
                contrast = (1f + effect.effectiveContrast / 100f).coerceIn(0f, 2f),
                saturation = (1f + effect.effectiveSaturation / 100f).coerceIn(0f, 2f),
                gainR = 1f + warmth * 0.22f + tint * 0.04f,
                gainG = 1f - tint * 0.18f,
                gainB = 1f - warmth * 0.22f + tint * 0.04f,
                fade = effect.effectiveFade,
                shadows = effect.shadows / 100f,
                highlights = effect.highlights / 100f,
                hueRadians = Math.toRadians(effect.effectiveHue.toDouble()).toFloat(),
                shadowChroma = chroma(preset.shadowTint, strength * 0.6f),
                highlightChroma = chroma(preset.highlightTint, strength * 0.6f),
                vignette = effect.effectiveVignette,
                grain = effect.effectiveGrain,
                sharpen = effect.effectiveSharpen,
            )
        }

        /** Split-tone colour reduced to its chroma (colour minus its own luma), pre-scaled. */
        private fun chroma(argb: Long, scale: Float): FloatArray {
            if (argb == 0L) return floatArrayOf(0f, 0f, 0f)
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val luma = r * LUMA_R + g * LUMA_G + b * LUMA_B
            return floatArrayOf((r - luma) * scale, (g - luma) * scale, (b - luma) * scale)
        }

        const val LUMA_R = 0.299f
        const val LUMA_G = 0.587f
        const val LUMA_B = 0.114f
    }
}

/**
 * CPU implementation of the clip look (grade + mask + chroma key + cut-out). Mirrors the clip
 * fragment shader line for line; if you change one, change the other.
 */
object GradeMath {

    /**
     * Grades one pixel. [u]/[v] are 0..1 positions (v measured downwards) used by the vignette and
     * grain. Output channels are written into [out] (0..1).
     */
    fun gradePixel(p: GradeParams, r: Float, g: Float, b: Float, u: Float, v: Float, out: FloatArray) {
        var cr = r * p.gainR
        var cg = g * p.gainG
        var cb = b * p.gainB

        cr = (cr - 0.5f) * p.contrast + 0.5f + p.brightness
        cg = (cg - 0.5f) * p.contrast + 0.5f + p.brightness
        cb = (cb - 0.5f) * p.contrast + 0.5f + p.brightness

        val fadeK = 1f - 0.3f * p.fade
        val fadeAdd = 0.15f * p.fade
        cr = cr * fadeK + fadeAdd
        cg = cg * fadeK + fadeAdd
        cb = cb * fadeK + fadeAdd

        var luma = cr * GradeParams.LUMA_R + cg * GradeParams.LUMA_G + cb * GradeParams.LUMA_B
        cr = luma + (cr - luma) * p.saturation
        cg = luma + (cg - luma) * p.saturation
        cb = luma + (cb - luma) * p.saturation

        if (p.hueRadians != 0f) {
            val c = cos(p.hueRadians)
            val s = sin(p.hueRadians)
            val nr = cr * (0.213f + c * 0.787f - s * 0.213f) + cg * (0.715f - c * 0.715f - s * 0.715f) + cb * (0.072f - c * 0.072f + s * 0.928f)
            val ng = cr * (0.213f - c * 0.213f + s * 0.143f) + cg * (0.715f + c * 0.285f + s * 0.140f) + cb * (0.072f - c * 0.072f - s * 0.283f)
            val nb = cr * (0.213f - c * 0.213f - s * 0.787f) + cg * (0.715f - c * 0.715f + s * 0.715f) + cb * (0.072f + c * 0.928f + s * 0.072f)
            cr = nr; cg = ng; cb = nb
        }

        luma = (cr * GradeParams.LUMA_R + cg * GradeParams.LUMA_G + cb * GradeParams.LUMA_B).coerceIn(0f, 1f)
        val shadowW = 1f - smoothstep(0f, 0.55f, luma)
        val highW = smoothstep(0.45f, 1f, luma)
        val tone = p.shadows * 0.25f * shadowW + p.highlights * 0.25f * highW
        cr += tone + p.shadowChroma[0] * shadowW + p.highlightChroma[0] * highW
        cg += tone + p.shadowChroma[1] * shadowW + p.highlightChroma[1] * highW
        cb += tone + p.shadowChroma[2] * shadowW + p.highlightChroma[2] * highW

        cr = cr.coerceIn(0f, 1f)
        cg = cg.coerceIn(0f, 1f)
        cb = cb.coerceIn(0f, 1f)

        if (p.vignette > 0f) {
            val dx = u - 0.5f
            val dy = v - 0.5f
            val d = sqrt(dx * dx + dy * dy) * 1.4142135f
            val f = 1f - p.vignette * 0.85f * smoothstep(0.3f, 1f, d)
            cr *= f; cg *= f; cb *= f
        }
        if (p.grain > 0f) {
            val n = (hash(u * 811f, v * 613f) - 0.5f) * p.grain * 0.22f
            cr = (cr + n).coerceIn(0f, 1f)
            cg = (cg + n).coerceIn(0f, 1f)
            cb = (cb + n).coerceIn(0f, 1f)
        }
        out[0] = cr; out[1] = cg; out[2] = cb
    }

    /** Alpha (0..1) a mask lets through at a source-frame position. [u]/[v]: 0..1, v downwards. */
    fun maskAlpha(mask: ClipMask, u: Float, v: Float, aspect: Float): Float {
        if (!mask.isActive) return 1f
        val px0 = (u - mask.centerX) * aspect
        val py0 = -(v - mask.centerY)
        val rot = Math.toRadians(mask.rotationDegrees.toDouble()).toFloat()
        val c = cos(rot)
        val s = sin(rot)
        val px = c * px0 - s * py0
        val py = s * px0 + c * py0
        val w = mask.width
        val h = mask.height
        val d: Float = when (mask.type) {
            MaskType.NONE -> -1f
            MaskType.LINEAR -> py
            MaskType.MIRROR -> abs(py) - h * 0.5f
            MaskType.CIRCLE -> (sqrt((px / (w * 0.5f)) * (px / (w * 0.5f)) + (py / (h * 0.5f)) * (py / (h * 0.5f))) - 1f) * min(w, h) * 0.5f
            MaskType.RECTANGLE -> {
                val qx = abs(px) - w * 0.5f
                val qy = abs(py) - h * 0.5f
                sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f)) + min(max(qx, qy), 0f)
            }
            MaskType.HEART -> sdHeart(px / (w * 0.5f) * 0.7f, py / (h * 0.5f) * 0.55f + 0.5f) * min(w, h) * 0.5f / 0.7f
            MaskType.STAR -> sdStar(px * (h / w), py, min(w, h) * 0.5f, 0.45f)
        }
        val f = mask.feather * 0.35f + 0.003f
        val a = 1f - smoothstep(-f, f, d)
        return if (mask.invert) 1f - a else a
    }

    /** Alpha produced by the chroma key for a pixel, plus the spill-suppressed colour in [out]. */
    fun chromaAlpha(key: ChromaKey, out: FloatArray): Float {
        val kr = ((key.colorArgb shr 16) and 0xFF) / 255f
        val kg = ((key.colorArgb shr 8) and 0xFF) / 255f
        val kb = (key.colorArgb and 0xFF) / 255f
        val dcb = cb(out[0], out[1], out[2]) - cb(kr, kg, kb)
        val dcr = cr(out[0], out[1], out[2]) - cr(kr, kg, kb)
        val d = sqrt(dcb * dcb + dcr * dcr)
        val edge = key.similarity * 0.7f
        val alpha = smoothstep(edge, edge + key.smoothness * 0.5f + 0.0001f, d)
        val spillAmount = key.spill * (1f - smoothstep(edge, edge + 0.25f, d))
        val luma = out[0] * GradeParams.LUMA_R + out[1] * GradeParams.LUMA_G + out[2] * GradeParams.LUMA_B
        out[0] = out[0] + (luma - out[0]) * spillAmount
        out[1] = out[1] + (luma - out[1]) * spillAmount
        out[2] = out[2] + (luma - out[2]) * spillAmount
        return alpha
    }

    fun cb(r: Float, g: Float, b: Float): Float = 0.5f - 0.168736f * r - 0.331264f * g + 0.5f * b
    fun cr(r: Float, g: Float, b: Float): Float = 0.5f + 0.5f * r - 0.418688f * g - 0.081312f * b

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hash(x: Float, y: Float): Float {
        val s = sin((x * 127.1f + y * 311.7f).toDouble()) * 43758.5453
        return (s - kotlin.math.floor(s)).toFloat()
    }

    private fun dot2(x: Float, y: Float) = x * x + y * y

    private fun sdHeart(px: Float, py: Float): Float {
        val x = abs(px)
        val y = py
        if (y + x > 1f) {
            return sqrt(dot2(x - 0.25f, y - 0.75f)) - 0.35355338f
        }
        val m = 0.5f * max(x + y, 0f)
        val d = sqrt(min(dot2(x, y - 1f), dot2(x - m, y - m)))
        return d * (if (x - y >= 0f) 1f else -1f)
    }

    private fun sdStar(px0: Float, py0: Float, r: Float, rf: Float): Float {
        val k1x = 0.809017f
        val k1y = -0.5877853f
        val k2x = -k1x
        val k2y = k1y
        var px = abs(px0)
        var py = py0
        var d = max(k1x * px + k1y * py, 0f) * 2f
        px -= d * k1x; py -= d * k1y
        d = max(k2x * px + k2y * py, 0f) * 2f
        px -= d * k2x; py -= d * k2y
        px = abs(px)
        py -= r
        val bax = rf * -k1y
        val bay = rf * k1x - 1f
        val h = ((px * bax + py * bay) / (bax * bax + bay * bay)).coerceIn(0f, r)
        val dx = px - bax * h
        val dy = py - bay * h
        val sgn = if (py * bax - px * bay >= 0f) 1f else -1f
        return sqrt(dx * dx + dy * dy) * sgn
    }

    @Suppress("unused")
    private fun angleOf(x: Float, y: Float): Float = atan2(y, x)
}
