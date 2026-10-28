package com.procutstudio.editor.ui.text

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.util.IdGenerator
import com.procutstudio.editor.core.util.TimeUtils
import com.procutstudio.editor.data.repository.ProjectRepository
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.OverlayAnimation
import com.procutstudio.editor.domain.model.TextOverlay
import com.procutstudio.editor.domain.model.TextStyleSpec
import com.procutstudio.editor.domain.timeline.TimelineEditor
import com.procutstudio.editor.media.render.TextBitmapRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TextEditorUiState(
    val project: EditorProject? = null,
    val draft: TextOverlay? = null,
    val isNew: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val previewWidthPx: Int = 720,
)

/**
 * Text overlay editor. Edits are applied to a draft so "Cancel" is always safe; the draft is
 * committed to the project document when the user taps Done.
 */
@HiltViewModel
class TextEditorViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val textRenderer: TextBitmapRenderer,
    savedStateHandle: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val projectId: String = savedStateHandle.get<String>("projectId").orEmpty()
    private val textId: String? = savedStateHandle.get<String>("textId")

    private val _uiState = MutableStateFlow(TextEditorUiState())
    val uiState: StateFlow<TextEditorUiState> = _uiState.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val project = withContext(ioDispatcher) { repository.getProject(projectId) }
            if (project == null) {
                _uiState.value = TextEditorUiState(isLoading = false, error = "This project could not be opened.")
                return@launch
            }
            val existing = textId?.let { id -> project.textOverlays.firstOrNull { it.id == id } }
            val draft = existing ?: TextOverlay(
                id = IdGenerator.textId(),
                startUs = 0L,
                durationUs = TimelineEditor.TEXT_DEFAULT_DURATION_US,
                text = "Your text",
                style = TextStyleSpec.Default,
                animation = OverlayAnimation(),
            )
            _uiState.value = TextEditorUiState(
                project = project,
                draft = draft,
                isNew = existing == null,
                isLoading = false,
            )
        }
    }

    fun updateText(text: String) = updateDraft { it.copy(text = text.take(280)) }

    fun updateStyle(change: (TextStyleSpec) -> TextStyleSpec) = updateDraft { overlay ->
        overlay.copy(style = change(overlay.style).sanitized())
    }

    fun updateAnimation(change: (OverlayAnimation) -> OverlayAnimation) = updateDraft { overlay ->
        overlay.copy(animation = change(overlay.animation))
    }

    fun updateTiming(startUs: Long, durationUs: Long) = updateDraft { overlay ->
        overlay.copy(
            startUs = startUs.coerceAtLeast(0L),
            durationUs = durationUs.coerceAtLeast(TimeUtils.MIN_CLIP_DURATION_US),
        )
    }

    suspend fun renderPreview(): android.graphics.Bitmap? {
        val draft = _uiState.value.draft ?: return null
        return textRenderer.renderAsync(draft.text, draft.style, _uiState.value.previewWidthPx)
    }

    fun save(onSaved: () -> Unit) {
        val project = _uiState.value.project ?: return
        val draft = _uiState.value.draft ?: return
        viewModelScope.launch {
            val updated = if (_uiState.value.isNew) {
                project.addItem(com.procutstudio.editor.domain.model.TrackType.TEXT, draft).touch()
            } else {
                project.replaceItem(draft).touch()
            }
            withContext(ioDispatcher) { repository.saveProject(updated) }
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val project = _uiState.value.project ?: return
        val draft = _uiState.value.draft ?: return
        viewModelScope.launch {
            val updated = project.removeItem(draft.id).touch()
            withContext(ioDispatcher) { repository.saveProject(updated) }
            onDeleted()
        }
    }

    private fun updateDraft(change: (TextOverlay) -> TextOverlay) {
        val draft = _uiState.value.draft ?: return
        _uiState.value = _uiState.value.copy(draft = change(draft))
    }
}
