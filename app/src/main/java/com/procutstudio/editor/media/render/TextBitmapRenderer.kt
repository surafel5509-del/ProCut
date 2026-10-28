package com.procutstudio.editor.media.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.procutstudio.editor.core.di.DefaultDispatcher
import com.procutstudio.editor.domain.model.FontOption
import com.procutstudio.editor.domain.model.TextAlignOption
import com.procutstudio.editor.domain.model.TextStyleSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max

/**
 * Rasterises a text overlay with `StaticLayout`.
 *
 * The same renderer feeds the live preview (as a Compose `Image`) and the export pipeline (as a
 * Media3 `BitmapOverlay`), which is why text looks identical before and after exporting.
 *
 * Sizes are expressed against a 1080 px reference width and scaled to the requested canvas width,
 * so a title keeps its proportions at 720p, 1080p or 4K.
 */
@Singleton
class TextBitmapRenderer @Inject constructor(
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Renders [text] for a frame [frameWidthPx] wide. Safe to call from any thread. */
    fun render(text: String, style: TextStyleSpec, frameWidthPx: Int): Bitmap? {
        val safeStyle = style.sanitized()
        val content = if (safeStyle.uppercase) text.uppercase() else text
        if (content.isBlank()) return null
        val key = buildKey(content, safeStyle, frameWidthPx)
        cache.get(key)?.let { return it }
        val bitmap = draw(content, safeStyle, frameWidthPx) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    suspend fun renderAsync(text: String, style: TextStyleSpec, frameWidthPx: Int): Bitmap? =
        withContext(defaultDispatcher) { render(text, style, frameWidthPx) }

    private fun draw(text: String, style: TextStyleSpec, frameWidthPx: Int): Bitmap? {
        val scale = frameWidthPx / REFERENCE_WIDTH_PX.toFloat()
        val textSizePx = (style.sizeSp * scale).coerceAtLeast(8f)
        val padding = (style.backgroundPaddingDp * scale).coerceAtLeast(0f)
        val maxTextWidth = (frameWidthPx * style.maxWidthFraction).toInt().coerceAtLeast(32)

        val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.colorArgb.toInt()
            textSize = textSizePx
            typeface = typefaceFor(style.font, style.bold, style.italic)
            letterSpacing = style.letterSpacing.coerceIn(-0.1f, 0.5f)
            isUnderlineText = style.underline
            if (style.shadowEnabled) {
                setShadowLayer(
                    (style.shadowRadius * scale).coerceAtLeast(1f),
                    (2f * scale),
                    (2f * scale),
                    style.shadowColorArgb.toInt(),
                )
            }
        }

        val alignment = when (style.align) {
            TextAlignOption.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlignOption.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignOption.END -> Layout.Alignment.ALIGN_OPPOSITE
        }

        fun buildLayout(paint: TextPaint): StaticLayout {
            val builder = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, maxTextWidth)
                .setAlignment(alignment)
                .setLineSpacing(0f, style.lineSpacing)
                .setIncludePad(false)
                .setMaxLines(MAX_LINES)
            return builder.build()
        }

        val fillPaint = basePaint
        val fillLayout = buildLayout(fillPaint)
        val strokeLayout = if (style.strokeWidth > 0f) {
            val strokePaint = TextPaint(fillPaint).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = (style.strokeWidth * scale).coerceAtLeast(1f)
                color = style.strokeColorArgb.toInt()
                clearShadowLayer()
            }
            buildLayout(strokePaint)
        } else {
            null
        }

        val textWidth = max(fillLayout.width, strokeLayout?.width ?: 0).coerceAtLeast(1)
        val textHeight = max(fillLayout.height, strokeLayout?.height ?: 0).coerceAtLeast(1)
        val width = (textWidth + padding * 2).toInt().coerceAtLeast(1)
        val height = (textHeight + padding * 2).toInt().coerceAtLeast(1)

        return runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (style.backgroundAlpha > 0.001f) {
                val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = style.backgroundColorArgb.toInt()
                    alpha = (style.backgroundAlpha * 255).toInt().coerceIn(0, 255)
                }
                val radius = (padding * 0.9f).coerceAtLeast(2f)
                canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, backgroundPaint)
            }
            canvas.save()
            canvas.translate(padding, padding)
            strokeLayout?.draw(canvas)
            fillLayout.draw(canvas)
            canvas.restore()
            bitmap
        }.getOrNull()
    }

    private fun buildKey(text: String, style: TextStyleSpec, frameWidthPx: Int): String =
        "$frameWidthPx|$text|$style"

    private fun typefaceFor(font: FontOption, bold: Boolean, italic: Boolean): Typeface {
        val family = when (font) {
            FontOption.SANS -> "sans-serif"
            FontOption.SERIF -> "serif"
            FontOption.MONO -> "monospace"
            FontOption.CURSIVE -> "cursive"
        }
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(family, style)
    }

    /** Clears the raster cache, e.g. when a project is closed. */
    fun clearCache() = cache.evictAll()

    companion object {
        const val REFERENCE_WIDTH_PX = 1080
        private const val MAX_LINES = 12
        private const val MAX_CACHE_BYTES = 24 * 1024 * 1024
        val TRANSPARENT: Int = Color.TRANSPARENT
        fun desiredBitmapWidth(frameWidthPx: Int, maxWidthFraction: Float): Int =
            ceil(frameWidthPx * maxWidthFraction).toInt()
    }
}
