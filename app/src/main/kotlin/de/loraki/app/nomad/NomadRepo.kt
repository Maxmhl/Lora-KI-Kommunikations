package de.loraki.app.nomad

import android.content.Context
import android.os.StatFs
import de.loraki.app.data.Settings
import de.loraki.core.InstalledItem
import de.loraki.core.Manifest
import de.loraki.core.Nomad
import de.loraki.core.RemoteItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

data class DlProgress(val done: Long, val total: Long)

/** Offline-Inhalte von NOMAD (über das Gateway der Bridge): Manifest, Auswahl, Download, Ablage. */
class NomadRepo(private val ctx: Context, private val scope: CoroutineScope, private val settings: Settings) {
    val root: File = (ctx.getExternalFilesDir("nomad") ?: File(ctx.filesDir, "nomad")).also { it.mkdirs() }
    val zimDir get() = File(root, "zim").also { it.mkdirs() }
    val mapsDir get() = File(root, "maps").also { it.mkdirs() }
    val pmtilesDir get() = File(mapsDir, "pmtiles").also { it.mkdirs() }

    private val _manifest = MutableStateFlow(settings.nomadManifestJson?.let { runCatching { Nomad.parseManifest(it) }.getOrNull() })
    val manifest: StateFlow<Manifest?> = _manifest
    private val _installed = MutableStateFlow(runCatching { Nomad.installedFromJson(settings.nomadInstalledJson) }.getOrDefault(emptyList()))
    val installed: StateFlow<List<InstalledItem>> = _installed
    private val _wanted = MutableStateFlow(settings.nomadWanted)
    val wanted: StateFlow<Set<String>> = _wanted
    private val _progress = MutableStateFlow<Map<String, DlProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DlProgress>> = _progress
    val checking = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    val configured get() = settings.nomadUrl.isNotBlank()

    // ------------------------------------------------------------ Server

