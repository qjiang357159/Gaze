package com.gaze.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gaze.reader.core.ThemeMode
import com.gaze.reader.core.ThemeVariant

/**
 * 一套主题的全部取色。
 *
 * [ThemeVariant.CUSTOM] 时由用户选的主色派生，其余预设各有固定色板。
 */
private data class GazePalette(
    val background: Color,
    val raised: Color,
    val sunken: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val accent: Color,
    val accentContainer: Color,
    val onAccent: Color
)

/** 自定义主题的默认种子色（朱红）。 */
val DefaultThemeSeed: Color = Vermilion

private fun lightPalette(variant: ThemeVariant, seed: Color): GazePalette = when (variant) {
    ThemeVariant.PAPER -> GazePalette(
        background = Paper, raised = PaperRaised, sunken = PaperSunken,
        ink = Ink, inkSoft = InkSoft, inkFaint = InkFaint,
        hairline = Hairline, hairlineStrong = HairlineStrong,
        accent = Vermilion, accentContainer = VermilionSoft, onAccent = Paper
    )
    ThemeVariant.CREAM -> GazePalette(
        background = CreamBg, raised = CreamRaised, sunken = CreamSunken,
        ink = CreamInk, inkSoft = CreamInkSoft, inkFaint = CreamInkFaint,
        hairline = CreamHairline, hairlineStrong = CreamHairlineStrong,
        accent = Ochre, accentContainer = Color(0xFFF2E4CC), onAccent = CreamBg
    )
    ThemeVariant.MIST -> GazePalette(
        background = MistBg, raised = MistRaised, sunken = MistSunken,
        ink = MistInk, inkSoft = MistInkSoft, inkFaint = MistInkFaint,
        hairline = MistHairline, hairlineStrong = MistHairlineStrong,
        accent = Indigo, accentContainer = Color(0xFFDCE5F2), onAccent = MistBg
    )
    ThemeVariant.SAGE -> GazePalette(
        background = SageBg, raised = SageRaised, sunken = SageSunken,
        ink = SageInk, inkSoft = SageInkSoft, inkFaint = SageInkFaint,
        hairline = SageHairline, hairlineStrong = SageHairlineStrong,
        accent = Moss, accentContainer = Color(0xFFDCE9E0), onAccent = SageBg
    )
    ThemeVariant.ROSE -> GazePalette(
        background = RoseBg, raised = RoseRaised, sunken = RoseSunken,
        ink = RoseInk, inkSoft = RoseInkSoft, inkFaint = RoseInkFaint,
        hairline = RoseHairline, hairlineStrong = RoseHairlineStrong,
        accent = Rose, accentContainer = Color(0xFFF2DEE4), onAccent = RoseBg
    )
    ThemeVariant.GRAPHITE -> GazePalette(
        background = GraphiteBg, raised = GraphiteRaised, sunken = GraphiteSunken,
        ink = GraphiteInk, inkSoft = GraphiteInkSoft, inkFaint = GraphiteInkFaint,
        hairline = GraphiteHairline, hairlineStrong = GraphiteHairlineStrong,
        accent = Steel, accentContainer = Color(0xFFDCE2EA), onAccent = GraphiteBg
    )
    ThemeVariant.PLUM -> GazePalette(
        background = PlumBg, raised = PlumRaised, sunken = PlumSunken,
        ink = PlumInk, inkSoft = PlumInkSoft, inkFaint = PlumInkFaint,
        hairline = PlumHairline, hairlineStrong = PlumHairlineStrong,
        accent = Plum, accentContainer = Color(0xFFEFDCE9), onAccent = PlumBg
    )
    ThemeVariant.CUSTOM -> deriveLightPalette(seed)
}

