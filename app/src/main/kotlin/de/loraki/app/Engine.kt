package de.loraki.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.loraki.app.ble.LinkState
import de.loraki.app.ble.MeshSession
import de.loraki.app.data.ChatMessage
import de.loraki.app.data.Settings
import de.loraki.app.data.Status
import de.loraki.app.data.Store
import de.loraki.core.Airtime
import de.loraki.core.DutyCycleLimiter
import de.loraki.core.LimiterState
import de.loraki.core.Lkp
import de.loraki.core.RadioParams
import de.loraki.core.Receiver
import de.loraki.core.Sender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** Funk-/Rechtslage für die UI. */
data class RadioInfo(
    val radio: RadioParams,
    val duty: Double,
    val maxErpMw: Int,
    val knownBand: Boolean,
    val budgetS: Double,
    val warnings: List<String>,
)

class Engine(
    private val ctx: Context,
    private val scope: CoroutineScope,
    val store: Store,
    val settings: Settings,
    val session: MeshSession,
) {
    private val receiver = Receiver()
    private var limiter: DutyCycleLimiter? = null
    private var sender: Sender? = null
    private val outbox = Channel<String>(Channel.UNLIMITED)

    private val _radioInfo = MutableStateFlow<RadioInfo?>(null)
    val radioInfo: StateFlow<RadioInfo?> = _radioInfo
    private val _usedS = MutableStateFlow(0.0)
    /** Verbrauchte Airtime im gleitenden 1-h-Fenster (Sekunden). */
    val usedS: StateFlow<Double> = _usedS

    init {
        createChannel()
        scope.launch { session.texts.collect { onText(it.pubKeyPrefixHex, it.text) } }
        scope.launch { session.state.collect { if (it == LinkState.READY) configureRadio() } }
        scope.launch { worker() }
        // wartende Nachrichten aus früheren Sitzungen erneut einreihen
        store.messages.value.filter { it.outgoing && it.status == Status.QUEUED }.forEach { outbox.trySend(it.id) }
    }

    // ------------------------------------------------------------ Funk-Konfiguration

    private fun configureRadio() {
        val si = session.selfInfo.value ?: return
        val r = si.radio
        val band = Airtime.subband(r.freqMhz)
        var duty = band.duty
        if (settings.dutyOverridePercent > 0f) duty = minOf(duty, settings.dutyOverridePercent / 100.0)
        val warn = mutableListOf<String>()
        if (!band.known) warn += "Frequenz %.3f MHz liegt in keinem bekannten SRD-Subband – strengster Duty-Cycle (0,1 %%) wird angesetzt.".format(r.freqMhz)
        if (Airtime.dbmToMw(si.txPowerDbm.toDouble()) > band.maxErpMw)
            warn += "Sendeleistung ${si.txPowerDbm} dBm (%.0f mW) überschreitet die erlaubte ERP von ${band.maxErpMw} mW in diesem Subband. Leistung am Node senken (Antennengewinn beachten)!".format(Airtime.dbmToMw(si.txPowerDbm.toDouble()))
        val lim = DutyCycleLimiter(duty, settings.margin.toDouble(), onChange = { saveLimiter(it) })
        loadLimiter()?.let { lim.restore(it) }
        limiter = lim
        sender = Sender(
            sendText = { peer, text -> session.sendText(peer, text) },
            limiter = lim,
            radio = { session.selfInfo.value?.radio ?: r },
            ackTimeoutS = 120.0,
            maxRetries = 8,
            onProgress = { p ->
                store.updateWhere({ it.outgoing && it.msgId == p.msgId && it.status == Status.SENDING }) {
                    it.copy(acked = p.acked, total = p.total)
                }
                _usedS.value = lim.usedS()
            },
        )
        _usedS.value = lim.usedS()
        _radioInfo.value = RadioInfo(r, duty, band.maxErpMw, band.known, lim.budgetS(), warn)
    }

    private fun saveLimiter(s: LimiterState) {
        val a = JSONArray()
        s.events.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        settings.limiterJson = org.json.JSONObject().put("events", a).put("next", s.nextStart).toString()
        _usedS.value = limiter?.usedS() ?: 0.0
    }

    private fun loadLimiter(): LimiterState? = try {
        val o = org.json.JSONObject(settings.limiterJson ?: return null)
        val a = o.getJSONArray("events")
        LimiterState((0 until a.length()).map { a.getJSONArray(it).let { e -> e.getDouble(0) to e.getDouble(1) } }, o.getDouble("next"))
    } catch (e: Exception) { null }

    /** Geschätzte Übertragungsdauer eines Objekts in Sekunden (null, solange kein Node verbunden war). */
    fun estimateSeconds(payloadBytes: Int): Double? {
        val info = _radioInfo.value ?: return null
        return Airtime.estimateTransferSeconds(payloadBytes + 12, info.radio, info.duty, settings.margin.toDouble())
    }

    /** Bucht die Airtime eines Adverts (~50 Byte) im Limiter, bevor er gesendet wird. */
    suspend fun advertAirtimeBudget() {
        val lim = limiter ?: return
        val r = session.selfInfo.value?.radio ?: return
        lim.acquire(Airtime.seconds(60, r))
    }

    // ------------------------------------------------------------ Senden

    fun submitText(conv: Int, text: String) {
        val m = ChatMessage(conv = conv, outgoing = true, kind = Lkp.KIND_TEXT, text = text, size = text.toByteArray().size, status = Status.QUEUED)
        store.add(m); outbox.trySend(m.id)
    }

    fun submitAttachment(conv: Int, name: String, mime: String, data: ByteArray, isImage: Boolean) {
        val f = File(store.attachmentDir, UUID.randomUUID().toString()).also { it.writeBytes(data) }
        val m = ChatMessage(
            conv = conv, outgoing = true, kind = if (isImage) Lkp.KIND_IMAGE else Lkp.KIND_FILE,
            fileName = name, mime = mime, filePath = f.path, size = data.size, status = Status.QUEUED,
        )
        store.add(m); outbox.trySend(m.id)
    }

    fun resetConversation(conv: Int) {
        val m = ChatMessage(conv = conv, outgoing = true, kind = Lkp.KIND_RESET, text = "Kontext zurücksetzen", status = Status.QUEUED)
        store.add(m); outbox.trySend(m.id)
    }

    fun retry(id: String) {
        store.update(id) { it.copy(status = Status.QUEUED) }
        outbox.trySend(id)
    }

    private suspend fun worker() {
        for (id in outbox) {
            session.state.first { it == LinkState.READY }
            val m = store.get(id) ?: continue
            if (m.status != Status.QUEUED) continue
            try { sendOne(m) } catch (e: Exception) {
                store.update(id) { it.copy(status = Status.FAILED) }
            }
        }
    }

    private fun newMsgId(): Int {
        val used = store.messages.value.filter { it.outgoing }.map { it.msgId }.toSet()
        while (true) { val n = Random.nextInt(Lkp.MAX_NUM + 1); if (n !in used) return n }
    }

    private suspend fun sendOne(m0: ChatMessage) {
        val snd = sender ?: error("Funk nicht konfiguriert")
        val peer = settings.serverKeyHex?.take(12) ?: error("Kein Server-Kontakt gewählt")
        val payload = when (m0.kind) {
            Lkp.KIND_TEXT, Lkp.KIND_RESET -> m0.text.toByteArray()
            else -> Lkp.packFile(m0.fileName ?: "datei", m0.mime ?: "application/octet-stream", File(m0.filePath!!).readBytes())
        }
        val env = Lkp.buildEnvelope(m0.kind, m0.conv, payload)
        val resume = m0.msgId >= 0
        val msgId = if (resume) m0.msgId else newMsgId()
        store.update(m0.id) { it.copy(status = Status.SENDING, msgId = msgId) }
        val ok = snd.send(peer, msgId, env, probeFirst = resume)
        store.update(m0.id) {
            it.copy(status = when {
                !ok -> Status.FAILED
                it.kind == Lkp.KIND_TEXT -> Status.WAITING_AI
                else -> Status.DELIVERED
            }, acked = if (ok) it.total else it.acked)
        }
    }

    // ------------------------------------------------------------ Empfangen

    private fun isServer(prefixHex: String): Boolean {
        val k = settings.serverKeyHex ?: return false
        return k.startsWith(prefixHex.lowercase())
    }

    private fun onText(prefixHex: String, text: String) {
        if (!isServer(prefixHex)) return
        val peer = prefixHex.lowercase()
        when (val f = Lkp.parseFrame(text)) {
            is Lkp.Frame.Data -> {
                val r = receiver.onFrame(peer, f)
                r.reply?.let { reply -> scope.launch { runCatching { sender?.tx(settings.serverKeyHex!!.take(12), reply) } } }
                r.envelope?.let { onEnvelope(it) }
            }
            is Lkp.Frame.Ack, is Lkp.Frame.Abort -> sender?.onAck(settings.serverKeyHex!!.take(12), f)
            null -> addIncoming(settings.currentConv, Lkp.KIND_REPLY, text) // Klartext vom Server
        }
    }

    private fun onEnvelope(raw: ByteArray) {
        val env = try { Lkp.parseEnvelope(raw) } catch (e: Lkp.ProtocolException) { return }
        if (env.kind == Lkp.KIND_REPLY || env.kind == Lkp.KIND_STATUS) addIncoming(env.conv, env.kind, String(env.payload))
    }

    private fun addIncoming(conv: Int, kind: Int, text: String) {
        store.updateWhere({ it.outgoing && it.conv == conv && it.status == Status.WAITING_AI }) { it.copy(status = Status.DELIVERED) }
        store.add(ChatMessage(conv = conv, outgoing = false, kind = kind, text = text, size = text.length, status = Status.RECEIVED))
        notifyReply(text)
    }

    // ------------------------------------------------------------ Benachrichtigung

    private fun createChannel() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reply", "KI-Antworten", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("link", "Funkverbindung", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notifyReply(text: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        val n = NotificationCompat.Builder(ctx, "reply")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Antwort der KI")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(500)))
            .setAutoCancel(true)
            .setContentIntent(android.app.PendingIntent.getActivity(ctx, 0,
                android.content.Intent(ctx, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1, n) }
    }
}
