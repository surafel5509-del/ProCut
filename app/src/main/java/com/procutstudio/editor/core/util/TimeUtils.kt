package com.procutstudio.editor.core.util

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * ProCut Studio stores every duration and position in **microseconds**.
 *
 * Two clocks exist and must never be mixed up:
 *  - **source time**: a position inside the original media file (`sourceStartUs`, `sourceEndUs`).
 *  - **timeline time**: a position on the editor timeline (`startUs`, `durationUs`).
 *
 * Converting between them always goes through the clip's speed and its source offset.
 */
object TimeUtils {

    const val MICROS_PER_MILLI = 1_000L
    const val MICROS_PER_SECOND = 1_000_000L
    const val MICROS_PER_MINUTE = 60 * MICROS_PER_SECOND

    /** Smallest clip length the editor allows (one frame at 10 fps). */
    const val MIN_CLIP_DURATION_US = 100_000L

    fun secondsToUs(seconds: Double): Long = (seconds * MICROS_PER_SECOND).roundToLong()

    fun usToSeconds(us: Long): Double = us.toDouble() / MICROS_PER_SECOND

    fun msToUs(ms: Long): Long = ms * MICROS_PER_MILLI

    fun usToMs(us: Long): Long = us / MICROS_PER_MILLI

    /** `m:ss` or `h:mm:ss` label used by the transport bar and the timeline ruler. */
    fun formatClock(us: Long, showMillis: Boolean = false): String {
        val clamped = max(0L, us)
        val totalSeconds = clamped / MICROS_PER_SECOND
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = (clamped % MICROS_PER_SECOND) / 1000
        val base = if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
        return if (showMillis) "%s.%02d".format(base, millis / 10) else base
    }

    /** Compact ruler label: 0:05, 1:30, 1:02:00. */
    fun formatRuler(us: Long): String = formatClock(us)

    /** `1.5s`, `12.0s` — used for clip badges. */
    fun formatSeconds(us: Long): String = "%.1fs".format(usToSeconds(max(0L, us)))

    fun formatDurationLong(us: Long): String {
        val totalSeconds = max(0L, us) / MICROS_PER_SECOND
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    fun msToClock(ms: Long): String = formatClock(msToUs(ms))
}
