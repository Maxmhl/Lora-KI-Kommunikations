package de.loraki.core

import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimaler Leser für ZIM-Dateien (Kiwix/OpenZIM, Format 5/6) – ohne native Bibliothek und ohne GPL-Code.
 * Unterstützt Artikel/Bilder/Metadaten, Weiterleitungen, xz- und zstd-Cluster sowie Titelsuche (Präfix).
 * Eine Volltextsuche (Xapian-Index in der ZIM) gibt es bewusst nicht.
 * Referenz: https://wiki.openzim.org/wiki/ZIM_file_format
 */
class ZimFile(private val file: File) : Closeable {
    class Dirent(
        val index: Int, val mime: Int, val ns: Char, val url: String, val title: String,
        val cluster: Int, val blob: Int, val redirect: Int,
    ) {
        val isRedirect get() = mime == REDIRECT
        val isContent get() = mime < LINKTARGET
        val displayTitle get() = title.ifEmpty { url }
    }

    class Entry(val dirent: Dirent, val mime: String, val data: () -> ByteArray)

    private val raf = RandomAccessFile(file, "r")
    val entryCount: Int
    val clusterCount: Int
    val majorVersion: Int
    val minorVersion: Int
    private val urlPtrPos: Long
    private val titlePtrPos: Long
    private val clusterPtrPos: Long
    private val mimeListPos: Long
    private val mainPage: Long
    private val checksumPos: Long
    val mimeTypes: List<String>

    /** Neues Namensraum-Schema (ab Minor 1): Inhalt liegt in 'C', Metadaten in 'M'. */
    val newNamespaces: Boolean

