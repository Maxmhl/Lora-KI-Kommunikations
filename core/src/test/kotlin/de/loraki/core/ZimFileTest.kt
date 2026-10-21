package de.loraki.core

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Prüft den ZIM-Leser gegen Referenzwerte aus libzim (zim-golden.json). Braucht die Mini-Wikipedia aus Project NOMAD. */
class ZimFileTest {
    private val golden = JSONObject(javaClass.getResource("/zim-golden.json")!!.readText())
    private val zimPath = System.getProperty("zimFile") ?: System.getenv("LK_TEST_ZIM")
    private val zim = zimPath?.let { File(it) }?.takeIf { it.exists() }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private inline fun withZim(block: (ZimFile) -> Unit) {
        if (zim == null) { println("ZIM-Testdatei nicht vorhanden – Test übersprungen"); return }
        ZimFile(zim).use(block)
    }

    @Test fun headerAndMetadata() = withZim { z ->
        assertEquals(golden.getInt("entryCount"), z.entryCount)
        assertTrue(z.newNamespaces)
        assertEquals(golden.getString("metaTitle"), z.metadata("Title"))
    }

    @Test fun entriesMatchReference() = withZim { z ->
        val arr = golden.getJSONArray("entries")
        for (n in 0 until arr.length()) {
            val g = arr.getJSONObject(n)
            val d = z.dirent(g.getInt("i"))
            assertEquals(g.getBoolean("redirect"), d.isRedirect, "Redirect-Flag ${g.getInt("i")}")
            if (!d.isRedirect) {
                val e = z.entry(d.index)
                assertEquals(g.getString("mime"), e.mime)
                val data = e.data()
                assertEquals(g.getInt("size"), data.size, "Größe ${g.getString("path")}")
                assertEquals(g.getString("sha1"), sha1(data), "Inhalt ${g.getString("path")}")
            }
        }
    }

    @Test fun findByPathAndRedirects() = withZim { z ->
        val arr = golden.getJSONArray("entries")
        for (n in 0 until arr.length()) {
            val g = arr.getJSONObject(n)
            val d = z.dirent(g.getInt("i"))
            if (d.ns == 'C') assertEquals(d.index, z.find('C', d.url), d.url)
            if (d.ns == 'C') assertEquals(d.index, z.findByUrlPath(d.url))
        }
        val redirect = (0 until golden.getInt("entryCount")).map { z.dirent(it) }.first { it.isRedirect && it.ns == 'C' }
        assertTrue(z.resolve(redirect).isContent)
    }

    @Test fun mainPage() = withZim { z ->
        val idx = assertNotNull(z.mainEntryIndex())
        val e = z.entry(idx)
        assertEquals(golden.getString("main"), e.dirent.url)
        assertEquals("text/html", e.mime)
    }

    @Test fun titleSearch() = withZim { z ->
        val r = z.titlePrefix("Par", 10)
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { it.displayTitle.startsWith("Par") })
        // "paris"-artige Eingabe in Kleinbuchstaben findet über die Varianten die großgeschriebenen Titel
        val s = z.suggest("par", 10)
        assertTrue(s.isNotEmpty() && s.all { it.displayTitle.startsWith("Par") })
        assertTrue(z.suggest("xqzyw", 10).isEmpty())
    }
}
