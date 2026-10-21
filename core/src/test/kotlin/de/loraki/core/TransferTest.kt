package de.loraki.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransferTest {
    private class Node(scope: TestScope, val name: String, val drop: (String) -> Boolean, val link: Link) {
        val receiver = Receiver(clock = { scope.currentTime / 1000.0 })
        val completed = mutableListOf<ByteArray>()
        val sender = Sender(
            sendText = { _, text -> link.deliver(this, text) },
            limiter = DutyCycleLimiter(0.10, clock = { scope.currentTime / 1000.0 }, sleep = { delay((it * 1000).toLong() + 1) }),
            radio = { RadioParams() }, ackTimeoutS = 5.0, maxRetries = 40,
        )
        suspend fun onText(peer: String, text: String) {
            when (val f = Lkp.parseFrame(text)) {
                is Lkp.Frame.Data -> {
                    val r = receiver.onFrame(peer, f)
                    r.reply?.let { sender.tx(peer, it) }
                    r.envelope?.let { completed += it }
                }
                null -> {}
                else -> sender.onAck(peer, f)
            }
        }
    }

    private class Link(val scope: TestScope) {
        lateinit var a: Node
        lateinit var b: Node
        fun deliver(from: Node, text: String) {
            if (from.drop(text)) return
            val to = if (from === a) b else a
            scope.launch { to.onText(from.name, text) }
        }
    }

    private fun TestScope.pair(dropA: (String) -> Boolean = { false }, dropB: (String) -> Boolean = { false }): Pair<Node, Node> {
        val link = Link(this)
        val a = Node(this, "A", dropA, link)
        val b = Node(this, "B", dropB, link)
        link.a = a; link.b = b
        return a to b
    }

    private fun randomBytes(n: Int, seed: Long = 7) = ByteArray(n).also { Random(seed).nextBytes(it) }

    @Test fun cleanTransferKeepsDutyCycle() = runTest {
        val (a, b) = pair()
        val env = Lkp.buildEnvelope(Lkp.KIND_FILE, 1, randomBytes(2000))
        assertTrue(a.sender.send("B", 5, env))
        assertContentEquals(env, b.completed.single())
        assertTrue(currentTime > 60_000)
    }

    @Test fun lossyLink() = runTest {
        val rnd = Random(1)
        val (a, b) = pair({ rnd.nextDouble() < 0.25 }, { rnd.nextDouble() < 0.25 })
        val env = Lkp.buildEnvelope(Lkp.KIND_FILE, 0, randomBytes(1500))
        assertTrue(a.sender.send("B", 9, env))
        assertContentEquals(env, b.completed.single())
    }

    @Test fun duplicateNotRedelivered() = runTest {
        val (a, b) = pair()
        val env = Lkp.buildEnvelope(Lkp.KIND_TEXT, 0, "hi".toByteArray())
        assertTrue(a.sender.send("B", 1, env))
        assertTrue(a.sender.send("B", 1, env))
        assertEquals(1, b.completed.size)
    }

    @Test fun givesUpWithoutAck() = runTest {
        val (a, _) = pair(dropA = { true })
        assertFalse(Sender({ _, _ -> }, DutyCycleLimiter(0.1, clock = { currentTime / 1000.0 }, sleep = { delay((it * 1000).toLong() + 1) }),
            { RadioParams() }, ackTimeoutS = 1.0, maxRetries = 2)
            .send("B", 2, Lkp.buildEnvelope(Lkp.KIND_TEXT, 0, "x".toByteArray())))
    }

    @Test fun ackTruncationNeverOverclaims() {
        val rx = Receiver()
        val r = rx.onFrame("P", Lkp.Frame.Data(1, 39, 40, ByteArray(1), poll = true))
        val a = Lkp.parseFrame(r.reply!!) as Lkp.Frame.Ack
        assertTrue(((0 until a.hi).toSet() - a.missing.toSet()).isEmpty())
    }

    @Test fun probeResumeSkipsKnownFragments() = runTest {
        val (a, b) = pair()
        val env = Lkp.buildEnvelope(Lkp.KIND_FILE, 1, randomBytes(900))
        val chunks = Lkp.fragment(env)
        // Empfänger kennt bereits die ersten 5 Fragmente (z. B. App-Neustart während der Übertragung)
        for (i in 0 until 5) b.receiver.onFrame("A", Lkp.Frame.Data(5, i, chunks.size, chunks[i], poll = false))
        val sentBefore = b.completed.size
        assertTrue(a.sender.send("B", 5, env, probeFirst = true))
        assertContentEquals(env, b.completed.last())
        assertEquals(sentBefore + 1, b.completed.size)
    }

    @Test fun limiterBudget() = runTest {
        val lim = DutyCycleLimiter(0.01, margin = 1.0, clock = { currentTime / 1000.0 }, sleep = { delay((it * 1000).toLong() + 1) })
        val times = mutableListOf<Double>()
        repeat(60) { lim.acquire(1.5); times += currentTime / 1000.0 }
        for (t in times) assertTrue(times.count { it > t - 3600 && it <= t } * 1.5 <= 36.0 + 1e-6)
        assertTrue(currentTime / 1000.0 > 3600)
    }
}

