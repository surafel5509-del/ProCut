package com.procutstudio.editor.media.export

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.procutstudio.editor.core.util.FileUtils
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.AudioClip
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.KenBurnsMotion
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.OutputSpec
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.domain.model.TransitionType
import com.procutstudio.editor.media.export.effects.ClipGeometryEffect
import com.procutstudio.editor.media.export.effects.CurtainOverlay
import com.procutstudio.editor.media.export.effects.ImageTimelineOverlay
import com.procutstudio.editor.media.export.effects.KenBurnsEffect
import com.procutstudio.editor.media.export.effects.TextTimelineOverlay
import com.procutstudio.editor.media.render.OverlayBitmaps
import com.procutstudio.editor.media.render.TextBitmapRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Raised when a project cannot be turned into an exportable composition. */
class CompositionBuildException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Everything the exporter needs, plus diagnostics the UI can show. */
@UnstableApi
data class BuiltComposition(
    val composition: Composition,
    val spec: OutputSpec,
    val expectedDurationUs: Long,
    val warnings: List<String>,
    val temporaryFiles: List<File> = emptyList(),
)

/**
 * Compiles the editing document into a Media3 [Composition].
 *
 * Layout of the produced composition:
 *  - **sequence 0 (primary video)**: every video/photo clip in timeline order, each carrying its
 *    trim (`ClippingConfiguration`), speed (`SpeedChangeEffect`), geometry
 *    ([ClipGeometryEffect]), colour grade, vignette, fade curtain and its own audio processors.
 *  - **optional background sequence**: a solid colour frame, added only when the canvas is not
 *    black so letterboxed footage sits on the chosen colour.
 *  - **audio lanes**: music / voice-over packed into up to [MAX_AUDIO_LANES] non-overlapping
 *    sequences, positioned with gaps.
 *  - **composition-level effects**: one [OverlayEffect] holding every text and sticker overlay,
 *    each windowed to its own timeline range.
 */
