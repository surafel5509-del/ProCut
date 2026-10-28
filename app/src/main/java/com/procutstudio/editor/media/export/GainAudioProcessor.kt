package com.procutstudio.editor.media.export

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies clip volume plus sample accurate fade-in / fade-out ramps.
 *
 * Media3 has no built-in gain processor, so this is a real audio transform: every PCM sample of the
 * clip is scaled by the current envelope. It is inserted into the `Effects` of an `EditedMediaItem`
 * (after the speed processor, so the envelope follows the *timeline* duration of the clip).
 *
 * The processor reports itself inactive when the clip is at unity gain with no fades, which lets
 * Media3 skip the pass entirely for untouched audio.
 */
@UnstableApi
class GainAudioProcessor(
    private val volume: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val totalDurationUs: Long,
) : BaseAudioProcessor() {

    private val hasWork = volume != 1f || fadeInUs > 0L || fadeOutUs > 0L

    private var framesWritten = 0L
    private var channelCount = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!hasWork) return AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        // Media3's pipeline carries PCM16 in native (little-endian) order; a freshly allocated
        // byte buffer defaults to big-endian, so the order is set explicitly before sampling.
        inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(remaining)
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        val channels = channelCount.coerceAtLeast(1)
        while (inputBuffer.remaining() >= 2) {
            val frame = framesWritten / channels
            val timeUs = frame * 1_000_000L / sampleRate
            val gain = gainAt(timeUs)
            val sample = inputBuffer.short.toInt()
            val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(scaled.toShort())
            framesWritten++
        }
        output.flip()
    }

    private fun gainAt(timeUs: Long): Float {
        var gain = volume
        if (fadeInUs > 0L && timeUs < fadeInUs) {
            gain *= (timeUs.toFloat() / fadeInUs.toFloat()).coerceIn(0f, 1f)
        }
        if (fadeOutUs > 0L) {
            val remaining = totalDurationUs - timeUs
            if (remaining < fadeOutUs) {
                gain *= (remaining.toFloat() / fadeOutUs.toFloat()).coerceIn(0f, 1f)
            }
        }
        return gain.coerceIn(0f, 2f)
    }

    override fun onFlush() {
        framesWritten = 0L
    }

    override fun onReset() {
        framesWritten = 0L
        channelCount = 0
    }
}
