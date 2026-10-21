package de.loraki.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MeshCore-Companion-Protokoll (BLE: Nordic UART Service, ein Frame pro Schreib-/Notify-Vorgang).
 * Layouts abgeleitet aus der Referenzbibliothek meshcore_py; gegen die eigene Firmware verifizieren.
 */
object Companion {
    const val NUS_SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val NUS_RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // App -> Node (write)
    const val NUS_TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // Node -> App (notify)

    private const val CMD_APP_START = 1
    private const val CMD_SEND_TXT_MSG = 2
    private const val CMD_GET_CONTACTS = 4
    private const val CMD_SEND_SELF_ADVERT = 7
    private const val CMD_SYNC_NEXT_MESSAGE = 10

    fun appStart(appName: String = "lkapp"): ByteArray =
        byteArrayOf(CMD_APP_START.toByte(), 3) + ByteArray(6) { 0x20 } + appName.toByteArray()

    fun getContacts(): ByteArray = byteArrayOf(CMD_GET_CONTACTS.toByte())
    fun syncNextMessage(): ByteArray = byteArrayOf(CMD_SYNC_NEXT_MESSAGE.toByte())
    fun sendSelfAdvert(flood: Boolean): ByteArray = byteArrayOf(CMD_SEND_SELF_ADVERT.toByte(), if (flood) 1 else 0)

    fun sendText(pubKey: ByteArray, text: String, timestampS: Long, attempt: Int = 0): ByteArray {
        require(pubKey.size >= 6)
        val t = text.toByteArray()
        val b = ByteBuffer.allocate(3 + 4 + 6 + t.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(CMD_SEND_TXT_MSG.toByte()).put(0).put(attempt.toByte())
        b.putInt(timestampS.toInt()).put(pubKey, 0, 6).put(t)
        return b.array()
    }

    sealed class Incoming {
        object Ok : Incoming()
        data class Error(val code: Int?) : Incoming()
        data class ContactsStart(val count: Long) : Incoming()
        data class Contact(val publicKey: ByteArray, val type: Int, val name: String, val lastAdvert: Long) : Incoming()
        object ContactsEnd : Incoming()
        data class SelfInfo(
            val txPowerDbm: Int, val maxTxPowerDbm: Int, val publicKey: ByteArray,
            val radio: RadioParams, val name: String,
            /** Vom Node gemeldete Position (GPS am Node bzw. eingestellte Position); null, wenn 0/0 oder nicht gesetzt. */
            val latitude: Double? = null, val longitude: Double? = null,
        ) : Incoming()
        data class MsgSent(val expectedAck: ByteArray, val suggestedTimeoutMs: Long) : Incoming()
        data class TextMsg(val pubKeyPrefixHex: String, val text: String, val snr: Double?) : Incoming()
        object NoMoreMessages : Incoming()
        object MessagesWaiting : Incoming()
        data class SendConfirmed(val ack: ByteArray) : Incoming()
        data class NewAdvert(val contact: Contact) : Incoming()
        data class Other(val code: Int) : Incoming()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun readContact(b: ByteBuffer): Incoming.Contact {
        val key = ByteArray(32).also { b.get(it) }
        val type = b.get().toInt() and 0xFF
        b.get(); b.get() // flags, path_len
        b.position(b.position() + 64) // out_path
        val nameBytes = ByteArray(32).also { b.get(it) }
        val name = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
        val lastAdvert = b.int.toLong() and 0xFFFFFFFFL
        return Incoming.Contact(key, type, name, lastAdvert)
    }

    fun parse(data: ByteArray): Incoming {
        if (data.isEmpty()) return Incoming.Other(-1)
        val code = data[0].toInt() and 0xFF
        val b = ByteBuffer.wrap(data, 1, data.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            when (code) {
                0 -> Incoming.Ok
                1 -> Incoming.Error(if (b.hasRemaining()) b.get().toInt() and 0xFF else null)
                2 -> Incoming.ContactsStart(b.int.toLong() and 0xFFFFFFFFL)
                3 -> readContact(b)
                4 -> Incoming.ContactsEnd
                5 -> {
                    b.get() // adv_type
                    val tx = b.get().toInt() and 0xFF
                    val maxTx = b.get().toInt() and 0xFF
                    val key = ByteArray(32).also { b.get(it) }
                    val latE6 = b.int; val lonE6 = b.int
                    b.position(b.position() + 4) // multi_acks, loc_policy, telemetry, manual_add
                    val freq = (b.int.toLong() and 0xFFFFFFFFL) / 1000.0
                    val bw = (b.int.toLong() and 0xFFFFFFFFL) / 1000.0
                    val sf = b.get().toInt() and 0xFF
                    val cr = b.get().toInt() and 0xFF
                    val name = String(ByteArray(b.remaining()).also { b.get(it) }, Charsets.UTF_8)
                    val hasPos = (latE6 != 0 || lonE6 != 0) && Math.abs(latE6) <= 90_000_000 && Math.abs(lonE6) <= 180_000_000
                    Incoming.SelfInfo(tx, maxTx, key, RadioParams(freq, bw, sf, cr, txPowerDbm = tx), name,
                        if (hasPos) latE6 / 1e6 else null, if (hasPos) lonE6 / 1e6 else null)
                }
                6 -> {
                    b.get() // type
                    val ack = ByteArray(4).also { b.get(it) }
                    Incoming.MsgSent(ack, b.int.toLong() and 0xFFFFFFFFL)
                }
                7, 16 -> {
                    var snr: Double? = null
                    if (code == 16) { snr = b.get() / 4.0; b.get(); b.get() }
                    val prefix = ByteArray(6).also { b.get(it) }
                    b.get() // path_len
                    val txtType = b.get().toInt() and 0xFF
                    b.int // sender timestamp
                    if (txtType == 2) b.position(b.position() + 4)
                    val text = String(ByteArray(b.remaining()).also { b.get(it) }, Charsets.UTF_8)
                    if (txtType != 0 && txtType != 2) Incoming.Other(code) else Incoming.TextMsg(prefix.hex(), text, snr)
                }
                10 -> Incoming.NoMoreMessages
                0x82 -> Incoming.SendConfirmed(ByteArray(minOf(4, b.remaining())).also { b.get(it) })
                0x83 -> Incoming.MessagesWaiting
                0x8A -> Incoming.NewAdvert(readContact(b))
                else -> Incoming.Other(code)
            }
        } catch (e: java.nio.BufferUnderflowException) {
            Incoming.Other(code)
        }
    }
}
