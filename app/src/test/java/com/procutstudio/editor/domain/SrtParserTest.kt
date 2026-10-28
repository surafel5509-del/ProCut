package com.procutstudio.editor.domain

import com.procutstudio.editor.domain.subtitles.SrtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtParserTest {

    @Test
    fun `parses a standard srt file`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,500
            Hello there

            2
            00:00:05,250 --> 00:00:08,000
            Second line

        """.trimIndent()

        val result = SrtParser.parse(srt)

        assertEquals(2, result.cues.size)
        assertEquals(0, result.skippedBlocks)
        assertEquals(1_000_000L, result.cues[0].startUs)
        assertEquals(4_500_000L, result.cues[0].endUs)
        assertEquals("Hello there", result.cues[0].text)
        assertEquals(5_250_000L, result.cues[1].startUs)
    }

    @Test
    fun `keeps multi line subtitles`() {
        val srt = "1\n00:00:02,000 --> 00:00:05,000\nFirst line\nSecond line\n"
        val cue = SrtParser.parse(srt).cues.single()
        assertEquals("First line\nSecond line", cue.text)
        assertEquals(3_000_000L, cue.durationUs)
    }

    @Test
    fun `accepts dot millisecond separators and crlf`() {
        val srt = "1\r\n00:00:01.500 --> 00:00:03.000\r\nDot separators\r\n"
        val cue = SrtParser.parse(srt).cues.single()
        assertEquals(1_500_000L, cue.startUs)
        assertEquals(3_000_000L, cue.endUs)
    }

    @Test
    fun `honours an offset`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nShifted\n"
        val cue = SrtParser.parse(srt, offsetUs = 4_000_000L).cues.single()
        assertEquals(5_000_000L, cue.startUs)
        assertEquals(6_000_000L, cue.endUs)
    }

    @Test
    fun `strips simple html tags`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n<i>Italic</i> text\n"
        assertEquals("Italic text", SrtParser.parse(srt).cues.single().text)
    }

    @Test
    fun `skips malformed blocks but keeps the good ones`() {
        val srt = """
            not a cue

            2
            00:00:03,000 --> 00:00:04,000
            Valid cue

            3
            99:99:99,999 --> garbage

        """.trimIndent()

        val result = SrtParser.parse(srt)

        assertEquals(1, result.cues.size)
        assertEquals("Valid cue", result.cues.single().text)
        assertTrue(result.skippedBlocks >= 1)
    }

    @Test
    fun `empty input yields nothing`() {
        val result = SrtParser.parse("")
        assertEquals(0, result.cues.size)
    }

    @Test
    fun `bom is ignored`() {
        val srt = "\uFEFF1\n00:00:01,000 --> 00:00:02,000\nBOM cue\n"
        assertEquals("BOM cue", SrtParser.parse(srt).cues.single().text)
    }
}
