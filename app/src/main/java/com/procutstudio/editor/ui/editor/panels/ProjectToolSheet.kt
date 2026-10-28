package com.procutstudio.editor.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.ui.components.PrimaryButton
import com.procutstudio.editor.ui.components.SectionTitle
import com.procutstudio.editor.ui.editor.EditorViewModel
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface
import com.procutstudio.editor.ui.theme.ProCutSurfaceHigh

private val canvasColors = listOf(
    0xFF000000L, 0xFFFFFFFFL, 0xFF111827L, 0xFF1F2937L,
    0xFF7C3AEDL, 0xFF0EA5E9L, 0xFFF97316L, 0xFF10B981L,
)

/** Project level settings: canvas shape, canvas colour, project name and stats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectToolSheet(
    project: EditorProject,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    viewModel: EditorViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(project.id) { mutableStateOf(project.name) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ProCutSurface,
        contentColor = ProCutOnSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Canvas & project", style = MaterialTheme.typography.titleMedium, color = ProCutOnSurface)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Project name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = "Apply name",
                onClick = { viewModel.renameProject(name) },
                enabled = name.isNotBlank() && name != project.name,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Aspect ratio", modifier = Modifier.padding(top = 18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectRatio.entries.forEach { ratio ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (project.aspectRatio == ratio) ProCutPrimary.copy(alpha = 0.16f) else ProCutSurface)
                            .border(
                                1.dp,
                                if (project.aspectRatio == ratio) ProCutPrimary else ProCutOutline,
                                RoundedCornerShape(14.dp),
                            )
                            .clickable { viewModel.setAspectRatio(ratio) }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .aspectRatio(ratio.ratio)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (project.aspectRatio == ratio) ProCutPrimary else ProCutSurfaceHigh),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            ratio.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (project.aspectRatio == ratio) ProCutOnSurface else ProCutOnSurfaceMuted,
                        )
                    }
                }
            }

            SectionTitle("Canvas colour", modifier = Modifier.padding(top = 18.dp))
            Text(
                "Shown behind letterboxed footage. The renderer draws it as a background layer.",
                style = MaterialTheme.typography.bodySmall,
                color = ProCutOnSurfaceMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                canvasColors.forEach { argb ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (project.canvasColorArgb == argb) 3.dp else 1.dp,
                                color = if (project.canvasColorArgb == argb) ProCutPrimary else ProCutOutline,
                                shape = CircleShape,
                            )
                            .clickable { viewModel.setCanvasColor(argb) },
                    )
                }
            }

            SectionTitle("Project", modifier = Modifier.padding(top = 18.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatBlock("Clips", "${project.videoClips.size}", Modifier.weight(1f))
                StatBlock("Text", "${project.textOverlays.size}", Modifier.weight(1f))
                StatBlock("Audio", "${project.audioClips.size}", Modifier.weight(1f))
                StatBlock("Length", TimeUtils.formatDurationLong(project.durationUs), Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(text = "Export video", onClick = onExport, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = ProCutOnSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = ProCutOnSurfaceMuted)
    }
}
