package com.procutstudio.editor.ui.export

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.procutstudio.editor.media.export.ExportStage
import com.procutstudio.editor.ui.components.ConfirmDialog
import com.procutstudio.editor.ui.components.PrimaryButton
import com.procutstudio.editor.ui.components.SecondaryButton
import com.procutstudio.editor.ui.theme.ProCutBackground
import com.procutstudio.editor.ui.theme.ProCutError
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSecondary
import com.procutstudio.editor.ui.theme.ProCutSurface
import com.procutstudio.editor.ui.theme.ProCutSuccess
import kotlin.math.roundToInt

/**
 * Progress screen. The render itself runs in [ExportViewModel]; this screen only observes it, so
 * rotating the device or backgrounding the app does not interrupt the encode. Back is intercepted
 * to offer cancellation instead of silently leaving a running export behind.
 */
@Composable
fun ExportProgressScreen(
    viewModel: ExportViewModel,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var showCancelDialog by remember { mutableStateOf(false) }
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        label = "export-progress",
    )

    LaunchedEffect(state.status) {
        if (state.status == ExportStatus.SUCCESS) onFinished()
    }

    BackHandler(enabled = state.status == ExportStatus.RUNNING) { showCancelDialog = true }

    Scaffold(containerColor = ProCutBackground) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (state.status) {
                ExportStatus.FAILED -> FailureContent(
                    message = state.error ?: "The export failed.",
                    onRetry = {
                        viewModel.resetToSettings()
                        viewModel.startExport()
                    },
                    onBack = {
                        viewModel.resetToSettings()
                        onCancel()
                    },
                )

                ExportStatus.CANCELLED -> FailureContent(
                    message = "Export cancelled. No partial file was kept.",
                    onRetry = {
                        viewModel.resetToSettings()
                        viewModel.startExport()
                    },
                    onBack = {
                        viewModel.resetToSettings()
                        onCancel()
                    },
                )

                else -> RunningContent(
                    stage = state.stage,
                    progress = animatedProgress,
                    percent = (animatedProgress * 100).roundToInt(),
                    projectName = state.project?.name ?: "Your video",
                    specLabel = state.outputSpec?.label ?: "—",
                    onCancel = { showCancelDialog = true },
                )
            }
        }
    }

    if (showCancelDialog) {
        ConfirmDialog(
            title = "Cancel export?",
            message = "The render is stopped and the temporary file is deleted. Nothing is saved to your gallery.",
            confirmLabel = "Cancel export",
            onConfirm = {
                showCancelDialog = false
                viewModel.cancelExport()
            },
            onDismiss = { showCancelDialog = false },
        )
    }
}

@Composable
private fun RunningContent(
    stage: ExportStage,
    progress: Float,
    percent: Int,
    projectName: String,
    specLabel: String,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(CircleShape)
            .background(ProCutSurface),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(112.dp),
            color = ProCutPrimary,
            trackColor = ProCutSurface,
            strokeWidth = 6.dp,
        )
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.headlineSmall,
            color = ProCutOnSurface,
        )
    }

    Spacer(Modifier.height(28.dp))
    Text(
        text = stage.label,
        style = MaterialTheme.typography.titleMedium,
        color = ProCutOnSurface,
        textAlign = TextAlign.Center,
    )
    Text(
        text = "$projectName · $specLabel",
        style = MaterialTheme.typography.bodySmall,
        color = ProCutOnSurfaceMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 6.dp),
    )

    Spacer(Modifier.height(28.dp))
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
        color = ProCutPrimary,
        trackColor = ProCutSurface,
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StagePill("Encode", active = stage == ExportStage.RENDERING || stage == ExportStage.PREPARING, modifier = Modifier.weight(1f))
        StagePill("Verify", active = stage == ExportStage.VERIFYING, modifier = Modifier.weight(1f))
        StagePill("Save", active = stage == ExportStage.SAVING || stage == ExportStage.DONE, modifier = Modifier.weight(1f))
    }

    Text(
        text = "Keep ProCut open until the export finishes. Rendering uses the device GPU and may warm up your phone.",
        style = MaterialTheme.typography.labelSmall,
        color = ProCutOnSurfaceMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 24.dp),
    )

    Spacer(Modifier.height(24.dp))
    SecondaryButton(
        text = "Cancel",
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StagePill(text: String, active: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) ProCutPrimary.copy(alpha = 0.18f) else ProCutSurface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (active) Icons.Rounded.Movie else Icons.Rounded.Check,
            contentDescription = null,
            tint = if (active) ProCutSecondary else ProCutOnSurfaceMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) ProCutOnSurface else ProCutOnSurfaceMuted,
        )
    }
}

@Composable
private fun FailureContent(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(ProCutError.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = null,
            tint = ProCutError,
            modifier = Modifier.size(36.dp),
        )
    }
    Spacer(Modifier.height(22.dp))
    Text(
        text = "Export failed",
        style = MaterialTheme.typography.titleLarge,
        color = ProCutOnSurface,
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = ProCutOnSurfaceMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 10.dp),
    )
    Text(
        text = "Tip: try 720p or a lower frame rate if this device cannot encode the selected size.",
        style = MaterialTheme.typography.labelSmall,
        color = ProCutSuccess.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 14.dp),
    )
    Spacer(Modifier.height(26.dp))
    PrimaryButton(text = "Try again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    SecondaryButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
}
