package com.procutstudio.editor.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.data.repository.ProjectSummary
import com.procutstudio.editor.ui.components.ConfirmDialog
import com.procutstudio.editor.ui.components.EmptyState
import com.procutstudio.editor.ui.components.TextPromptDialog
import com.procutstudio.editor.ui.theme.ProCutBackground
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated
import java.io.File

/**
 * Home screen: brand header, a prominent "new project" entry point and the recent project library
 * with rename / duplicate / delete actions.
 */
@Composable
fun HomeScreen(
    onNewProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = ProCutBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        ) {
            item { HomeHeader(onOpenSettings = onOpenSettings) }
            item {
                Spacer(Modifier.height(18.dp))
                NewProjectCard(onClick = onNewProject)
                Spacer(Modifier.height(26.dp))
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Recent projects",
                        style = MaterialTheme.typography.titleMedium,
                        color = ProCutOnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.projects.isNotEmpty()) {
                        Text(
                            text = "${state.projects.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ProCutOnSurfaceMuted,
                        )
                    }
                }
            }
            if (state.projects.isEmpty() && !state.isLoading) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Movie,
                        title = "No projects yet",
                        message = "Start with a video or a photo, then trim, add music and export in minutes.",
                        actionLabel = "Create your first project",
                        onAction = onNewProject,
                    )
                }
            }
            items(items = state.projects, key = { it.id }) { project ->
                ProjectRow(
                    project = project,
                    onOpen = { onOpenProject(project.id) },
                    onRename = { renameTarget = project },
                    onDuplicate = { viewModel.duplicate(project.id) },
                    onDelete = { deleteTarget = project },
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    renameTarget?.let { target ->
        TextPromptDialog(
            title = "Rename project",
            initialValue = target.name,
            confirmLabel = "Save",
            label = "Project name",
            onConfirm = { newName ->
                viewModel.rename(target.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete project?",
            message = "“${target.name}” and its imported media copies will be removed from this device. Exported videos stay in your gallery.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(ProCutPrimary, ProCutSurfaceElevated))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Movie, contentDescription = null, tint = ProCutOnSurface, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("ProCut Studio", style = MaterialTheme.typography.titleLarge, color = ProCutOnSurface)
            Text(
                "Mobile video editor",
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurfaceMuted,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = ProCutOnSurfaceMuted)
        }
    }
}

@Composable
private fun NewProjectCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(ProCutPrimary.copy(alpha = 0.9f), ProCutPrimary.copy(alpha = 0.35f), ProCutSurfaceElevated),
                ),
            )
            .border(1.dp, ProCutPrimary.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ProCutOnSurface.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = ProCutOnSurface)
                }
                Spacer(Modifier.width(12.dp))
                Text("New project", style = MaterialTheme.typography.titleMedium, color = ProCutOnSurface)
            }
            Text(
                "Import video or photos · 16:9, 9:16, 1:1, 4:5",
                style = MaterialTheme.typography.bodySmall,
                color = ProCutOnSurface.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ProCutSurface)
            .border(1.dp, ProCutOutline.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ProCutSurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            val thumbnail = project.thumbnailPath
            if (thumbnail != null) {
                AsyncImage(
                    model = File(thumbnail),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Outlined.Movie, contentDescription = null, tint = ProCutOnSurfaceMuted)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.titleSmall,
                color = ProCutOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${TimeUtils.formatDurationLong(project.durationUs)} · ${project.clipCount} clips · ${project.aspectRatio.shortLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurfaceMuted,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Project actions", tint = ProCutOnSurfaceMuted)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = { menuOpen = false; onDuplicate() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}
