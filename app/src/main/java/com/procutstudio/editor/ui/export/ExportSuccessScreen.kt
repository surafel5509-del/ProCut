package com.procutstudio.editor.ui.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.ui.components.PrimaryButton
import com.procutstudio.editor.ui.components.SecondaryButton
import com.procutstudio.editor.ui.theme.ProCutBackground
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutSuccess
import com.procutstudio.editor.ui.theme.ProCutSurface

/**
 * Success screen: shows the verified result, offers playback of the published file, the system
 * sharesheet and a path back to the editor. The file already exists in the gallery at this point,
 * so leaving this screen never loses the export.
 */
@Composable
fun ExportSuccessScreen(
    viewModel: ExportViewModel,
    onDone: () -> Unit,
    onExportAgain: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val outcome = state.outcome

    Scaffold(containerColor = ProCutBackground) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(ProCutSuccess.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = ProCutSuccess,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = "Export complete",
                style = MaterialTheme.typography.headlineSmall,
                color = ProCutOnSurface,
            )

            if (outcome == null) {
                Text(
                    text = "There is no finished export to show. Start one from the export settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProCutOnSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Text(
                    text = "Saved to your gallery as ${outcome.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProCutOnSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(ProCutSurface)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ResultRow("Resolution", "${outcome.width} × ${outcome.height}")
                    ResultRow("Duration", TimeUtils.formatClock(outcome.durationUs))
                    ResultRow("File size", formatBytes(outcome.sizeBytes))
                    ResultRow("Format", "MP4 · H.264 + AAC")
                }

                outcome.warnings.forEach { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = ProCutOnSurfaceMuted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                Spacer(Modifier.height(28.dp))
                PrimaryButton(
                    text = "Share",
                    icon = Icons.Rounded.Share,
                    onClick = { shareVideo(context, outcome.uri) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(
                    text = "Play",
                    icon = Icons.Rounded.PlayArrow,
                    onClick = { playVideo(context, outcome.uri) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        text = "Open in editor",
                        icon = Icons.Rounded.OpenInNew,
                        onClick = onOpenEditor,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Export again",
                        onClick = {
                            viewModel.resetToSettings()
                            onExportAgain()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            SecondaryButton(
                text = "Back to projects",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = ProCutOnSurface,
        )
    }
}

private fun shareVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { error ->
        if (error !is ActivityNotFoundException) throw error
    }
}

private fun playVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Play video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
