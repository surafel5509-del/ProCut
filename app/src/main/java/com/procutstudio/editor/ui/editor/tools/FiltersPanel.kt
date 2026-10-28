package com.procutstudio.editor.ui.editor.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.procutstudio.editor.domain.history.EditorCommands
import com.procutstudio.editor.domain.model.FilterCategory
import com.procutstudio.editor.domain.model.FilterPreset
import com.procutstudio.editor.domain.model.VideoEffect
import com.procutstudio.editor.media.render.LookProcessor
import com.procutstudio.editor.ui.editor.EditorUiState
import com.procutstudio.editor.ui.editor.EditorViewModel
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A look counts as "Pro" when it layers split-toning or grain on top of the basic grade. */
val FilterPreset.isPro: Boolean get() = this != FilterPreset.NONE && (shadowTint != 0L || highlightTint != 0L || grain > 0f)

private class FilterGroup(val label: String, val presets: List<FilterPreset>)

private val FilterGroups: List<FilterGroup> = buildList {
    add(FilterGroup("Featured", FilterPreset.inCategory(FilterCategory.FEATURED)))
    add(FilterGroup("Pro", FilterPreset.visualPresets.filter { it.isPro }))
    FilterCategory.entries.filter { it != FilterCategory.FEATURED }.forEach { category ->
        add(FilterGroup(category.label, FilterPreset.inCategory(category)))
    }
}

private val TabLabels = listOf("Filters", "Adjust", "Video quality")

/** CapCut-style Filters panel: Filters / Adjust / Video quality tabs, category chips, cards. */
@Composable
fun FiltersPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
) {
    val clip = state.selectedClip ?: state.clipAtPlayhead
    val effect = clip?.effects ?: VideoEffect()
    var tab by remember { mutableStateOf(initialTab) }
    var groupIndex by remember { mutableStateOf(0) }

    ToolPanelFrame(
        title = TabLabels[tab],
        onConfirm = onClose,
        modifier = modifier,
        footerStart = {
            PanelPill(label = "Apply to all", onClick = { viewModel.applyEffectsToAll(effect) }, enabled = clip != null)
        },
    ) {
        PanelTabs(TabLabels, tab, { tab = it })
        when (tab) {
            0 -> FilterBrowser(
                clipId = clip?.id,
                effect = effect,
                viewModel = viewModel,
                groupIndex = groupIndex,
                onGroup = { groupIndex = it },
            )
            1 -> AdjustSliders(effect, viewModel)
            else -> QualitySliders(effect, viewModel)
        }
    }
}

@Composable
private fun FilterBrowser(
    clipId: String?,
    effect: VideoEffect,
    viewModel: EditorViewModel,
    groupIndex: Int,
    onGroup: (Int) -> Unit,
) {
    val source by produceState<Bitmap?>(initialValue = null, clipId) {
        value = viewModel.loadFilterSource()
    }
    val cache = remember(source) { HashMap<FilterPreset, ImageBitmap>() }
    val group = FilterGroups[groupIndex.coerceIn(0, FilterGroups.lastIndex)]

    PanelChips(FilterGroups.map { it.label }, groupIndex, onGroup, Modifier.padding(vertical = 4.dp))
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "none") {
            ThumbCard(
                label = "None",
                selected = effect.filter == FilterPreset.NONE,
                onClick = { viewModel.applyFilter(FilterPreset.NONE) },
                size = 62.dp,
            ) { NoneGlyph() }
        }
        items(group.presets, key = { it.name }) { preset ->
            ThumbCard(
                label = preset.label,
                selected = effect.filter == preset,
                onClick = { viewModel.applyFilter(preset) },
                size = 62.dp,
                isPro = preset.isPro,
            ) {
                FilterThumb(source = source, preset = preset, cache = cache)
            }
        }
    }
    val hasFilter = effect.filter != FilterPreset.NONE
    Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        CompactSlider(
            label = "Intensity",
            value = effect.filterIntensity * 100f,
            range = 0f..100f,
            enabled = hasFilter,
            onValueChange = { viewModel.previewEffects(effect.copy(filterIntensity = it / 100f)) },
            onValueChangeFinished = { viewModel.commitTransient(EditorCommands.FILTER) },
        )
    }
}

