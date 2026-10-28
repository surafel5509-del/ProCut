package com.procutstudio.editor.domain.model

import com.procutstudio.editor.core.util.TimeUtils
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything that can sit on a timeline lane.
 *
 * Items are persisted inside the project document, so the sealed hierarchy is serialised with an
 * explicit `type` discriminator. Times are microseconds on the **timeline** clock.
 */
@Serializable
sealed interface TimelineItem {
    val id: String
    val startUs: Long
    val durationUs: Long

    val endUs: Long get() = startUs + durationUs
}

/** A video or photo clip on the main video lane. */
@Serializable
@SerialName("clip")
data class TimelineClip(
    override val id: String,
    override val startUs: Long,
    override val durationUs: Long,
    val assetId: String,
    val mediaType: MediaType,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val isMuted: Boolean = false,
    val transform: ClipTransform = ClipTransform(),
    val effects: VideoEffect = VideoEffect(),
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val transition: TransitionConfig = TransitionConfig(),
    val label: String = "",
    /** Static clip opacity (1 = opaque). Transparent areas show the canvas colour. */
    val opacity: Float = 1f,
    val keyframes: List<Keyframe> = emptyList(),
    val animationIn: ClipAnimationPreset? = null,
    val animationOut: ClipAnimationPreset? = null,
    val animationCombo: ClipAnimationPreset? = null,
    val animationDurationUs: Long = ClipMotion.DEFAULT_ANIMATION_US,
    val mask: ClipMask = ClipMask(),
    val chromaKey: ChromaKey = ChromaKey(),
    /** App-private PNG whose alpha channel is the manually painted background cut-out. */
    val cutoutUri: String? = null,
    /** True when [assetId] points at a rendered reversed copy of the original footage. */
    val isReversed: Boolean = false,
) : TimelineItem {

    /** True when the clip needs the custom GL shader (grade, mask, key or cut-out). */
    val needsLookShader: Boolean
        get() = !effects.isNeutral || mask.isActive || chromaKey.enabled || cutoutUri != null

    /** True when keyframes / animations / opacity move or fade the clip. */
    val hasMotion: Boolean
        get() = keyframes.isNotEmpty() || animationIn != null || animationOut != null ||
            animationCombo != null || opacity < 0.999f

    val sourceDurationUs: Long get() = (sourceEndUs - sourceStartUs).coerceAtLeast(0L)

    /** Timeline length implied by the current trim and speed. */
    val expectedDurationUs: Long
        get() = (sourceDurationUs / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)

    /** Position inside the source file that maps to a timeline offset within this clip. */
    fun sourcePositionAt(offsetUs: Long): Long =
        sourceStartUs + (offsetUs * speed).toLong()

    fun timelineOffsetOf(sourcePositionUs: Long): Long =
        ((sourcePositionUs - sourceStartUs) / speed).toLong()

    fun withSourceRange(startUsInSource: Long, endUsInSource: Long): TimelineClip {
        val start = startUsInSource.coerceAtLeast(0L)
        val end = endUsInSource.coerceAtLeast(start + TimeUtils.MIN_CLIP_DURATION_US)
        val newDuration = ((end - start) / speed).toLong().coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)
        val maxFade = newDuration / 2
        return copy(
            sourceStartUs = start,
            sourceEndUs = end,
            durationUs = newDuration,
            fadeInUs = fadeInUs.coerceIn(0L, maxFade),
            fadeOutUs = fadeOutUs.coerceIn(0L, maxFade),
        )
    }

    fun withSpeed(newSpeed: Float): TimelineClip {
        val clamped = newSpeed.coerceIn(0.25f, 4f)
        val newDuration = (sourceDurationUs / clamped).toLong()
            .coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)
        return copy(
            speed = clamped,
            durationUs = newDuration,
            fadeInUs = fadeInUs.coerceIn(0L, newDuration / 2),
            fadeOutUs = fadeOutUs.coerceIn(0L, newDuration / 2),
        )
    }

    fun sanitized(): TimelineClip {
        val start = startUs.coerceAtLeast(0L)
        val natural = expectedDurationUs
        val duration = durationUs.coerceIn(TimeUtils.MIN_CLIP_DURATION_US, natural)
        return copy(
            startUs = start,
            durationUs = duration,
            speed = speed.coerceIn(0.25f, 4f),
            volume = volume.coerceIn(0f, 2f),
            transform = transform.sanitized(),
            fadeInUs = fadeInUs.coerceIn(0L, duration / 2),
            fadeOutUs = fadeOutUs.coerceIn(0L, duration / 2),
            transition = transition.copy(durationUs = transition.durationUs.coerceIn(0L, duration)),
            opacity = opacity.coerceIn(0f, 1f),
            keyframes = keyframes.map { it.sanitized() }.sortedBy { it.timeUs },
            animationDurationUs = animationDurationUs.coerceIn(100_000L, 5_000_000L),
            mask = mask.sanitized(),
            chromaKey = chromaKey.sanitized(),
        )
    }
}

