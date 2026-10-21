package de.loraki.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.loraki.app.MainViewModel
import de.loraki.app.ble.LinkState
import de.loraki.app.data.ChatMessage
import de.loraki.app.data.Status
import de.loraki.core.Lkp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val convs by vm.conversations.collectAsState()
    val current by vm.currentConv.collectAsState()
    val link by vm.linkState.collectAsState()
    val radio by vm.radioInfo.collectAsState()
    val used by vm.usedS.collectAsState()
    val pending by vm.pending.collectAsState()
    val toast by vm.toast.collectAsState()
    val ctx = LocalContext.current
    val drawer = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(toast) { toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toast.value = null } }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::addImage) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::addFile) }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
            Text("Unterhaltungen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            convs.forEach { c ->
                NavigationDrawerItem(label = { Text(c.title) }, selected = c.id == current,
                    onClick = { vm.selectConversation(c.id); scope.launch { drawer.close() } })
            }
            NavigationDrawerItem(label = { Text("+ Neue Unterhaltung") }, selected = false,
                onClick = { vm.newConversation(); scope.launch { drawer.close() } })
        }
    }) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Unterhaltungen") } },
                    title = {
                        Column {
                            Text(convs.firstOrNull { it.id == current }?.title ?: "LoRa-KI", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(
                                    when (link) { LinkState.READY -> Color(0xFF2E7D32); LinkState.CONNECTING -> Color(0xFFF9A825); else -> Color(0xFFC62828) }, CircleShape))
                                Text(when (link) { LinkState.READY -> "  verbunden"; LinkState.CONNECTING -> "  verbinde …"; else -> "  getrennt" },
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = {
                        IconButton(onSettings) { Icon(Icons.Default.Settings, "Einstellungen") }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Kontext der KI zurücksetzen") }, { menu = false; vm.resetContext() })
                                DropdownMenuItem({ Text("Unterhaltung löschen") }, { menu = false; confirmDelete = true })
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    HorizontalDivider()
                    radio?.let { r ->
                        val frac = (used / r.budgetS).coerceIn(0.0, 1.0).toFloat()
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                            Text("Sendezeit-Budget: %.0f / %.0f s pro Stunde (Duty-Cycle %.1f %%)".format(used, r.budgetS, r.duty * 100),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    pending.forEach { p ->
                        val est = vm.estimate(p.bytes.size)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (p.isImage) Icons.Default.Image else Icons.Default.AttachFile, null, Modifier.size(18.dp))
                            Text("  ${p.name} · ${"%.1f".format(p.bytes.size / 1024.0)} KB" + (est?.let { " · ca. ${formatDuration(it)}" } ?: ""),
                                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            IconButton({ vm.removePending(p) }) { Icon(Icons.Default.Close, "Entfernen") }
                        }
                    }
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
                        Box {
                            IconButton({ attachMenu = true }) { Icon(Icons.Default.AttachFile, "Anhang") }
                            DropdownMenu(attachMenu, { attachMenu = false }) {
                                DropdownMenuItem({ Text("Foto / Bild") }, { attachMenu = false
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    leadingIcon = { Icon(Icons.Default.Image, null) })
                                DropdownMenuItem({ Text("Datei (TXT, PDF, DOCX …)") }, { attachMenu = false; pickFile.launch(arrayOf("*/*")) },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, null) })
                            }
                        }
                        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Nachricht an die KI") }, maxLines = 5,
                            shape = RoundedCornerShape(24.dp))
                        IconButton({ vm.send(text); text = "" }, enabled = text.isNotBlank() || pending.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Senden")
                        }
                    }
                }
            },
        ) { pad ->
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Stelle der KI eine Frage. Die Nachricht wird per LoRa-Funk an deinen Server gesendet – das dauert wegen der gesetzlichen Sendepausen einige Sekunden bis Minuten.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(pad), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages, key = { it.id }) { m -> Bubble(m, vm) }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Unterhaltung löschen?") },
        text = { Text("Alle Nachrichten und Anhänge dieser Unterhaltung werden vom Handy entfernt.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteConversation() }) { Text("Löschen") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Abbrechen") } },
    )
}

@Composable
private fun Bubble(m: ChatMessage, vm: MainViewModel) {
    val mine = m.outgoing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            Modifier.widthIn(max = 320.dp),
            colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                when (m.kind) {
                    Lkp.KIND_IMAGE, Lkp.KIND_FILE -> Text((if (m.kind == Lkp.KIND_IMAGE) "🖼 " else "📎 ") + (m.fileName ?: "Datei") + "  (${"%.1f".format(m.size / 1024.0)} KB)")
                    Lkp.KIND_RESET -> Text("↺ Kontext zurückgesetzt", fontSize = 13.sp)
                    else -> Text(if (mine) androidx.compose.ui.text.AnnotatedString(m.text) else markdown(m.text))
                }
                if (mine) StatusLine(m, vm)
            }
        }
    }
}

@Composable
private fun StatusLine(m: ChatMessage, vm: MainViewModel) {
    val label = when (m.status) {
        Status.QUEUED -> "wartet auf Verbindung/Sendefenster"
        Status.SENDING -> if (m.total > 0) "sendet … ${m.acked}/${m.total} Fragmente" else "sendet …"
        Status.DELIVERED -> "zugestellt"
        Status.WAITING_AI -> "zugestellt – KI antwortet …"
        Status.FAILED -> "fehlgeschlagen"
        Status.RECEIVED -> ""
    }
    Text(label, fontSize = 11.sp, color = if (m.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (m.status == Status.SENDING && m.total > 0)
        LinearProgressIndicator(progress = { m.acked.toFloat() / m.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    if (m.status == Status.FAILED) TextButton({ vm.retry(m.id) }) { Text("Erneut senden") }
}
