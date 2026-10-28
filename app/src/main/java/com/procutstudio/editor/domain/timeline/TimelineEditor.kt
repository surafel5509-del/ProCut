package com.procutstudio.editor.domain.timeline

import com.procutstudio.editor.core.util.IdGenerator
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.AudioClip
import com.procutstudio.editor.domain.model.ChromaKey
import com.procutstudio.editor.domain.model.ClipAnimationPreset
import com.procutstudio.editor.domain.model.ClipMask
import com.procutstudio.editor.domain.model.ClipMotion
import com.procutstudio.editor.domain.model.EffectItem
import com.procutstudio.editor.domain.model.Keyframe
import com.procutstudio.editor.domain.model.KeyframeEasing
import com.procutstudio.editor.domain.model.VideoFxType
import com.procutstudio.editor.domain.model.ClipTransform
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.ExportSettings
import com.procutstudio.editor.domain.model.ImageOverlay
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.MediaType
import com.procutstudio.editor.domain.model.OverlayAnimation
import com.procutstudio.editor.domain.model.TextOverlay
import com.procutstudio.editor.domain.model.TextStyleSpec
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.domain.model.TimelineItem
import com.procutstudio.editor.domain.model.TrackType
import com.procutstudio.editor.domain.model.TransitionConfig
import com.procutstudio.editor.domain.model.VideoEffect
import kotlin.math.roundToLong

/**
 * Pure timeline editing operations.
 *
 * Every function takes a project and returns a new project — no mutation, no side effects, no
 * coroutines. That makes the whole editing surface straightforward to unit test (see
 * `TimelineEditorTest`) and lets the ViewModel treat each call as one undoable transaction.
 */
object TimelineEditor {

    // ------------------------------------------------------------------ video lane

