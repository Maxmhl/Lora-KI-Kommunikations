package de.loraki.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.loraki.app.nomad.NomadChatViewModel
import de.loraki.app.nomad.UiMsg

/** Chat mit der NOMAD-KI (Ollama + Wissensdatenbank) über das Gateway – Sitzungen sind dieselben wie in der NOMAD-Weboberfläche. */
@Composable
fun NomadChatTab(vm: NomadChatViewModel, onOpenLibrary: () -> Unit) {
    if (!vm.configured) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Noch kein Server verbunden. Trage in der Bibliothek Adresse und Token des Gateways ein.")
                OutlinedButton(onOpenLibrary) { Text("Server einrichten") }
            }
        }
        return
    }
    val models by vm.models.collectAsState()
    val model by vm.model.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val current by vm.current.collectAsState()
    val messages by vm.messages.collectAsState()
    val rag by vm.rag.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    var text by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) { if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton({ modelMenu = true }, enabled = models.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(model ?: "Modell", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    models.forEach { m ->
                        DropdownMenuItem({ Text(m.name + if (m.vision) "  (Bilder)" else "") }, { vm.selectModel(m.name); modelMenu = false })
                    }
                }
            }
            Box {
                OutlinedButton({ sessionMenu = true }) { Text("Verlauf") }
                DropdownMenu(sessionMenu, { sessionMenu = false }) {
                    DropdownMenuItem({ Text("+ Neue Unterhaltung") }, { vm.newChat(); sessionMenu = false })
                    sessions.take(25).forEach { s ->
                        DropdownMenuItem({ Text((if (s.id == current) "● " else "") + s.title.ifBlank { "Unterhaltung" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            { vm.open(s); sessionMenu = false },
                            trailingIcon = { TextButton({ vm.delete(s); sessionMenu = false }) { Text("Löschen", fontSize = 11.sp) } })
                    }
                }
            }
        }
        rag?.let { on ->
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wissensdatenbank nutzen", fontSize = 13.sp)
                    Text("Serverweite NOMAD-Einstellung – gilt auch in der Weboberfläche", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(on, { vm.setRag(it) })
            }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Text(if (models.isEmpty() && !loading) "Keine Modelle gefunden – ist der AI Assistant in NOMAD installiert und ein Modell geladen?"
                    else "Frag die NOMAD-KI. Sie nutzt – wenn aktiviert – die Wissensdatenbank des Servers und nennt ihre Quellen.",
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages.size) { i -> NomadBubble(messages[i]) }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Nachricht an NOMAD-KI") }, maxLines = 5, shape = RoundedCornerShape(24.dp))
            if (streaming) IconButton({ vm.stop() }) { Icon(Icons.Default.Stop, "Stopp") }
            else IconButton({ vm.send(text); text = "" }, enabled = text.isNotBlank() && model != null) { Icon(Icons.AutoMirrored.Filled.Send, "Senden") }
        }
    }
}

@Composable
private fun NomadBubble(m: UiMsg) {
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(Modifier.widthIn(max = 340.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(
            containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                if (m.thinking && m.content.isEmpty()) Text("denkt …", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(if (mine) AnnotatedString(m.content) else markdown(m.content))
                if (m.sources.isNotEmpty()) {
                    Text("Quellen", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    m.sources.take(6).forEach { Text("• " + it.title + (it.date?.let { d -> " ($d)" } ?: ""), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}
