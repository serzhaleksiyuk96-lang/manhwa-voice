package com.manhwavoice.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** Палитра: ночной фиолетовый с неоновым розовым — под чтение манхвы. */
object Palette {
    val Bg = Color(0xFF0E0A18)
    val Surface = Color(0xFF171127)
    val SurfaceHigh = Color(0xFF221A37)
    val Outline = Color(0xFF3A2F57)
    val Violet = Color(0xFF8B5CF6)
    val VioletDeep = Color(0xFF6D28D9)
    val VioletLight = Color(0xFFC4B5FD)
    val Pink = Color(0xFFEC4899)
    val PinkDeep = Color(0xFFDB2777)
    val Amber = Color(0xFFFBBF24)
    val Green = Color(0xFF34D399)
    val Red = Color(0xFFF87171)
    val TextMain = Color(0xFFF5F1FF)
    val TextDim = Color(0xFFA89EC6)

    val brand: Brush get() = Brush.linearGradient(listOf(Violet, Pink))
    val hero: Brush get() = Brush.linearGradient(listOf(VioletDeep, PinkDeep))
    val heroOff: Brush get() = Brush.linearGradient(listOf(Color(0xFF3A1730), Color(0xFF261A40)))
}

private val scheme = darkColorScheme(
    primary = Palette.Violet,
    onPrimary = Color.White,
    secondary = Palette.Pink,
    onSecondary = Color.White,
    tertiary = Palette.Amber,
    background = Palette.Bg,
    onBackground = Palette.TextMain,
    surface = Palette.Surface,
    onSurface = Palette.TextMain,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainerHigh = Palette.SurfaceHigh,
    outline = Palette.Outline,
    error = Palette.Red
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val typography = base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
