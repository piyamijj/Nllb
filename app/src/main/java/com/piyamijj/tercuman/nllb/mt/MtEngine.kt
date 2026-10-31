package com.piyamijj.tercuman.nllb.mt

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * NLLB-200 çeviri motoru (ONNX Runtime, cihazda).
 *
 * Boru hattı (Optimum/Xenova biçimi):
 *
 *   encoder( input_ids, attention_mask ) -> last_hidden_state
 *   decoder merged: input_ids, encoder_hidden_states, encoder_attention_mask,
 *                   past_key_values.*, use_cache_branch -> logits + present.*
 *
 * Kurallar (M2M100/NLLB):
 *  - kaynak: `[ <src_lang>, …tokens…, </s> ]`
 *  - decoder: ilk adım `[ </s>, <tgt_lang> ]`, sonrası `[ önceki_token ]`
 *
 * Optimum "merged" decoder, önbellekli dalda `present.*.encoder.*`
 * çıktılarını boş döndürür (şekil `(0,16,1,64)`); cross-attention KV'si
 * sabit olduğundan ilk adımdaki değer korunur.
 *
 * Ağır sınıftır (~900 MB model): IO iş parçacığında kullanın, bitince
 * [close] ile kapatın. ONNX oturumları dosya yolu ister; APK asset'leri
 * ilk açılışta uygulama dizinine kopyalanır (tek seferlik).
 */
class MtEngine(private val context: Context) {

    companion object {
        private const val TAG = "MtEngine"

        const val ASSET_DIR = "models/mt"
        const val ASSET_ENCODER = "$ASSET_DIR/encoder_quantized.onnx"
        const val ASSET_DECODER = "$ASSET_DIR/decoder_merged_quantized.onnx"
        private const val FILES_SUBDIR = "mt"
        private const val VERSION_NAME = "VERSION"
        private const val VERSION_VALUE = "1"

        private const val LAYERS = 12
        private const val HEADS = 16
        private const val HEAD_DIM = 64
        private const val HIDDEN = 1024
        private const val EOS = 2
        private const val DECODER_START = 2
        private const val MAX_NEW_TOKENS = 128
        private const val MAX_SRC_TOKENS = 128
        private const val MAX_CHARS_PER_CHUNK = 400
    }

    /** 48 past girdisinin adı (sabit sıra). */
    private val pastNames: List<String> = buildList {
        val parts = listOf("decoder.key", "decoder.value", "encoder.key", "encoder.value")
        for (l in 0 until LAYERS) for (p in parts) add("past_key_values.$l.$p")
    }
    private val presentNames: List<String> =
        pastNames.map { "present." + it.removePrefix("past_key_values.") }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: BpeTokenizer? = null

    fun isLoaded(): Boolean = encoder != null && decoder != null && tokenizer != null

