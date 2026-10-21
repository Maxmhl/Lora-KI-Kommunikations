package de.loraki.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.loraki.app.R

/**
 * Designsprache „Funkgerät im Feld“: Papierfarben, Daten in Monospace, Höhenlinien-Motiv, ruhige Flächen mit 1-px-Linien.
 * Farben und Maße folgen dem Entwurf (Designsprache-Tafel).
 */
@Immutable
data class LoraColors(
    val bg: Color, val surface: Color, val surface2: Color, val ink: Color, val muted: Color,
    val line: Color, val lineStrong: Color, val accent: Color, val accentInk: Color, val accentSoft: Color,
    val amber: Color, val amberSoft: Color, val danger: Color, val dark: Boolean,
)

val LightLoraColors = LoraColors(
    bg = Color(0xFFF4F2EC), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEAE7DE), ink = Color(0xFF14211D), muted = Color(0xFF4B5A54),
    line = Color(0xFFD5D1C4), lineStrong = Color(0xFFA8A395), accent = Color(0xFF0B6B52), accentInk = Color(0xFFFFFFFF), accentSoft = Color(0xFFD6EBE2),
    amber = Color(0xFF7A4F00), amberSoft = Color(0xFFF6E6BF), danger = Color(0xFFA3322A), dark = false,
)

val DarkLoraColors = LoraColors(
    bg = Color(0xFF0D1613), surface = Color(0xFF15221E), surface2 = Color(0xFF1D2D28), ink = Color(0xFFE7EEE9), muted = Color(0xFFA3B3AB),
    line = Color(0xFF2A3C35), lineStrong = Color(0xFF41584F), accent = Color(0xFF5ED1A4), accentInk = Color(0xFF06231A), accentSoft = Color(0xFF17372D),
    amber = Color(0xFFF0B84A), amberSoft = Color(0xFF3A2E10), danger = Color(0xFFFF8A80), dark = true,
)

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.Bold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_medium, FontWeight.SemiBold),
)

/** Textstil für Daten (Koordinaten, Zeiten, Frequenzen). */
val DataStyle = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)

private val LocalLoraColors = staticCompositionLocalOf { LightLoraColors }

object LoraTheme {
    val colors: LoraColors @Composable @ReadOnlyComposable get() = LocalLoraColors.current
}

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.plex(w: FontWeight = FontWeight.Normal) = copy(fontFamily = PlexSans, fontWeight = w)
    return base.copy(
        displaySmall = base.displaySmall.plex(FontWeight.SemiBold),
        headlineSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = PlexSans, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = PlexSans, fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
    )
}

private fun scheme(c: LoraColors): ColorScheme =
    (if (c.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.accentInk, primaryContainer = c.accentSoft, onPrimaryContainer = c.ink,
        secondary = c.amber, onSecondary = c.bg, secondaryContainer = c.amberSoft, onSecondaryContainer = c.amber,
        tertiary = c.amber, tertiaryContainer = c.amberSoft, onTertiaryContainer = c.amber,
        background = c.bg, onBackground = c.ink, surface = c.bg, onSurface = c.ink,
        surfaceVariant = c.surface2, onSurfaceVariant = c.muted, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surface2, surfaceContainerLow = c.surface, surfaceContainerLowest = c.surface,
        outline = c.lineStrong, outlineVariant = c.line, error = c.danger, onError = c.bg, errorContainer = c.amberSoft, onErrorContainer = c.danger,
        inverseSurface = c.ink, inverseOnSurface = c.bg, inversePrimary = c.accentSoft, surfaceTint = c.accent,
    )

@Composable
fun LoraKiTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkLoraColors else LightLoraColors
    CompositionLocalProvider(LocalLoraColors provides c) {
        MaterialTheme(colorScheme = scheme(c), typography = typography(), content = content)
    }
}
