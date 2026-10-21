package de.loraki.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Hilfstest: erzeugt aus der echten NOMAD-Vorlage einen Kartenstil für Browser-Tests (nur wenn LK_STYLE_TEMPLATE gesetzt ist). */
class StyleFixtureTest {
    @Test fun generate() {
        val tpl = System.getenv("LK_STYLE_TEMPLATE") ?: return
        val out = File(System.getenv("LK_STYLE_OUT") ?: return)
        val base = System.getenv("LK_STYLE_BASE") ?: "https://lk.local/data/maps"
        val json = Nomad.buildMapStyle(File(tpl).readText(), mapOf((System.getenv("LK_STYLE_REGION") ?: "firenze") to (System.getenv("LK_STYLE_FILE") ?: "fir.pmtiles")), null, base, base)
        out.writeText(json)
        assertTrue(json.contains("pmtiles://$base/pmtiles/"))
    }
}
