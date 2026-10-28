package com.procutstudio.editor.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.AudioClip
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.ImageOverlay
import com.procutstudio.editor.domain.model.TextOverlay
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.domain.model.TimelineItem
import com.procutstudio.editor.ui.theme.PlayheadColor
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutSurface
import com.procutstudio.editor.ui.theme.TimelineGridLine
import com.procutstudio.editor.ui.theme.TrackAudioColor
import com.procutstudio.editor.ui.theme.TrackOverlayColor
import com.procutstudio.editor.ui.theme.TrackTextColor
import com.procutstudio.editor.ui.theme.TrackVideoColor
import kotlin.math.roundToInt

private val LABEL_WIDTH = 54.dp
private val VIDEO_TRACK_HEIGHT = 62.dp
private val OVERLAY_TRACK_HEIGHT = 40.dp
private val TEXT_TRACK_HEIGHT = 38.dp
private val AUDIO_TRACK_HEIGHT = 46.dp
private val TRACK_GAP = 6.dp

/**
 * The multi-track timeline.
 *
 * Design notes:
 *  - every lane draws items at `startUs * pixelsPerSecond`, so positions stay exact at any zoom,
 *  - the ruler and playhead are drawn outside the scroll container and offset by the scroll value,
 *    which keeps them perfectly in sync without a second scroll state,
 *  - trims and moves report deltas during the gesture and call the matching `*DragEnd` callback so
 *    the ViewModel can record exactly one undo step per gesture.
 */
