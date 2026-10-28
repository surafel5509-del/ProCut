package com.procutstudio.editor.domain

import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.AnimationGroup
import com.procutstudio.editor.domain.model.ClipAnimationPreset
import com.procutstudio.editor.domain.model.ClipMask
import com.procutstudio.editor.domain.model.ClipMotion
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.FilterPreset
import com.procutstudio.editor.domain.model.KeyframeEasing
import com.procutstudio.editor.domain.model.MaskType
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.MediaType
import com.procutstudio.editor.domain.model.MotionState
import com.procutstudio.editor.domain.model.VideoEffect
import com.procutstudio.editor.domain.model.VideoFxType
import com.procutstudio.editor.domain.timeline.TimelineEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timeline operations behind the effects lane, keyframes, masks, freeze frame and friends. */
class ProToolsTest {

    private val second = TimeUtils.MICROS_PER_SECOND

    private fun videoAsset(durationUs: Long, name: String = "clip") = MediaAsset(
        id = "asset-$name-$durationUs",
        uri = "file:///tmp/$name.mp4",
        displayName = name,
        mediaType = MediaType.VIDEO,
        durationUs = durationUs,
        width = 1920,
        height = 1080,
        hasAudio = true,
    )

    private fun stillAsset() = MediaAsset(
        id = "asset-still",
        uri = "file:///tmp/still.jpg",
        displayName = "Freeze frame",
        mediaType = MediaType.IMAGE,
        durationUs = 0L,
        width = 1920,
        height = 1080,
    )

    private fun tenSecondProject(): EditorProject =
        TimelineEditor.appendAssets(EditorProject.create("Pro tools"), listOf(videoAsset(10 * second)))

    @Test
    fun `effects land on their own lane and stack at the playhead`() {
        val project = tenSecondProject()
        val withGlitch = TimelineEditor.addEffect(project, VideoFxType.GLITCH, 2 * second)
        val withBoth = TimelineEditor.addEffect(withGlitch, VideoFxType.BLUR, 3 * second)

        assertEquals(2, withBoth.effectItems.size)
        assertEquals(0, project.effectItems.size)
        assertEquals(1, withBoth.effectsAt(2 * second + 1L).size)
        assertEquals(2, withBoth.effectsAt(4 * second).size)
        assertTrue(withBoth.effectsAt(9 * second).isEmpty())
        // The video lane is untouched by effects.
        assertEquals(project.videoClips, withBoth.videoClips)
    }

    @Test
    fun `an effect never runs past the end of the project`() {
        val project = TimelineEditor.addEffect(tenSecondProject(), VideoFxType.VHS, 9 * second)
        val effect = project.effectItems.single()
        assertTrue(effect.endUs <= project.durationUs)
        assertTrue(effect.durationUs >= TimeUtils.MIN_CLIP_DURATION_US)
    }

    @Test
    fun `spreading an effect covers the whole video and deleting removes it`() {
        val added = TimelineEditor.addEffect(tenSecondProject(), VideoFxType.SPARKLE, 4 * second)
        val id = added.effectItems.single().id
        val spread = TimelineEditor.spreadEffectOverProject(added, id)
        assertEquals(0L, spread.effectItems.single().startUs)
        assertEquals(spread.durationUs, spread.effectItems.single().durationUs)

        val removed = TimelineEditor.deleteItem(spread, id)
        assertTrue(removed.effectItems.isEmpty())
    }

