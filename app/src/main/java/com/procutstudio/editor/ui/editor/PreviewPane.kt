package com.procutstudio.editor.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.NavigateBefore
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.procutstudio.editor.R
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.FitMode
import com.procutstudio.editor.domain.model.ClipMotion
import com.procutstudio.editor.domain.model.ImageOverlay
import com.procutstudio.editor.domain.model.KenBurnsMotion
import com.procutstudio.editor.domain.model.MediaType
import com.procutstudio.editor.domain.model.TextOverlay
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.domain.model.TransitionType
import com.procutstudio.editor.media.render.GradeParams
import com.procutstudio.editor.media.render.LookProcessor
import com.procutstudio.editor.media.render.OverlayAnimationMath
import com.procutstudio.editor.ui.components.AspectFrame
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The editing preview: a Media3 player surface inside the project's aspect frame plus an overlay
 * layer that renders text, stickers and fade curtains with the same math as the exporter.
 */
@Composable
fun PreviewPane(
    project: EditorProject,
    playheadUs: Long,
    isPlaying: Boolean,
    player: Player,
    modifier: Modifier = Modifier,
    renderTextBitmap: suspend (TextOverlay, Int) -> Bitmap?,
    onTogglePlayback: () -> Unit,
    onStepFrame: (Boolean) -> Unit,
    isMuted: Boolean = false,
    onToggleMute: () -> Unit = {},
    showSafeArea: Boolean = false,
    selectedItemId: String? = null,
    onSelectItem: (String?) -> Unit = {},
    onOverlayTransform: (String, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onOverlayTransformEnd: () -> Unit = {},
    onReplay: () -> Unit = {},
) {
    val clip = project.clipAt(playheadUs)
    val baseGeometry = PreviewGeometry.from(clip?.transform)
    // Keyframes, animations and opacity are evaluated by the same ClipMotion maths as the export.
    val geometry = if (clip != null && clip.hasMotion) {
        PreviewGeometry.withMotion(
            baseGeometry,
            ClipMotion.stateAt(clip, (playheadUs - clip.startUs).coerceAtLeast(0L)),
        )
    } else {
        baseGeometry
    }
    val surfaceColor = Color(project.canvasColorArgb)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black),
        ) {
            AspectFrame(aspectRatio = project.aspectRatio.ratio, backgroundColor = surfaceColor) { frameModifier ->
                BoxWithConstraints(modifier = frameModifier) {
                    val frameWidthPx = constraints.maxWidth.toFloat()
                    val frameHeightPx = constraints.maxHeight.toFloat()
                    val videoClip = clip?.takeIf { it.mediaType == MediaType.VIDEO }

                    AndroidView(
                        factory = { context ->
                            val view = LayoutInflater.from(context)
                                .inflate(R.layout.view_player, null, false) as PlayerView
                            view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            view
                        },
                        update = { view ->
                            if (view.player !== player) view.player = player
                            view.resizeMode = when (geometry.fitMode) {
                                FitMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                FitMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                FitMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            }
                            view.alpha = if (videoClip != null) 1f else 0f
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (videoClip != null) {
                                    Modifier.previewTransform(geometry, frameWidthPx, frameHeightPx)
                                } else {
                                    Modifier
                                },
                            ),
                    )

                    // ExoPlayer has no image renderer, so photos are drawn as stills here.
                    if (clip != null && clip.mediaType == MediaType.IMAGE) {
                        val asset = project.asset(clip.assetId)
                        if (asset != null) {
                            val rawBitmap = rememberAssetBitmap(asset.uri, frameWidthPx.toInt())
                            val bitmap = rememberLookedPhoto(rawBitmap, clip, project.canvasColorArgb)
                            if (bitmap != null) {
                                // Ken Burns: same envelope as KenBurnsEffect in the exporter.
                                val kbProgress = if (clip.durationUs > 0L) {
                                    ((playheadUs - clip.startUs).toFloat() / clip.durationUs.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                val kbScale = when (clip.transform.kenBurns) {
                                    KenBurnsMotion.ZOOM_IN -> 1f + 0.14f * kbProgress
                                    KenBurnsMotion.ZOOM_OUT -> 1.14f - 0.14f * kbProgress
                                    KenBurnsMotion.PAN_LEFT, KenBurnsMotion.PAN_RIGHT -> 1.14f
                                    KenBurnsMotion.NONE -> 1f
                                }
                                val kbShift = when (clip.transform.kenBurns) {
                                    // Exporter translates in NDC (±1 across the frame), the preview
                                    // in frame widths — hence the halved magnitude.
                                    KenBurnsMotion.PAN_LEFT -> 0.025f * (1f - 2f * kbProgress)
                                    KenBurnsMotion.PAN_RIGHT -> 0.025f * (2f * kbProgress - 1f)
                                    else -> 0f
                                }
                                val imageGeometry = geometry.copy(
                                    scaleX = geometry.scaleX * kbScale,
                                    scaleY = geometry.scaleY * kbScale,
                                    translationX = geometry.translationX + kbShift,
                                    translationY = geometry.translationY,
                                )
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = when (clip.transform.fit) {
                                        FitMode.FIT -> ContentScale.Fit
                                        FitMode.FILL -> ContentScale.Crop
                                        FitMode.STRETCH -> ContentScale.FillBounds
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .previewTransform(imageGeometry, frameWidthPx, frameHeightPx),
                                )
                            }
                        }
                    }

                    project.textOverlaysAt(playheadUs).forEach { overlay ->
                        TextOverlayPreview(
                            overlay = overlay,
                            localTimeUs = playheadUs - overlay.startUs,
                            frameWidthPx = frameWidthPx,
                            frameHeightPx = frameHeightPx,
                            renderTextBitmap = renderTextBitmap,
                            selected = overlay.id == selectedItemId,
                            onSelect = { onSelectItem(overlay.id) },
                            onTransform = { x, y, scale, rotation -> onOverlayTransform(overlay.id, x, y, scale, rotation) },
                            onTransformEnd = onOverlayTransformEnd,
                        )
                    }
                    project.imageOverlaysAt(playheadUs).forEach { overlay ->
                        val asset = project.asset(overlay.assetId)
                        if (asset != null) {
                            StickerOverlayPreview(
                                overlay = overlay,
                                localTimeUs = playheadUs - overlay.startUs,
                                frameWidthPx = frameWidthPx,
                                frameHeightPx = frameHeightPx,
                                bitmap = rememberAssetBitmap(
                                    asset.uri,
                                    (frameWidthPx * overlay.scale).toInt().coerceAtLeast(48),
                                ),
                                selected = overlay.id == selectedItemId,
                                onSelect = { onSelectItem(overlay.id) },
                                onTransform = { x, y, scale, rotation -> onOverlayTransform(overlay.id, x, y, scale, rotation) },
                                onTransformEnd = onOverlayTransformEnd,
                            )
                        }
                    }

                    CurtainPreview(project = project, playheadUs = playheadUs, clip = clip)

                    // Title/action-safe guides (Settings → "Show safe area"). Drawn last so they
                    // stay visible on top of every layer, exactly like an NLE overlay.
                    if (showSafeArea) {
                        val density = LocalDensity.current
                        val titleInset = with(density) { (frameWidthPx * 0.05f).toDp() }
                        val actionInset = with(density) { (frameWidthPx * 0.10f).toDp() }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(titleInset)
                                .border(1.dp, Color.White.copy(alpha = 0.35f)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(actionInset)
                                .border(1.dp, Color.White.copy(alpha = 0.18f)),
                        )
                    }
                }
            }
        }

        TransportBar(
            playheadUs = playheadUs,
            durationUs = project.durationUs,
            isPlaying = isPlaying,
            onTogglePlayback = onTogglePlayback,
            onStepFrame = onStepFrame,
            isMuted = isMuted,
            onToggleMute = onToggleMute,
            onReplay = onReplay,
        )
    }
}

