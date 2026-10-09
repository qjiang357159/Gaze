package com.gaze.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.gaze.reader.domain.TagPalette
import com.gaze.reader.ui.theme.gazeColors

/**
 * 预设色一行放几个。
 *
 * 之前是每行 4 个 + 12dp 间距，在对话框里只占掉一半宽度，右边留一大片白。
 * 改成**每行 8 个 + SpaceBetween**：色点铺满整行，行数不变，
 * 顺手把预设从 8 色扩到 16 色 —— 排版变整齐的同时选择还更多了。
 */
private const val PRESET_PER_ROW = 8

/**
 * 标签配色选择器。
 *
 * 用固定尺寸的色点而不是靠 Row 均分 —— 均分会在窄屏上把圆点压成椭圆。
 * 横向位置交给 `SpaceBetween`，于是不论屏幕多宽，一行都是满的。
 *
 * 另外补了一条自定义通道：预设色再多也总有人想给「已完结」挑一个特别的颜色。
 */
@Composable
fun TagColorPicker(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = gazeColors

    // 自定义色的初始色相从当前选中色推不出来（Int -> HSV 不划算），
    // 给一个中性的起点即可，用户拖一下就到位了。
    var hue by remember { mutableFloatStateOf(210f) }
    var brightness by remember { mutableFloatStateOf(0.72f) }
    val customColor = Color.hsv(hue, 0.58f, brightness)
    val customArgb = customColor.toArgb()

    Column(modifier) {
        SectionLabel("颜色")
        Spacer(Modifier.height(10.dp))

        // 预设色：每行 8 个，铺满整行
        TagPalette.COLORS.chunked(PRESET_PER_ROW).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                row.forEach { candidate ->
                    ColorDot(
                        color = Color(candidate),
                        selected = candidate == selected,
                        onClick = { onSelect(candidate) }
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("自定义")
            Spacer(Modifier.weight(1f))
            ColorDot(
                color = customColor,
                selected = customArgb == selected,
                onClick = { onSelect(customArgb) }
            )
        }
        Spacer(Modifier.height(2.dp))

        // 色相条：用一层渐变做底，Slider 自己把轨道设成透明
        Box(Modifier.fillMaxWidth().height(28.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .align(Alignment.CenterStart)
                    .background(rainbowBrush(), CircleShape)
            )
            Slider(
                value = hue,
                onValueChange = {
                    hue = it
                    onSelect(Color.hsv(it, 0.58f, brightness).toArgb())
                },
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = customColor
                )
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "明暗",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                modifier = Modifier.width(34.dp)
            )
            Slider(
                value = brightness,
                onValueChange = {
                    brightness = it
                    onSelect(Color.hsv(hue, 0.58f, it).toArgb())
                },
                valueRange = 0.25f..1f,
                colors = SliderDefaults.colors(
                    activeTrackColor = colors.inkFaint,
                    inactiveTrackColor = colors.hairline,
                    thumbColor = customColor
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) gazeColors.ink else gazeColors.hairline,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

private fun rainbowBrush(): Brush = Brush.horizontalGradient(
    listOf(
        Color.hsv(0f, 0.58f, 0.85f),
        Color.hsv(60f, 0.58f, 0.85f),
        Color.hsv(120f, 0.58f, 0.85f),
        Color.hsv(180f, 0.58f, 0.85f),
        Color.hsv(240f, 0.58f, 0.85f),
        Color.hsv(300f, 0.58f, 0.85f),
        Color.hsv(360f, 0.58f, 0.85f)
    )
)

// ==================================================================== 主题主色

/**
 * 主题主色选择器。
 *
 * 与标签配色选择器不同，这里选的是**整套主题的种子**：程序会从它派生出
 * 纸色、墨色与强调色。所以除了三个滑块，还画一条「派生预览」——
 * 用户拖动时能立刻看到最终会得到什么样的纸和字，而不是靠想象。
 *
 * 预设色偏「低饱和、偏灰」的一批：主题色要铺满整个界面，
 * 高饱和的纯色做纸底会刺眼。
 */
@Composable
fun ThemeColorPicker(
    seed: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = gazeColors
    val initial = rgbToHsv(Color(seed))

    var hue by remember { mutableFloatStateOf(initial.first) }
    var saturation by remember { mutableFloatStateOf(initial.second.coerceIn(0.05f, 0.9f)) }
    var lightness by remember { mutableFloatStateOf(initial.third.coerceIn(0.2f, 0.95f)) }

    val current = Color.hsv(hue, saturation, lightness)
    val currentArgb = current.toArgb()

    Column(modifier) {
        SectionLabel("主题主色")
        Spacer(Modifier.height(10.dp))

        // 预设：每行 8 个 + SpaceBetween，铺满整行（原来 5 个靠左，右侧空一半）
        THEME_SEEDS.chunked(PRESET_PER_ROW).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                row.forEach { candidate ->
                    ColorDot(
                        color = Color(candidate),
                        selected = candidate == seed,
                        onClick = {
                            onSelect(candidate)
                            val c = rgbToHsv(Color(candidate))
                            hue = c.first
                            saturation = c.second.coerceIn(0.05f, 0.9f)
                            lightness = c.third.coerceIn(0.2f, 0.95f)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 派生预览：纸色 / 强调色 / 墨色
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("预览")
            Spacer(Modifier.weight(1f))
            PreviewChip(label = "纸", color = derivedPaper(current))
            Spacer(Modifier.width(8.dp))
            PreviewChip(label = "强调", color = current)
            Spacer(Modifier.width(8.dp))
            PreviewChip(label = "墨", color = derivedInk(current))
        }

        Spacer(Modifier.height(10.dp))

        Box(Modifier.fillMaxWidth().height(28.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .align(Alignment.CenterStart)
                    .background(rainbowBrush(), CircleShape)
            )
            Slider(
                value = hue,
                onValueChange = {
                    hue = it
                    onSelect(Color.hsv(it, saturation, lightness).toArgb())
                },
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = current
                )
            )
        }

        LabeledSlider(
            label = "浓淡",
            value = saturation,
            range = 0.05f..0.9f,
            thumb = current,
            onValueChange = {
                saturation = it
                onSelect(Color.hsv(hue, it, lightness).toArgb())
            }
        )
        LabeledSlider(
            label = "明暗",
            value = lightness,
            range = 0.2f..0.95f,
            thumb = current,
            onValueChange = {
                lightness = it
                onSelect(Color.hsv(hue, saturation, it).toArgb())
            }
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = "整套主题由这一个颜色派生 —— 纸色是它的极淡版本，墨色是极深版本。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkFaint
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    thumb: Color,
    onValueChange: (Float) -> Unit
) {
    val colors = gazeColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkFaint,
            modifier = Modifier.width(34.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                activeTrackColor = colors.inkFaint,
                inactiveTrackColor = colors.hairline,
                thumbColor = thumb
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PreviewChip(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, gazeColors.hairline, CircleShape)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = gazeColors.inkFaint
        )
    }
}

/** 与 Theme.kt 的派生口径保持一致的近似预览（只用于展示，不参与真正的取色）。 */
private fun derivedPaper(seed: Color): Color {
    val (h, s, _) = rgbToHsv(seed)
    return Color.hsv(h, (s * 0.30f).coerceIn(0f, 1f), 0.955f)
}

private fun derivedInk(seed: Color): Color {
    val (h, s, _) = rgbToHsv(seed)
    return Color.hsv(h, (s * 0.5f).coerceAtMost(0.30f), 0.085f)
}

/**
 * RGB -> HSV。
 *
 * 不用 Compose 的 `Color.hue` / `saturation` 扩展属性 —— 它们在
 * Compose 1.6 才补齐，写死在这里既能少一个版本依赖，也顺便和
 * Theme.kt 里的 `colorToHsl` 保持同一套自算风格。
 *
 * @return (hue 0..360, saturation 0..1, value 0..1)
 */
private fun rgbToHsv(color: Color): Triple<Float, Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val v = max
    val s = if (max < 1e-6f) 0f else d / max
    val h = if (d < 1e-6f) {
        0f
    } else {
        val raw = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        (raw + 360f) % 360f
    }
    return Triple(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
}

/** 主题预设主色：低饱和、偏灰的一批，铺满界面时不刺眼。 */
private val THEME_SEEDS: List<Int> = listOf(
    0xFFB4472F.toInt(), // 朱红
    0xFF9A6B2F.toInt(), // 赭石
    0xFF3A5A8C.toInt(), // 靛蓝
    0xFF3F6B4F.toInt(), // 墨绿
    0xFFA64B6B.toInt(), // 玫
    0xFF4A5A6E.toInt(), // 冷钢蓝
    0xFF6B4E8C.toInt(), // 紫棠
    0xFF2F6B6B.toInt(), // 松石
    0xFF8C5A2F.toInt(), // 焦糖
    0xFF5A5A5A.toInt(), // 石墨
    0xFF7A3F4A.toInt(), // 绛
    0xFF4A7A5F.toInt(), // 松绿
    0xFF5A6472.toInt(), // 灰蓝
    0xFF8C7A3F.toInt(), // 沙金
    0xFF3F5A7A.toInt(), // 深靛
    0xFF6B5A4A.toInt()  // 褐
)