    @Test
    fun `keyframes upsert without duplicates and interpolate with easing`() {
        val project = tenSecondProject()
        val clipId = project.videoClips.single().id

        var edited = TimelineEditor.upsertKeyframe(project, clipId, 0L, MotionState(scale = 1f))
        edited = TimelineEditor.upsertKeyframe(edited, clipId, 4 * second, MotionState(scale = 2f))
        // Same instant again overwrites instead of stacking.
        edited = TimelineEditor.upsertKeyframe(edited, clipId, 4 * second + 10_000L, MotionState(scale = 3f))
        val clip = edited.videoClips.single()
        assertEquals(2, clip.keyframes.size)
        assertEquals(3f, clip.keyframes.last().scale, 0.0001f)

        // Smooth easing is symmetric: halfway in time is halfway in value (1 -> 3 => 2).
        assertEquals(2f, ClipMotion.keyframeState(clip.keyframes, 2 * second).scale, 0.01f)
        // Outside the range the first / last keyframe is held.
        assertEquals(1f, ClipMotion.keyframeState(clip.keyframes, 0L).scale, 0.0001f)
        assertEquals(3f, ClipMotion.keyframeState(clip.keyframes, 9 * second).scale, 0.0001f)
    }

    @Test
    fun `hold easing keeps the previous value until the next keyframe`() {
        val project = tenSecondProject()
        val clipId = project.videoClips.single().id
        var edited = TimelineEditor.upsertKeyframe(project, clipId, 0L, MotionState(scale = 1f), KeyframeEasing.HOLD)
        edited = TimelineEditor.upsertKeyframe(edited, clipId, 4 * second, MotionState(scale = 2f))
        val keyframes = edited.videoClips.single().keyframes
        assertEquals(1f, ClipMotion.keyframeState(keyframes, 3 * second).scale, 0.0001f)
        assertEquals(2f, ClipMotion.keyframeState(keyframes, 4 * second).scale, 0.0001f)
    }

    @Test
    fun `removing a keyframe and clearing work`() {
        val project = tenSecondProject()
        val clipId = project.videoClips.single().id
        var edited = TimelineEditor.upsertKeyframe(project, clipId, 1 * second, MotionState(scale = 1.4f))
        edited = TimelineEditor.upsertKeyframe(edited, clipId, 5 * second, MotionState(scale = 1.8f))
        edited = TimelineEditor.removeKeyframeAt(edited, clipId, 1 * second)
        assertEquals(1, edited.videoClips.single().keyframes.size)
        edited = TimelineEditor.setKeyframes(edited, clipId, emptyList())
        assertTrue(edited.videoClips.single().keyframes.isEmpty())
    }

    @Test
    fun `splitting a keyframed clip keeps the motion continuous across the cut`() {
        val project = tenSecondProject()
        val clipId = project.videoClips.single().id
        var edited = TimelineEditor.upsertKeyframe(project, clipId, 0L, MotionState(scale = 1f), KeyframeEasing.LINEAR)
        edited = TimelineEditor.upsertKeyframe(edited, clipId, 10 * second, MotionState(scale = 2f), KeyframeEasing.LINEAR)
        val split = TimelineEditor.splitItem(edited, clipId, 5 * second)
        val (left, right) = split.videoClips
        assertEquals(1.5f, ClipMotion.keyframeState(left.keyframes, left.durationUs).scale, 0.02f)
        assertEquals(1.5f, ClipMotion.keyframeState(right.keyframes, 0L).scale, 0.02f)
        assertEquals(2f, ClipMotion.keyframeState(right.keyframes, right.durationUs).scale, 0.02f)
    }

    @Test
    fun `animations fade in and out and stack with the clip opacity`() {
        val project = tenSecondProject()
        val clipId = project.videoClips.single().id
        var edited = TimelineEditor.setAnimation(project, clipId, AnimationGroup.IN, ClipAnimationPreset.FADE_IN)
        edited = TimelineEditor.setAnimation(edited, clipId, AnimationGroup.OUT, ClipAnimationPreset.FADE_OUT)
        edited = TimelineEditor.setOpacity(edited, clipId, 0.5f)
        val clip = edited.videoClips.single()
        assertTrue(clip.hasMotion)

        assertEquals(0f, ClipMotion.stateAt(clip, 0L).opacity, 0.001f)
        assertEquals(0.5f, ClipMotion.stateAt(clip, 5 * second).opacity, 0.001f)
        assertEquals(0f, ClipMotion.stateAt(clip, clip.durationUs).opacity, 0.001f)

        val cleared = TimelineEditor.setAnimation(edited, clipId, AnimationGroup.IN, null)
        assertNull(cleared.videoClips.single().animationIn)
    }

