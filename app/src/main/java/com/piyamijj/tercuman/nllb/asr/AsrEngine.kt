package com.piyamijj.tercuman.nllb.asr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

data class AsrResult(
    val text: String,
    val lang: String,
    val elapsedMs: Long,
)

/**
 * Whisper (sherpa-onnx) sarmalayıcısı.
 *
 * Model dosyaları APK içinde, sıkıştırılmamış asset olarak durur:
 *   models/whisper/base-encoder.int8.onnx
 *   models/whisper/base-decoder.int8.onnx
 *   models/whisper/base-tokens.txt
 *   models/silero_vad.onnx
 *
 * Dil boş string ise Whisper her cümlede dili kendi algılar (LID).
 */
class AsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "AsrEngine"
        const val SAMPLE_RATE = 16000

        private const val ENCODER = "models/whisper/base-encoder.int8.onnx"
        private const val DECODER = "models/whisper/base-decoder.int8.onnx"
        private const val TOKENS = "models/whisper/base-tokens.txt"
        const val VAD_MODEL = "models/silero_vad.onnx"

        private val BRACKETED = Regex("""^\s*[\[\(\*].*[\]\)\*]\s*$""")
        private val MULTI_SPACE = Regex("""\s+""")
    }

    private var recognizer: OfflineRecognizer? = null
    private var currentLang: String = ""

    val isLoaded: Boolean get() = recognizer != null

    /** Asset'ler APK'da var mı? */
    fun modelFilesPresent(): Boolean = try {
        context.assets.open(ENCODER).close()
        context.assets.open(DECODER).close()
        context.assets.open(TOKENS).close()
        context.assets.open(VAD_MODEL).close()
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Modeli yükler. Ağır işlemdir; ana thread'de çağrılmamalı.
     * @param language "" = otomatik dil algılama, "tr" / "fa" = sabit dil
     */
    @Synchronized
    fun load(language: String = "", threads: Int = 2) {
        if (recognizer != null && currentLang == language) return
        if (!modelFilesPresent()) {
            error("ASR model dosyaları APK içinde bulunamadı")
        }

        release()

        val config = buildConfig(language, threads)
        Log.i(TAG, "Whisper yükleniyor (lang='${language.ifEmpty { "auto" }}', threads=$threads)")
        val t0 = SystemClock.elapsedRealtime()
        recognizer = OfflineRecognizer(assetManager = context.assets, config = config)
        currentLang = language
        Log.i(TAG, "Whisper hazır: ${SystemClock.elapsedRealtime() - t0} ms")
    }

    @Synchronized
    fun setLanguage(language: String) {
        val r = recognizer ?: return
        if (language == currentLang) return
        runCatching {
            r.setConfig(buildConfig(language, 2))
            currentLang = language
            Log.i(TAG, "Dil değişti: '${language.ifEmpty { "auto" }}'")
        }.onFailure {
            Log.w(TAG, "Dil değiştirilemedi", it)
        }
    }

    @Synchronized
    fun transcribe(samples: FloatArray): AsrResult {
        val r = recognizer ?: error("ASR modeli yüklenmedi")
        val t0 = SystemClock.elapsedRealtime()
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            val res = r.getResult(stream)
            return AsrResult(
                text = clean(res.text),
                lang = normalizeLang(res.lang),
                elapsedMs = SystemClock.elapsedRealtime() - t0,
            )
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
        currentLang = ""
    }

    private fun buildConfig(language: String, threads: Int) = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = ENCODER,
                decoder = DECODER,
                language = language,
                task = "transcribe",
                tailPaddings = 300,
            ),
            tokens = TOKENS,
            numThreads = threads,
            debug = false,
            provider = "cpu",
            modelType = "whisper",
        ),
    )

    /** Whisper bazen "[MÜZİK]" gibi etiketler üretir; bunları at. */
    private fun clean(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        if (BRACKETED.matches(t)) return ""
        return t.replace(MULTI_SPACE, " ")
    }

    /** "tr", "<|fa|>", "persian" -> "tr" / "fa" */
    private fun normalizeLang(raw: String): String {
        val s = raw.lowercase().trim().trim('<', '|', '>')
        return when {
            s.startsWith("tr") -> "tr"
            s.startsWith("fa") || s.startsWith("persian") -> "fa"
            else -> s.take(2)
        }
    }
}
