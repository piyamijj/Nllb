package com.piyamijj.tercuman.nllb

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piyamijj.tercuman.nllb.asr.AsrEngine
import com.piyamijj.tercuman.nllb.asr.AsrResult
import com.piyamijj.tercuman.nllb.asr.SpeechLoop
import com.piyamijj.tercuman.nllb.mt.MtEngine
import com.piyamijj.tercuman.nllb.spk.SpeakerId
import com.piyamijj.tercuman.nllb.tts.TtsEngine
import java.util.concurrent.Executors
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
    val speaker: String = "",
    val translation: String = "",
    val mtMs: Long = 0,
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
    val mtReady: Boolean = false,
    val ttsReady: Boolean = false,
    val translating: Boolean = false,
    val speaking: Boolean = false,
    val enrolledMe: Boolean = false,
    val enrolledOther: Boolean = false,
    val enrolling: String? = null,
    val lastDecodeMs: Long = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val MAX_ENTRIES = 200
    }

    private val engine = AsrEngine(app)
    private val mt = MtEngine(app)
    private val tts = TtsEngine(app)
    private val spk = SpeakerId(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loop: SpeechLoop? = null
    private var stopJob: Job? = null
    private var nextId = 1L

    /** Çeviri+seslendirme boru hattı (tek iş parçacığı: cümle sırası korunur). */
    private val pipe = Executors.newSingleThreadExecutor { r -> Thread(r, "tercuman-pipe") }

    /** Oturum sayacı: stop()/start() eskileri geçersiz kılar. */
    @Volatile
    private var session = 0L

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
                speaker = result.speaker,
            )
            _state.update { s ->
                s.copy(
                    entries = (s.entries + entry).takeLast(MAX_ENTRIES),
                    lastDecodeMs = result.elapsedMs,
                )
            }
            val s = session
            pipe.execute { processEntry(entry, s) }
        }

        override fun onError(message: String) {
            _state.update { it.copy(error = message) }
        }
    }

    /** Cümleyi çevirir ve seslendirir (boru hattı iş parçacığında, sıralı). */
    private fun processEntry(entry: TranscriptEntry, s: Long) {
        if (entry.lang != "tr" && entry.lang != "fa") return
        if (!_state.value.mtReady) return
        val tgt = if (entry.lang == "tr") "fa" else "tr"

        _state.update { it.copy(translating = true) }
        try {
            val t0 = SystemClock.elapsedRealtime()
            val translation = mt.translate(entry.text, entry.lang, tgt)
            if (s != session) return
            updateEntry(entry.id) {
                it.copy(translation = translation, mtMs = SystemClock.elapsedRealtime() - t0)
            }
            if (translation.isBlank() || !_state.value.ttsReady) return
            if (s != session) return

            _state.update { it.copy(speaking = true) }
            try {
                val l = loop
                l?.muted = true
                try {
                    tts.speak(translation, tgt)
                } finally {
                    l?.flush()
                    l?.muted = false
                }
            } finally {
                _state.update { it.copy(speaking = false) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "boru hattı hatası: ${t.message}")
        } finally {
            _state.update { it.copy(translating = false) }
        }
    }

    private fun updateEntry(id: Long, fn: (TranscriptEntry) -> TranscriptEntry) {
        _state.update { s ->
            s.copy(entries = s.entries.map { if (it.id == id) fn(it) else it })
        }
    }

    fun start() {
        if (_state.value.listening || _state.value.loading) return

        viewModelScope.launch {
            stopJob?.join()
            session++
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
                _state.update { it.copy(modelReady = true) }
            } else {
                withContext(Dispatchers.Default) {
                    engine.setLanguage(_state.value.mode.whisperLang)
                }
            }

            CrashLog.stage(getApplication<Application>(), "MT yükleniyor")
            if (!_state.value.mtReady) {
                _state.update {
                    it.copy(loading = true, loadingNote = "Çeviri motoru yükleniyor…")
                }
                val ok = withContext(Dispatchers.Default) {
                    runCatching {
                        mt.load { f, s ->
                            _state.update {
                                it.copy(loadingNote = "$s %${(f * 100).toInt()}")
                            }
                        }
                    }.getOrDefault(false)
                }
                _state.update { it.copy(mtReady = ok) }
                if (!ok) {
                    _state.update {
                        it.copy(error = "Çeviri yüklenemedi: " + (mt.lastError ?: "dosya bulunamadı"))
                    }
                }
            }

            CrashLog.stage(getApplication<Application>(), "TTS yükleniyor")
            if (!_state.value.ttsReady) {
                _state.update {
                    it.copy(loading = true, loadingNote = "Seslendirme yükleniyor…")
                }
                val ok = withContext(Dispatchers.Default) {
                    runCatching { tts.load() }.getOrDefault(false)
                }
                _state.update { it.copy(ttsReady = ok) }
                if (!ok) {
                    _state.update {
                        it.copy(error = "Seslendirme yüklenemedi: " + (tts.lastError ?: "dosya bulunamadı"))
                    }
                }
            }

            withContext(Dispatchers.Default) {
                runCatching { spk.load() }
            }
            CrashLog.stage(getApplication<Application>(), "Konuşmacı tanıma yükleniyor")
            refreshEnrolled()

            _state.update { it.copy(loading = false) }
            if (_state.value.listening) return@launch

            CrashLog.stage(getApplication<Application>(), "done")
            val speechLoop = SpeechLoop(getApplication<Application>(), engine, listener, spk)
            loop = speechLoop
            speechLoop.start()
            _state.update { it.copy(listening = true, error = null) }
        }
    }

    fun stop() {
        session++
        val active = loop
        loop = null
        tts.stopPlaying()
        if (active == null) return
        _state.update {
            it.copy(
                listening = false,
                speechActive = false,
                decoding = false,
                translating = false,
                speaking = false,
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

    /**
     * Konuşmacı kaydı: 4 saniye mikrofonu dinleyip [name] ("ben"/"karsi")
     * adıyla kaydeder. Dinleme açıksa önce durdurulur.
     */
    fun enroll(name: String) {
        if (_state.value.enrolling != null || _state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(enrolling = name, error = null) }
            val ok = withContext(Dispatchers.Default) {
                if (_state.value.listening) stop()
                if (!spk.isLoaded() && !spk.load()) return@withContext false
                val samples = spk.recordSample(4) ?: return@withContext false
                spk.enroll(name, samples)
            }
            refreshEnrolled()
            _state.update {
                it.copy(
                    enrolling = null,
                    error = if (ok) {
                        it.error
                    } else {
                        "Ses kaydı başarısız. Mikrofon iznini kontrol edip tekrar deneyin."
                    },
                )
            }
        }
    }

    fun resetSpeakers() {
        spk.reset()
        refreshEnrolled()
    }

    private fun refreshEnrolled() {
        val names = try {
            spk.enrolled()
        } catch (t: Throwable) {
            emptyList()
        }
        _state.update {
            it.copy(
                enrolledMe = SpeakerId.ME in names,
                enrolledOther = SpeakerId.OTHER in names,
            )
        }
    }

    fun clear() {
        tts.stopPlaying()
        _state.update { it.copy(entries = emptyList()) }
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Tüm model dosyaları APK içinde mi? */
    fun modelsBundled(): Boolean =
        engine.modelFilesPresent() && mt.modelFilesPresent() &&
            tts.modelFilesPresent() && spk.modelFilesPresent()

    override fun onCleared() {
        super.onCleared()
        session++
        runCatching { loop?.stop() }
        loop = null
        runCatching { pipe.shutdownNow() }
        engine.release()
        runCatching { mt.close() }
        runCatching { tts.release() }
        runCatching { spk.release() }
    }
}
