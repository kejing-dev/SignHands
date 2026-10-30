package io.embodit.signhands.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Typography
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.signhands.ui.Copy

/** Brand tokens of the design package (`palette`): 3D content and the few accents the panels need. */
object SignColors {
    val Interaction = Color(0xFF4D8DFF)
    val Jade = Color(0xFF3FBF8F)
    val Alert = Color(0xFFFFB648)
    val Coral = Color(0xFFFF6B57)
    val TeacherWhite = Color(0xFFF4EFE6)
    val TrailAmber = Color(0xFFFFB648)
    val PalmCoral = Color(0xFFE0715A)
    val Skin = Color(0xFFC98E73)
    val Ring = Color(0xFFF4EFE6)

    // Buttons on the glass panel: one tone per level, measured contrast (ui-audit.md): primary 15.0:1, secondary 12.4:1, selected 6.6:1
    val ButtonPrimary = Color(0xFFF4EFE6)
    val OnButtonPrimary = Color(0xFF1C1A24)
    val ButtonSecondary = Color(0xFF2A2A36)
    val OnButtonSecondary = Color(0xFFF2F0EC)
    val Selected = Color(0xFF3FBF8F)
    val OnSelected = Color(0xFF0E2A20)
    val LabelInk = Color(0xFFFFFFFF)
    val LabelShadow = Color(0xCC14121A)
}

val LocalCopy = staticCompositionLocalOf { Copy(io.embodit.signhands.domain.Lang.SYSTEM) }

/** PicoTheme over the system (Vibrant) scheme with the design's accent roles, Noto Sans / Noto Sans SC, and the copy. */
@Composable
fun SignTheme(copy: Copy, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors = remember(system) { system.copy(interaction = SignColors.Interaction, passable = SignColors.Jade, alert = SignColors.Alert, error = SignColors.Coral) }
    PicoTheme(colorScheme = colors) {
        val typography = rememberNotoTypography(PicoTheme.typography, !copy.en)
        PicoTheme(colorScheme = colors, typography = typography) {
            CompositionLocalProvider(LocalCopy provides copy, content = content)
        }
    }
}

private const val LATIN_FONT = "NotoSans-VF.ttf"
private const val CHINESE_FONT = "NotoSansSC-VF.ttf"
private val WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

private fun Context.notoFontFamily(chinese: Boolean): FontFamily? {
    val file = if (chinese) CHINESE_FONT else LATIN_FONT
    val bundled = runCatching { assets.list("fonts")?.toSet() }.getOrNull().orEmpty()
    if (file !in bundled) return null
    return FontFamily(WEIGHTS.map { w -> Font(path = "fonts/$file", assetManager = assets, weight = w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight))) })
}

@Composable
private fun rememberNotoTypography(base: Typography, chinese: Boolean): Typography {
    val context = LocalContext.current
    return remember(context, base, chinese) {
        val family = context.notoFontFamily(chinese) ?: return@remember base
        fun TextStyle.f(): TextStyle = copy(fontFamily = family)
        base.copy(
            displayLarge = base.displayLarge.f(),
            displayMedium = base.displayMedium.f(),
            displaySmall = base.displaySmall.f(),
            headlineLarge = base.headlineLarge.f(),
            headlineMedium = base.headlineMedium.f(),
            headlineSmall = base.headlineSmall.f(),
            titleLarge = base.titleLarge.f(),
            titleMedium = base.titleMedium.f(),
            titleSmall = base.titleSmall.f(),
            bodyLarge = base.bodyLarge.f(),
            bodyLargeMultiline = base.bodyLargeMultiline.f(),
            bodyMedium = base.bodyMedium.f(),
            bodyMediumMultiline = base.bodyMediumMultiline.f(),
            bodySmall = base.bodySmall.f(),
            bodyTiny = base.bodyTiny.f(),
            labelLarge = base.labelLarge.f(),
            labelMedium = base.labelMedium.f(),
            labelSmall = base.labelSmall.f(),
        )
    }
}
