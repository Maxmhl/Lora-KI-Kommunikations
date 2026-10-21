package de.loraki.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.MenuBook
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
import de.loraki.app.ui.components.AppCard
import de.loraki.app.ui.components.DutyBar
import de.loraki.app.ui.components.FragmentBar
import de.loraki.app.ui.components.Mono
import de.loraki.app.ui.components.Way
import de.loraki.app.ui.components.WayBadge
import de.loraki.app.ui.theme.LoraTheme
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
    var modelDialog by remember { mutableStateOf(false) }
    val models by vm.models.collectAsState()
    val currentModel = convs.firstOrNull { it.id == current }?.model
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
            containerColor = LoraTheme.colors.bg,
            topBar = {
                val cv = LoraTheme.colors
                val self by vm.selfInfo.collectAsState()
                val conv = convs.firstOrNull { it.id == current }
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SquareButton({ scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Unterhaltungen", Modifier.size(20.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(conv?.title ?: "LoRa-KI", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cv.ink)
                            Mono((currentModel ?: "Server-Standard") + (if (conv?.kb == true) " · Wissensbasis an" else "") + " ▾",
                                modifier = Modifier.clickable { modelDialog = true })
                        }
                        SquareButton(onSettings) { Icon(Icons.Default.Settings, "Einstellungen", Modifier.size(20.dp)) }
                        Box {
                            SquareButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Mehr", Modifier.size(20.dp)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("KI-Modell wählen …") }, { menu = false; modelDialog = true })
                                DropdownMenuItem({ Text("NOMAD-Wissensdatenbank: " + if (conv?.kb == true) "an ✓" else "aus") }, { menu = false; vm.toggleKnowledgeBase() })
                                DropdownMenuItem({ Text("Kontext der KI zurücksetzen") }, { menu = false; vm.resetContext() })
                                DropdownMenuItem({ Text("Unterhaltung löschen") }, { menu = false; confirmDelete = true })
                            }
                        }
                    }
                    // Statusband: Node, Weg, Sendezeit
                    AppCard(Modifier.padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(when (link) { LinkState.READY -> cv.accent; LinkState.CONNECTING -> cv.amber; else -> cv.danger }))
                                Text(when (link) { LinkState.READY -> self?.name?.ifBlank { null } ?: "Node verbunden"; LinkState.CONNECTING -> "Node verbindet …"; else -> "Node getrennt" },
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cv.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                WayBadge(if (link == LinkState.READY) Way.LORA else Way.NONE)
                            }
                            radio?.let { r -> DutyBar(used, r.budgetS, r.duty * 100) }
                        }
                    }
                }
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    pending.forEach { p ->
                        val est = vm.estimate(p.bytes.size)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (p.isImage) Icons.Default.Image else Icons.Default.AttachFile, null, Modifier.size(18.dp))
                            Text("  ${p.name} · ${"%.1f".format(p.bytes.size / 1024.0)} KB" + (est?.let { " · ca. ${formatDuration(it)}" } ?: ""),
                                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            IconButton({ vm.removePending(p) }) { Icon(Icons.Default.Close, "Entfernen") }
                        }
                    }
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            SquareButton({ attachMenu = true }, size = 48.dp) { Icon(Icons.Default.AttachFile, "Anhang", Modifier.size(22.dp)) }
                            DropdownMenu(attachMenu, { attachMenu = false }) {
                                DropdownMenuItem({ Text("Foto / Bild") }, { attachMenu = false
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    leadingIcon = { Icon(Icons.Default.Image, null) })
                                DropdownMenuItem({ Text("Datei (TXT, PDF, DOCX …)") }, { attachMenu = false; pickFile.launch(arrayOf("*/*")) },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, null) })
                            }
                        }
                        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Nachricht an die KI") }, maxLines = 5, shape = RoundedCornerShape(24.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = LoraTheme.colors.accent, unfocusedBorderColor = LoraTheme.colors.lineStrong,
                                focusedContainerColor = LoraTheme.colors.surface, unfocusedContainerColor = LoraTheme.colors.surface))
                        val canSend = text.isNotBlank() || pending.isNotEmpty()
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(if (canSend) LoraTheme.colors.accent else LoraTheme.colors.surface2)
                            .clickable(enabled = canSend) { vm.send(text); text = "" }, contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Senden", Modifier.size(22.dp), tint = if (canSend) LoraTheme.colors.accentInk else LoraTheme.colors.muted)
                        }
                    }
                }
            },
        ) { pad ->
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        de.loraki.app.ui.components.Contours(Modifier.fillMaxWidth().height(110.dp))
                        Text("Stelle der KI eine Frage", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = LoraTheme.colors.ink)
                        Text("Die Nachricht geht per LoRa-Funk an deinen Server. Wegen der gesetzlichen Sendepausen dauert das Sekunden bis Minuten – vor jedem Anhang siehst du die geschätzte Zeit.",
                            color = LoraTheme.colors.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(pad), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(messages, key = { it.id }) { m -> Bubble(m, vm) }
            }
        }
    }

    if (modelDialog) AlertDialog(
        onDismissRequest = { modelDialog = false },
        title = { Text("KI-Modell") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                @Composable fun option(label: String, sub: String?, selected: Boolean, onPick: () -> Unit) {
                    Row(Modifier.fillMaxWidth().clickable { onPick(); modelDialog = false }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected, { onPick(); modelDialog = false })
                        Column { Text(label); sub?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                }
                option("Server-Standard", "das in der Bridge konfigurierte Modell", currentModel == null) { vm.selectModel(null) }
                models.forEach { m -> option(m.name, if (m.vision) "versteht Bilder" else null, currentModel == m.name) { vm.selectModel(m.name) } }
                if (models.isEmpty()) Text("Noch keine Modellliste. Unten „Liste vom Server laden“ tippen (kostet einige Sekunden Sendezeit); sie erscheint danach hier.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton({ vm.requestModels() }, enabled = link == LinkState.READY) { Text("Liste vom Server laden") } },
        dismissButton = { TextButton({ modelDialog = false }) { Text("Schließen") } },
    )

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Unterhaltung löschen?") },
        text = { Text("Alle Nachrichten und Anhänge dieser Unterhaltung werden vom Handy entfernt.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteConversation() }) { Text("Löschen") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Abbrechen") } },
    )
}

