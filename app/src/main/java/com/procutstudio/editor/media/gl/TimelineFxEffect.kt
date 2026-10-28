package com.procutstudio.editor.media.gl

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.procutstudio.editor.domain.model.EffectItem
import com.procutstudio.editor.domain.model.VideoFxType

/**
 * Applies the effects lane to the *whole composition*: between an item's start and end the matching
 * section of the effects shader runs at the item's intensity. Items overlap freely (strongest wins
 * per effect type), and every effect is driven by the global timeline clock so it animates
 * identically in the preview and in the export.
 *
 * @param timeMapper turns the frame's presentation time into timeline time. Export frames already
 *   carry timeline time; the live preview player doesn't, so it passes the playhead clock.
 */
@UnstableApi
class TimelineFxEffect(
    items: List<EffectItem>,
    private val timeMapper: (Long) -> Long = { it },
) : GlEffect {

    private val items: List<EffectItem> = items.toList()

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        TimelineFxProgram(items, timeMapper, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = items.isEmpty()
}

@UnstableApi
private class TimelineFxProgram(
    private val items: List<EffectItem>,
    private val timeMapper: (Long) -> Long,
    useHdr: Boolean,
) : QuadShaderProgram(useHdr, ShaderSources.FX_FRAGMENT) {

    private val intensities = FloatArray(ShaderSources.FX_VECTOR_COUNT * 4)

    override fun setUniforms(presentationTimeUs: Long) {
        val timelineUs = timeMapper(presentationTimeUs)
        intensities.fill(0f)
        for (item in items) {
            if (item.isActiveAt(timelineUs)) {
                val slot = item.type.ordinal
                intensities[slot] = maxOf(intensities[slot], item.intensity)
            }
        }
        for (v in 0 until ShaderSources.FX_VECTOR_COUNT) {
            uniform(
                ShaderSources.fxUniformVectorName(v),
                intensities[v * 4], intensities[v * 4 + 1], intensities[v * 4 + 2], intensities[v * 4 + 3],
            )
        }
        uniform("uTime", (timelineUs % 3_600_000_000L) / 1_000_000f)
        uniform("uAspect", frameWidth.toFloat() / frameHeight)
        uniform("uTexel", 1f / frameWidth, 1f / frameHeight)
    }
}

/** Shared playhead for the live preview; written by the player ticker, read on the GL thread. */
object PreviewClock {
    @Volatile
    var timelineUs: Long = 0L
}