@Composable
fun TimelineView(
    project: EditorProject,
    playheadUs: Long,
    selectedItemId: String?,
    pixelsPerSecond: Float,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onSelectItem: (String?) -> Unit,
    onTrimDragStart: () -> Unit,
    onTrimBy: (String, Boolean, Long) -> Unit,
    onTrimDragEnd: () -> Unit,
    onMoveClipToIndex: (String, Int) -> Unit,
    onMoveItemTo: (String, Long) -> Unit,
    onMoveDragEnd: () -> Unit,
    loadThumbnails: suspend (TimelineClip, Int, Int) -> List<Bitmap?>,
    loadWaveform: suspend (AudioClip) -> FloatArray?,
    onAddMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val tailPadding = 96.dp
    val durationUs = project.durationUs.coerceAtLeast(TimeUtils.MICROS_PER_SECOND)
    val contentWidth = with(density) {
        (((durationUs / 1_000_000f) * pixelsPerSecond) + tailPadding.toPx()).toDp()
    }
    val pxPerUs = pixelsPerSecond / 1_000_000f

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportWidthPx = constraints.maxWidth.toFloat()

        // Keep the playhead visible while playing or when the user scrubs far away.
        LaunchedEffect(playheadUs, isPlaying, pixelsPerSecond) {
            val playheadPx = playheadUs * pxPerUs
            val visibleStart = scrollState.value.toFloat()
            val visibleEnd = visibleStart + viewportWidthPx - LABEL_WIDTH.value * density.density
            if (playheadPx < visibleStart || playheadPx > visibleEnd) {
                val target = (playheadPx - viewportWidthPx / 3f).coerceAtLeast(0f)
                scrollState.scrollTo(target.toInt())
            }
        }

        Column {
            // ---------------------------------------------------------------- ruler
            Row(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                Spacer(Modifier.width(LABEL_WIDTH))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .pointerInput(pixelsPerSecond) {
                            detectTapGestures { offset ->
                                onSeek((offset.x / pxPerUs).toLong().coerceAtLeast(0L))
                            }
                        }
                        .pointerInput(pixelsPerSecond) {
                            detectDragGestures { change, _ ->
                                onSeek((change.position.x / pxPerUs).toLong().coerceAtLeast(0L))
                            }
                        },
                ) {
                    RulerLabels(
                        durationUs = durationUs,
                        pixelsPerSecond = pixelsPerSecond,
                        scrollOffsetPx = scrollState.value,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                // ------------------------------------------------------------ labels
                Column(
                    modifier = Modifier.width(LABEL_WIDTH).background(ProCutSurface),
                ) {
                    TrackLabel(Icons.Outlined.Movie, "Video", VIDEO_TRACK_HEIGHT, onClick = onAddMedia)
                    Spacer(Modifier.height(TRACK_GAP))
                    TrackLabel(Icons.Outlined.Layers, "Overlay", OVERLAY_TRACK_HEIGHT)
                    Spacer(Modifier.height(TRACK_GAP))
                    TrackLabel(Icons.Outlined.TextFields, "Text", TEXT_TRACK_HEIGHT)
                    Spacer(Modifier.height(TRACK_GAP))
                    TrackLabel(Icons.Outlined.Audiotrack, "Audio", AUDIO_TRACK_HEIGHT)
                }

                // ------------------------------------------------------------ lanes
                Box(modifier = Modifier.weight(1f).clipToBounds()) {
                    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                        Column(modifier = Modifier.requiredWidth(contentWidth)) {
                            VideoLane(
                                project = project,
                                selectedItemId = selectedItemId,
                                pxPerUs = pxPerUs,
                                height = VIDEO_TRACK_HEIGHT,
                                onSelect = onSelectItem,
                                onMoveClipToIndex = onMoveClipToIndex,
                                onMoveDragEnd = onMoveDragEnd,
                                loadThumbnails = loadThumbnails,
                                onTrimDragStart = onTrimDragStart,
                                onTrimBy = onTrimBy,
                                onTrimDragEnd = onTrimDragEnd,
                            )
                            Spacer(Modifier.height(TRACK_GAP))
                            OverlayLane(
                                project = project,
                                selectedItemId = selectedItemId,
                                pxPerUs = pxPerUs,
                                height = OVERLAY_TRACK_HEIGHT,
                                onSelect = onSelectItem,
                                onMoveItemTo = onMoveItemTo,
                                onMoveDragEnd = onMoveDragEnd,
                            )
                            Spacer(Modifier.height(TRACK_GAP))
                            TextLane(
                                project = project,
                                selectedItemId = selectedItemId,
                                pxPerUs = pxPerUs,
                                height = TEXT_TRACK_HEIGHT,
                                onSelect = onSelectItem,
                                onMoveItemTo = onMoveItemTo,
                                onMoveDragEnd = onMoveDragEnd,
                            )
                            Spacer(Modifier.height(TRACK_GAP))
                            AudioLane(
                                project = project,
                                selectedItemId = selectedItemId,
                                pxPerUs = pxPerUs,
                                height = AUDIO_TRACK_HEIGHT,
                                onSelect = onSelectItem,
                                onMoveItemTo = onMoveItemTo,
                                onMoveDragEnd = onMoveDragEnd,
                                loadWaveform = loadWaveform,
                            )
                        }
                    }

                    // -------------------------------------------------------- playhead
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val x = playheadUs * pxPerUs - scrollState.value
                        if (x >= 0f && x <= size.width) {
                            drawLine(
                                color = PlayheadColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulerLabels(
    durationUs: Long,
    pixelsPerSecond: Float,
    scrollOffsetPx: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val stepSeconds = remember(pixelsPerSecond) { chooseRulerStep(pixelsPerSecond) }
    val labelGapPx = 72.dp.value * density.density
    val totalTicks = ((durationUs / 1_000_000f) / stepSeconds).toInt() + 2
    Box(modifier = modifier) {
        for (index in 0 until totalTicks) {
            val seconds = index * stepSeconds
            val xPx = seconds * pixelsPerSecond - scrollOffsetPx
            if (xPx < -labelGapPx || xPx > 4000) continue
            Text(
                text = TimeUtils.formatRuler((seconds * 1_000_000L).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurfaceMuted,
                modifier = Modifier.offset { IntOffset(xPx.roundToInt() + 4, 2) },
            )
        }
    }
}

private fun chooseRulerStep(pixelsPerSecond: Float): Float {
    val candidates = listOf(1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f)
    return candidates.firstOrNull { it * pixelsPerSecond >= 70f } ?: 300f
}

@Composable
private fun TrackLabel(
    icon: ImageVector,
    label: String,
    height: Dp,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = ProCutOnSurfaceMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = ProCutOnSurfaceMuted, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------- lanes

@Composable
private fun VideoLane(
    project: EditorProject,
    selectedItemId: String?,
    pxPerUs: Float,
    height: Dp,
    onSelect: (String?) -> Unit,
    onMoveClipToIndex: (String, Int) -> Unit,
    onMoveDragEnd: () -> Unit,
    loadThumbnails: suspend (TimelineClip, Int, Int) -> List<Bitmap?>,
    onTrimDragStart: () -> Unit,
    onTrimBy: (String, Boolean, Long) -> Unit,
    onTrimDragEnd: () -> Unit,
) {
    val clips = project.videoClips
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(ProCutSurface)
            .pointerInput(Unit) { detectTapGestures { onSelect(null) } },
    ) {
        clips.forEachIndexed { index, clip ->
            VideoClipBlock(
                clip = clip,
                index = index,
                totalClips = clips.size,
                selected = clip.id == selectedItemId,
                pxPerUs = pxPerUs,
                height = height,
                asset = project.asset(clip.assetId),
                onSelect = { onSelect(clip.id) },
                onMoveToIndex = { target -> onMoveClipToIndex(clip.id, target) },
                onMoveDragEnd = onMoveDragEnd,
                loadThumbnails = loadThumbnails,
                onTrimDragStart = onTrimDragStart,
                onTrimBy = { isStart, deltaUs -> onTrimBy(clip.id, isStart, deltaUs) },
                onTrimDragEnd = onTrimDragEnd,
            )
        }
    }
}

@Composable
private fun VideoClipBlock(
    clip: TimelineClip,
    index: Int,
    totalClips: Int,
    selected: Boolean,
    pxPerUs: Float,
    height: Dp,
    asset: com.procutstudio.editor.domain.model.MediaAsset?,
    onSelect: () -> Unit,
    onMoveToIndex: (Int) -> Unit,
    onMoveDragEnd: () -> Unit,
    loadThumbnails: suspend (TimelineClip, Int, Int) -> List<Bitmap?>,
    onTrimDragStart: () -> Unit,
    onTrimBy: (Boolean, Long) -> Unit,
    onTrimDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    var dragOffsetPx by remember(clip.id) { mutableStateOf(0f) }
    val widthDp = with(density) { (clip.durationUs * pxPerUs).toDp() }.coerceAtLeast(26.dp)
    val widthPx = with(density) { widthDp.toPx() }

    Box(
        modifier = Modifier
            .offset { IntOffset((clip.startUs * pxPerUs).roundToInt() + dragOffsetPx.roundToInt(), 0) }
            .width(widthDp)
            .height(height)
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) TrackVideoColor else TrackVideoColor.copy(alpha = 0.62f))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else ProCutOutline,
                RoundedCornerShape(10.dp),
            )
            .pointerInput(clip.id, totalClips, widthPx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onSelect() },
                    onDragEnd = {
                        val deltaIndex = (dragOffsetPx / (widthPx + 4f)).roundToInt()
                        if (deltaIndex != 0) onMoveToIndex((index + deltaIndex).coerceIn(0, totalClips - 1))
                        dragOffsetPx = 0f
                        onMoveDragEnd()
                    },
                    onDragCancel = {
                        dragOffsetPx = 0f
                        onMoveDragEnd()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffsetPx += amount.x
                    },
                )
            }
            .clickable(onClick = onSelect),
    ) {
        if (asset != null) {
            ClipThumbnailStrip(
                clip = clip,
                asset = asset,
                widthPx = widthPx,
                loadThumbnails = loadThumbnails,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.62f)),
                    ),
                ),
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)) {
            Text(
                text = TimeUtils.formatSeconds(clip.durationUs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
            Text(
                text = clip.label.ifBlank { "Clip ${index + 1}" },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            ClipEdgeHandle(
                isStart = true,
                modifier = Modifier.align(Alignment.CenterStart),
                onDragStart = onTrimDragStart,
                onDelta = { dxPx -> onTrimBy(true, (dxPx / pxPerUs).toLong()) },
                onDragEnd = onTrimDragEnd,
            )
            ClipEdgeHandle(
                isStart = false,
                modifier = Modifier.align(Alignment.CenterEnd),
                onDragStart = onTrimDragStart,
                onDelta = { dxPx -> onTrimBy(false, (dxPx / pxPerUs).toLong()) },
                onDragEnd = onTrimDragEnd,
            )
        }
        if (clip.speed != 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text("${clip.speed}×", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

@Composable
private fun ClipThumbnailStrip(
    clip: TimelineClip,
    asset: com.procutstudio.editor.domain.model.MediaAsset,
    widthPx: Float,
    loadThumbnails: suspend (TimelineClip, Int, Int) -> List<Bitmap?>,
) {
    val frameHeightPx = with(LocalDensity.current) { VIDEO_TRACK_HEIGHT.toPx() }
    val count = ((widthPx / (frameHeightPx * 0.9f)).roundToInt()).coerceIn(1, 8)
    val frames by produceState(initialValue = emptyList<Bitmap?>(), clip.id, clip.sourceStartUs, clip.sourceEndUs, count) {
        value = loadThumbnails(clip, count, 160)
    }
    if (frames.isEmpty()) return
    Row(modifier = Modifier.fillMaxSize()) {
        frames.forEach { bitmap ->
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(TrackVideoColor))
            }
        }
    }
}

@Composable
private fun OverlayLane(
    project: EditorProject,
    selectedItemId: String?,
    pxPerUs: Float,
    height: Dp,
    onSelect: (String?) -> Unit,
    onMoveItemTo: (String, Long) -> Unit,
    onMoveDragEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(ProCutSurface)
            .pointerInput(Unit) { detectTapGestures { onSelect(null) } },
    ) {
        project.imageOverlays.forEach { overlay ->
            FloatingItemBlock(
                item = overlay,
                label = overlay.label.ifBlank { "Sticker" },
                icon = Icons.Outlined.Layers,
                colour = TrackOverlayColor,
                selected = overlay.id == selectedItemId,
                pxPerUs = pxPerUs,
                height = height,
                onSelect = { onSelect(overlay.id) },
                onMoveTo = { startUs -> onMoveItemTo(overlay.id, startUs) },
                onMoveDragEnd = onMoveDragEnd,
            )
        }
    }
}

@Composable
private fun TextLane(
    project: EditorProject,
    selectedItemId: String?,
    pxPerUs: Float,
    height: Dp,
    onSelect: (String?) -> Unit,
    onMoveItemTo: (String, Long) -> Unit,
    onMoveDragEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(ProCutSurface)
            .pointerInput(Unit) { detectTapGestures { onSelect(null) } },
    ) {
        project.textOverlays.forEach { overlay ->
            FloatingItemBlock(
                item = overlay,
                label = overlay.text.ifBlank { "Text" },
                icon = Icons.Outlined.TextFields,
                colour = TrackTextColor,
                selected = overlay.id == selectedItemId,
                pxPerUs = pxPerUs,
                height = height,
                onSelect = { onSelect(overlay.id) },
                onMoveTo = { startUs -> onMoveItemTo(overlay.id, startUs) },
                onMoveDragEnd = onMoveDragEnd,
            )
        }
    }
}

@Composable
private fun AudioLane(
    project: EditorProject,
    selectedItemId: String?,
    pxPerUs: Float,
    height: Dp,
    onSelect: (String?) -> Unit,
    onMoveItemTo: (String, Long) -> Unit,
    onMoveDragEnd: () -> Unit,
    loadWaveform: suspend (AudioClip) -> FloatArray?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(ProCutSurface)
            .pointerInput(Unit) { detectTapGestures { onSelect(null) } },
    ) {
        project.audioClips.forEach { audio ->
            val selected = audio.id == selectedItemId
            FloatingItemBlock(
                item = audio,
                label = audio.label.ifBlank { "Audio" },
                icon = Icons.Outlined.Audiotrack,
                colour = if (audio.isMuted) TrackAudioColor.copy(alpha = 0.4f) else TrackAudioColor,
                selected = selected,
                pxPerUs = pxPerUs,
                height = height,
                onSelect = { onSelect(audio.id) },
                onMoveTo = { startUs -> onMoveItemTo(audio.id, startUs) },
                onMoveDragEnd = onMoveDragEnd,
                content = {
                    WaveformCanvas(audio = audio, loadWaveform = loadWaveform)
                },
            )
        }
    }
}

