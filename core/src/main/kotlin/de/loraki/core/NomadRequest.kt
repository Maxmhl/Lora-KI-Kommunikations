package de.loraki.core

/**
 * Gezielte NOMAD-Anfragen (Artikel, Kartenausschnitt, …): kleine Anfrage rein, kompakte Antwort raus – über LoRa oder im WLAN
 * über das Gateway (`POST /lk/nomad`) im selben Format. Gegenstück: bridge/lkbridge/nomad_ops.py.
 */
object NomadRequest {
    data class Book(val id: String, val title: String, val lang: String, val articles: Int)
    data class Hit(val path: String, val title: String)
    data class Article(val total: Int, val offset: Int, val text: String, val summary: Boolean) {
        val end get() = offset + text.length
        val hasMore get() = !summary && end < total
    }
    data class MapRegion(val name: String, val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double, val maxZoom: Int) {
        fun covers(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
    }
    class MapSection(val format: String, val region: String, val zoom: Int, val size: Int,
                     val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double, val metersPerPixel: Double, val image: ByteArray)

    /** Bildstufen für Kartenausschnitte: Seitenlänge in Pixeln, Palettengröße und grobe Datenmenge (Erfahrungswerte). */
    enum class MapQuality(val label: String, val size: Int, val colors: Int, val approxBytes: Int) {
        SMALL("Klein (96 px)", 96, 8, 1600),
        MEDIUM("Mittel (128 px)", 128, 8, 2800),
        LARGE("Groß (160 px)", 160, 12, 4600),
        XL("Sehr groß (256 px)", 256, 24, 10800),
    }

    class ProtocolError(message: String) : Exception(message)

    fun books() = "books".toByteArray()
    fun search(book: String, query: String, limit: Int = 8) = "search\t$book\t${clean(query)}\t$limit".toByteArray()
    fun article(book: String, path: String, offset: Int = 0, length: Int = 1500, summary: Boolean = false) =
        "article\t$book\t$path\t$offset\t$length\t${if (summary) "summary" else "text"}".toByteArray()
    fun mapInfo() = "mapinfo".toByteArray()
    fun map(lat: Double, lon: Double, zoom: Int, q: MapQuality) =
        "map\t${"%.5f".format(java.util.Locale.ROOT, lat)}\t${"%.5f".format(java.util.Locale.ROOT, lon)}\t$zoom\t${q.size}\t${q.colors}".toByteArray()

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()

    private fun split(resp: ByteArray): Pair<List<String>, ByteArray> {
        val nl = resp.indexOf('\n'.code.toByte())
        val head = String(resp, 0, if (nl < 0) resp.size else nl, Charsets.UTF_8).split('\t')
        if (head.firstOrNull() == "error") throw ProtocolError(head.getOrNull(1) ?: "Fehler")
        if (head.firstOrNull() != "ok") throw ProtocolError("Unerwartete Antwort")
        return head to (if (nl < 0) ByteArray(0) else resp.copyOfRange(nl + 1, resp.size))
    }

    private fun rows(body: ByteArray) = String(body, Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { it.split('\t') }

    fun parseBooks(resp: ByteArray): List<Book> = rows(split(resp).second).map { r -> Book(r[0], r.getOrElse(1) { r[0] }, r.getOrElse(2) { "" }, r.getOrNull(3)?.toIntOrNull() ?: 0) }
    fun parseSearch(resp: ByteArray): List<Hit> = rows(split(resp).second).map { r -> Hit(r[0], r.getOrElse(1) { r[0] }) }

    fun parseArticle(resp: ByteArray): Article {
        val (h, body) = split(resp)
        if (h.size < 6) throw ProtocolError("Artikelkopf unvollständig")
        return Article(h[2].toInt(), h[3].toInt(), String(body, Charsets.UTF_8), h[5] == "summary")
    }

    fun parseMapInfo(resp: ByteArray): List<MapRegion> = rows(split(resp).second).map {
        MapRegion(it[0], it[1].toDouble(), it[2].toDouble(), it[3].toDouble(), it[4].toDouble(), it[5].toInt())
    }

    fun parseMap(resp: ByteArray): MapSection {
        val (h, body) = split(resp)
        if (h.size < 11) throw ProtocolError("Kartenkopf unvollständig")
        return MapSection(h[2], h[3], h[4].toInt(), h[5].toInt(), h[6].toDouble(), h[7].toDouble(), h[8].toDouble(), h[9].toDouble(), h[10].toDouble(), body)
    }

    /** Grobe Größe der Antwort in Byte – für die Zeitschätzung vor dem Senden. */
    fun approxArticleBytes(chars: Int) = (chars * 0.5).toInt() + 40

    /** Zeilenweise Verschiebung des Kartenmittelpunkts: Seitenlänge * [fraction] in Metern -> neue Koordinate. */
    fun pan(lat: Double, lon: Double, dxMeters: Double, dyMeters: Double): Pair<Double, Double> {
        val dLat = dyMeters / 111_320.0
        val dLon = dxMeters / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return (lat + dLat).coerceIn(-85.0, 85.0) to (lon + dLon).let { if (it > 180) it - 360 else if (it < -180) it + 360 else it }
    }
}
