package com.procutstudio.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface

/**
 * One button of the bottom toolbar. Either an [icon] or a text [glyph] is drawn above the label.
 */
class ToolbarEntry(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val glyph: String? = null,
    val enabled: Boolean = true,
    val highlighted: Boolean = false,
)

/**
 * Bottom toolbar. It is a single horizontally scrolling row whose content depends on what is
 * selected (nothing → project tools, a clip → clip tools, ...), like CapCut's contextual toolbar.
 */
@Composable
fun EditorToolbar(
    entries: List<ToolbarEntry>,
    showBack: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ProCutSurface)
            .height(62.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            ToolbarButton(
                entry = ToolbarEntry(label = "Back", onClick = onBack, icon = Icons.AutoMirrored.Outlined.ArrowBack),
            )
            Box(modifier = Modifier.width(1.dp).height(30.dp).background(ProCutOnSurfaceMuted.copy(alpha = 0.25f)))
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEach { ToolbarButton(it) }
        }
    }
}

@Composable
private fun ToolbarButton(entry: ToolbarEntry) {
    val tint = when {
        !entry.enabled -> ProCutOnSurfaceMuted.copy(alpha = 0.35f)
        entry.highlighted -> ProCutPrimary
        else -> ProCutOnSurface
    }
    Column(
        modifier = Modifier
            .width(64.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = entry.enabled, onClick = entry.onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (entry.icon != null) {
                Icon(entry.icon, contentDescription = entry.label, tint = tint, modifier = Modifier.size(22.dp))
            } else if (entry.glyph != null) {
                Text(entry.glyph, color = tint, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
        )
    }
}
