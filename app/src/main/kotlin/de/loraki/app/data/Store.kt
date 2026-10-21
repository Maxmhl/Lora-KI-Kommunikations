package de.loraki.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Status { QUEUED, SENDING, DELIVERED, WAITING_AI, FAILED, RECEIVED }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conv: Int,
    val outgoing: Boolean,
    val kind: Int,
    val text: String = "",
    val fileName: String? = null,
    val mime: String? = null,
    val filePath: String? = null,
    val size: Int = 0,
    val status: Status,
    val acked: Int = 0,
    val total: Int = 0,
    val msgId: Int = -1,
    val ts: Long = System.currentTimeMillis(),
)

data class Conversation(val id: Int, val title: String, val created: Long = System.currentTimeMillis())

/** Einfacher JSON-Speicher (Unterhaltungen + Nachrichten). */
class Store(ctx: Context) {
    private val file = File(ctx.filesDir, "store.json")
    val attachmentDir = File(ctx.filesDir, "att").apply { mkdirs() }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _convs = MutableStateFlow<List<Conversation>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    val conversations: StateFlow<List<Conversation>> = _convs

    init { load() }

    @Synchronized private fun load() {
        if (!file.exists()) { _convs.value = listOf(Conversation(0, "Unterhaltung 1")); return }
        try {
            val o = JSONObject(file.readText())
            _convs.value = o.getJSONArray("convs").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { c -> Conversation(c.getInt("id"), c.getString("title"), c.getLong("created")) } }
            }
            _messages.value = o.getJSONArray("msgs").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).toMsg() }
            }.map { if (it.status == Status.SENDING) it.copy(status = Status.QUEUED) else it }
        } catch (e: Exception) {
            _convs.value = listOf(Conversation(0, "Unterhaltung 1"))
        }
    }

    @Synchronized private fun save() {
        val o = JSONObject()
        o.put("convs", JSONArray(_convs.value.map { JSONObject().put("id", it.id).put("title", it.title).put("created", it.created) }))
        o.put("msgs", JSONArray(_messages.value.map { it.toJson() }))
        val tmp = File(file.parentFile, "store.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    @Synchronized fun add(m: ChatMessage) { _messages.value = _messages.value + m; save() }

    @Synchronized fun update(id: String, f: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) f(it) else it }
        save()
    }

    @Synchronized fun updateWhere(pred: (ChatMessage) -> Boolean, f: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (pred(it)) f(it) else it }
        save()
    }

    fun get(id: String): ChatMessage? = _messages.value.firstOrNull { it.id == id }

    @Synchronized fun newConversation(): Conversation {
        val id = (0..255).first { n -> _convs.value.none { it.id == n } }
        val c = Conversation(id, "Unterhaltung ${_convs.value.size + 1}")
        _convs.value = _convs.value + c; save()
        return c
    }

    @Synchronized fun deleteConversation(id: Int) {
        _messages.value.filter { it.conv == id }.forEach { it.filePath?.let { p -> File(p).delete() } }
        _messages.value = _messages.value.filter { it.conv != id }
        _convs.value = _convs.value.filter { it.id != id }.ifEmpty { listOf(Conversation(0, "Unterhaltung 1")) }
        save()
    }

    @Synchronized fun clearMessages(conv: Int) {
        _messages.value = _messages.value.filter { it.conv != conv }; save()
    }

    private fun ChatMessage.toJson() = JSONObject()
        .put("id", id).put("conv", conv).put("out", outgoing).put("kind", kind).put("text", text)
        .put("fn", fileName).put("mime", mime).put("path", filePath).put("size", size)
        .put("st", status.name).put("ack", acked).put("tot", total).put("mid", msgId).put("ts", ts)

    private fun JSONObject.toMsg() = ChatMessage(
        id = getString("id"), conv = getInt("conv"), outgoing = getBoolean("out"), kind = getInt("kind"),
        text = optString("text"), fileName = optString("fn").ifEmpty { null }, mime = optString("mime").ifEmpty { null },
        filePath = optString("path").ifEmpty { null }, size = optInt("size"), status = Status.valueOf(getString("st")),
        acked = optInt("ack"), total = optInt("tot"), msgId = optInt("mid", -1), ts = getLong("ts"),
    )
}
