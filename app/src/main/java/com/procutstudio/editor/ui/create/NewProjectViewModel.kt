package com.procutstudio.editor.ui.create

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.data.media.ImportResult
import com.procutstudio.editor.data.media.MediaImporter
import com.procutstudio.editor.data.repository.ProjectRepository
import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.timeline.TimelineEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class NewProjectUiState(
    val name: String = defaultProjectName(),
    val aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    val assets: List<MediaAsset> = emptyList(),
    val isImporting: Boolean = false,
    val importStatus: String? = null,
    val isCreating: Boolean = false,
    val error: String? = null,
)

internal fun defaultProjectName(): String {
    val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
    return "Project $stamp"
}

/**
 * Drives the new-project flow: collect media, import it into app storage, then create and seed the
 * project document. Import failures are surfaced per file, and the project can still be created with
 * whatever imported successfully.
 */
@HiltViewModel
class NewProjectViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val mediaImporter: MediaImporter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewProjectUiState())
    val uiState: StateFlow<NewProjectUiState> = _uiState.asStateFlow()

    fun setName(name: String) {
        _uiState.value = _uiState.value.copy(name = name.take(60))
    }

    fun setAspectRatio(aspectRatio: AspectRatio) {
        _uiState.value = _uiState.value.copy(aspectRatio = aspectRatio)
    }

    fun removeAsset(assetId: String) {
        _uiState.value = _uiState.value.copy(assets = _uiState.value.assets.filterNot { it.id == assetId })
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, error = null)
            val imported = mutableListOf<MediaAsset>()
            val failures = mutableListOf<String>()
            uris.forEachIndexed { index, uri ->
                _uiState.value = _uiState.value.copy(
                    importStatus = "Importing ${index + 1} of ${uris.size}…",
                )
                when (val result = mediaImporter.import(uri)) {
                    is ImportResult.Success -> imported += result.asset
                    is ImportResult.Failure -> failures += result.message
                }
            }
            _uiState.value = _uiState.value.copy(
                isImporting = false,
                importStatus = null,
                assets = _uiState.value.assets + imported,
                error = failures.firstOrNull(),
            )
        }
    }

    /** Creates the project document, drops the imported assets on the video lane and saves it. */
    fun createProject(onCreated: (String) -> Unit) {
        val state = _uiState.value
        if (state.assets.isEmpty() || state.isCreating) return
        viewModelScope.launch {
            _uiState.value = state.copy(isCreating = true, error = null)
            runCatching {
                withContext(ioDispatcher) {
                    val project = repository.createProject(
                        name = state.name.ifBlank { defaultProjectName() },
                        aspectRatio = state.aspectRatio,
                        assets = state.assets,
                    )
                    val seeded = TimelineEditor.appendAssets(project, state.assets).touch()
                    repository.saveProject(seeded)
                    seeded.id
                }
            }.onSuccess { projectId ->
                _uiState.value = _uiState.value.copy(isCreating = false)
                onCreated(projectId)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    error = error.message ?: "Could not create the project.",
                )
            }
        }
    }
}
