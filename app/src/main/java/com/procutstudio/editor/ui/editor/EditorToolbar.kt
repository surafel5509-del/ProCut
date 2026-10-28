package com.procutstudio.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.procutstudio.editor.ui.components.ToolAction
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface

/**
 * Bottom editing toolbar: structural quick actions plus the tool switcher used to enter each
 * editing surface (clip sheet, speed, filters, text, audio, canvas, stickers).
 */
@Composable
fun EditorToolbar(
    hasSelection: Boolean,
    canSplit: Boolean,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onAddMedia: () -> Unit,
    onEditClip: () -> Unit,
    onSpeed: () -> Unit,
    onFilters: () -> Unit,
    onText: () -> Unit,
    onAudio: () -> Unit,
    onCanvas: () -> Unit,
    onSticker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(ProCutSurface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            QuickAction(Icons.Outlined.ContentCut, "Split", enabled = canSplit, onClick = onSplit)
            QuickAction(Icons.Outlined.ContentCopy, "Duplicate", enabled = hasSelection, onClick = onDuplicate)
            QuickAction(Icons.Outlined.Delete, "Delete", enabled = hasSelection, onClick = onDelete)
            QuickAction(Icons.Outlined.PhotoLibrary, "Add media", enabled = true, onClick = onAddMedia)
            Box(modifier = Modifier.weight(1f))
            Text(
                text = if (hasSelection) "Clip selected" else "Tap a clip to edit",
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurfaceMuted,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            ToolAction(Icons.Outlined.Tune, "Edit", onEditClip, enabled = hasSelection, selected = hasSelection)
            ToolAction(Icons.Outlined.Speed, "Speed", onSpeed, enabled = hasSelection)
            ToolAction(Icons.Outlined.FilterVintage, "Filters", onFilters, enabled = hasSelection)
            ToolAction(Icons.Outlined.TextFields, "Text", onText)
            ToolAction(Icons.Outlined.MusicNote, "Audio", onAudio)
            ToolAction(Icons.Outlined.Wallpaper, "Canvas", onCanvas)
            ToolAction(Icons.Outlined.EmojiEmotions, "Sticker", onSticker)
        }
    }
}

@Composable
private fun RowScope.QuickAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) ProCutOnSurface else ProCutOnSurfaceMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Small helper used by the editor top bar for its primary action. */
@Composable
fun ToolbarLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = ProCutPrimary,
        modifier = modifier,
    )
}
