package com.procutstudio.editor.domain.model

import com.procutstudio.editor.core.util.IdGenerator
import com.procutstudio.editor.core.util.TimeUtils
import kotlinx.serialization.Serializable

/** A lane of the timeline. Video is the main (contiguous) lane; the others hold free-floating items. */
@Serializable
data class TimelineTrack(
    val id: String,
    val type: TrackType,
    val name: String = type.displayName,
    val isMuted: Boolean = false,
    val isHidden: Boolean = false,
    val items: List<TimelineItem> = emptyList(),
) {
    val sortedItems: List<TimelineItem> get() = items.sortedBy { it.startUs }

    fun item(id: String): TimelineItem? = items.firstOrNull { it.id == id }

    fun itemAt(us: Long): TimelineItem? =
        items.firstOrNull { us >= it.startUs && us < it.endUs }
}

/**
 * The whole editing document. This is the single source of truth: the database stores this object
 * serialised as JSON, undo/redo keeps previous copies, and the exporter walks it to build a Media3
 * `Composition`. Source media is never modified, so editing is non-destructive by construction.
 */
@Serializable
data class EditorProject(
    val id: String,
    val name: String,
    val schemaVersion: Int = ProjectSchema.CURRENT_VERSION,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    val canvasMode: CanvasMode = CanvasMode.SOLID,
    /** Packed ARGB colour drawn behind letterboxed footage. */
    val canvasColorArgb: Long = 0xFF000000,
    val tracks: List<TimelineTrack> = emptyList(),
    val assets: List<MediaAsset> = emptyList(),
    val exportSettings: ExportSettings = ExportSettings(),
) {
    // ---------------------------------------------------------------- lookups

    val videoTrack: TimelineTrack?
        get() = tracks.firstOrNull { it.type == TrackType.VIDEO }

    val overlayTrack: TimelineTrack?
        get() = tracks.firstOrNull { it.type == TrackType.OVERLAY }

    val textTrack: TimelineTrack?
        get() = tracks.firstOrNull { it.type == TrackType.TEXT }

    val audioTrack: TimelineTrack?
        get() = tracks.firstOrNull { it.type == TrackType.AUDIO }

    val videoClips: List<TimelineClip>
        get() = videoTrack?.items.orEmpty().filterIsInstance<TimelineClip>().sortedBy { it.startUs }

    val imageOverlays: List<ImageOverlay>
        get() = overlayTrack?.items.orEmpty().filterIsInstance<ImageOverlay>().sortedBy { it.startUs }

    val textOverlays: List<TextOverlay>
        get() = textTrack?.items.orEmpty().filterIsInstance<TextOverlay>().sortedBy { it.startUs }

    val audioClips: List<AudioClip>
        get() = audioTrack?.items.orEmpty().filterIsInstance<AudioClip>().sortedBy { it.startUs }

    val allItems: List<TimelineItem> get() = tracks.flatMap { it.items }

    val durationUs: Long
        get() = allItems.maxOfOrNull { it.endUs } ?: 0L

    val isEmpty: Boolean get() = allItems.isEmpty()

    fun asset(assetId: String?): MediaAsset? =
        if (assetId == null) null else assets.firstOrNull { it.id == assetId }

    fun trackOf(itemId: String): TimelineTrack? =
        tracks.firstOrNull { track -> track.items.any { it.id == itemId } }

    fun item(id: String): TimelineItem? = allItems.firstOrNull { it.id == id }

    fun clipAt(us: Long): TimelineClip? =
        videoClips.firstOrNull { us >= it.startUs && us < it.endUs }

    fun textOverlaysAt(us: Long): List<TextOverlay> =
        textOverlays.filter { us >= it.startUs && us < it.endUs }

    fun imageOverlaysAt(us: Long): List<ImageOverlay> =
        imageOverlays.filter { us >= it.startUs && us < it.endUs }

    /** Ordered list of media used by the same asset id, used by the media inspector. */
    fun usagesOf(assetId: String): Int = allItems.count {
        when (it) {
            is TimelineClip -> it.assetId == assetId
            is AudioClip -> it.assetId == assetId
            is ImageOverlay -> it.assetId == assetId
            is TextOverlay -> false
        }
    }

    // ---------------------------------------------------------------- mutation helpers

    fun touch(nowMs: Long = System.currentTimeMillis()): EditorProject = copy(updatedAtMs = nowMs)

    fun renamed(newName: String): EditorProject = copy(name = newName.trim().ifBlank { name })

    fun withTrack(track: TimelineTrack): EditorProject =
        copy(tracks = tracks.map { if (it.id == track.id) track else it })

    fun withTrackItems(trackId: String, items: List<TimelineItem>): EditorProject {
        val target = tracks.firstOrNull { it.id == trackId } ?: return this
        return withTrack(target.copy(items = items))
    }

    fun addItem(trackType: TrackType, item: TimelineItem): EditorProject {
        val target = tracks.firstOrNull { it.type == trackType } ?: return this
        val updated = (target.items + item).sortedBy { it.startUs }
        return withTrackItems(target.id, updated)
    }

    fun replaceItem(item: TimelineItem): EditorProject {
        val target = trackOf(item.id) ?: return this
        val updated = target.items.map { if (it.id == item.id) item else it }.sortedBy { it.startUs }
        return withTrackItems(target.id, updated)
    }

    fun removeItem(itemId: String): EditorProject {
        val target = trackOf(itemId) ?: return this
        return withTrackItems(target.id, target.items.filterNot { it.id == itemId })
    }

    fun withAssets(newAssets: List<MediaAsset>): EditorProject {
        val byId = (assets + newAssets).distinctBy { it.id }
        return copy(assets = byId)
    }

    /**
     * The main video lane has no gaps: clip N starts exactly where clip N-1 ends. Every structural
     * edit calls this so `startUs` can never drift out of sync with the clip order.
     */
    fun reflowVideoTrack(): EditorProject {
        val target = videoTrack ?: return this
        var cursor = 0L
        val reflowed = target.items.filterIsInstance<TimelineClip>()
            .sortedBy { it.startUs }
            .map { clip ->
                val sanitized = clip.sanitized()
                val placed = sanitized.copy(startUs = cursor)
                cursor += placed.durationUs
                placed
            }
        return withTrackItems(target.id, reflowed)
    }

    fun sanitized(): EditorProject = reflowVideoTrack().let { base ->
        val cleanedTracks = base.tracks.map { track ->
            when (track.type) {
                TrackType.VIDEO -> track.copy(items = track.items.filterIsInstance<TimelineClip>().map { it.sanitized() })
                TrackType.TEXT -> track.copy(items = track.items.filterIsInstance<TextOverlay>().map { it.sanitized() })
                TrackType.OVERLAY -> track.copy(items = track.items.filterIsInstance<ImageOverlay>().map { it.sanitized() })
                TrackType.AUDIO -> track.copy(items = track.items.filterIsInstance<AudioClip>().map { it.sanitized() })
            }
        }
        base.copy(tracks = cleanedTracks).reflowVideoTrack()
    }

    companion object {
        /**
         * Creates an empty project with the four standard lanes. [firstClip] is optional so callers
         * can seed a brand-new project straight from the media picker.
         */
        fun create(
            name: String,
            aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
            assets: List<MediaAsset> = emptyList(),
            nowMs: Long = System.currentTimeMillis(),
        ): EditorProject {
            val project = EditorProject(
                id = IdGenerator.projectId(),
                name = name.ifBlank { "Untitled project" },
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
                aspectRatio = aspectRatio,
                tracks = emptyList(),
                assets = assets,
            )
            return withDefaultTracks(project)
        }

        fun withDefaultTracks(project: EditorProject): EditorProject = project.copy(
            tracks = listOf(
                TimelineTrack(id = IdGenerator.trackId(), type = TrackType.VIDEO),
                TimelineTrack(id = IdGenerator.trackId(), type = TrackType.OVERLAY),
                TimelineTrack(id = IdGenerator.trackId(), type = TrackType.TEXT),
                TimelineTrack(id = IdGenerator.trackId(), type = TrackType.AUDIO),
            ),
        )
    }
}

/** Persisted schema version, bumped whenever the document shape changes. */
object ProjectSchema {
    const val CURRENT_VERSION = 1
    const val MIN_SUPPORTED_VERSION = 1
}

/** Convenience: a new clip for an asset, with sane defaults per media type. */
object ClipFactory {

    fun clipForAsset(
        asset: MediaAsset,
        startUs: Long,
        defaultImageDurationUs: Long = 3 * TimeUtils.MICROS_PER_SECOND,
    ): TimelineClip {
        val sourceDuration = if (asset.isImage) defaultImageDurationUs else asset.durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)
        return TimelineClip(
            id = IdGenerator.clipId(),
            startUs = startUs,
            durationUs = sourceDuration,
            assetId = asset.id,
            mediaType = asset.mediaType,
            sourceStartUs = 0L,
            sourceEndUs = sourceDuration,
            label = asset.displayName,
        )
    }
}
