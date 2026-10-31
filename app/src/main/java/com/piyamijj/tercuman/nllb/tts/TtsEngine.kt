package com.piyamijj.tercuman.nllb.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * Piper seslendirme motoru (sherpa-onnx VITS, cihazda).
 *
 * Her dilin kendi sesi var (tr: dfki, fa: ganji); espeak-ng verisi ortak.
 * Asset düzeni:
 *
 *   models/tts/tr/tr_TR-dfki-medium.onnx
 *   models/tts/tr/tokens.txt
 *   models/tts/fa/fa_IR-ganji-medium.onnx
 *   models/tts/fa/tokens.txt
 *   models/tts/espeak-ng-data/...
 *
 * Üretilen ses [speak] ile hoparlörden çalınır; yarı-dubleks konuşma
 * için çalma bitene kadar bloklar.
 */
class TtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "TtsEngine"

        private const val TR_MODEL = "models/tts/tr/tr_TR-dfki-medium.onnx"
        private const val TR_TOKENS = "models/tts/tr/tokens.txt"
        private const val FA_MODEL = "models/tts/fa/fa_IR-ganji-medium.onnx"
        private const val FA_TOKENS = "models/tts/fa/tokens.txt"
        private const val ESPEAK_DATA = "models/tts/espeak-ng-data"

        private const val NOISE_SCALE = 0.667f
        private const val NOISE_W = 0.8f
        private const val LENGTH_SCALE = 1.0f
        private const val SPEED = 1.0f
    }

    private var trTts: OfflineTts? = null
    private var faTts: OfflineTts? = null

    @Volatile
    private var track: AudioTrack? = null

    fun isLoaded(): Boolean = trTts != null && faTts != null

    /** Ses dosyaları APK'da var mı? */
    fun modelFilesPresent(): Boolean = try {
        context.assets.open(TR_MODEL).close()
        context.assets.open(TR_TOKENS).close()
        context.assets.open(FA_MODEL).close()
        context.assets.open(FA_TOKENS).close()
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * İki sesi de yükler. Ağır işlemdir; ana thread'de çağrılmamalı.
     */
    @Volatile var lastError: String? = null

    @Synchronized
    fun load(): Boolean {
        if (isLoaded()) return true
        if (!modelFilesPresent()) {
            Log.w(TAG, "TTS dosyaları APK içinde bulunamadı")
            return false
        }
        return try {
            val t0 = SystemClock.elapsedRealtime()
            trTts = OfflineTts(context.assets, ttsConfig(TR_MODEL, TR_TOKENS))
            faTts = OfflineTts(context.assets, ttsConfig(FA_MODEL, FA_TOKENS))
            Log.i(TAG, "TTS hazır: ${SystemClock.elapsedRealtime() - t0} ms")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "TTS yüklenemedi", t)
            lastError = t.javaClass.simpleName + ": " + (t.message ?: "").take(300)
            release()
            false
        }
    }

    /**
     * Metni [lang] ("tr"/"fa") sesiyle sentezleyip çalar.
     * Çalma bitene kadar bloklar; yarı-dubleks akış bu sayede mikrofona
     * kendi sesimizi almaz. Başarılıysa true döndürür.
     */
    fun speak(text: String, lang: String): Boolean {
        val tts = if (lang == "fa") faTts else trTts
        val engine = tts ?: return false
        val input = text.trim()
        if (input.isEmpty()) return false
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val audio = synchronized(this) { engine.generate(input, 0, SPEED) }
            val samples = audio.samples
            if (samples.isEmpty()) return false
            Log.i(TAG, "sentez ${SystemClock.elapsedRealtime() - t0} ms (${samples.size} örnek)")
            play(samples, audio.sampleRate)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "seslendirme hatası: ${t.message}")
            false
        }
    }

    /** Çalmakta olan sesi durdurur (kapanış/yeni cümle için). */
    fun stopPlaying() {
        runCatching {
            track?.stop()
            track?.release()
        }
        track = null
    }

    @Synchronized
    fun release() {
        stopPlaying()
        runCatching { trTts?.release() }
        runCatching { faTts?.release() }
        trTts = null
        faTts = null
    }

    private fun ttsConfig(model: String, tokens: String) = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = model,
                lexicon = "",
                tokens = tokens,
                dataDir = ESPEAK_DATA,
                dictDir = "",
                noiseScale = NOISE_SCALE,
                noiseScaleW = NOISE_W,
                lengthScale = LENGTH_SCALE,
            ),
            numThreads = 2,
            debug = false,
            provider = "cpu",
        ),
    )

    private fun play(samples: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val sizeBytes = maxOf(minBuf, pcm.size * 2)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(sizeBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
            val timeoutMs = (pcm.size * 1000L / sampleRate) + 5000L
            val start = SystemClock.elapsedRealtime()
            while (t.playState == AudioTrack.PLAYSTATE_PLAYING &&
                SystemClock.elapsedRealtime() - start < timeoutMs
            ) {
                Thread.sleep(50)
            }
        } finally {
            runCatching { t.stop() }
            runCatching { t.release() }
            if (track === t) track = null
        }
    }
}
