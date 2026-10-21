package de.loraki.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Setzt Fragmente zusammen und erzeugt ACK-Texte. */
class Receiver(maxObjectBytes: Int = 200_000, private val ttlS: Double = 6 * 3600.0,
               private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private class Partial(val total: Int, var updated: Double) { val parts = HashMap<Int, ByteArray>() }
    class Result(val reply: String?, val envelope: ByteArray?)

    private val maxFragments = maxOf(1, maxObjectBytes / Lkp.FRAGMENT_BYTES)
    private val active = HashMap<Pair<String, Int>, Partial>()
    private val done = LinkedHashMap<Pair<String, Int>, Int>()

    @Synchronized fun onFrame(peer: String, f: Lkp.Frame.Data): Result {
        val now = clock()
        active.entries.removeAll { now - it.value.updated > ttlS }
        val key = peer to f.msgId
        done[key]?.let { return Result(if (f.poll) Lkp.formatAck(f.msgId, it, emptyList()) else null, null) }
        if (f.total > maxFragments) return Result(Lkp.formatAbort(f.msgId), null)
        var p = active[key]
        if (p == null || p.total != f.total) { p = Partial(f.total, now); active[key] = p }
        p.parts[f.idx] = f.body
        p.updated = now
        if (p.parts.size == p.total) {
            val out = java.io.ByteArrayOutputStream()
            for (i in 0 until p.total) out.write(p.parts[i]!!)
            active.remove(key)
            done[key] = p.total
            while (done.size > 256) done.remove(done.keys.first())
            return Result(Lkp.formatAck(f.msgId, p.total, emptyList()), out.toByteArray())
        }
        if (f.poll) {
            var hi = p.parts.keys.max() + 1
            var missing = (0 until hi).filter { it !in p.parts }
            if (missing.size > Lkp.MAX_MISSING) {
                // Liste passt nicht in ein Paket: hi bis zur ersten ausgelassenen Lücke kürzen
                hi = missing[Lkp.MAX_MISSING]
                missing = missing.take(Lkp.MAX_MISSING)
            }
            return Result(Lkp.formatAck(f.msgId, hi, missing), null)
        }
        return Result(null, null)
    }
}

data class Progress(val peer: String, val msgId: Int, val sent: Int, val acked: Int, val total: Int)

/** Sendet ein Envelope fragmentiert, hält Duty-Cycle ein und wartet auf ACKs (Selective Repeat). */
class Sender(
    private val sendText: suspend (peer: String, text: String) -> Unit,
    private val limiter: DutyCycleLimiter,
    private val radio: () -> RadioParams,
    private val ackTimeoutS: Double = 90.0,
    private val maxRetries: Int = 6,
    private val onProgress: ((Progress) -> Unit)? = null,
) {
    private val waiting = ConcurrentHashMap<Pair<String, Int>, Channel<Lkp.Frame>>()

    /** Einzelnes Textpaket unter Duty-Cycle-Kontrolle senden. */
    suspend fun tx(peer: String, text: String) {
        limiter.acquire(Airtime.textSeconds(text, radio()))
        var last: Exception? = null
        repeat(3) {
            try { sendText(peer, text); return } catch (e: java.io.IOException) { last = e; delay(5000) }
        }
        throw last ?: java.io.IOException("Node nicht erreichbar")
    }

    fun onAck(peer: String, f: Lkp.Frame) {
        waiting[peer to f.msgId]?.trySend(f)
    }

    /** [probeFirst]: zuerst nur das letzte Fragment als Poll senden, um den Stand des Empfängers zu erfahren (Wiederaufnahme). */
    suspend fun send(peer: String, msgId: Int, envelope: ByteArray, probeFirst: Boolean = false): Boolean {
        val chunks = Lkp.fragment(envelope)
        val total = chunks.size
        val key = peer to msgId
        val ch = Channel<Lkp.Frame>(Channel.UNLIMITED)
        waiting[key] = ch
        try {
            var received = emptySet<Int>()
            var queue = ArrayDeque((0 until total).toList())
            var sent = 0
            var probe = probeFirst && total > 1
            while (received.size < total) {
                val batch = if (probe) listOf(total - 1) else List(minOf(Lkp.WINDOW, queue.size)) { queue.removeFirst() }
                probe = false
                val texts = batch.mapIndexed { n, i ->
                    Lkp.formatData(msgId, i, total, chunks[i], poll = n == batch.size - 1)
                }
                for (t in texts) {
                    tx(peer, t)
                    sent++
                    onProgress?.invoke(Progress(peer, msgId, sent, received.size, total))
                }
                var retries = maxRetries
                var ack: Lkp.Frame
                while (true) {
                    try {
                        ack = withTimeout((ackTimeoutS * 1000).toLong()) { ch.receive() }
                        break
                    } catch (e: TimeoutCancellationException) {
                        if (--retries < 0) return false
                        tx(peer, texts.last())
                    }
                }
                when (ack) {
                    is Lkp.Frame.Abort -> return false
                    is Lkp.Frame.Ack -> {
                        received = (0 until minOf(ack.hi, total)).toSet() - ack.missing.toSet()
                        queue = ArrayDeque((0 until total).filter { it !in received })
                        onProgress?.invoke(Progress(peer, msgId, sent, received.size, total))
                    }
                    else -> {}
                }
            }
            return true
        } finally {
            waiting.remove(key)
        }
    }
}
