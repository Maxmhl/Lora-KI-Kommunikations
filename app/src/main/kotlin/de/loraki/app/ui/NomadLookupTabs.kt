package de.loraki.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.loraki.app.nomad.NomadLookupViewModel
import de.loraki.app.nomad.NomadMode
import de.loraki.app.nomad.PositionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import de.loraki.core.NomadRequest
import kotlinx.coroutines.delay

/** Zeigt den Verbindungsweg und – bei laufender LoRa-Anfrage – Fortschritt und Wartezeit. */
@Composable
private fun ModeBanner(vm: NomadLookupViewModel, busy: Boolean) {
    val mode by vm.mode.collectAsState()
    val activity by vm.activity.collectAsState()
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(busy) { elapsed = 0; while (busy) { delay(1000); elapsed++ } }
    LaunchedEffect(Unit) { while (true) { vm.refreshMode(); delay(20_000) } }
    Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = when (mode) {
        NomadMode.WLAN -> MaterialTheme.colorScheme.primaryContainer
        NomadMode.LORA -> MaterialTheme.colorScheme.tertiaryContainer
        NomadMode.NONE -> MaterialTheme.colorScheme.errorContainer })) {
        Column(Modifier.padding(10.dp)) {
            Text(when (mode) {
                NomadMode.WLAN -> "Lokales WLAN – schnell"
                NomadMode.LORA -> "Per LoRa – langsam: Antworten brauchen je nach Größe Minuten (gesetzliche Sendepausen)"
                NomadMode.NONE -> "Keine Verbindung – WLAN/VPN (Server in den Einstellungen) oder MeshCore-Node verbinden"
            }, fontSize = 13.sp)
            if (busy) {
                Text((activity ?: "Wird abgerufen …") + if (mode == NomadMode.LORA) "  ·  Wartezeit %d:%02d".format(elapsed / 60, elapsed % 60) else "", fontSize = 12.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

private fun eta(seconds: Double?): String = if (seconds == null) "" else " · ca. ${formatDuration(seconds)}"

// ----------------------------------------------------------------------------------------------
// Wiki: gezielt Artikel anfragen
// ----------------------------------------------------------------------------------------------

@Composable
fun WikiRequestTab(vm: NomadLookupViewModel) {
    val mode by vm.mode.collectAsState()
    val books by vm.books.collectAsState()
    val book by vm.book.collectAsState()
    val hits by vm.hits.collectAsState()
    val article by vm.article.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(mode) { if (mode == NomadMode.WLAN && books.isEmpty()) vm.loadBooks() }

    Column(Modifier.fillMaxSize()) {
        ModeBanner(vm, busy)
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (books.isEmpty()) {
                OutlinedButton({ vm.loadBooks() }, enabled = !busy && mode != NomadMode.NONE) { Text("Bibliotheken laden" + eta(vm.access.estimateSeconds(120))) }
            } else Box {
                OutlinedButton({ menu = true }) { Text(books.firstOrNull { it.id == book }?.title ?: "Bibliothek", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(menu, { menu = false }) { books.forEach { b -> DropdownMenuItem({ Text("${b.title} (${b.lang})") }, { vm.book.value = b.id; menu = false }) } }
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("Suchbegriff") }, singleLine = true)
            Button({ vm.search(query) }, enabled = query.isNotBlank() && !busy && book != null) { Text("Suchen") }
        }
        if (mode == NomadMode.LORA && query.isNotBlank()) Text("Suche: Antwort ca. ${formatDuration(vm.access.estimateSeconds(250) ?: 0.0)}", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }

        val a = article
        if (a != null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ vm.article.value = null }) { Text("← Treffer") }
                    Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(markdown(a.text))
                Text(if (a.summary) "KI-Kurzfassung" else "${a.text.length} von ${a.total} Zeichen", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (!a.summary && a.text.length < a.total)
                        OutlinedButton({ vm.moreOfArticle() }, enabled = !busy) { Text("Mehr lesen" + eta(vm.access.estimateSeconds(NomadRequest.approxArticleBytes(1500)))) }
                    if (!a.summary) OutlinedButton({ vm.summarize() }, enabled = !busy) { Text("Kurzfassung (KI)" + eta(vm.access.estimateSeconds(450))) }
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                hits.forEach { h ->
                    Text(h.title, Modifier.fillMaxWidth().clickable(enabled = !busy) { vm.openArticle(h) }.padding(horizontal = 16.dp, vertical = 12.dp))
                    HorizontalDivider()
                }
                if (hits.isNotEmpty() && mode == NomadMode.LORA)
                    Text("Beim Öffnen werden zuerst die ersten ca. ${vm.firstChunk()} Zeichen geladen (${formatDuration(vm.access.estimateSeconds(NomadRequest.approxArticleBytes(vm.firstChunk())) ?: 0.0)}); der Rest auf Wunsch.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                if (hits.isEmpty() && !busy) Text("Nichts wird auf dem Handy gespeichert: Du fragst gezielt einen Eintrag an, der Server schickt nur diesen.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Karte: gezielt einen Ausschnitt anfragen
// ----------------------------------------------------------------------------------------------

@SuppressLint("MissingPermission")
private fun currentLocation(ctx: Context, onResult: (Location?) -> Unit) {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return onResult(null)
    val last = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        lm.getCurrentLocation(provider, null, ctx.mainExecutor) { onResult(it ?: last) }
    } else onResult(last)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MapRequestTab(vm: NomadLookupViewModel) {
    val ctx = LocalContext.current
    val mode by vm.mode.collectAsState()
    val map by vm.map.collectAsState()
    val regions by vm.regions.collectAsState()
    val quality by vm.quality.collectAsState()
    val radius by vm.radius.collectAsState()
    val position by vm.position.collectAsState()
    val source by vm.source.collectAsState()
    val target by vm.target.collectAsState()
    val canBack by vm.canGoBack.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var manualLat by rememberSaveable { mutableStateOf("") }
    var manualLon by rememberSaveable { mutableStateOf("") }
    var qMenu by remember { mutableStateOf(false) }
    var rMenu by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    fun fromPhone() {
        locating = true
        currentLocation(ctx) { loc ->
            locating = false
            if (loc == null) vm.error.value = "Handy-Standort nicht verfügbar (GPS an? Draußen versuchen)"
            else vm.setPosition(loc.latitude, loc.longitude, PositionSource.PHONE)
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.any { it }) fromPhone() else vm.error.value = "Standort-Berechtigung fehlt" }

    fun fetchPosition() {
        when (source) {
            PositionSource.NODE -> vm.positionFromNode()
            PositionSource.PHONE -> {
                val ok = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (ok) fromPhone() else perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            PositionSource.MANUAL -> {
                val la = manualLat.replace(',', '.').toDoubleOrNull(); val lo = manualLon.replace(',', '.').toDoubleOrNull()
                if (la == null || lo == null || la !in -85.0..85.0 || lo !in -180.0..180.0) vm.error.value = "Bitte gültige Koordinaten eingeben (z. B. 49.4075 und 8.6935)"
                else vm.setPosition(la, lo, PositionSource.MANUAL)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ModeBanner(vm, busy)
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("1 · Position", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PositionSource.entries.forEach { s -> androidx.compose.material3.FilterChip(source == s, { vm.setSource(s) }, label = { Text(s.label) }) }
            }
            if (source == PositionSource.MANUAL) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(manualLat, { manualLat = it }, Modifier.weight(1f), label = { Text("Breite") }, singleLine = true)
                OutlinedTextField(manualLon, { manualLon = it }, Modifier.weight(1f), label = { Text("Länge") }, singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ fetchPosition() }, enabled = !busy && !locating) { Text(if (locating) "Suche …" else if (source == PositionSource.MANUAL) "Übernehmen" else "Position holen") }
                Text(position?.let { "%.5f, %.5f (%s)".format(java.util.Locale.ROOT, it.lat, it.lon, it.source.label) } ?: "noch keine Position", fontSize = 12.sp)
            }
            if (source == PositionSource.NODE) Text("Nutzt die vom Node gemeldete Position (GPS am Node muss aktiv sein und einen Fix haben).", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("2 · Radius und Detailgrad", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton({ rMenu = true }) { Text("Radius: ${radius.label}") }
                    DropdownMenu(rMenu, { rMenu = false }) { NomadRequest.MapRadius.entries.forEach { r -> DropdownMenuItem({ Text(r.label) }, { vm.setRadius(r); rMenu = false }) } }
                }
                Box {
                    OutlinedButton({ qMenu = true }) { Text(quality.label) }
                    DropdownMenu(qMenu, { qMenu = false }) {
                        NomadRequest.MapQuality.entries.forEach { q -> DropdownMenuItem({ Text("${q.label} · ca. ${q.approxBytes / 1000.0} KB" + eta(vm.access.estimateSeconds(q.approxBytes, map = true))) }, { vm.setQuality(q); qMenu = false }) }
                    }
                }
            }
            Button({ position?.let { vm.requestMap(it.lat, it.lon) } }, enabled = !busy && mode != NomadMode.NONE && position != null, modifier = Modifier.fillMaxWidth()) {
                Text("Karte um meine Position anfordern" + eta(vm.access.estimateSeconds(quality.approxBytes + 280, map = true)))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }

        map?.let { m ->
            Column(Modifier.padding(8.dp)) {
                Text("3 · Ort antippen für eine Detailkarte", style = MaterialTheme.typography.titleSmall)
                var imgSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                Box(Modifier.fillMaxWidth().aspectRatio(1f).onSizeChanged { imgSize = it }.pointerInput(m) {
                    detectTapGestures { off -> if (imgSize.width > 0) { val (la, lo) = m.section.toLatLon(off.x / imgSize.width.toDouble(), off.y / imgSize.height.toDouble()); vm.selectTarget(la, lo) } }
                }) {
                    Image(m.bitmap.asImageBitmap(), "Kartenausschnitt", Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
                    target?.let { (la, lo) ->
                        m.section.toFraction(la, lo)?.let { (fx, fy) ->
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                val c = androidx.compose.ui.geometry.Offset((fx * size.width).toFloat(), (fy * size.height).toFloat())
                                drawCircle(androidx.compose.ui.graphics.Color(0xFF1F6FEB), radius = 14f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                                drawCircle(androidx.compose.ui.graphics.Color(0xFF1F6FEB), radius = 3f, center = c)
                            }
                        }
                    }
                }
                target?.let { (la, lo) ->
                    Button({ vm.detailAt(la, lo) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("Detailkarte hier (Radius ${m.request.radius.finer().label})" + eta(vm.access.estimateSeconds(quality.approxBytes + 280, map = true)))
                    }
                }
                if (m.section.places.isNotEmpty()) {
                    Text("Orte in diesem Ausschnitt", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.section.places.forEach { p -> androidx.compose.material3.AssistChip({ vm.detailAt(p.lat, p.lon) }, label = { Text(p.name) }, enabled = !busy) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton({ vm.pan(0.0, 1.0) }, enabled = !busy) { Text("N") }
                    OutlinedButton({ vm.pan(0.0, -1.0) }, enabled = !busy) { Text("S") }
                    OutlinedButton({ vm.pan(-1.0, 0.0) }, enabled = !busy) { Text("W") }
                    OutlinedButton({ vm.pan(1.0, 0.0) }, enabled = !busy) { Text("O") }
                    OutlinedButton({ vm.radiusBy(-1) }, enabled = !busy) { Text("−R") }
                    OutlinedButton({ vm.radiusBy(1) }, enabled = !busy) { Text("+R") }
                }
                if (canBack) OutlinedButton({ vm.back() }, enabled = !busy) { Text("← Vorherige Karte") }
                Text("Radius ${m.request.radius.label} · 1 Pixel ≈ ${m.section.metersPerPixel} m · Region ${m.section.region}", fontSize = 11.sp)
                Text("Mitte %.5f, %.5f".format(java.util.Locale.ROOT, m.request.lat, m.request.lon) + target?.let { "  ·  Ziel %.5f, %.5f".format(java.util.Locale.ROOT, it.first, it.second) }.orEmpty(), fontSize = 11.sp)
                Text("© OpenStreetMap-Mitwirkende, Protomaps (ODbL)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(Modifier.padding(8.dp)) {
            OutlinedButton({ vm.loadRegions() }, enabled = !busy && mode != NomadMode.NONE) { Text("Verfügbare Karten anzeigen" + eta(vm.access.estimateSeconds(150))) }
            regions.forEach { r ->
                Text("${r.name} · bis Zoom ${r.maxZoom}", Modifier.fillMaxWidth().clickable {
                    manualLat = "%.5f".format(java.util.Locale.ROOT, (r.minLat + r.maxLat) / 2); manualLon = "%.5f".format(java.util.Locale.ROOT, (r.minLon + r.maxLon) / 2)
                    vm.setSource(PositionSource.MANUAL)
                }.padding(vertical = 8.dp), fontSize = 13.sp)
            }
            if (regions.isNotEmpty()) Text("Antippen übernimmt die Mitte der Region als manuelle Position.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (map == null && !busy) Text("Deine Position geht (verschlüsselt) an den Server, der zeichnet sie in die Karte ein und schickt nur dieses kleine Bild zurück. Es wird nichts gespeichert.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
