package de.loraki.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/** Minimales Markdown: **fett**, `code`, # Überschriften, - Listen. */
fun markdown(src: String): AnnotatedString = buildAnnotatedString {
    val inline = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
    src.lines().forEachIndexed { i, raw ->
        if (i > 0) append("\n")
        var line = raw
        val heading = Regex("^#{1,6}\\s+").find(line)
        val bold = heading != null
        if (heading != null) line = line.removePrefix(heading.value)
        if (line.startsWith("- ") || line.startsWith("* ")) line = "• " + line.drop(2)
        var pos = 0
        for (m in inline.findAll(line)) {
            appendStyled(line.substring(pos, m.range.first), bold)
            m.groups[1]?.let { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(it.value) } }
            m.groups[2]?.let { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) { append(it.value) } }
            pos = m.range.last + 1
        }
        appendStyled(line.substring(pos), bold)
    }
}

private fun AnnotatedString.Builder.appendStyled(t: String, bold: Boolean) {
    if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) { append(t) } else append(t)
}

fun formatDuration(seconds: Double): String {
    val s = seconds.toLong()
    return when {
        s < 90 -> "$s s"
        s < 5400 -> "${(s + 30) / 60} min"
        else -> "%.1f h".format(seconds / 3600)
    }
}