@Composable
private fun FilterThumb(source: Bitmap?, preset: FilterPreset, cache: HashMap<FilterPreset, ImageBitmap>) {
    val image by produceState<ImageBitmap?>(initialValue = cache[preset], source, preset) {
        val src = source
        if (src != null && cache[preset] == null) {
            val graded = withContext(Dispatchers.Default) {
                LookProcessor.graded(src, VideoEffect(filter = preset, filterIntensity = 1f)).asImageBitmap()
            }
            cache[preset] = graded
            value = graded
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = preset.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF3B3B52), Color(0xFF1B1B2A)))),
        )
    }
}

private data class SliderSpec(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val read: (VideoEffect) -> Float,
    val write: (VideoEffect, Float) -> VideoEffect,
    val display: (Float) -> String = { it.toInt().toString() },
)

private val AdjustSpecs = listOf(
    SliderSpec("Brightness", -100f..100f, { it.brightness }, { e, v -> e.copy(brightness = v) }),
    SliderSpec("Contrast", -100f..100f, { it.contrast }, { e, v -> e.copy(contrast = v) }),
    SliderSpec("Saturation", -100f..100f, { it.saturation }, { e, v -> e.copy(saturation = v) }),
    SliderSpec("Temperature", -100f..100f, { it.warmth }, { e, v -> e.copy(warmth = v) }),
    SliderSpec("Tint", -100f..100f, { it.tint }, { e, v -> e.copy(tint = v) }),
    SliderSpec("Highlights", -100f..100f, { it.highlights }, { e, v -> e.copy(highlights = v) }),
    SliderSpec("Shadows", -100f..100f, { it.shadows }, { e, v -> e.copy(shadows = v) }),
    SliderSpec("Hue", -180f..180f, { it.hueDegrees }, { e, v -> e.copy(hueDegrees = v) }, { "${it.toInt()}°" }),
    SliderSpec("Fade", 0f..100f, { it.fade }, { e, v -> e.copy(fade = v) }),
    SliderSpec("Vignette", 0f..100f, { it.vignette * 100f }, { e, v -> e.copy(vignette = v / 100f) }),
)

private val QualitySpecs = listOf(
    SliderSpec("Sharpen", 0f..100f, { it.sharpen * 100f }, { e, v -> e.copy(sharpen = v / 100f) }),
    SliderSpec("Film grain", 0f..100f, { it.grain * 100f }, { e, v -> e.copy(grain = v / 100f) }),
)

@Composable
private fun AdjustSliders(effect: VideoEffect, viewModel: EditorViewModel) {
    SliderList(AdjustSpecs, effect, viewModel)
}

@Composable
private fun QualitySliders(effect: VideoEffect, viewModel: EditorViewModel) {
    SliderList(QualitySpecs, effect, viewModel)
    Text(
        text = "Sharpen and grain are computed per pixel in the GPU shader, in the preview and in the export.",
        style = MaterialTheme.typography.labelSmall,
        color = ProCutOnSurfaceMuted,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun SliderList(specs: List<SliderSpec>, effect: VideoEffect, viewModel: EditorViewModel) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(specs, key = { it.label }) { spec ->
            val value = spec.read(effect)
            CompactSlider(
                label = spec.label,
                value = value,
                range = spec.range,
                valueLabel = spec.display(value),
                onValueChange = { viewModel.previewEffects(spec.write(effect, it)) },
                onValueChangeFinished = { viewModel.commitTransient(EditorCommands.ADJUST) },
            )
        }
        item(key = "reset") {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                PanelPill(
                    label = "Reset adjustments",
                    onClick = {
                        viewModel.setEffects(effect.copy(
                            brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f, hueDegrees = 0f,
                            vignette = 0f, tint = 0f, highlights = 0f, shadows = 0f, fade = 0f, sharpen = 0f, grain = 0f,
                        ))
                    },
                    enabled = effect.hasManualAdjustments,
                )
            }
        }
    }
}
