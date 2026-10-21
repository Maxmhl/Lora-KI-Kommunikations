package de.loraki.app.nomad

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.loraki.app.Engine
import de.loraki.app.App
import de.loraki.app.ble.LinkState
import de.loraki.app.ble.MeshSession
import de.loraki.app.data.Settings
import de.loraki.core.NomadRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class NomadMode { WLAN, LORA, NONE }

/**
 * Wählt den Weg zu NOMAD: im lokalen WLAN (Gateway erreichbar) schnell per HTTP, sonst per LoRa. Es wird nichts gespeichert –
 * Antworten leben nur im Arbeitsspeicher der Anzeige.
 */
class NomadAccess(private val settings: Settings, private val engine: Engine, private val session: MeshSession, private val scope: CoroutineScope) {
    private val _mode = MutableStateFlow(NomadMode.NONE)
    val mode: StateFlow<NomadMode> = _mode
    val loraActivity get() = engine.loraActivity

    private fun loraReady() = session.state.value == LinkState.READY && settings.serverKeyHex != null

    /** Gateway im lokalen Netz erreichbar? (kurzer Test ohne Anmeldung) */
    private fun gatewayReachable(): Boolean {
        if (settings.nomadUrl.isBlank()) return false
        return try {
            val c = URL(settings.nomadUrl + "/lk/ping").openConnection() as HttpURLConnection
            c.connectTimeout = 1200; c.readTimeout = 1500
            try { c.responseCode == 200 && c.inputStream.bufferedReader().readText().contains("lkbridge-gateway") } finally { c.disconnect() }
        } catch (e: Exception) { false }
    }

    fun refresh() { scope.launch { _mode.value = detect() } }

    suspend fun detect(): NomadMode = withContext(Dispatchers.IO) {
        when { gatewayReachable() -> NomadMode.WLAN; loraReady() -> NomadMode.LORA; else -> NomadMode.NONE }
    }

    private fun viaWlan(payload: ByteArray): ByteArray {
        val c = URL(settings.nomadUrl + "/lk/nomad").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 4000; c.readTimeout = 120_000; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer ${settings.nomadToken}")
        try {
            c.outputStream.use { it.write(payload) }
            if (c.responseCode == 401) throw IOException("Token abgelehnt (401)")
            if (c.responseCode != 200) throw IOException("Server antwortet mit ${c.responseCode}")
            return c.inputStream.readBytes()
        } finally { c.disconnect() }
    }

    /** Schickt eine Anfrage auf dem besten verfügbaren Weg. Fällt das WLAN aus, wird per LoRa wiederholt. */
    suspend fun request(payload: ByteArray): ByteArray {
        if (_mode.value != NomadMode.LORA) {
            val m = detect(); _mode.value = m
            if (m == NomadMode.WLAN) {
                try { return withContext(Dispatchers.IO) { viaWlan(payload) } } catch (e: IOException) {
                    if (!loraReady()) throw e
                    _mode.value = NomadMode.LORA
                }
            }
        }
        if (!loraReady()) throw IOException("Weder WLAN noch LoRa-Verbindung zum Server")
        _mode.value = NomadMode.LORA
        return engine.nomadViaLora(payload)
    }

    /** Geschätzte Wartezeit für eine Antwort dieser Größe (Sekunden); null im WLAN. */
    fun estimateSeconds(responseBytes: Int, map: Boolean = false): Double? {
        if (_mode.value != NomadMode.LORA) return null
        val base = engine.estimateSeconds(responseBytes + 40) ?: return null
        val compact = settings.nomadToken.length >= 16 && (if (map) settings.compactMap else settings.compactText)
        return if (compact) base * 0.78 else base   // Rohdatenpakete: rund ein Fünftel weniger Funkzeit
    }
}

data class ArticleView(val book: String, val path: String, val title: String, val text: String, val total: Int, val summary: Boolean)
data class MapRequestInfo(val lat: Double, val lon: Double, val radius: NomadRequest.MapRadius, val quality: NomadRequest.MapQuality)
data class MapView(val request: MapRequestInfo, val section: NomadRequest.MapSection, val bitmap: Bitmap)
enum class PositionSource(val label: String) { NODE("Node-GPS"), PHONE("Handy-GPS"), MANUAL("Manuell") }
data class UserPosition(val lat: Double, val lon: Double, val source: PositionSource)

/** Zustand der gezielten NOMAD-Anfragen (Wiki, Karte). Alles nur im Arbeitsspeicher. */
class NomadLookupViewModel(app: Application) : AndroidViewModel(app) {
    private val g = (app as App).graph
    val access = g.nomadAccess
    val mode = access.mode
    val activity = access.loraActivity

