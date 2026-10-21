package de.loraki.app.ble

import android.content.Context
import de.loraki.core.Companion
import de.loraki.core.Companion.Incoming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

enum class LinkState { DISCONNECTED, CONNECTING, READY }

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun String.unhex() = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }

/** Companion-Protokoll-Sitzung über [BleLink]: Befehle, Kontakte, Nachrichtenabruf. */
class MeshSession(ctx: Context, private val scope: CoroutineScope) {
    val ble = BleLink(ctx)
    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    val state: StateFlow<LinkState> = _state
    val selfInfo = MutableStateFlow<Incoming.SelfInfo?>(null)
    val contacts = MutableStateFlow<List<Incoming.Contact>>(emptyList())
    val lastError = MutableStateFlow<String?>(null)
    private val _texts = MutableSharedFlow<Incoming.TextMsg>(extraBufferCapacity = 64)
    val texts: SharedFlow<Incoming.TextMsg> = _texts

    private val responses = Channel<Incoming>(Channel.UNLIMITED)
    private val cmd = Mutex()
    private var reader: Job? = null
    private var watcher: Job? = null
    private val fetching = AtomicBoolean(false)

    suspend fun connect(address: String) {
        if (_state.value != LinkState.DISCONNECTED) return
        _state.value = LinkState.CONNECTING
        lastError.value = null
        try {
            ble.connect(address)
            startReader()
            val si = request(Companion.appStart()) as? Incoming.SelfInfo ?: throw IOException("Node antwortet nicht auf App-Start")
            selfInfo.value = si
            refreshContacts()
            _state.value = LinkState.READY
            scope.launch { fetchMessages() }
        } catch (e: Exception) {
            lastError.value = e.message
            disconnect()
            throw e
        }
    }

    fun disconnect() {
        reader?.cancel(); watcher?.cancel()
        ble.close()
        _state.value = LinkState.DISCONNECTED
    }

    private fun startReader() {
        reader?.cancel(); watcher?.cancel()
        reader = scope.launch { for (f in ble.frames) handle(Companion.parse(f)) }
        watcher = scope.launch {
            ble.connected.collect { c -> if (!c && _state.value != LinkState.DISCONNECTED) { lastError.value = "Verbindung verloren"; disconnect() } }
        }
    }

    private suspend fun handle(m: Incoming) {
        when (m) {
            is Incoming.MessagesWaiting -> scope.launch { fetchMessages() }
            is Incoming.SendConfirmed -> {}
            is Incoming.NewAdvert -> contacts.value = (contacts.value.filter { !it.publicKey.contentEquals(m.contact.publicKey) } + m.contact)
            else -> responses.trySend(m)
        }
    }

    private suspend fun request(frame: ByteArray, timeoutMs: Long = 15_000): Incoming = cmd.withLock {
        while (responses.tryReceive().isSuccess) { /* alte Antworten verwerfen */ }
        ble.write(frame)
        withTimeout(timeoutMs) { responses.receive() }
    }

    suspend fun refreshContacts() = cmd.withLock {
        while (responses.tryReceive().isSuccess) { }
        ble.write(Companion.getContacts())
        val list = mutableListOf<Incoming.Contact>()
        withTimeout(30_000) {
            while (true) {
                when (val r = responses.receive()) {
                    is Incoming.Contact -> list += r
                    is Incoming.ContactsEnd -> break
                    is Incoming.Error -> throw IOException("Kontaktliste: Fehler ${r.code}")
                    else -> {}
                }
            }
        }
        contacts.value = list
    }

    suspend fun fetchMessages() {
        if (!fetching.compareAndSet(false, true)) return
        try {
            while (true) {
                when (val r = request(Companion.syncNextMessage())) {
                    is Incoming.TextMsg -> _texts.emit(r)
                    is Incoming.Other -> continue // z. B. Kanalnachricht – überspringen
                    else -> break
                }
            }
        } catch (_: Exception) {
        } finally { fetching.set(false) }
    }

    /** Sendet eine Textnachricht (≤ ~150 Zeichen) an den Kontakt mit dem Schlüssel-Präfix [peerHex]. */
    suspend fun sendText(peerHex: String, text: String) {
        val key = contacts.value.firstOrNull { it.publicKey.hex().startsWith(peerHex.lowercase()) }?.publicKey
            ?: peerHex.unhex()
        when (val r = request(Companion.sendText(key, text, System.currentTimeMillis() / 1000))) {
            is Incoming.MsgSent -> {}
            is Incoming.Error -> throw IOException("Node meldet Fehler ${r.code ?: ""}".trim())
            else -> throw IOException("Unerwartete Antwort vom Node")
        }
    }

    suspend fun sendAdvert(flood: Boolean = true) {
        request(Companion.sendSelfAdvert(flood))
    }
}