@Composable
private fun SquareButton(onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 44.dp, content: @Composable () -> Unit) {
    val c = LoraTheme.colors
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.ink) { content() } }
}

@Composable
private fun Bubble(m: ChatMessage, vm: MainViewModel) {
    val c = LoraTheme.colors
    val mine = m.outgoing
    val system = m.kind == Lkp.KIND_RESET || m.kind == Lkp.KIND_SET_MODEL || m.kind == Lkp.KIND_SET_OPTS || m.kind == Lkp.KIND_MODEL_LIST_REQ
    if (system) {   // Steuernachrichten: leise Zeile statt Blase
        val label = when (m.kind) {
            Lkp.KIND_RESET -> "↺ Kontext zurückgesetzt"
            Lkp.KIND_SET_MODEL -> "Modell: " + m.text.ifEmpty { "Server-Standard" }
            Lkp.KIND_SET_OPTS -> if (m.text.contains("kb=")) "NOMAD-Wissensdatenbank: " + if (m.text.contains("kb=1")) "an" else "aus" else "Einstellung gesendet"
            else -> m.text
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Mono(label) }
        return
    }
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val isFile = m.kind == Lkp.KIND_IMAGE || m.kind == Lkp.KIND_FILE
        val body = if (!mine) m.text.substringBefore("\n\nQuellen: ") else m.text
        val sources = if (!mine && m.text.contains("\n\nQuellen: ")) m.text.substringAfter("\n\nQuellen: ") else null
        Box(Modifier.widthIn(max = 320.dp).clip(shape).then(if (mine) Modifier.background(c.accent) else Modifier.background(c.surface).border(1.dp, c.line, shape))
            .padding(if (isFile) 10.dp else 14.dp, if (isFile) 10.dp else 12.dp)) {
            if (isFile) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(c.accentInk.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(if (m.kind == Lkp.KIND_IMAGE) Icons.Default.Image else Icons.Default.AttachFile, null, tint = c.accentInk)
                }
                Column { Text(m.fileName ?: "Datei", color = c.accentInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Mono("%.1f KB".format(m.size / 1024.0), color = c.accentInk.copy(alpha = 0.9f)) }
            } else Text(if (mine) androidx.compose.ui.text.AnnotatedString(body) else markdown(body), color = if (mine) c.accentInk else c.ink, fontSize = 15.sp, lineHeight = 22.sp)
        }
        sources?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.MenuBook, null, Modifier.size(14.dp), tint = LoraTheme.colors.muted); Mono("Quellen: $it", size = 11) } }
        if (mine) StatusLine(m, vm)
    }
}

@Composable
private fun StatusLine(m: ChatMessage, vm: MainViewModel) {
    val c = LoraTheme.colors
    val label = when (m.status) {
        Status.QUEUED -> "wartet auf Verbindung/Sendefenster"
        Status.SENDING -> if (m.total > 0) "sendet · ${m.acked} / ${m.total} Pakete" else "sendet …"
        Status.DELIVERED -> "zugestellt" + if (m.total > 0) " · ${m.total} Pakete" else ""
        Status.WAITING_AI -> "zugestellt"
        Status.FAILED -> "fehlgeschlagen"
        Status.RECEIVED -> ""
    }
    Column(Modifier.widthIn(min = 120.dp, max = 300.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (m.status == Status.SENDING && m.total > 0) FragmentBar(m.acked, m.total, Modifier.fillMaxWidth())
        Mono(label, color = if (m.status == Status.FAILED) c.danger else c.muted, size = 11)
        if (m.status == Status.WAITING_AI) Row(Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(c.surface).border(1.dp, c.line, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(1f, 0.6f, 0.3f).forEach { a -> Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.accent.copy(alpha = a))) } }
            Text("Server bearbeitet die Anfrage …", fontSize = 13.sp, color = c.muted)
        }
        if (m.status == Status.FAILED) TextButton({ vm.retry(m.id) }) { Text("Erneut senden") }
    }
}
