package com.procutstudio.editor.domain

import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.ClipTransform
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.FitMode
import com.procutstudio.editor.domain.model.KenBurnsMotion
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.MediaType
import com.procutstudio.editor.domain.model.TextStyleSpec
import com.procutstudio.editor.domain.model.TrackType
import com.procutstudio.editor.domain.model.TransitionConfig
import com.procutstudio.editor.domain.model.TransitionType
import com.procutstudio.editor.domain.timeline.TimelineEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEditorTest {

    private val second = TimeUtils.MICROS_PER_SECOND

    private fun videoAsset(durationUs: Long, name: String = "clip") = MediaAsset(
        id = "asset-$name-${durationUs}",
        uri = "file:///tmp/$name.mp4",
        displayName = name,
        mediaType = MediaType.VIDEO,
        durationUs = durationUs,
        width = 1920,
        height = 1080,
        hasAudio = true,
    )

    private fun imageAsset(name: String = "photo") = MediaAsset(
        id = "asset-$name",
        uri = "file:///tmp/$name.jpg",
        displayName = name,
        mediaType = MediaType.IMAGE,
        durationUs = 0L,
        width = 1080,
        height = 1920,
    )

    private fun audioAsset(name: String = "music") = MediaAsset(
        id = "asset-$name",
        uri = "file:///tmp/$name.m4a",
        displayName = name,
        mediaType = MediaType.AUDIO,
        durationUs = 30 * second,
        width = 0,
        height = 0,
    )

    private fun projectWith(vararg assets: MediaAsset): EditorProject {
        val project = EditorProject.create("Test project")
        return TimelineEditor.appendAssets(project, assets.toList())
    }

    @Test
    fun `appending assets keeps the video lane gapless and in order`() {
        val first = videoAsset(4 * second, "a")
        val secondAsset = videoAsset(7 * second, "b")

        val project = projectWith(first, secondAsset)
        val clips = project.videoClips

        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startUs)
        assertEquals(4 * second, clips[0].durationUs)
        assertEquals(4 * second, clips[1].startUs)
        assertEquals(11 * second, project.durationUs)
        assertEquals(2, project.assets.size)
    }

    @Test
    fun `photos get a default duration instead of zero`() {
        val project = projectWith(imageAsset())
        val clip = project.videoClips.single()

        assertEquals(TimelineEditor.IMAGE_DEFAULT_DURATION_US, clip.durationUs)
        assertEquals(MediaType.IMAGE, clip.mediaType)
    }

    @Test
    fun `split produces two contiguous halves with matching source ranges`() {
        val project = projectWith(videoAsset(10 * second, "a"))
        val clip = project.videoClips.single()

        val split = TimelineEditor.splitAtPlayhead(project, 4 * second)
        val halves = split.videoClips

        assertEquals(2, halves.size)
        assertEquals(0L, halves[0].startUs)
        assertEquals(4 * second, halves[0].durationUs)
        assertEquals(0L, halves[0].sourceStartUs)
        assertEquals(4 * second, halves[0].sourceEndUs)

        assertEquals(4 * second, halves[1].startUs)
        assertEquals(6 * second, halves[1].durationUs)
        assertEquals(4 * second, halves[1].sourceStartUs)
        assertEquals(10 * second, halves[1].sourceEndUs)

        // Total source material is preserved.
        assertEquals(clip.durationUs, halves.sumOf { it.durationUs })
    }

    @Test
    fun `split is refused too close to an edge`() {
        val project = projectWith(videoAsset(10 * second))
        val split = TimelineEditor.splitAtPlayhead(project, 50_000L)

        assertEquals(1, split.videoClips.size)
        assertFalse(TimelineEditor.canSplitAt(project, 50_000L))
        assertTrue(TimelineEditor.canSplitAt(project, second))
    }

    @Test
    fun `deleting a clip reflows the remaining clips`() {
        val project = projectWith(
            videoAsset(3 * second, "a"),
            videoAsset(5 * second, "b"),
            videoAsset(2 * second, "c"),
        )
        val middle = project.videoClips[1]

        val after = TimelineEditor.deleteItem(project, middle.id)
        val clips = after.videoClips

        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startUs)
        assertEquals(3 * second, clips[1].startUs)
        assertEquals(5 * second, after.durationUs)
    }

    @Test
    fun `reordering moves a clip to the requested index without gaps`() {
        val project = projectWith(
            videoAsset(2 * second, "a"),
            videoAsset(3 * second, "b"),
            videoAsset(4 * second, "c"),
        )
        val first = project.videoClips.first()

        val reordered = TimelineEditor.moveClipToIndex(project, first.id, 2)
        val clips = reordered.videoClips

        assertEquals(listOf("b", "c", "a"), clips.map { it.label })
        assertEquals(0L, clips[0].startUs)
        assertEquals(3 * second, clips[1].startUs)
        assertEquals(7 * second, clips[2].startUs)
        assertEquals(9 * second, reordered.durationUs)
    }

    @Test
    fun `duplicating a clip inserts it right after the original`() {
        val project = projectWith(videoAsset(2 * second, "a"), videoAsset(2 * second, "b"))
        val first = project.videoClips.first()

        val duplicated = TimelineEditor.duplicateItem(project, first.id)
        val clips = duplicated.videoClips

        assertEquals(3, clips.size)
        assertEquals("a", clips[0].label)
        assertEquals("a", clips[1].label)
        assertEquals(2 * second, clips[1].startUs)
        assertNotEquals(clips[0].id, clips[1].id)
        assertEquals(6 * second, duplicated.durationUs)
    }

    @Test
    fun `trimming the head shortens the clip and moves into the source`() {
        val project = projectWith(videoAsset(10 * second))
        val clip = project.videoClips.single()

        val trimmed = TimelineEditor.trimStartBy(project, clip.id, 2 * second)
        val result = trimmed.videoClips.single()

        assertEquals(2 * second, result.sourceStartUs)
        assertEquals(8 * second, result.durationUs)
        assertEquals(0L, result.startUs)
        assertEquals(8 * second, trimmed.durationUs)
    }

    @Test
    fun `trimming the tail never goes below the minimum clip length`() {
        val project = projectWith(videoAsset(10 * second))
        val clip = project.videoClips.single()

        val trimmed = TimelineEditor.trimEndBy(project, clip.id, 20 * second)
        val result = trimmed.videoClips.single()

        assertEquals(TimeUtils.MIN_CLIP_DURATION_US, result.durationUs)
        assertEquals(result.sourceStartUs + TimeUtils.MIN_CLIP_DURATION_US, result.sourceEndUs)
    }

    @Test
    fun `changing speed rescales the timeline duration`() {
        val project = projectWith(videoAsset(10 * second))
        val clip = project.videoClips.single()

        val fast = TimelineEditor.setSpeed(project, clip.id, 2f)
        assertEquals(5 * second, fast.videoClips.single().durationUs)
        assertEquals(5 * second, fast.durationUs)

        val slow = TimelineEditor.setSpeed(project, clip.id, 0.5f)
        assertEquals(20 * second, slow.videoClips.single().durationUs)
    }

    @Test
    fun `photo ken burns motion is stored on the transform`() {
        val project = projectWith(imageAsset())
        val clip = project.videoClips.single()

        val updated = TimelineEditor.setTransform(
            project,
            clip.id,
            clip.transform.copy(kenBurns = KenBurnsMotion.ZOOM_IN),
        )

        assertEquals(KenBurnsMotion.ZOOM_IN, updated.videoClips.single().transform.kenBurns)
    }

    @Test
    fun `crop and rotation are sanitized into valid ranges`() {
        val project = projectWith(videoAsset(4 * second))
        val clip = project.videoClips.single()

        val updated = TimelineEditor.setTransform(
            project,
            clip.id,
            ClipTransform(
                rotationDegrees = -90,
                scale = 9f,
                cropLeft = 0.9f,
                cropRight = 0.1f,
                fit = FitMode.FILL,
            ),
        )
        val transform = updated.videoClips.single().transform

        assertEquals(270, transform.rotationDegrees)
        assertEquals(5f, transform.scale)
        assertTrue(transform.cropRight > transform.cropLeft)
        assertEquals(FitMode.FILL, transform.fit)
    }

    @Test
    fun `text overlays can be added split and removed`() {
        val project = projectWith(videoAsset(10 * second))
        val withText = TimelineEditor.addTextOverlay(
            project = project,
            text = "Hello",
            startUs = second,
            durationUs = 4 * second,
            style = TextStyleSpec.Caption,
        )
        val text = withText.textOverlays.single()

        assertEquals("Hello", text.text)
        assertEquals(second, text.startUs)
        assertEquals(TrackType.TEXT, withText.trackOf(text.id)?.type)

        val split = TimelineEditor.splitItem(withText, text.id, 2 * second)
        assertEquals(2, split.textOverlays.size)

        val removed = TimelineEditor.deleteItem(split, split.textOverlays.first().id)
        assertEquals(1, removed.textOverlays.size)
    }

    @Test
    fun `audio clips are added to the audio lane and moved freely`() {
        val project = projectWith(videoAsset(10 * second))
        val asset = audioAsset()
        val withAudio = TimelineEditor.addAudioClip(
            project = project.withAssets(listOf(asset)),
            asset = asset,
            startUs = 2 * second,
            durationUs = 5 * second,
        )
        val clip = withAudio.audioClips.single()

        assertEquals(2 * second, clip.startUs)
        assertEquals(5 * second, clip.durationUs)

        val moved = TimelineEditor.moveItemTo(withAudio, clip.id, 6 * second)
        assertEquals(6 * second, moved.audioClips.single().startUs)
        // The video lane is unaffected by audio moves.
        assertEquals(10 * second, moved.videoClips.single().durationUs)
    }

    @Test
    fun `transition and fades are applied to the clip`() {
        val project = projectWith(videoAsset(6 * second))
        val clip = project.videoClips.single()

        val withFades = TimelineEditor.setFades(project, clip.id, second, second / 2)
        val withTransition = TimelineEditor.setTransition(
            withFades,
            clip.id,
            TransitionConfig(type = TransitionType.FADE_TO_WHITE, durationUs = second),
        )
        val result = withTransition.videoClips.single()

        assertEquals(second, result.fadeInUs)
        assertEquals(second / 2, result.fadeOutUs)
        assertEquals(TransitionType.FADE_TO_WHITE, result.transition.type)
        assertEquals(second, result.transition.durationUs)
    }

    @Test
    fun `aspect ratio and canvas colour are project level settings`() {
        val project = projectWith(videoAsset(3 * second))

        val updated = TimelineEditor.setCanvasColor(
            TimelineEditor.setAspectRatio(project, AspectRatio.RATIO_9_16),
            0xFF112233,
        )

        assertEquals(AspectRatio.RATIO_9_16, updated.aspectRatio)
        assertEquals(0xFF112233L, updated.canvasColorArgb)
    }

    @Test
    fun `replacing a clip asset keeps its slot but swaps the source`() {
        val project = projectWith(videoAsset(4 * second, "a"))
        val clip = project.videoClips.single()
        val replacement = videoAsset(9 * second, "b")

        val replaced = TimelineEditor.replaceClipAsset(project, clip.id, replacement)
        val result = replaced.videoClips.single()

        assertEquals(replacement.id, result.assetId)
        assertEquals(0L, result.startUs)
        assertEquals(clip.id, result.id)
        assertTrue(result.durationUs > 0L)
    }
}
