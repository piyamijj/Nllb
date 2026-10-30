package com.piyamijj.tercuman.nllb

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piyamijj.tercuman.nllb.asr.AsrEngine
import com.piyamijj.tercuman.nllb.asr.AsrResult
import com.piyamijj.tercuman.nllb.asr.SpeechLoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LangMode(val label: String, val whisperLang: String) {
    AUTO("Otomatik", ""),
    TR("Türkçe", "tr"),
    FA("Farsça", "fa"),
}

data class TranscriptEntry(
    val id: Long,
    val text: String,
    val lang: String,
    val elapsedMs: Long,
    val time: Long,
)

data class UiState(
    val listening: Boolean = false,
    val loading: Boolean = false,
    val loadingNote: String = "",
    val speechActive: Boolean = false,
    val decoding: Boolean = false,
    val level: Float = 0f,
    val mode: LangMode = LangMode.AUTO,
    val entries: List<TranscriptEntry> = emptyList(),
    val error: String? = null,
    val modelReady: Boolean = false,
    val lastDecodeMs: Long = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val MAX_ENTRIES = 200
    }

    private val engine = AsrEngine(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loop: SpeechLoop? = null
    private var stopJob: Job? = null
    private var nextId = 1L

    private val listener = object : SpeechLoop.Listener {
        override fun onLevel(level: Float) {
            _state.update { it.copy(level = level) }
        }

        override fun onSpeech(active: Boolean) {
            _state.update { it.copy(speechActive = active) }
        }

        override fun onDecoding(active: Boolean) {
            _state.update { it.copy(decoding = active) }
        }

        override fun onResult(result: AsrResult) {
            val entry = TranscriptEntry(
                id = nextId++,
                text = result.text,
                lang = result.lang,
                elapsedMs = result.elapsedMs,
                time = System.currentTimeMillis(),
            )
            _state.update { s ->
                s.copy(
                    entries = (s.entries + entry).takeLast(MAX_ENTRIES),
                    lastDecodeMs = result.elapsedMs,
                )
            }
        }

        override fun onError(message: String) {
            _state.update { it.copy(error = message) }
        }
    }

    fun start() {
        if (_state.value.listening || _state.value.loading) return

        viewModelScope.launch {
            stopJob?.join()
            _state.update { it.copy(error = null) }

            if (!_state.value.modelReady) {
                _state.update {
                    it.copy(loading = true, loadingNote = "Whisper base yükleniyor…")
                }
                val loaded = withContext(Dispatchers.Default) {
                    runCatching { engine.load(_state.value.mode.whisperLang) }
                }
                if (loaded.isFailure) {
                    Log.e(TAG, "model yüklenemedi", loaded.exceptionOrNull())
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "ASR modeli yüklenemedi: " +
                                (loaded.exceptionOrNull()?.message ?: "bilinmeyen hata"),
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(modelReady = true, loading = false) }
            } else {
                withContext(Dispatchers.Default) {
                    engine.setLanguage(_state.value.mode.whisperLang)
                }
            }

            if (_state.value.listening) return@launch

            val speechLoop = SpeechLoop(getApplication<Application>(), engine, listener)
            loop = speechLoop
            speechLoop.start()
            _state.update { it.copy(listening = true, error = null) }
        }
    }

    fun stop() {
        val active = loop ?: return
        loop = null
        _state.update {
            it.copy(
                listening = false,
                speechActive = false,
                decoding = false,
                level = 0f,
            )
        }
        stopJob = viewModelScope.launch(Dispatchers.Default) {
            runCatching { active.stop() }
        }
    }

    fun setMode(mode: LangMode) {
        if (_state.value.mode == mode) return
        _state.update { it.copy(mode = mode) }
        if (_state.value.modelReady) {
            viewModelScope.launch(Dispatchers.Default) {
                runCatching { engine.setLanguage(mode.whisperLang) }
            }
        }
    }

    fun clear() {
        _state.update { it.copy(entries = emptyList()) }
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Model dosyalarının APK içinde olup olmadığını kontrol eder. */
    fun modelsBundled(): Boolean = engine.modelFilesPresent()

    override fun onCleared() {
        super.onCleared()
        loop?.stop()
        loop = null
        engine.release()
    }
}
