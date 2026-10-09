package com.gaze.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gaze.reader.BuildConfig
import com.gaze.reader.core.Formatters
import com.gaze.reader.core.LibraryLayout
import com.gaze.reader.core.LibraryView
import com.gaze.reader.core.SortOrder
import com.gaze.reader.core.ThemeMode
import com.gaze.reader.core.ThemeVariant
import com.gaze.reader.data.db.LibraryStats
import com.gaze.reader.data.prefs.GazeSettings
import com.gaze.reader.scan.ScanPhase
import com.gaze.reader.scan.ScanProgress
import com.gaze.reader.ui.components.BackToTopButton
import com.gaze.reader.ui.components.GazeIcons
import com.gaze.reader.ui.components.Hairline
import com.gaze.reader.ui.components.ScreenTitle
import com.gaze.reader.ui.components.ScrollStateScrollbar
import com.gaze.reader.ui.components.SectionLabel
import com.gaze.reader.ui.components.ThinProgress
import com.gaze.reader.ui.components.scrolledBeyond
import com.gaze.reader.ui.theme.gazeColors
import kotlinx.coroutines.launch

/**
 * 设置主入口。
 *
 * **分组卡片**。v1.3.1 之前是「栏目头（图标 + 粗体标题 + 一条横线）+ 一行入口」，
 * 而每个栏目下恰好只有一行 —— 于是同一枚图标连着出现两次，标题也成了
 * 「主题与配色」外面再套一层「外观」这样的同义重复，读起来全是噪音；
 * 改成「一行一张卡片」之后，七个薄块又全飘在屏幕上半截，下半截空着。
 *
 * 现在按**语义分三组**（外观 / 阅读 / 数据）：一组一个标题 + 一张卡片，
 * 卡片里装一到三行，行间用缩进细线分开。同样七个入口只占三张卡片，
 * 既压住了纵向高度，也让层级一眼可辨。
 */
@Composable
fun SettingsScreen(
    settings: GazeSettings,
    stats: LibraryStats?,
    scan: ScanProgress?,
    message: String?,
    onCancelScan: () -> Unit,
    onConsumeMessage: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenNaming: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title = "设置", subtitle = "Gaze ${BuildConfig.VERSION_NAME}")

        ScanBanner(scan = scan, onCancel = onCancelScan)
        MessageBar(message = message, onConsume = onConsumeMessage)

        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Spacer(Modifier.height(6.dp))

                // 库概览一行小字。
                //
                // 设置页原本只有七个入口，短内容撑不满一屏，下半截全是空白 ——
                // 观感上像「还没加载完」。这一行既是真信息（有多少本、占多大），
                // 也把页面的上沿压实。
                stats?.let { SettingsStatsLine(it) }

                SettingsGroup("外观") {
                    SettingsEntryRow(
                        icon = GazeIcons.Palette,
                        label = "主题与配色",
                        value = "${settings.themeMode.label} · ${settings.themeVariant.label}",
                        onClick = onOpenAppearance
                    )
                    SettingsRowDivider()
                    SettingsEntryRow(
                        icon = GazeIcons.grid(2),
                        label = "书架显示",
                        value = "${settings.libraryView.label} · ${settings.libraryLayout.label}",
                        onClick = onOpenLibrary
                    )
                }

                SettingsGroup("阅读") {
                    SettingsEntryRow(
                        icon = GazeIcons.Book,
                        label = "阅读行为",
                        value = "${settings.defaultReadMode.label} · ${settings.defaultFitMode.label}",
                        onClick = onOpenReading
                    )
                    SettingsRowDivider()
                    SettingsEntryRow(
                        icon = GazeIcons.TextLines,
                        label = "解析规则",
                        value = parseSummary(settings),
                        onClick = onOpenNaming
                    )
                    SettingsRowDivider()
                    SettingsEntryRow(
                        icon = GazeIcons.History,
                        label = "阅读统计",
                        value = "阅读时长 · 翻页数 · 会话记录",
                        onClick = onOpenStats
                    )
                }

                SettingsGroup("数据") {
                    SettingsEntryRow(
                        icon = GazeIcons.Folder,
                        label = "扫描与存储",
                        value = "${stats?.totalCount ?: 0} 本 · ${settings.renderMode.label}",
                        onClick = onOpenMedia
                    )
                    SettingsRowDivider()
                    SettingsEntryRow(
                        icon = GazeIcons.Archive,
                        label = "备份与恢复",
                        value = settings.backupFolderName?.takeIf { it.isNotBlank() }
                            ?: "未指定备份文件夹",
                        onClick = onOpenBackup
                    )
                    SettingsRowDivider()
                    SettingsEntryRow(
                        icon = GazeIcons.Info,
                        label = "关于 Gaze",
                        value = "版本 ${BuildConfig.VERSION_NAME}",
                        onClick = onOpenAbout
                    )
                }

                Spacer(Modifier.height(28.dp))
            }
            ScrollStateScrollbar(
                state = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
            )
            BackToTopButton(
                state = scrollState,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
            )
        }
    }
}

