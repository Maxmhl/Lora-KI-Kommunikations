package de.loraki.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.loraki.app.MainViewModel
import de.loraki.app.ble.LinkState

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val link by vm.linkState.collectAsState()
    val error by vm.linkError.collectAsState()
    val found by vm.found.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val self by vm.selfInfo.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val radio by vm.radioInfo.collectAsState()
    var serverKey by remember { mutableStateOf(vm.settings.serverKeyHex) }
    var margin by remember { mutableFloatStateOf(vm.settings.margin) }
    var dim by remember { mutableFloatStateOf(vm.settings.imageMaxDim.toFloat()) }
    var quality by remember { mutableFloatStateOf(vm.settings.imageQuality.toFloat()) }
    var maxKb by remember { mutableFloatStateOf(vm.settings.maxAttachKb.toFloat()) }
    var dutyOverride by remember { mutableFloatStateOf(vm.settings.dutyOverridePercent) }

    val perms = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it } || vm.hasBlePermission()) vm.startScan()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Einstellungen") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {

            Section("1 · MeshCore-Node (Bluetooth)") {
                Text("Status: " + when (link) { LinkState.READY -> "verbunden mit ${self?.name ?: vm.settings.deviceName}"; LinkState.CONNECTING -> "verbinde …"; else -> "getrennt" })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (vm.hasBlePermission()) vm.startScan() else permLauncher.launch(perms) }, enabled = !scanning) { Text(if (scanning) "Suche …" else "Node suchen") }
                    if (link == LinkState.DISCONNECTED) OutlinedButton({ vm.reconnect() }, enabled = vm.settings.deviceAddress != null) { Text("Verbinden") }
                    else OutlinedButton({ vm.disconnect() }) { Text("Trennen") }
                }
                found.forEach { d ->
                    Card(Modifier.fillMaxWidth().clickable { vm.connect(d) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp)) { Text(d.name); Text("${d.address} · ${d.rssi} dBm", fontSize = 12.sp) }
                    }
                }
                Text("Beim ersten Verbinden fragt Android nach der Kopplung. Standard-PIN der MeshCore-Companion-Firmware: 123456.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("2 · KI-Server (Ziel-Node)") {
                Text("Wähle den MeshCore-Kontakt, an dem dein Server hängt. Der Kontakt muss dem Handy-Node bekannt sein (Advert empfangen).", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.refreshContacts() }, enabled = link == LinkState.READY) { Text("Kontakte laden") }
                    OutlinedButton({ vm.sendAdvert() }, enabled = link == LinkState.READY) { Text("Advert senden") }
                }
                if (contacts.isEmpty()) Text("Keine Kontakte geladen.", fontSize = 13.sp)
                contacts.filter { it.type == 1 || it.type == 3 }.forEach { c ->
                    val hex = c.publicKey.joinToString("") { "%02x".format(it) }
                    Row(Modifier.fillMaxWidth().clickable { vm.selectServer(c.publicKey, c.name); serverKey = hex }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(serverKey == hex, { vm.selectServer(c.publicKey, c.name); serverKey = hex })
                        Column { Text(c.name.ifBlank { "(ohne Namen)" }); Text(hex.take(12), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                serverKey?.let { Text("Schlüssel-Präfix für die Bridge (allowed_peers) – Handy-Node: ${self?.publicKey?.joinToString("") { b -> "%02x".format(b) }?.take(12) ?: "erst nach Verbindung sichtbar"}", fontSize = 12.sp) }
            }

            Section("3 · Funk & Gesetz (Deutschland, 868 MHz)") {
                val r = radio
                if (r == null) Text("Wird nach der Verbindung aus dem Node gelesen.", fontSize = 13.sp)
                else {
                    Text("%.3f MHz · SF%d · BW %.1f kHz · CR 4/%d".format(r.radio.freqMhz, r.radio.sf, r.radio.bwKhz, r.radio.cr))
                    Text("Erlaubter Duty-Cycle in diesem Subband: %.1f %%, max. %d mW ERP".format(r.duty * 100, r.maxErpMw))
                    r.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
                Text("Sicherheitsfaktor: %.0f %% des erlaubten Duty-Cycle werden genutzt (Rest als Reserve für Adverts/ACKs des Nodes)".format(margin * 100), fontSize = 13.sp)
                Slider(margin, { margin = it; vm.settings.margin = it }, valueRange = 0.3f..1f)
                Text(if (dutyOverride == 0f) "Duty-Cycle: automatisch" else "Duty-Cycle zusätzlich begrenzen auf %.1f %%".format(dutyOverride), fontSize = 13.sp)
                Slider(dutyOverride, { dutyOverride = Math.round(it * 10) / 10f; vm.settings.dutyOverridePercent = dutyOverride }, valueRange = 0f..10f)
                Text("Änderungen wirken nach erneutem Verbinden. Du bist für die Einhaltung der Vorschriften (BNetzA Allgemeinzuteilung, ERP, Duty-Cycle) selbst verantwortlich; die Grenzwerte in der App sind eine Hilfe und ersetzen keine Prüfung.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("4 · Anhänge") {
                Text("Bild: max. ${dim.toInt()} px Kantenlänge", fontSize = 13.sp)
                Slider(dim, { dim = Math.round(it / 32) * 32f; vm.settings.imageMaxDim = dim.toInt() }, valueRange = 128f..1024f)
                Text("Bildqualität: ${quality.toInt()} %", fontSize = 13.sp)
                Slider(quality, { quality = it; vm.settings.imageQuality = it.toInt() }, valueRange = 20f..90f)
                Text("Maximale Anhanggröße: ${maxKb.toInt()} KB", fontSize = 13.sp)
                Slider(maxKb, { maxKb = it; vm.settings.maxAttachKb = it.toInt() }, valueRange = 8f..200f)
                Text("Auf der Funkstrecke sind ca. 5–10 Byte/s realistisch – 60 KB dauern deshalb Stunden. Die geschätzte Dauer zeigt die App vor dem Senden.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