    private val direntCache = object : LinkedHashMap<Int, Dirent>(512, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Dirent>?) = size > 2048
    }
    private var clusterCache: Pair<Int, ByteArray>? = null
    private var titleList: IntArray? = null

    init {
        val h = readAt(0, 80)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != MAGIC) throw IOException("Keine ZIM-Datei: ${file.name}")
        majorVersion = b.short.toInt() and 0xFFFF
        minorVersion = b.short.toInt() and 0xFFFF
        b.position(24)
        entryCount = b.int
        clusterCount = b.int
        urlPtrPos = b.long
        titlePtrPos = b.long
        clusterPtrPos = b.long
        mimeListPos = b.long
        mainPage = b.int.toLong() and 0xFFFFFFFFL
        b.int // layoutPage
        checksumPos = b.long
        newNamespaces = minorVersion >= 1
        mimeTypes = readMimeList()
    }

    // ------------------------------------------------------------ Rohzugriff

    private fun readAt(pos: Long, len: Int): ByteArray {
        val avail = (raf.length() - pos).coerceAtMost(len.toLong()).toInt()
        if (avail <= 0) throw IOException("Lesen hinter Dateiende")
        val buf = ByteArray(avail)
        synchronized(raf) { raf.seek(pos); raf.readFully(buf) }
        return buf
    }

    private fun u64(pos: Long) = ByteBuffer.wrap(readAt(pos, 8)).order(ByteOrder.LITTLE_ENDIAN).long
    private fun u32(pos: Long) = ByteBuffer.wrap(readAt(pos, 4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun readMimeList(): List<String> {
        val out = ArrayList<String>()
        var pos = mimeListPos
        while (true) {
            val chunk = readAt(pos, 256)
            val end = chunk.indexOf(0)
            if (end <= 0) break
            out += String(chunk, 0, end, Charsets.UTF_8)
            pos += end + 1
        }
        return out
    }

    // ------------------------------------------------------------ Verzeichniseinträge

    fun dirent(index: Int): Dirent = synchronized(direntCache) {
        direntCache[index] ?: parseDirent(index).also { direntCache[index] = it }
    }

    private fun parseDirent(index: Int): Dirent {
        if (index < 0 || index >= entryCount) throw IOException("Eintrag $index existiert nicht")
        val pos = u64(urlPtrPos + 8L * index)
        val buf = readAt(pos, 4096)
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val mime = b.short.toInt() and 0xFFFF
        b.get() // parameter length
        val ns = (b.get().toInt() and 0xFF).toChar()
        b.int // revision
        var cluster = -1
        var blob = -1
        var redirect = -1
        when {
            mime == REDIRECT -> redirect = b.int
            mime >= LINKTARGET -> {}
            else -> { cluster = b.int; blob = b.int }
        }
        val strings = String(buf, b.position(), buf.size - b.position(), Charsets.UTF_8)
        val parts = strings.split('\u0000', limit = 3)
        return Dirent(index, mime, ns, parts[0], parts.getOrElse(1) { "" }, cluster, blob, redirect)
    }

    private fun compareKey(ns: Char, url: String, d: Dirent): Int {
        if (ns != d.ns) return ns.compareTo(d.ns)
        return compareBytes(url.toByteArray(Charsets.UTF_8), d.url.toByteArray(Charsets.UTF_8))
    }

    /** Index des Eintrags (Namensraum, Pfad) oder null. Die URL-Liste ist nach (Namensraum, Pfad) sortiert. */
    fun find(ns: Char, path: String): Int? {
        var lo = 0
        var hi = entryCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareKey(ns, path, dirent(mid))
            when {
                c == 0 -> return mid
                c < 0 -> hi = mid - 1
                else -> lo = mid + 1
            }
        }
        return null
    }

    /** Löst URL-Pfade aus dem Browser auf: neues Schema `Pfad` (→ 'C'), altes Schema `A/Pfad`. */
    fun findByUrlPath(path: String): Int? {
        val p = path.trimStart('/')
        if (newNamespaces) {
            find('C', p)?.let { return it }
            if (p.length > 2 && p[1] == '/') find(p[0], p.substring(2))?.let { return it }
            return null
        }
        if (p.length > 2 && p[1] == '/') return find(p[0], p.substring(2))
        return null
    }

    fun resolve(d: Dirent): Dirent {
        var cur = d
        var hops = 0
        while (cur.isRedirect) {
            if (++hops > 16) throw IOException("Weiterleitungsschleife")
            cur = dirent(cur.redirect)
        }
        return cur
    }

    // ------------------------------------------------------------ Inhalte

    fun entry(index: Int): Entry {
        val d = resolve(dirent(index))
        if (!d.isContent) throw IOException("Eintrag ohne Inhalt")
        return Entry(d, mimeTypes.getOrElse(d.mime) { "application/octet-stream" }) { readBlob(d.cluster, d.blob) }
    }

    fun readBlob(cluster: Int, blob: Int): ByteArray {
        if (cluster < 0 || cluster >= clusterCount) throw IOException("Cluster $cluster existiert nicht")
        val start = u64(clusterPtrPos + 8L * cluster)
        val end = if (cluster + 1 < clusterCount) u64(clusterPtrPos + 8L * (cluster + 1)) else checksumPos
        val info = readAt(start, 1)[0].toInt() and 0xFF
        val comp = info and 0x0F
        val extended = info and 0x10 != 0
        val osize = if (extended) 8 else 4
        if (comp <= 1) { // unkomprimiert: nur den benötigten Teil lesen (große Medien nicht komplett laden)
            val first = if (extended) u64(start + 1) else u32(start + 1)
            val count = (first / osize).toInt() - 1
            if (blob < 0 || blob >= count) throw IOException("Blob $blob außerhalb des Clusters")
            val a = if (extended) u64(start + 1 + 8L * blob) else u32(start + 1 + 4L * blob)
            val z = if (extended) u64(start + 1 + 8L * (blob + 1)) else u32(start + 1 + 4L * (blob + 1))
            return if (z == a) ByteArray(0) else readAt(start + 1 + a, (z - a).toInt())
        }
        val data = synchronized(this) {
            clusterCache?.takeIf { it.first == cluster }?.second ?: decompress(comp, readAt(start + 1, (end - start - 1).toInt()))
                .also { clusterCache = cluster to it }
        }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val first = if (extended) b.getLong(0) else b.getInt(0).toLong() and 0xFFFFFFFFL
        val count = (first / osize).toInt() - 1
        if (blob < 0 || blob >= count) throw IOException("Blob $blob außerhalb des Clusters")
        val a = if (extended) b.getLong(osize * blob) else b.getInt(osize * blob).toLong() and 0xFFFFFFFFL
        val z = if (extended) b.getLong(osize * (blob + 1)) else b.getInt(osize * (blob + 1)).toLong() and 0xFFFFFFFFL
        return data.copyOfRange(a.toInt(), z.toInt())
    }

    private fun decompress(type: Int, input: ByteArray): ByteArray = when (type) {
        4 -> XZInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
        5 -> com.github.luben.zstd.ZstdInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
        else -> throw IOException("Unbekannte Kompression: $type")
    }

    /** Metadatum (z. B. "Title", "Description", "Language"). */
    fun metadata(key: String): String? {
        val idx = find(if (newNamespaces) 'M' else 'M', key) ?: return null
        return runCatching { String(entry(idx).data(), Charsets.UTF_8) }.getOrNull()
    }

    /** Startseite: Header-Eintrag, sonst 'W/mainPage' (neues Schema). */
    fun mainEntryIndex(): Int? {
        if (mainPage != 0xFFFFFFFFL && mainPage < entryCount) return mainPage.toInt()
        return find('W', "mainPage")
    }

    // ------------------------------------------------------------ Titelsuche

    private fun titles(): IntArray {
        titleList?.let { return it }
        val list = if (titlePtrPos != -1L) {
            val n = entryCount
            val buf = ByteBuffer.wrap(readAt(titlePtrPos, 4 * n)).order(ByteOrder.LITTLE_ENDIAN)
            IntArray(n) { buf.int }
        } else {
            val idx = find('X', "listing/titleOrdered/v1") ?: return IntArray(0).also { titleList = it }
            val b = ByteBuffer.wrap(entry(idx).data()).order(ByteOrder.LITTLE_ENDIAN)
            IntArray(b.remaining() / 4) { b.int }
        }
        titleList = list
        return list
    }

    private val articleNs get() = if (newNamespaces) 'C' else 'A'

    /** Artikel, deren Titel mit [prefix] beginnt (Groß-/Kleinschreibung wird beachtet; [suggest] probiert Varianten). */
    fun titlePrefix(prefix: String, limit: Int = 20): List<Dirent> {
        val list = titles()
        if (list.isEmpty() || prefix.isEmpty()) return emptyList()
        val key = prefix.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = list.size
        while (lo < hi) { // untere Schranke: erster Eintrag mit Titel >= prefix
            val mid = (lo + hi) ushr 1
            val d = dirent(list[mid])
            val c = if (newNamespaces) compareBytes(d.displayTitle.toByteArray(Charsets.UTF_8), key)
            else if (d.ns != articleNs) d.ns.compareTo(articleNs) else compareBytes(d.displayTitle.toByteArray(Charsets.UTF_8), key)
            if (c < 0) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<Dirent>()
        var i = lo
        while (i < list.size && out.size < limit) {
            val d = dirent(list[i])
            if (!d.displayTitle.startsWith(prefix)) break
            if (d.ns == articleNs || !newNamespaces && d.ns == articleNs) out += d
            i++
        }
        return out
    }

    /** Vorschläge mit Varianten der Groß-/Kleinschreibung ("paris" → "Paris"); Weiterleitungen werden aufgelöst. */
    fun suggest(query: String, limit: Int = 20): List<Dirent> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val variants = linkedSetOf(
            q, q.replaceFirstChar { it.uppercase() }, q.lowercase().replaceFirstChar { it.uppercase() },
            q.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }, q.uppercase(), q.lowercase(),
        )
        val seen = LinkedHashMap<Int, Dirent>()
        for (v in variants) {
            for (d in titlePrefix(v, limit)) {
                val target = runCatching { resolve(d) }.getOrNull() ?: continue
                seen.putIfAbsent(target.index, if (d.isRedirect) d else target)
            }
            if (seen.size >= limit) break
        }
        return seen.values.take(limit)
    }

    override fun close() = raf.close()

    companion object {
        private const val MAGIC = 72173914
        private const val REDIRECT = 0xFFFF
        private const val LINKTARGET = 0xFFFE

        private fun compareBytes(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (c != 0) return c
            }
            return a.size - b.size
        }
    }
}
