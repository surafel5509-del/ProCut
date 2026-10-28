package com.procutstudio.editor.ui.editor.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated
import com.procutstudio.editor.ui.theme.ProCutSurfaceHigh
import com.procutstudio.editor.ui.theme.ProCutWarning

/**
 * Chrome shared by every bottom tool panel: a dark body, then a footer with an optional leading
 * control (for example "Apply to all"), the panel title and the confirm tick, like CapCut.
 */
@Composable
fun ToolPanelFrame(
    title: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    footerStart: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().background(ProCutSurface)) {
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ProCutOutline))
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(120.dp), contentAlignment = Alignment.CenterStart) {
                if (footerStart != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) { footerStart() }
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = ProCutOnSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.width(120.dp), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = onConfirm) {
                    Icon(Icons.Outlined.Check, contentDescription = "Done", tint = ProCutOnSurface, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** Underlined text tabs (Filters / Adjust / Video quality, effect categories...). */
@Composable
fun PanelTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(index) }.padding(vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) ProCutOnSurface else ProCutOnSurfaceMuted,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .background(if (selected) ProCutOnSurface else Color.Transparent),
                )
            }
        }
    }
}

/** Small rounded chip row (Featured, Pro, Life...). */
@Composable
fun PanelChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) ProCutSurfaceHigh else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) ProCutOnSurface else ProCutOnSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Gold "Pro" tag drawn on the corner of advanced looks, effects and stickers. */
@Composable
fun ProBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text("Pro", color = ProCutWarning, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** Thumbnail + caption used by the filter strip and the effect grid. */
@Composable
fun ThumbCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    isPro: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.width(size).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(ProCutSurfaceElevated)
                .border(
                    BorderStroke(if (selected) 2.dp else 0.dp, if (selected) ProCutPrimary else Color.Transparent),
                    RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
            if (isPro) ProBadge(Modifier.align(Alignment.TopEnd).padding(3.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) ProCutOnSurface else ProCutOnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Single-row slider with the label on the left and the value on the right (compact panels). */
@Composable
fun CompactSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueLabel: String = value.toInt().toString(),
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = ProCutOnSurfaceMuted,
            modifier = Modifier.width(76.dp),
            maxLines = 1,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = ProCutPrimary,
                inactiveTrackColor = ProCutOutline,
            ),
            modifier = Modifier.weight(1f).height(32.dp),
        )
        Text(
            text = valueLabel,
            style = MaterialTheme.typography.labelMedium,
            color = ProCutOnSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
    }
}

/** Round icon-less pill button for panel actions. */
@Composable
fun PanelPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) ProCutPrimary.copy(alpha = 0.25f) else ProCutSurfaceElevated)
            .border(1.dp, if (selected) ProCutPrimary else ProCutOutline, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) ProCutOnSurface else ProCutOnSurfaceMuted.copy(alpha = 0.4f),
            maxLines = 1,
        )
    }
}

/** "No effect" glyph shown on the None cards. */
@Composable
fun NoneGlyph() {
    Text("\u2298", color = ProCutOnSurfaceMuted, fontSize = 30.sp)
}