@Composable
private fun TransportBar(
    playheadUs: Long,
    durationUs: Long,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onStepFrame: (Boolean) -> Unit,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onReplay: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${TimeUtils.formatClock(playheadUs, showMillis = true)} / ${TimeUtils.formatClock(durationUs)}",
            style = MaterialTheme.typography.labelMedium,
            color = ProCutOnSurfaceMuted,
            modifier = Modifier.width(150.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onReplay) {
            Icon(Icons.Outlined.Replay, contentDescription = "Play again from the start", tint = ProCutOnSurface)
        }
        IconButton(onClick = { onStepFrame(false) }) {
            Icon(Icons.Outlined.NavigateBefore, contentDescription = "Previous frame", tint = ProCutOnSurface)
        }
        IconButton(
            onClick = onTogglePlayback,
            modifier = Modifier.size(46.dp).clip(CircleShape).background(ProCutSurfaceElevated),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = ProCutOnSurface,
                modifier = Modifier.size(26.dp),
            )
        }
        IconButton(onClick = { onStepFrame(true) }) {
            Icon(Icons.Outlined.NavigateNext, contentDescription = "Next frame", tint = ProCutOnSurface)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleMute) {
            Icon(
                imageVector = if (isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                contentDescription = if (isMuted) "Unmute preview" else "Mute preview",
                tint = if (isMuted) ProCutOnSurfaceMuted else ProCutOnSurface,
            )
        }
    }
}

