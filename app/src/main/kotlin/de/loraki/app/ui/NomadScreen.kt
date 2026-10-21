package de.loraki.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import de.loraki.app.nomad.NomadViewModel
import de.loraki.core.InstalledItem
import de.loraki.core.Nomad
import de.loraki.core.RemoteItem
import androidx.work.WorkInfo

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

@Composable
fun NomadScreen(vm: NomadViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val titles = listOf("Oberfläche", "Bibliothek", "Karte", "Wiki")
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, maxLines = 1) }) }
        }
        when (tab) {
            0 -> NomadWebTab(vm, onOpenLibrary = { tab = 1 })
            1 -> LibraryTab(vm)
            2 -> MapTab(vm)
            3 -> WikiTab(vm)
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Tab 1: dieselbe Oberfläche wie im Browser (NOMAD-Command-Center) über das Gateway
// ----------------------------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun NomadWebTab(vm: NomadViewModel, onOpenLibrary: () -> Unit) {
    val ctx = LocalContext.current
    val base = vm.settings.nomadUrl
    val token = vm.settings.nomadToken
    if (base.isBlank() || token.isBlank()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Noch kein Server verbunden. Trage in der Bibliothek die Adresse und das Token des Gateways ein.")
                Button(onOpenLibrary) { Text("Server einrichten") }
            }
        }
        return
    }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var canBack by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var chooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        chooser?.onReceiveValue(uris.toTypedArray()); chooser = null
    }
    BackHandler(enabled = canBack) { web?.goBack() }

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            WebView(c).apply {
                val wv = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                CookieManager.getInstance().apply {
                    setAcceptCookie(true); setAcceptThirdPartyCookies(wv, true)
                    setCookie(base, "lk_token=$token; Path=/"); flush()
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { loading = true; error = null }
                    override fun onPageFinished(v: WebView, url: String) { loading = false; canBack = v.canGoBack() }
                    override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                        if (r.isForMainFrame) error = "Server nicht erreichbar (${e.description}). WLAN/VPN verbunden?"
                    }
                    override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                        if (r.isForMainFrame && e.statusCode == 401) error = "Token abgelehnt – Token in der Bibliothek prüfen."
                        if (r.isForMainFrame && e.statusCode == 502) error = "Das Gateway erreicht NOMAD nicht. Läuft NOMAD auf dem Server?"
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        val u = r.url
                        if (u.host == Uri.parse(base).host && u.port == Uri.parse(base).port) return false
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                        chooser?.onReceiveValue(null); chooser = cb
                        picker.launch("*/*"); return true
                    }
                }
                loadUrl("$base/", mapOf("Authorization" to "Bearer $token"))
                web = this
            }
        }, onRelease = { it.destroy() })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { msg ->
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    Button({ error = null; web?.reload() }) { Text("Erneut versuchen") }
                    OutlinedButton(onOpenLibrary) { Text("Server-Einstellungen") }
                }
                Text("Offline stehen Karte und Wiki aus der Bibliothek zur Verfügung.", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Tab 2: Bibliothek – Server, Auswahl, Downloads, Update-Intervall
// ----------------------------------------------------------------------------------------------

private fun kindTitle(kind: String) = when (kind) {
    Nomad.KIND_MAP -> "Karten"; Nomad.KIND_ZIM -> "Bibliotheken (Wikipedia & Co.)"; else -> "Kartenstil & Schriften"
}

@Composable
private fun LibraryTab(vm: NomadViewModel) {
    val manifest by vm.manifest.collectAsState()
    val installed by vm.installed.collectAsState()
    val wanted by vm.wanted.collectAsState()
    val progress by vm.progress.collectAsState()
    val checking by vm.checking.collectAsState()
    val message by vm.message.collectAsState()
    val conn by vm.conn.collectAsState()
    val dl by vm.downloadState.collectAsState()
    var url by rememberSaveable { mutableStateOf(vm.settings.nomadUrl) }
    var token by rememberSaveable { mutableStateOf(vm.settings.nomadToken) }
    var interval by remember { mutableStateOf(vm.settings.nomadIntervalDays) }
    var wifiOnly by remember { mutableStateOf(vm.settings.nomadWifiOnly) }
    var auto by remember { mutableStateOf(vm.settings.nomadAutoDownload) }
    var menu by remember { mutableStateOf(false) }
    val plan = remember(manifest, installed, wanted) { vm.repo.plan() }
    val items = manifest?.items ?: emptyList()
    val byKind = items.groupBy { it.kind }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Server (Gateway der Bridge)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(url, { url = it }, label = { Text("Adresse, z. B. http://192.168.1.10:8765") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.saveServer(url, token); vm.test() }, enabled = url.isNotBlank() && token.isNotBlank()) { Text("Speichern & testen") }
                    }
                    conn?.let { Text(it, fontSize = 13.sp) }
                    Text("Verbindung nur über WLAN/VPN – nicht über LoRa. Das Token schützt NOMAD, das selbst keine Anmeldung hat.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Aktualisierung", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Prüfen: ", Modifier.weight(1f))
                        Box {
                            OutlinedButton({ menu = true }) { Text(when (interval) { 0 -> "nie (nur manuell)"; 1 -> "täglich"; 7 -> "wöchentlich"; 30 -> "monatlich"; else -> "alle $interval Tage" }) }
                            DropdownMenu(menu, { menu = false }) {
                                listOf(0 to "nie (nur manuell)", 1 to "täglich", 7 to "wöchentlich", 30 to "monatlich").forEach { (d, label) ->
                                    DropdownMenuItem({ Text(label) }, { interval = d; vm.setInterval(d); menu = false })
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Nur im WLAN laden", Modifier.weight(1f)); Switch(wifiOnly, { wifiOnly = it; vm.setWifiOnly(it) }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Updates automatisch laden", Modifier.weight(1f)); Switch(auto, { auto = it; vm.setAuto(it) }) }
                    Text(if (auto) "Neue Versionen der gewählten Inhalte werden im Intervall automatisch geladen." else "Bei neuen Versionen gibt es nur eine Benachrichtigung; geladen wird erst auf Tippen.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val last = vm.settings.nomadLastCheck
                    Text("Zuletzt geprüft: " + if (last == 0L) "noch nie" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last)), fontSize = 12.sp)
                    Text("Belegt: ${formatBytes(vm.repo.usedBytes())} · frei: ${formatBytes(vm.repo.freeBytes())}", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ vm.check() }, enabled = !checking && vm.repo.configured) { Text(if (checking) "Prüfe …" else "Jetzt prüfen") }
                        if (plan.update.isNotEmpty()) Button({ vm.updateAll() }) { Text("${plan.update.size} Update(s) laden") }
                        if (dl != null) OutlinedButton({ vm.cancel() }) { Text("Abbrechen") }
                    }
                    if (dl == WorkInfo.State.ENQUEUED) Text(if (wifiOnly) "Wartet auf WLAN/Netzwerk …" else "Wartet auf Netzwerk …", fontSize = 12.sp)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            }
        }
        if (items.isEmpty()) item { Text(if (vm.repo.configured) "Noch kein Manifest geladen. „Jetzt prüfen“ tippen." else "Server eintragen, um Inhalte zu sehen.", fontSize = 13.sp) }
        listOf(Nomad.KIND_MAP, Nomad.KIND_ZIM, Nomad.KIND_ASSETS).forEach { kind ->
            val list = byKind[kind].orEmpty()
            if (list.isNotEmpty()) {
                item(key = "h_$kind") { Text(kindTitle(kind), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                items(list, key = { it.id }) { r ->
                    ItemRow(r, installed.firstOrNull { it.series == r.series }, r.series in wanted, progress[r.id], plan.update.any { it.series == r.series }, vm)
                }
            }
        }
        val orphans = plan.orphans
        if (orphans.isNotEmpty()) {
            item { Text("Nur auf dem Handy (nicht mehr auf dem Server)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            items(orphans, key = { it.series }) { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(i.name); Text("${i.version} · ${formatBytes(i.size)}", fontSize = 12.sp) }
                    TextButton({ vm.remove(i) }) { Text("Löschen") }
                }
            }
        }
        item { LicenseNote() }
    }
}

