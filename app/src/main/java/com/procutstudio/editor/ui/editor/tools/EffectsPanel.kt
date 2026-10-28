package com.procutstudio.editor.ui.editor.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procutstudio.editor.domain.history.EditorCommands
import com.procutstudio.editor.domain.model.EffectItem
import com.procutstudio.editor.domain.model.FxCategory
import com.procutstudio.editor.domain.model.VideoFxType
import com.procutstudio.editor.ui.editor.EditorUiState
import com.procutstudio.editor.ui.editor.EditorViewModel
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceFaint
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated

/** Effects built from several shader stages; these carry the "Pro" tag. */
private val ProEffects: Set<VideoFxType> = setOf(
    VideoFxType.KALEIDOSCOPE, VideoFxType.GLITCH, VideoFxType.VHS, VideoFxType.DISCO,
    VideoFxType.NEON_EDGE, VideoFxType.ZOOM_BLUR, VideoFxType.TILT_SHIFT, VideoFxType.LIGHT_LEAK,
    VideoFxType.SPARKLE, VideoFxType.RIPPLE, VideoFxType.RGB_SPLIT, VideoFxType.OLD_FILM,
)

val VideoFxType.isPro: Boolean get() = this in ProEffects

private val EffectTabs: List<String> =
    listOf(FxCategory.TRENDING.label, "Pro") + FxCategory.entries.filter { it != FxCategory.TRENDING }.map { it.label }

private fun effectsForTab(index: Int): List<VideoFxType> = when (index) {
    0 -> VideoFxType.inCategory(FxCategory.TRENDING)
    1 -> VideoFxType.entries.filter { it.isPro }
    else -> VideoFxType.inCategory(FxCategory.entries.filter { it != FxCategory.TRENDING }[index - 2])
}

private fun categoryColors(type: VideoFxType): List<Color> = when (type.category) {
    FxCategory.TRENDING, FxCategory.BASIC -> listOf(Color(0xFF4C3A8A), Color(0xFF1F1A3D))
    FxCategory.GLITCH -> listOf(Color(0xFF0E5E6F), Color(0xFF3B0F5C))
    FxCategory.RETRO -> listOf(Color(0xFF7A4B1F), Color(0xFF2E1B0E))
    FxCategory.NIGHTCLUB -> listOf(Color(0xFF8A1F6B), Color(0xFF1B1038))
    FxCategory.LENS -> listOf(Color(0xFF1F4E8A), Color(0xFF101C38))
    FxCategory.NATURE -> listOf(Color(0xFF1F7A5A), Color(0xFF0E2E28))
}

/**
 * CapCut-style Effects panel. Tapping a card drops the effect on the effects lane at the playhead
 * (or swaps the type of the selected effect); its length and position are then edited directly on
 * the timeline and its strength with the intensity slider.
 */
@Composable
fun EffectsPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val selected = state.selectedItem as? EffectItem
    val visible: List<VideoFxType> = if (query.isBlank()) {
        effectsForTab(tab)
    } else {
        VideoFxType.entries.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    ToolPanelFrame(
        title = "Effects",
        onConfirm = onClose,
        modifier = modifier,
        footerStart = {
            if (selected != null) {
                PanelPill("Whole video", onClick = { viewModel.spreadEffectOverVideo(selected.id) })
            }
        },
    ) {
        SearchField(query = query, onQuery = { query = it })
        if (query.isBlank()) PanelTabs(EffectTabs, tab, { tab = it })
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "none") {
                ThumbCard(
                    label = "None",
                    selected = false,
                    onClick = { viewModel.removeEffectsAtPlayhead() },
                    size = 60.dp,
                ) { NoneGlyph() }
            }
            items(visible, key = { it.name }) { type ->
                ThumbCard(
                    label = type.label,
                    selected = selected?.type == type,
                    onClick = { viewModel.addVideoEffect(type) },
                    size = 60.dp,
                    isPro = type.isPro,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Brush.linearGradient(categoryColors(type))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(type.emoji, fontSize = 24.sp)
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            Text(
                "No effects match \"$query\"",
                style = MaterialTheme.typography.labelMedium,
                color = ProCutOnSurfaceMuted,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (selected != null) {
            CompactSlider(
                label = "Intensity",
                value = selected.intensity * 100f,
                range = 0f..100f,
                onValueChange = { viewModel.previewEffectIntensity(selected.id, it / 100f) },
                onValueChangeFinished = { viewModel.commitTransient(EditorCommands.EDIT_EFFECT) },
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Text(
                "Tap an effect to add it at the playhead",
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurfaceFaint,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun SearchField(query: String, onQuery: (String) -> Unit, hint: String = "Search") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ProCutSurfaceElevated)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = ProCutOnSurfaceMuted, modifier = Modifier.size(16.dp))
        Box(modifier = Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(hint, style = MaterialTheme.typography.labelMedium, color = ProCutOnSurfaceFaint)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = ProCutOnSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(ProCutPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Clear",
                tint = ProCutOnSurfaceMuted,
                modifier = Modifier.size(16.dp).clickable { onQuery("") },
            )
        }
    }
}