    fun appendAssets(project: EditorProject, assets: List<MediaAsset>): EditorProject {
        if (assets.isEmpty()) return project
        var cursor = project.videoClips.lastOrNull()?.endUs ?: 0L
        val newClips = assets.map { asset ->
            val duration = if (asset.isImage) IMAGE_DEFAULT_DURATION_US
            else asset.durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)
            val clip = TimelineClip(
                id = IdGenerator.clipId(),
                startUs = cursor,
                durationUs = duration,
                assetId = asset.id,
                mediaType = asset.mediaType,
                sourceStartUs = 0L,
                sourceEndUs = duration,
                label = asset.displayName,
            )
            cursor += duration
            clip
        }
        return project.withAssets(assets).addItems(TrackType.VIDEO, newClips)
    }

    private fun EditorProject.addItems(type: TrackType, items: List<TimelineItem>): EditorProject {
        val track = tracks.firstOrNull { it.type == type } ?: return this
        return withTrackItems(track.id, (track.items + items).sortedBy { it.startUs })
    }

    /** Splits the clip under the playhead. Returns the untouched project when nothing is splittable. */
    fun splitAtPlayhead(project: EditorProject, playheadUs: Long, trackType: TrackType = TrackType.VIDEO): EditorProject {
        val track = project.tracks.firstOrNull { it.type == trackType } ?: return project
        val item = track.items.firstOrNull { playheadUs > it.startUs && playheadUs < it.endUs } ?: return project
        return splitItem(project, item.id, playheadUs)
    }

    fun splitItem(project: EditorProject, itemId: String, atUs: Long): EditorProject {
        val track = project.trackOf(itemId) ?: return project
        val item = track.item(itemId) ?: return project
        val offset = atUs - item.startUs
        if (offset < TimeUtils.MIN_CLIP_DURATION_US || item.durationUs - offset < TimeUtils.MIN_CLIP_DURATION_US) {
            return project
        }
        val halves: Pair<TimelineItem, TimelineItem> = when (item) {
            is TimelineClip -> {
                val sourceSplit = item.sourceStartUs + (offset * item.speed).roundToLong()
                val (leftKeyframes, rightKeyframes) = splitKeyframes(item.keyframes, offset)
                val left = item.copy(
                    durationUs = offset,
                    sourceEndUs = sourceSplit,
                    fadeOutUs = 0L,
                    label = item.label,
                    keyframes = leftKeyframes,
                    animationOut = null,
                )
                val right = item.copy(
                    id = IdGenerator.clipId(),
                    startUs = atUs,
                    durationUs = item.durationUs - offset,
                    sourceStartUs = sourceSplit,
                    fadeInUs = 0L,
                    transition = TransitionConfig(),
                    keyframes = rightKeyframes,
                    animationIn = null,
                )
                left to right
            }

            is AudioClip -> {
                val left = item.copy(durationUs = offset, fadeOutUs = 0L)
                val right = item.copy(
                    id = IdGenerator.audioId(),
                    startUs = atUs,
                    durationUs = item.durationUs - offset,
                    sourceStartUs = item.sourceStartUs + offset,
                    fadeInUs = 0L,
                )
                left to right
            }

            is TextOverlay -> {
                val left = item.copy(durationUs = offset)
                val right = item.copy(id = IdGenerator.textId(), startUs = atUs, durationUs = item.durationUs - offset)
                left to right
            }

            is ImageOverlay -> {
                val left = item.copy(durationUs = offset)
                val right = item.copy(id = IdGenerator.overlayId(), startUs = atUs, durationUs = item.durationUs - offset)
                left to right
            }

            is EffectItem -> {
                val left = item.copy(durationUs = offset)
                val right = item.copy(id = IdGenerator.effectId(), startUs = atUs, durationUs = item.durationUs - offset)
                left to right
            }
        }
        val updated = track.items.map { if (it.id == itemId) halves.first else it } + halves.second
        val placed = project.withTrackItems(track.id, updated.sortedBy { it.startUs })
        return if (track.type == TrackType.VIDEO) placed.reflowVideoTrack() else placed
    }

    fun deleteItem(project: EditorProject, itemId: String): EditorProject {
        val track = project.trackOf(itemId) ?: return project
        val remaining = track.items.filterNot { it.id == itemId }
        val updated = project.withTrackItems(track.id, remaining)
        return if (track.type == TrackType.VIDEO) updated.reflowVideoTrack() else updated
    }

    fun duplicateItem(project: EditorProject, itemId: String): EditorProject {
        val track = project.trackOf(itemId) ?: return project
        val item = track.item(itemId) ?: return project
        val copy: TimelineItem = when (item) {
            is TimelineClip -> item.copy(id = IdGenerator.clipId(), transition = TransitionConfig())
            is AudioClip -> item.copy(id = IdGenerator.audioId())
            is TextOverlay -> item.copy(id = IdGenerator.textId())
            is ImageOverlay -> item.copy(id = IdGenerator.overlayId())
            is EffectItem -> item.copy(id = IdGenerator.effectId())
        }
        val insertAt = if (track.type == TrackType.VIDEO) {
            val index = track.sortedItems.indexOfFirst { it.id == itemId }
            track.items.toMutableList().apply { add((index + 1).coerceAtMost(size), copy) }
        } else {
            val shifted = copy.shiftedBy(item.durationUs)
            (track.items + shifted)
        }
        val updated = project.withTrackItems(track.id, insertAt.sortedBy { it.startUs })
        return if (track.type == TrackType.VIDEO) updated.reflowVideoTrack() else updated
    }

    /** Drag-and-drop reordering of the main lane. */
    fun moveClipToIndex(project: EditorProject, clipId: String, targetIndex: Int): EditorProject {
        val track = project.videoTrack ?: return project
        val ordered = track.sortedItems.toMutableList()
        val from = ordered.indexOfFirst { it.id == clipId }
        if (from < 0) return project
        val to = targetIndex.coerceIn(0, ordered.lastIndex)
        if (from == to) return project
        val moved = ordered.removeAt(from)
        ordered.add(to, moved)
        return project.withTrackItems(track.id, ordered).reflowVideoTrack()
    }

    fun moveClipBy(project: EditorProject, clipId: String, deltaUs: Long): EditorProject {
        val track = project.videoTrack ?: return project
        val ordered = track.sortedItems.toMutableList()
        val from = ordered.indexOfFirst { it.id == clipId }
        if (from < 0) return project
        val to = (from + if (deltaUs > 0) 1 else -1).coerceIn(0, ordered.lastIndex)
        if (to == from) return project
        val moved = ordered.removeAt(from)
        ordered.add(to, moved)
        return project.withTrackItems(track.id, ordered).reflowVideoTrack()
    }

    /** Moves a free-floating item (text, overlay, audio) along the timeline. */
    fun moveItemTo(project: EditorProject, itemId: String, newStartUs: Long): EditorProject {
        val track = project.trackOf(itemId) ?: return project
        if (track.type == TrackType.VIDEO) return project
        val item = track.item(itemId) ?: return project
        val clampedStart = newStartUs.coerceAtLeast(0L)
        val moved = item.withStart(clampedStart)
        return project.withTrackItems(track.id, track.items.map { if (it.id == itemId) moved else it }.sortedBy { it.startUs })
    }

    /** Trims the head of an item by [deltaUs] (positive trims content away from the head). */
    fun trimStartBy(project: EditorProject, itemId: String, deltaUs: Long): EditorProject {
        val track = project.trackOf(itemId) ?: return project
        val item = track.item(itemId) ?: return project
        val updated: TimelineItem = when (item) {
            is TimelineClip -> {
                // Trim maths happens in source time; the timeline length follows from the speed.
                val maxSourceDelta = ((item.durationUs - TimeUtils.MIN_CLIP_DURATION_US) * item.speed).roundToLong()
                val sourceDelta = (deltaUs * item.speed).roundToLong()
                    .coerceIn(-item.sourceStartUs, maxSourceDelta.coerceAtLeast(0L))
                val newSourceStart = item.sourceStartUs + sourceDelta
                // Keep at least one frame of source material regardless of the rounding above.
                val safeStart = newSourceStart.coerceIn(0L, item.sourceEndUs - TimeUtils.MIN_CLIP_DURATION_US)
                val newDuration = ((item.sourceEndUs - safeStart) / item.speed).roundToLong()
                    .coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)
                item.copy(
                    sourceStartUs = safeStart,
                    durationUs = newDuration,
                    startUs = (item.startUs + (item.durationUs - newDuration)).coerceAtLeast(0L),
                )
            }

            is AudioClip -> {
                val maxDelta = item.durationUs - TimeUtils.MIN_CLIP_DURATION_US
                val delta = deltaUs.coerceIn(-item.sourceStartUs, maxDelta)
                item.copy(
                    sourceStartUs = item.sourceStartUs + delta,
                    durationUs = item.durationUs - delta,
                    startUs = (item.startUs + delta).coerceAtLeast(0L),
                )
            }

            is TextOverlay -> {
                val delta = deltaUs.coerceIn(-item.startUs, item.durationUs - TimeUtils.MIN_CLIP_DURATION_US)
                item.copy(startUs = item.startUs + delta, durationUs = item.durationUs - delta)
            }

            is ImageOverlay -> {
                val delta = deltaUs.coerceIn(-item.startUs, item.durationUs - TimeUtils.MIN_CLIP_DURATION_US)
                item.copy(startUs = item.startUs + delta, durationUs = item.durationUs - delta)
            }

            is EffectItem -> {
                val delta = deltaUs.coerceIn(-item.startUs, item.durationUs - TimeUtils.MIN_CLIP_DURATION_US)
                item.copy(startUs = item.startUs + delta, durationUs = item.durationUs - delta)
            }
        }
        val placed = project.withTrackItems(
            track.id,
            track.items.map { if (it.id == itemId) updated else it }.sortedBy { it.startUs },
        )
        return if (track.type == TrackType.VIDEO) placed.reflowVideoTrack() else placed
    }

    /** Trims the tail of an item by [deltaUs] (positive shortens it). */
    fun trimEndBy(project: EditorProject, itemId: String, deltaUs: Long): EditorProject {
        val track = project.trackOf(itemId) ?: return project
        val item = track.item(itemId) ?: return project
        val updated: TimelineItem = when (item) {
            is TimelineClip -> {
                val maxDelta = item.durationUs - TimeUtils.MIN_CLIP_DURATION_US
                item.withSourceRange(item.sourceStartUs, item.sourceEndUs - (deltaUs.coerceIn(-maxDelta, maxDelta) * item.speed).roundToLong())
            }

            is AudioClip -> item.copy(
                durationUs = (item.durationUs - deltaUs).coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
            )

            is TextOverlay -> item.copy(
                durationUs = (item.durationUs - deltaUs).coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
            )

            is ImageOverlay -> item.copy(
                durationUs = (item.durationUs - deltaUs).coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
            )

            is EffectItem -> item.copy(
                durationUs = (item.durationUs - deltaUs).coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
            )
        }
        val placed = project.withTrackItems(
            track.id,
            track.items.map { if (it.id == itemId) updated else it },
        )
        return if (track.type == TrackType.VIDEO) placed.reflowVideoTrack() else placed
    }

    /** Sets an exact timeline length; used by the trim panel's numeric fields. */
    fun setItemDuration(project: EditorProject, itemId: String, durationUs: Long): EditorProject {
        val track = project.trackOf(itemId) ?: return project
        val item = track.item(itemId) ?: return project
        val target = durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)
        val updated: TimelineItem = when (item) {
            is TimelineClip -> item.withSourceRange(
                item.sourceStartUs,
                item.sourceStartUs + (target * item.speed).roundToLong(),
            )
            is AudioClip -> item.copy(durationUs = target)
            is TextOverlay -> item.copy(durationUs = target)
            is ImageOverlay -> item.copy(durationUs = target)
            is EffectItem -> item.copy(durationUs = target)
        }
        val placed = project.withTrackItems(track.id, track.items.map { if (it.id == itemId) updated else it })
        return if (track.type == TrackType.VIDEO) placed.reflowVideoTrack() else placed
    }

    // ------------------------------------------------------------------ per-item properties

    fun updateClip(project: EditorProject, clipId: String, change: (TimelineClip) -> TimelineClip): EditorProject {
        val track = project.trackOf(clipId) ?: return project
        val clip = track.item(clipId) as? TimelineClip ?: return project
        val updated = change(clip).sanitized()
        val placed = project.withTrackItems(track.id, track.items.map { if (it.id == clipId) updated else it })
        return placed.reflowVideoTrack()
    }

    fun updateText(project: EditorProject, textId: String, change: (TextOverlay) -> TextOverlay): EditorProject {
        val track = project.trackOf(textId) ?: return project
        val overlay = track.item(textId) as? TextOverlay ?: return project
        val updated = change(overlay).sanitized()
        return project.withTrackItems(track.id, track.items.map { if (it.id == textId) updated else it })
    }

    fun updateImageOverlay(project: EditorProject, overlayId: String, change: (ImageOverlay) -> ImageOverlay): EditorProject {
        val track = project.trackOf(overlayId) ?: return project
        val overlay = track.item(overlayId) as? ImageOverlay ?: return project
        val updated = change(overlay).sanitized()
        return project.withTrackItems(track.id, track.items.map { if (it.id == overlayId) updated else it })
    }

    fun updateAudio(project: EditorProject, audioId: String, change: (AudioClip) -> AudioClip): EditorProject {
        val track = project.trackOf(audioId) ?: return project
        val clip = track.item(audioId) as? AudioClip ?: return project
        val updated = change(clip).sanitized()
        return project.withTrackItems(track.id, track.items.map { if (it.id == audioId) updated else it })
    }

    fun setSpeed(project: EditorProject, clipId: String, speed: Float): EditorProject =
        updateClip(project, clipId, { it.withSpeed(speed) })

    fun setVolume(project: EditorProject, itemId: String, volume: Float): EditorProject {
        val item = project.item(itemId)
        return when (item) {
            is TimelineClip -> updateClip(project, itemId) { it.copy(volume = volume.coerceIn(0f, 2f)) }
            is AudioClip -> updateAudio(project, itemId) { it.copy(volume = volume.coerceIn(0f, 2f)) }
            else -> project
        }
    }

    fun setMuted(project: EditorProject, itemId: String, muted: Boolean): EditorProject {
        val item = project.item(itemId)
        return when (item) {
            is TimelineClip -> updateClip(project, itemId) { it.copy(isMuted = muted) }
            is AudioClip -> updateAudio(project, itemId) { it.copy(isMuted = muted) }
            else -> project
        }
    }

    fun setTransform(project: EditorProject, clipId: String, transform: ClipTransform): EditorProject =
        updateClip(project, clipId) { it.copy(transform = transform) }

    fun setEffects(project: EditorProject, clipId: String, effects: VideoEffect): EditorProject =
        updateClip(project, clipId) { it.copy(effects = effects) }

    fun setFades(project: EditorProject, clipId: String, fadeInUs: Long, fadeOutUs: Long): EditorProject =
        updateClip(project, clipId) { it.copy(fadeInUs = fadeInUs, fadeOutUs = fadeOutUs) }

    fun setTransition(project: EditorProject, clipId: String, transition: TransitionConfig): EditorProject =
        updateClip(project, clipId) { it.copy(transition = transition) }

    fun setAudioFades(project: EditorProject, audioId: String, fadeInUs: Long, fadeOutUs: Long): EditorProject =
        updateAudio(project, audioId) { it.copy(fadeInUs = fadeInUs, fadeOutUs = fadeOutUs) }

    /** Swaps the source of a clip while keeping its trim window, speed and effects. */
    fun replaceClipAsset(project: EditorProject, clipId: String, asset: MediaAsset): EditorProject {
        val updated = updateClip(project, clipId) { clip ->
            val duration = if (asset.isImage) clip.durationUs else asset.durationUs
            clip.copy(
                assetId = asset.id,
                mediaType = asset.mediaType,
                sourceStartUs = 0L,
                sourceEndUs = duration.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
                durationUs = duration.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
                label = asset.displayName,
            )
        }
        return updated.withAssets(listOf(asset))
    }

    // ------------------------------------------------------------------ creation helpers

    fun addTextOverlay(
        project: EditorProject,
        text: String,
        startUs: Long,
        durationUs: Long = TEXT_DEFAULT_DURATION_US,
        style: TextStyleSpec = TextStyleSpec.Default,
        animation: OverlayAnimation = OverlayAnimation(),
    ): EditorProject {
        val overlay = TextOverlay(
            id = IdGenerator.textId(),
            startUs = startUs.coerceAtLeast(0L),
            durationUs = durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
            text = text,
            style = style,
            animation = animation,
            label = text.take(24),
        )
        return project.addItems(TrackType.TEXT, listOf(overlay))
    }

    fun addAudioClip(
        project: EditorProject,
        asset: MediaAsset,
        startUs: Long,
        sourceStartUs: Long = 0L,
        durationUs: Long = asset.durationUs,
        volume: Float = 1f,
        label: String = asset.displayName,
        isRecording: Boolean = false,
    ): EditorProject {
        val length = durationUs.coerceIn(
            TimeUtils.MIN_CLIP_DURATION_US,
            (asset.durationUs - sourceStartUs).coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
        )
        val audio = AudioClip(
            id = IdGenerator.audioId(),
            startUs = startUs.coerceAtLeast(0L),
            durationUs = length,
            assetId = asset.id,
            sourceStartUs = sourceStartUs.coerceAtLeast(0L),
            volume = volume,
            label = label,
            isRecording = isRecording,
        )
        return project.withAssets(listOf(asset)).addItems(TrackType.AUDIO, listOf(audio))
    }

    fun addImageOverlay(
        project: EditorProject,
        asset: MediaAsset,
        startUs: Long,
        durationUs: Long = OVERLAY_DEFAULT_DURATION_US,
        scale: Float = 0.4f,
        positionX: Float = 0.5f,
        positionY: Float = 0.5f,
        cornerRadius: Float = 0f,
    ): EditorProject {
        val overlay = ImageOverlay(
            id = IdGenerator.overlayId(),
            startUs = startUs.coerceAtLeast(0L),
            durationUs = durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
            assetId = asset.id,
            scale = scale,
            positionX = positionX,
            positionY = positionY,
            cornerRadius = cornerRadius,
            label = asset.displayName,
        )
        return project.withAssets(listOf(asset)).addItems(TrackType.OVERLAY, listOf(overlay))
    }

    /** Adds a trimmed take recorded from the microphone. */
    fun addVoiceRecording(project: EditorProject, asset: MediaAsset, startUs: Long): EditorProject =
        addAudioClip(
            project = project,
            asset = asset,
            startUs = startUs,
            volume = 1f,
            label = "Voice-over",
            isRecording = true,
        )

    // ------------------------------------------------------------------ project level

    fun setAspectRatio(project: EditorProject, aspectRatio: com.procutstudio.editor.domain.model.AspectRatio): EditorProject =
        project.copy(aspectRatio = aspectRatio)

    fun setExportSettings(project: EditorProject, settings: ExportSettings): EditorProject =
        project.copy(exportSettings = settings)

    fun setCanvasColor(project: EditorProject, argb: Long): EditorProject =
        project.copy(canvasColorArgb = argb)

    // ------------------------------------------------------------------ effects lane

    /**
     * Drops an effect on the effects lane at [startUs]. The item is clamped so it never starts
     * past the end of the project and never runs beyond it (unless the project is shorter than the
     * minimum effect length).
     */
    fun addEffect(
        project: EditorProject,
        type: VideoFxType,
        startUs: Long,
        durationUs: Long = FX_DEFAULT_DURATION_US,
        intensity: Float = 0.7f,
    ): EditorProject {
        val total = project.durationUs
        val start = if (total > 0L) startUs.coerceIn(0L, (total - TimeUtils.MIN_CLIP_DURATION_US).coerceAtLeast(0L)) else 0L
        val room = if (total > 0L) total - start else durationUs
        val length = durationUs.coerceAtMost(room).coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)
        val item = EffectItem(
            id = IdGenerator.effectId(),
            startUs = start,
            durationUs = length,
            type = type,
            intensity = intensity,
            label = type.label,
        )
        return project.addItems(TrackType.EFFECT, listOf(item))
    }

    fun updateEffect(project: EditorProject, effectId: String, change: (EffectItem) -> EffectItem): EditorProject {
        val track = project.trackOf(effectId) ?: return project
        val item = track.item(effectId) as? EffectItem ?: return project
        val updated = change(item).sanitized()
        return project.withTrackItems(
            track.id,
            track.items.map { if (it.id == effectId) updated else it }.sortedBy { it.startUs },
        )
    }

    /** Stretches an effect over the entire video. */
    fun spreadEffectOverProject(project: EditorProject, effectId: String): EditorProject =
        updateEffect(project, effectId) { it.copy(startUs = 0L, durationUs = project.durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)) }

    // ------------------------------------------------------------------ clip tools

    fun setMask(project: EditorProject, clipId: String, mask: ClipMask): EditorProject =
        updateClip(project, clipId) { it.copy(mask = mask) }

    fun setChromaKey(project: EditorProject, clipId: String, key: ChromaKey): EditorProject =
        updateClip(project, clipId) { it.copy(chromaKey = key) }

    fun setOpacity(project: EditorProject, clipId: String, opacity: Float): EditorProject =
        updateClip(project, clipId) { it.copy(opacity = opacity) }

    fun setCutout(project: EditorProject, clipId: String, uri: String?): EditorProject =
        updateClip(project, clipId) { it.copy(cutoutUri = uri) }

    fun setKeyframes(project: EditorProject, clipId: String, keyframes: List<Keyframe>): EditorProject =
        updateClip(project, clipId) { it.copy(keyframes = keyframes) }

    /**
     * Adds or replaces the keyframe at [localTimeUs] (clip-relative). Keyframes closer than
     * [KEYFRAME_SNAP_US] to an existing one overwrite it, so tapping the diamond twice on the same
     * frame never stacks duplicates.
     */
    fun upsertKeyframe(project: EditorProject, clipId: String, localTimeUs: Long, state: com.procutstudio.editor.domain.model.MotionState, easing: KeyframeEasing? = null): EditorProject =
        updateClip(project, clipId) { clip ->
            val time = localTimeUs.coerceIn(0L, clip.durationUs)
            val existing = clip.keyframes.firstOrNull { kotlin.math.abs(it.timeUs - time) <= KEYFRAME_SNAP_US }
            val keyframe = Keyframe(
                timeUs = existing?.timeUs ?: time,
                scale = state.scale,
                offsetX = state.offsetX,
                offsetY = state.offsetY,
                rotationDegrees = state.rotationDegrees,
                opacity = state.opacity,
                easing = easing ?: existing?.easing ?: KeyframeEasing.EASE_IN_OUT,
            )
            clip.copy(keyframes = (clip.keyframes.filter { it !== existing } + keyframe).sortedBy { it.timeUs })
        }

    fun removeKeyframeAt(project: EditorProject, clipId: String, localTimeUs: Long): EditorProject =
        updateClip(project, clipId) { clip ->
            val target = clip.keyframes.firstOrNull { kotlin.math.abs(it.timeUs - localTimeUs) <= KEYFRAME_SNAP_US }
            if (target == null) clip else clip.copy(keyframes = clip.keyframes.filter { it !== target })
        }

    fun setKeyframeEasing(project: EditorProject, clipId: String, localTimeUs: Long, easing: KeyframeEasing): EditorProject =
        updateClip(project, clipId) { clip ->
            clip.copy(
                keyframes = clip.keyframes.map {
                    if (kotlin.math.abs(it.timeUs - localTimeUs) <= KEYFRAME_SNAP_US) it.copy(easing = easing) else it
                },
            )
        }

    /** Sets (or clears, with null) the in / out / combo animation of a clip. */
    fun setAnimation(
        project: EditorProject,
        clipId: String,
        group: com.procutstudio.editor.domain.model.AnimationGroup,
        preset: ClipAnimationPreset?,
    ): EditorProject = updateClip(project, clipId) { clip ->
        when (group) {
            com.procutstudio.editor.domain.model.AnimationGroup.IN -> clip.copy(animationIn = preset)
            com.procutstudio.editor.domain.model.AnimationGroup.OUT -> clip.copy(animationOut = preset)
            com.procutstudio.editor.domain.model.AnimationGroup.COMBO -> clip.copy(animationCombo = preset)
        }
    }

    fun setAnimationDuration(project: EditorProject, clipId: String, durationUs: Long): EditorProject =
        updateClip(project, clipId) { it.copy(animationDurationUs = durationUs) }

    /**
     * Swaps a clip's footage for a rendered reversed copy. The reversed file only contains the
     * clip's trimmed range, so the source window becomes the whole file.
     */
    fun applyReversedAsset(project: EditorProject, clipId: String, reversed: MediaAsset): EditorProject {
        val updated = updateClip(project, clipId) { clip ->
            clip.copy(assetId = reversed.id, isReversed = !clip.isReversed, keyframes = clip.keyframes)
                .withSourceRange(0L, reversed.durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US))
        }
        return updated.withAssets(listOf(reversed))
    }

    /**
     * CapCut-style freeze frame: the clip is split at [atUs] and a still of that frame, shown for
     * [durationUs], is inserted between the halves. [frameAsset] is the already-extracted picture.
     */
    fun freezeFrame(
        project: EditorProject,
        clipId: String,
        atUs: Long,
        frameAsset: MediaAsset,
        durationUs: Long = FREEZE_DEFAULT_DURATION_US,
    ): EditorProject {
        val trackId = project.videoTrack?.id ?: return project
        val clip = project.videoClips.firstOrNull { it.id == clipId } ?: return project
        val offset = (atUs - clip.startUs).coerceIn(0L, clip.durationUs)
        val length = durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)
        val freeze = clip.copy(
            id = IdGenerator.clipId(),
            assetId = frameAsset.id,
            mediaType = MediaType.IMAGE,
            sourceStartUs = 0L,
            sourceEndUs = length,
            durationUs = length,
            speed = 1f,
            isMuted = true,
            fadeInUs = 0L,
            fadeOutUs = 0L,
            transition = TransitionConfig(),
            keyframes = emptyList(),
            animationIn = null,
            animationOut = null,
            animationCombo = null,
            cutoutUri = clip.cutoutUri,
            isReversed = false,
            transform = clip.transform.copy(kenBurns = com.procutstudio.editor.domain.model.KenBurnsMotion.NONE),
            label = "Freeze frame",
        )
        val split = splitItem(project, clipId, clip.startUs + offset)
        val lane = split.videoClips
        val leftIndex = lane.indexOfFirst { it.id == clipId }
        if (leftIndex < 0) return project
        val insertIndex = if (split !== project) {
            leftIndex + 1
        } else if (offset < clip.durationUs / 2) {
            leftIndex
        } else {
            leftIndex + 1
        }
        val newLane = lane.toMutableList().apply { add(insertIndex, freeze) }
        return split.withAssets(listOf(frameAsset)).withTrackItems(trackId, newLane).reflowVideoTrack()
    }

    /**
     * Copies a video clip's sound onto the audio lane (at the clip's position) and mutes the clip,
     * which is how "Extract audio" works in mobile editors.
     */
    fun extractAudio(project: EditorProject, clipId: String): EditorProject {
        val clip = project.videoClips.firstOrNull { it.id == clipId } ?: return project
        val asset = project.asset(clip.assetId) ?: return project
        if (!asset.isVideo || !asset.hasAudio) return project
        val audio = AudioClip(
            id = IdGenerator.audioId(),
            startUs = clip.startUs,
            durationUs = clip.sourceDurationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
            assetId = asset.id,
            sourceStartUs = clip.sourceStartUs,
            volume = clip.volume.coerceIn(0f, 2f),
            fadeInUs = 0L,
            fadeOutUs = 0L,
            label = "Audio · ${clip.label.ifBlank { asset.displayName }}".take(40),
        )
        return setMuted(project.addItems(TrackType.AUDIO, listOf(audio)), clipId, true)
    }

    /** Copies one grade onto every clip of the main lane ("Apply to all"). */
    fun applyGradeToAll(project: EditorProject, effects: VideoEffect): EditorProject =
        project.videoClips.fold(project) { acc, clip -> setEffects(acc, clip.id, effects) }

    private fun splitKeyframes(keyframes: List<Keyframe>, offsetUs: Long): Pair<List<Keyframe>, List<Keyframe>> {
        if (keyframes.isEmpty()) return emptyList<Keyframe>() to emptyList()
        val boundary = ClipMotion.keyframeState(keyframes, offsetUs)
        val sorted = keyframes.sortedBy { it.timeUs }
        val left = sorted.filter { it.timeUs < offsetUs } + Keyframe(
            timeUs = offsetUs,
            scale = boundary.scale,
            offsetX = boundary.offsetX,
            offsetY = boundary.offsetY,
            rotationDegrees = boundary.rotationDegrees,
            opacity = boundary.opacity,
        )
        val right = listOf(
            Keyframe(
                timeUs = 0L,
                scale = boundary.scale,
                offsetX = boundary.offsetX,
                offsetY = boundary.offsetY,
                rotationDegrees = boundary.rotationDegrees,
                opacity = boundary.opacity,
            ),
        ) + sorted.filter { it.timeUs > offsetUs }.map { it.copy(timeUs = it.timeUs - offsetUs) }
        return left to right
    }

    const val KEYFRAME_SNAP_US = 60_000L
    val FX_DEFAULT_DURATION_US = 3 * TimeUtils.MICROS_PER_SECOND
    val FREEZE_DEFAULT_DURATION_US = 2 * TimeUtils.MICROS_PER_SECOND

    val IMAGE_DEFAULT_DURATION_US = 3 * TimeUtils.MICROS_PER_SECOND
    val TEXT_DEFAULT_DURATION_US = 3 * TimeUtils.MICROS_PER_SECOND
    val OVERLAY_DEFAULT_DURATION_US = 3 * TimeUtils.MICROS_PER_SECOND

    private fun TimelineItem.withStart(newStartUs: Long): TimelineItem = when (this) {
        is TimelineClip -> copy(startUs = newStartUs)
        is TextOverlay -> copy(startUs = newStartUs)
        is ImageOverlay -> copy(startUs = newStartUs)
        is AudioClip -> copy(startUs = newStartUs)
        is EffectItem -> copy(startUs = newStartUs)
    }

    private fun TimelineItem.shiftedBy(deltaUs: Long): TimelineItem = when (this) {
        is TimelineClip -> copy(startUs = startUs + deltaUs)
        is TextOverlay -> copy(startUs = startUs + deltaUs)
        is ImageOverlay -> copy(startUs = startUs + deltaUs)
        is AudioClip -> copy(startUs = startUs + deltaUs)
        is EffectItem -> copy(startUs = startUs + deltaUs)
    }

    /** True when the item is a video clip that can be split at [us]. */
    fun canSplitAt(project: EditorProject, us: Long): Boolean {
        val clip = project.clipAt(us) ?: return false
        val offset = us - clip.startUs
        return offset >= TimeUtils.MIN_CLIP_DURATION_US && clip.durationUs - offset >= TimeUtils.MIN_CLIP_DURATION_US
    }

    fun hasAudio(item: TimelineItem): Boolean = when (item) {
        is TimelineClip -> item.mediaType == MediaType.VIDEO
        is AudioClip -> true
        else -> false
    }
}
