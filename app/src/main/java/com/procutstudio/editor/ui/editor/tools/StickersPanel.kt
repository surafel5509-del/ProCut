package com.procutstudio.editor.ui.editor.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procutstudio.editor.media.stickers.StickerCatalog
import com.procutstudio.editor.media.stickers.StickerCategory
import com.procutstudio.editor.media.stickers.StickerDef
import com.procutstudio.editor.ui.editor.EditorViewModel
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated
import com.procutstudio.editor.ui.theme.ProCutSurfaceHigh

/**
 * "Let's explore stickers" panel: category icon row, search, and a 4-column grid of dark tiles.
 * Every tile is rendered on the device by `StickerRenderer`; tapping one drops it on the overlay
 * lane at the playhead, where it can be dragged, pinched and rotated on the preview.
 */
@Composable
fun StickersPanel(
    viewModel: EditorViewModel,
    onImportFromGallery: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var category by remember { mutableStateOf(StickerCategory.TRENDING) }
    var query by remember { mutableStateOf("") }
    val stickers: List<StickerDef> = when {
        query.isNotBlank() -> StickerCatalog.search(query)
        else -> StickerCatalog.inCategory(category)
    }

    ToolPanelFrame(title = "Stickers", onConfirm = onClose, modifier = modifier) {
        Text(
            text = "Let's explore stickers",
            style = MaterialTheme.typography.titleSmall,
            color = ProCutOnSurface,
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 14.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StickerCategory.entries.forEach { entry ->
                val selected = query.isBlank() && entry == category
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (selected) ProCutSurfaceHigh else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable {
                            query = ""
                            category = entry
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(entry.icon, fontSize = 18.sp)
                }
            }
        }
        SearchField(query = query, onQuery = { query = it }, hint = "Search stickers")
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "import") {
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ProCutSurfaceElevated)
                        .clickable(onClick = onImportFromGallery),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Import image", tint = ProCutPrimary)
                }
            }
            items(stickers, key = { it.id }) { def ->
                StickerTile(def = def, viewModel = viewModel, onClick = { viewModel.addSticker(def) })
            }
        }
        if (stickers.isEmpty()) {
            Text(
                "No stickers match \"$query\"",
                style = MaterialTheme.typography.labelMedium,
                color = ProCutOnSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
    }
}

@Composable
private fun StickerTile(def: StickerDef, viewModel: EditorViewModel, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, def.id) {
        value = runCatching { viewModel.stickerTile(def) }.getOrNull()
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(ProCutSurfaceElevated)
            .clickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = def.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (def.isPro) ProBadge(Modifier.align(Alignment.TopEnd))
    }
}
