package com.procutstudio.editor.ui.text

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.FontOption
import com.procutstudio.editor.domain.model.TextAlignOption
import com.procutstudio.editor.domain.model.TextAnimationType
import com.procutstudio.editor.domain.model.TextPalettes
import com.procutstudio.editor.domain.model.TextPreset
import com.procutstudio.editor.domain.model.TextPresets
import com.procutstudio.editor.domain.model.TextStyleSpec
import com.procutstudio.editor.ui.components.ConfirmDialog
import com.procutstudio.editor.ui.components.PrimaryButton
import com.procutstudio.editor.ui.components.ProCutChip
import com.procutstudio.editor.ui.components.ProCutSlider
import com.procutstudio.editor.ui.components.ProCutTopBar
import com.procutstudio.editor.ui.components.SectionTitle
import com.procutstudio.editor.ui.theme.ProCutBackground
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Text & overlay editor: live WYSIWYG preview (rendered by the same rasteriser the exporter uses),
 * typography, colour, outline, shadow, alignment, position, animation and timing.
 */
@Composable
fun TextEditorScreen(
    onBack: () -> Unit,
    viewModel: TextEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = ProCutBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val draft = state.draft
            ProCutTopBar(
                title = if (state.isNew) "Add text" else "Edit text",
                subtitle = draft?.let { "${TimeUtils.formatClock(it.startUs)} · ${TimeUtils.formatClock(it.durationUs)}" },
                onBack = onBack,
                actions = {
                    if (!state.isNew) {
                        IconAction(
                            icon = Icons.Outlined.Delete,
                            label = "Delete",
                            onClick = { showDeleteConfirm = true },
                        )
                    }
                    TextButton(onClick = { viewModel.save(onBack) }, enabled = draft != null) {
                        Text("Done", color = ProCutPrimary)
                    }
                },
            )

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ProCutPrimary)
                }

                draft == null -> Text(
                    text = state.error ?: "This text overlay no longer exists.",
                    color = ProCutOnSurfaceMuted,
                    modifier = Modifier.padding(24.dp),
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp),
                ) {
                    TextPreview(draft = draft, viewModel = viewModel)

                    OutlinedTextField(
                        value = draft.text,
                        onValueChange = viewModel::updateText,
                        label = { Text("Text") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        shape = RoundedCornerShape(14.dp),
                        minLines = 2,
                    )

                    SectionTitle("Templates", modifier = Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextPreset.entries.take(3).forEach { preset ->
                            ProCutChip(
                                label = preset.label,
                                selected = false,
                                onClick = {
                                    viewModel.updateStyle { TextPresets.styleFor(preset) }
                                    viewModel.updateAnimation { TextPresets.animationFor(preset) }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextPreset.entries.drop(3).forEach { preset ->
                            ProCutChip(
                                label = preset.label,
                                selected = false,
                                onClick = {
                                    viewModel.updateStyle { TextPresets.styleFor(preset) }
                                    viewModel.updateAnimation { TextPresets.animationFor(preset) }
                                },
                            )
                        }
                    }

                    SectionTitle("Font", modifier = Modifier.padding(top = 14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FontOption.entries.forEach { font ->
                            ProCutChip(
                                label = font.label,
                                selected = draft.style.font == font,
                                onClick = { viewModel.updateStyle { it.copy(font = font) } },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProCutChip("Bold", draft.style.bold, { viewModel.updateStyle { it.copy(bold = !it.bold) } })
                        ProCutChip("Italic", draft.style.italic, { viewModel.updateStyle { it.copy(italic = !it.italic) } })
                        ProCutChip("Underline", draft.style.underline, { viewModel.updateStyle { it.copy(underline = !it.underline) } })
                        ProCutChip("CAPS", draft.style.uppercase, { viewModel.updateStyle { it.copy(uppercase = !it.uppercase) } })
                    }

                    ProCutSlider(
                        label = "Size",
                        value = draft.style.sizeSp,
                        range = 12f..120f,
                        valueLabel = "${draft.style.sizeSp.toInt()}",
                        onValueChange = { value -> viewModel.updateStyle { it.copy(sizeSp = value) } },
                    )

                    SectionTitle("Colour")
                    ColorRow(
                        colors = TextPalettes.colors,
                        selected = draft.style.colorArgb,
                        onSelect = { argb -> viewModel.updateStyle { it.copy(colorArgb = argb) } },
                    )

                    SectionTitle("Background")
                    ColorRow(
                        colors = TextPalettes.backgrounds,
                        selected = draft.style.backgroundColorArgb,
                        onSelect = { argb -> viewModel.updateStyle { it.copy(backgroundColorArgb = argb) } },
                    )
                    ProCutSlider(
                        label = "Background opacity",
                        value = draft.style.backgroundAlpha * 100f,
                        range = 0f..100f,
                        valueLabel = "${(draft.style.backgroundAlpha * 100).toInt()}%",
                        onValueChange = { value -> viewModel.updateStyle { it.copy(backgroundAlpha = value / 100f) } },
                    )
                    ProCutSlider(
                        label = "Outline",
                        value = draft.style.strokeWidth,
                        range = 0f..12f,
                        valueLabel = "%.1f".format(draft.style.strokeWidth),
                        onValueChange = { value -> viewModel.updateStyle { it.copy(strokeWidth = value) } },
                    )
                    ProCutChip(
                        label = "Drop shadow",
                        selected = draft.style.shadowEnabled,
                        onClick = { viewModel.updateStyle { it.copy(shadowEnabled = !it.shadowEnabled) } },
                    )

                    SectionTitle("Alignment", modifier = Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextAlignOption.entries.forEach { align ->
                            ProCutChip(
                                label = align.label,
                                selected = draft.style.align == align,
                                onClick = { viewModel.updateStyle { it.copy(align = align) } },
                            )
                        }
                    }

                    SectionTitle("Position", modifier = Modifier.padding(top = 12.dp))
                    ProCutSlider(
                        label = "Horizontal",
                        value = draft.style.positionX * 100f,
                        range = 0f..100f,
                        valueLabel = "${(draft.style.positionX * 100).toInt()}%",
                        onValueChange = { value -> viewModel.updateStyle { it.copy(positionX = value / 100f) } },
                    )
                    ProCutSlider(
                        label = "Vertical",
                        value = draft.style.positionY * 100f,
                        range = 0f..100f,
                        valueLabel = "${(draft.style.positionY * 100).toInt()}%",
                        onValueChange = { value -> viewModel.updateStyle { it.copy(positionY = value / 100f) } },
                    )
                    ProCutSlider(
                        label = "Scale",
                        value = draft.style.scale,
                        range = 0.4f..2f,
                        valueLabel = "${(draft.style.scale * 100).toInt()}%",
                        onValueChange = { value -> viewModel.updateStyle { it.copy(scale = value) } },
                    )

                    SectionTitle("Animation", modifier = Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextAnimationType.entries.take(4).forEach { animation ->
                            ProCutChip(
                                label = animation.label,
                                selected = draft.animation.type == animation,
                                onClick = { viewModel.updateAnimation { it.copy(type = animation) } },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextAnimationType.entries.drop(4).forEach { animation ->
                            ProCutChip(
                                label = animation.label,
                                selected = draft.animation.type == animation,
                                onClick = { viewModel.updateAnimation { it.copy(type = animation) } },
                            )
                        }
                    }

                    SectionTitle("Timing", modifier = Modifier.padding(top = 12.dp))
                    val maxStart = (state.project?.durationUs ?: draft.durationUs).coerceAtLeast(draft.durationUs)
                    ProCutSlider(
                        label = "Starts at",
                        value = draft.startUs / 1_000_000f,
                        range = 0f..(maxStart / 1_000_000f).coerceAtLeast(1f),
                        valueLabel = TimeUtils.formatClock(draft.startUs),
                        onValueChange = { value -> viewModel.updateTiming((value * 1_000_000).toLong(), draft.durationUs) },
                    )
                    ProCutSlider(
                        label = "Duration",
                        value = draft.durationUs / 1_000_000f,
                        range = 0.5f..20f,
                        valueLabel = TimeUtils.formatClock(draft.durationUs),
                        onValueChange = { value -> viewModel.updateTiming(draft.startUs, (value * 1_000_000).toLong()) },
                    )

                    PrimaryButton(
                        text = "Save text",
                        onClick = { viewModel.save(onBack) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete this text?",
            message = "The text overlay is removed from the timeline.",
            confirmLabel = "Delete",
            onConfirm = {
                showDeleteConfirm = false
                viewModel.delete(onBack)
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun TextPreview(draft: com.procutstudio.editor.domain.model.TextOverlay, viewModel: TextEditorViewModel) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, draft.text, draft.style) {
        value = viewModel.renderPreview()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Text preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1)),
            )
        } else {
            Text("Preview", style = MaterialTheme.typography.labelSmall, color = ProCutOnSurfaceMuted)
        }
    }
}

@Composable
private fun ColorRow(colors: List<Long>, selected: Long, onSelect: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { argb ->
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (selected == argb) 3.dp else 1.dp,
                        color = if (selected == argb) ProCutPrimary else ProCutOutline,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(argb) },
            )
        }
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = ProCutOnSurfaceMuted)
    }
}
