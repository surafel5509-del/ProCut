package com.procutstudio.editor.domain.model

import kotlinx.serialization.Serializable

/**
 * Complete description of how a text overlay is drawn. Colours are stored as packed ARGB longs so
 * the same spec can be consumed by the Compose preview and by the Android `StaticLayout` renderer
 * used during export.
 *
 * Sizes are expressed in "canvas sp": the renderer scales them against a 1080 px reference width,
 * so a title looks the same on a 720p preview and a 4K export.
 */
@Serializable
data class TextStyleSpec(
    val font: FontOption = FontOption.SANS,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val uppercase: Boolean = false,
    val sizeSp: Float = 42f,
    val colorArgb: Long = 0xFFFFFFFF,
    val align: TextAlignOption = TextAlignOption.CENTER,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1.05f,
    val backgroundColorArgb: Long = 0xFF000000,
    val backgroundAlpha: Float = 0f,
    val backgroundPaddingDp: Float = 10f,
    val strokeColorArgb: Long = 0xFF000000,
    val strokeWidth: Float = 0f,
    val shadowEnabled: Boolean = true,
    val shadowColorArgb: Long = 0xB3000000,
    val shadowRadius: Float = 8f,
    val maxWidthFraction: Float = 0.86f,
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    val rotationDegrees: Float = 0f,
    val scale: Float = 1f,
) {
    fun sanitized(): TextStyleSpec = copy(
        sizeSp = sizeSp.coerceIn(10f, 200f),
        backgroundAlpha = backgroundAlpha.coerceIn(0f, 1f),
        strokeWidth = strokeWidth.coerceIn(0f, 20f),
        maxWidthFraction = maxWidthFraction.coerceIn(0.2f, 1f),
        positionX = positionX.coerceIn(0f, 1f),
        positionY = positionY.coerceIn(0f, 1f),
        scale = scale.coerceIn(0.3f, 3f),
        lineSpacing = lineSpacing.coerceIn(0.8f, 2.5f),
        shadowRadius = shadowRadius.coerceIn(0f, 30f),
    )

    companion object {
        val Title = TextStyleSpec(
            sizeSp = 64f,
            bold = true,
            letterSpacing = 0.02f,
            positionY = 0.22f,
        )

        val Caption = TextStyleSpec(
            sizeSp = 34f,
            positionY = 0.82f,
            backgroundColorArgb = 0xFF000000,
            backgroundAlpha = 0.55f,
            backgroundPaddingDp = 14f,
        )

        val Subtitle = TextStyleSpec(
            sizeSp = 28f,
            positionY = 0.88f,
            shadowRadius = 10f,
        )

        val LowerThird = TextStyleSpec(
            sizeSp = 32f,
            bold = true,
            align = TextAlignOption.START,
            positionX = 0.28f,
            positionY = 0.76f,
            backgroundColorArgb = 0xFF7C3AED,
            backgroundAlpha = 0.85f,
            backgroundPaddingDp = 12f,
        )

        val Neon = TextStyleSpec(
            sizeSp = 56f,
            bold = true,
            colorArgb = 0xFFE9D5FF,
            strokeColorArgb = 0xFF7C3AED,
            strokeWidth = 6f,
            shadowColorArgb = 0xFF8B5CF6,
            shadowRadius = 18f,
        )

        /** Default style for a fresh text overlay on the timeline. */
        val Default = TextStyleSpec()
    }
}

@Serializable
data class TextTemplateSpec(
    val preset: TextPreset,
    val style: TextStyleSpec,
    val animation: OverlayAnimation,
)


/** Style presets offered by the text editor. */
object TextPresets {
    fun styleFor(preset: TextPreset): TextStyleSpec = when (preset) {
        TextPreset.TITLE -> TextStyleSpec.Title
        TextPreset.HEADLINE -> TextStyleSpec.Title.copy(
            sizeSp = 48f,
            letterSpacing = 0.08f,
            uppercase = true,
            positionY = 0.18f,
        )
        TextPreset.CAPTION -> TextStyleSpec.Caption
        TextPreset.SUBTITLE -> TextStyleSpec.Subtitle
        TextPreset.LOWER_THIRD -> TextStyleSpec.LowerThird
        TextPreset.NEON -> TextStyleSpec.Neon
    }

    fun animationFor(preset: TextPreset): OverlayAnimation = when (preset) {
        TextPreset.NEON -> OverlayAnimation(type = TextAnimationType.POP, inDurationUs = 500_000L)
        TextPreset.HEADLINE -> OverlayAnimation(type = TextAnimationType.SLIDE_UP)
        TextPreset.LOWER_THIRD -> OverlayAnimation(type = TextAnimationType.SLIDE_LEFT)
        TextPreset.CAPTION -> OverlayAnimation(type = TextAnimationType.FADE)
        else -> OverlayAnimation(type = TextAnimationType.FADE)
    }
}

/** Palette used by the text editor's colour picker. */
object TextPalettes {
    val colors: List<Long> = listOf(
        0xFFFFFFFF, 0xFF000000, 0xFFF87171, 0xFFFB923C, 0xFFFACC15,
        0xFF4ADE80, 0xFF38BDF8, 0xFF6366F1, 0xFFA855F7, 0xFFEC4899,
    )

    val backgrounds: List<Long> = listOf(
        0xFF000000, 0xFFFFFFFF, 0xFF7C3AED, 0xFF0EA5E9, 0xFFF97316, 0xFF111827,
    )
}
