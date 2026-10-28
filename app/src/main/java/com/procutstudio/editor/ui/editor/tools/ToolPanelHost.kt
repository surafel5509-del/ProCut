package com.procutstudio.editor.ui.editor.tools

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.procutstudio.editor.ui.editor.EditorUiState
import com.procutstudio.editor.ui.editor.EditorViewModel
import com.procutstudio.editor.ui.editor.ToolPanel

/** Fixed height of each bottom panel (the timeline keeps its own fixed height above it). */
fun ToolPanel.panelHeight(): Dp = when (this) {
    ToolPanel.NONE -> 0.dp
    ToolPanel.FILTERS, ToolPanel.ADJUST -> 262.dp
    ToolPanel.EFFECTS -> 244.dp
    ToolPanel.STICKERS -> 340.dp
    ToolPanel.KEYFRAME -> 290.dp
    ToolPanel.MASK -> 290.dp
    ToolPanel.CHROMA -> 272.dp
    ToolPanel.ANIMATION -> 212.dp
    ToolPanel.OPACITY -> 150.dp
}

/** Shows the bottom panel selected by [EditorUiState.toolPanel]. */
@Composable
fun ToolPanelHost(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onImportSticker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelModifier = modifier.height(state.toolPanel.panelHeight())
    val close: () -> Unit = { viewModel.closeTool() }
    when (state.toolPanel) {
        ToolPanel.NONE -> Unit
        ToolPanel.FILTERS -> FiltersPanel(state, viewModel, close, panelModifier, initialTab = 0)
        ToolPanel.ADJUST -> FiltersPanel(state, viewModel, close, panelModifier, initialTab = 1)
        ToolPanel.EFFECTS -> EffectsPanel(state, viewModel, close, panelModifier)
        ToolPanel.STICKERS -> StickersPanel(viewModel, onImportSticker, close, panelModifier)
        ToolPanel.KEYFRAME -> KeyframePanel(state, viewModel, close, panelModifier)
        ToolPanel.MASK -> MaskPanel(state, viewModel, close, panelModifier)
        ToolPanel.CHROMA -> ChromaPanel(state, viewModel, close, panelModifier)
        ToolPanel.ANIMATION -> AnimationPanel(state, viewModel, close, panelModifier)
        ToolPanel.OPACITY -> OpacityPanel(state, viewModel, close, panelModifier)
    }
}