private fun darkPalette(variant: ThemeVariant, seed: Color): GazePalette {
    if (variant == ThemeVariant.CUSTOM) return deriveDarkPalette(seed)

    val (deep, raised, sunken) = when (variant) {
        ThemeVariant.PAPER -> Triple(InkDeep, InkRaised, InkSunken)
        ThemeVariant.CREAM -> Triple(InkDeepWarm, InkRaisedWarm, InkSunken)
        ThemeVariant.MIST -> Triple(InkDeepCool, InkRaisedCool, InkSunken)
        ThemeVariant.SAGE -> Triple(InkDeepSage, InkRaisedSage, InkSunken)
        ThemeVariant.ROSE -> Triple(InkDeepRose, InkRaisedRose, InkSunken)
        ThemeVariant.GRAPHITE -> Triple(InkDeepGraphite, InkRaisedGraphite, InkSunken)
        ThemeVariant.PLUM -> Triple(InkDeepPlum, InkRaisedPlum, InkSunken)
        ThemeVariant.CUSTOM -> Triple(InkDeep, InkRaised, InkSunken)
    }
    val accent = when (variant) {
        ThemeVariant.PAPER -> VermilionOnInk
        ThemeVariant.CREAM -> OchreOnInk
        ThemeVariant.MIST -> IndigoOnInk
        ThemeVariant.SAGE -> MossOnInk
        ThemeVariant.ROSE -> RoseOnInk
        ThemeVariant.GRAPHITE -> SteelOnInk
        ThemeVariant.PLUM -> PlumOnInk
        ThemeVariant.CUSTOM -> VermilionOnInk
    }
    return GazePalette(
        background = deep, raised = raised, sunken = sunken,
        ink = PaperOnInk, inkSoft = PaperOnInkSoft, inkFaint = PaperOnInkFaint,
        hairline = HairlineDark, hairlineStrong = HairlineDarkStrong,
        accent = accent, accentContainer = accent.copy(alpha = 0.16f), onAccent = deep
    )
}

// ---------------------------------------------------------------- 自定义主题派生
//
// 用户只挑一个「主色」，其余全部由它推导：
// 纸色是主色的极淡版本，墨色是极深版本，强调色就是主色本身（按明暗微调）。
// 这样无论用户选什么颜色，得到的一整套都是协调的，不会出现「亮黄纸 + 亮黄字」。

private fun deriveLightPalette(seed: Color): GazePalette {
    val (h, s, l) = colorToHsl(seed)
    val accent = if (l > 0.5f) hsl(h, s, 0.42f) else seed
    return GazePalette(
        background = hsl(h, s * 0.30f, 0.955f),
        raised = hsl(h, s * 0.26f, 0.985f),
        sunken = hsl(h, s * 0.34f, 0.905f),
        ink = hsl(h, (s * 0.5f).coerceAtMost(0.30f), 0.085f),
        inkSoft = hsl(h, (s * 0.25f).coerceAtMost(0.16f), 0.31f),
        inkFaint = hsl(h, (s * 0.20f).coerceAtMost(0.13f), 0.55f),
        hairline = hsl(h, s * 0.22f, 0.86f),
        hairlineStrong = hsl(h, s * 0.26f, 0.73f),
        accent = accent,
        accentContainer = hsl(h, s * 0.45f, 0.92f),
        onAccent = hsl(h, s * 0.30f, 0.98f)
    )
}

private fun deriveDarkPalette(seed: Color): GazePalette {
    val (h, s, l) = colorToHsl(seed)
    val accent = if (l < 0.55f) hsl(h, s, 0.66f) else seed
    return GazePalette(
        background = hsl(h, s * 0.22f, 0.055f),
        raised = hsl(h, s * 0.22f, 0.095f),
        sunken = hsl(h, s * 0.22f, 0.030f),
        ink = hsl(h, (s * 0.15f).coerceAtMost(0.10f), 0.92f),
        inkSoft = hsl(h, (s * 0.18f).coerceAtMost(0.12f), 0.66f),
        inkFaint = hsl(h, (s * 0.18f).coerceAtMost(0.12f), 0.44f),
        hairline = hsl(h, s * 0.20f, 0.17f),
        hairlineStrong = hsl(h, s * 0.20f, 0.25f),
        accent = accent,
        accentContainer = hsl(h, s * 0.35f, 0.16f),
        onAccent = hsl(h, s * 0.22f, 0.06f)
    )
}

