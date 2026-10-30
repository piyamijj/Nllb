package com.piyamijj.tercuman.nllb.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Mikrofon -> Silero VAD -> Whisper (sherpa-onnx) döngüsü.
 *
 * Sürekli dinler; sessizlik algılanınca cümleyi keser ve arka planda çözer.
 * Aynı anda birden fazla cümle kuyruğa girebilir.
 */
class SpeechLoop(
    private val context: Context,
    private val engine: AsrEngine,
    private val listener: Listener,
) {

    interface Listener {
        fun onLevel(level: Float)
        fun onSpeech(active: Boolean)
        fun onDecoding(active: Boolean)
        fun onResult(result: AsrResult)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "SpeechLoop"
        private const val CHUNK = 1600 // 100 ms @ 16 kHz
        private const val MIN_SEGMENT_SECONDS = 0.35f
        private const val MAX_QUEUE = 8
    }

    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<FloatArray>()

    private var audioThread: Thread? = null
    private var decodeThread: Thread? = null

    @Volatile
    private var record: AudioRecord? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        queue.clear()
        decodeThread = Thread(::decodeLoop, "tercuman-decode").also { it.start() }
        audioThread = Thread(::audioLoop, "tercuman-audio").also { it.start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { record?.stop() }
        runCatching { audioThread?.join(2500) }
        // Kuyrukta kalan son cümlelerin çözülmesini bekle.
        runCatching { decodeThread?.join(10000) }
        if (decodeThread?.isAlive == true) decodeThread?.interrupt()
        audioThread = null
        decodeThread = null
        listener.onLevel(0f)
        listener.onSpeech(false)
        listener.onDecoding(false)
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        var rec: AudioRecord? = null
        var detector: Vad? = null
        try {
            val r = createRecord()
            if (r == null) {
                listener.onError("Mikrofon açılamadı")
                return
            }
            rec = r
            record = r

            val v = Vad(assetManager = context.assets, config = vadConfig())
            detector = v
            r.startRecording()

            val buf = ShortArray(CHUNK)
            val minSamples = (AsrEngine.SAMPLE_RATE * MIN_SEGMENT_SECONDS).toInt()
            var lastLevelAt = 0L
            var wasSpeech = false

            while (running.get()) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT) break
                    continue
                }

                val frame = FloatArray(n)
                var acc = 0.0
                for (i in 0 until n) {
                    val s = buf[i] / 32768f
                    frame[i] = s
                    acc += s.toDouble() * s
                }

                v.acceptWaveform(frame)

                val speech = v.isSpeechDetected()
                if (speech != wasSpeech) {
                    wasSpeech = speech
                    listener.onSpeech(speech)
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelAt >= 80L) {
                    lastLevelAt = now
                    listener.onLevel((sqrt(acc / n).toFloat() * 7f).coerceIn(0f, 1f))
                }

                drain(v, minSamples)
            }

            runCatching { v.flush() }
            drain(v, minSamples)
        } catch (t: Throwable) {
            if (running.get()) {
                Log.e(TAG, "ses döngüsü hatası", t)
                listener.onError(t.message ?: "Ses döngüsü hatası")
            }
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            record = null
            runCatching { detector?.release() }
            queue.offer(FloatArray(0))
        }
    }

    private fun drain(vad: Vad, minSamples: Int) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            if (segment.samples.size < minSamples) continue
            if (queue.size >= MAX_QUEUE) queue.poll()
            queue.offer(segment.samples)
        }
    }

    private fun decodeLoop() {
        while (true) {
            val samples = try {
                queue.take()
            } catch (e: InterruptedException) {
                break
            }
            if (samples.isEmpty()) {
                if (!running.get()) break else continue
            }

            listener.onDecoding(true)
            try {
                val result = engine.transcribe(samples)
                if (result.text.isNotBlank()) listener.onResult(result)
            } catch (t: Throwable) {
                Log.e(TAG, "çözümleme hatası", t)
                listener.onError("Çözümleme hatası: ${t.message ?: "bilinmeyen"}")
            } finally {
                listener.onDecoding(false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            AsrEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBuf, AsrEngine.SAMPLE_RATE * 2)

        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    AsrEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufBytes,
                )
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            rec.release()
        }
        return null
    }

    private fun vadConfig() = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = AsrEngine.VAD_MODEL,
            threshold = 0.5f,
            minSilenceDuration = 0.6f,
            minSpeechDuration = 0.35f,
            windowSize = 512,
            maxSpeechDuration = 15f,
        ),
        sampleRate = AsrEngine.SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
    )
}
