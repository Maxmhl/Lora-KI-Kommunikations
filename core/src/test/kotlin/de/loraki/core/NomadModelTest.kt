package de.loraki.core

import org.json.JSONObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NomadModelTest {
    private fun r(id: String, kind: String, name: String, ver: String, size: Long = 100) =
        RemoteItem(id, kind, name, id.substringAfter('/'), ver, size, 0, "e")

    private fun i(series: String, ver: String, size: Long = 100) = InstalledItem(series, "x", series.substringBefore(':'), series.substringAfter(':'), "f", ver, size, 0)

    private val remote = listOf(
        r("zim/wiki_2026-02.zim", "zim", "wiki", "2026-02"),
        r("zim/med_2026-01.zim", "zim", "med", "2026-01"),
        r("map/bayern_2026-01.pmtiles", "map", "bayern", "2026-01"),
        r("map/world.pmtiles", "map", "world", "2026-01-15"),
        r("mapassets", "mapassets", "mapassets", "2026-01-10"),
    )

    @Test fun manifestRoundtrip() {
        val json = """{"version":1,"server_time":5,"nomad":true,"items":[{"id":"zim/a_2026-01.zim","kind":"zim","name":"a","file":"a_2026-01.zim","version":"2026-01","size":10,"mtime":3,"etag":"x"}]}"""
        val m = Nomad.parseManifest(json)
        assertEquals("zim:a", m.items.single().series)
        assertTrue(m.nomadAvailable)
        val inst = listOf(InstalledItem("zim:a", "zim/a_2026-01.zim", "zim", "a", "a_2026-01.zim", "2026-01", 10, 7))
        assertEquals(inst, Nomad.installedFromJson(Nomad.installedToJson(inst)))
        assertTrue(Nomad.installedFromJson(null).isEmpty())
    }

    @Test fun planInstallsOnlyWantedAndAddsMapAssets() {
        val p = Nomad.plan(remote, emptyList(), setOf("zim:wiki", "map:bayern"))
        assertEquals(setOf("zim:wiki", "map:bayern", "mapassets:mapassets"), p.install.map { it.series }.toSet())
        assertTrue(p.update.isEmpty())
        // nur Wikipedia gewählt -> kein Kartenstil nötig
        assertEquals(listOf("zim:wiki"), Nomad.plan(remote, emptyList(), setOf("zim:wiki")).install.map { it.series })
    }

    @Test fun planDetectsUpdatesAndOrphans() {
        val installed = listOf(i("zim:wiki", "2026-01"), i("zim:med", "2026-01"), i("map:bayern", "2026-01"), i("zim:alt", "2020-01"))
        val p = Nomad.plan(remote, installed, setOf("zim:wiki", "zim:med", "map:bayern", "zim:alt"))
        assertEquals(listOf("zim:wiki"), p.update.map { it.series })      // neuere Version
        assertEquals(listOf("mapassets:mapassets"), p.install.map { it.series }) // Stil fehlt noch
        assertEquals(listOf("zim:alt"), p.orphans.map { it.series })      // auf dem Server verschwunden
        // gleiche Version, andere Größe -> Datei wurde ersetzt
        val same = Nomad.plan(remote, listOf(i("zim:med", "2026-01", size = 999)), setOf("zim:med"))
        assertEquals(listOf("zim:med"), same.update.map { it.series })
        // nichts zu tun
        assertTrue(Nomad.plan(remote, listOf(i("zim:med", "2026-01")), setOf("zim:med")).downloads.isEmpty())
    }

    @Test fun updateIntervals() {
        val day = 24L * 3600_000
        assertFalse(Nomad.shouldCheck(0, 100 * day, 0))            // nie
        assertFalse(Nomad.shouldCheck(10 * day, 10 * day + day - 1, 1))
        assertTrue(Nomad.shouldCheck(10 * day, 11 * day, 1))
        assertFalse(Nomad.shouldCheck(0, 6 * day, 7))
        assertTrue(Nomad.shouldCheck(0, 7 * day, 7))
        assertTrue(Nomad.shouldCheck(0, 30 * day, 30))
    }

    @Test fun zipSlipProtection() {
        val dir = Files.createTempDirectory("lk").toFile()
        assertNotNull(Nomad.safeTarget(dir, "basemaps-assets/fonts/a/0-255.pbf"))
        assertNull(Nomad.safeTarget(dir, "../evil"))
        assertNull(Nomad.safeTarget(dir, "a/../../evil"))
        assertNull(Nomad.safeTarget(dir, "/etc/passwd"))
    }

    @Test fun mapStyleFollowsNomadTemplateLogic() {
        val template = """{"version":8,"name":"x","sources":{"old":{"type":"vector","url":"x"}},
            "layers":[{"id":"background","type":"background"},
                      {"id":"water","type":"fill","source":"protomaps","source-layer":"water"},
                      {"id":"roads","type":"line","source":"protomaps","source-layer":"roads"}],
            "sprite":"s","glyphs":"g"}"""
        val s = JSONObject(Nomad.buildMapStyle(template, mapOf("bayern" to "bayern_2026-01.pmtiles"), "world.pmtiles",
            "https://lk.local/data/maps", "https://lk.local/data"))
        assertEquals(setOf("world", "bayern"), s.getJSONObject("sources").keys().asSequence().toSet())
        assertEquals("pmtiles://https://lk.local/data/maps/pmtiles/bayern_2026-01.pmtiles", s.getJSONObject("sources").getJSONObject("bayern").getString("url"))
        val ids = (0 until s.getJSONArray("layers").length()).map { s.getJSONArray("layers").getJSONObject(it).getString("id") }
        assertEquals(listOf("background", "water-world", "roads-world", "water-bayern", "roads-bayern"), ids) // Welt zuerst = darunter
        assertEquals("https://lk.local/data/basemaps-assets/fonts/{fontstack}/{range}.pbf", s.getString("glyphs"))
        assertEquals("https://lk.local/data/basemaps-assets/sprites/v4/light", s.getString("sprite"))
        // ohne Weltkarte
        val n = JSONObject(Nomad.buildMapStyle(template, mapOf("bayern" to "b.pmtiles"), null, "b", "a"))
        assertEquals(setOf("bayern"), n.getJSONObject("sources").keys().asSequence().toSet())
    }

    @Test fun rangeParsing() {
        assertEquals(100L..199L, Nomad.parseRange("bytes=100-199", 1000))
        assertEquals(900L..999L, Nomad.parseRange("bytes=900-", 1000))
        assertEquals(900L..999L, Nomad.parseRange("bytes=-100", 1000))
        assertEquals(500L..999L, Nomad.parseRange("bytes=500-5000", 1000))
        assertNull(Nomad.parseRange("bytes=2000-", 1000))
        assertNull(Nomad.parseRange("bytes=5-1", 1000))
        assertNull(Nomad.parseRange("bytes=-", 1000))
        assertNull(Nomad.parseRange(null, 1000))
        assertNull(Nomad.parseRange("items=1-2", 1000))
    }
}