@Composable
private fun ItemRow(r: RemoteItem, have: InstalledItem?, isWanted: Boolean, p: de.loraki.app.nomad.DlProgress?, hasUpdate: Boolean, vm: NomadViewModel) {
    var confirm by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    p != null -> "Lädt … ${formatBytes(p.done)} / ${formatBytes(p.total)}"
                    have == null -> "Nicht geladen · ${r.version} · ${formatBytes(r.size)}"
                    hasUpdate -> "Update: ${have.version} → ${r.version} · ${formatBytes(r.size)}"
                    else -> "Auf dem Handy · ${have.version} · ${formatBytes(have.size)}"
                }, fontSize = 12.sp, color = if (hasUpdate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                p != null -> {}
                have == null -> Button({ if (r.size > 1L shl 30) confirm = true else vm.download(r) }) { Text("Laden") }
                hasUpdate -> Button({ vm.download(r) }) { Text("Update") }
            }
            if (have != null && p == null) TextButton({ vm.remove(have) }) { Text("Löschen") }
        }
        if (p != null && p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Großer Download") },
        text = { Text("${r.name} ist ${formatBytes(r.size)} groß. Der Download läuft im Hintergrund weiter und wird bei Abbruch fortgesetzt. Empfohlen: WLAN und genug freier Speicher.") },
        confirmButton = { TextButton({ confirm = false; vm.download(r) }) { Text("Laden") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Abbrechen") } },
    )
}

@Composable
private fun LicenseNote() {
    Text("Kartendaten © OpenStreetMap-Mitwirkende (ODbL), Basiskarte Protomaps. Karten-Viewer: MapLibre GL JS und PMTiles (BSD-3-Clause). " +
        "Bibliotheken (ZIM) stehen unter den Lizenzen ihrer Inhalte, z. B. Wikipedia CC BY-SA. Project NOMAD (Apache-2.0) läuft nur auf dem Server und ist nicht in der App enthalten.",
        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
}

// ----------------------------------------------------------------------------------------------
// Tab 3: Offline-Karte
// ----------------------------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapTab(vm: NomadViewModel) {
    val installed by vm.installed.collectAsState()
    val ctx = LocalContext.current
    val hasMap = installed.any { it.kind == Nomad.KIND_MAP && it.name != "world" } && installed.any { it.kind == Nomad.KIND_ASSETS }
    if (!hasMap) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Noch keine Offline-Karte auf dem Handy. In der Bibliothek eine Region und den Kartenstil laden – einmalig, danach funktioniert die Karte ohne Netz.")
        }
        return
    }
    var pendingGeo by remember { mutableStateOf<Pair<String, GeolocationPermissions.Callback>?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        pendingGeo?.let { (origin, cb) -> cb.invoke(origin, ok, false) }; pendingGeo = null
    }
    var web by remember { mutableStateOf<WebView?>(null) }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
        WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = vm.content.intercept(r)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) cb.invoke(origin, true, false) else { pendingGeo = origin to cb; permLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION) }
                }
            }
            loadUrl("https://lk.local/map")
            web = this
        }
    }, onRelease = { it.destroy() })
}

