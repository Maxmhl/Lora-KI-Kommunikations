package de.loraki.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.loraki.app.ble.FoundDevice
import de.loraki.app.ble.LinkState
import de.loraki.app.data.Attachments
import de.loraki.app.data.ChatMessage
import de.loraki.app.data.Conversation
import de.loraki.app.data.Prepared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = (app as App).graph
    val settings = g.settings
    val engine = g.engine
    val session = g.session

    val linkState: StateFlow<LinkState> = session.state
    val linkError = session.lastError
    val selfInfo = session.selfInfo
    val contacts = session.contacts
    val radioInfo = engine.radioInfo
    val usedS = engine.usedS
    val conversations: StateFlow<List<Conversation>> = g.store.conversations
    val models = engine.models

    private val _conv = MutableStateFlow(settings.currentConv)
    val currentConv: StateFlow<Int> = _conv
    val messages: StateFlow<List<ChatMessage>> = combine(g.store.messages, _conv) { all, c -> all.filter { it.conv == c } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pending = MutableStateFlow<List<Prepared>>(emptyList())
    val toast = MutableStateFlow<String?>(null)
    val bleLog = de.loraki.app.ble.BleLog.lines
    fun clearLog() = de.loraki.app.ble.BleLog.clear()

    val found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    private var scanJob: Job? = null

    fun selectConversation(id: Int) { _conv.value = id; settings.currentConv = id }
    fun newConversation() { selectConversation(g.store.newConversation().id) }
    fun deleteConversation() {
        val id = _conv.value
        g.store.deleteConversation(id)
        selectConversation(g.store.conversations.value.first().id)
    }
    fun resetContext() = engine.resetConversation(_conv.value)
    fun toggleKnowledgeBase() { val c = conversations.value.firstOrNull { it.id == _conv.value } ?: return; engine.setKnowledgeBase(c.id, !c.kb) }
    /** Neuer Chat mit NOMAD-Wissensdatenbank (für den Weg ohne WLAN). */
    fun newKnowledgeBaseChat() { newConversation(); engine.setKnowledgeBase(_conv.value, true) }
    fun requestModels() = engine.requestModels(_conv.value)
    fun selectModel(name: String?) = engine.selectModel(_conv.value, name)

    fun send(text: String) {
        val conv = _conv.value
        val atts = pending.value
        pending.value = emptyList()
        atts.forEach { engine.submitAttachment(conv, it.name, it.mime, it.bytes, it.isImage) }
        if (text.isNotBlank()) engine.submitText(conv, text.trim())
    }

    fun retry(id: String) = engine.retry(id)
    fun removePending(p: Prepared) { pending.value = pending.value - p }

    fun addImage(uri: Uri) = prepare {
        Attachments.prepareImage(getApplication(), uri, settings.imageMaxDim, settings.imageQuality, settings.maxAttachKb * 1024)
    }
    fun addFile(uri: Uri) = prepare { Attachments.prepareFile(getApplication(), uri, settings.maxAttachKb * 1024) }

    private fun prepare(block: () -> Prepared) {
        viewModelScope.launch {
            try { pending.value = pending.value + withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) { toast.value = e.message ?: "Anhang nicht verwendbar" }
        }
    }

    fun estimate(bytes: Int): Double? = engine.estimateSeconds(bytes)

    // ------------------------------------------------------------ Verbindung

    fun startScan() {
        scanJob?.cancel()
        found.value = emptyList()
        scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                session.ble.scan().collect { d -> found.value = (found.value.filter { it.address != d.address } + d).sortedWith(compareByDescending<FoundDevice> { it.likely }.thenByDescending { it.rssi }) }
            } catch (e: Exception) { toast.value = e.message } finally { scanning.value = false }
        }
    }

    fun stopScan() { scanJob?.cancel(); scanning.value = false }

    fun connect(d: FoundDevice) {
        stopScan()
        settings.deviceAddress = d.address
        settings.deviceName = d.name
        g.connect()
    }

    fun reconnect() = g.connect()
    fun disconnect() = g.disconnect()
    fun hasBlePermission() = g.hasBlePermission()

    fun selectServer(key: ByteArray, name: String) {
        settings.serverKeyHex = key.joinToString("") { "%02x".format(it) }
        settings.serverName = name
    }

    fun sendAdvert() = viewModelScope.launch {
        try {
            g.engine.advertAirtimeBudget()
            session.sendAdvert(true)
            toast.value = "Advert gesendet"
        } catch (e: Exception) { toast.value = e.message }
    }

    fun refreshContacts() = viewModelScope.launch { runCatching { session.refreshContacts() } }
}
