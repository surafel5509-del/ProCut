package com.procutstudio.editor.media.export

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import com.procutstudio.editor.domain.model.VideoEffect

/**
 * Turns the editor's colour grade into Media3 GL effects.
 *
 * The editor stores adjustments on a -100..100 scale (what the sliders show). Media3 expects
 * `HslAdjustment` on -100..100, but `Brightness`/`Contrast` on -1..1, so the conversion is explicit
 * here. The same list is used for live preview (`ExoPlayer.setVideoEffects`) and for export, which
 * keeps the preview honest.
 */
@UnstableApi
object ColorEffectMapper {

    fun videoEffects(effect: VideoEffect): List<Effect> {
        if (effect.isNeutral) return emptyList()
        val effects = mutableListOf<Effect>()

        val brightness = effect.effectiveBrightness
        if (brightness != 0f) effects += Brightness((brightness / 100f).coerceIn(-1f, 1f))

        val contrast = effect.effectiveContrast
        if (contrast != 0f) effects += Contrast((contrast / 100f).coerceIn(-1f, 1f))

        val saturation = effect.effectiveSaturation
        val hue = effect.effectiveHue
        if (saturation != 0f || hue != 0f) {
            effects += HslAdjustment.Builder()
                .adjustSaturation(saturation)
                .adjustHue(hue)
                .build()
        }

        val warmth = effect.effectiveWarmth
        if (warmth != 0f) {
            effects += RgbAdjustment.Builder()
                .setRedScale((1f + warmth / 200f).coerceIn(0f, 2f))
                .setBlueScale((1f - warmth / 200f).coerceIn(0f, 2f))
                .build()
        }
        return effects
    }

    /** True when the grade needs GL processing at all (used to keep the preview fast path). */
    fun needsGlProcessing(effect: VideoEffect): Boolean = !effect.isNeutral
}
