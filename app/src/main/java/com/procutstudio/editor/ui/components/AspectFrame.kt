package com.procutstudio.editor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Centers [content] inside a box with the project's output aspect ratio, filling whichever axis is
 * limiting. Everything drawn inside uses the exact exported frame proportions.
 */
@Composable
fun AspectFrame(
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black,
    cornerRadius: Int = 12,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val widthIfHeightBound = availableHeight * aspectRatio
        val useWidth = widthIfHeightBound <= availableWidth
        Box(
            modifier = Modifier
                .then(if (useWidth) Modifier.fillMaxHeight().aspectRatio(aspectRatio) else Modifier.fillMaxWidth().aspectRatio(aspectRatio))
                .clip(RoundedCornerShape(cornerRadius.dp))
                .background(backgroundColor),
        ) {
            content(Modifier.fillMaxSize())
        }
    }
}