    /** Gerekli asset'ler APK'da var mı? */
    fun modelFilesPresent(): Boolean = try {
        context.assets.open(BpeTokenizer.ASSET).close()
        context.assets.open(ASSET_ENCODER).close()
        context.assets.open(ASSET_DECODER).close()
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Tokenizeci + ONNX oturumlarını yükler. Ağır işlemdir; ana thread'de
     * çağrılmamalı. [onProgress] 0..1 arası ilerleme bildirir.
     */
    @Synchronized
    fun load(onProgress: (Float, String) -> Unit = { _, _ -> }): Boolean {
        if (isLoaded()) return true
        if (!modelFilesPresent()) {
            Log.w(TAG, "çeviri model dosyaları APK içinde bulunamadı")
            return false
        }
        return try {
            onProgress(0.05f, "Sözlük yükleniyor…")
            context.assets.open(BpeTokenizer.ASSET).use {
                tokenizer = BpeTokenizer.load(it)
            }

            val dir = File(context.filesDir, FILES_SUBDIR).apply { mkdirs() }
            val encFile = File(dir, "encoder_quantized.onnx")
            val decFile = File(dir, "decoder_merged_quantized.onnx")
            val marker = File(dir, VERSION_NAME)
            if (!marker.isFile || marker.readText() != VERSION_VALUE) {
                onProgress(0.1f, "Çeviri modeli hazırlanıyor (ilk açılış)…")
                copyAsset(ASSET_ENCODER, encFile) { f ->
                    onProgress(0.1f + 0.35f * f, "Çeviri modeli hazırlanıyor…")
                }
                copyAsset(ASSET_DECODER, decFile) { f ->
                    onProgress(0.45f + 0.4f * f, "Çeviri modeli hazırlanıyor…")
                }
                marker.writeText(VERSION_VALUE)
            }

            onProgress(0.9f, "Çeviri motoru başlatılıyor…")
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            encoder = env.createSession(encFile.absolutePath, opts)
            decoder = env.createSession(decFile.absolutePath, opts)
            onProgress(1f, "Çeviri hazır")
            Log.i(TAG, "MT hazır (vocab=${tokenizer?.vocabSize()})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "çeviri motoru yüklenemedi: ${t.message}")
            close()
            false
        }
    }

    /**
     * Metni [srcLang] dilinden ("tr"/"fa") diğer dile çevirir.
     * Uzun metin cümlelere bölünüp ayrı çevrilir.
     */
    @Synchronized
    fun translate(text: String, srcLang: String, tgtLang: String): String {
        val tk = tokenizer ?: return ""
        val input = text.trim()
        if (input.isEmpty()) return ""

        val t0 = SystemClock.elapsedRealtime()
        val out = ArrayList<String>()
        for (chunk in splitSentences(input, MAX_CHARS_PER_CHUNK)) {
            val one = try {
                translateChunk(tk, chunk, srcLang, tgtLang)
            } catch (t: Throwable) {
                Log.e(TAG, "çeviri hatası: ${t.message}")
                ""
            }
            if (one.isNotBlank()) out.add(one)
        }
        val result = out.joinToString(" ")
        Log.i(
            TAG,
            "çevrildi $srcLang->$tgtLang " +
                "(${SystemClock.elapsedRealtime() - t0} ms, ${out.size} parça)",
        )
        return result
    }

    private fun translateChunk(
        tk: BpeTokenizer,
        text: String,
        srcLang: String,
        tgtLang: String,
    ): String {
        val srcIds = tk.encodeSource(text, srcLang, MAX_SRC_TOKENS)
        val hidden = runEncoder(srcIds)
        val generated = greedyDecode(hidden, srcIds.size, tk.langId(tgtLang))
        return tk.decode(generated)
    }

    private fun runEncoder(srcIds: LongArray): FloatArray {
        val enc = encoder ?: error("encoder yüklenmedi")
        val s = srcIds.size
        val mask = LongArray(s) { 1L }
        val inIds = OnnxTensor.createTensor(env, LongBuffer.wrap(srcIds), longArrayOf(1, s.toLong()))
        val inMask = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, s.toLong()))
        try {
            enc.run(mapOf("input_ids" to inIds, "attention_mask" to inMask)).use { res ->
                val t = res.get("last_hidden_state").get() as OnnxTensor
                return copyFloats(t)
            }
        } finally {
            inIds.close(); inMask.close()
        }
    }

    private fun greedyDecode(hidden: FloatArray, srcLen: Int, tgtId: Int): IntArray {
        val dec = decoder ?: error("decoder yüklenmedi")
        val hiddenT = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(hidden), longArrayOf(1, srcLen.toLong(), HIDDEN.toLong()),
        )
        val encMask = LongArray(srcLen) { 1L }
        val maskT = OnnxTensor.createTensor(
            env, LongBuffer.wrap(encMask), longArrayOf(1, srcLen.toLong()),
        )

