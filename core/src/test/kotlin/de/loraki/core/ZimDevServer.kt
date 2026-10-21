package de.loraki.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder

/** Entwicklungshilfe: liefert ZIM-Inhalte unter /zim/<datei>/<pfad> wie die App (LocalContent) – für Browser-Tests. */
fun main(args: Array<String>) {
    val file = File(args[0])
    val port = args.getOrNull(1)?.toInt() ?: 8098
    val zim = ZimFile(file)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/") { ex ->
        val path = URLDecoder.decode(ex.requestURI.rawPath, "UTF-8")
        val rest = path.removePrefix("/zim/").substringAfter('/', "")
        val idx = if (path.startsWith("/zim/")) (if (rest.isEmpty()) zim.mainEntryIndex() else zim.findByUrlPath(rest)) else null
        if (idx == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
        val e = zim.entry(idx)
        val data = e.data()
        ex.responseHeaders.add("Content-Type", e.mime)
        ex.sendResponseHeaders(200, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }
    server.start()
    println("ZIM-Server auf http://127.0.0.1:$port/zim/${file.name}/")
    Thread.currentThread().join()
}