/**
 * 设置主入口的一组。
 *
 * 一组 = 一个小标题 + 一张卡片，卡片里可以放好几行，行与行之间用一条缩进的
 * 细线分开。v1.3.1 之前是「一行一张卡片」，七个薄卡片飘在屏幕上半截，
 * 下半截全空 —— 分组之后同样七行只占三张卡片，密度上来了，层级也更清楚
 * （外观 / 阅读 / 数据）。
 */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = gazeColors
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = SettingsGutter, end = SettingsGutter, top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkFaint
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(colors.hairline))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsGutter)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, colors.hairline, shape),
            content = content
        )
    }
}

/** 组内两行之间的分隔线。缩进到与文字左边缘对齐（内边距 + 图标 18 + 间距 14）。 */
@Composable
private fun SettingsRowDivider() {
    Hairline(Modifier.padding(start = SettingsRowInset + 32.dp))
}

/** 设置页顶部的库概览。 */
@Composable
private fun SettingsStatsLine(stats: LibraryStats) {
    val text = buildString {
        append("${stats.totalCount} 本")
        append("  ·  ${Formatters.fileSize(stats.totalBytes, "0 B")}")
        if (stats.favoriteCount > 0) append("  ·  ${stats.favoriteCount} 收藏")
        if (stats.unreadCount > 0) append("  ·  ${stats.unreadCount} 未读")
        if (stats.finishedCount > 0) append("  ·  ${stats.finishedCount} 已读完")
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = gazeColors.inkFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
    )
}

/** 解析规则的摘要：把启用的类型拼起来，太长会在入口行里省略。 */
private fun parseSummary(settings: GazeSettings): String {
    val parts = buildList {
        if (settings.parseAuthorBySeparator) add("分隔符「${settings.authorSeparator}」")
        if (settings.parseAuthorByBracket) add("括号「${settings.authorBracket}」")
    }
    return if (parts.isEmpty()) "不解析作者" else parts.joinToString(" · ")
}

/** 卡片里的一行入口：图标 + 名称 + 当前值 + 尖括号。 */
@Composable
private fun SettingsEntryRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    val colors = gazeColors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SettingsRowInset, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.ink,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        Text(text = "›", style = MaterialTheme.typography.bodyLarge, color = colors.inkFaint)
    }
}

// ==================================================================== 通用骨架

/**
 * 二级页骨架：返回箭头 + 标题 + 可滚动内容。
 *
 * 与主入口页用同一个 `ScreenTitle` 的字号体系，只是把标题换成返回按钮 ——
 * 二级页不该比一级页更「重」。
 */
@Composable
fun SettingsPage(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = gazeColors
    val scrollState = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.ink
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkFaint
                        )
                    }
                }
            }
            Hairline()
        }

        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                content()
                Spacer(Modifier.height(40.dp))
            }
            ScrollStateScrollbar(
                state = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
            )
            BackToTopButton(
                state = scrollState,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
            )
        }
    }
}

