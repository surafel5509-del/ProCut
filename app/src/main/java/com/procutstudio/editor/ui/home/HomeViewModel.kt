package com.procutstudio.editor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.procutstudio.editor.data.repository.ProjectRepository
import com.procutstudio.editor.data.repository.ProjectSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ProjectRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeProjects()
                .map { projects -> HomeUiState(projects = projects, isLoading = false) }
                .catch { error ->
                    emit(HomeUiState(isLoading = false, loadError = error.message ?: "Could not load projects"))
                }
                .collect { _uiState.value = it }
        }
    }

    fun rename(projectId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            runCatching { repository.renameProject(projectId, newName) }
                .onSuccess { messageChannel.send("Renamed to “${it?.name ?: newName}”") }
                .onFailure { messageChannel.send("Rename failed: ${it.message}") }
        }
    }

    fun duplicate(projectId: String) {
        viewModelScope.launch {
            runCatching { repository.duplicateProject(projectId) }
                .onSuccess { messageChannel.send("Project duplicated") }
                .onFailure { messageChannel.send("Duplicate failed: ${it.message}") }
        }
    }

    fun delete(projectId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteProject(projectId) }
                .onSuccess { messageChannel.send("Project deleted") }
                .onFailure { messageChannel.send("Delete failed: ${it.message}") }
        }
    }
}
