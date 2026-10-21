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
    data class Place(val name: String, val lat: Double, val lon: Double, val kind: String)

    class MapSection(val format: String, val region: String, val zoom: Double, val size: Int,
                     val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double, val metersPerPixel: Double,
                     val image: ByteArray, val places: List<Place>) {
        private fun merc(lat: Double) = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2))

        /** Bildposition (0..1 von links/oben) -> Koordinate (Web-Mercator, wie der Server rendert). */
        fun toLatLon(fx: Double, fy: Double): Pair<Double, Double> {
            val lon = minLon + fx * (maxLon - minLon)
            val y = merc(maxLat) + fy * (merc(minLat) - merc(maxLat))
            return Math.toDegrees(2 * Math.atan(Math.exp(y)) - Math.PI / 2) to lon
        }

        /** Koordinate -> Bildposition (0..1), null außerhalb des Ausschnitts. */
        fun toFraction(lat: Double, lon: Double): Pair<Double, Double>? {
            val fx = (lon - minLon) / (maxLon - minLon)
            val fy = (merc(lat) - merc(maxLat)) / (merc(minLat) - merc(maxLat))
            return if (fx in 0.0..1.0 && fy in 0.0..1.0) fx to fy else null
        }
    }

    /** Auswählbare Radien (Meter vom Mittelpunkt bis zum Bildrand). */
    enum class MapRadius(val label: String, val meters: Int) {
        R250("250 m", 250), R500("500 m", 500), R1K("1 km", 1000), R2K("2 km", 2000), R5K("5 km", 5000), R10K("10 km", 10000), R25K("25 km", 25000);

        /** Nächstkleinerer Radius für eine Detailkarte (mindestens 250 m). */
        fun finer(): MapRadius = entries.getOrNull(ordinal - 1) ?: this
    }

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
    /** Kartenausschnitt um (lat, lon) mit [radiusMeters] bis zum Bildrand; [mark] = eigene Position, die eingezeichnet wird. */
    fun map(lat: Double, lon: Double, radiusMeters: Int, q: MapQuality, mark: Pair<Double, Double>? = null): ByteArray {
        fun f(v: Double) = "%.5f".format(java.util.Locale.ROOT, v)
        val base = "map\t${f(lat)}\t${f(lon)}\t$radiusMeters\t${q.size}\t${q.colors}"
        return (if (mark != null) "$base\t${f(mark.first)}\t${f(mark.second)}" else base).toByteArray()
    }

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

    /** Kopf `ok map fmt region zoom size minlon minlat maxlon maxlat m/px bildbytes`, danach Bild, danach Orte (`name lat lon art`). */
    fun parseMap(resp: ByteArray): MapSection {
        val (h, body) = split(resp)
        if (h.size < 12) throw ProtocolError("Kartenkopf unvollständig")
        val n = h[11].toInt()
        if (n < 0 || n > body.size) throw ProtocolError("Kartenbild unvollständig")
        val places = rows(body.copyOfRange(n, body.size)).filter { it.size >= 3 }.map { Place(it[0], it[1].toDouble(), it[2].toDouble(), it.getOrElse(3) { "" }) }
        return MapSection(h[2], h[3], h[4].toDouble(), h[5].toInt(), h[6].toDouble(), h[7].toDouble(), h[8].toDouble(), h[9].toDouble(),
            h[10].toDouble(), body.copyOfRange(0, n), places)
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
