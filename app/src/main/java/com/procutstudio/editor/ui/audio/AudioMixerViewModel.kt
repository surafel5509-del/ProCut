package com.procutstudio.editor.ui.audio

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.util.FileUtils
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.data.media.ImportResult
import com.procutstudio.editor.data.media.MediaImporter
import com.procutstudio.editor.data.media.WaveformStore
import com.procutstudio.editor.data.repository.ProjectRepository
import com.procutstudio.editor.domain.model.AudioClip
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.timeline.TimelineEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class AudioMixerUiState(
    val project: EditorProject? = null,
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val isRecording: Boolean = false,
    val recordingSeconds: Int = 0,
    val playheadUs: Long = 0L,
    val error: String? = null,
    val waveforms: Map<String, FloatArray> = emptyMap(),
)

/**
 * Music, sound effects and voice-over management.
 *
 * Voice-over is recorded straight into app storage with `MediaRecorder`, then imported as a normal
 * audio asset — the microphone is only ever touched while a recording is running, and the
 * permission is requested by the screen, not on app start.
 */
@HiltViewModel
class AudioMixerViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val mediaImporter: MediaImporter,
    private val waveformStore: WaveformStore,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val projectId: String = savedStateHandle.get<String>("projectId").orEmpty()

    private val _uiState = MutableStateFlow(AudioMixerUiState())
    val uiState: StateFlow<AudioMixerUiState> = _uiState.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingTimer: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val project = withContext(ioDispatcher) { repository.getProject(projectId) }
            _uiState.value = _uiState.value.copy(
                project = project,
                isLoading = false,
                error = if (project == null) "This project could not be opened." else null,
            )
            project?.audioClips?.forEach { loadWaveform(it) }
        }
    }

    fun setPlayhead(us: Long) {
        _uiState.value = _uiState.value.copy(playheadUs = us.coerceAtLeast(0L))
    }

    fun importMusic(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, error = null)
            var imported = 0
            var failure: String? = null
            uris.forEach { uri ->
                when (val result = mediaImporter.import(uri)) {
                    is ImportResult.Success -> {
                        if (result.asset.isAudio) {
                            apply(projectChange = { project ->
                                TimelineEditor.addAudioClip(
                                    project = project,
                                    asset = result.asset,
                                    startUs = _uiState.value.playheadUs,
                                )
                            })
                            imported++
                        } else {
                            failure = "“${result.asset.displayName}” is not an audio file."
                        }
                    }
                    is ImportResult.Failure -> failure = result.message
                }
            }
            if (imported > 0) notify("Added $imported audio clip(s)")
            failure?.let { notify(it) }
            _uiState.value = _uiState.value.copy(isImporting = false)
        }
    }

    fun startRecording(): Boolean {
        if (recorder != null) return false
        return runCatching {
            val file = File(FileUtils.recordingsDir(context), "voice_${System.currentTimeMillis()}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(128_000)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            recordingFile = file
            _uiState.value = _uiState.value.copy(isRecording = true, recordingSeconds = 0)
            recordingTimer = viewModelScope.launch {
                while (true) {
                    delay(1_000)
                    _uiState.value = _uiState.value.copy(
                        recordingSeconds = _uiState.value.recordingSeconds + 1,
                    )
                }
            }
            true
        }.onFailure { error ->
            ProCutLogger.e(ProCutLogger.TAG_MEDIA, "Recorder failed to start", error)
            notify("Could not start recording: ${error.message}")
            recorder = null
        }.getOrDefault(false)
    }

    fun stopRecording() {
        val active = recorder ?: return
        recordingTimer?.cancel()
        recordingTimer = null
        recorder = null
        _uiState.value = _uiState.value.copy(isRecording = false)
        val file = recordingFile
        recordingFile = null
        runCatching { active.stop() }
            .onFailure {
                ProCutLogger.w(ProCutLogger.TAG_MEDIA, "Recorder stop failed", it)
                file?.delete()
                notify("Recording was too short to keep.")
                return
            }
        runCatching { active.release() }
        if (file == null || !file.exists() || file.length() < 2_000L) {
            file?.delete()
            notify("Recording was too short to keep.")
            return
        }
        viewModelScope.launch {
            when (val result = mediaImporter.importLocalFile(file, com.procutstudio.editor.domain.model.MediaType.AUDIO, "Voice-over")) {
                is ImportResult.Success -> {
                    apply { project ->
                        TimelineEditor.addVoiceRecording(project, result.asset, _uiState.value.playheadUs)
                    }
                    notify("Voice-over added at the playhead")
                }
                is ImportResult.Failure -> notify(result.message)
            }
        }
    }

    fun cancelRecording() {
        recordingTimer?.cancel()
        recordingTimer = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        recordingFile?.delete()
        recordingFile = null
        _uiState.value = _uiState.value.copy(isRecording = false, recordingSeconds = 0)
    }

    fun setVolume(audioId: String, volume: Float) = apply { project ->
        TimelineEditor.setVolume(project, audioId, volume)
    }

    fun setFades(audioId: String, fadeInUs: Long, fadeOutUs: Long) = apply { project ->
        TimelineEditor.setAudioFades(project, audioId, fadeInUs, fadeOutUs)
    }

    fun trim(audioId: String, sourceStartUs: Long, durationUs: Long) = apply { project ->
        TimelineEditor.updateAudio(project, audioId) { clip ->
            clip.copy(sourceStartUs = sourceStartUs, durationUs = durationUs)
        }
    }

    fun toggleMute(audioId: String) = apply { project ->
        val clip = project.audioClips.firstOrNull { it.id == audioId } ?: return@apply project
        TimelineEditor.setMuted(project, audioId, !clip.isMuted)
    }

    fun delete(audioId: String) = apply { project -> TimelineEditor.deleteItem(project, audioId) }

    fun setVideoVolume(clipId: String, volume: Float) = apply { project ->
        TimelineEditor.setVolume(project, clipId, volume)
    }

    fun toggleVideoMute(clipId: String) = apply { project ->
        val clip = project.videoClips.firstOrNull { it.id == clipId } ?: return@apply project
        TimelineEditor.setMuted(project, clipId, !clip.isMuted)
    }

    private fun apply(projectChange: (EditorProject) -> EditorProject) {
        val current = _uiState.value.project ?: return
        val updated = projectChange(current).touch()
        _uiState.value = _uiState.value.copy(project = updated)
        viewModelScope.launch {
            withContext(ioDispatcher) { repository.saveProject(updated) }
        }
        updated.audioClips.forEach { clip ->
            if (_uiState.value.waveforms[clip.assetId] == null) loadWaveform(clip)
        }
    }

    private fun loadWaveform(clip: AudioClip) {
        val asset = _uiState.value.project?.asset(clip.assetId) ?: return
        viewModelScope.launch {
            val peaks = withContext(ioDispatcher) { waveformStore.peaks(asset) } ?: return@launch
            _uiState.value = _uiState.value.copy(
                waveforms = _uiState.value.waveforms + (clip.assetId to peaks),
            )
        }
    }

    private fun notify(message: String) {
        viewModelScope.launch { messageChannel.send(message) }
    }

    override fun onCleared() {
        super.onCleared()
        recordingTimer?.cancel()
        runCatching { recorder?.release() }
        recorder = null
    }
}