/** A text overlay on the text lane. */
@Serializable
@SerialName("text")
data class TextOverlay(
    override val id: String,
    override val startUs: Long,
    override val durationUs: Long,
    val text: String,
    val style: TextStyleSpec = TextStyleSpec(),
    val animation: OverlayAnimation = OverlayAnimation(),
    val preset: TextPreset? = null,
    val label: String = "",
) : TimelineItem {
    fun sanitized(): TextOverlay = copy(
        startUs = startUs.coerceAtLeast(0L),
        durationUs = durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
        style = style.sanitized(),
    )
}

/** An image, logo or sticker on the overlay lane. */
@Serializable
@SerialName("overlay")
data class ImageOverlay(
    override val id: String,
    override val startUs: Long,
    override val durationUs: Long,
    val assetId: String,
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    /** Width of the overlay as a fraction of the output frame width. */
    val scale: Float = 0.4f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    val cornerRadius: Float = 0f,
    val animation: OverlayAnimation = OverlayAnimation(),
    val label: String = "",
) : TimelineItem {
    fun sanitized(): ImageOverlay = copy(
        startUs = startUs.coerceAtLeast(0L),
        durationUs = durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
        positionX = positionX.coerceIn(0f, 1f),
        positionY = positionY.coerceIn(0f, 1f),
        scale = scale.coerceIn(0.05f, 2f),
        opacity = opacity.coerceIn(0f, 1f),
        cornerRadius = cornerRadius.coerceIn(0f, 200f),
    )
}

/** Music, sound effects or a voice-over take on the audio lane. */
@Serializable
@SerialName("audio")
data class AudioClip(
    override val id: String,
    override val startUs: Long,
    override val durationUs: Long,
    val assetId: String,
    val sourceStartUs: Long = 0L,
    val volume: Float = 1f,
    val fadeInUs: Long = 250_000L,
    val fadeOutUs: Long = 250_000L,
    val isMuted: Boolean = false,
    val isRecording: Boolean = false,
    val label: String = "",
) : TimelineItem {
    fun sourceEndUs(): Long = sourceStartUs + durationUs

    fun sanitized(): AudioClip {
        val start = startUs.coerceAtLeast(0L)
        val duration = durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US)
        return copy(
            startUs = start,
            durationUs = duration,
            sourceStartUs = sourceStartUs.coerceAtLeast(0L),
            volume = volume.coerceIn(0f, 2f),
            fadeInUs = fadeInUs.coerceIn(0L, duration / 2),
            fadeOutUs = fadeOutUs.coerceIn(0L, duration / 2),
        )
    }
}

/**
 * A video effect on the effects lane. Between [startUs] and [endUs] the effect runs at
 * [intensity] on whatever the video lane (and clip look) produces; overlapping effects stack.
 */
@Serializable
@SerialName("effect")
data class EffectItem(
    override val id: String,
    override val startUs: Long,
    override val durationUs: Long,
    val type: VideoFxType,
    val intensity: Float = 0.7f,
    val label: String = "",
) : TimelineItem {
    fun sanitized(): EffectItem = copy(
        startUs = startUs.coerceAtLeast(0L),
        durationUs = durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
        intensity = intensity.coerceIn(0f, 1f),
    )

    fun isActiveAt(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs
}
