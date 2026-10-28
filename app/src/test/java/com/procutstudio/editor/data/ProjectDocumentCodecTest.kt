package com.procutstudio.editor.data

import com.procutstudio.editor.data.local.ProjectDocumentCodec
import com.procutstudio.editor.data.local.ProjectDocumentException
import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.FilterPreset
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.MediaType
import com.procutstudio.editor.domain.model.ProjectSchema
import com.procutstudio.editor.domain.model.TextStyleSpec
import com.procutstudio.editor.domain.model.TransitionConfig
import com.procutstudio.editor.domain.model.TransitionType
import com.procutstudio.editor.domain.model.VideoEffect
import com.procutstudio.editor.domain.timeline.TimelineEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectDocumentCodecTest {

    private val codec = ProjectDocumentCodec()
    private val second = 1_000_000L

    private fun asset(id: String, type: MediaType = MediaType.VIDEO) = MediaAsset(
        id = id,
        uri = "file:///data/media/$id.mp4",
        displayName = id,
        mediaType = type,
        durationUs = if (type == MediaType.VIDEO) 8 * second else 20 * second,
        width = 1920,
        height = 1080,
        hasAudio = type == MediaType.VIDEO,
    )

    private fun sampleProject(): EditorProject {
        val video = asset("video-1")
        val audio = asset("audio-1", MediaType.AUDIO)
        var project = EditorProject.create("Roundtrip", AspectRatio.RATIO_9_16)
        project = TimelineEditor.appendAssets(project, listOf(video))
        project = TimelineEditor.setEffects(
            project,
            project.videoClips.single().id,
            VideoEffect(filter = FilterPreset.CINEMATIC, brightness = 12f),
        )
        project = TimelineEditor.setTransition(
            project,
            project.videoClips.single().id,
            TransitionConfig(TransitionType.FADE_TO_BLACK, second / 2),
        )
        project = TimelineEditor.setFades(project, project.videoClips.single().id, second, 0L)
        project = TimelineEditor.addTextOverlay(
            project = project,
            text = "Roundtrip title",
            startUs = second,
            durationUs = 2 * second,
            style = TextStyleSpec.Title,
        )
        project = TimelineEditor.addAudioClip(
            project = project,
            asset = audio,
            startUs = second,
            durationUs = 4 * second,
            volume = 0.6f,
        )
        return project.copy(canvasColorArgb = 0xFF101018)
    }

    @Test
    fun `project survives an encode decode roundtrip`() {
        val project = sampleProject()

        val decoded = codec.decode(codec.encode(project))

        assertEquals(project.id, decoded.id)
        assertEquals(project.name, decoded.name)
        assertEquals(ProjectSchema.CURRENT_VERSION, decoded.schemaVersion)
        assertEquals(project.aspectRatio, decoded.aspectRatio)
        assertEquals(project.canvasColorArgb, decoded.canvasColorArgb)
        assertEquals(project.videoClips.size, decoded.videoClips.size)
        assertEquals(project.textOverlays.size, decoded.textOverlays.size)
        assertEquals(project.audioClips.size, decoded.audioClips.size)
        assertEquals(project.assets.size, decoded.assets.size)

        val clip = decoded.videoClips.single()
        assertEquals(FilterPreset.CINEMATIC, clip.effects.filter)
        assertEquals(12f, clip.effects.brightness, 0.001f)
        assertEquals(TransitionType.FADE_TO_BLACK, clip.transition.type)
        assertEquals(second, clip.fadeInUs)
        assertEquals("Roundtrip title", decoded.textOverlays.single().text)
        assertEquals(0.6f, decoded.audioClips.single().volume, 0.001f)
        assertEquals(project.durationUs, decoded.durationUs)
    }

    @Test
    fun `encoded document carries the schema version`() {
        val encoded = codec.encode(sampleProject())
        assertTrue(encoded.contains("\"schemaVersion\""))
        assertTrue(encoded.contains("\"type\":\"clip\""))
    }

    @Test
    fun `unknown fields from a future build are ignored`() {
        val encoded = codec.encode(sampleProject())
        val patched = encoded.replaceFirst("{", "{\"fieldFromTheFuture\":{\"nested\":true},")

        val decoded = codec.decode(patched)
        assertEquals(sampleProject().videoClips.size, decoded.videoClips.size)
    }

    @Test
    fun `a newer schema version is rejected with a clear message`() {
        val encoded = codec.encode(sampleProject()).replace(
            "\"schemaVersion\":${ProjectSchema.CURRENT_VERSION}",
            "\"schemaVersion\":99",
        )
        try {
            codec.decode(encoded)
            fail("Expected ProjectDocumentException")
        } catch (expected: ProjectDocumentException) {
            assertTrue(expected.message.orEmpty().contains("newer version"))
        }
    }

    @Test
    fun `corrupted json is reported instead of crashing`() {
        try {
            codec.decode("this is not json")
            fail("Expected ProjectDocumentException")
        } catch (expected: ProjectDocumentException) {
            assertNotNull(expected.message)
            assertFalse(expected.message.isNullOrBlank())
        }
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val encoded = codec.encode(sampleProject())
        // Remove the canvas colour: the decoder must fall back to the default instead of failing.
        val patched = encoded.replace(Regex("\"canvasColorArgb\":-?\\d+,"), "")

        val decoded = codec.decode(patched)

        assertEquals(0xFF000000L, decoded.canvasColorArgb)
        assertEquals(1, decoded.videoClips.size)
    }
}
