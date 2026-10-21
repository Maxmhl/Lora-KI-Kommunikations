package de.loraki.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** LKP v1 – Wire-Format. Muss mit bridge/lkbridge/codec.py identisch sein (protocol/PROTOCOL.md). */
object Lkp {
    const val NUM_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    const val MAX_NUM = 64 * 64 - 1

    const val MARKER = '~'
    const val T_DATA = 'D'
    const val T_POLL = 'P'
    const val T_ACK = 'A'
    const val T_ABORT = 'X'

    const val HEADER_CHARS = 8
    const val BODY_CHARS = 132
    const val FRAGMENT_BYTES = BODY_CHARS / 4 * 3
    const val WINDOW = 6
    const val MAX_MISSING = 12

    const val KIND_TEXT = 1
    const val KIND_IMAGE = 2
    const val KIND_FILE = 3
    const val KIND_REPLY = 4
    const val KIND_STATUS = 5
    const val KIND_RESET = 6
    const val KIND_MODEL_LIST_REQ = 7
    const val KIND_MODEL_LIST = 8
    const val KIND_SET_MODEL = 9

    private const val FLAG_ZLIB = 1
    private const val MAX_INFLATED = 8 * 1024 * 1024

    class ProtocolException(msg: String) : Exception(msg)

    fun encNum(n: Int): String {
        if (n !in 0..MAX_NUM) throw ProtocolException("Zahl außerhalb 0..$MAX_NUM: $n")
        return "${NUM_ALPHABET[n shr 6]}${NUM_ALPHABET[n and 63]}"
    }

    fun decNum(s: String): Int {
        if (s.length != 2) throw ProtocolException("Zahl braucht 2 Zeichen")
        val a = NUM_ALPHABET.indexOf(s[0])
        val b = NUM_ALPHABET.indexOf(s[1])
        if (a < 0 || b < 0) throw ProtocolException("Ungültiges Zeichen in Zahl")
        return (a shl 6) or b
    }

    // ------------------------------------------------------------ Envelope

    class Envelope(val kind: Int, val conv: Int, val payload: ByteArray)

    private fun crc(data: ByteArray, off: Int = 0): Long =
        CRC32().also { it.update(data, off, data.size - off) }.value

    fun buildEnvelope(kind: Int, conv: Int, payload: ByteArray, compress: Boolean = true): ByteArray {
        var flags = 0
        var body = payload
        if (compress && payload.size > 24) {
            val z = deflate(payload)
            if (z.size < payload.size) { body = z; flags = FLAG_ZLIB }
        }
        val rest = byteArrayOf(flags.toByte(), kind.toByte(), conv.toByte()) + body
        val c = crc(rest)
        val head = ByteArray(4) { ((c shr (24 - 8 * it)) and 0xFF).toByte() }
        return head + rest
    }

    fun parseEnvelope(data: ByteArray): Envelope {
        if (data.size < 7) throw ProtocolException("Envelope zu kurz")
        var expected = 0L
        for (i in 0 until 4) expected = (expected shl 8) or (data[i].toLong() and 0xFF)
        if (crc(data, 4) != expected) throw ProtocolException("CRC32 stimmt nicht")
        val flags = data[4].toInt() and 0xFF
        var body = data.copyOfRange(7, data.size)
        if (flags and FLAG_ZLIB != 0) body = inflate(body)
        return Envelope(data[5].toInt() and 0xFF, data[6].toInt() and 0xFF, body)
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(9)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(b: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(b)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw ProtocolException("zlib: unvollständig")
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) throw ProtocolException("Entpackte Daten zu groß")
            }
        } catch (e: DataFormatException) {
            throw ProtocolException("zlib: ${e.message}")
        } finally { inf.end() }
        return out.toByteArray()
    }

    fun packFile(name: String, mime: String, data: ByteArray): ByteArray {
        val n = name.toByteArray().take(255).toByteArray()
        val m = mime.toByteArray().take(255).toByteArray()
        return byteArrayOf(n.size.toByte()) + n + byteArrayOf(m.size.toByte()) + m + data
    }

    class FilePayload(val name: String, val mime: String, val data: ByteArray)

    fun unpackFile(p: ByteArray): FilePayload = try {
        val nl = p[0].toInt() and 0xFF
        val name = String(p, 1, nl, Charsets.UTF_8)
        val ml = p[1 + nl].toInt() and 0xFF
        val mime = String(p, 2 + nl, ml, Charsets.UTF_8)
        FilePayload(name, mime, p.copyOfRange(2 + nl + ml, p.size))
    } catch (e: IndexOutOfBoundsException) {
        throw ProtocolException("Ungültige Datei-Nutzlast")
    }

    // ------------------------------------------------------------ Frames

    sealed class Frame {
        abstract val msgId: Int
        data class Data(override val msgId: Int, val idx: Int, val total: Int, val body: ByteArray, val poll: Boolean) : Frame()
        data class Ack(override val msgId: Int, val hi: Int, val missing: List<Int>) : Frame()
        data class Abort(override val msgId: Int) : Frame()
    }

    fun fragment(envelope: ByteArray): List<ByteArray> {
        if (envelope.isEmpty()) return listOf(ByteArray(0))
        val chunks = envelope.toList().chunked(FRAGMENT_BYTES).map { it.toByteArray() }
        if (chunks.size > MAX_NUM) throw ProtocolException("Objekt zu groß")
        return chunks
    }

    fun formatData(msgId: Int, idx: Int, total: Int, chunk: ByteArray, poll: Boolean): String =
        "$MARKER${if (poll) T_POLL else T_DATA}${encNum(msgId)}${encNum(idx)}${encNum(total)}" +
            Base64.getEncoder().encodeToString(chunk)

    fun formatAck(msgId: Int, hi: Int, missing: List<Int>): String =
        "$MARKER$T_ACK${encNum(msgId)}${encNum(hi)}" + missing.take(MAX_MISSING).joinToString("") { encNum(it) }

    fun formatAbort(msgId: Int): String = "$MARKER$T_ABORT${encNum(msgId)}"

    /** null, wenn der Text kein LKP-Frame ist. */
    fun parseFrame(text: String): Frame? {
        if (text.length < 4 || text[0] != MARKER) return null
        return try {
            val id = decNum(text.substring(2, 4))
            when (text[1]) {
                T_DATA, T_POLL -> {
                    if (text.length < HEADER_CHARS) return null
                    val idx = decNum(text.substring(4, 6))
                    val total = decNum(text.substring(6, 8))
                    if (total == 0 || idx >= total) return null
                    Frame.Data(id, idx, total, Base64.getDecoder().decode(text.substring(8)), text[1] == T_POLL)
                }
                T_ACK -> {
                    val hi = decNum(text.substring(4, 6))
                    val rest = text.substring(6)
                    if (rest.length % 2 != 0) return null
                    Frame.Ack(id, hi, rest.chunked(2).map { decNum(it) })
                }
                T_ABORT -> Frame.Abort(id)
                else -> null
            }
        } catch (e: ProtocolException) { null } catch (e: IllegalArgumentException) { null }
    }
}
