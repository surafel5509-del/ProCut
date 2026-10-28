package com.procutstudio.editor.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/**
 * One-and-two-finger gesture for stickers and text on the preview: drag to move, pinch to scale,
 * twist to rotate. [onStart] fires on the first touch (selects the item) and [onEnd] when the last
 * finger lifts, so the caller can record exactly one undo step per gesture.
 *
 * [onTransform] receives the pan in pixels (in the element's own coordinate space), the zoom
 * factor and the rotation delta in degrees of this event.
 */
@Composable
fun Modifier.overlayGestures(
    key: Any?,
    onStart: () -> Unit,
    onTransform: (pan: Offset, zoom: Float, rotationDegrees: Float) -> Unit,
    onEnd: () -> Unit,
): Modifier {
    val currentStart by rememberUpdatedState(onStart)
    val currentTransform by rememberUpdatedState(onTransform)
    val currentEnd by rememberUpdatedState(onEnd)
    return this.pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            currentStart()
            do {
                val event = awaitPointerEvent()
                val cancelled = event.changes.any { it.isConsumed }
                if (!cancelled) {
                    val zoom = event.calculateZoom()
                    val rotation = event.calculateRotation()
                    val pan = event.calculatePan()
                    if (zoom != 1f || rotation != 0f || pan != Offset.Zero) {
                        currentTransform(pan, zoom, rotation)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (!cancelled && event.changes.any { it.pressed })
            currentEnd()
        }
    }
}
