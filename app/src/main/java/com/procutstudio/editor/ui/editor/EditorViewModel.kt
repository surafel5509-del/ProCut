package com.procutstudio.editor.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.data.media.ImportResult
import com.procutstudio.editor.data.media.MediaImporter
import com.procutstudio.editor.data.media.ThumbnailStore
import com.procutstudio.editor.data.local.prefs.AppSettings
import com.procutstudio.editor.data.local.prefs.SettingsRepository
import com.procutstudio.editor.data.media.WaveformStore
import com.procutstudio.editor.data.repository.ProjectRepository
import com.procutstudio.editor.domain.history.EditHistory
import com.procutstudio.editor.domain.history.EditorCommands
import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.AudioClip
import com.procutstudio.editor.domain.model.ClipTransform
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.FitMode
import com.procutstudio.editor.domain.model.OverlayAnimation
import com.procutstudio.editor.domain.model.TextAnimationType
import com.procutstudio.editor.domain.model.TextOverlay
import com.procutstudio.editor.domain.model.TextStyleSpec
import com.procutstudio.editor.domain.model.TimelineClip
import com.procutstudio.editor.domain.model.TransitionConfig
import com.procutstudio.editor.domain.model.TransitionType
import com.procutstudio.editor.domain.model.VideoEffect
import com.procutstudio.editor.domain.subtitles.SrtParser
import com.procutstudio.editor.domain.timeline.TimelineEditor
import com.procutstudio.editor.media.export.ColorEffectMapper
import com.procutstudio.editor.media.playback.PreviewPlayer
import com.procutstudio.editor.media.render.TextBitmapRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State holder for the editing workspace.
 *
 * Responsibilities:
 *  - owns the immutable [EditorProject] plus an [EditHistory] for undo/redo,
 *  - keeps the [PreviewPlayer] and the playhead in sync (including photos, which the player cannot
 *    render, and clip speeds, which retime the timeline),
 *  - debounces autosave so a trim drag produces one write, not two hundred.
 *
 * All document mutations go through [mutate] which keeps history, autosave and player sync in one
 * place; individual actions stay one-liners.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProjectRepository,
    private val mediaImporter: MediaImporter,
    private val player: PreviewPlayer,
    private val thumbnailStore: ThumbnailStore,
    private val waveformStore: WaveformStore,
    private val textRenderer: TextBitmapRenderer,
    settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val projectId: String = savedStateHandle.get<String>("projectId").orEmpty()

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    /** Editor preferences (keep screen on, safe-area guides, haptics) from Settings. */
    val appSettings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private var history: EditHistory? = null
    private var autosaveJob: Job? = null
    private var tickerJob: Job? = null
    private var lastTickMs: Long = 0L
    private var dragSnapshotTaken = false

    init {
        loadProject()
    }

    // ------------------------------------------------------------------ loading

    /** Exposes the shared player for the preview surface. */
    fun playerInstance(): androidx.media3.common.Player = player.player

    /** Preview monitor mute — never touches the project, so it is not an undoable edit. */
    fun togglePreviewMute() {
        val muted = !_uiState.value.isPreviewMuted
        _uiState.value = _uiState.value.copy(isPreviewMuted = muted)
        player.setMasterVolume(if (muted) 0f else 1f)
    }

    fun loadProject() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)
            val project = withContext(ioDispatcher) { repository.getProject(projectId) }
            if (project == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadError = "This project could not be opened. It may have been deleted.",
                )
                return@launch
            }
            history = EditHistory(project)
            _uiState.value = _uiState.value.copy(
                project = project,
                isLoading = false,
                playheadUs = 0L,
                selectedItemId = project.videoClips.firstOrNull()?.id,
                activePanel = EditorPanel.NONE,
            )
            syncPlayer(project, 0L)
            refreshHistoryState()
        }
    }

    /** Called when returning from a sub-editor (text/audio/filters) that writes to the repository. */
    fun refreshFromRepository() {
        viewModelScope.launch {
            val project = withContext(ioDispatcher) { repository.getProject(projectId) } ?: return@launch
            if (project == history?.current) return@launch
            history = EditHistory(project)
            _uiState.value = _uiState.value.copy(project = project)
            syncPlayer(project, _uiState.value.playheadUs)
            refreshHistoryState()
        }
    }

    // ------------------------------------------------------------------ transport

    fun seekTo(us: Long) {
        val state = _uiState.value
        val project = state.project ?: return
        val clamped = us.coerceIn(0L, project.durationUs)
        stopTicker()
        _uiState.value = state.copy(playheadUs = clamped, isPlaying = false)
        // Photos and gaps have no player surface; seeking the player is best-effort there.
        player.seekToTimeline(clamped)
        player.pause()
        applyLiveEffects()
    }

    fun seekBy(deltaUs: Long) = seekTo(_uiState.value.playheadUs + deltaUs)

    fun stepFrame(forward: Boolean) {
        // One frame at 30 fps is a predictable, useful nudge regardless of source frame rate.
        seekBy(if (forward) FRAME_US else -FRAME_US)
    }

    fun togglePlayback() {
        val state = _uiState.value
        val project = state.project ?: return
        if (project.durationUs <= 0L) return
        if (state.isPlaying) {
            pause()
        } else {
            if (state.playheadUs >= project.durationUs - 10_000L) {
                _uiState.value = state.copy(playheadUs = 0L)
            }
            _uiState.value = _uiState.value.copy(isPlaying = true)
            syncPlayer(project, _uiState.value.playheadUs)
            startTicker()
        }
    }

    fun pause() {
        stopTicker()
        player.pause()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        lastTickMs = System.currentTimeMillis()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val state = _uiState.value
                val project = state.project ?: break
                if (!state.isPlaying) break
                val now = System.currentTimeMillis()
                val elapsedUs = (now - lastTickMs) * 1_000L
                lastTickMs = now
                val clip = project.clipAt(state.playheadUs)
                val newPlayhead = when {
                    clip == null -> project.durationUs
                    clip.mediaType == com.procutstudio.editor.domain.model.MediaType.VIDEO -> {
                        val playerPosition = player.timelinePositionUs()
                        if (playerPosition != null && playerPosition > state.playheadUs) {
                            playerPosition
                        } else {
                            state.playheadUs + (elapsedUs * clip.speed).toLong()
                        }
                    }
                    else -> state.playheadUs + elapsedUs
                }
                if (!player.isPlaying && clip?.mediaType == com.procutstudio.editor.domain.model.MediaType.VIDEO) {
                    player.play()
                }
                if (newPlayhead >= project.durationUs) {
                    _uiState.value = _uiState.value.copy(playheadUs = project.durationUs, isPlaying = false)
                    player.pause()
                    break
                }
                _uiState.value = _uiState.value.copy(playheadUs = newPlayhead)
                if (clip != null && newPlayhead >= clip.endUs) {
                    player.seekToTimeline(newPlayhead)
                }
                delay(TICK_MS)
            }
            player.pause()
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    // ------------------------------------------------------------------ selection & panels

    fun selectItem(itemId: String?) {
        val state = _uiState.value
        _uiState.value = state.copy(
            selectedItemId = itemId,
            activePanel = if (itemId != null) EditorPanel.CLIP else EditorPanel.NONE,
        )
        applyLiveEffects()
    }

    fun openPanel(panel: EditorPanel) {
        _uiState.value = _uiState.value.copy(activePanel = panel)
    }

    fun closePanel() {
        _uiState.value = _uiState.value.copy(activePanel = EditorPanel.NONE)
    }

    fun setZoom(pixelsPerSecond: Float) {
        _uiState.value = _uiState.value.copy(pixelsPerSecond = pixelsPerSecond.coerceIn(16f, 240f))
    }

    fun zoomIn() = setZoom(_uiState.value.pixelsPerSecond * 1.5f)

    fun zoomOut() = setZoom(_uiState.value.pixelsPerSecond / 1.5f)

    // ------------------------------------------------------------------ editing commands

    /**
     * Splits whatever sits under the playhead.
     *
     * When a text/sticker/audio item is selected and the playhead is inside it, that item is split;
     * otherwise the main video lane is split, which is what the button means 95 % of the time.
     */
    fun splitAtPlayhead() {
        val state = _uiState.value
        val project = state.project ?: return
        val selected = state.selectedItem
        val splitSelected = selected != null &&
            selected !is TimelineClip &&
            state.playheadUs > selected.startUs &&
            state.playheadUs < selected.endUs
        if (splitSelected && selected != null) {
            mutate(EditorCommands.SPLIT) { current ->
                TimelineEditor.splitItem(current, selected.id, state.playheadUs)
            }
            return
        }
        if (!state.canSplit) {
            notify("Move the playhead inside a clip to split it")
            return
        }
        mutate(EditorCommands.SPLIT) { current -> TimelineEditor.splitAtPlayhead(current, state.playheadUs) }
    }

    fun deleteSelected() {
        val item = _uiState.value.selectedItem ?: return
        mutate(EditorCommands.DELETE) { project -> TimelineEditor.deleteItem(project, item.id) }
        _uiState.value = _uiState.value.copy(selectedItemId = null, activePanel = EditorPanel.NONE)
    }

    fun duplicateSelected() {
        val item = _uiState.value.selectedItem ?: return
        mutate(EditorCommands.DUPLICATE) { project -> TimelineEditor.duplicateItem(project, item.id) }
    }

    fun reorderSelectedClip(direction: Int) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(EditorCommands.REORDER) { project -> TimelineEditor.moveClipBy(project, clip.id, direction.toLong()) }
    }

    fun moveClipToIndex(clipId: String, index: Int) {
        mutate(EditorCommands.REORDER) { project -> TimelineEditor.moveClipToIndex(project, clipId, index) }
    }

    // Trim needs a snapshot before the drag so undo restores the original clip length.
    fun onTrimDragStart() {
        dragSnapshotTaken = false
    }

    fun trimSelectedStartBy(deltaUs: Long) {
        val clip = _uiState.value.selectedItem ?: return
        if (!dragSnapshotTaken) dragSnapshotTaken = true
        mutate(null) { project -> TimelineEditor.trimStartBy(project, clip.id, deltaUs) }
    }

    fun trimSelectedEndBy(deltaUs: Long) {
        val clip = _uiState.value.selectedItem ?: return
        if (!dragSnapshotTaken) dragSnapshotTaken = true
        mutate(null) { project -> TimelineEditor.trimEndBy(project, clip.id, deltaUs) }
    }

    fun onTrimDragEnd() {
        if (!dragSnapshotTaken) return
        dragSnapshotTaken = false
        commitPending(EditorCommands.TRIM)
    }

    fun moveItemTo(itemId: String, startUs: Long) {
        if (!dragSnapshotTaken) dragSnapshotTaken = true
        mutate(null) { project -> TimelineEditor.moveItemTo(project, itemId, startUs) }
    }

    fun onMoveDragEnd() {
        if (!dragSnapshotTaken) return
        dragSnapshotTaken = false
        commitPending("Move clip")
    }

    fun setSpeed(speed: Float) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(EditorCommands.SPEED) { project -> TimelineEditor.setSpeed(project, clip.id, speed) }
    }

    fun setVolume(volume: Float) {
        val item = _uiState.value.selectedItem ?: return
        mutate(EditorCommands.VOLUME) { project -> TimelineEditor.setVolume(project, item.id, volume) }
    }

    fun toggleMute() {
        val item = _uiState.value.selectedItem ?: return
        val muted = when (item) {
            is TimelineClip -> !item.isMuted
            is com.procutstudio.editor.domain.model.AudioClip -> !item.isMuted
            else -> return
        }
        mutate(EditorCommands.MUTE) { project -> TimelineEditor.setMuted(project, item.id, muted) }
    }

    fun setTransform(transform: ClipTransform) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(EditorCommands.TRANSFORM) { project -> TimelineEditor.setTransform(project, clip.id, transform) }
    }

    fun rotateBy(degrees: Int) {
        val clip = _uiState.value.selectedClip ?: return
        val transform = clip.transform.copy(rotationDegrees = ((clip.transform.rotationDegrees + degrees) % 360 + 360) % 360)
        setTransform(transform)
    }

    fun toggleFlipHorizontal() {
        val clip = _uiState.value.selectedClip ?: return
        setTransform(clip.transform.copy(flipHorizontal = !clip.transform.flipHorizontal))
    }

    fun toggleFlipVertical() {
        val clip = _uiState.value.selectedClip ?: return
        setTransform(clip.transform.copy(flipVertical = !clip.transform.flipVertical))
    }

    /** Ken Burns camera move for photos (exported and animated in the preview). */
    fun setKenBurns(motion: com.procutstudio.editor.domain.model.KenBurnsMotion) {
        val clip = _uiState.value.selectedClip ?: return
        if (clip.mediaType != com.procutstudio.editor.domain.model.MediaType.IMAGE) return
        mutate(com.procutstudio.editor.domain.history.EditorCommands.TRANSFORM) { project ->
            TimelineEditor.setTransform(project, clip.id, clip.transform.copy(kenBurns = motion))
        }
    }

    fun setFitMode(fitMode: FitMode) {
        val clip = _uiState.value.selectedClip ?: return
        setTransform(clip.transform.copy(fit = fitMode))
    }

    fun setCrop(left: Float, top: Float, right: Float, bottom: Float) {
        val clip = _uiState.value.selectedClip ?: return
        setTransform(clip.transform.copy(cropLeft = left, cropTop = top, cropRight = right, cropBottom = bottom))
    }

    fun resetTransform() {
        val clip = _uiState.value.selectedClip ?: return
        setTransform(ClipTransform())
    }

    /** Live update while a slider is being dragged; commit with [commitTransient]. */
    fun previewTransform(transform: ClipTransform) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(null) { project -> TimelineEditor.setTransform(project, clip.id, transform) }
    }

    fun previewEffects(effects: VideoEffect) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(null) { project -> TimelineEditor.setEffects(project, clip.id, effects) }
        applyLiveEffects()
    }

    fun previewFades(fadeInUs: Long, fadeOutUs: Long) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(null) { project -> TimelineEditor.setFades(project, clip.id, fadeInUs, fadeOutUs) }
    }

    fun previewSpeed(speed: Float) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(null) { project -> TimelineEditor.setSpeed(project, clip.id, speed) }
    }

    fun previewTransition(durationUs: Long, type: TransitionType) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(null) { project -> TimelineEditor.setTransition(project, clip.id, TransitionConfig(type = type, durationUs = durationUs)) }
    }

    /** Closes a slider gesture so it becomes exactly one undo step. */
    fun commitTransient(label: String) = commitPending(label)

    fun setEffects(effects: VideoEffect) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(EditorCommands.ADJUST) { project -> TimelineEditor.setEffects(project, clip.id, effects) }
        applyLiveEffects()
    }

    fun setFades(fadeInUs: Long, fadeOutUs: Long) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(EditorCommands.FADE) { project -> TimelineEditor.setFades(project, clip.id, fadeInUs, fadeOutUs) }
    }

    fun setTransition(type: TransitionType) {
        val clip = _uiState.value.selectedClip ?: return
        mutate(EditorCommands.TRANSITION) { project ->
            TimelineEditor.setTransition(project, clip.id, TransitionConfig(type = type))
        }
    }

    fun setAspectRatio(aspectRatio: AspectRatio) {
        mutate(EditorCommands.ASPECT_RATIO) { project -> project.copy(aspectRatio = aspectRatio, updatedAtMs = System.currentTimeMillis()) }
    }

    fun setCanvasColor(argb: Long) {
        mutate(EditorCommands.CANVAS) { project -> project.copy(canvasColorArgb = argb) }
    }

    fun renameProject(name: String) {
        if (name.isBlank()) return
        mutate(EditorCommands.RENAME) { project -> project.renamed(name).touch() }
    }

    /** Creates a text overlay at the playhead and returns its id so the caller can open the editor. */
    fun addTextOverlay(text: String = "New text"): String? {
        val start = _uiState.value.playheadUs
        mutate(EditorCommands.ADD_TEXT) { project ->
            TimelineEditor.addTextOverlay(
                project = project,
                text = text,
                startUs = start,
                style = TextStyleSpec.Default,
            )
        }
        val overlay = _uiState.value.project?.textOverlays?.maxByOrNull { it.startUs }
        if (overlay != null) selectItem(overlay.id)
        return overlay?.id
    }

    fun importSticker(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImportingSticker = true)
            when (val result = mediaImporter.import(uri)) {
                is ImportResult.Success -> {
                    val start = _uiState.value.playheadUs
                    mutate(EditorCommands.ADD_OVERLAY) { project ->
                        TimelineEditor.addImageOverlay(
                            project = project,
                            asset = result.asset,
                            startUs = start,
                            scale = 0.35f,
                        )
                    }
                    _uiState.value.project?.imageOverlays?.lastOrNull()?.let { selectItem(it.id) }
                }
                is ImportResult.Failure -> notify(result.message)
            }
            _uiState.value = _uiState.value.copy(isImportingSticker = false)
        }
    }

    /** Imports additional media and appends it to the main video lane. */
    fun addMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusyMessage = "Importing media…")
            val assets = mutableListOf<com.procutstudio.editor.domain.model.MediaAsset>()
            var failure: String? = null
            uris.forEach { uri ->
                when (val result = mediaImporter.import(uri)) {
                    is ImportResult.Success -> assets += result.asset
                    is ImportResult.Failure -> if (failure == null) failure = result.message
                }
            }
            if (assets.isNotEmpty()) {
                mutate(EditorCommands.IMPORT_MEDIA) { project ->
                    TimelineEditor.appendAssets(project, assets)
                }
            }
            failure?.let { notify(it) }
            _uiState.value = _uiState.value.copy(isBusyMessage = null)
        }
    }

    /**
     * Imports an SRT file and turns every cue into a subtitle-styled text overlay, all in a single
     * undo step. Cues keep their own timing, so the result can still be edited cue by cue.
     */
    fun importSubtitles(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusyMessage = "Importing subtitles…")
            val raw = runCatching {
                withContext(ioDispatcher) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
            }.getOrNull()
            if (raw.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(isBusyMessage = null)
                notify("That subtitle file could not be read.")
                return@launch
            }
            val result = runCatching { SrtParser.parse(raw) }.getOrNull()
            if (result == null || result.cues.isEmpty()) {
                _uiState.value = _uiState.value.copy(isBusyMessage = null)
                notify("No subtitles were found in that file.")
                return@launch
            }
            mutate(EditorCommands.SUBTITLE_IMPORT) { project ->
                result.cues.fold(project) { acc, cue ->
                    TimelineEditor.addTextOverlay(
                        project = acc,
                        text = cue.text,
                        startUs = cue.startUs,
                        durationUs = cue.durationUs,
                        style = TextStyleSpec.Subtitle,
                        animation = OverlayAnimation(
                            type = TextAnimationType.FADE,
                            inDurationUs = 150_000L,
                            outDurationUs = 150_000L,
                        ),
                    )
                }
            }
            _uiState.value = _uiState.value.copy(isBusyMessage = null)
            val skipped = if (result.skippedBlocks > 0) " (${result.skippedBlocks} blocks skipped)" else ""
            notify("Added ${result.cues.size} subtitles$skipped")
        }
    }

    fun removeTextOverlay(overlayId: String) {
        mutate(EditorCommands.DELETE) { project -> TimelineEditor.deleteItem(project, overlayId) }
        _uiState.value = _uiState.value.copy(selectedItemId = null, activePanel = EditorPanel.NONE)
    }

    fun replaceSelectedClip(uri: Uri) {
        val clip = _uiState.value.selectedClip ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusyMessage = "Importing replacement…")
            when (val result = mediaImporter.import(uri)) {
                is ImportResult.Success -> {
                    mutate(EditorCommands.REPLACE_CLIP) { project ->
                        TimelineEditor.replaceClipAsset(project, clip.id, result.asset)
                    }
                }
                is ImportResult.Failure -> notify(result.message)
            }
            _uiState.value = _uiState.value.copy(isBusyMessage = null)
        }
    }

    fun undo() {
        val label = history?.undo() ?: return
        val project = history?.current ?: return
        _uiState.value = _uiState.value.copy(project = project)
        syncPlayer(project, _uiState.value.playheadUs)
        refreshHistoryState()
        scheduleAutosave()
        notify("Undo: $label")
    }

    fun redo() {
        val label = history?.redo() ?: return
        val project = history?.current ?: return
        _uiState.value = _uiState.value.copy(project = project)
        syncPlayer(project, _uiState.value.playheadUs)
        refreshHistoryState()
        scheduleAutosave()
        notify("Redo: $label")
    }

    fun saveNow() {
        val project = _uiState.value.project ?: return
        autosaveJob?.cancel()
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            runCatching { withContext(ioDispatcher) { repository.saveProject(project.touch()) } }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        lastSavedAtMs = System.currentTimeMillis(),
                    )
                }
                .onFailure { error ->
                    notify("Save failed: ${error.message}")
                    _uiState.value = _uiState.value.copy(isSaving = false)
                }
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Applies [transform] to the document. A non-null [label] records an undo step; a null label is
     * used for transient drag updates that are committed once the gesture ends.
     */
    private fun mutate(label: String?, transform: (EditorProject) -> EditorProject) {
        val current = _uiState.value.project ?: return
        val updated = runCatching { transform(current) }.getOrElse { error ->
            ProCutLogger.e(ProCutLogger.TAG_UI, "Edit failed", error)
            notify(error.message ?: "That edit could not be applied")
            return
        }
        if (updated == current) return
        if (label != null) {
            history?.commit(label, updated)
            refreshHistoryState()
        }
        _uiState.value = _uiState.value.copy(project = updated)
        syncPlayer(updated, _uiState.value.playheadUs)
        applyLiveEffects()
        scheduleAutosave()
    }

    private fun commitPending(label: String) {
        val project = _uiState.value.project ?: return
        history?.commit(label, project)
        refreshHistoryState()
        scheduleAutosave()
    }

    private fun refreshHistoryState() {
        val history = history
        _uiState.value = _uiState.value.copy(
            canUndo = history?.canUndo == true,
            canRedo = history?.canRedo == true,
            undoLabel = history?.undoLabel,
            redoLabel = history?.redoLabel,
        )
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            val project = _uiState.value.project ?: return@launch
            _uiState.value = _uiState.value.copy(isSaving = true)
            val ok = runCatching { withContext(ioDispatcher) { repository.saveProject(project.touch()) } }.isSuccess
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                lastSavedAtMs = if (ok) System.currentTimeMillis() else _uiState.value.lastSavedAtMs,
            )
            if (!ok) notify("Autosave failed — your changes are still in memory")
        }
    }

    private fun syncPlayer(project: EditorProject, playheadUs: Long) {
        runCatching { player.setProject(project, playheadUs) }
            .onFailure { ProCutLogger.w(ProCutLogger.TAG_PLAYBACK, "Preview sync failed", it) }
        val clip = project.clipAt(playheadUs)
        if (clip != null && clip.mediaType == com.procutstudio.editor.domain.model.MediaType.VIDEO) {
            player.seekToTimeline(playheadUs)
        } else {
            player.pause()
        }
        applyLiveEffects()
    }

    /** Colour grade + speed preview for the clip under the playhead. */
    private fun applyLiveEffects() {
        val clip = _uiState.value.project?.clipAt(_uiState.value.playheadUs) ?: return
        if (clip.mediaType != com.procutstudio.editor.domain.model.MediaType.VIDEO) return
        player.setVideoEffects(ColorEffectMapper.videoEffects(clip.effects))
    }

    private fun notify(message: String) {
        viewModelScope.launch { messageChannel.send(message) }
    }

    // ------------------------------------------------------------------ timeline rendering

    /** Evenly spaced frames for a clip's thumbnail strip, loaded in parallel. */
    suspend fun loadClipThumbnails(clip: TimelineClip, count: Int, widthPx: Int): List<Bitmap?> {
        val asset = _uiState.value.project?.asset(clip.assetId) ?: return emptyList()
        if (count <= 0) return emptyList()
        return withContext(ioDispatcher) {
            (0 until count).map { index ->
                async {
                    val fraction = if (count == 1) 0f else index.toFloat() / (count - 1)
                    val timeUs = clip.sourceStartUs + ((clip.sourceEndUs - clip.sourceStartUs) * fraction).toLong()
                    thumbnailStore.thumbnail(asset, timeUs, widthPx)
                }
            }.awaitAll()
        }
    }

    suspend fun loadWaveform(clip: AudioClip): FloatArray? {
        val asset = _uiState.value.project?.asset(clip.assetId) ?: return null
        return withContext(ioDispatcher) { waveformStore.peaks(asset) }
    }

    /** Renders a text overlay with the shared rasteriser so the preview matches the export. */
    suspend fun renderTextOverlayBitmap(overlay: TextOverlay, frameWidthPx: Int): Bitmap? =
        textRenderer.renderAsync(overlay.text, overlay.style, frameWidthPx.coerceIn(240, 2160))

    override fun onCleared() {
        super.onCleared()
        stopTicker()
        autosaveJob?.cancel()
        player.release()
    }

    private companion object {
        const val TICK_MS = 33L
        const val AUTOSAVE_DELAY_MS = 600L
        const val FRAME_US = 33_333L
    }
}