        var past = HashMap<String, Pair<FloatArray, LongArray>>()
        var stepIds = longArrayOf(DECODER_START.toLong(), tgtId.toLong())
        var first = true
        val out = ArrayList<Int>(64)
        try {
            for (step in 0 until MAX_NEW_TOKENS) {
                val (logits, newPast) = decoderStep(dec, hiddenT, maskT, stepIds, past, first)
                past = newPast
                first = false
                var best = 0
                var bestV = Float.NEGATIVE_INFINITY
                for (v in logits.indices) {
                    if (logits[v] > bestV) {
                        bestV = logits[v]; best = v
                    }
                }
                if (best == EOS) break
                out.add(best)
                stepIds = longArrayOf(best.toLong())
            }
        } finally {
            hiddenT.close(); maskT.close()
        }
        return out.toIntArray()
    }

    /**
     * Decoder'ın tek adımı. Son token'ın logit satırını ve güncel
     * past KV'yi döndürür.
     */
    private fun decoderStep(
        dec: OrtSession,
        hiddenT: OnnxTensor,
        maskT: OnnxTensor,
        ids: LongArray,
        past: Map<String, Pair<FloatArray, LongArray>>,
        first: Boolean,
    ): Pair<FloatArray, HashMap<String, Pair<FloatArray, LongArray>>> {
        val feeds = HashMap<String, OnnxTensor>(pastNames.size + 4)
        val created = ArrayList<OnnxTensor>(pastNames.size + 4)

        val idT = OnnxTensor.createTensor(
            env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()),
        )
        created.add(idT)
        feeds["input_ids"] = idT
        feeds["encoder_hidden_states"] = hiddenT
        feeds["encoder_attention_mask"] = maskT
        val useCache = OnnxTensor.createTensor(
            env, boolBuffer(!first), longArrayOf(1), OnnxJavaType.BOOL,
        )
        created.add(useCache)
        feeds["use_cache_branch"] = useCache

        for (name in pastNames) {
            val p = past[name]
            if (p != null) {
                val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(p.first), p.second)
                created.add(t)
                feeds[name] = t
            } else {
                val shape = longArrayOf(1, HEADS.toLong(), 0, HEAD_DIM.toLong())
                val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(0)), shape)
                created.add(t)
                feeds[name] = t
            }
        }

        try {
            dec.run(feeds).use { res ->
                val newPast = HashMap<String, Pair<FloatArray, LongArray>>(pastNames.size * 2)
                for ((inName, outName) in pastNames.zip(presentNames)) {
                    val t = res.get(outName).get() as OnnxTensor
                    val shape = t.info.shape
                    val prev = past[inName]
                    if (!first && prev != null && presentIsUnusable(shape)) {
                        newPast[inName] = prev
                    } else {
                        newPast[inName] = copyFloats(t) to shape
                    }
                }
                val logits = res.get("logits").get() as OnnxTensor
                val flat = copyFloats(logits)
                val vocab = logits.info.shape[2].toInt()
                val start = flat.size - vocab
                return flat.copyOfRange(start, flat.size) to newPast
            }
        } finally {
            created.forEach { runCatching { it.close() } }
        }
    }

    /** `present.*` çıktısı kullanışsız mı (sıfır batch ya da sıfır token)? */
    private fun presentIsUnusable(shape: LongArray): Boolean =
        shape.isEmpty() || shape[0] == 0L || (shape.size >= 3 && shape[2] == 0L)

    private fun copyAsset(assetPath: String, dest: File, onProgress: (Float) -> Unit) {
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(1024 * 1024)
                var copied = 0L
                val total = try {
                    context.assets.openFd(assetPath).use { it.length }
                } catch (t: Throwable) {
                    -1L
                }
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    copied += n
                    if (total > 0) onProgress(copied.toFloat() / total)
                }
            }
        }
    }

    private fun boolBuffer(v: Boolean): ByteBuffer =
        ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
            .put(if (v) 1 else 0).apply { rewind() }

    private fun copyFloats(t: OnnxTensor): FloatArray {
        val fb = t.floatBuffer
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }

    @Synchronized
    fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
        tokenizer = null
    }

    /**
     * Metni cümlelere böler. NLLB/M2M çok cümleli girdide ilk cümleden
     * sonra </s> üretip gerisini atabildiğinden çeviri parça parça yapılır.
     * [maxChars] aşan cümle ayrıca kelimelerden bölünür.
     */
    internal fun splitSentences(text: String, maxChars: Int): List<String> {
        val sentences = ArrayList<String>()
        for (line in text.split('\n')) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val sb = StringBuilder()
            var i = 0
            while (i < l.length) {
                val c = l[i]
                sb.append(c)
                i++
                val end = c == '.' || c == '!' || c == '?' || c == '؟' ||
                    c == '…' || c == ':' || c == '。' || c == '！' || c == '？'
                if (end) {
                    var j = i
                    while (j < l.length && l[j].isWhitespace()) j++
                    // Latin noktalamasında boşluk şart ("Dr.", "3.14" bölünmez).
                    if (j > i || j >= l.length) {
                        val s = sb.toString().trim()
                        if (s.isNotEmpty()) sentences.add(s)
                        sb.setLength(0)
                        i = j
                    }
                }
            }
            val rest = sb.toString().trim()
            if (rest.isNotEmpty()) sentences.add(rest)
        }

        val units = ArrayList<String>(sentences.size)
        for (s in sentences) {
            if (s.length <= maxChars) {
                units.add(s)
            } else {
                var cur = ""
                for (word in s.split(' ')) {
                    if (cur.isNotEmpty() && cur.length + 1 + word.length > maxChars) {
                        units.add(cur); cur = word
                    } else {
                        cur = if (cur.isEmpty()) word else "$cur $word"
                    }
                }
                if (cur.isNotEmpty()) units.add(cur)
            }
        }
        return units.ifEmpty { listOf(text) }
    }
}