class CompanionTest {
    @Test fun sendTextLayout() {
        val key = ByteArray(32) { it.toByte() }
        val f = Companion.sendText(key, "hi", 0x01020304L)
        assertContentEquals(byteArrayOf(2, 0, 0, 4, 3, 2, 1, 0, 1, 2, 3, 4, 5, 'h'.code.toByte(), 'i'.code.toByte()), f)
    }

    @Test fun parseTextV3() {
        val data = byteArrayOf(16, 8, 0, 0, 1, 2, 3, 4, 5, 6, 0, 0, 0, 0, 0, 0) + "~Hallo".toByteArray()
        val m = Companion.parse(data) as Companion.Incoming.TextMsg
        assertEquals("010203040506", m.pubKeyPrefixHex)
        assertEquals("~Hallo", m.text)
        assertEquals(2.0, m.snr)
    }

    @Test fun parseRawPush() {
        val data = byteArrayOf(0x84.toByte(), 12, (-95).toByte(), 0xFF.toByte()) + byteArrayOf(1, 2, 3, 4)
        val r = Companion.parse(data) as Companion.Incoming.RawData
        assertEquals(3.0, r.snr); assertEquals(-95, r.rssi)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), r.payload)
    }

    @Test fun rawFramesFeedTheSameReceiver() {
        val key = LkRaw.deriveKey("geheimnis-geheimnis")
        val env = Lkp.buildEnvelope(Lkp.KIND_NOMAD_RESP, 9, ByteArray(400) { it.toByte() })
        val chunks = env.toList().chunked(LkRaw.MAX_CHUNK).map { it.toByteArray() }
        val rx = Receiver()
        var done: ByteArray? = null
        for ((i, c) in chunks.withIndex()) {
            val f = LkRaw.parse(key, LkRaw.build(key, 5, i, chunks.size, c, poll = i == chunks.size - 1))!!
            val r = rx.onFrame("srv", f)
            r.envelope?.let { done = it }
        }
        assertContentEquals(env, done)
        assertTrue(Airtime.rawPacketBytes(160) < Airtime.meshPacketBytes(Lkp.TEXT_MAX))
    }

    @Test fun parseSelfInfo() {
        val bb = java.nio.ByteBuffer.allocate(1 + 3 + 32 + 8 + 4 + 8 + 2 + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put(5).put(1).put(14).put(22).put(ByteArray(32)).putInt(49_407_500).putInt(8_693_500).put(ByteArray(4))
        bb.putInt(869618).putInt(62500).put(8).put(8).put("Test".toByteArray())
        val s = Companion.parse(bb.array()) as Companion.Incoming.SelfInfo
        assertEquals(869.618, s.radio.freqMhz, 1e-9)
        assertEquals(62.5, s.radio.bwKhz, 1e-9)
        assertEquals(8, s.radio.sf)
        assertEquals(14, s.txPowerDbm)
        assertEquals("Test", s.name)
        assertEquals(49.4075, s.latitude!!, 1e-9)       // Node-GPS
        assertEquals(8.6935, s.longitude!!, 1e-9)
    }

    @Test fun selfInfoWithoutPositionHasNull() {
        val bb = java.nio.ByteBuffer.allocate(1 + 3 + 32 + 8 + 4 + 8 + 2 + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put(5).put(1).put(14).put(22).put(ByteArray(32)).putInt(0).putInt(0).put(ByteArray(4))
        bb.putInt(869618).putInt(62500).put(8).put(8).put("Test".toByteArray())
        val s = Companion.parse(bb.array()) as Companion.Incoming.SelfInfo
        assertNull(s.latitude); assertNull(s.longitude)
    }
}
