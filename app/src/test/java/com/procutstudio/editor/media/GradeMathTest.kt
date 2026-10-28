package com.procutstudio.editor.media

import com.procutstudio.editor.domain.model.ChromaKey
import com.procutstudio.editor.domain.model.ClipMask
import com.procutstudio.editor.domain.model.FilterPreset
import com.procutstudio.editor.domain.model.MaskType
import com.procutstudio.editor.domain.model.VideoEffect
import com.procutstudio.editor.media.render.GradeMath
import com.procutstudio.editor.media.render.GradeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CPU mirror of the clip shader: what filter thumbnails and photo previews are drawn with. */
class GradeMathTest {

    private fun grade(effect: VideoEffect, r: Float, g: Float, b: Float): FloatArray {
        val out = FloatArray(3)
        GradeMath.gradePixel(GradeParams.from(effect), r, g, b, 0.5f, 0.5f, out)
        return out
    }

    @Test
    fun `a neutral grade leaves pixels untouched`() {
        val out = grade(VideoEffect(), 0.3f, 0.5f, 0.7f)
        assertEquals(0.3f, out[0], 0.003f)
        assertEquals(0.5f, out[1], 0.003f)
        assertEquals(0.7f, out[2], 0.003f)
    }

    @Test
    fun `brightness lifts and full desaturation removes colour`() {
        val brighter = grade(VideoEffect(brightness = 30f), 0.3f, 0.3f, 0.3f)
        assertTrue(brighter[0] > 0.35f)

        val mono = grade(VideoEffect(saturation = -100f), 0.9f, 0.2f, 0.4f)
        assertEquals(mono[0], mono[1], 0.003f)
        assertEquals(mono[1], mono[2], 0.003f)
    }

    @Test
    fun `filter intensity zero is the same as no filter`() {
        val none = grade(VideoEffect(), 0.4f, 0.5f, 0.6f)
        val zero = grade(VideoEffect(filter = FilterPreset.CINEMATIC, filterIntensity = 0f), 0.4f, 0.5f, 0.6f)
        for (channel in 0..2) assertEquals(none[channel], zero[channel], 0.004f)

        val full = grade(VideoEffect(filter = FilterPreset.CINEMATIC, filterIntensity = 1f), 0.4f, 0.5f, 0.6f)
        assertTrue((0..2).any { kotlin.math.abs(full[it] - none[it]) > 0.01f })
    }

    @Test
    fun `a circle mask keeps its centre and drops the corners`() {
        val mask = ClipMask(type = MaskType.CIRCLE, width = 0.4f, height = 0.4f, feather = 0f)
        assertEquals(1f, GradeMath.maskAlpha(mask, 0.5f, 0.5f, 1f), 0.01f)
        assertEquals(0f, GradeMath.maskAlpha(mask, 0.95f, 0.5f, 1f), 0.01f)

        val inverted = mask.copy(invert = true)
        assertEquals(0f, GradeMath.maskAlpha(inverted, 0.5f, 0.5f, 1f), 0.01f)
        assertEquals(1f, GradeMath.maskAlpha(inverted, 0.95f, 0.5f, 1f), 0.01f)
    }

    @Test
    fun `an inactive mask lets everything through`() {
        assertEquals(1f, GradeMath.maskAlpha(ClipMask(), 0.1f, 0.9f, 1.7f), 0f)
    }

    @Test
    fun `chroma key removes the key colour and keeps other colours`() {
        val key = ChromaKey(enabled = true, colorArgb = 0xFF00D84AL)
        val green = floatArrayOf(0f, 0xD8 / 255f, 0x4A / 255f)
        val red = floatArrayOf(1f, 0f, 0f)
        assertEquals(0f, GradeMath.chromaAlpha(key, green), 0.02f)
        assertEquals(1f, GradeMath.chromaAlpha(key, red), 0.02f)
    }
}