    @Test
    fun `mask chroma key and cut-out switch the look shader on`() {
        val project = tenSecondProject()
        val clip = project.videoClips.single()
        assertFalse(clip.needsLookShader)

        val masked = TimelineEditor.setMask(project, clip.id, ClipMask(type = MaskType.CIRCLE))
        assertTrue(masked.videoClips.single().needsLookShader)

        val graded = TimelineEditor.setEffects(project, clip.id, VideoEffect(filter = FilterPreset.CINEMATIC))
        assertTrue(graded.videoClips.single().needsLookShader)

        val cut = TimelineEditor.setCutout(project, clip.id, "file:///tmp/cut.png")
        assertTrue(cut.videoClips.single().needsLookShader)
        assertNull(TimelineEditor.setCutout(cut, clip.id, null).videoClips.single().cutoutUri)
    }

    @Test
    fun `freeze frame splits the clip and inserts a still between the halves`() {
        val project = tenSecondProject()
        val clip = project.videoClips.single()
        val frozen = TimelineEditor.freezeFrame(project, clip.id, 4 * second, stillAsset())
        val lane = frozen.videoClips

        assertEquals(3, lane.size)
        assertEquals(MediaType.VIDEO, lane[0].mediaType)
        assertEquals(MediaType.IMAGE, lane[1].mediaType)
        assertEquals(MediaType.VIDEO, lane[2].mediaType)
        assertEquals(TimelineEditor.FREEZE_DEFAULT_DURATION_US, lane[1].durationUs)
        assertEquals(project.durationUs + TimelineEditor.FREEZE_DEFAULT_DURATION_US, frozen.durationUs)
        // The video lane stays gapless.
        assertEquals(lane[0].endUs, lane[1].startUs)
        assertEquals(lane[1].endUs, lane[2].startUs)
        assertNotNull(frozen.asset("asset-still"))
    }

    @Test
    fun `extract audio copies the sound to the audio lane and mutes the clip`() {
        val project = tenSecondProject()
        val clip = project.videoClips.single()
        val extracted = TimelineEditor.extractAudio(project, clip.id)
        assertEquals(1, extracted.audioClips.size)
        assertTrue(extracted.videoClips.single().isMuted)
        assertEquals(clip.startUs, extracted.audioClips.single().startUs)
    }

    @Test
    fun `apply to all copies one grade onto every clip`() {
        val project = TimelineEditor.appendAssets(
            EditorProject.create("Two clips"),
            listOf(videoAsset(3 * second, "a"), videoAsset(4 * second, "b")),
        )
        val grade = VideoEffect(filter = FilterPreset.TEAL_ORANGE, contrast = 12f)
        val graded = TimelineEditor.applyGradeToAll(project, grade)
        assertTrue(graded.videoClips.all { it.effects.filter == FilterPreset.TEAL_ORANGE })
        assertTrue(graded.videoClips.all { it.effects.contrast == 12f })
    }

    @Test
    fun `reversed asset replaces the footage and covers the whole new file`() {
        val project = tenSecondProject()
        val clip = project.videoClips.single()
        val reversed = videoAsset(10 * second, "reversed")
        val result = TimelineEditor.applyReversedAsset(project, clip.id, reversed)
        val updated = result.videoClips.single()
        assertEquals(reversed.id, updated.assetId)
        assertTrue(updated.isReversed)
        assertEquals(0L, updated.sourceStartUs)
        assertEquals(reversed.durationUs, updated.sourceEndUs)
        assertNotNull(result.asset(reversed.id))
    }
}