@Composable
private fun TextOverlayPreview(
    overlay: TextOverlay,
    localTimeUs: Long,
    frameWidthPx: Float,
    frameHeightPx: Float,
    renderTextBitmap: suspend (TextOverlay, Int) -> Bitmap?,
    selected: Boolean,
    onSelect: () -> Unit,
    onTransform: (Float, Float, Float, Float) -> Unit,
    onTransformEnd: () -> Unit,
) {
    val state = OverlayAnimationMath.stateAt(overlay.animation, localTimeUs, overlay.durationUs)
    if (state.alpha <= 0.01f) return
    val bitmap by produceState<Bitmap?>(initialValue = null, overlay.id, overlay.text, overlay.style, frameWidthPx) {
        value = renderTextBitmap(overlay, frameWidthPx.toInt())
    }
    val image = bitmap ?: return
    val style = overlay.style
    val widthPx = image.width * style.scale * state.scaleFactor
    val heightPx = image.height * style.scale * state.scaleFactor
    val centreX = frameWidthPx * (style.positionX + state.offsetX)
    val centreY = frameHeightPx * (style.positionY + state.offsetY)
    val density = LocalDensity.current
    val rotation = style.rotationDegrees
    Box(
        modifier = Modifier
            .offset { IntOffset((centreX - widthPx / 2f).toInt(), (centreY - heightPx / 2f).toInt()) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .graphicsLayer {
                alpha = state.alpha
                rotationZ = rotation
            }
            .overlayGestures(
                key = overlay.id,
                onStart = onSelect,
                onTransform = { pan, zoom, twist ->
                    val moved = rotatePan(pan, rotation)
                    onTransform(
                        style.positionX + moved.x / frameWidthPx,
                        style.positionY + moved.y / frameHeightPx,
                        style.scale * zoom,
                        rotation + twist,
                    )
                },
                onEnd = onTransformEnd,
            )
            .then(if (selected) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(3.dp)) else Modifier),
    ) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = overlay.text,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun StickerOverlayPreview(
    overlay: ImageOverlay,
    localTimeUs: Long,
    frameWidthPx: Float,
    frameHeightPx: Float,
    bitmap: Bitmap?,
    selected: Boolean,
    onSelect: () -> Unit,
    onTransform: (Float, Float, Float, Float) -> Unit,
    onTransformEnd: () -> Unit,
) {
    val image = bitmap ?: return
    val state = OverlayAnimationMath.stateAt(overlay.animation, localTimeUs, overlay.durationUs)
    if (overlay.opacity * state.alpha <= 0.01f) return
    val widthPx = frameWidthPx * overlay.scale * state.scaleFactor
    val heightPx = widthPx * image.height / image.width.toFloat()
    val centreX = frameWidthPx * (overlay.positionX + state.offsetX)
    val centreY = frameHeightPx * (overlay.positionY + state.offsetY)
    val density = LocalDensity.current
    val rotation = overlay.rotationDegrees
    Box(
        modifier = Modifier
            .offset { IntOffset((centreX - widthPx / 2f).toInt(), (centreY - heightPx / 2f).toInt()) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .graphicsLayer {
                alpha = overlay.opacity * state.alpha
                rotationZ = rotation
            }
            .overlayGestures(
                key = overlay.id,
                onStart = onSelect,
                onTransform = { pan, zoom, twist ->
                    val moved = rotatePan(pan, rotation)
                    onTransform(
                        overlay.positionX + moved.x / frameWidthPx,
                        overlay.positionY + moved.y / frameHeightPx,
                        overlay.scale * zoom,
                        rotation + twist,
                    )
                },
                onEnd = onTransformEnd,
            )
            .then(if (selected) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(3.dp)) else Modifier),
    ) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = overlay.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Converts a drag measured inside a rotated layer back into parent (screen) coordinates. */
private fun rotatePan(pan: androidx.compose.ui.geometry.Offset, degrees: Float): androidx.compose.ui.geometry.Offset {
    if (degrees == 0f) return pan
    val radians = Math.toRadians(degrees.toDouble())
    val cos = Math.cos(radians).toFloat()
    val sin = Math.sin(radians).toFloat()
    return androidx.compose.ui.geometry.Offset(pan.x * cos - pan.y * sin, pan.x * sin + pan.y * cos)
}

@Composable
private fun CurtainPreview(project: EditorProject, playheadUs: Long, clip: TimelineClip?) {
    if (clip == null) return
    val index = project.videoClips.indexOfFirst { it.id == clip.id }
    val next = project.videoClips.getOrNull(index + 1)
    var alpha = 0f
    val sinceStart = playheadUs - clip.startUs
    if (clip.fadeInUs > 0L && sinceStart < clip.fadeInUs) {
        alpha = 1f - (sinceStart.toFloat() / clip.fadeInUs)
    }
    if (!clip.transition.isNone) {
        val half = clip.transition.durationUs / 2
        if (half > 0L && sinceStart < half) alpha = maxOf(alpha, 1f - (sinceStart.toFloat() / half))
    }
    val untilEnd = clip.endUs - playheadUs
    if (clip.fadeOutUs > 0L && untilEnd < clip.fadeOutUs) {
        alpha = maxOf(alpha, 1f - (untilEnd.toFloat() / clip.fadeOutUs))
    }
    if (next != null && !next.transition.isNone) {
        val half = next.transition.durationUs / 2
        if (half > 0L && untilEnd < half) alpha = maxOf(alpha, 1f - (untilEnd.toFloat() / half))
    }
    if (alpha <= 0.001f) return
    val colour = if (
        clip.transition.type == TransitionType.FADE_TO_WHITE ||
        next?.transition?.type == TransitionType.FADE_TO_WHITE
    ) {
        Color.White
    } else {
        Color.Black
    }
    Box(modifier = Modifier.fillMaxSize().background(colour.copy(alpha = alpha.coerceIn(0f, 1f))))
}

/**
 * Photos have no GL surface in the preview, so grade / mask / chroma key / cut-out are applied on
 * the CPU with the same maths as the shader ([LookProcessor]). The previous result stays on screen
 * while a new one renders, and a superseded render stops at the next row.
 */
@Composable
private fun rememberLookedPhoto(base: Bitmap?, clip: TimelineClip, canvasArgb: Long): Bitmap? {
    val effects = clip.effects
    val mask = clip.mask
    val chroma = clip.chromaKey
    val cutoutUri = clip.cutoutUri
    val needsLook = clip.needsLookShader
    val result by produceState<Bitmap?>(initialValue = base, base, effects, mask, chroma, cutoutUri, canvasArgb, needsLook) {
        val source = base
        if (source == null || !needsLook) {
            value = source
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val scaled = if (source.width > PHOTO_LOOK_MAX_PX) {
                val ratio = PHOTO_LOOK_MAX_PX / source.width.toFloat()
                Bitmap.createScaledBitmap(source, PHOTO_LOOK_MAX_PX, (source.height * ratio).toInt().coerceAtLeast(1), true)
            } else {
                source
            }
            val cutout = cutoutUri?.let { BitmapFactory.decodeFile(File(it.removePrefix("file://")).absolutePath) }
            LookProcessor.process(scaled, GradeParams.from(effects), mask, chroma, cutout, canvasArgb) { ensureActive() }
        }
    }
    return result
}

private const val PHOTO_LOOK_MAX_PX = 720

/** Loads a photo, downsampled to the preview size. Re-runs only when the uri or size changes. */
@Composable
private fun rememberAssetBitmap(uri: String, targetWidthPx: Int): Bitmap? {
    val safeWidth = targetWidthPx.coerceIn(64, 2048)
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, safeWidth) {
        value = withContext(Dispatchers.IO) {
            val file = File(uri.removePrefix("file://"))
            if (!file.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= safeWidth) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    }
    return bitmap
}
