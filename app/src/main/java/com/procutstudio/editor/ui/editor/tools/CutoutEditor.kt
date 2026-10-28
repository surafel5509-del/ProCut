package com.procutstudio.editor.ui.editor.tools

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.procutstudio.editor.ui.editor.EditorViewModel
import com.procutstudio.editor.ui.theme.ProCutBackground
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutPrimary
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CutTool { BRUSH, AUTO }

private const val MAX_UNDO = 10

/**
 * Manual background remover. The user paints on a frame of the clip: the eraser removes pixels, the
 * restore brush brings them back, and "Auto select" flood-fills similar colours (magic wand) so
 * large flat backgrounds go in one tap. The result is an alpha mask that is stored with the clip
 * and applied in the live preview and in the export.
 */
@Composable
fun CutoutEditorDialog(
    input: EditorViewModel.CutoutInput,
    onSave: (Bitmap?) -> Unit,
    onDismiss: () -> Unit,
) {
    val frame = input.frame
    val mask = remember(input) { buildMask(frame, input.existingMask) }
    val frameImage = remember(frame) { frame.asImageBitmap() }
    val maskImage = remember(mask) { mask.asImageBitmap() }
    val checker = remember { checkerTile() }
    val undoStack = remember(input) { ArrayList<Bitmap>() }
    var version by remember { mutableStateOf(0) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var tool by remember { mutableStateOf(CutTool.BRUSH) }
    var erase by remember { mutableStateOf(true) }
    var brushPercent by remember { mutableStateOf(5f) }
    var tolerance by remember { mutableStateOf(40f) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val erasePaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    }
    val restorePaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
            color = android.graphics.Color.WHITE
        }
    }
    val maskCanvas = remember(mask) { AndroidCanvas(mask) }

    fun fitScale(): Float =
        if (viewSize.width == 0 || viewSize.height == 0) 1f
        else min(viewSize.width / frame.width.toFloat(), viewSize.height / frame.height.toFloat())

    fun toBitmapSpace(point: Offset): Offset {
        val scale = fitScale()
        val originX = (viewSize.width - frame.width * scale) / 2f
        val originY = (viewSize.height - frame.height * scale) / 2f
        return Offset((point.x - originX) / scale, (point.y - originY) / scale)
    }

    fun pushUndo() {
        if (undoStack.size >= MAX_UNDO) undoStack.removeAt(0)
        undoStack.add(mask.copy(Bitmap.Config.ARGB_8888, false))
    }

    fun stroke(from: Offset, to: Offset) {
        val paint = if (erase) erasePaint else restorePaint
        paint.strokeWidth = max(2f, max(frame.width, frame.height) * brushPercent / 100f)
        maskCanvas.drawLine(from.x, from.y, to.x, to.y, paint)
        version++
    }

    fun wand(point: Offset) {
        val bitmapPoint = toBitmapSpace(point)
        val x = bitmapPoint.x.toInt()
        val y = bitmapPoint.y.toInt()
        if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) return
        pushUndo()
        busy = true
        val eraseMode = erase
        val tol = tolerance
        scope.launch {
            withContext(Dispatchers.Default) { floodSelect(frame, mask, x, y, tol, eraseMode) }
            busy = false
            version++
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ProCutBackground)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Cancel", tint = ProCutOnSurface)
                }
                Text(
                    "Remove background",
                    style = MaterialTheme.typography.titleSmall,
                    color = ProCutOnSurface,
                    modifier = Modifier.weight(1f),
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ProCutPrimary)
                IconButton(onClick = { onSave(mask) }, enabled = !busy) {
                    Icon(Icons.Outlined.Check, contentDescription = "Save", tint = ProCutOnSurface)
                }
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(tool, erase, tolerance, brushPercent, viewSize) {
                        detectTapGestures { position ->
                            if (tool == CutTool.AUTO) {
                                wand(position)
                            } else {
                                val point = toBitmapSpace(position)
                                pushUndo()
                                stroke(point, point)
                            }
                        }
                    }
                    .pointerInput(tool, erase, brushPercent, viewSize) {
                        if (tool == CutTool.BRUSH) {
                            var last = Offset.Zero
                            detectDragGestures(
                                onDragStart = { position ->
                                    pushUndo()
                                    last = toBitmapSpace(position)
                                    stroke(last, last)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val point = toBitmapSpace(change.position)
                                    stroke(last, point)
                                    last = point
                                },
                            )
                        }
                    },
            ) {
                version // subscribe: the mask bitmap is mutated in place
                val scale = fitScale()
                val width = frame.width * scale
                val height = frame.height * scale
                val left = (size.width - width) / 2f
                val top = (size.height - height) / 2f
                drawRect(
                    brush = ShaderBrush(ImageShader(checker, TileMode.Repeated, TileMode.Repeated)),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                )
                drawIntoCanvas { canvas ->
                    val bounds = Rect(left, top, left + width, top + height)
                    canvas.saveLayer(bounds, Paint())
                    drawImage(
                        image = frameImage,
                        dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(width.toInt(), height.toInt()),
                    )
                    drawImage(
                        image = maskImage,
                        dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(width.toInt(), height.toInt()),
                        blendMode = BlendMode.DstIn,
                    )
                    canvas.restore()
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().background(com.procutstudio.editor.ui.theme.ProCutSurface).padding(vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PanelPill("Brush", { tool = CutTool.BRUSH }, selected = tool == CutTool.BRUSH)
                    PanelPill("Auto select", { tool = CutTool.AUTO }, selected = tool == CutTool.AUTO)
                    Box(modifier = Modifier.weight(1f))
                    PanelPill("Erase", { erase = true }, selected = erase)
                    PanelPill("Restore", { erase = false }, selected = !erase)
                }
                if (tool == CutTool.BRUSH) {
                    CompactSlider(
                        label = "Brush size",
                        value = brushPercent,
                        range = 1f..15f,
                        valueLabel = "${brushPercent.toInt()}",
                        onValueChange = { brushPercent = it },
                    )
                } else {
                    CompactSlider(
                        label = "Tolerance",
                        value = tolerance,
                        range = 5f..140f,
                        valueLabel = "${tolerance.toInt()}",
                        onValueChange = { tolerance = it },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PanelPill(
                        "Undo",
                        {
                            if (undoStack.isNotEmpty()) {
                                val previous = undoStack.removeAt(undoStack.lastIndex)
                                mask.eraseColor(android.graphics.Color.TRANSPARENT)
                                maskCanvas.drawBitmap(previous, 0f, 0f, null)
                                version++
                            }
                        },
                        enabled = undoStack.isNotEmpty() && !busy,
                    )
                    PanelPill("Invert", {
                        pushUndo()
                        invertMask(mask)
                        version++
                    }, enabled = !busy)
                    PanelPill("Reset", {
                        pushUndo()
                        mask.eraseColor(android.graphics.Color.WHITE)
                        version++
                    }, enabled = !busy)
                    Box(modifier = Modifier.weight(1f))
                    if (input.existingMask != null) {
                        PanelPill("Remove cut-out", { onSave(null) }, enabled = !busy)
                    }
                }
                Text(
                    text = if (tool == CutTool.BRUSH) "Drag to ${if (erase) "erase" else "restore"} pixels." else "Tap an area to ${if (erase) "remove" else "bring back"} everything of a similar colour.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProCutOnSurfaceMuted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private fun buildMask(frame: Bitmap, existing: Bitmap?): Bitmap {
    val result = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
    if (existing == null) {
        result.eraseColor(android.graphics.Color.WHITE)
    } else {
        val scaled = if (existing.width == frame.width && existing.height == frame.height) {
            existing
        } else {
            Bitmap.createScaledBitmap(existing, frame.width, frame.height, true)
        }
        AndroidCanvas(result).drawBitmap(scaled, 0f, 0f, null)
    }
    return result
}

private fun invertMask(mask: Bitmap) {
    val pixels = IntArray(mask.width * mask.height)
    mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
    for (index in pixels.indices) {
        val alpha = (pixels[index] ushr 24) and 0xFF
        pixels[index] = ((255 - alpha) shl 24) or 0x00FFFFFF
    }
    mask.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
}

/**
 * Magic wand: flood-fills from ([startX], [startY]) over pixels whose colour is within [tolerance]
 * (0..255 euclidean distance) of the seed pixel, then clears (or restores) them in [mask].
 */
private fun floodSelect(frame: Bitmap, mask: Bitmap, startX: Int, startY: Int, tolerance: Float, erase: Boolean) {
    val width = frame.width
    val height = frame.height
    val colours = IntArray(width * height)
    frame.getPixels(colours, 0, width, 0, 0, width, height)
    val alphas = IntArray(width * height)
    mask.getPixels(alphas, 0, width, 0, 0, width, height)

    val seed = colours[startY * width + startX]
    val seedR = (seed shr 16) and 0xFF
    val seedG = (seed shr 8) and 0xFF
    val seedB = seed and 0xFF
    val limit = tolerance * tolerance
    val visited = BooleanArray(width * height)
    val stack = IntArray(width * height)
    var top = 0
    stack[top++] = startY * width + startX
    visited[startY * width + startX] = true
    val target = if (erase) 0x00FFFFFF else -1 // transparent white or opaque white

    while (top > 0) {
        val index = stack[--top]
        val x = index % width
        val y = index / width
        alphas[index] = target
        if (x > 0) top = push(index - 1, colours, visited, stack, top, seedR, seedG, seedB, limit)
        if (x < width - 1) top = push(index + 1, colours, visited, stack, top, seedR, seedG, seedB, limit)
        if (y > 0) top = push(index - width, colours, visited, stack, top, seedR, seedG, seedB, limit)
        if (y < height - 1) top = push(index + width, colours, visited, stack, top, seedR, seedG, seedB, limit)
    }
    mask.setPixels(alphas, 0, width, 0, 0, width, height)
}

private fun push(
    index: Int,
    colours: IntArray,
    visited: BooleanArray,
    stack: IntArray,
    top: Int,
    seedR: Int,
    seedG: Int,
    seedB: Int,
    limit: Float,
): Int {
    if (visited[index]) return top
    visited[index] = true
    val colour = colours[index]
    val dr = ((colour shr 16) and 0xFF) - seedR
    val dg = ((colour shr 8) and 0xFF) - seedG
    val db = (colour and 0xFF) - seedB
    if ((dr * dr + dg * dg + db * db).toFloat() > limit) return top
    stack[top] = index
    return top + 1
}

private fun checkerTile(): androidx.compose.ui.graphics.ImageBitmap {
    val cell = 14
    val bitmap = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = AndroidPaint()
    paint.color = 0xFF3A3A48.toInt()
    canvas.drawRect(0f, 0f, cell * 2f, cell * 2f, paint)
    paint.color = 0xFF50505F.toInt()
    canvas.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), paint)
    canvas.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, paint)
    return bitmap.asImageBitmap()
}
