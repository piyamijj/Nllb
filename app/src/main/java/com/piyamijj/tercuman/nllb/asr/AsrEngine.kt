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
    val speaker: String = "",
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
 * Dil boş string ise Whisper her cümlede dili kendi algılar (LID), ancak
 * sonuç TR/FA dışına taşarsa cümle TR veya FA zorlanarak tekrar çözülür;
 * otomatik kipte çıktı dili her zaman TR veya FA olur.
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

        /** Arap alfabesi blokları: Farsça metin bu aralıklardadır. */
        private fun isArabicScript(c: Char): Boolean =
            c in '\u0600'..'\u06FF' || c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF'

        /** Latin alfabesi blokları: Türkçe (ç, ğ, ı, ö, ş, ü dahil) bu aralıklardadır. */
        private fun isLatinScript(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '\u00C0'..'\u00FF' || c in '\u0100'..'\u017F'

        internal fun arabicLetterCount(text: String): Int = text.count(::isArabicScript)

        internal fun latinLetterCount(text: String): Int = text.count(::isLatinScript)

        /**
         * Metnin alfabesi iddia edilen dille uyumlu mu?
         * Harf yoksa (sayı/noktalama) yargı verilemez, uyumlu sayılır.
         */
        internal fun isScriptConsistent(text: String, lang: String): Boolean {
            val latin = latinLetterCount(text)
            val arabic = arabicLetterCount(text)
            if (latin == 0 && arabic == 0) return true
            return if (lang == "fa") arabic >= latin else latin >= arabic
        }

        /**
         * Otomatik kipte Whisper LID sonucuna göre zorlanacak dili seçer.
         * null = sonucu olduğu gibi kabul et, "tr"/"fa" = o dile zorla ve tekrar çöz.
         */
        internal fun autoTargetLang(text: String, lang: String): String? {
            if (text.isBlank()) return null
            if (lang == "tr" || lang == "fa") {
                if (isScriptConsistent(text, lang)) return null
                return if (lang == "tr") "fa" else "tr"
            }
            // LID TR/FA dışına taştı (örn. de/ar): alfabeye göre karar ver.
            return if (arabicLetterCount(text) > latinLetterCount(text)) "fa" else "tr"
        }
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
        if (recognizer == null || language == currentLang) return
        applyConfig(language)
    }

    /**
     * Cümleyi çözer.
     *
     * Otomatik kipte Whisper önce dili kendisi algılar; sonuç TR/FA dışına
     * taşarsa (örn. Türkçe yerine "de", Farsça yerine "ar") cümle yazının
     * alfabesine göre TR veya FA zorlanarak bir kez daha çözülür. Böylece
     * otomatik kipte çıktı dili her zaman TR veya FA olur.
     */
    @Synchronized
    fun transcribe(samples: FloatArray): AsrResult {
        val r = recognizer ?: error("ASR modeli yüklenmedi")
        val t0 = SystemClock.elapsedRealtime()
        val (rawText, rawLang) = decodeOnce(r, samples)
        val text = clean(rawText)
        val lang = normalizeLang(rawLang)
        val target = if (currentLang.isEmpty()) autoTargetLang(text, lang) else null
        if (target == null) {
            return AsrResult(text, lang, SystemClock.elapsedRealtime() - t0)
        }
        Log.i(TAG, "Otomatik dil düzeltmesi: '$lang' -> '$target'")
        applyConfig(target, logChange = false)
        try {
            val (raw2, _) = decodeOnce(r, samples)
            return AsrResult(clean(raw2), target, SystemClock.elapsedRealtime() - t0)
        } finally {
            applyConfig("", logChange = false)
        }
    }

    private fun applyConfig(language: String, logChange: Boolean = true) {
        val r = recognizer ?: return
        runCatching {
            r.setConfig(buildConfig(language, 2))
            currentLang = language
            if (logChange) Log.i(TAG, "Dil değişti: '${language.ifEmpty { "auto" }}'")
        }.onFailure {
            Log.w(TAG, "Dil değiştirilemedi", it)
        }
    }

    private fun decodeOnce(r: OfflineRecognizer, samples: FloatArray): Pair<String, String> {
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            val res = r.getResult(stream)
            return res.text to res.lang
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
