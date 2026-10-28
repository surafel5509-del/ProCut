package com.procutstudio.editor.domain.subtitles

/**
 * One subtitle block: when it appears on the timeline and what it says.
 * Times are microseconds, matching the rest of the project document.
 */
data class SubtitleCue(
    val startUs: Long,
    val endUs: Long,
    val text: String,
) {
    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(1L)
}

/**
 * Minimal but strict SRT (SubRip) reader.
 *
 * The format is a sequence of blocks separated by blank lines:
 *
 * ```
 * 1
 * 00:00:01,000 --> 00:00:04,000
 * First line
 * Second line
 * ```
 *
 * Handles BOMs, CRLF line endings, comma or dot millisecond separators, missing sequence numbers
 * and stray blank lines inside text. Blocks that cannot be parsed are skipped instead of failing
 * the whole import, and a summary of skipped blocks is returned so the UI can be honest about it.
 */
object SrtParser {

    data class Result(
        val cues: List<SubtitleCue>,
        val skippedBlocks: Int,
    )

    private val TIMECODE = Regex(
        """^\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""",
    )

    fun parse(raw: String, offsetUs: Long = 0L): Result {
        val normalized = raw
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val cues = mutableListOf<SubtitleCue>()
        var skipped = 0

        val blocks = normalized.split(Regex("\n\\s*\n"))
        blocks.forEach { block ->
            val lines = block.trim('\n').lines()
            if (lines.isEmpty()) return@forEach
            // An optional numeric index comes first; the timecode is the first matching line.
            val timecodeIndex = lines.indexOfFirst { TIMECODE.containsMatchIn(it) }
            if (timecodeIndex < 0) {
                if (block.isNotBlank()) skipped++
                return@forEach
            }
            val match = TIMECODE.find(lines[timecodeIndex]) ?: run { skipped++; return@forEach }
            val startUs = timecodeToUs(match, 1)
            val endUs = timecodeToUs(match, 5)
            val text = lines.drop(timecodeIndex + 1)
                .joinToString("\n")
                .trim()
                .replace(Regex("<[^>]+>"), "")   // strip simple HTML tags (<i>, <b>, <font>)
                .trim()
            if (text.isEmpty() || endUs <= startUs) {
                skipped++
                return@forEach
            }
            cues += SubtitleCue(
                startUs = (startUs + offsetUs).coerceAtLeast(0L),
                endUs = (endUs + offsetUs).coerceAtLeast(0L),
                text = text,
            )
        }

        return Result(
            cues = cues.sortedBy { it.startUs },
            skippedBlocks = skipped,
        )
    }

    private fun timecodeToUs(match: MatchResult, group: Int): Long {
        val hours = match.groupValues[group].toLong()
        val minutes = match.groupValues[group + 1].toLong()
        val seconds = match.groupValues[group + 2].toLong()
        val fraction = match.groupValues[group + 3].padEnd(3, '0').take(3).toLong()
        return hours * 3_600_000_000L +
            minutes * 60_000_000L +
            seconds * 1_000_000L +
            fraction * 1_000L
    }
}
