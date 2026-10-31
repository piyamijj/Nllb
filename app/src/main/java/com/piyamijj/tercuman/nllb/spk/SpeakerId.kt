package com.piyamijj.tercuman.nllb.spk

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Konuşmacı tanıma (sherpa-onnx speaker embedding, cihazda).
 *
 * İki kişi kaydedilir ("ben", "karsi"); her cümle kimin söylediğiyle
 * etiketlenir. Kayıt yoksa özellik suskun kalır (etiket boş).
 */
class SpeakerId(private val context: Context) {

    companion object {
        private const val TAG = "SpeakerId"

        const val ME = "ben"
        const val OTHER = "karsi"

        private const val MODEL = "models/spk/campplus_voxceleb.onnx"
        private const val SAMPLE_RATE = 16000
        private const val MIN_IDENTIFY_SECONDS = 0.8f
        private const val MIN_ENROLL_SECONDS = 2.0f
        private const val THRESHOLD = 0.5f
        private const val DIR = "spk"
    }

    private var extractor: SpeakerEmbeddingExtractor? = null
    private var manager: SpeakerEmbeddingManager? = null

    fun isLoaded(): Boolean = extractor != null && manager != null

    fun enrolled(): List<String> =
        manager?.allSpeakerNames()?.toList() ?: emptyList()

    fun modelFilesPresent(): Boolean = try {
        context.assets.open(MODEL).close()
        true
    } catch (t: Throwable) {
        false
    }

    /** Modeli yükler ve kayıtlı sesleri geri okur. Ana thread'de çağrılmamalı. */
    @Synchronized
    fun load(): Boolean {
        if (isLoaded()) return true
        if (!modelFilesPresent()) {
            Log.w(TAG, "konuşmacı modeli APK içinde bulunamadı")
            return false
        }
        return try {
            val ext = SpeakerEmbeddingExtractor(
                context.assets,
                SpeakerEmbeddingExtractorConfig(
                    model = MODEL,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            extractor = ext
            val mgr = SpeakerEmbeddingManager(ext.dim())
            manager = mgr
            var n = 0
            for (name in listOf(ME, OTHER)) {
                val emb = readEmbedding(name, ext.dim())
                if (emb != null && mgr.add(name, emb)) n++
            }
            Log.i(TAG, "konuşmacı hazır (boyut=${ext.dim()}, kayıtlı=$n)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "konuşmacı yüklenemedi: ${t.message}")
            release()
            false
        }
    }

    /**
     * Örnekten kimlik vektörü çıkarıp [name] adıyla kaydeder.
     * Kayıt diske de yazılır (üzerine yazar).
     */
    @Synchronized
    fun enroll(name: String, samples: FloatArray): Boolean {
        val ext = extractor ?: return false
        val mgr = manager ?: return false
        if (samples.size < (SAMPLE_RATE * MIN_ENROLL_SECONDS).toInt()) return false
        return try {
            val emb = embed(ext, samples) ?: return false
            if (!mgr.add(name, emb)) return false
            writeEmbedding(name, emb)
            Log.i(TAG, "kaydedildi: $name")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "kayıt hatası: ${t.message}")
            false
        }
    }

    /** Örneği kimin söylediğini döndürür (bilinmiyorsa ""). */
    @Synchronized
    fun identify(samples: FloatArray): String {
        val ext = extractor ?: return ""
        val mgr = manager ?: return ""
        if (mgr.numSpeakers() == 0) return ""
        if (samples.size < (SAMPLE_RATE * MIN_IDENTIFY_SECONDS).toInt()) return ""
        return try {
            val emb = embed(ext, samples) ?: return ""
            mgr.search(emb, THRESHOLD) ?: ""
        } catch (t: Throwable) {
            Log.e(TAG, "tanıma hatası: ${t.message}")
            ""
        }
    }

    /** Tüm kayıtları siler (bellek + disk). */
    @Synchronized
    fun reset() {
        val mgr = manager
        for (name in listOf(ME, OTHER)) {
            runCatching { mgr?.remove(name) }
            runCatching { File(spkDir(), "$name.bin").delete() }
        }
    }

    @Synchronized
    fun release() {
        runCatching { extractor?.release() }
        runCatching { manager?.release() }
        extractor = null
        manager = null
    }

    private fun embed(ext: SpeakerEmbeddingExtractor, samples: FloatArray): FloatArray? {
        val stream = ext.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!ext.isReady(stream)) return null
            return ext.compute(stream)
        } finally {
            runCatching { stream.release() }
        }
    }

    private fun spkDir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun writeEmbedding(name: String, emb: FloatArray) {
        val buf = ByteBuffer.allocate(emb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in emb) buf.putFloat(v)
        File(spkDir(), "$name.bin").writeBytes(buf.array())
    }

    private fun readEmbedding(name: String, dim: Int): FloatArray? {
        return try {
            val bytes = File(spkDir(), "$name.bin").readBytes()
            if (bytes.size != dim * 4) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(dim).also { buf.get(it) }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Mikrofondan [seconds] saniyelik ham örnek kaydeder (kayıt için).
     * RECORD_AUDIO izni çağrıcıda olmalı. Bloklar.
     */
    @SuppressLint("MissingPermission")
    fun recordSample(seconds: Int): FloatArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 2),
            )
        }.getOrNull() ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return try {
            val need = SAMPLE_RATE * seconds
            val out = FloatArray(need)
            val buf = ShortArray(1600)
            var filled = 0
            rec.startRecording()
            while (filled < need) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT) break
                    continue
                }
                for (i in 0 until n) {
                    if (filled >= need) break
                    out[filled++] = buf[i] / 32768f
                }
            }
            if (filled >= (SAMPLE_RATE * MIN_ENROLL_SECONDS).toInt()) out else null
        } catch (t: Throwable) {
            Log.e(TAG, "örnek kaydı hatası: ${t.message}")
            null
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }
}
