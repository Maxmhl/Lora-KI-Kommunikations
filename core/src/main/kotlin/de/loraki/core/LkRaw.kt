package de.loraki.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LKR v1 – kompakter Binärrahmen für MeshCore-Rohdatenpakete (Server -> Handy), unverschlüsselt aber beglaubigt (HMAC-SHA256, 4 Byte).
 * Gegenstück: bridge/lkbridge/rawframe.py. Aufbau: 0xA7 | Version<<4|Poll | msgId(2) | idx(2) | total(2) | Daten (≤ 148) | Tag(4).
 */
object LkRaw {
    private const val MAGIC = 0xA7
    private const val VERSION = 1
    const val HEADER = 8
    const val TAG = 4
    const val MAX_CHUNK = 148

    fun deriveKey(secret: String): ByteArray = MessageDigest.getInstance("SHA-256").digest("lkr1|$secret".toByteArray())

    private fun tag(key: ByteArray, body: ByteArray, len: Int = body.size): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(body, 0, len)
        return mac.doFinal().copyOf(TAG)
    }

    fun build(key: ByteArray, msgId: Int, idx: Int, total: Int, chunk: ByteArray, poll: Boolean): ByteArray {
        require(msgId in 0..Lkp.MAX_NUM && idx in 0 until total && total <= Lkp.MAX_NUM && chunk.size <= MAX_CHUNK) { "Ungültige Rahmenwerte" }
        val body = ByteArray(HEADER + chunk.size)
        body[0] = MAGIC.toByte(); body[1] = ((VERSION shl 4) or (if (poll) 1 else 0)).toByte()
        body[2] = (msgId shr 8).toByte(); body[3] = msgId.toByte()
        body[4] = (idx shr 8).toByte(); body[5] = idx.toByte()
        body[6] = (total shr 8).toByte(); body[7] = total.toByte()
        chunk.copyInto(body, HEADER)
        return body + tag(key, body)
    }

    /** Fragment, wenn Magic, Version und Echtheits-Tag stimmen; sonst null (Fremd-/Fälschungs-/Störpaket). */
    fun parse(key: ByteArray, frame: ByteArray): Lkp.Frame.Data? {
        if (frame.size < HEADER + TAG || (frame[0].toInt() and 0xFF) != MAGIC || (frame[1].toInt() and 0xFF) shr 4 != VERSION) return null
        val bodyLen = frame.size - TAG
        if (!MessageDigest.isEqual(tag(key, frame, bodyLen), frame.copyOfRange(bodyLen, frame.size))) return null
        fun u16(i: Int) = ((frame[i].toInt() and 0xFF) shl 8) or (frame[i + 1].toInt() and 0xFF)
        val id = u16(2); val idx = u16(4); val total = u16(6)
        if (total == 0 || idx >= total || id > Lkp.MAX_NUM || total > Lkp.MAX_NUM) return null
        return Lkp.Frame.Data(id, idx, total, frame.copyOfRange(HEADER, bodyLen), poll = frame[1].toInt() and 1 != 0)
    }
}