@Composable
private fun WaveformCanvas(audio: AudioClip, loadWaveform: suspend (AudioClip) -> FloatArray?) {
    val peaks by produceState(initialValue = null as FloatArray?, audio.assetId, audio.sourceStartUs) {
        value = loadWaveform(audio)
    }
    val data = peaks ?: return
    Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 6.dp)) {
        val barWidth = 2.dp.toPx()
        val bars = (size.width / (barWidth * 2)).toInt().coerceAtLeast(1)
        for (index in 0 until bars) {
            val fraction = index.toFloat() / bars
            val peak = data[(fraction * data.size).toInt().coerceIn(0, data.size - 1)]
            val barHeight = (peak * size.height).coerceAtLeast(1f)
            drawLine(
                color = Color.White.copy(alpha = 0.65f),
                start = Offset(index * barWidth * 2, size.height / 2 - barHeight / 2),
                end = Offset(index * barWidth * 2, size.height / 2 + barHeight / 2),
                strokeWidth = barWidth,
            )
        }
    }
}

@Composable
private fun FloatingItemBlock(
    item: TimelineItem,
    label: String,
    icon: ImageVector,
    colour: Color,
    selected: Boolean,
    pxPerUs: Float,
    height: Dp,
    onSelect: () -> Unit,
    onMoveTo: (Long) -> Unit,
    onMoveDragEnd: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    var dragOffsetPx by remember(item.id) { mutableStateOf(0f) }
    val widthDp = with(density) { (item.durationUs * pxPerUs).toDp() }.coerceAtLeast(24.dp)
    Box(
        modifier = Modifier
            .offset { IntOffset((item.startUs * pxPerUs).roundToInt() + dragOffsetPx.roundToInt(), 0) }
            .width(widthDp)
            .height(height)
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colour.copy(alpha = if (selected) 1f else 0.66f))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else ProCutOutline,
                RoundedCornerShape(9.dp),
            )
            .pointerInput(item.id, pxPerUs) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onSelect() },
                    onDragEnd = {
                        val deltaUs = (dragOffsetPx / pxPerUs).toLong()
                        onMoveTo((item.startUs + deltaUs).coerceAtLeast(0L))
                        dragOffsetPx = 0f
                        onMoveDragEnd()
                    },
                    onDragCancel = {
                        dragOffsetPx = 0f
                        onMoveDragEnd()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffsetPx += amount.x
                    },
                )
            }
            .clickable(onClick = onSelect),
    ) {
        content?.invoke()
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Edge handle used to trim the selected clip directly on the timeline. */
@Composable
private fun ClipEdgeHandle(
    isStart: Boolean,
    modifier: Modifier,
    onDragStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier = modifier
            .width(16.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.18f))
            .pointerInput(isStart) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, amount ->
                        change.consume()
                        onDelta(amount.x)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White),
        )
    }
}
