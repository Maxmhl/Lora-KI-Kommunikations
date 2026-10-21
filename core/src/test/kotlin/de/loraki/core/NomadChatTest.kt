package de.loraki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NomadChatTest {
    @Test fun models() {
        val m = NomadChat.parseModels("""[{"name":"llama3.1:8b","size":4900000000,"vision":"unsupported","thinking":false},
            {"model":"llava:7b","size":4700000000,"vision":"supported","thinking":false},{"name":"qwen3:8b","thinking":true,"vision":"unknown"},{}]""")
        assertEquals(listOf("llama3.1:8b", "llava:7b", "qwen3:8b"), m.map { it.name })
        assertEquals(listOf(false, true, false), m.map { it.vision })
        assertTrue(m[2].thinking)
    }

    @Test fun sessionsAndHistory() {
        val s = NomadChat.parseSessions("""[{"id":"12","title":"Erste Hilfe","model":"llama3.1","timestamp":"x","lastMessage":null},{"id":3,"title":"","model":null}]""")
        assertEquals(NomadChat.Session("12", "Erste Hilfe", "llama3.1"), s[0])
        assertNull(s[1].model)
        val h = NomadChat.parseSessionMessages("""{"id":"12","messages":[{"id":"1","role":"user","content":"Hi"},
            {"id":"2","role":"assistant","content":"Hallo","sources":[{"title":"Wikipedia: Erste Hilfe","date":"2026-01","source":"/zim/x.zim"}]}]}""")
        assertEquals(2, h.size)
        assertEquals("Wikipedia: Erste Hilfe", h[1].sources.single().title)
        assertTrue(h[0].sources.isEmpty())
    }

    @Test fun streamEvents() {
        assertEquals(NomadChat.Event.Content("Hal"), NomadChat.parseEvent("""{"model":"m","message":{"role":"assistant","content":"Hal"},"done":false}"""))
        assertEquals(NomadChat.Event.Thinking("hmm"), NomadChat.parseEvent("""{"message":{"role":"assistant","content":"","thinking":"hmm"},"done":false}"""))
        assertEquals(NomadChat.Event.Done, NomadChat.parseEvent("""{"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}"""))
        val src = assertIs<NomadChat.Event.Sources>(NomadChat.parseEvent("""{"sources":[{"title":"A","source":"/p"}]}"""))
        assertEquals("A", src.sources.single().title)
        assertIs<NomadChat.Event.Error>(NomadChat.parseEvent("""{"error":true,"message":"Modell fehlt"}"""))
        assertEquals(NomadChat.Event.Ignored, NomadChat.parseEvent("kein json"))
    }

    @Test fun sseLines() {
        assertEquals("""{"a":1}""", NomadChat.dataOf("""data: {"a":1}"""))
        assertNull(NomadChat.dataOf(""))
        assertNull(NomadChat.dataOf(": keepalive"))
    }

    @Test fun requestMessagesDropEmpty() {
        val a = NomadChat.requestMessages(listOf(NomadChat.Message("user", "Frage"), NomadChat.Message("assistant", ""), NomadChat.Message("system", "x")))
        assertEquals(1, a.length())
        assertEquals("user", a.getJSONObject(0).getString("role"))
    }
}
