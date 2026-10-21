package de.loraki.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Eintrag im Manifest des Gateways (`/lk/manifest`). */
data class RemoteItem(
    val id: String, val kind: String, val name: String, val file: String,
    val version: String, val size: Long, val mtime: Long, val etag: String,
) {
    /** Serienschlüssel: bleibt bei Updates (neues Datum im Dateinamen) gleich. */
    val series get() = "$kind:$name"
}

data class Manifest(val items: List<RemoteItem>, val serverTime: Long, val nomadAvailable: Boolean)

data class InstalledItem(
    val series: String, val id: String, val kind: String, val name: String, val file: String,
    val version: String, val size: Long, val installedAt: Long,
)

object Nomad {
    const val KIND_ZIM = "zim"
    const val KIND_MAP = "map"
    const val KIND_ASSETS = "mapassets"
    const val ASSETS_ID = "mapassets"
    const val WORLD_SERIES = "map:world"

    fun parseManifest(json: String): Manifest {
        val o = JSONObject(json)
        val a = o.getJSONArray("items")
        val items = (0 until a.length()).map {
            val i = a.getJSONObject(it)
            RemoteItem(i.getString("id"), i.getString("kind"), i.getString("name"), i.getString("file"),
                i.getString("version"), i.getLong("size"), i.optLong("mtime"), i.optString("etag"))
        }
        return Manifest(items, o.optLong("server_time"), o.optBoolean("nomad"))
    }

    fun installedToJson(list: List<InstalledItem>): String = JSONArray(list.map {
        JSONObject().put("series", it.series).put("id", it.id).put("kind", it.kind).put("name", it.name)
            .put("file", it.file).put("version", it.version).put("size", it.size).put("at", it.installedAt)
    }).toString()

    fun installedFromJson(json: String?): List<InstalledItem> {
        if (json.isNullOrBlank()) return emptyList()
        val a = JSONArray(json)
        return (0 until a.length()).map {
            val i = a.getJSONObject(it)
            InstalledItem(i.getString("series"), i.getString("id"), i.getString("kind"), i.getString("name"),
                i.getString("file"), i.getString("version"), i.getLong("size"), i.optLong("at"))
        }
    }

    /** Neuere Version? Datumsstrings wie 2026-01 / 2026-01-15 sind lexikographisch vergleichbar. */
    fun isNewer(remote: String, local: String): Boolean = remote > local

    class Plan(val install: List<RemoteItem>, val update: List<RemoteItem>, val orphans: List<InstalledItem>) {
        val downloads get() = install + update
    }

    /**
     * Was ist zu laden? [wanted] = vom Nutzer gewählte Serien. Der Kartenstil (mapassets) wird mit jeder gewählten Karte
     * automatisch mitgeführt. Gleiche Version mit anderer Größe zählt als Update (Datei wurde ersetzt).
     */
    fun plan(remote: List<RemoteItem>, installed: List<InstalledItem>, wanted: Set<String>): Plan {
        val byInstalled = installed.associateBy { it.series }
        val want = wanted.toMutableSet()
        if (want.any { it.startsWith("$KIND_MAP:") }) want += "$KIND_ASSETS:$KIND_ASSETS"
        val install = ArrayList<RemoteItem>()
        val update = ArrayList<RemoteItem>()
        for (r in remote) {
            if (r.series !in want) continue
            val have = byInstalled[r.series]
            when {
                have == null -> install += r
                isNewer(r.version, have.version) || (r.version == have.version && r.size != have.size) -> update += r
            }
        }
        val remoteSeries = remote.map { it.series }.toSet()
        return Plan(install, update, installed.filter { it.series !in remoteSeries })
    }

    fun shouldCheck(lastCheckMs: Long, nowMs: Long, intervalDays: Int): Boolean =
        intervalDays > 0 && nowMs - lastCheckMs >= intervalDays * 24L * 3600_000L

    /** Zip-Slip-Schutz: Zielpfad muss innerhalb von [dir] liegen. */
    fun safeTarget(dir: File, entryName: String): File? {
        if (entryName.startsWith("/") || entryName.contains("..")) return null
        val target = File(dir, entryName).canonicalFile
        return if (target.path.startsWith(dir.canonicalPath + File.separator)) target else null
    }

    /**
     * Kartenstil für die Offline-Karte: übernimmt die Vorlage `nomad-base-styles.json` (Layer mit `source` werden je Region
     * dupliziert, wie in NOMADs `generateStylesJSON`) und zeigt auf lokale PMTiles. [pmtilesBase] z. B. `https://lk.local/data/maps`.
     */
    fun buildMapStyle(template: String, regionFiles: Map<String, String>, worldFile: String?, pmtilesBase: String, assetsBase: String): String {
        val t = JSONObject(template)
        val layers = t.getJSONArray("layers")
        val perSource = ArrayList<JSONObject>()
        val plain = ArrayList<JSONObject>()
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if (l.has("source")) perSource += l else plain += l
        }
        val attribution = "<a href=\"https://github.com/protomaps/basemaps\">Protomaps</a> © <a href=\"https://openstreetmap.org\">OpenStreetMap</a>"
        val sources = LinkedHashMap<String, String>()
        if (worldFile != null) sources["world"] = worldFile
        regionFiles.toSortedMap().forEach { (name, file) -> if (name != "world") sources[name] = file }

        val outSources = JSONObject()
        val outLayers = JSONArray(plain)
        for ((name, file) in sources) {
            outSources.put(name, JSONObject().put("type", "vector").put("attribution", attribution)
                .put("url", "pmtiles://$pmtilesBase/pmtiles/$file"))
            for (tpl in perSource) {
                val copy = JSONObject(tpl.toString())
                copy.put("id", "${tpl.getString("id")}-$name")
                copy.put("source", name)
                outLayers.put(copy)
            }
        }
        t.put("sources", outSources)
        t.put("layers", outLayers)
        t.put("sprite", "$assetsBase/basemaps-assets/sprites/v4/light")
        t.put("glyphs", "$assetsBase/basemaps-assets/fonts/{fontstack}/{range}.pbf")
        return t.toString()
    }

    /** `Range: bytes=a-b` / `bytes=a-` / `bytes=-n` -> (start, endInklusive); null = kein/ungültiger Range. */
    fun parseRange(header: String?, size: Long): LongRange? {
        val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header?.trim() ?: return null) ?: return null
        val (a, b) = m.destructured
        if (a.isEmpty() && b.isEmpty() || size <= 0) return null
        if (a.isEmpty()) { val n = b.toLong(); return if (n == 0L) null else maxOf(0, size - n)..(size - 1) }
        val start = a.toLong()
        val end = if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1)
        return if (start >= size || start > end) null else start..end
    }
}
