package de.loraki.app.nomad

import android.content.res.AssetManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import de.loraki.core.Nomad
import de.loraki.core.ZimFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Liefert dem WebView alles unter https://lk.local/ aus: Karten-Viewer (Assets), PMTiles/Schriften (Dateien, mit Range),
 * den Kartenstil und ZIM-Inhalte. Es gibt keinen lokalen Netzwerk-Port; externe Hosts werden blockiert.
 */
class LocalContent(private val repo: NomadRepo, private val assets: AssetManager) {
    private val zims = HashMap<String, ZimFile>()

    fun zim(key: String): ZimFile? = synchronized(zims) {
        zims[key] ?: repo.installed.value.firstOrNull { it.kind == Nomad.KIND_ZIM && it.file == key }
            ?.let { runCatching { ZimFile(repo.installedFile(it)) }.getOrNull() }?.also { zims[key] = it }
    }

    fun close() = synchronized(zims) { zims.values.forEach { runCatching { it.close() } }; zims.clear() }

    private fun text(code: Int, reason: String, msg: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, cors(), ByteArrayInputStream(msg.toByteArray()))

    private fun cors() = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")

    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val url = req.url
        if (url.host != HOST) {
            // Offline-Viewer: keine Verbindungen nach außen
            return if (url.scheme == "https" || url.scheme == "http") text(403, "Forbidden", "Offline") else null
        }
        val path = url.path ?: "/"
        return try {
            when {
                path == "/style.json" -> style()
                path.startsWith("/data/") -> dataFile(path.removePrefix("/data/"), req.requestHeaders["Range"])
                path.startsWith("/zim/") -> zimContent(path.removePrefix("/zim/"))
                path == "/" || path == "/map" -> asset("map/index.html")
                else -> asset("map/" + path.removePrefix("/"))
            }
        } catch (e: Exception) {
            text(500, "Error", e.message ?: "Fehler")
        }
    }

    private fun mimeOf(name: String) = when {
        name.endsWith(".pmtiles") -> "application/octet-stream"
        name.endsWith(".pbf") -> "application/x-protobuf"
        name.endsWith(".js") -> "text/javascript"
        name.endsWith(".json") -> "application/json"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }

    private fun asset(path: String): WebResourceResponse {
        if (path.contains("..")) return text(403, "Forbidden", "")
        val stream = try { assets.open(path) } catch (e: java.io.FileNotFoundException) { return text(404, "Not Found", path) }
        return WebResourceResponse(mimeOf(path), "utf-8", 200, "OK", cors(), stream)
    }

    private fun style(): WebResourceResponse {
        val template = File(repo.mapsDir, "nomad-base-styles.json")
        val maps = repo.installed.value.filter { it.kind == Nomad.KIND_MAP }
        if (!template.exists() || maps.isEmpty())
            return text(404, "Not Found", "Keine Karte auf dem Handy. In der Bibliothek eine Karte und den Kartenstil laden.")
        val world = maps.firstOrNull { it.name == "world" }?.file
        val regions = maps.filter { it.name != "world" }.associate { it.name to it.file }
        val json = Nomad.buildMapStyle(template.readText(), regions, world, "$ORIGIN/data/maps", "$ORIGIN/data/maps")
        return WebResourceResponse("application/json", "utf-8", 200, "OK", cors(), ByteArrayInputStream(json.toByteArray()))
    }

    private fun dataFile(rel: String, rangeHeader: String?): WebResourceResponse {
        val f = File(repo.root, rel).canonicalFile
        if (!f.path.startsWith(repo.root.canonicalPath + File.separator) || !f.isFile) return text(404, "Not Found", rel)
        val size = f.length()
        val headers = cors() + ("Accept-Ranges" to "bytes")
        val range = Nomad.parseRange(rangeHeader, size)
        if (range == null) return WebResourceResponse(mimeOf(f.name), null, 200, "OK", headers + ("Content-Length" to size.toString()), f.inputStream())
        val len = range.last - range.first + 1
        return WebResourceResponse(mimeOf(f.name), null, 206, "Partial Content",
            headers + mapOf("Content-Range" to "bytes ${range.first}-${range.last}/$size", "Content-Length" to len.toString()),
            SliceStream(f, range.first, len))
    }

    private fun zimContent(rest: String): WebResourceResponse {
        val key = rest.substringBefore('/')
        val path = rest.substringAfter('/', "")
        val z = zim(key) ?: return text(404, "Not Found", "Bibliothek nicht geladen: $key")
        val idx = (if (path.isEmpty()) z.mainEntryIndex() else z.findByUrlPath(path)) ?: return text(404, "Not Found", path)
        val e = z.entry(idx)
        val mime = e.mime.substringBefore(';')
        return WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("javascript")) "utf-8" else null, 200, "OK", cors(), ByteArrayInputStream(e.data()))
    }

    /** Liest nur einen Ausschnitt einer Datei (für HTTP-Range-Anfragen von pmtiles.js). */
    private class SliceStream(file: File, start: Long, private var remaining: Long) : InputStream() {
        private val raf = RandomAccessFile(file, "r").also { it.seek(start) }
        override fun read(): Int { if (remaining <= 0) return -1; remaining--; return raf.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
        override fun close() = raf.close()
    }

    companion object {
        const val HOST = "lk.local"
        const val ORIGIN = "https://lk.local"
    }
}
