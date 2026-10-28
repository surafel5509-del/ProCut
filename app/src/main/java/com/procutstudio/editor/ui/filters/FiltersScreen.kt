package com.procutstudio.editor.ui.filters

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.procutstudio.editor.domain.model.FilterPreset
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.domain.model.VideoEffect
import com.procutstudio.editor.ui.components.PrimaryButton
import com.procutstudio.editor.ui.components.ProCutChip
import com.procutstudio.editor.ui.components.ProCutSlider
import com.procutstudio.editor.ui.components.ProCutTopBar
import com.procutstudio.editor.ui.components.SecondaryButton
import com.procutstudio.editor.ui.components.SectionTitle
import com.procutstudio.editor.ui.theme.ProCutBackground
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated

/**
 * Filters & adjustments screen with a live (CPU matrix) preview of the grade. The export pipeline
 * applies the same values through Media3 GL effects, so what is previewed here is what is rendered,
 * with the caveat noted in the UI: the live preview approximates hue shifts.
 */
@Composable
fun FiltersScreen(
    onBack: () -> Unit,
    viewModel: FiltersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val project = state.project

    Scaffold(containerColor = ProCutBackground) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ProCutTopBar(
                title = "Filters & adjust",
                subtitle = state.selectedClip?.label,
                onBack = onBack,
            )
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ProCutPrimary)
                }

                project == null || state.selectedClip == null -> Text(
                    text = state.error ?: "Add a clip to the timeline first.",
                    color = ProCutOnSurfaceMuted,
                    modifier = Modifier.padding(24.dp),
                )

                else -> {
                    val clip = state.selectedClip
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp),
                    ) {
                        FilterPreview(clip = clip, effect = clip.effects, viewModel = viewModel)

                        if (project.videoClips.size > 1) {
                            SectionTitle("Clip", modifier = Modifier.padding(top = 12.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(project.videoClips, key = { it.id }) { item ->
                                    ProCutChip(
                                        label = item.label.ifBlank { "Clip" }.take(14),
                                        selected = item.id == clip.id,
                                        onClick = { viewModel.selectClip(item.id) },
                                    )
                                }
                            }
                        }

                        SectionTitle("Presets", modifier = Modifier.padding(top = 14.dp))
                        FilterPreset.entries.chunked(4).forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 8.dp),
                            ) {
                                row.forEach { preset ->
                                    ProCutChip(
                                        label = preset.label,
                                        selected = clip.effects.filter == preset,
                                        onClick = { viewModel.setFilter(preset) },
                                    )
                                }
                            }
                        }

                        SectionTitle("Manual adjustments", modifier = Modifier.padding(top = 6.dp))
                        ProCutSlider("Brightness", clip.effects.brightness, -100f..100f,
                            onValueChange = viewModel::setBrightness)
                        ProCutSlider("Contrast", clip.effects.contrast, -100f..100f,
                            onValueChange = viewModel::setContrast)
                        ProCutSlider("Saturation", clip.effects.saturation, -100f..100f,
                            onValueChange = viewModel::setSaturation)
                        ProCutSlider("Warmth", clip.effects.warmth, -100f..100f,
                            onValueChange = viewModel::setWarmth)
                        ProCutSlider("Hue", clip.effects.hueDegrees, -180f..180f,
                            onValueChange = viewModel::setHue)
                        ProCutSlider("Vignette", clip.effects.vignette * 100f, 0f..100f,
                            valueLabel = "${(clip.effects.vignette * 100).toInt()}%",
                            onValueChange = { viewModel.setVignette(it / 100f) })

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                            SecondaryButton(
                                text = "Reset",
                                onClick = viewModel::resetEffects,
                                modifier = Modifier.weight(1f),
                            )
                            SecondaryButton(
                                text = "Apply to all clips",
                                onClick = viewModel::applyToAllClips,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        PrimaryButton(
                            text = "Done",
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPreview(clip: TimelineClip, effect: VideoEffect, viewModel: FiltersViewModel) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, clip.id, clip.sourceStartUs) {
        value = viewModel.previewBitmap()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Filter preview",
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.colorMatrix(effect.toColorMatrix()),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = ProCutPrimary, modifier = Modifier.width(24.dp))
        }
    }
}

/**
 * CPU approximation of the GL grade used by the exporter: saturation through the luminance matrix,
 * contrast as scale+offset, brightness as offset and warmth as channel gains.
 */
internal fun VideoEffect.toColorMatrix(): ColorMatrix {
    val saturation = (1f + effectiveSaturation / 100f).coerceIn(0f, 2f)
    val contrast = (1f + effectiveContrast / 100f).coerceIn(0f, 2f)
    val brightness = effectiveBrightness / 100f * 255f
    val warmth = effectiveWarmth / 100f

    val luminanceR = 0.213f
    val luminanceG = 0.715f
    val luminanceB = 0.072f
    val inverseSaturation = 1f - saturation

    val redGain = (1f + warmth * 0.25f).coerceIn(0f, 2f)
    val blueGain = (1f - warmth * 0.25f).coerceIn(0f, 2f)

    val scale = contrast
    val translate = (1f - contrast) * 128f + brightness

    return ColorMatrix(
        floatArrayOf(
            (inverseSaturation * luminanceR + saturation) * scale * redGain, inverseSaturation * luminanceG * scale * redGain, inverseSaturation * luminanceB * scale * redGain, 0f, translate,
            inverseSaturation * luminanceR * scale, (inverseSaturation * luminanceG + saturation) * scale, inverseSaturation * luminanceB * scale, 0f, translate,
            inverseSaturation * luminanceR * scale * blueGain, inverseSaturation * luminanceG * scale * blueGain, (inverseSaturation * luminanceB + saturation) * scale * blueGain, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}
