package com.procutstudio.editor.media.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Rasterises [StickerDef]s with the Android Canvas API. The same bitmap feeds the picker tile, the
 * live preview and the exported video (stickers are stored as PNG assets), so what the user taps is
 * exactly what appears in the render.
 */
object StickerRenderer {

    /** Renders [def] into a transparent bitmap that is [widthPx] wide (height follows the content). */
    fun render(def: StickerDef, widthPx: Int): Bitmap {
        val width = widthPx.coerceIn(48, 2048)
        return when (val kind = def.kind) {
            is StickerKind.TextArt -> renderTextArt(kind, width)
            is StickerKind.Emoji -> renderEmoji(kind, width)
            is StickerKind.Shape -> renderShape(kind, width)
            is StickerKind.Badge -> renderBadge(kind, width)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Text art
    // ------------------------------------------------------------------------------------------

    private fun renderTextArt(kind: StickerKind.TextArt, width: Int): Bitmap {
        val lines = wrap(kind.text)
        val typeface = when (kind.style) {
            TextArtStyle.SCRIPT -> Typeface.create("cursive", Typeface.BOLD)
            TextArtStyle.BUBBLE -> Typeface.create("casual", Typeface.BOLD)
            TextArtStyle.SHADOW3D -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            else -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        }
        val padding = width * 0.09f
        val inner = width - padding * 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
            textSize = width * 0.2f
        }
        val widest = lines.maxOf { paint.measureText(it) }.coerceAtLeast(1f)
        paint.textSize = (paint.textSize * inner / widest).coerceAtMost(width * 0.30f)
        val metrics = paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * 0.92f
        val textHeight = lineHeight * lines.size
        val height = (textHeight + padding * 2).toInt().coerceAtLeast(8)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val primary = kind.primary.toInt()
        val secondary = kind.secondary.toInt()
        val outlineWidth = paint.textSize * 0.16f
        val cx = width / 2f
        val firstBaseline = padding + (-metrics.ascent) * 0.96f

        fun forEachLine(draw: (String, Float) -> Unit) {
            lines.forEachIndexed { index, line -> draw(line, firstBaseline + index * lineHeight) }
        }

        when (kind.style) {
            TextArtStyle.OUTLINE -> {
                val stroke = Paint(paint).apply {
                    style = Paint.Style.STROKE; this.strokeWidth = outlineWidth; strokeJoin = Paint.Join.ROUND
                    color = secondary
                }
                val fill = Paint(paint).apply { style = Paint.Style.FILL; color = primary }
                forEachLine { line, y -> canvas.drawText(line, cx, y, stroke) }
                forEachLine { line, y -> canvas.drawText(line, cx, y, fill) }
            }

            TextArtStyle.NEON -> {
                val glow = Paint(paint).apply {
                    style = Paint.Style.FILL; color = secondary
                    setShadowLayer(paint.textSize * 0.45f, 0f, 0f, secondary)
                }
                val core = Paint(paint).apply {
                    style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt()
                    setShadowLayer(paint.textSize * 0.12f, 0f, 0f, primary)
                }
                val ring = Paint(paint).apply {
                    style = Paint.Style.STROKE; this.strokeWidth = paint.textSize * 0.07f; color = primary
                    strokeJoin = Paint.Join.ROUND
                }
                repeat(2) { forEachLine { line, y -> canvas.drawText(line, cx, y, glow) } }
                forEachLine { line, y -> canvas.drawText(line, cx, y, ring) }
                forEachLine { line, y -> canvas.drawText(line, cx, y, core) }
            }

            TextArtStyle.BOXED -> {
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
                val rect = RectF(padding * 0.35f, padding * 0.35f, width - padding * 0.35f, height - padding * 0.35f)
                canvas.drawRoundRect(rect, height * 0.2f, height * 0.2f, box)
                val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; this.strokeWidth = paint.textSize * 0.07f; color = secondary
                }
                val inset = paint.textSize * 0.12f
                canvas.drawRoundRect(
                    RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
                    height * 0.15f, height * 0.15f, edge,
                )
                val fill = Paint(paint).apply { style = Paint.Style.FILL; color = secondary }
                forEachLine { line, y -> canvas.drawText(line, cx, y, fill) }
            }

            TextArtStyle.GRADIENT -> {
                val stroke = Paint(paint).apply {
                    style = Paint.Style.STROKE; this.strokeWidth = outlineWidth * 0.8f; strokeJoin = Paint.Join.ROUND
                    color = 0xFFFFFFFF.toInt()
                    setShadowLayer(paint.textSize * 0.12f, 0f, paint.textSize * 0.05f, 0x66000000)
                }
                val fill = Paint(paint).apply {
                    style = Paint.Style.FILL
                    shader = LinearGradient(
                        0f, padding, 0f, height - padding, primary, secondary, Shader.TileMode.CLAMP,
                    )
                }
                forEachLine { line, y -> canvas.drawText(line, cx, y, stroke) }
                forEachLine { line, y -> canvas.drawText(line, cx, y, fill) }
            }

            TextArtStyle.SHADOW3D -> {
                val depth = (paint.textSize * 0.09f).toInt().coerceAtLeast(2)
                val extrude = Paint(paint).apply { style = Paint.Style.FILL; color = secondary }
                val outline = Paint(paint).apply {
                    style = Paint.Style.STROKE; this.strokeWidth = paint.textSize * 0.10f; strokeJoin = Paint.Join.ROUND
                    color = 0xFF111111.toInt()
                }
                val fill = Paint(paint).apply { style = Paint.Style.FILL; color = primary }
                for (step in depth downTo 1) {
                    forEachLine { line, y -> canvas.drawText(line, cx + step, y + step, extrude) }
                }
                forEachLine { line, y -> canvas.drawText(line, cx + depth, y + depth, outline) }
                forEachLine { line, y -> canvas.drawText(line, cx, y, outline) }
                forEachLine { line, y -> canvas.drawText(line, cx, y, fill) }
            }

            TextArtStyle.BUBBLE -> {
                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x33000000; style = Paint.Style.FILL
                }
                val rect = RectF(padding * 0.4f, padding * 0.3f, width - padding * 0.4f, height - padding * 0.6f)
                val radius = (rect.height() / 2f)
                canvas.drawRoundRect(RectF(rect.left, rect.top + 6f, rect.right, rect.bottom + 6f), radius, radius, shadow)
                canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = secondary })
                canvas.drawRoundRect(
                    rect, radius, radius,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; this.strokeWidth = paint.textSize * 0.1f; color = primary
                    },
                )
                val fill = Paint(paint).apply { style = Paint.Style.FILL; color = primary }
                val shift = -padding * 0.15f
                forEachLine { line, y -> canvas.drawText(line, cx, y + shift, fill) }
            }

            TextArtStyle.SCRIPT -> {
                val shadow = Paint(paint).apply {
                    style = Paint.Style.FILL; color = secondary
                    setShadowLayer(paint.textSize * 0.15f, 0f, paint.textSize * 0.06f, 0x88000000.toInt())
                }
                val stroke = Paint(paint).apply {
                    style = Paint.Style.STROKE; this.strokeWidth = paint.textSize * 0.12f; strokeJoin = Paint.Join.ROUND
                    color = secondary
                }
                val fill = Paint(paint).apply { style = Paint.Style.FILL; color = primary }
                forEachLine { line, y -> canvas.drawText(line, cx, y, shadow) }
                forEachLine { line, y -> canvas.drawText(line, cx, y, stroke) }
                forEachLine { line, y -> canvas.drawText(line, cx, y, fill) }
            }
        }
        return bitmap
    }

    /** Splits long text into two balanced lines; short text stays on one line. */
    private fun wrap(text: String): List<String> {
        val trimmed = text.trim()
        val words = trimmed.split(' ').filter { it.isNotEmpty() }
        if (trimmed.length <= 9 || words.size < 2) return listOf(trimmed)
        var best = 1
        var bestDelta = Int.MAX_VALUE
        for (split in 1 until words.size) {
            val left = words.take(split).joinToString(" ").length
            val right = words.drop(split).joinToString(" ").length
            val delta = kotlin.math.abs(left - right)
            if (delta < bestDelta) { bestDelta = delta; best = split }
        }
        return listOf(words.take(best).joinToString(" "), words.drop(best).joinToString(" "))
    }

    // ------------------------------------------------------------------------------------------
    // Emoji
    // ------------------------------------------------------------------------------------------

    private fun renderEmoji(kind: StickerKind.Emoji, width: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = width * 0.78f
        }
        val measured = paint.measureText(kind.glyph).coerceAtLeast(1f)
        if (measured > width * 0.94f) paint.textSize *= width * 0.94f / measured
        val metrics = paint.fontMetrics
        val baseline = width / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(kind.glyph, width / 2f, baseline, paint)
        return bitmap
    }

    // ------------------------------------------------------------------------------------------
    // Vector shapes
    // ------------------------------------------------------------------------------------------

    private fun renderShape(kind: StickerKind.Shape, width: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val size = width.toFloat()
        val inset = size * 0.08f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(0f, 0f, 0f, size, kind.primary.toInt(), kind.secondary.toInt(), Shader.TileMode.CLAMP)
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = size * 0.045f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            color = 0xFFFFFFFF.toInt()
            setShadowLayer(size * 0.03f, 0f, size * 0.015f, 0x55000000)
        }
        val path = Path()
        val box = RectF(inset, inset, size - inset, size - inset)
        when (kind.shape) {
            ShapeKind.STAR -> starPath(path, size / 2f, size / 2f * 1.04f, box.width() / 2f, box.width() / 2f * 0.48f, 5)
            ShapeKind.BURST -> starPath(path, size / 2f, size / 2f, box.width() / 2f, box.width() / 2f * 0.72f, 14)
            ShapeKind.SPARKLE -> sparklePath(path, size / 2f, size / 2f, box.width() / 2f)
            ShapeKind.HEART -> heartPath(path, box)
            ShapeKind.ARROW -> arrowPath(path, box)
            ShapeKind.SPEECH -> speechPath(path, box)
            ShapeKind.CHECK -> {
                canvas.drawCircle(size / 2f, size / 2f, box.width() / 2f, fill)
                canvas.drawCircle(size / 2f, size / 2f, box.width() / 2f, edge)
                val tick = Path().apply {
                    moveTo(size * 0.28f, size * 0.52f)
                    lineTo(size * 0.44f, size * 0.68f)
                    lineTo(size * 0.74f, size * 0.34f)
                }
                canvas.drawPath(
                    tick,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = size * 0.1f
                        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFFFFFFFF.toInt()
                    },
                )
                return bitmap
            }
            ShapeKind.CROWN -> crownPath(path, box)
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, edge)
        return bitmap
    }

    private fun starPath(path: Path, cx: Float, cy: Float, outer: Float, inner: Float, points: Int) {
        val step = Math.PI / points
        var angle = -Math.PI / 2
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val x = cx + (cos(angle) * r).toFloat()
            val y = cy + (sin(angle) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            angle += step
        }
        path.close()
    }

    private fun sparklePath(path: Path, cx: Float, cy: Float, r: Float) {
        val pinch = r * 0.16f
        path.moveTo(cx, cy - r)
        path.quadTo(cx + pinch, cy - pinch, cx + r, cy)
        path.quadTo(cx + pinch, cy + pinch, cx, cy + r)
        path.quadTo(cx - pinch, cy + pinch, cx - r, cy)
        path.quadTo(cx - pinch, cy - pinch, cx, cy - r)
        path.close()
    }

    private fun heartPath(path: Path, box: RectF) {
        val w = box.width()
        val h = box.height()
        val x = box.left
        val y = box.top
        path.moveTo(x + w / 2f, y + h)
        path.cubicTo(x - w * 0.15f, y + h * 0.62f, x + w * 0.02f, y - h * 0.02f, x + w / 2f, y + h * 0.28f)
        path.cubicTo(x + w * 0.98f, y - h * 0.02f, x + w * 1.15f, y + h * 0.62f, x + w / 2f, y + h)
        path.close()
    }

    private fun arrowPath(path: Path, box: RectF) {
        val cy = box.centerY()
        val shaft = box.height() * 0.17f
        val head = box.height() * 0.38f
        val neck = box.left + box.width() * 0.56f
        path.moveTo(box.left, cy - shaft)
        path.lineTo(neck, cy - shaft)
        path.lineTo(neck, cy - head)
        path.lineTo(box.right, cy)
        path.lineTo(neck, cy + head)
        path.lineTo(neck, cy + shaft)
        path.lineTo(box.left, cy + shaft)
        path.close()
    }

    private fun speechPath(path: Path, box: RectF) {
        val body = RectF(box.left, box.top + box.height() * 0.08f, box.right, box.top + box.height() * 0.76f)
        path.addRoundRect(body, box.height() * 0.2f, box.height() * 0.2f, Path.Direction.CW)
        path.moveTo(box.left + box.width() * 0.2f, body.bottom - 2f)
        path.lineTo(box.left + box.width() * 0.16f, box.bottom)
        path.lineTo(box.left + box.width() * 0.42f, body.bottom - 2f)
        path.close()
    }

    private fun crownPath(path: Path, box: RectF) {
        val l = box.left
        val r = box.right
        val t = box.top + box.height() * 0.12f
        val b = box.bottom - box.height() * 0.1f
        val w = r - l
        path.moveTo(l, b)
        path.lineTo(l, t + (b - t) * 0.25f)
        path.lineTo(l + w * 0.25f, t + (b - t) * 0.55f)
        path.lineTo(l + w * 0.5f, t)
        path.lineTo(l + w * 0.75f, t + (b - t) * 0.55f)
        path.lineTo(r, t + (b - t) * 0.25f)
        path.lineTo(r, b)
        path.close()
    }

    // ------------------------------------------------------------------------------------------
    // Badges
    // ------------------------------------------------------------------------------------------

    private fun renderBadge(kind: StickerKind.Badge, width: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            textSize = width * 0.3f
            letterSpacing = 0.04f
        }
        val padX = width * 0.09f
        val avail = width - padX * 2 - width * 0.08f
        val measured = paint.measureText(kind.text).coerceAtLeast(1f)
        paint.textSize = min(paint.textSize, paint.textSize * avail / measured)
        val metrics = paint.fontMetrics
        val textH = metrics.descent - metrics.ascent
        val height = (textH + width * 0.22f).toInt().coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(width * 0.04f, height * 0.08f, width * 0.96f, height * 0.9f)
        val radius = rect.height() * 0.28f
        canvas.drawRoundRect(
            RectF(rect.left, rect.top + height * 0.05f, rect.right, rect.bottom + height * 0.05f),
            radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44000000 },
        )
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kind.background.toInt() })
        canvas.drawRoundRect(
            RectF(rect.left + 5f, rect.top + 5f, rect.right - 5f, rect.bottom - 5f), radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = max(2f, width * 0.012f); color = kind.foreground.toInt(); alpha = 150
            },
        )
        paint.color = kind.foreground.toInt()
        canvas.drawText(kind.text, width / 2f, rect.centerY() - (metrics.ascent + metrics.descent) / 2f, paint)
        return bitmap
    }
}
