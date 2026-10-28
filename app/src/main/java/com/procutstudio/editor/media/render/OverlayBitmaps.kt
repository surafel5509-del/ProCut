package com.procutstudio.editor.media.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.procutstudio.editor.core.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitmaps that back non-text overlays: solid colour curtains used for fades, the vignette look, and
 * imported images used as stickers/logos. Everything is cached because overlays ask for a bitmap on
 * every rendered frame.
 */
@Singleton
class OverlayBitmaps @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val cache = ConcurrentHashMap<String, Bitmap>()

    /** 1×1 transparent bitmap used when an overlay is outside its time window. */
    val transparent: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    fun solid(colorArgb: Long): Bitmap {
        val key = "solid_$colorArgb"
        return cache.getOrPut(key) {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(colorArgb.toInt()) }
        }
    }

    /** Radial darkening used by cinematic/film looks. [strength] is 0..1. */
    fun vignette(strength: Float): Bitmap? {
        if (strength <= 0.01f) return null
        val bucket = (strength * 10).toInt().coerceIn(1, 10)
        val key = "vignette_$bucket"
        return cache.getOrPut(key) {
            val size = 256
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val alpha = (bucket / 10f * 235).toInt().coerceIn(0, 255)
            val shader = RadialGradient(
                size / 2f,
                size / 2f,
                size / 2f * 0.95f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(alpha, 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { this.shader = shader })
            bitmap
        }
    }

    /**
     * Loads a sticker/logo, downsampled to [maxWidthPx] and optionally rounded. Decoding happens on
     * the IO dispatcher; the returned bitmap is shared through the cache, so callers must not
     * recycle it.
     */
    suspend fun loadImage(uriString: String, maxWidthPx: Int, cornerRadiusPx: Float = 0f): Bitmap? =
        withContext(ioDispatcher) {
            val key = "img_${uriString}_${maxWidthPx}_${cornerRadiusPx.toInt()}"
            cache[key]?.let { return@withContext it }
            val file = File(uriString.removePrefix("file://"))
            if (!file.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= maxWidthPx) sampleSize *= 2
            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: return@withContext null
            val scaled = if (decoded.width > maxWidthPx) {
                val height = (decoded.height * (maxWidthPx.toFloat() / decoded.width)).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, maxWidthPx, height, true).also { decoded.recycle() }
            } else {
                decoded
            }
            val result = if (cornerRadiusPx > 0.5f) roundCorners(scaled, cornerRadiusPx) else scaled
            cache[key] = result
            result
        }

    private fun roundCorners(source: Bitmap, radiusPx: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val rect = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        canvas.drawRoundRect(rect, radiusPx.coerceAtMost(source.width / 2f), radiusPx.coerceAtMost(source.height / 2f), paint)
        return output
    }

    fun clearCache() {
        cache.values.forEach { runCatching { it.recycle() } }
        cache.clear()
    }
}