@UnstableApi
@Singleton
class CompositionBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textRenderer: TextBitmapRenderer,
    private val overlayBitmaps: OverlayBitmaps,
) {

    suspend fun build(project: EditorProject, spec: OutputSpec): BuiltComposition {
        if (project.videoClips.isEmpty()) {
            throw CompositionBuildException("Add at least one video or photo before exporting.")
        }
        val warnings = mutableListOf<String>()
        val temporaryFiles = mutableListOf<File>()

        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences += EditedMediaItemSequence.Builder(buildVideoItems(project, spec)).build()

        canvasSequence(project, spec, temporaryFiles, warnings)?.let { sequences += it }

        val audibleMusic = project.audioClips.filterNot { it.isMuted }
        if (audibleMusic.isNotEmpty()) {
            packAudioLanes(audibleMusic, MAX_AUDIO_LANES).forEach { lane ->
                if (lane.isNotEmpty()) sequences += buildAudioSequence(lane, project)
            }
            if (audibleMusic.size > MAX_AUDIO_LANES && packAudioLanes(audibleMusic, MAX_AUDIO_LANES + 1).size > MAX_AUDIO_LANES) {
                warnings += "Several audio clips overlap; they are mixed across ${MAX_AUDIO_LANES} lanes."
            }
        }

        val compositionOverlays = buildCompositionOverlays(project, spec)
        val builder = Composition.Builder(sequences)
        if (compositionOverlays.isNotEmpty()) {
            builder.setEffects(Effects(emptyList(), compositionOverlays))
        }
        builder.experimentalSetForceAudioTrack(hasAnyAudio(project, spec))

        return BuiltComposition(
            composition = builder.build(),
            spec = spec,
            expectedDurationUs = project.durationUs,
            warnings = warnings,
            temporaryFiles = temporaryFiles,
        )
    }

    // ------------------------------------------------------------------ video

    private fun buildVideoItems(project: EditorProject, spec: OutputSpec): List<EditedMediaItem> {
        val clips = project.videoClips
        return clips.mapIndexed { index, clip ->
            val asset = project.asset(clip.assetId) ?: throw CompositionBuildException(
                "Clip “${clip.label}” is missing its source file. Re-import the media to continue.",
            )
            val videoEffects = clipVideoEffects(project, clip, asset, index, spec)
            val audioProcessors = clipAudioProcessors(clip, asset)
            val hasAudio = asset.hasAudio && !clip.isMuted && spec.includeAudio

            val builder = EditedMediaItem.Builder(mediaItemFor(clip, asset))
                .setRemoveAudio(!hasAudio)
            if (asset.isImage) {
                builder.setDurationUs(clip.durationUs)
                builder.setFrameRate(spec.fps)
            }
            if (videoEffects.isNotEmpty() || audioProcessors.isNotEmpty()) {
                builder.setEffects(Effects(audioProcessors, videoEffects))
            }
            builder.build()
        }
    }

    private fun mediaItemFor(clip: TimelineClip, asset: MediaAsset): MediaItem {
        if (asset.isImage) return MediaItem.fromUri(asset.uri)
        return MediaItem.Builder()
            .setUri(asset.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(TimeUtils.usToMs(clip.sourceStartUs))
                    .setEndPositionMs(TimeUtils.usToMs(clip.sourceEndUs))
                    .build(),
            )
            .build()
    }

    private fun clipVideoEffects(
        project: EditorProject,
        clip: TimelineClip,
        asset: MediaAsset,
        index: Int,
        spec: OutputSpec,
    ): List<Effect> {
        val effects = mutableListOf<Effect>()
        if (!asset.isImage && clip.speed != 1f) effects += SpeedChangeEffect(clip.speed)
        effects += ClipGeometryEffect(
            outputWidth = spec.width,
            outputHeight = spec.height,
            transform = clip.transform,
        )
        if (asset.isImage && clip.transform.kenBurns != KenBurnsMotion.NONE) {
            effects += KenBurnsEffect(clip.transform.kenBurns, clip.durationUs)
        }
        effects += ColorEffectMapper.videoEffects(clip.effects)

        val overlays = mutableListOf<TextureOverlay>()
        overlayBitmaps.vignette(clip.effects.effectiveVignette)?.let { vignette ->
            overlays += FullFrameOverlay(vignette, spec.width, spec.height)
        }
        val headUs = curtainHeadUs(project, clip, index)
        val tailUs = curtainTailUs(project, clip, index)
        if (headUs > 0L || tailUs > 0L) {
            overlays += CurtainOverlay(
                bits = overlayBitmaps.solid(curtainColor(project, clip, index)),
                clipDurationUs = clip.durationUs,
                headUs = headUs,
                tailUs = tailUs,
                outputWidth = spec.width,
                outputHeight = spec.height,
            )
        }
        if (overlays.isNotEmpty()) effects += OverlayEffect(overlays)
        return effects
    }

    /**
     * Static, frame-filling overlay (vignette).
     *
     * The vignette bitmap is small, so it is scaled to the output resolution — Media3 draws an
     * overlay bitmap 1:1 with the frame, hence the division by the bitmap size.
     */
    private class FullFrameOverlay(
        private val bits: Bitmap,
        private val outputWidth: Int,
        private val outputHeight: Int,
    ) : BitmapOverlay() {
        override fun getBitmap(presentationTimeUs: Long): Bitmap = bits

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            OverlaySettings.Builder()
                .setScale(
                    outputWidth.toFloat() / bits.width.coerceAtLeast(1),
                    outputHeight.toFloat() / bits.height.coerceAtLeast(1),
                )
                .setAlphaScale(1f)
                .build()
    }

    private fun clipAudioProcessors(clip: TimelineClip, asset: MediaAsset): List<AudioProcessor> {
        if (!asset.hasAudio || clip.isMuted) return emptyList()
        val processors = mutableListOf<AudioProcessor>()
        if (clip.speed != 1f) {
            processors += SpeedChangingAudioProcessor(
                object : SpeedProvider {
                    override fun getSpeed(timeUs: Long): Float = clip.speed
                    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
                },
            )
        }
        if (clip.volume != 1f || clip.fadeInUs > 0L || clip.fadeOutUs > 0L) {
            processors += GainAudioProcessor(
                volume = clip.volume,
                fadeInUs = clip.fadeInUs,
                fadeOutUs = clip.fadeOutUs,
                totalDurationUs = clip.durationUs,
            )
        }
        return processors
    }

    private fun curtainHeadUs(project: EditorProject, clip: TimelineClip, index: Int): Long {
        var head = clip.fadeInUs
        if (index > 0 && !clip.transition.isNone) head = maxOf(head, clip.transition.durationUs / 2)
        return head.coerceIn(0L, clip.durationUs / 2)
    }

    private fun curtainTailUs(project: EditorProject, clip: TimelineClip, index: Int): Long {
        var tail = clip.fadeOutUs
        val incoming = project.videoClips.getOrNull(index + 1)?.transition
        if (incoming != null && !incoming.isNone) tail = maxOf(tail, incoming.durationUs / 2)
        return tail.coerceIn(0L, clip.durationUs / 2)
    }

    private fun curtainColor(project: EditorProject, clip: TimelineClip, index: Int): Long {
        val relevant = when {
            !clip.transition.isNone -> clip.transition.type
            else -> project.videoClips.getOrNull(index + 1)?.transition?.type ?: TransitionType.NONE
        }
        return if (relevant == TransitionType.FADE_TO_WHITE) COLOR_WHITE else COLOR_BLACK
    }

    // ------------------------------------------------------------------ canvas

    private fun canvasSequence(
        project: EditorProject,
        spec: OutputSpec,
        temporaryFiles: MutableList<File>,
        warnings: MutableList<String>,
    ): EditedMediaItemSequence? {
        if (project.canvasColorArgb == COLOR_BLACK) return null
        return runCatching {
            val colorFile = writeCanvasColorFile(project.canvasColorArgb, spec.width, spec.height)
            temporaryFiles += colorFile
            val item = EditedMediaItem.Builder(MediaItem.fromUri(colorFile.toURI().toString()))
                .setDurationUs(project.durationUs.coerceAtLeast(TimeUtils.MICROS_PER_SECOND))
                .setFrameRate(spec.fps)
                .setRemoveAudio(true)
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf<Effect>(
                            // The canvas bitmap is a solid colour, so stretching it to the output
                            // size is lossless and guarantees the letterbox area is filled.
                            Presentation.createForWidthAndHeight(
                                spec.width,
                                spec.height,
                                Presentation.LAYOUT_STRETCH_TO_FIT,
                            ),
                        ),
                    ),
                )
                .build()
            EditedMediaItemSequence.Builder(item).build()
        }.onFailure {
            ProCutLogger.w(ProCutLogger.TAG_EXPORT, "Canvas colour layer could not be created", it)
            warnings += "The custom canvas colour was skipped on this device."
        }.getOrNull()
    }

    // ------------------------------------------------------------------ audio

    private fun buildAudioSequence(lane: List<AudioClip>, project: EditorProject): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder()
        var cursorUs = 0L
        lane.sortedBy { it.startUs }.forEach { audio ->
            val asset = project.asset(audio.assetId) ?: throw CompositionBuildException(
                "Audio clip “${audio.label}” is missing its source file.",
            )
            if (audio.startUs > cursorUs) {
                builder.addGap(audio.startUs - cursorUs)
                cursorUs = audio.startUs
            }
            val mediaItem = MediaItem.Builder()
                .setUri(asset.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(TimeUtils.usToMs(audio.sourceStartUs))
                        .setEndPositionMs(TimeUtils.usToMs(audio.sourceStartUs + audio.durationUs))
                        .build(),
                )
                .build()
            val processors = listOf<AudioProcessor>(
                GainAudioProcessor(
                    volume = audio.volume,
                    fadeInUs = audio.fadeInUs,
                    fadeOutUs = audio.fadeOutUs,
                    totalDurationUs = audio.durationUs,
                ),
            )
            builder.addItem(
                EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true)
                    .setEffects(Effects(processors, emptyList()))
                    .build(),
            )
            cursorUs += audio.durationUs
        }
        return builder.build()
    }

    /**
     * Greedy interval packing: overlapping clips land in different lanes, each of which Media3
     * mixes into the output. Sequential clips share a lane to keep the mixer cheap.
     */
    private fun packAudioLanes(clips: List<AudioClip>, maxLanes: Int): List<List<AudioClip>> {
        val lanes = mutableListOf<MutableList<AudioClip>>()
        val laneEnds = mutableListOf<Long>()
        clips.sortedBy { it.startUs }.forEach { clip ->
            val free = laneEnds.indexOfFirst { it <= clip.startUs }
            if (free >= 0) {
                lanes[free] += clip
                laneEnds[free] = clip.endUs
            } else if (lanes.size < maxLanes) {
                lanes += mutableListOf(clip)
                laneEnds += clip.endUs
            } else {
                val earliest = laneEnds.indices.minByOrNull { laneEnds[it] } ?: 0
                lanes[earliest] += clip
                laneEnds[earliest] = clip.endUs
            }
        }
        return lanes
    }

    // ------------------------------------------------------------------ overlays

    private suspend fun buildCompositionOverlays(project: EditorProject, spec: OutputSpec): List<Effect> {
        val overlays = mutableListOf<TextureOverlay>()
        val transparent = overlayBitmaps.transparent

        project.textOverlays.forEach { text ->
            if (text.text.isNotBlank()) {
                overlays += TextTimelineOverlay(
                    overlay = text,
                    renderer = textRenderer,
                    transparent = transparent,
                    outputWidth = spec.width,
                    outputHeight = spec.height,
                )
            }
        }
        project.imageOverlays.forEach { overlay ->
            val asset = project.asset(overlay.assetId) ?: return@forEach
            val maxWidth = (spec.width * overlay.scale).toInt().coerceIn(64, 2048)
            val bitmap = overlayBitmaps.loadImage(asset.uri, maxWidth, overlay.cornerRadius) ?: return@forEach
            overlays += ImageTimelineOverlay(
                overlay = overlay,
                bitmap = bitmap,
                transparent = transparent,
                outputWidth = spec.width,
                outputHeight = spec.height,
            )
        }
        return if (overlays.isEmpty()) emptyList() else listOf(OverlayEffect(overlays))
    }

    // ------------------------------------------------------------------ helpers

    private fun hasAnyAudio(project: EditorProject, spec: OutputSpec): Boolean {
        if (!spec.includeAudio) return false
        val clipAudio = project.videoClips.any { clip ->
            !clip.isMuted && project.asset(clip.assetId)?.hasAudio == true
        }
        return clipAudio || project.audioClips.any { !it.isMuted }
    }

    private fun writeCanvasColorFile(colorArgb: Long, outputWidth: Int, outputHeight: Int): File {
        val file = File(FileUtils.exportsDir(context), "canvas_${System.nanoTime()}.png")
        // A solid colour survives being upscaled, so the PNG is kept small (1/8 of the output).
        val width = (outputWidth / 8).coerceAtLeast(2)
        val height = (outputHeight / 8).coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(colorArgb.toInt())
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        return file
    }

    companion object {
        const val MAX_AUDIO_LANES = 4
        private const val COLOR_BLACK = 0xFF000000L
        private const val COLOR_WHITE = 0xFFFFFFFFL
    }
}
