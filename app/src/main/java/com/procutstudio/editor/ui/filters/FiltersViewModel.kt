package com.procutstudio.editor.ui.filters

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.data.media.ThumbnailStore
import com.procutstudio.editor.data.repository.ProjectRepository
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.FilterPreset
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.domain.model.VideoEffect
import com.procutstudio.editor.domain.timeline.TimelineEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class FiltersUiState(
    val project: EditorProject? = null,
    val selectedClipId: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val selectedClip: TimelineClip? get() = project?.videoClips?.firstOrNull { it.id == selectedClipId }
}

/**
 * Filters & adjustments. Changes are applied to the project immediately (so the editor picks them
 * up) but persisted with a short debounce so dragging a slider does not hammer the database.
 */
@HiltViewModel
class FiltersViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val thumbnailStore: ThumbnailStore,
    savedStateHandle: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val projectId: String = savedStateHandle.get<String>("projectId").orEmpty()
    private val requestedClipId: String? = savedStateHandle.get<String>("clipId")

    private val _uiState = MutableStateFlow(FiltersUiState())
    val uiState: StateFlow<FiltersUiState> = _uiState.asStateFlow()

    private var saveJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val project = withContext(ioDispatcher) { repository.getProject(projectId) }
            _uiState.value = FiltersUiState(
                project = project,
                selectedClipId = requestedClipId?.takeIf { id -> project?.videoClips?.any { it.id == id } == true }
                    ?: project?.videoClips?.firstOrNull()?.id,
                isLoading = false,
                error = if (project == null) "This project could not be opened." else null,
            )
        }
    }

    fun selectClip(clipId: String) {
        _uiState.value = _uiState.value.copy(selectedClipId = clipId)
    }

    fun setFilter(preset: FilterPreset) = updateSelected { clip, project ->
        TimelineEditor.setEffects(project, clip.id, clip.effects.copy(filter = preset))
    }

    fun setBrightness(value: Float) = updateSelected { clip, project ->
        TimelineEditor.setEffects(project, clip.id, clip.effects.copy(brightness = value))
    }

    fun setContrast(value: Float) = updateSelected { clip, project ->
        TimelineEditor.setEffects(project, clip.id, clip.effects.copy(contrast = value))
    }

    fun setSaturation(value: Float) = updateSelected { clip, project ->
        TimelineEditor.setEffects(project, clip.id, clip.effects.copy(saturation = value))
    }

    fun setWarmth(value: Float) = updateSelected { clip, project ->
        TimelineEditor.setEffects(project, clip.id, clip.effects.copy(warmth = value))
    }

    fun setHue(value: Float) = updateSelected { clip, project ->
        TimelineEditor.setEffects(project, clip.id, clip.effects.copy(hueDegrees = value))
    }

    fun setVignette(value: Float) = updateSelected { clip, project ->
        TimelineEditor.setEffects(project, clip.id, clip.effects.copy(vignette = value))
    }

    fun resetEffects() = updateSelected { clip, project ->
        TimelineEditor.setEffects(project, clip.id, VideoEffect())
    }

    /** Copies the current grade to every clip — a common workflow after matching a look. */
    fun applyToAllClips() {
        val project = _uiState.value.project ?: return
        val effects = _uiState.value.selectedClip?.effects ?: return
        var updated = project
        project.videoClips.forEach { clip ->
            updated = TimelineEditor.setEffects(updated, clip.id, effects)
        }
        _uiState.value = _uiState.value.copy(project = updated)
        persist(updated)
    }

    suspend fun previewBitmap(widthPx: Int = 720): Bitmap? {
        val project = _uiState.value.project ?: return null
        val clip = _uiState.value.selectedClip ?: return null
        val asset = project.asset(clip.assetId) ?: return null
        return withContext(ioDispatcher) {
            thumbnailStore.thumbnail(asset, clip.sourceStartUs + (clip.durationUs / 2), widthPx)
        }
    }

    private fun updateSelected(change: (TimelineClip, EditorProject) -> EditorProject) {
        val project = _uiState.value.project ?: return
        val clip = _uiState.value.selectedClip ?: return
        val updated = change(clip, project)
        _uiState.value = _uiState.value.copy(project = updated)
        persist(updated)
    }

    private fun persist(project: EditorProject) {
        saveJob?.cancel()
        _uiState.value = _uiState.value.copy(isSaving = true)
        saveJob = viewModelScope.launch {
            delay(400)
            withContext(ioDispatcher) { repository.saveProject(project.touch()) }
            _uiState.value = _uiState.value.copy(isSaving = false)
        }
    }
}