/**
 * 设置页的一个栏目。
 *
 * 图标 + 加粗加深的标题是刻意的：之前每个栏目都只是一行 10sp 的浅灰小字，
 * 一屏七八个栏目扫下来完全分不出边界。图标给形状，粗体给重量，
 * 两者一起才能让「这是新的一组设置」一眼可见。
 *
 * 内容统一装进一张带细边的卡片里 —— 与主入口页的 [SettingsGroup] 同一套语言。
 * 之前二级页是「标题 + 光秃秃的控件」，长页面读起来像没排版；
 * 加上卡片之后每一组都有明确的起止，页面也显得充实。
 */
@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    val colors = gazeColors
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SettingsGutter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(colors.hairline))
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsGutter)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, colors.hairline, shape)
        ) {
            content()
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
fun SettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = gazeColors.inkFaint,
        modifier = Modifier.padding(horizontal = SettingsRowInset, vertical = 8.dp)
    )
}

// ==================================================================== 栅格常量
//
// 设置页此前是「谁写谁定」的内边距：说明文字 20dp、大部分行 14dp、
// 色卡网格 12dp、栏目头又是另一个数 —— 于是同一屏里三四条左边缘各走各的，
// 卡片内容还会随控件类型左右跳。
//
// 现在收敛成一条竖线：**卡片外边距 = 栏目头图标的起点**，
// **卡片内容内边距 = 栏目头标题文字的起点**。任何新加的控件都从这两个常量取，
// 不要再写字面量。

/**
 * 卡片距屏幕左右边缘的距离，也是栏目头图标的起点。
 *
 * 栏目头标题文字的起点 = 20（外边距）+ 14（图标）+ 8（间距）= 42；
 * 卡片内容的起点 = 20 + 1（边框）+ [SettingsRowInset] = 41。
 * 两者只差 1dp（就是那条边框），肉眼看不出来 —— 栏目标题与卡片内容
 * 因此落在同一条竖线上。
 */
internal val SettingsGutter = 20.dp

/** 卡片内一行内容的左右留白。卡片自己还有 [SettingsGutter] 的外边距与 1dp 边框。 */
internal val SettingsRowInset = 20.dp

// ==================================================================== 通用行

/**
 * 一组单选选项。
 *
 * 排布方式从「按内容宽度自然折行」改成了**等宽网格**：自然折行时每行宽窄不一，
 * 末行常常只剩一个孤零零的药丸，几组叠在一页里看着就像没对齐的补丁。
 * 等宽之后每一列都严格对齐，末行即使不满也占住自己那格，整页的竖线是齐的。
 *
 * 列数由 [choiceColumns] 按项数与最长标签算出来 —— 不再让排版随文案长度漂。
 */
@Composable
fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    hint: String? = null
) {
    val colors = gazeColors
    Column(Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.inkFaint)
        }
        Spacer(Modifier.height(10.dp))
        ChipGrid(
            items = options.map { (key, text) ->
                ChipItem(label = text, selected = key == selected) { onSelect(key) }
            }
        )
    }
}