// ----------------------------------------------------------------------------------------------
// Tab 4: Offline-Wiki (ZIM)
// ----------------------------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WikiTab(vm: NomadViewModel) {
    val installed by vm.installed.collectAsState()
    val zims = installed.filter { it.kind == Nomad.KIND_ZIM }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // Titel -> Pfad
    var web by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val zim = selected ?: zims.firstOrNull()?.file

    if (zims.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Noch keine Bibliothek auf dem Handy. In der Bibliothek z. B. Wikipedia laden – einmalig, danach offline lesbar.")
        }
        return
    }
    BackHandler(enabled = canBack) { web?.goBack() }
    LaunchedEffect(query, zim) {
        val z = zim?.let { vm.content.zim(it) }
        suggestions = if (z == null || query.length < 2) emptyList() else
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { z.suggest(query, 12).map { it.displayTitle to it.url } }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var menu by remember { mutableStateOf(false) }
            if (zims.size > 1) Box {
                OutlinedButton({ menu = true }) { Text(zims.firstOrNull { it.file == zim }?.name?.take(14) ?: "Bibliothek", maxLines = 1) }
                DropdownMenu(menu, { menu = false }) { zims.forEach { z -> DropdownMenuItem({ Text(z.name) }, { selected = z.file; menu = false; web?.loadUrl("https://lk.local/zim/${z.file}/") }) } }
            }
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("Titel suchen") }, singleLine = true)
            OutlinedButton({ query = ""; web?.loadUrl("https://lk.local/zim/$zim/") }) { Text("Start") }
        }
        if (suggestions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                suggestions.take(8).forEach { (title, path) ->
                    Text(title, Modifier.fillMaxWidth().clickable {
                        query = ""; suggestions = emptyList()
                        web?.loadUrl("https://lk.local/zim/$zim/" + Uri.encode(path, "/"))
                    }.padding(vertical = 8.dp, horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = vm.content.intercept(r)
                    override fun onPageFinished(v: WebView, url: String) { canBack = v.canGoBack() }
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        if (r.url.host == "lk.local") return false
                        Toast.makeText(c, "Externer Link – offline nicht verfügbar", Toast.LENGTH_SHORT).show(); return true
                    }
                }
                loadUrl("https://lk.local/zim/$zim/")
                web = this
            }
        }, onRelease = { it.destroy() })
    }
}
