package dev.yusuf.falconlauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import dev.yusuf.falconlauncher.R

/** True black: on a mini-LED panel, local dimming can switch those zones fully off. */
val Background = Color.Black
val Glass = Color.White.copy(alpha = 0.08f)
val GlassBorder = Color.White.copy(alpha = 0.10f)
val DialogBackground = Color(0xFF121318)

/** Inter (the closest open match to Apple's SF Pro) from one variable font file, at the weights the UI uses. */
@OptIn(ExperimentalTextApi::class)
val UiFont = FontFamily(
    listOf(300, 400, 500, 600, 700).map { weight ->
        Font(
            R.font.inter,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    }
)

/** Anybody at its widest (wdth 150) and bold: a typeface drawn wide, used for the clock. */
@OptIn(ExperimentalTextApi::class)
val AnybodyWide = FontFamily(
    Font(
        R.font.anybody,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.width(150f)),
    )
)

private fun Typography.withFont(font: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = font),
    displayMedium = displayMedium.copy(fontFamily = font),
    displaySmall = displaySmall.copy(fontFamily = font),
    headlineLarge = headlineLarge.copy(fontFamily = font),
    headlineMedium = headlineMedium.copy(fontFamily = font),
    headlineSmall = headlineSmall.copy(fontFamily = font),
    titleLarge = titleLarge.copy(fontFamily = font),
    titleMedium = titleMedium.copy(fontFamily = font),
    titleSmall = titleSmall.copy(fontFamily = font),
    bodyLarge = bodyLarge.copy(fontFamily = font),
    bodyMedium = bodyMedium.copy(fontFamily = font),
    bodySmall = bodySmall.copy(fontFamily = font),
    labelLarge = labelLarge.copy(fontFamily = font),
    labelMedium = labelMedium.copy(fontFamily = font),
    labelSmall = labelSmall.copy(fontFamily = font),
)

/** The whole UI is drawn at 85% of the standard TV size: crisper and calmer, with more room. */
const val UI_SCALE = 0.85f

@Composable
fun FalconTheme(content: @Composable () -> Unit) {
    val typography = Typography().withFont(UiFont)
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * UI_SCALE, base.fontScale)) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color.White,
                onPrimary = Color.Black,
                background = Background,
                surface = DialogBackground,
                onSurface = Color.White,
            ),
            typography = typography,
        ) {
            // Text outside a Surface would otherwise default to black (and to the default font).
            CompositionLocalProvider(
                LocalContentColor provides Color.White,
                LocalTextStyle provides typography.bodyLarge,
                content = content,
            )
        }
    }
}
