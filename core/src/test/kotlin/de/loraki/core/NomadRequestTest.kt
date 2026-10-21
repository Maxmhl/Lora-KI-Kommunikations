package de.loraki.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NomadRequestTest {
    private fun b(s: String) = s.toByteArray()

    @Test fun requestsAreCompactTabSeparated() {
        assertEquals("books", String(NomadRequest.books()))
        assertEquals("search\twiki\tbird feathers\t8", String(NomadRequest.search("wiki", "bird\tfeathers\n", 8)))
        assertEquals("article\twiki\tBird\t1500\t1500\ttext", String(NomadRequest.article("wiki", "Bird", 1500)))
        assertEquals("article\twiki\tBird\t0\t1500\tsummary", String(NomadRequest.article("wiki", "Bird", summary = true)))
        assertEquals("map\t49.40750\t8.69350\t14\t128\t8", String(NomadRequest.map(49.4075, 8.6935, 14, NomadRequest.MapQuality.MEDIUM)))
        assertTrue(NomadRequest.map(49.4, 8.7, 14, NomadRequest.MapQuality.SMALL).size < 40)   // passt in ein einziges Funkpaket
    }

    @Test fun parseBooksAndSearch() {
        val books = NomadRequest.parseBooks(b("ok\tbooks\nwikipedia_en_100_mini_2026-01\tWikipedia 100\teng\t4985"))
        assertEquals(NomadRequest.Book("wikipedia_en_100_mini_2026-01", "Wikipedia 100", "eng", 4985), books.single())
        val hits = NomadRequest.parseSearch(b("ok\tsearch\nPars_lateralis\tPars lateralis\nBird\tBird"))
        assertEquals(listOf("Pars_lateralis", "Bird"), hits.map { it.path })
        assertTrue(NomadRequest.parseSearch(b("ok\tsearch\n")).isEmpty())
    }

    @Test fun parseArticleWithPaging() {
        val a = NomadRequest.parseArticle(b("ok\tarticle\t3420\t0\t12\ttext\n# Bird\n\nBirds"))
        assertEquals(3420, a.total); assertEquals(0, a.offset); assertEquals("# Bird\n\nBirds", a.text)
        assertTrue(a.hasMore)
        val last = NomadRequest.parseArticle(b("ok\tarticle\t100\t50\t50\ttext\n" + "x".repeat(50)))
        assertFalse(last.hasMore)
        val s = NomadRequest.parseArticle(b("ok\tarticle\t9000\t0\t20\tsummary\nKurz."))
        assertTrue(s.summary); assertFalse(s.hasMore)
    }

    @Test fun parseMapKeepsBinaryBody() {
        val img = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x0A, 0x00, 0x09, 0xFF.toByte())   // enthält \n und \t – darf nichts zerstören
        val head = "ok\tmap\twebp\theidelberg_2026-10\t14\t128\t8.68801\t49.40393\t8.69899\t49.41100\t6.22\n"
        val m = NomadRequest.parseMap(head.toByteArray() + img)
        assertEquals("webp", m.format); assertEquals(128, m.size); assertEquals(14, m.zoom)
        assertEquals(49.411, m.maxLat, 1e-9); assertEquals(6.22, m.metersPerPixel, 1e-9)
        assertContentEquals(img, m.image)
        val regions = NomadRequest.parseMapInfo(b("ok\tmapinfo\nheidelberg_2026-10\t8.6600\t49.3900\t8.7300\t49.4300\t14"))
        assertTrue(regions.single().covers(49.4, 8.7)); assertFalse(regions.single().covers(50.0, 8.7))
    }

    @Test fun errorsBecomeExceptions() {
        assertEquals("Keine Karten auf dem Server", assertFailsWith<NomadRequest.ProtocolError> { NomadRequest.parseMap(b("error\tKeine Karten auf dem Server")) }.message)
        assertFailsWith<NomadRequest.ProtocolError> { NomadRequest.parseBooks(b("quatsch")) }
    }

    @Test fun panMovesCenter() {
        val (lat, lon) = NomadRequest.pan(49.4, 8.7, 0.0, 1000.0)
        assertTrue(lat > 49.4 && lat < 49.42 && lon == 8.7)
        val (lat2, lon2) = NomadRequest.pan(49.4, 8.7, -1000.0, 0.0)
        assertTrue(lon2 < 8.7 && lat2 == 49.4)
        assertEquals(180.0 - 0.0, NomadRequest.pan(0.0, 179.9999, 1.0e5, 0.0).second.let { 180.0 + (it - it) }, 1e-9)
    }
}