    fun open(path: String, method: String = "GET", range: String? = null, ifRange: String? = null, timeoutMs: Int = 20_000): HttpURLConnection {
        val c = URL(settings.nomadUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = 60_000
        if (settings.nomadToken.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${settings.nomadToken}")
        c.setRequestProperty("Accept-Encoding", "identity") // kein gzip: Streams (SSE) müssen sofort ankommen
        range?.let { c.setRequestProperty("Range", it) }
        ifRange?.let { c.setRequestProperty("If-Range", it) }
        return c
    }

    /** Lädt das Manifest. Wirft IOException mit verständlicher Meldung. */
    suspend fun refresh(): Manifest = withContext(Dispatchers.IO) {
        if (!configured) throw IOException("Server-Adresse fehlt")
        checking.value = true
        try {
            val c = open("/lk/manifest")
            try {
                when (c.responseCode) {
                    200 -> {}
                    401 -> throw IOException("Token abgelehnt (401) – Token in der Bridge-Config prüfen")
                    else -> throw IOException("Server antwortet mit ${c.responseCode}")
                }
                val text = c.inputStream.bufferedReader().readText()
                val m = Nomad.parseManifest(text)
                settings.nomadManifestJson = text
                settings.nomadLastCheck = System.currentTimeMillis()
                _manifest.value = m
                m
            } catch (e: java.net.UnknownHostException) {
                throw IOException("Server nicht gefunden – Adresse prüfen, WLAN/VPN verbunden?")
            } catch (e: java.net.ConnectException) {
                throw IOException("Server nicht erreichbar – läuft das Gateway? WLAN/VPN verbunden?")
            } catch (e: java.net.SocketTimeoutException) {
                throw IOException("Zeitüberschreitung beim Verbinden mit dem Server")
            } finally { c.disconnect() }
        } finally { checking.value = false }
    }

    fun plan() = Nomad.plan(_manifest.value?.items ?: emptyList(), _installed.value, _wanted.value)

    // ------------------------------------------------------------ Auswahl / Ablage

    fun setWanted(series: String, on: Boolean) {
        _wanted.update { if (on) it + series else it - series }
        settings.nomadWanted = _wanted.value
    }

    fun installedFile(i: InstalledItem): File = when (i.kind) {
        Nomad.KIND_ZIM -> File(zimDir, i.file)
        Nomad.KIND_MAP -> File(pmtilesDir, i.file)
        else -> File(mapsDir, i.file)
    }

    private fun saveInstalled(list: List<InstalledItem>) {
        _installed.value = list
        settings.nomadInstalledJson = Nomad.installedToJson(list)
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun freeBytes(): Long = StatFs(root.path).availableBytes

    /** Entfernt eine Serie vom Handy (Dateien, Eintrag, Auswahl). */
    fun remove(series: String) {
        val i = _installed.value.firstOrNull { it.series == series } ?: return
        if (i.kind == Nomad.KIND_ASSETS) {
            File(mapsDir, "basemaps-assets").deleteRecursively(); File(mapsDir, "nomad-base-styles.json").delete()
        } else installedFile(i).delete()
        saveInstalled(_installed.value - i)
        setWanted(series, false)
        // Kartenstil nur behalten, solange noch eine Karte da ist
        if (i.kind == Nomad.KIND_MAP && _installed.value.none { it.kind == Nomad.KIND_MAP }) remove("${Nomad.KIND_ASSETS}:${Nomad.KIND_ASSETS}")
    }

    // ------------------------------------------------------------ Download

    /** Lädt eine Datei fortsetzbar (Range + If-Range), prüft die Größe und legt sie atomar ab. */
    suspend fun download(item: RemoteItem) = withContext(Dispatchers.IO) {
        if (freeBytes() < item.size + 64L * 1024 * 1024) throw IOException("Zu wenig freier Speicher für ${item.file} (${item.size / 1_000_000} MB nötig)")
        val target = when (item.kind) {
            Nomad.KIND_ZIM -> File(zimDir, item.file)
            Nomad.KIND_MAP -> File(pmtilesDir, item.file)
            else -> File(root, "mapassets.zip")
        }
        val part = File(target.path + ".part")
        val meta = File(target.path + ".part.etag")
        var offset = if (part.exists()) part.length() else 0L
        if (offset > item.size) { part.delete(); offset = 0 }
        val storedEtag = meta.takeIf { it.exists() }?.readText()
        val encoded = item.id.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        _progress.update { it + (item.id to DlProgress(offset, item.size)) }
        try {
            val c = open("/lk/files/$encoded", range = if (offset > 0) "bytes=$offset-" else null,
                ifRange = if (offset > 0) storedEtag else null, timeoutMs = 30_000)
            try {
                val code = c.responseCode
                if (code == 416) { part.delete(); meta.delete(); throw IOException("Server hat den Bereich abgelehnt – bitte erneut starten") }
                if (code != 200 && code != 206) throw IOException("Server antwortet mit $code")
                c.getHeaderField("ETag")?.let { meta.writeText(it) }
                val append = code == 206
                if (!append) offset = 0
                var done = offset
                c.inputStream.use { input ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var lastUi = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - lastUi > 250_000_000L) { lastUi = now; _progress.update { it + (item.id to DlProgress(done, item.size)) } }
                        }
                    }
                }
            } finally { c.disconnect() }
            if (part.length() != item.size) throw IOException("Download unvollständig (${part.length()} von ${item.size} Byte) – wird fortgesetzt")
            target.delete()
            if (!part.renameTo(target)) throw IOException("Datei konnte nicht abgelegt werden")
            meta.delete()
            finish(item, target)
        } finally {
            _progress.update { it - item.id }
        }
    }

    private fun finish(item: RemoteItem, file: File) {
        if (item.kind == Nomad.KIND_ASSETS) {
            File(mapsDir, "basemaps-assets").deleteRecursively()
            ZipInputStream(file.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val dest = Nomad.safeTarget(mapsDir, e.name) ?: throw IOException("Ungültiger Eintrag im Kartenpaket: ${e.name}")
                    if (e.isDirectory) { dest.mkdirs(); continue }
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { zin.copyTo(it) }
                }
            }
            file.delete()
        }
        val old = _installed.value.firstOrNull { it.series == item.series }
        if (old != null && old.file != item.file && item.kind != Nomad.KIND_ASSETS) installedFile(old).delete()
        val entry = InstalledItem(item.series, item.id, item.kind, item.name, item.file, item.version, item.size, System.currentTimeMillis())
        saveInstalled(_installed.value.filter { it.series != item.series } + entry)
        setWanted(item.series, true)
    }

    /** Löscht alle Offline-Inhalte, die Auswahl und das Manifest vom Handy. */
    fun wipe() {
        root.deleteRecursively(); root.mkdirs()
        _installed.value = emptyList(); _wanted.value = emptySet(); _manifest.value = null; _progress.value = emptyMap()
        settings.nomadInstalledJson = null; settings.nomadWanted = emptySet(); settings.nomadManifestJson = null
    }

    fun launch(block: suspend () -> Unit) = scope.launch { try { block() } catch (e: IOException) { message.value = e.message } }
}
