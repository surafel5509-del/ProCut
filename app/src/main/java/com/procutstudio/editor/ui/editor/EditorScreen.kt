package com.procutstudio.editor.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.procutstudio.editor.domain.model.MediaType
import com.procutstudio.editor.domain.model.TextOverlay
import com.procutstudio.editor.ui.components.ConfirmDialog
import com.procutstudio.editor.ui.components.EmptyState
import com.procutstudio.editor.ui.components.TextPromptDialog
import com.procutstudio.editor.ui.editor.panels.ClipToolSheet
import com.procutstudio.editor.ui.editor.panels.ClipToolTab
import com.procutstudio.editor.ui.editor.panels.ProjectToolSheet
import com.procutstudio.editor.ui.theme.ProCutBackground
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated

/**
 * The editing workspace: preview, transport, multi-track timeline, tool bar and contextual sheets.
 */
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onOpenTextEditor: (String?) -> Unit,
    onOpenAudioMixer: () -> Unit,
    onExport: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val rootView = LocalView.current

    // "Keep screen on" is a real window flag while the editor is visible.
    LaunchedEffect(appSettings.keepScreenOnWhileEditing) {
        rootView.keepScreenOn = appSettings.keepScreenOnWhileEditing
    }
    DisposableEffect(rootView) {
        onDispose { rootView.keepScreenOn = false }
    }

    // Haptics confirm an edit landed; only when the user left it enabled in Settings.
    val tick: () -> Unit = {
        if (appSettings.hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    var clipSheetTab by remember { mutableStateOf<ClipToolTab?>(null) }
    var showProjectSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val addMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris -> viewModel.addMedia(uris) }

    val stickerPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.importSticker(it) } }

    val replacePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.replaceSelectedClip(it) } }

    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importSubtitles(it) } }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Returning from the text / audio / filters screens must pick up their edits.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshFromRepository()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val project = state.project

    Scaffold(
        containerColor = ProCutBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ProCutPrimary)
                }

                project == null -> EmptyState(
                    icon = Icons.Outlined.Movie,
                    title = "Project unavailable",
                    message = state.loadError ?: "This project could not be opened.",
                    actionLabel = "Go back",
                    onAction = onBack,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    EditorTopBar(
                        title = project.name,
                        subtitle = editorSubtitle(state),
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        isSaving = state.isSaving,
                        moreExpanded = showMoreMenu,
                        onMoreExpandedChange = { showMoreMenu = it },
                        onBack = {
                            viewModel.saveNow()
                            onBack()
                        },
                        onUndo = viewModel::undo,
                        onRedo = viewModel::redo,
                        onRename = { showRename = true },
                        onSave = viewModel::saveNow,
                        onCanvas = { showProjectSheet = true },
                        onImportSubtitles = {
                            subtitlePicker.launch(
                                arrayOf("application/x-subrip", "application/x-srt", "text/plain", "text/*"),
                            )
                        },
                        onExport = {
                            viewModel.saveNow()
                            onExport()
                        },
                    )

                    PreviewPane(
                        project = project,
                        playheadUs = state.playheadUs,
                        isPlaying = state.isPlaying,
                        player = viewModelPlayer(viewModel),
                        modifier = Modifier.weight(1f),
                        renderTextBitmap = viewModel::renderTextOverlayBitmap,
                        onTogglePlayback = viewModel::togglePlayback,
                        onStepFrame = viewModel::stepFrame,
                        isMuted = state.isPreviewMuted,
                        onToggleMute = viewModel::togglePreviewMute,
                        showSafeArea = appSettings.showSafeArea,
                    )

                    TimelineHeader(
                        pixelsPerSecond = state.pixelsPerSecond,
                        durationUs = project.durationUs,
                        onZoomIn = viewModel::zoomIn,
                        onZoomOut = viewModel::zoomOut,
                    )

                    TimelineView(
                        project = project,
                        playheadUs = state.playheadUs,
                        selectedItemId = state.selectedItemId,
                        pixelsPerSecond = state.pixelsPerSecond,
                        isPlaying = state.isPlaying,
                        onSeek = viewModel::seekTo,
                        onSelectItem = viewModel::selectItem,
                        onTrimDragStart = viewModel::onTrimDragStart,
                        onTrimBy = { _, isStart, deltaUs ->
                            if (isStart) viewModel.trimSelectedStartBy(deltaUs) else viewModel.trimSelectedEndBy(deltaUs)
                        },
                        onTrimDragEnd = { tick(); viewModel.onTrimDragEnd() },
                        onMoveClipToIndex = viewModel::moveClipToIndex,
                        onMoveItemTo = viewModel::moveItemTo,
                        onMoveDragEnd = { tick(); viewModel.onMoveDragEnd() },
                        loadThumbnails = viewModel::loadClipThumbnails,
                        loadWaveform = viewModel::loadWaveform,
                        onAddMedia = {
                            addMediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                        },
                        modifier = Modifier.height(260.dp),
                    )

                    EditorToolbar(
                        hasSelection = state.selectedItemId != null,
                        canSplit = state.canSplit,
                        onSplit = { tick(); viewModel.splitAtPlayhead() },
                        onDuplicate = { tick(); viewModel.duplicateSelected() },
                        onDelete = { tick(); showDeleteConfirm = true },
                        onAddMedia = {
                            addMediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                        },
                        onEditClip = { if (state.selectedItemId != null) clipSheetTab = ClipToolTab.TRIM },
                        onSpeed = { if (state.selectedItemId != null) clipSheetTab = ClipToolTab.SPEED },
                        onFilters = { if (state.selectedItemId != null) clipSheetTab = ClipToolTab.TRIM },
                        onText = {
                            val selectedText = state.selectedItem as? TextOverlay
                            if (selectedText != null) {
                                onOpenTextEditor(selectedText.id)
                            } else {
                                val newId = viewModel.addTextOverlay("Your text")
                                onOpenTextEditor(newId)
                            }
                        },
                        onAudio = onOpenAudioMixer,
                        onCanvas = { showProjectSheet = true },
                        onSticker = {
                            stickerPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            }

            if (state.isBusyMessage != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ProCutSurfaceElevated)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ProCutPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = state.isBusyMessage ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = ProCutOnSurface,
                        )
                    }
                }
            }
        }
    }

    val selectedClip = state.selectedClip
    if (clipSheetTab != null && selectedClip != null && project != null) {
        ClipToolSheet(
            project = project,
            clip = selectedClip,
            playheadUs = state.playheadUs,
            initialTab = clipSheetTab ?: ClipToolTab.TRIM,
            onDismiss = { clipSheetTab = null },
            viewModel = viewModel,
            onRequestReplace = {
                replacePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
        )
    }

    if (showProjectSheet && project != null) {
        ProjectToolSheet(
            project = project,
            onDismiss = { showProjectSheet = false },
            onExport = {
                showProjectSheet = false
                viewModel.saveNow()
                onExport()
            },
            viewModel = viewModel,
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete this item?",
            message = "The item is removed from the timeline. You can undo this with the undo button.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deleteSelected()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    if (showRename && project != null) {
        TextPromptDialog(
            title = "Rename project",
            initialValue = project.name,
            confirmLabel = "Save",
            label = "Project name",
            onConfirm = { name ->
                viewModel.renameProject(name)
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    subtitle: String,
    canUndo: Boolean,
    canRedo: Boolean,
    isSaving: Boolean,
    moreExpanded: Boolean,
    onMoreExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
    onCanvas: () -> Unit,
    onImportSubtitles: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ProCutBackground)
            .height(58.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = ProCutOnSurface,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ProCutOnSurface, maxLines = 1)
            Text(
                text = if (isSaving) "Saving…" else subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurfaceMuted,
                maxLines = 1,
            )
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                androidx.compose.material.icons.Icons.Outlined.Undo,
                contentDescription = "Undo",
                tint = if (canUndo) ProCutOnSurface else ProCutOnSurfaceMuted.copy(alpha = 0.35f),
            )
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(
                androidx.compose.material.icons.Icons.Outlined.Redo,
                contentDescription = "Redo",
                tint = if (canRedo) ProCutOnSurface else ProCutOnSurfaceMuted.copy(alpha = 0.35f),
            )
        }
        Box {
            IconButton(onClick = { onMoreExpandedChange(true) }) {
                Icon(androidx.compose.material.icons.Icons.Outlined.MoreVert, contentDescription = "More", tint = ProCutOnSurface)
            }
            DropdownMenu(expanded = moreExpanded, onDismissRequest = { onMoreExpandedChange(false) }) {
                DropdownMenuItem(text = { Text("Rename project") }, onClick = { onMoreExpandedChange(false); onRename() })
                DropdownMenuItem(text = { Text("Save now") }, onClick = { onMoreExpandedChange(false); onSave() })
                DropdownMenuItem(text = { Text("Canvas & aspect ratio") }, onClick = { onMoreExpandedChange(false); onCanvas() })
                DropdownMenuItem(
                    text = { Text("Import subtitles (.srt)") },
                    onClick = { onMoreExpandedChange(false); onImportSubtitles() },
                )
            }
        }
        Row(
            modifier = Modifier
                .padding(start = 4.dp, end = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(ProCutPrimary, Color(0xFF6366F1))))
                .clickable(onClick = onExport)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                androidx.compose.material.icons.Icons.Outlined.FileUpload,
                contentDescription = null,
                tint = ProCutOnSurface,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Export", style = MaterialTheme.typography.labelMedium, color = ProCutOnSurface)
        }
    }
}

