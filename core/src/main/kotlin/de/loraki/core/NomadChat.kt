package de.loraki.core

import org.json.JSONArray
import org.json.JSONObject

/** Datenmodelle und Parser für NOMADs Chat-API (`/api/ollama/…`, `/api/chat/sessions`), erreichbar über das Gateway. */
object NomadChat {
    data class Model(val name: String, val vision: Boolean, val thinking: Boolean, val sizeBytes: Long)
    data class Source(val title: String, val date: String?, val path: String?)
    data class Message(val role: String, val content: String, val sources: List<Source> = emptyList())
    data class Session(val id: String, val title: String, val model: String?)

    sealed class Event {
        data class Content(val text: String) : Event()
        data class Thinking(val text: String) : Event()
        data class Sources(val sources: List<Source>) : Event()
        data class Error(val message: String?) : Event()
        object Done : Event()
        object Ignored : Event()
    }

    /** `/api/ollama/installed-models`: Ollama-Modellliste, angereichert um `vision` ("supported"/…) und `thinking`. */
    fun parseModels(json: String): List<Model> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull {
            val o = a.getJSONObject(it)
            val name = o.optString("name").ifEmpty { o.optString("model") }
            if (name.isEmpty()) null
            else Model(name, o.optString("vision") == "supported" || o.optBoolean("vision", false), o.optBoolean("thinking", false), o.optLong("size"))
        }
    }

    fun parseSessions(json: String): List<Session> {
        val a = JSONArray(json)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Session(o.get("id").toString(), o.optString("title", "Unterhaltung"), o.optString("model").ifEmpty { null })
        }
    }

    private fun sources(a: JSONArray?): List<Source> =
        if (a == null) emptyList() else (0 until a.length()).map {
            val s = a.getJSONObject(it)
            Source(s.optString("title"), s.optString("date").ifEmpty { null }, s.optString("source").ifEmpty { null })
        }

    /** `/api/chat/sessions/:id` -> Verlauf. */
    fun parseSessionMessages(json: String): List<Message> {
        val a = JSONObject(json).optJSONArray("messages") ?: return emptyList()
        return (0 until a.length()).map {
            val m = a.getJSONObject(it)
            Message(m.getString("role"), m.optString("content"), sources(m.optJSONArray("sources")))
        }
    }

    /** Eine `data: {...}`-Zeile des SSE-Streams von `/api/ollama/chat`. */
    fun parseEvent(data: String): Event {
        val o = try { JSONObject(data) } catch (e: Exception) { return Event.Ignored }
        if (o.optBoolean("error", false)) return Event.Error(o.optString("message").ifEmpty { null })
        o.optJSONArray("sources")?.let { return Event.Sources(sources(it)) }
        val msg = o.optJSONObject("message")
        val content = msg?.optString("content").orEmpty()
        val thinking = msg?.optString("thinking").orEmpty()
        return when {
            content.isNotEmpty() -> Event.Content(content)
            thinking.isNotEmpty() -> Event.Thinking(thinking)
            o.optBoolean("done", false) -> Event.Done
            else -> Event.Ignored
        }
    }

    /** Nur die Nutzdaten der `data:`-Zeilen; leere Zeilen und Kommentare werden übersprungen. */
    fun dataOf(line: String): String? = if (line.startsWith("data:")) line.removePrefix("data:").trim().ifEmpty { null } else null

    /** Verlauf für die Anfrage: nur gefüllte user/assistant-Nachrichten (NOMAD ergänzt System-Prompt und Wissensdatenbank). */
    fun requestMessages(history: List<Message>): JSONArray = JSONArray(history.filter { it.content.isNotBlank() && it.role in listOf("user", "assistant") }
        .map { JSONObject().put("role", it.role).put("content", it.content) })
}
