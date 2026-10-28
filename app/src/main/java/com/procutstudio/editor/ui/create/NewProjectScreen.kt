package com.procutstudio.editor.ui.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.ui.components.MessageBanner
import com.procutstudio.editor.ui.components.PrimaryButton
import com.procutstudio.editor.ui.components.ProCutChip
import com.procutstudio.editor.ui.components.ProCutTopBar
import com.procutstudio.editor.ui.components.SectionTitle
import com.procutstudio.editor.ui.components.SecondaryButton
import com.procutstudio.editor.ui.theme.ProCutBackground
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutOutline
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurface
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated
import java.io.File

/**
 * Media selection + project setup. Uses the Android Photo Picker for photos/videos and the Storage
 * Access Framework for anything the picker does not cover (audio, unusual containers). No storage
 * permission is requested: the pickers hand out per-file access and ProCut copies the bytes into
 * its own sandbox immediately.
 */
@Composable
fun NewProjectScreen(
    onBack: () -> Unit,
    onProjectReady: (String) -> Unit,
    viewModel: NewProjectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris -> viewModel.importUris(uris) }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importUris(uris) }

    Column(modifier = Modifier.fillMaxSize().background(ProCutBackground)) {
        ProCutTopBar(title = "New project", subtitle = "Choose media and canvas", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Project name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )

            SectionTitle("Aspect ratio", modifier = Modifier.padding(top = 18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectRatio.entries.forEach { ratio ->
                    AspectChip(
                        ratio = ratio,
                        selected = state.aspectRatio == ratio,
                        onClick = { viewModel.setAspectRatio(ratio) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SectionTitle("Media", modifier = Modifier.padding(top = 20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "Photos & videos",
                    icon = Icons.Outlined.AddPhotoAlternate,
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Browse",
                    icon = Icons.Outlined.FolderOpen,
                    onClick = { documentPicker.launch(arrayOf("video/*", "image/*", "audio/*")) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.isImporting) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = ProCutPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        state.importStatus ?: "Importing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = ProCutOnSurfaceMuted,
                    )
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                MessageBanner(message = message, isError = true)
            }

            if (state.assets.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(ProCutSurface)
                        .border(1.dp, ProCutOutline, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Movie,
                            contentDescription = null,
                            tint = ProCutOnSurfaceMuted,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Videos and photos you pick appear here",
                            style = MaterialTheme.typography.bodySmall,
                            color = ProCutOnSurfaceMuted,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items = state.assets, key = { it.id }) { asset ->
                        SelectedAssetCard(asset = asset, onRemove = { viewModel.removeAsset(asset.id) })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ProCutBackground)
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            PrimaryButton(
                text = if (state.assets.isEmpty()) "Select media to continue" else "Create project",
                onClick = { viewModel.createProject(onProjectReady) },
                enabled = state.assets.isNotEmpty() && !state.isImporting,
                loading = state.isCreating,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AspectChip(
    ratio: AspectRatio,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) ProCutPrimary.copy(alpha = 0.16f) else ProCutSurface)
            .border(1.dp, if (selected) ProCutPrimary else ProCutOutline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .aspectRatio(ratio.ratio)
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected) ProCutPrimary else ProCutSurfaceElevated)
                .border(1.dp, if (selected) ProCutPrimary else ProCutOutline, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            ratio.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) ProCutOnSurface else ProCutOnSurfaceMuted,
        )
    }
}

@Composable
private fun SelectedAssetCard(asset: MediaAsset, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .width(124.dp)
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ProCutSurfaceElevated),
    ) {
        AsyncImage(
            model = File(asset.uri.removePrefix("file://")),
            contentDescription = asset.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f)))),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(26.dp).padding(2.dp),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = ProCutOnSurface, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (asset.isImage) Icons.Outlined.Photo else Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = ProCutOnSurface,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (asset.isImage) "Photo" else TimeUtils.formatClock(asset.durationUs),
                    style = MaterialTheme.typography.labelSmall,
                    color = ProCutOnSurface,
                )
            }
            Text(
                text = asset.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = ProCutOnSurface.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
