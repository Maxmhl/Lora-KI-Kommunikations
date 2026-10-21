package de.loraki.app.nomad

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.loraki.app.App
import de.loraki.core.NomadChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/** Spricht NOMADs Chat-API über das Gateway an (gleiche Sitzungen und Wissensdatenbank wie die NOMAD-Weboberfläche). */
class NomadChatClient(private val repo: NomadRepo) {
    @Volatile private var streamConn: HttpURLConnection? = null

    private fun fail(c: HttpURLConnection): Nothing {
        val msg = runCatching { JSONObject(c.errorStream?.bufferedReader()?.readText().orEmpty()).optString("message") }.getOrNull().orEmpty()
        throw IOException(when (c.responseCode) {
            401 -> "Token abgelehnt (401)"
            502 -> "Das Gateway erreicht NOMAD nicht – läuft NOMAD auf dem Server?"
            404 -> "NOMAD-KI nicht gefunden – ist der AI Assistant in NOMAD installiert?"
            else -> msg.ifEmpty { "Server antwortet mit ${c.responseCode}" }
        })
    }

    private fun call(path: String, method: String = "GET", body: String? = null, override: String? = null, timeout: Int = 30_000): String {
        val c = repo.open(path, if (override != null) "POST" else method, timeoutMs = 20_000)
        c.readTimeout = timeout
        try {
            override?.let { c.setRequestProperty("X-HTTP-Method-Override", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode !in 200..299) fail(c)
            return c.inputStream.bufferedReader().readText()
        } catch (e: java.net.ConnectException) {
            throw IOException("Server nicht erreichbar – WLAN/VPN verbunden?")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("Zeitüberschreitung")
        } finally { c.disconnect() }
    }

    fun models() = NomadChat.parseModels(call("/api/ollama/installed-models"))
    fun sessions() = NomadChat.parseSessions(call("/api/chat/sessions"))
    fun history(id: String) = NomadChat.parseSessionMessages(call("/api/chat/sessions/$id"))
    fun createSession(title: String, model: String?): String =
        JSONObject(call("/api/chat/sessions", "POST", JSONObject().put("title", title.take(60)).apply { model?.let { put("model", it) } }.toString())).get("id").toString()
    fun deleteSession(id: String) { call("/api/chat/sessions/$id", "DELETE") }
    fun ragEnabled(): Boolean = JSONObject(call("/api/system/settings?key=rag.enabled")).opt("value") as? Boolean ?: true
    fun setRag(on: Boolean) { call("/api/system/settings", override = "PATCH", body = JSONObject().put("key", "rag.enabled").put("value", on).toString()) }

    /** Streamt die Antwort; [onEvent] kommt für jedes SSE-Ereignis. Kehrt zurück, wenn der Stream endet oder [cancel] aufgerufen wurde. */
    fun stream(model: String, history: List<NomadChat.Message>, sessionId: String?, onEvent: (NomadChat.Event) -> Unit) {
        val body = JSONObject().put("model", model).put("messages", NomadChat.requestMessages(history)).put("stream", true)
        sessionId?.toIntOrNull()?.let { body.put("sessionId", it) }
        val c = repo.open("/api/ollama/chat", "POST", timeoutMs = 20_000)
        streamConn = c
        c.readTimeout = 15 * 60_000 // Modelle laden/„denken“ kann dauern
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) fail(c)
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) NomadChat.dataOf(line)?.let { onEvent(NomadChat.parseEvent(it)) }
            }
        } finally { streamConn = null; c.disconnect() }
    }

    fun cancel() { streamConn?.disconnect() }
}

data class UiMsg(val role: String, val content: String, val sources: List<NomadChat.Source> = emptyList(), val thinking: Boolean = false)

class NomadChatViewModel(app: Application) : AndroidViewModel(app) {
    private val g = (app as App).graph
    private val settings = g.settings
    private val client = NomadChatClient(g.nomad)