/** HSL -> Color。[h] 0..360，[s] / [l] 0..1。 */
private fun hsl(h: Float, s: Float, l: Float): Color {
    val saturation = s.coerceIn(0f, 1f)
    val lightness = l.coerceIn(0f, 1f)
    val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val hp = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r, g, b) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = lightness - c / 2f
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f)
    )
}

/** Color -> (h 0..360, s 0..1, l 0..1)。 */
private fun colorToHsl(color: Color): Triple<Float, Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d < 1e-6f) return Triple(0f, 0f, l)
    val s = d / (1f - kotlin.math.abs(2f * l - 1f))
    val h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return Triple((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
}

private fun GazePalette.toColorScheme(dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentContainer,
            onPrimaryContainer = ink,
            secondary = inkSoft,
            onSecondary = onAccent,
            tertiary = inkFaint,
            background = background,
            onBackground = ink,
            surface = background,
            onSurface = ink,
            surfaceVariant = raised,
            onSurfaceVariant = inkSoft,
            surfaceContainer = raised,
            surfaceContainerHigh = raised,
            surfaceContainerLow = background,
            surfaceContainerLowest = sunken,
            outline = hairlineStrong,
            outlineVariant = hairline,
            error = accent,
            onError = onAccent
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentContainer,
            onPrimaryContainer = ink,
            secondary = inkSoft,
            onSecondary = onAccent,
            tertiary = inkFaint,
            background = background,
            onBackground = ink,
            surface = background,
            onSurface = ink,
            surfaceVariant = raised,
            onSurfaceVariant = inkSoft,
            surfaceContainer = raised,
            surfaceContainerHigh = raised,
            surfaceContainerLow = background,
            surfaceContainerLowest = sunken,
            outline = hairlineStrong,
            outlineVariant = hairline,
            error = accent,
            onError = onAccent
        )
    }

/**
 * 弱圆角（文档第十九章要求「非圆角或弱圆角」）。
 * 印刷品的书页是直角，界面元素也应当克制 —— 4dp 只用来消除锯齿感，不构成「圆润」的观感。
 */
private val GazeShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp)
)

/**
 * 供个别地方（如细线分隔、纸色背景）取用的扩展色。
 * Material3 的语义色表覆盖不到「发丝线」这类概念，但它在 Editorial 风格里是核心元素。
 */
data class GazeExtraColors(
    val hairline: Color,
    val hairlineStrong: Color,
    val ink: Color,
    val inkFaint: Color,
    val accent: Color,
    val isDark: Boolean
)

val LocalGazeColors = staticCompositionLocalOf {
    GazeExtraColors(
        hairline = Hairline,
        hairlineStrong = HairlineStrong,
        ink = Ink,
        inkFaint = InkFaint,
        accent = Vermilion,
        isDark = false
    )
}

@Composable
fun GazeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeVariant: ThemeVariant = ThemeVariant.PAPER,
    themeSeed: Color = DefaultThemeSeed,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val palette = if (dark) darkPalette(themeVariant, themeSeed) else lightPalette(themeVariant, themeSeed)

    val extra = GazeExtraColors(
        hairline = palette.hairline,
        hairlineStrong = palette.hairlineStrong,
        ink = palette.ink,
        inkFaint = palette.inkFaint,
        accent = palette.accent,
        isDark = dark
    )

    CompositionLocalProvider(LocalGazeColors provides extra) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(dark),
            typography = GazeTypography,
            shapes = GazeShapes,
            content = content
        )
    }
}

/** 便捷读取扩展色。 */
val gazeColors: GazeExtraColors
    @Composable get() = LocalGazeColors.current
