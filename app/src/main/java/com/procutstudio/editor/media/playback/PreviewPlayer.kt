package com.procutstudio.editor.media.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Owns the ExoPlayer used for the editor preview.
 *
 * The preview playlist is built from the *video* clips only (photos are shown by the Compose
 * overlay, since ExoPlayer has no image renderer). Each clip becomes a clipped `MediaItem`, so the
 * player itself enforces trims and gives gapless playback between clips.
 *
 * Timeline ⇄ player mapping lives here and nowhere else:
 *  - `timelinePositionUs = segment.startUs + playerPositionInClip / speed`
 *  - `seekToTimeline(us)` seeks to `(us - segment.startUs) * speed` inside the segment.
 */
@UnstableApi
class PreviewPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class VideoSegment(
        val clipId: String,
        val startUs: Long,
        val endUs: Long,
        val sourceStartUs: Long,
        val sourceEndUs: Long,
        val speed: Float,
        val volume: Float,
        val isMuted: Boolean,
        val uri: String,
    ) {
        val durationUs: Long get() = endUs - startUs
    }

    private var exoPlayer: ExoPlayer? = null
    private var segments: List<VideoSegment> = emptyList()
    private var signature: Int = 0
    private var pendingSeekUs: Long = 0L
    private var masterVolume: Float = 1f

    val player: ExoPlayer
        get() = exoPlayer ?: createPlayer().also { exoPlayer = it }

    val isPlaying: Boolean get() = exoPlayer?.isPlaying == true

    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            repeatMode = Player.REPEAT_MODE_OFF
            volume = masterVolume
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    applySegmentAudio()
                }
            })
        }

    /**
     * Syncs the player with the project. Structural edits rebuild the playlist; everything else
     * (filters, text, audio levels) is applied without interrupting playback.
     */
    fun setProject(project: EditorProject, playheadUs: Long) {
        val newSegments = buildSegments(project)
        val newSignature = newSegments.hashCode()
        if (newSignature == signature) {
            applySegmentAudio()
            return
        }
        signature = newSignature
        segments = newSegments
        pendingSeekUs = playheadUs
        if (newSegments.isEmpty()) {
            exoPlayer?.clearMediaItems()
            return
        }
        val items = newSegments.map { segment ->
            MediaItem.Builder()
                .setUri(segment.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(TimeUtils.usToMs(segment.sourceStartUs))
                        .setEndPositionMs(TimeUtils.usToMs(segment.sourceEndUs))
                        .build(),
                )
                .setMediaId(segment.clipId)
                .build()
        }
        val player = player
        val wasPlaying = player.isPlaying
        player.setMediaItems(items, /* resetPosition= */ true)
        player.prepare()
        seekToTimeline(playheadUs)
        if (wasPlaying) player.play()
    }

    private fun buildSegments(project: EditorProject): List<VideoSegment> =
        project.videoClips
            .filter { clip ->
                clip.mediaType == MediaType.VIDEO && project.asset(clip.assetId)?.isVideo == true
            }
            .map { clip ->
                val asset = project.asset(clip.assetId)
                VideoSegment(
                    clipId = clip.id,
                    startUs = clip.startUs,
                    endUs = clip.endUs,
                    sourceStartUs = clip.sourceStartUs,
                    sourceEndUs = clip.sourceEndUs,
                    speed = clip.speed,
                    volume = clip.volume,
                    isMuted = clip.isMuted,
                    uri = asset?.uri.orEmpty(),
                )
            }

    fun play() {
        val player = player
        if (player.playbackState == Player.STATE_ENDED) {
            seekToTimeline(0L)
        }
        player.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }

    /**
     * Seeks the player to a timeline position. Returns true when a video segment exists at that
     * position (photos and gaps return false so the caller can render a still frame instead).
     */
    fun seekToTimeline(us: Long): Boolean {
        val player = exoPlayer ?: return false
        val index = segments.indexOfFirst { us >= it.startUs && us < it.endUs }
        if (index < 0) {
            pendingSeekUs = us
            return false
        }
        val segment = segments[index]
        val offsetInSourceMs = TimeUtils.usToMs(((us - segment.startUs) * segment.speed).toLong())
        player.seekTo(index, offsetInSourceMs.coerceAtLeast(0L))
        pendingSeekUs = us
        applySegmentAudio()
        return true
    }

    /** Current timeline position, or null when the player is not on a video segment. */
    fun timelinePositionUs(): Long? {
        val player = exoPlayer ?: return null
        if (player.currentMediaItemIndex >= segments.size) return null
        val segment = segments[player.currentMediaItemIndex]
        val sourceOffsetUs = TimeUtils.msToUs(player.currentPosition.coerceAtLeast(0L))
        return segment.startUs + (sourceOffsetUs / segment.speed).toLong()
    }

    fun hasVideoAt(us: Long): Boolean = segments.any { us >= it.startUs && us < it.endUs }

    /** Applies clip volume/mute/speed for the segment currently playing, scaled by master volume. */
    private fun applySegmentAudio() {
        val player = exoPlayer ?: return
        val index = player.currentMediaItemIndex
        if (index < 0 || index >= segments.size) return
        val segment = segments[index]
        val clipGain = if (segment.isMuted) 0f else segment.volume.coerceIn(0f, 1f)
        player.volume = clipGain * masterVolume
        player.playbackParameters = PlaybackParameters(segment.speed.coerceIn(0.25f, 4f))
    }

    /** Live colour-grade preview through Media3's GL pipeline. */
    fun setVideoEffects(effects: List<Effect>) {
        val player = exoPlayer ?: return
        runCatching { player.setVideoEffects(effects) }
            .onFailure { ProCutLogger.w(ProCutLogger.TAG_PLAYBACK, "Live video effects unavailable", it) }
    }

    /** Master volume for the preview (0..1), independent of clip volume; survives playlist rebuilds. */
    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1f)
        val player = exoPlayer ?: return
        if (segments.isEmpty()) player.volume = masterVolume else applySegmentAudio()
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        segments = emptyList()
        signature = 0
    }

    /** Snapshot for the UI: which clip is under the playhead. */
    fun segmentAt(us: Long): VideoSegment? = segments.firstOrNull { us >= it.startUs && us < it.endUs }
}