    val models = MutableStateFlow<List<NomadChat.Model>>(emptyList())
    val sessions = MutableStateFlow<List<NomadChat.Session>>(emptyList())
    val current = MutableStateFlow<String?>(null)
    val messages = MutableStateFlow<List<UiMsg>>(emptyList())
    val model = MutableStateFlow(settings.nomadChatModel)
    val rag = MutableStateFlow<Boolean?>(null)
    val streaming = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    private var job: Job? = null
    private var loaded = false

    val configured get() = g.nomad.configured

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Lädt Modelle, Sitzungen und den Wissensdatenbank-Schalter (einmal pro Start, oder erzwungen). */
    fun refresh(force: Boolean = false) {
        if (!configured || (loaded && !force)) return
        viewModelScope.launch {
            loading.value = true; error.value = null
            try {
                val m = io { client.models() }
                models.value = m
                if (model.value == null || m.none { it.name == model.value }) model.value = m.firstOrNull()?.name
                sessions.value = io { client.sessions() }
                rag.value = runCatching { io { client.ragEnabled() } }.getOrNull()
                loaded = true
            } catch (e: IOException) { error.value = e.message } finally { loading.value = false }
        }
    }

    fun selectModel(name: String) { model.value = name; settings.nomadChatModel = name }

    fun setRag(on: Boolean) {
        val old = rag.value
        rag.value = on
        viewModelScope.launch { try { io { client.setRag(on) } } catch (e: IOException) { rag.value = old; error.value = e.message } }
    }

    fun newChat() { stop(); current.value = null; messages.value = emptyList(); error.value = null }

    fun open(s: NomadChat.Session) {
        stop()
        current.value = s.id
        s.model?.let { m -> if (models.value.any { it.name == m }) selectModel(m) }
        viewModelScope.launch {
            try { messages.value = io { client.history(s.id) }.map { UiMsg(it.role, it.content, it.sources) } }
            catch (e: IOException) { error.value = e.message }
        }
    }

    fun delete(s: NomadChat.Session) {
        viewModelScope.launch {
            try { io { client.deleteSession(s.id) }; sessions.value = sessions.value - s; if (current.value == s.id) newChat() }
            catch (e: IOException) { error.value = e.message }
        }
    }

    fun send(text: String) {
        val m = model.value ?: run { error.value = "Kein Modell gewählt"; return }
        if (text.isBlank() || streaming.value) return
        error.value = null
        streaming.value = true
        val history = messages.value.map { NomadChat.Message(it.role, it.content) } + NomadChat.Message("user", text.trim())
        messages.value = messages.value + UiMsg("user", text.trim()) + UiMsg("assistant", "", thinking = true)
        job = viewModelScope.launch {
            try {
                var sid = current.value
                if (sid == null) {
                    sid = io { client.createSession(text.trim(), m) }
                    current.value = sid
                }
                var answer = StringBuilder()
                var sources = emptyList<NomadChat.Source>()
                io {
                    client.stream(m, history, sid) { ev ->
                        when (ev) {
                            is NomadChat.Event.Content -> answer.append(ev.text)
                            is NomadChat.Event.Sources -> sources = ev.sources
                            is NomadChat.Event.Error -> throw IOException(ev.message ?: "Fehler bei der Antwort")
                            else -> {}
                        }
                        val a = answer.toString()
                        val src = sources
                        messages.value = messages.value.dropLast(1) + UiMsg("assistant", a, src, thinking = a.isEmpty())
                    }
                }
                messages.value = messages.value.dropLast(1) + UiMsg("assistant", answer.toString().ifEmpty { "(keine Antwort)" }, sources)
            } catch (e: IOException) {
                // Abbruch durch den Nutzer wirft ebenfalls IOException; die bisherige Teilantwort bleibt stehen
                if (streaming.value && job?.isCancelled != true) error.value = e.message
                messages.value = messages.value.dropLast(1).let { base ->
                    val last = messages.value.lastOrNull()
                    if (last != null && last.content.isNotEmpty()) base + last.copy(thinking = false) else base
                }
            } finally {
                streaming.value = false
                launch { delay(4000); runCatching { sessions.value = io { client.sessions() } } } // Titel entsteht erst nach der ersten Antwort
            }
        }
    }

    fun stop() { job?.cancel(); client.cancel(); streaming.value = false }

    override fun onCleared() { client.cancel() }
}
