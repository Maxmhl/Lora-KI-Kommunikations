package de.loraki.core

/** basE91 (Joachim Henke): ~23 % Overhead statt 33 % bei Base64; nur druckbare ASCII-Zeichen. */
object Base91 {
    const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&()*+,./:;<=>?@[]^_`{|}~\""
    private val dec = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(data: ByteArray): String {
        val out = StringBuilder()
        var b = 0
        var n = 0
        for (byte in data) {
            b = b or ((byte.toInt() and 0xFF) shl n)
            n += 8
            if (n > 13) {
                var v = b and 8191
                if (v > 88) { b = b shr 13; n -= 13 } else { v = b and 16383; b = b shr 14; n -= 14 }
                out.append(ALPHABET[v % 91]).append(ALPHABET[v / 91])
            }
        }
        if (n > 0) {
            out.append(ALPHABET[b % 91])
            if (n > 7 || b > 90) out.append(ALPHABET[b / 91])
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var v = -1
        var b = 0
        var n = 0
        for (ch in text) {
            val c = if (ch.code < 128) dec[ch.code] else -1
            if (c < 0) throw IllegalArgumentException("Ungültiges basE91-Zeichen")
            if (v < 0) v = c
            else {
                v += c * 91
                b = b or (v shl n)
                n += 13 + if ((v and 8191) > 88) 0 else 1
                do { out.write(b and 255); b = b shr 8; n -= 8 } while (n > 7)
                v = -1
            }
        }
        if (v >= 0) out.write((b or (v shl n)) and 255)
        return out.toByteArray()
    }
}