/** 一枚可选项药丸。`ChoiceRow` 与文件名解析的规则选择共用同一套视觉。 */
@Composable
internal fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = gazeColors
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) colors.ink else Color.Transparent)
            .border(1.dp, if (selected) colors.ink else colors.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.background else colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 一枚待排布的选项。 */
internal data class ChipItem(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit
)

/**
 * 把若干选项排成等宽网格。
 *
 * 末行不满时补等宽空位（而不是让药丸撑满）—— 补空位才能保住列对齐；
 * 让最后一个撑满整行会把它变成「按钮」，重量突然变了。
 */
@Composable
internal fun ChipGrid(
    items: List<ChipItem>,
    modifier: Modifier = Modifier,
    columns: Int = choiceColumns(items.map { it.label })
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    ChoiceChip(
                        text = item.label,
                        selected = item.selected,
                        onClick = item.onClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 等宽网格的列数。
 *
 * 两条约束：
 * 1. **尽量整除** —— 4 项排 2 列、9 项排 3 列都能铺满，末行不留孤儿；
 * 2. **按最长标签兜底** —— 估宽超过一格就退到 2 列，
 *    免得长标签被省略号截成「列表·小封…」。
 */
internal fun choiceColumns(labels: List<String>): Int {
    if (labels.size <= 2) return maxOf(labels.size, 1)
    val longest = labels.maxOfOrNull { estimatedTextWidth(it) } ?: 0
    if (longest > 72) return 2
    return if (labels.size % 3 == 0 || labels.size > 4) 3 else 2
}

/**
 * 粗估一段 12sp 文本的宽度（dp）。只用于挑列数，不求精确。
 *
 * 用显式循环而不是 `sumOf { }` —— 后者的重载按 Int / Long / Double / UInt / ULong
 * 各有一个，lambda 里全是整数字面量时编译器无法定夺，会报「重载解析有歧义」。
 */
private fun estimatedTextWidth(text: String): Int {
    var width = 0
    for (ch in text) width += if (ch.code >= 0x2E80) 12 else 7
    return width
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    hint: String? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
                .padding(horizontal = SettingsRowInset, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = gazeColors.inkFaint,
                modifier = Modifier.padding(start = SettingsRowInset, end = SettingsRowInset, bottom = 8.dp)
            )
        }
    }
}

@Composable
fun ActionRow(
    label: String,
    subtitle: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val colors = gazeColors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SettingsRowInset, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) colors.accent else MaterialTheme.colorScheme.onBackground
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.inkFaint)
        }
    }
}

/**
 * 「名称 —— 值」一行。
 *
 * 权重必须给**值**，不能给名称：名称只有几个字，给它 `weight(1f)` 之后，
 * 一旦值很长（比如「支持格式」那一串），Row 会先满足值所需的宽度、
 * 把名称压到几乎零宽 —— 于是名称被挤成一列竖排的单个汉字，还和值叠在一起。
 *
 * 现在名称取自然宽度，值占剩余宽度并右对齐：值再长也只会在自己那半边换行。
 * 需要整行展示的长内容用 [stacked]（名称在上、值在下一行左对齐）。
 */
@Composable
fun StatRow(label: String, value: String, stacked: Boolean = false) {
    val colors = gazeColors
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkFaint
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkFaint,
            maxLines = 1,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

// ==================================================================== 顶部提示

/** 扫描 / 封面生成的进度条。二级页里也要显示，否则「进了设置就不知道还在扫」。 */
@Composable
fun ScanBanner(scan: ScanProgress?, onCancel: () -> Unit) {
    val colors = gazeColors
    if (scan == null) return

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (scan.phase) {
                    ScanPhase.DISCOVERING -> "正在查找漫画…"
                    ScanPhase.IMPORTING -> "正在索引 ${scan.processed} / ${scan.total}"
                    ScanPhase.MARKING -> "正在校验文件…"
                    ScanPhase.DONE -> "扫描完成"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text("取消") }
        }
        ThinProgress(
            progress = if (scan.total > 0) scan.processed.toFloat() / scan.total else 0.35f
        )
    }
}

@Composable
fun MessageBar(message: String?, onConsume: () -> Unit) {
    if (message == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onConsume) { Text("知道了") }
    }
}

/** 供二级页拼摘要文本用。 */
internal fun layoutSummary(view: LibraryView, layout: LibraryLayout, sort: SortOrder): String =
    "${view.label} · ${layout.label} · ${sort.label}"

/** 主题摘要。 */
internal fun themeSummary(mode: ThemeMode, variant: ThemeVariant): String =
    "${mode.label} · ${variant.label}"
