package com.procutstudio.editor.ui.export

import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.data.media.ThumbnailStore
import com.procutstudio.editor.data.repository.ProjectRepository
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.ExportResolution
import com.procutstudio.editor.domain.model.ExportSettings
import com.procutstudio.editor.domain.model.FrameRate
import com.procutstudio.editor.domain.model.OutputSpec
import com.procutstudio.editor.media.export.DeviceCapabilities
import com.procutstudio.editor.media.export.ExportFailure
import com.procutstudio.editor.media.export.ExportOutcome
import com.procutstudio.editor.media.export.ExportProgressInfo
import com.procutstudio.editor.media.export.ExportStage
import com.procutstudio.editor.media.export.VideoExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ExportStatus { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED }

data class ExportUiState(
    val project: EditorProject? = null,
    val settings: ExportSettings = ExportSettings(),
    val isLoading: Boolean = true,
    val status: ExportStatus = ExportStatus.IDLE,
    val stage: ExportStage = ExportStage.PREPARING,
    val progress: Float = 0f,
    val outcome: ExportOutcome? = null,
    val error: String? = null,
    val supportedResolutions: List<ExportResolution> = ExportResolution.entries,
    val warnings: List<String> = emptyList(),
    /** Poster frame of the first clip, so the export screen can show what is about to be rendered. */
    val previewBitmap: Bitmap? = null,
) {
    val outputSpec: OutputSpec? get() = project?.let { settings.toOutputSpec(it.aspectRatio) }

    val estimatedBytes: Long
        get() {
            val spec = outputSpec ?: return 0L
            val seconds = (project?.durationUs ?: 0L) / 1_000_000.0
            return (((spec.videoBitrate + if (spec.includeAudio) spec.audioBitrate else 0) / 8.0) * seconds * 1.15).toLong()
        }
}

/**
 * Drives the three export screens from a single state machine. The export job lives in the
 * ViewModel so navigating between settings → progress → success never restarts or duplicates a
 * render, and cancelling is always possible.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val videoExporter: VideoExporter,
    private val deviceCapabilities: DeviceCapabilities,
    private val thumbnailStore: ThumbnailStore,
    savedStateHandle: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val projectId: String = savedStateHandle.get<String>("projectId").orEmpty()

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val project = withContext(ioDispatcher) { repository.getProject(projectId) }
            val supported = if (project != null) {
                deviceCapabilities.supportedResolutions(project.aspectRatio, project.exportSettings.frameRate)
            } else {
                listOf(ExportResolution.HD_720)
            }
            val clampedSettings = project?.exportSettings?.copy(
                resolution = if (project.exportSettings.resolution in supported) {
                    project.exportSettings.resolution
                } else {
                    supported.last()
                },
            ) ?: ExportSettings()
            val preview = if (project != null) {
                val asset = project.videoClips.firstNotNullOfOrNull { project.asset(it.assetId) }
                if (asset != null) {
                    withContext(ioDispatcher) { runCatching { thumbnailStore.posterFrame(asset, 720) }.getOrNull() }
                } else {
                    null
                }
            } else {
                null
            }
            _uiState.value = _uiState.value.copy(
                project = project,
                settings = clampedSettings,
                isLoading = false,
                supportedResolutions = supported,
                previewBitmap = preview,
                error = if (project == null) "This project could not be opened." else null,
            )
            validateSettings()
        }
    }

    fun setResolution(resolution: ExportResolution) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(resolution = resolution),
        )
        validateSettings()
    }

    fun setFrameRate(frameRate: FrameRate) {
        val project = _uiState.value.project ?: return
        val supported = deviceCapabilities.supportedResolutions(project.aspectRatio, frameRate)
        val resolution = _uiState.value.settings.resolution.takeIf { it in supported } ?: supported.last()
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(frameRate = frameRate, resolution = resolution),
            supportedResolutions = supported,
        )
        validateSettings()
    }

    fun setBitrateMbps(mbps: Float) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(videoBitrate = (mbps * 1_000_000).toInt()),
        )
    }

    fun setAutoBitrate() {
        _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(videoBitrate = 0))
    }

    fun setIncludeAudio(include: Boolean) {
        _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(includeAudio = include))
    }

    private fun validateSettings() {
        val state = _uiState.value
        val project = state.project ?: return
        val spec = state.settings.toOutputSpec(project.aspectRatio)
        val warnings = mutableListOf<String>()
        if (!deviceCapabilities.supportsResolution(spec.width, spec.height, spec.fps)) {
            warnings += "This device reports the selected size as unsupported; the encoder may fall back."
        }
        warnings += "Video clips keep their source frame rate. Photos use ${spec.fps} fps."
        if (project.canvasColorArgb != 0xFF000000L) {
            warnings += "The canvas colour is added as a stretched background layer, so it fills the whole frame. " +
                "Clips stay at their own aspect ratio on top of it."
        }
        _uiState.value = state.copy(warnings = warnings)
    }

    fun startExport() {
        val project = _uiState.value.project ?: return
        if (_uiState.value.status == ExportStatus.RUNNING) return
        exportJob?.cancel()
        _uiState.value = _uiState.value.copy(
            status = ExportStatus.RUNNING,
            progress = 0f,
            stage = ExportStage.PREPARING,
            error = null,
            outcome = null,
        )
        exportJob = viewModelScope.launch {
            try {
                val outcome = videoExporter.export(project, _uiState.value.settings) { info ->
                    _uiState.value = _uiState.value.copy(
                        progress = info.fraction,
                        stage = info.stage,
                    )
                }
                _uiState.value = _uiState.value.copy(
                    status = ExportStatus.SUCCESS,
                    progress = 1f,
                    stage = ExportStage.DONE,
                    outcome = outcome,
                )
            } catch (cancelled: CancellationException) {
                _uiState.value = _uiState.value.copy(status = ExportStatus.CANCELLED)
                throw cancelled
            } catch (error: ExportFailure) {
                _uiState.value = _uiState.value.copy(
                    status = ExportStatus.FAILED,
                    error = error.message ?: "The export failed.",
                )
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    status = ExportStatus.FAILED,
                    error = error.message ?: "The export failed unexpectedly.",
                )
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _uiState.value = _uiState.value.copy(status = ExportStatus.CANCELLED, error = null)
    }

    /**
     * Surfaces a message on the export settings screen — used for permission refusals and other
     * situations that stop an export before it starts.
     */
    fun reportMessage(message: String) {
        _uiState.value = _uiState.value.copy(error = message, status = ExportStatus.IDLE)
    }

    fun resetToSettings() {
        _uiState.value = _uiState.value.copy(
            status = ExportStatus.IDLE,
            progress = 0f,
            outcome = null,
            error = null,
        )
    }

    /** Shares the finished file through the system sharesheet. */
    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share video"))
    }

    /** Persists the chosen settings on the project so the next export opens with them. */
    fun rememberSettings() {
        val project = _uiState.value.project ?: return
        val updated = project.copy(exportSettings = _uiState.value.settings)
        viewModelScope.launch {
            withContext(ioDispatcher) { repository.saveProject(updated.touch()) }
        }
    }
}
