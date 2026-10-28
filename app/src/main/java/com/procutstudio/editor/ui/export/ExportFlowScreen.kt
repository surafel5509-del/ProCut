package com.procutstudio.editor.ui.export

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel

/** Steps inside the export destination. */
private enum class ExportStep { SETTINGS, PROGRESS, SUCCESS }

/**
 * Hosts the three export screens inside one navigation destination.
 *
 * The ViewModel (and therefore the render job) is owned by this destination, so moving between
 * settings, progress and the result never restarts an encode and a half-finished job can always be
 * cancelled. A configuration change keeps the current step because the step is derived from the
 * render state.
 */
@Composable
fun ExportFlowScreen(
    onClose: () -> Unit,
    onOpenEditor: (String) -> Unit,
    onBackToProjects: () -> Unit,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var step by remember { mutableStateOf(ExportStep.SETTINGS) }

    // An export may already be running (screen recreated mid-render, or the user came back).
    LaunchedEffect(state.status) {
        step = when (state.status) {
            ExportStatus.RUNNING -> ExportStep.PROGRESS
            ExportStatus.SUCCESS -> ExportStep.SUCCESS
            else -> step
        }
    }

    BackHandler(enabled = step != ExportStep.SETTINGS) {
        if (state.status == ExportStatus.RUNNING) {
            viewModel.cancelExport()
        }
        viewModel.resetToSettings()
        step = ExportStep.SETTINGS
    }

    AnimatedContent(
        targetState = step,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "export-step",
    ) { current ->
        when (current) {
            ExportStep.SETTINGS -> ExportSettingsScreen(
                viewModel = viewModel,
                onBack = onClose,
                onStartExport = { step = ExportStep.PROGRESS },
            )

            ExportStep.PROGRESS -> ExportProgressScreen(
                viewModel = viewModel,
                onFinished = { step = ExportStep.SUCCESS },
                onCancel = {
                    viewModel.resetToSettings()
                    step = ExportStep.SETTINGS
                },
            )

            ExportStep.SUCCESS -> ExportSuccessScreen(
                viewModel = viewModel,
                onDone = onBackToProjects,
                onExportAgain = {
                    viewModel.resetToSettings()
                    step = ExportStep.SETTINGS
                },
                onOpenEditor = { onOpenEditor(viewModel.projectId) },
            )
        }
    }
}