@Composable
private fun TimelineHeader(
    pixelsPerSecond: Float,
    durationUs: Long,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Timeline",
            style = MaterialTheme.typography.labelMedium,
            color = ProCutOnSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onZoomOut, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Outlined.ZoomOut, contentDescription = "Zoom out", tint = ProCutOnSurfaceMuted, modifier = Modifier.size(18.dp))
        }
        Text(
            text = "${(pixelsPerSecond / 64f * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = ProCutOnSurfaceMuted,
            modifier = Modifier.width(44.dp),
        )
        IconButton(onClick = onZoomIn, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Outlined.ZoomIn, contentDescription = "Zoom in", tint = ProCutOnSurfaceMuted, modifier = Modifier.size(18.dp))
        }
    }
}

private fun editorSubtitle(state: EditorUiState): String {
    val project = state.project ?: return ""
    val clips = project.videoClips.size
    val overlayCount = project.textOverlays.size + project.imageOverlays.size
    return buildString {
        append(project.aspectRatio.shortLabel)
        append(" · ")
        append(if (clips == 1) "1 clip" else "$clips clips")
        if (overlayCount > 0) append(" · $overlayCount overlays")
        if (state.undoLabel != null) append(" · last: ${state.undoLabel}")
    }
}

/**
 * Bridges the ViewModel-owned player into the composable. The player is created eagerly by the
 * ViewModel, so this is a plain accessor that keeps the composable signature free of Media3 types.
 */
private fun viewModelPlayer(viewModel: EditorViewModel): androidx.media3.common.Player =
    viewModel.playerInstance()
