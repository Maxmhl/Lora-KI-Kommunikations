package de.loraki.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.loraki.app.ui.theme.DataStyle
import de.loraki.app.ui.theme.LoraTheme

/** Verbindungsweg, wie er oben in jedem Bildschirm steht. */
enum class Way { WLAN, LORA, NONE }

@Composable
fun WayBadge(way: Way, modifier: Modifier = Modifier) {
    val c = LoraTheme.colors
    val (bg, fg, label) = when (way) {
        Way.WLAN -> Triple(c.accentSoft, c.accent, "WLAN · schnell")
        Way.LORA -> Triple(c.amberSoft, c.amber, "LoRa · langsam")
        Way.NONE -> Triple(c.surface2, c.muted, "keine Verbindung")
    }
    Row(modifier.clip(CircleShape).background(bg).padding(horizontal = 11.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Karte mit 1-px-Linie, 18 dp Rundung, keine Schatten. */
@Composable
fun AppCard(modifier: Modifier = Modifier, accent: Boolean = false, content: @Composable () -> Unit) {
    val c = LoraTheme.colors
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (accent) c.accentSoft else c.surface)
        .border(BorderStroke(1.dp, if (accent) c.accent else c.line), RoundedCornerShape(18.dp))) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, color = LoraTheme.colors.muted, style = DataStyle.copy(letterSpacing = 1.2.sp))
}

/** Daten in Monospace (Koordinaten, Zeiten, Größen). */
@Composable
fun Mono(text: String, modifier: Modifier = Modifier, color: Color = LoraTheme.colors.muted, size: Int = 12, weight: FontWeight = FontWeight.Normal) {
    Text(text, modifier, color = color, style = DataStyle.copy(fontSize = size.sp, lineHeight = (size + 4).sp, fontWeight = weight))
}

/** Sendezeit-Leiste: verbrauchte Airtime der letzten Stunde gegenüber dem erlaubten Budget. */
@Composable
fun DutyBar(usedS: Double, budgetS: Double, dutyPercent: Double, modifier: Modifier = Modifier) {
    val c = LoraTheme.colors
    val frac = if (budgetS > 0) (usedS / budgetS).coerceIn(0.0, 1.0).toFloat() else 0f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.surface2)) {
            Box(Modifier.fillMaxWidth(frac).height(8.dp).background(if (frac > 0.85f) c.danger else c.accent))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Mono("Sendezeit %.0f / %.0f s je Stunde".format(usedS, budgetS))
            Mono("%.0f %%".format(dutyPercent))
        }
    }
}

/** Fragment-Leiste: ein Kästchen = ein Funkpaket (bestätigt · wird gesendet · offen). Lange Übertragungen werden gestaucht. */
@Composable
fun FragmentBar(acked: Int, total: Int, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val c = LoraTheme.colors
    val cells = total.coerceIn(1, 40)
    val doneCells = (acked.toLong() * cells / total.coerceAtLeast(1)).toInt()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until cells) {
            Box(Modifier.weight(1f).height(height).clip(RoundedCornerShape(3.dp))
                .background(when { i < doneCells -> c.accent; i == doneCells && acked < total -> c.amber; else -> c.surface2 }))
        }
    }
}

/** Pillen-Reiter statt Tab-Leiste (Nomad). */
@Composable
fun <T> PillTabs(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = LoraTheme.colors
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
        items(items) { (id, label) ->
            val on = id == selected
            Box(Modifier.clip(CircleShape).then(if (on) Modifier.background(c.ink) else Modifier.background(c.surface).border(1.dp, c.line, CircleShape))
                .clickable { onSelect(id) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(label, color = if (on) c.bg else c.muted, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Kleiner Wähler (Radius, Qualität): gewählt = Funkgrün, sonst Linie. Daten in Monospace. */
@Composable
fun PillChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, mono: Boolean = true, enabled: Boolean = true) {
    val c = LoraTheme.colors
    Box(modifier.clip(CircleShape).then(if (selected) Modifier.background(c.accent) else Modifier.border(1.dp, c.lineStrong, CircleShape))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp)) {
        if (mono) Mono(label, color = if (selected) c.accentInk else c.muted, weight = if (selected) FontWeight.Medium else FontWeight.Normal)
        else Text(label, color = if (selected) c.accentInk else c.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Hellgrüner Ortschip. */
@Composable
fun SoftChip(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = LoraTheme.colors
    Box(Modifier.clip(CircleShape).background(c.accentSoft).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Aktion mit Aufwand: Beschriftung links, geschätzte Funkzeit rechts („≈ 9 min“). */
@Composable
fun EffortButton(label: String, effort: String?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true) {
    val c = LoraTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val fg = if (primary) c.accentInk else c.ink
    Row(modifier.fillMaxWidth().clip(shape)
        .then(if (primary) Modifier.background(if (enabled) c.accent else c.surface2) else Modifier.border(1.dp, c.lineStrong, shape))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = if (enabled) fg else c.muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        effort?.let { Mono(it, color = if (enabled) fg.copy(alpha = 0.9f) else c.muted, size = 13) }
    }
}

/** Schalterzeile mit optionalem Hinweis und Warnung (rot). */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, warning: String? = null, enabled: Boolean = true) {
    val c = LoraTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.ink)
            subtitle?.let { Text(it, fontSize = 12.sp, color = c.muted) }
            warning?.let { Text(it, fontSize = 12.sp, color = c.danger) }
        }
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = c.accentInk, checkedTrackColor = c.accent, uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surface2,
            uncheckedBorderColor = c.lineStrong, disabledUncheckedTrackColor = c.surface2, disabledCheckedTrackColor = c.accentSoft))
    }
}

/** Kleines Etikett („experimentell“). */
@Composable
fun Tag(text: String) {
    val c = LoraTheme.colors
    Box(Modifier.clip(CircleShape).background(c.amberSoft).padding(horizontal = 9.dp, vertical = 3.dp)) { Mono(text, color = c.amber, size = 11, weight = FontWeight.Medium) }
}

/** Höhenlinien-Motiv für leere Zustände. */
@Composable
fun Contours(modifier: Modifier = Modifier) {
    val c = LoraTheme.colors
    Canvas(modifier) {
        val w = size.width; val h = size.height
        for (i in 0 until 5) {
            val y0 = h * (0.25f + i * 0.14f)
            val p = Path().apply {
                moveTo(0f, y0)
                cubicTo(w * 0.25f, y0 - h * 0.20f, w * 0.45f, y0 + h * 0.10f, w * 0.65f, y0 - h * 0.06f)
                cubicTo(w * 0.80f, y0 - h * 0.18f, w * 0.92f, y0 - h * 0.02f, w, y0 - h * 0.10f)
            }
            drawPath(p, c.accent.copy(alpha = 0.18f), style = Stroke(width = 1.5f))
        }
        drawCircle(c.accent.copy(alpha = 0.55f), radius = 4f, center = Offset(w * 0.8f, h * 0.35f))
    }
}
