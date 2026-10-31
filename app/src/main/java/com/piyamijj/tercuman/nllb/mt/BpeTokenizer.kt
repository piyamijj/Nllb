package com.piyamijj.tercuman.nllb.mt

import java.io.InputStream

/**
 * NLLB-200 SentencePiece BPE tokenizeci, saf Kotlin.
 *
 * Android ya da native bağımlılığı yok. Kompakt ikili biçimi
 * `tokenizer.json` dosyasından üretilir (bkz. araç betiği):
 *
 *   magic "NLLBTK1\n" | u32 nTokens | u32 nMerges
 *   tokens : nTokens × (u16 len + UTF-8)            (id sırasıyla)
 *   merges : nMerges × (u32 left, u32 right, u32 result) (sıra = öncelik)
 *   langs  : u32 nLangs, nLangs × (u16 len + u32 id + UTF-8)
 *
 * Algoritma:
 *   1. Metaspace: boşluklar U+2581 olur, başa U+2581 eklenir.
 *   2. Karakterler vocab id'lerine çevrilir (yoksa UNK).
 *   3. En düşük sıralı komşu çift tekrar tekrar birleştirilir (BPE).
 */
class BpeTokenizer private constructor(
    private val tokens: Array<String>,
    private val charToId: HashMap<Int, Int>,
    private val mergeRank: HashMap<Long, Int>,
    private val mergeResult: IntArray,
    val langIds: Map<String, Int>,
) {

    companion object {
        const val BOS = 0
        const val PAD = 1
        const val EOS = 2
        const val UNK = 3

        const val MAGIC = "NLLBTK1\n"
        const val ASSET = "mt/tokenizer.bin"
        private const val METASPACE = '\u2581'

        /**
         * SentencePiece nmt_nfkc normalizecisinin uyguladığı en yaygın
         * denklikler (anahtar ve değer = code point).
         */
        private val FIXUPS = mapOf(
            0x2018 to 0x27, 0x2019 to 0x27, 0x201A to 0x27, 0x201B to 0x27,
            0x201C to 0x22, 0x201D to 0x22, 0x201E to 0x22, 0x00AB to 0x22,
            0x00BB to 0x22, 0x2033 to 0x22,
            0x2013 to 0x2D, 0x2014 to 0x2D, 0x2212 to 0x2D, 0x2010 to 0x2D,
            0x2026 to 0x2E, 0x00A0 to 0x20, 0x2009 to 0x20, 0x202F to 0x20,
            0x3000 to 0x20,
        )

        /** Tokenizeci bir stream'den yükler (genelde APK asset'i). */
        fun load(input: InputStream): BpeTokenizer {
            val data = input.readBytes()

            val magic = String(data, 0, 8, Charsets.US_ASCII)
            require(magic == MAGIC) { "tokenizer.bin bozuk (magic=$magic)" }
            var p = 8

            val nTokens = readU32(data, p); p += 4
            val nMerges = readU32(data, p); p += 4

            val tokens = Array(nTokens) { "" }
            for (i in 0 until nTokens) {
                val len = readU16(data, p); p += 2
                tokens[i] = String(data, p, len, Charsets.UTF_8)
                p += len
            }

            val mergeRank = HashMap<Long, Int>(nMerges * 2)
            val mergeResult = IntArray(nMerges)
            for (r in 0 until nMerges) {
                val l = readU32(data, p)
                val rr = readU32(data, p + 4)
                val res = readU32(data, p + 8)
                p += 12
                mergeRank[key(l, rr)] = r
                mergeResult[r] = res
            }

            val nLangs = readU32(data, p); p += 4
            val langIds = HashMap<String, Int>(nLangs * 2)
            for (i in 0 until nLangs) {
                val len = readU16(data, p)
                val id = readU32(data, p + 2)
                val code = String(data, p + 6, len, Charsets.UTF_8)
                p += 6 + len
                langIds[code] = id
            }

            val charToId = HashMap<Int, Int>(16384)
            for (i in tokens.indices) {
                val t = tokens[i]
                if (t.isNotEmpty() && t.codePointCount(0, t.length) == 1) {
                    charToId.putIfAbsent(t.codePointAt(0), i)
                }
            }
            return BpeTokenizer(tokens, charToId, mergeRank, mergeResult, langIds)
        }

        private fun key(left: Int, right: Int): Long =
            (left.toLong() shl 32) or (right.toLong() and 0xFFFFFFFFL)

        private fun readU16(b: ByteArray, p: Int): Int =
            (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

        private fun readU32(b: ByteArray, p: Int): Int =
            (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
    }

    /** Dil kodu ("tr"/"fa") -> model dil jetonu id'si. */
    fun langId(code: String): Int =
        langIds[code] ?: throw IllegalArgumentException("bilinmeyen dil: $code")

    /** Ham token id'leri (dil jetonları hariç). */
    fun encode(text: String): IntArray {
        val s = metaspace(text)
        if (s.isEmpty()) return IntArray(0)

        val syms = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            syms.add(charToId[cp] ?: UNK)
            i += Character.charCount(cp)
        }

        while (syms.size > 1) {
            var bestRank = Int.MAX_VALUE
            for (k in 0 until syms.size - 1) {
                val r = mergeRank[key(syms[k], syms[k + 1])]
                if (r != null && r < bestRank) bestRank = r
            }
            if (bestRank == Int.MAX_VALUE) break

            var k = 0
            while (k < syms.size - 1) {
                val r = mergeRank[key(syms[k], syms[k + 1])]
                if (r != null && r == bestRank) {
                    syms[k] = mergeResult[bestRank]
                    syms.removeAt(k + 1)
                    break
                }
                k++
            }
        }
        return syms.toIntArray()
    }

    /** Encoder'ın beklediği id'ler: `[<src_lang>, …tokens…, </s>]`. */
    fun encodeSource(text: String, srcLang: String, maxTokens: Int = 128): LongArray {
        val ids = encode(text).take(maxTokens)
        val out = LongArray(ids.size + 2)
        out[0] = langId(srcLang).toLong()
        for (i in ids.indices) out[i + 1] = ids[i].toLong()
        out[out.size - 1] = EOS.toLong()
        return out
    }

    /** Üretilen id'lerden metin (özel jetonlar atılır). */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (isSpecial(id)) continue
            if (id < 0 || id >= tokens.size) continue
            sb.append(tokens[id])
        }
        return sb.toString().replace(METASPACE, ' ').trim()
    }

    fun isSpecial(id: Int): Boolean =
        id <= UNK || langIds.containsValue(id) || tokens.getOrNull(id) == "<mask>"

    private fun metaspace(text: String): String {
        val sb = StringBuilder(text.length + 1)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val fixed = FIXUPS[cp] ?: cp
            sb.appendCodePoint(
                if (fixed == 0x20 || fixed == 0x0A || fixed == 0x09 || fixed == 0x0D) {
                    METASPACE.code
                } else {
                    fixed
                },
            )
            i += Character.charCount(cp)
        }
        if (sb.isEmpty() || sb[0] != METASPACE) sb.insert(0, METASPACE)
        return sb.toString().trimEnd(METASPACE)
    }

    fun vocabSize(): Int = tokens.size
}
