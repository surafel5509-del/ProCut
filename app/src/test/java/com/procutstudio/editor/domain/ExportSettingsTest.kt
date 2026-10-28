package com.procutstudio.editor.domain

import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.ExportResolution
import com.procutstudio.editor.domain.model.ExportSettings
import com.procutstudio.editor.domain.model.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSettingsTest {

    private fun spec(
        resolution: ExportResolution = ExportResolution.FHD_1080,
        fps: FrameRate = FrameRate.FPS_30,
        aspect: AspectRatio = AspectRatio.RATIO_16_9,
        bitrate: Int = 0,
    ) = ExportSettings(resolution = resolution, frameRate = fps, videoBitrate = bitrate).toOutputSpec(aspect)

    @Test
    fun `1080p landscape renders 1920x1080`() {
        val out = spec()
        assertEquals(1920, out.width)
        assertEquals(1080, out.height)
        assertEquals(30, out.fps)
    }

    @Test
    fun `portrait renders 1080x1920`() {
        val out = spec(aspect = AspectRatio.RATIO_9_16)
        assertEquals(1080, out.width)
        assertEquals(1920, out.height)
    }

    @Test
    fun `square and 4 by 5 keep the short side at the requested height`() {
        val square = spec(aspect = AspectRatio.RATIO_1_1)
        assertEquals(1080, square.width)
        assertEquals(1080, square.height)

        val fourByFive = spec(aspect = AspectRatio.RATIO_4_5)
        assertEquals(1080, fourByFive.width)
        assertEquals(1350, fourByFive.height)
    }

    @Test
    fun `4K renders 3840x2160`() {
        val out = spec(resolution = ExportResolution.UHD_4K)
        assertEquals(3840, out.width)
        assertEquals(2160, out.height)
    }

    @Test
    fun `every output dimension is even`() {
        val all = listOf(ExportResolution.HD_720, ExportResolution.FHD_1080, ExportResolution.UHD_4K)
            .flatMap { resolution ->
                AspectRatio.entries.map { aspect ->
                    spec(resolution = resolution, aspect = aspect, bitrate = 0)
                }
            }
        all.forEach { out ->
            assertEquals(0, out.width % 2)
            assertEquals(0, out.height % 2)
        }
    }

    @Test
    fun `frame rate selection reaches the output spec`() {
        assertEquals(24, spec(fps = FrameRate.FPS_24).fps)
        assertEquals(60, spec(fps = FrameRate.FPS_60).fps)
    }

    @Test
    fun `automatic bitrate scales with pixels and frame rate`() {
        val hd30 = spec(resolution = ExportResolution.HD_720, fps = FrameRate.FPS_30)
        val hd60 = spec(resolution = ExportResolution.HD_720, fps = FrameRate.FPS_60)
        val uhd30 = spec(resolution = ExportResolution.UHD_4K, fps = FrameRate.FPS_30)

        assertTrue(hd30.videoBitrate in 2_000_000..45_000_000)
        assertTrue(hd60.videoBitrate > hd30.videoBitrate)
        assertTrue(uhd30.videoBitrate > hd30.videoBitrate)
        assertEquals(ExportSettings.automaticBitrate(hd30.width, hd30.height, 30), hd30.videoBitrate)
    }

    @Test
    fun `a custom bitrate is used verbatim`() {
        val out = spec(bitrate = 18_000_000)
        assertEquals(18_000_000, out.videoBitrate)
    }

    @Test
    fun `audio and codec choices are carried into the spec`() {
        val out = ExportSettings(includeAudio = false).toOutputSpec(AspectRatio.RATIO_16_9)
        assertEquals(false, out.includeAudio)
        assertEquals(192_000, ExportSettings().audioBitrate)
    }

    @Test
    fun `output spec exposes a readable label`() {
        val out = spec()
        assertTrue(out.label.contains("1920"))
        assertTrue(out.label.contains("30"))
    }
}