    val books = MutableStateFlow<List<NomadRequest.Book>>(emptyList())
    val book = MutableStateFlow<String?>(null)
    val hits = MutableStateFlow<List<NomadRequest.Hit>>(emptyList())
    val article = MutableStateFlow<ArticleView?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    val regions = MutableStateFlow<List<NomadRequest.MapRegion>>(emptyList())
    val map = MutableStateFlow<MapView?>(null)
    val quality = MutableStateFlow(NomadRequest.MapQuality.MEDIUM)

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true; error.value = null
        try { block() } catch (e: IOException) { error.value = e.message } catch (e: NomadRequest.ProtocolError) { error.value = e.message } finally { busy.value = false }
    }

    fun refreshMode() = access.refresh()

    // ---- Wiki
    fun loadBooks() = run {
        val b = NomadRequest.parseBooks(access.request(NomadRequest.books()))
        books.value = b
        if (book.value == null) book.value = b.firstOrNull()?.id
    }

    fun search(q: String) = run {
        val b = book.value ?: throw IOException("Keine Bibliothek gewählt")
        hits.value = NomadRequest.parseSearch(access.request(NomadRequest.search(b, q)))
        if (hits.value.isEmpty()) error.value = "Keine Treffer"
    }

    fun firstChunk() = if (mode.value == NomadMode.LORA) 1500 else 20000

    fun openArticle(hit: NomadRequest.Hit, summary: Boolean = false) = run {
        val b = book.value ?: throw IOException("Keine Bibliothek gewählt")
        val a = NomadRequest.parseArticle(access.request(NomadRequest.article(b, hit.path, 0, firstChunk(), summary)))
        article.value = ArticleView(b, hit.path, hit.title, a.text, a.total, a.summary)
    }

    fun moreOfArticle(chunk: Int = 1500) = run {
        val cur = article.value ?: return@run
        val a = NomadRequest.parseArticle(access.request(NomadRequest.article(cur.book, cur.path, cur.text.length, chunk, false)))
        article.value = cur.copy(text = cur.text + a.text)
    }

    fun summarize() { val cur = article.value ?: return; openArticle(NomadRequest.Hit(cur.path, cur.title), summary = true) }

    // ---- Karte
    val position = MutableStateFlow<UserPosition?>(null)
    val source = MutableStateFlow(PositionSource.NODE)
    val radius = MutableStateFlow(NomadRequest.MapRadius.R1K)
    /** Per Antippen gewählter Punkt (Ziel einer Detailkarte). */
    val target = MutableStateFlow<Pair<Double, Double>?>(null)
    private val history = ArrayDeque<MapView>()
    val canGoBack = MutableStateFlow(false)
    private val mapCache = object : LinkedHashMap<String, MapView>(8, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, MapView>?) = size > 8 }

    fun loadRegions() = run { regions.value = NomadRequest.parseMapInfo(access.request(NomadRequest.mapInfo())) }

    /** Position vom Node (GPS am Node) holen: der Node wird neu abgefragt, damit die Position aktuell ist. */
    fun positionFromNode() = run {
        val si = g.session.refreshSelfInfo() ?: g.session.selfInfo.value
        val la = si?.latitude; val lo = si?.longitude
        if (la == null || lo == null) throw IOException("Der Node meldet keine Position – GPS am Node einschalten/Fix abwarten, oder Handy-GPS bzw. manuelle Eingabe nutzen")
        position.value = UserPosition(la, lo, PositionSource.NODE)
    }

    fun setPosition(lat: Double, lon: Double, src: PositionSource) { position.value = UserPosition(lat, lon, src) }
    fun setSource(s: PositionSource) { source.value = s }
    fun setRadius(r: NomadRequest.MapRadius) { radius.value = r }
    fun selectTarget(lat: Double, lon: Double) { target.value = lat to lon }

    /** Eigene Position wird (falls bekannt) immer als Punkt eingezeichnet, auch in Detailkarten. */
    private fun mark() = position.value?.let { it.lat to it.lon }

    fun requestMap(lat: Double, lon: Double, r: NomadRequest.MapRadius = radius.value, q: NomadRequest.MapQuality = quality.value) = run {
        val info = MapRequestInfo(lat, lon, r, q)
        val key = "%.4f,%.4f,%d,%s,%s".format(java.util.Locale.ROOT, lat, lon, r.meters, q.name, mark()?.let { "%.4f,%.4f".format(java.util.Locale.ROOT, it.first, it.second) } ?: "-")
        val view = mapCache[key] ?: kotlin.run {
            val sec = NomadRequest.parseMap(access.request(NomadRequest.map(lat, lon, r.meters, q, mark())))
            val bmp = BitmapFactory.decodeByteArray(sec.image, 0, sec.image.size) ?: throw IOException("Kartenbild nicht lesbar")
            MapView(info, sec, bmp).also { mapCache[key] = it }
        }
        map.value?.let { history.addLast(it) }
        while (history.size > 6) history.removeFirst()
        canGoBack.value = history.isNotEmpty()
        radius.value = r
        target.value = null
        map.value = view
    }

    /** Detailkarte um den angetippten Punkt oder einen Ort aus der Liste, mit nächstkleinerem Radius. */
    fun detailAt(lat: Double, lon: Double) = requestMap(lat, lon, (map.value?.request?.radius ?: radius.value).finer())

    fun back() { val prev = history.removeLastOrNull() ?: return; map.value = prev; radius.value = prev.request.radius; target.value = null; canGoBack.value = history.isNotEmpty() }

    /** Verschiebt den Ausschnitt um [dx]/[dy] Radien (Ost/Nord positiv) und fordert ihn an. */
    fun pan(dx: Double, dy: Double) {
        val m = map.value ?: return
        val r = m.request.radius.meters * 0.8
        val (lat, lon) = NomadRequest.pan(m.request.lat, m.request.lon, dx * r, dy * r)
        requestMap(lat, lon, m.request.radius)
    }

    fun radiusBy(delta: Int) {
        val m = map.value ?: return
        val i = (m.request.radius.ordinal + delta).coerceIn(0, NomadRequest.MapRadius.entries.size - 1)
        requestMap(m.request.lat, m.request.lon, NomadRequest.MapRadius.entries[i])
    }

    fun setQuality(q: NomadRequest.MapQuality) { quality.value = q }

    /** Alles Angezeigte verwerfen (z. B. beim Verlassen). */
    fun clear() { hits.value = emptyList(); article.value = null; map.value = null; mapCache.clear(); history.clear(); canGoBack.value = false; target.value = null; position.value = null; error.value = null }

    override fun onCleared() = clear()
}
