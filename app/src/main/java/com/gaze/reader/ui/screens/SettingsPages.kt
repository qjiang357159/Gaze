package com.gaze.reader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gaze.reader.BuildConfig
import com.gaze.reader.backup.BackupEntry
import com.gaze.reader.backup.BackupFormat
import com.gaze.reader.backup.BackupInfo
import com.gaze.reader.backup.BackupResult
import com.gaze.reader.backup.MissingReport
import com.gaze.reader.backup.RestoreMode
import com.gaze.reader.core.AutoBackupInterval
import com.gaze.reader.core.FileNameParser
import com.gaze.reader.core.FitMode
import com.gaze.reader.core.Formatters
import com.gaze.reader.core.LibraryLayout
import com.gaze.reader.core.LibraryView
import com.gaze.reader.core.ReadMode
import com.gaze.reader.core.ReaderBackground
import com.gaze.reader.core.RenderMode
import com.gaze.reader.core.ScreenOrientation
import com.gaze.reader.core.SortOrder
import com.gaze.reader.core.ThemeMode
import com.gaze.reader.core.ThemeVariant
import com.gaze.reader.data.db.FolderEntity
import com.gaze.reader.data.db.LibraryStats
import com.gaze.reader.data.prefs.GazeSettings
import com.gaze.reader.domain.UriDisplay
import com.gaze.reader.scan.HealthReport
import com.gaze.reader.scan.ScanProgress
import com.gaze.reader.scan.persistUriPermission
import com.gaze.reader.ui.components.GazeIcons
import com.gaze.reader.ui.components.Hairline
import com.gaze.reader.ui.components.SectionLabel
import com.gaze.reader.ui.components.ThemeColorPicker
import com.gaze.reader.ui.components.ThinProgress
import com.gaze.reader.ui.theme.CreamBg
import com.gaze.reader.ui.theme.GraphiteBg
import com.gaze.reader.ui.theme.Indigo
import com.gaze.reader.ui.theme.MistBg
import com.gaze.reader.ui.theme.Moss
import com.gaze.reader.ui.theme.Ochre
import com.gaze.reader.ui.theme.Paper
import com.gaze.reader.ui.theme.Plum
import com.gaze.reader.ui.theme.PlumBg
import com.gaze.reader.ui.theme.Rose
import com.gaze.reader.ui.theme.RoseBg
import com.gaze.reader.ui.theme.SageBg
import com.gaze.reader.ui.theme.Steel
import com.gaze.reader.ui.theme.Vermilion
import com.gaze.reader.ui.theme.gazeColors

// ==================================================================== 外观

/**
 * 主题与配色。
 *
 * 两个选项组各自换了形态：
 * - 明暗只有三项 → 横向一排药丸，一行装得下；
 * - 纸张主题 → **色卡网格**（一行四格）。每格直接把该套主题的纸色铺出来、
 *   中间点一个强调色圆点，一眼就能比较，比读八个词快得多。
 *
 * 其余二级页的选项组统一走 `ChoiceRow`：横向排布、超宽自动折行，
 * 不再是「一个选项一整行」的竖排列表。
 */
@Composable
fun AppearanceSettingsScreen(
    settings: GazeSettings,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetThemeVariant: (ThemeVariant) -> Unit,
    onSetThemeSeed: (Int) -> Unit,
    onBack: () -> Unit
) {
    SettingsPage(
        title = "主题与配色",
        subtitle = themeSummary(settings.themeMode, settings.themeVariant),
        onBack = onBack
    ) {
        SettingsSection("明暗", icon = GazeIcons.Auto) {
            ChoiceRow(
                label = "主题模式",
                options = ThemeMode.entries.map { it.key to it.label },
                selected = settings.themeMode.key,
                onSelect = { onSetThemeMode(ThemeMode.of(it)) }
            )
        }

        SettingsSection("纸张主题", icon = GazeIcons.Palette) {
            ThemeVariantGrid(
                selected = settings.themeVariant,
                seed = settings.themeSeed,
                onSelect = onSetThemeVariant
            )
        }

        if (settings.themeVariant == ThemeVariant.CUSTOM) {
            SettingsSection("自定义主色", icon = GazeIcons.Palette) {
                ThemeColorPicker(
                    seed = settings.themeSeed,
                    onSelect = onSetThemeSeed,
                    modifier = Modifier.padding(horizontal = SettingsRowInset)
                )
            }
        }
    }
}

/**
 * 纸张主题色卡网格。
 *
 * 每格是「一张纸」：底色就是那套主题的纸色，中间点一个该套主题的强调色圆点。
 * 选中态用强调色描边 + 文字变色，不额外加对勾 —— 网格里再加勾会太吵。
 */
@Composable
private fun ThemeVariantGrid(
    selected: ThemeVariant,
    seed: Int,
    onSelect: (ThemeVariant) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 6.dp)) {
        ThemeVariant.entries.chunked(4).forEach { row ->
            Row(
                // 行距与列距一致（10dp）：两者不等时网格会看着「上下比左右松」。
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { variant ->
                    ThemeSwatch(
                        variant = variant,
                        seed = seed,
                        selected = variant == selected,
                        onClick = { onSelect(variant) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // 最后一行不足四格时补空位，保证每格宽度一致
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(
    variant: ThemeVariant,
    seed: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = gazeColors
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(shape)
                .background(paperColorOf(variant, seed))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accent else colors.hairlineStrong,
                    shape = shape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(accentColorOf(variant, seed))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = variant.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.accent else colors.inkFaint,
            maxLines = 1
        )
    }
}

/** 每套主题的纸色（与 Theme.kt 的预设色板对应）。 */
private fun paperColorOf(variant: ThemeVariant, seed: Int): Color = when (variant) {
    ThemeVariant.PAPER -> Paper
    ThemeVariant.CREAM -> CreamBg
    ThemeVariant.MIST -> MistBg
    ThemeVariant.SAGE -> SageBg
    ThemeVariant.ROSE -> RoseBg
    ThemeVariant.GRAPHITE -> GraphiteBg
    ThemeVariant.PLUM -> PlumBg
    // 自定义：用和 Theme.kt 同口径的派生公式算一张极淡的纸出来
    ThemeVariant.CUSTOM -> {
        val (h, s) = colorToHueSat(Color(seed))
        Color.hsv(h, (s * 0.30f).coerceIn(0f, 1f), 0.955f)
    }
}

/** 每套主题的强调色。 */
private fun accentColorOf(variant: ThemeVariant, seed: Int): Color = when (variant) {
    ThemeVariant.PAPER -> Vermilion
    ThemeVariant.CREAM -> Ochre
    ThemeVariant.MIST -> Indigo
    ThemeVariant.SAGE -> Moss
    ThemeVariant.ROSE -> Rose
    ThemeVariant.GRAPHITE -> Steel
    ThemeVariant.PLUM -> Plum
    ThemeVariant.CUSTOM -> Color(seed)
}

/** 只取色相与饱和度，供自定义主题的纸色预览用。 */
private fun colorToHueSat(color: Color): Pair<Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
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
    return h to s.coerceIn(0f, 1f)
}

// ==================================================================== 阅读

@Composable
fun ReadingSettingsScreen(
    settings: GazeSettings,
    onSetReadMode: (ReadMode) -> Unit,
    onSetFitMode: (FitMode) -> Unit,
    onSetRenderMode: (RenderMode) -> Unit,
    onSetScreenOrientation: (ScreenOrientation) -> Unit,
    onSetBrightnessFollowSystem: (Boolean) -> Unit,
    onSetBrightness: (Float) -> Unit,
    onSetVolumeKeyPaging: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetShowPageNumber: (Boolean) -> Unit,
    onSetReaderBackground: (ReaderBackground) -> Unit,
    onBack: () -> Unit
) {
    val colors = gazeColors
    SettingsPage(
        title = "阅读行为",
        subtitle = "${settings.defaultReadMode.label} · ${settings.defaultFitMode.label}",
        onBack = onBack
    ) {
        SettingsSection("默认阅读", icon = GazeIcons.Book) {
            SettingsNote("这里是全局默认。在阅读页单独改过的漫画会记住自己的设置，不受影响。")
            ChoiceRow(
                label = "阅读模式",
                options = ReadMode.entries.map { it.key to it.label },
                selected = settings.defaultReadMode.key,
                onSelect = { onSetReadMode(ReadMode.of(it)) }
            )
            Hairline(Modifier.padding(start = SettingsRowInset))
            ChoiceRow(
                label = "图片适配",
                options = FitMode.entries.map { it.key to it.label },
                selected = settings.defaultFitMode.key,
                onSelect = { onSetFitMode(FitMode.of(it)) }
            )
        }

        SettingsSection("渲染", icon = GazeIcons.PageNumber) {
            ChoiceRow(
                label = "渲染模式",
                hint = RenderMode.entries.first { it.key == settings.renderMode.key }.hint,
                options = RenderMode.entries.map { it.key to it.label },
                selected = settings.renderMode.key,
                onSelect = { onSetRenderMode(RenderMode.of(it)) }
            )
        }

        SettingsSection("阅读器背景", icon = GazeIcons.Brightness) {
            SettingsNote("与系统主题独立 —— 白天用浅色系统，阅读时照样可以用黑底。")
            ChoiceRow(
                label = "漫画背景",
                options = ReaderBackground.entries.map { it.key to it.label },
                selected = settings.readerBackground.key,
                onSelect = { onSetReaderBackground(ReaderBackground.of(it)) }
            )
        }

        SettingsSection("屏幕", icon = GazeIcons.Rotate) {
            ChoiceRow(
                label = "屏幕旋转",
                hint = "阅读页的方向可以独立于系统设置。",
                options = ScreenOrientation.entries.map { it.key to it.label },
                selected = settings.screenOrientation.key,
                onSelect = { onSetScreenOrientation(ScreenOrientation.of(it)) }
            )
            Hairline(Modifier.padding(start = SettingsRowInset))
            ToggleRow(
                label = "亮度跟随系统",
                checked = settings.brightnessFollowSystem,
                onChange = onSetBrightnessFollowSystem,
                hint = if (settings.brightnessFollowSystem) {
                    "阅读页使用系统亮度，不会覆盖你手动调过的屏幕亮度。"
                } else {
                    "阅读页使用下面的自定义亮度，退出后自动还原。"
                }
            )
            if (!settings.brightnessFollowSystem) {
                Column(Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("亮度")
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${(settings.brightness * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkFaint
                        )
                    }
                    Slider(
                        value = settings.brightness,
                        onValueChange = onSetBrightness,
                        valueRange = 0.05f..1f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = colors.inkFaint,
                            inactiveTrackColor = colors.hairline,
                            thumbColor = colors.accent
                        )
                    )
                }
            }
            Hairline(Modifier.padding(start = SettingsRowInset))
            ToggleRow(
                label = "阅读时保持屏幕常亮",
                checked = settings.keepScreenOn,
                onChange = onSetKeepScreenOn
            )
        }

        SettingsSection("操作", icon = GazeIcons.VolumeKeys) {
            ToggleRow(
                label = "音量键翻页",
                checked = settings.volumeKeyPaging,
                onChange = onSetVolumeKeyPaging,
                hint = "音量上键上一页、下键下一页。开启后阅读页会占用音量键，无法调节音量。"
            )
            Hairline(Modifier.padding(start = SettingsRowInset))
            ToggleRow(
                label = "显示页码",
                checked = settings.showPageNumber,
                onChange = onSetShowPageNumber
            )
        }
    }
}

// ==================================================================== 书架

@Composable
fun LibrarySettingsScreen(
    settings: GazeSettings,
    onSetLibraryView: (LibraryView) -> Unit,
    onSetLibraryLayout: (LibraryLayout) -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    onBack: () -> Unit
) {
    SettingsPage(
        title = "书架显示",
        subtitle = layoutSummary(settings.libraryView, settings.libraryLayout, settings.sortOrder),
        onBack = onBack
    ) {
        SettingsSection("默认视图", icon = GazeIcons.grid(2)) {
            ChoiceRow(
                label = "视图",
                hint = "「文件夹」与「标签」会把同一个目录 / 同一个标签下的漫画收进一个书架文件夹。",
                options = LibraryView.entries.map { it.key to it.label },
                selected = settings.libraryView.key,
                onSelect = { onSetLibraryView(LibraryView.of(it)) }
            )
        }

        SettingsSection("排版", icon = GazeIcons.list(true)) {
            ChoiceRow(
                label = "排版方式",
                options = LibraryLayout.entries.map { it.key to it.label },
                selected = settings.libraryLayout.key,
                onSelect = { onSetLibraryLayout(LibraryLayout.of(it)) }
            )
        }

        SettingsSection("排序", icon = GazeIcons.TextLines) {
            SettingsNote("「名称 A-Z」按自然数字顺序 —— 第 2 卷排在第 10 卷前面，而不是按字符比较。")
            ChoiceRow(
                label = "默认排序",
                options = SortOrder.entries.map { it.key to it.label },
                selected = settings.sortOrder.key,
                onSelect = { onSetSortOrder(SortOrder.of(it)) }
            )
        }
    }
}

// ==================================================================== 媒体库

@Composable
fun MediaSettingsScreen(
    settings: GazeSettings,
    stats: LibraryStats?,
    folders: List<FolderEntity>,
    scan: ScanProgress?,
    health: HealthReport?,
    message: String?,
    missingCount: Int,
    onAddFolder: (Uri) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onClearCoverCache: () -> Unit,
    onHealthCheck: () -> Unit,
    onCancelHealthCheck: () -> Unit,
    onDismissHealthReport: () -> Unit,
    onOpenMissing: () -> Unit,
    onSetAutoScan: (Boolean) -> Unit,
    onConsumeMessage: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.persistUriPermission(uri, isTree = true)
            onAddFolder(uri)
        }
    }

    // 授权过的与「待重新授权」的分开渲染：混在一张清单里，用户根本分不清
    // 哪个是真的能扫、哪个只是一条来自备份的线索。
    val authorized = folders.filter { it.pending != true }
    val pendingFolders = folders.filter { it.pending == true }

    SettingsPage(
        title = "扫描与存储",
        subtitle = "${stats?.totalCount ?: 0} 本 · ${Formatters.fileSize(stats?.totalBytes ?: 0L, "0 B")}",
        onBack = onBack
    ) {
        ScanBanner(scan = scan, onCancel = onCancelScan)
        MessageBar(message = message, onConsume = onConsumeMessage)

        // 「概览」与「阅读统计」都不在这里 —— 统计数字属于库的整体情况，
        // 入口放在设置主入口的顶部概览行与「阅读」分组里。这一页只留
        // 真正和「扫描 / 存储」有关的三件事：授权目录、扫描、缓存与维护。

        SettingsSection("已授权文件夹", icon = GazeIcons.Folder) {
            SettingsNote("Gaze 只读取你主动授权的目录，不做全盘扫描。")
            if (authorized.isEmpty()) {
                SettingsNote("还没有授权任何文件夹。")
            } else {
                authorized.forEach { folder ->
                    FolderRow(folder = folder, onRemove = { onRemoveFolder(folder.uri) })
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = SettingsRowInset, end = 8.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "授权新文件夹",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { folderLauncher.launch(null) }) { Text("选择") }
            }
        }

        if (pendingFolders.isNotEmpty()) {
            SettingsSection("待重新授权的文件夹", icon = GazeIcons.Info) {
                SettingsNote(
                    "这些目录来自一份恢复回来的备份。备份文件里存不下 SAF 读权限，" +
                        "所以它们还不是已授权状态 —— 重新点一次目录选择器，它们就能照常扫描。"
                )
                pendingFolders.forEach { folder ->
                    PendingFolderRow(
                        folder = folder,
                        onAuthorize = { folderLauncher.launch(null) },
                        onIgnore = { onRemoveFolder(folder.uri) }
                    )
                    Hairline(Modifier.padding(start = SettingsRowInset))
                }
            }
        }

        if (missingCount > 0) {
            SettingsSection("媒体匹配", icon = Icons.Default.Warning) {
                SettingsNote(
                    "$missingCount 本漫画在本机找不到对应文件。阅读进度、收藏和标签都还留着，" +
                        "重新指定文件位置就能接回来。"
                )
                ActionRow(
                    label = "去媒体匹配",
                    subtitle = "手动重新定位，或让 Gaze 自己按内容找",
                    onClick = onOpenMissing
                )
            }
        }

        SettingsSection("扫描", icon = Icons.Default.Refresh) {
            ActionRow(
                label = "重新扫描漫画库",
                subtitle = "只检查发生变化的文件，不会重新解析整个库",
                onClick = onScan
            )
            Hairline(Modifier.padding(start = SettingsRowInset))
            ToggleRow(
                label = "启动时自动增量扫描",
                checked = settings.autoScanOnLaunch,
                onChange = onSetAutoScan,
                hint = "后台进行，只在书架顶部显示进度，不会弹提示打断你。"
            )
        }

        SettingsSection("缓存与维护", icon = GazeIcons.Archive) {
            ActionRow(
                label = "清空封面缓存",
                subtitle = "下次打开书架会重新生成",
                onClick = onClearCoverCache
            )
            Hairline(Modifier.padding(start = SettingsRowInset))
            ActionRow(
                label = "检查文件健康",
                subtitle = "逐个打开归档，找出损坏、加密或页数异常的漫画",
                onClick = onHealthCheck
            )
            health?.let { report ->
                HealthPanel(report, onCancelHealthCheck, onDismissHealthReport)
            }
        }
    }
}

@Composable
private fun FolderRow(folder: FolderEntity, onRemove: () -> Unit) {
    val colors = gazeColors
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = SettingsRowInset, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(UriDisplay.readable(folder.uri))
                    if (folder.comicCount > 0) append(" · ${folder.comicCount} 本")
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = { confirm = true }) {
            Text("取消授权", style = MaterialTheme.typography.bodySmall, color = colors.accent)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("取消这个文件夹的授权？") },
            text = {
                Text(
                    "取消授权后 Gaze 不再自动扫描这个目录。\n" +
                        "已经导入的漫画不会被删除，只是会被标记为失效。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onRemove()
                }) { Text("取消授权", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("保留") }
            }
        )
    }
}

/**
 * 一条「待重新授权」的文件夹。
 *
 * 刻意不复用 [FolderRow]：那条上的按钮是「取消授权」，而这里的语义是
 * 「还没授权、要不要现在授权」—— 同一个位置放两个含义相反的按钮，
 * 用户点错一次就会把一条本来能救回来的目录记录删掉。
 */
@Composable
private fun PendingFolderRow(
    folder: FolderEntity,
    onAuthorize: () -> Unit,
    onIgnore: () -> Unit
) {
    val colors = gazeColors
    Row(
        Modifier.fillMaxWidth().padding(start = SettingsRowInset, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = UriDisplay.readable(folder.uri),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onIgnore) {
            Text("忽略", style = MaterialTheme.typography.bodySmall, color = colors.inkFaint)
        }
        TextButton(onClick = onAuthorize) {
            Text("重新授权", style = MaterialTheme.typography.bodySmall, color = colors.accent)
        }
    }
}

/**
 * 健康检查面板。
 *
 * 检查过程中只显示进度条 + 取消；跑完后才展开问题清单。
 * 清单只做展示，**不提供「一键删除」** —— 那会把「文件暂时不可读」
 * 直接变成「数据永久消失」，风险不对等。
 */
@Composable
private fun HealthPanel(
    report: HealthReport,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = gazeColors

    if (report.running) {
        Column(Modifier.fillMaxWidth().padding(start = SettingsRowInset, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "正在检查 ${report.checked} / ${report.total}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) { Text("取消") }
            }
            ThinProgress(
                progress = if (report.total > 0) report.checked.toFloat() / report.total else 0f
            )
        }
        return
    }

    if (!report.finished) return

    Column(Modifier.fillMaxWidth()) {
        if (report.issues.isEmpty()) {
            SettingsNote("${report.total} 本全部可以正常读取。")
        } else {
            SettingsNote("${report.total} 本中发现 ${report.issues.size} 本有问题：")
            report.issues.take(HEALTH_PREVIEW_LIMIT).forEach { issue ->
                Column(Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 6.dp)) {
                    Text(
                        text = issue.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${issue.fileName} · ${issue.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accent,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (report.issues.size > HEALTH_PREVIEW_LIMIT) {
                SettingsNote("还有 ${report.issues.size - HEALTH_PREVIEW_LIMIT} 本未列出。")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(end = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("收起") }
        }
    }
}

/** 问题清单最多展开这么多条，再多就只是一面墙。 */
private const val HEALTH_PREVIEW_LIMIT = 20

// ==================================================================== 文件名解析

/** 预设分隔符。值与显示名分开 —— 空格这种值直接显示会看不出来。 */
private val SEPARATOR_PRESETS: List<Pair<String, String>> = listOf(
    "_" to "下划线 _",
    "-" to "连字符 -",
    " " to "空格"
)

/**
 * 括号预设**只显示符号本身**。
 *
 * 之前写的是「粗方括号 【 】」这种「名字 + 符号」，一个标签要占大半格，
 * 等宽网格里必然被省略号截断。而名字其实是冗余的 —— 用户是照着符号认的，
 * 不是照着名字。半角与全角在等宽字体下也分得清。
 */
private val BRACKET_PRESETS: List<Pair<String, String>> = listOf(
    "()" to "( )",
    "[]" to "[ ]",
    "【】" to "【 】",
    "（）" to "（ ）"
)

@Composable
fun NamingSettingsScreen(
    settings: GazeSettings,
    onSetParseBySeparator: (Boolean) -> Unit,
    onSetSeparator: (String) -> Unit,
    onSetParseByBracket: (Boolean) -> Unit,
    onSetBracket: (String) -> Unit,
    onReparseFileNames: () -> Unit,
    onBack: () -> Unit
) {
    SettingsPage(title = "文件名解析", onBack = onBack) {
        SettingsSection("说明", icon = GazeIcons.Info) {
            SettingsNote(
                "漫画的文件命名非常混乱，很难建立通用的识别规则。下面两条规则只是" +
                    "开发者个人的命名习惯，不一定对得上你的文件 —— 建议以手动改书名、" +
                    "作者为主，自动解析只当作兜底。"
            )
        }

        SettingsSection("按分隔符识别", icon = GazeIcons.TextLines) {
            ToggleRow(
                label = "启用分隔符识别",
                checked = settings.parseAuthorBySeparator,
                onChange = onSetParseBySeparator,
                hint = "文件名中最后一个分隔符右侧的内容识别为作者。"
            )
            if (settings.parseAuthorBySeparator) {
                Hairline(Modifier.padding(start = SettingsRowInset))
                PatternChoiceRow(
                    label = "分隔符",
                    presets = SEPARATOR_PRESETS,
                    value = settings.authorSeparator,
                    onSelect = onSetSeparator,
                    sanitize = FileNameParser::sanitizeSeparator,
                    dialogTitle = "自定义分隔符",
                    dialogHint = "输入 1 个字符，不能是空格。"
                )
            }
        }

        SettingsSection("按括号识别", icon = GazeIcons.Book) {
            ToggleRow(
                label = "启用括号识别",
                checked = settings.parseAuthorByBracket,
                onChange = onSetParseByBracket,
                hint = "文件名中第一对括号里的内容识别为作者。两条规则都启用时，以括号为准。"
            )
            if (settings.parseAuthorByBracket) {
                Hairline(Modifier.padding(start = SettingsRowInset))
                PatternChoiceRow(
                    label = "括号",
                    presets = BRACKET_PRESETS,
                    value = settings.authorBracket,
                    onSelect = onSetBracket,
                    sanitize = FileNameParser::sanitizeBracket,
                    dialogTitle = "自定义括号",
                    dialogHint = "输入一对括号，共 2 个字符，例如 〈〉。左右两个字符不能相同。"
                )
            }
        }

        SettingsSection("示例", icon = GazeIcons.TextLines) {
            SettingsNote(
                "作品名_作者.cbz\n" +
                    "　→ 书名「作品名」　作者「作者」\n\n" +
                    "[作者] 作品名.cbz\n" +
                    "　→ 书名「作品名」　作者「作者」"
            )
        }

        SettingsSection("生效", icon = Icons.Default.Check) {
            ActionRow(
                label = "重新解析全部文件名",
                subtitle = "手动改过的书名与作者不会被覆盖",
                onClick = onReparseFileNames
            )
        }
    }
}

/**
 * 规则取值：预设几项 + 一个「自定义」。
 *
 * 「自定义」刻意不把输入框常驻在页面上 —— 两条规则就是两个输入框，
 * 页面会被填满。改成药丸 + 弹窗：平时只占一行，需要时才展开。
 *
 * @param sanitize 校验并归一化自定义输入；返回 null 表示不合法。
 */
@Composable
private fun PatternChoiceRow(
    label: String,
    presets: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
    sanitize: (String) -> String?,
    dialogTitle: String,
    dialogHint: String
) {
    var editing by remember { mutableStateOf(false) }
    val isPreset = presets.any { it.first == value }

    Column(Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(10.dp))
        // 预设与「自定义」排进同一个等宽网格 —— 它们本来就是同级选项，
        // 让「自定义」单独浮在行尾会显得它不是一个选项。
        ChipGrid(
            items = presets.map { (key, text) ->
                ChipItem(label = text, selected = key == value) { onSelect(key) }
            } + ChipItem(
                label = if (isPreset) "自定义" else "自定义 $value",
                selected = !isPreset,
                onClick = { editing = true }
            )
        )
    }

    if (editing) {
        PatternInputDialog(
            title = dialogTitle,
            hint = dialogHint,
            initial = if (isPreset) "" else value,
            sanitize = sanitize,
            onDismiss = { editing = false },
            onConfirm = {
                onSelect(it)
                editing = false
            }
        )
    }
}

@Composable
private fun PatternInputDialog(
    title: String,
    hint: String,
    initial: String,
    sanitize: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val normalized = sanitize(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = gazeColors.inkFaint)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 2) text = it },
                    singleLine = true,
                    shape = RoundedCornerShape(4.dp),
                    placeholder = { Text("例如 $initial") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (text.isNotEmpty() && normalized == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这个输入不可用，请对照上面的说明改一下。",
                        style = MaterialTheme.typography.bodySmall,
                        color = gazeColors.accent
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { normalized?.let(onConfirm) },
                enabled = normalized != null
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================================================================== 数据备份

@Composable
fun BackupSettingsScreen(
    settings: GazeSettings,
    backups: List<BackupEntry>,
    missingReport: MissingReport?,
    pendingRestore: Pair<Uri, BackupInfo>?,
    lastRestore: BackupResult?,
    suggestedName: String,
    onSetBackupFolder: (Uri, String) -> Unit,
    onClearBackupFolder: () -> Unit,
    onSetAutoBackupInterval: (AutoBackupInterval) -> Unit,
    onSetAutoBackupKeep: (Int) -> Unit,
    onBackupNow: () -> Unit,
    onRefreshBackups: () -> Unit,
    onExport: (Uri, String) -> Unit,
    onPeekBackup: (Uri) -> Unit,
    onRestoreBackup: (BackupEntry) -> Unit,
    onVerifyBackup: (BackupEntry) -> Unit,
    onDismissMissingReport: () -> Unit,
    onRestoreLastExport: () -> Unit,
    onForgetLastExport: () -> Unit,
    onConfirmRestore: (RestoreMode) -> Unit,
    onCancelPendingRestore: () -> Unit,
    onDismissRestoreResult: () -> Unit,
    onOpenMissing: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var pendingExportName by remember { mutableStateOf(suggestedName) }

    // 进页面时刷新一次历史备份列表：用户可能在文件管理器里删过文件
    LaunchedEffect(settings.backupFolderUri) { onRefreshBackups() }

    val backupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.persistUriPermission(uri, isTree = true)
            onSetBackupFolder(uri, UriDisplay.displayName(uri.toString()))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME)
    ) { uri -> if (uri != null) onExport(uri, pendingExportName) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPeekBackup(uri) }

    SettingsPage(title = "备份与恢复", onBack = onBack) {
        SettingsSection("说明", icon = GazeIcons.Info) {
            SettingsNote(
                "备份只包含元数据 —— 阅读进度、收藏、标签与标签顺序、文件夹归属、" +
                    "书架封面、阅读统计、全部设置。不复制漫画原文件，否则几百 GB 的漫画库" +
                    "会让备份大到没法用。"
            )
            SettingsNote(
                "文件夹授权是唯一带不过去的东西：SAF 的读权限绑定在「你亲自点过一次目录选择器」" +
                    "这件事上，备份文件里存不下。恢复后需要你重新授权一次才能继续扫描。"
            )
        }

        // ---------------------------------------------------- 上次恢复的结果
        lastRestore?.let { result ->
            SettingsSection("上次恢复的结果", icon = GazeIcons.Info) {
                SettingsNote(result.message)
                if (result.pending > 0 || result.pendingFolders > 0) {
                    ActionRow(
                        label = "去媒体匹配",
                        subtitle = buildString {
                            append("把 ${result.pending} 本待重新定位的漫画接回本机文件")
                            if (result.pendingFolders > 0) {
                                append("；先重新授权 ${result.pendingFolders} 个文件夹")
                            }
                        },
                        onClick = onOpenMissing
                    )
                    Hairline(Modifier.padding(start = SettingsRowInset))
                }
                Row(
                    Modifier.fillMaxWidth().padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRestoreResult) { Text("知道了") }
                }
            }
        }

        // ---------------------------------------------------- 备份文件夹
        SettingsSection("备份文件夹", icon = GazeIcons.Folder) {
            val folderName = settings.backupFolderName
            if (folderName.isNullOrBlank()) {
                SettingsNote("指定一个文件夹之后，备份可以一键完成，也才能开启自动备份。")
                ActionRow(
                    label = "指定备份文件夹",
                    subtitle = "从系统文件管理器里选一个目录",
                    onClick = { backupFolderLauncher.launch(null) }
                )
            } else {
                StatRow("当前文件夹", folderName, stacked = true)
                Hairline(Modifier.padding(start = SettingsRowInset))
                ActionRow(
                    label = "导出备份到这里",
                    subtitle = "不必再过一遍系统文件选择器",
                    onClick = onBackupNow
                )
                Hairline(Modifier.padding(start = SettingsRowInset))
                ActionRow(
                    label = "更换备份文件夹",
                    onClick = { backupFolderLauncher.launch(null) }
                )
                Hairline(Modifier.padding(start = SettingsRowInset))
                ActionRow(
                    label = "取消指定",
                    subtitle = "已导出的备份文件不会被删除",
                    onClick = onClearBackupFolder
                )
            }
        }

        // ---------------------------------------------------- 自动备份
        if (!settings.backupFolderName.isNullOrBlank()) {
            SettingsSection("自动备份", icon = GazeIcons.History) {
                ChoiceRow(
                    label = "备份频率",
                    hint = "只在打开应用时检查是否到点，不做后台常驻 —— 省电，也不需要额外的后台权限。",
                    options = AutoBackupInterval.entries.map { it.key to it.label },
                    selected = settings.autoBackupInterval.key,
                    onSelect = { onSetAutoBackupInterval(AutoBackupInterval.of(it)) }
                )
                Hairline(Modifier.padding(start = SettingsRowInset))
                ChoiceRow(
                    label = "保留份数",
                    hint = "只清理自动备份（文件名以 gaze-auto- 开头），手动导出的备份一份都不动。",
                    options = KEEP_OPTIONS.map { it.toString() to "$it 份" },
                    selected = settings.autoBackupKeep.toString(),
                    onSelect = { onSetAutoBackupKeep(it.toIntOrNull() ?: 5) }
                )
                if (settings.lastAutoBackupTime > 0L) {
                    StatRow("上次自动备份", Formatters.dateTime(settings.lastAutoBackupTime))
                }
                Hairline(Modifier.padding(start = SettingsRowInset))
                ActionRow(
                    label = "立即备份一次",
                    subtitle = "走自动备份的命名与保留策略",
                    onClick = onBackupNow
                )
            }
        }

        // ---------------------------------------------------- 手动导出 / 导入
        SettingsSection("手动导出与导入", icon = GazeIcons.Archive) {
            ActionRow(
                label = "导出备份",
                subtitle = "用系统文件选择器指定保存位置",
                onClick = {
                    pendingExportName = suggestedName
                    exportLauncher.launch(suggestedName)
                }
            )
            Hairline(Modifier.padding(start = SettingsRowInset))
            ActionRow(
                label = "导入备份",
                subtitle = "从文件管理器选择备份文件。也可以直接在文件管理器里点开备份文件，" +
                    "在「打开方式」里选 Gaze。",
                onClick = { importLauncher.launch(arrayOf("*/*")) }
            )
            if (settings.lastExportTime > 0L) {
                Hairline(Modifier.padding(start = SettingsRowInset))
                ActionRow(
                    label = "恢复上次导出的备份",
                    subtitle = "${settings.lastExportName ?: "备份"} · ${Formatters.dateTime(settings.lastExportTime)}",
                    onClick = onRestoreLastExport
                )
                Hairline(Modifier.padding(start = SettingsRowInset))
                ActionRow(
                    label = "清除上次导出记录",
                    subtitle = "不会删除已导出的文件",
                    onClick = onForgetLastExport
                )
            }
        }

        // ---------------------------------------------------- 历史备份
        SettingsSection("历史备份", icon = GazeIcons.History) {
            if (settings.backupFolderName.isNullOrBlank()) {
                SettingsNote("指定备份文件夹后，这里会列出其中的全部备份。")
            } else if (backups.isEmpty()) {
                SettingsNote("这个文件夹里还没有 Gaze 备份。")
                ActionRow(label = "重新读取", onClick = onRefreshBackups)
            } else {
                SettingsNote("点「恢复」把某一份写回；点「核对」检查它里面的漫画本地还剩几本。")
                backups.forEach { entry ->
                    BackupEntryRow(
                        entry = entry,
                        onRestore = { onRestoreBackup(entry) },
                        onVerify = { onVerifyBackup(entry) }
                    )
                }
                ActionRow(label = "重新读取列表", onClick = onRefreshBackups)
            }
        }

        // ---------------------------------------------------- 文件匹配报告
        missingReport?.let { report ->
            SettingsSection("文件匹配", icon = GazeIcons.Info) {
                MissingReportPanel(report = report, onDismiss = onDismissMissingReport)
            }
        }
    }

    pendingRestore?.let { (_, info) ->
        RestoreDialog(
            info = info,
            onDismiss = onCancelPendingRestore,
            onConfirm = onConfirmRestore
        )
    }
}

/** 保留份数只给几档固定值 —— 让用户填数字只会制造「填了 0 会怎样」这类问题。 */
private val KEEP_OPTIONS = listOf(3, 5, 10, 20)

@Composable
private fun BackupEntryRow(
    entry: BackupEntry,
    onRestore: () -> Unit,
    onVerify: () -> Unit
) {
    val colors = gazeColors
    val isAuto = entry.name.startsWith("gaze-auto-")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = SettingsRowInset, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (isAuto) append("自动 · ")
                        append(Formatters.dateTime(entry.modified))
                        if (entry.size > 0L) append(" · ${Formatters.fileSize(entry.size, "")}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onVerify) {
                Text("核对", style = MaterialTheme.typography.bodySmall, color = colors.inkFaint)
            }
            TextButton(onClick = onRestore) {
                Text("恢复", style = MaterialTheme.typography.bodySmall, color = colors.accent)
            }
        }
        Hairline(Modifier.padding(start = SettingsRowInset))
    }
}

/**
 * 文件匹配报告。
 *
 * 「备份里有、本地没有」是换设备 / 换存储卡之后最常见的情况。
 * 只列清单，**不提供一键清理** —— 缺文件不等于用户想删记录。
 */
@Composable
private fun MissingReportPanel(report: MissingReport, onDismiss: () -> Unit) {
    val colors = gazeColors
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = report.backupName,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = SettingsRowInset, vertical = 4.dp)
        )
        if (report.allPresent) {
            SettingsNote("${report.total} 本全部能在本地找到，没有丢失。")
        } else {
            SettingsNote(
                "共 ${report.total} 本：${report.matched} 本匹配到本地，" +
                    "${report.missing.size} 本没找到。"
            )
            report.missing.take(MISSING_PREVIEW_LIMIT).forEach { item ->
                Column(Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 5.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.fileName} · ${Formatters.fileSize(item.size, "未知大小")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (report.missing.size > MISSING_PREVIEW_LIMIT) {
                SettingsNote("还有 ${report.missing.size - MISSING_PREVIEW_LIMIT} 本未列出。")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(end = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("收起") }
        }
    }
}

private const val MISSING_PREVIEW_LIMIT = 30

@Composable
private fun RestoreDialog(
    info: BackupInfo,
    onDismiss: () -> Unit,
    onConfirm: (RestoreMode) -> Unit
) {
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复备份") },
        text = {
            Column {
                Text(
                    text = "备份时间：${Formatters.dateTime(info.createdAt)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = buildString {
                        append("备份版本：${info.appVersion} · 含 ${info.comicCount} 本漫画、${info.tagCount} 个标签")
                        if (info.relationCount > 0) append("（${info.relationCount} 条标签关系）")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                RestoreMode.entries.forEach { candidate ->
                    val selected = candidate == mode
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp))
                            .border(
                                width = 1.dp,
                                color = if (selected) gazeColors.ink else gazeColors.hairline,
                                shape = RoundedCornerShape(3.dp)
                            )
                            .clickable { mode = candidate }
                            .padding(12.dp)
                    ) {
                        Text(
                            text = candidate.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) gazeColors.accent else gazeColors.ink
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = candidate.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = gazeColors.inkFaint
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode) }) { Text("开始恢复") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================================================================== 关于

@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    val colors = gazeColors
    SettingsPage(title = "关于 Gaze", onBack = onBack) {
        SettingsSection("简介", icon = GazeIcons.Info) {
            SettingsNote(
                "Gaze 是一款完全本地的漫画阅读器。不登录、不注册、不上传，" +
                    "安装包里没有任何网络权限。漫画库属于你的私人数据。"
            )
            StatRow("版本", BuildConfig.VERSION_NAME)
            StatRow("开发者", "萧雨")
            StatRow(
                label = "支持格式",
                value = "压缩包 CBZ · CBR · CBT · CB7 · ZIP · RAR · TAR · 7Z\n" +
                    "文档 PDF · EPUB",
                stacked = true
            )
        }

        SettingsSection("更新日志", icon = GazeIcons.TextLines) {
            CHANGELOG.forEach { release ->
                Column(Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = release.version,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (release.version == BuildConfig.VERSION_NAME) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(colors.accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.accent
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    release.items.forEach { item ->
                        Row(Modifier.padding(bottom = 3.dp)) {
                            Text(
                                text = "·",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkFaint,
                                modifier = Modifier.width(10.dp)
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkFaint
                            )
                        }
                    }
                }
                Hairline(Modifier.padding(start = SettingsRowInset))
            }
        }

        SettingsSection("隐私", icon = GazeIcons.Info) {
            SettingsNote(
                "Gaze 不申请 INTERNET 权限 —— 这不是「不使用网络」，而是装不了任何网络代码。" +
                    "所有解析、解码、统计都在本机完成。"
            )
        }
    }
}

private data class ReleaseNote(val version: String, val items: List<String>)

/**
 * 更新日志。
 *
 * 刻意写得**短**：每条压在一行以内，只说用户能感知到的结果，
 * 不写实现细节（「不再用 RGB_565」「采样率不低于显示尺寸」这种话
 * 对用户没有任何意义）。更早的版本进一步合并成一句，
 * 更新日志是给人扫的，不是给人读的。
 */
private val CHANGELOG = listOf(
    ReleaseNote(
        version = "1.7.1",
        items = listOf(
            "修复「先恢复备份、再授权扫描」时标签与阅读进度丢失 —— 扫描现在会把备份留下的记录直接接回文件，而不是另建一条新的",
            "扫描时自动合并此前产生的重复记录：历史数据会并回保留的那条，不留空壳",
            "媒体匹配改为按原文件夹分组，点进文件夹才看具体是哪几本",
            "手动重新定位时，如果指到的文件本来就在库里，改为合并历史数据而不是产生重复"
        )
    ),
    ReleaseNote(
        version = "1.7.0",
        items = listOf(
            "新增「媒体匹配」：恢复备份后找不到文件的漫画会列在这里，可以手动重新定位，也可以让 Gaze 自己扫一遍按内容接回",
            "恢复备份不再把备份里记录的文件夹当成已授权，改为「待重新授权」，重新指一次目录即可继续扫描",
            "修复恢复备份后漫画上的标签丢失",
            "备份补全了此前漏掉的字段：标签顺序、文件夹归属、容器格式、手动改过的标题与作者、书架封面、阅读统计",
            "修复标签页上下滑动时容易误点进标签详情"
        )
    ),
    ReleaseNote(
        version = "1.6.0",
        items = listOf(
            "标签支持长按拖动排序，顺序会被记住（长按不动仍是批量管理）",
            "修复书架文件夹里返回会直接退出应用",
            "修复从阅读页返回会掉回书架首页、而不是刚才那个文件夹",
            "修复新建标签时选的颜色被自动推荐色顶掉",
            "搜索框右侧新增说明：多个条件用空格分开"
        )
    ),
    ReleaseNote(
        version = "1.5.1",
        items = listOf(
            "修复备份文件无法从文件管理器「打开方式」导入",
            "标签页「收藏」移到顶部独立区域，没有标签时也不再消失",
            "设置二级页统一左边缘，选项改为等宽网格"
        )
    ),
    ReleaseNote(
        version = "1.5.0",
        items = listOf(
            "标签页改为两列卡片，一屏能看到约两倍的标签",
            "「收藏」同样以卡片并入标签网格",
            "文件名解析重做：分隔符与括号两类规则可分别启用",
            "备份文件支持从文件管理器「打开方式」直接导入",
            "「概览」「阅读统计」移到设置页「阅读」分组",
            "退出阅读页的转场不再割裂，状态栏也不再泛白"
        )
    ),
    ReleaseNote(
        version = "1.4.0",
        items = listOf(
            "新增 PDF 与 EPUB 支持，可直接加入书架阅读",
            "PDF 按屏幕像素重新绘制，放大到几倍都不糊",
            "PDF / EPUB 支持目录跳转，也可输入页码直达",
            "EPUB 文字章节跟随阅读器背景与配色",
            "设置页改为分组卡片，各二级页补齐卡片与折行选项",
            "长页面滚动条可拖动，并新增「返回顶部」",
            "预设主题色补到 8 套，新增「紫檀」",
            "书架视图「全部」换成独立图标"
        )
    ),
    ReleaseNote(
        version = "1.3.1",
        items = listOf(
            "修复点进标签页「收藏」会闪退",
            "设置页重做：一栏一卡，去掉重复图标与大片留白",
            "主题与配色改为色卡网格，预设色铺满整行",
            "阅读器背景移到「阅读行为」，阅读页菜单里也能直接改",
            "阅读页控件与状态栏配色跟随阅读器背景",
            "页面转场从 700ms 缩短到 180ms",
            "说明文字里多余的星号已清理"
        )
    ),
    ReleaseNote(
        version = "1.3.0",
        items = listOf(
            "按原图分辨率渲染：放大时只解可见区域，超长条漫分块只为省内存",
            "阅读页不再自动沉浸，点一下才全屏",
            "书架视图 / 排版 / 排序会记住，重启不再回到默认",
            "标签长按可批量管理；「收藏」独立成一页",
            "长列表加入细滚动条"
        )
    ),
    ReleaseNote(
        version = "1.2.0",
        items = listOf(
            "设置拆成二级页",
            "6 套纸张主题，并支持自定义主色",
            "阅读页新增旋转 / 亮度 / 渲染模式 / 音量键翻页 / 常亮 / 页码",
            "书架视图与排版扩展，支持自定义书架封面",
            "备份支持指定文件夹、自动备份与恢复"
        )
    ),
    ReleaseNote(
        version = "1.1.0",
        items = listOf(
            "新增阅读统计",
            "坏页可重试或跳过；扩展元数据；文件名解析可配置"
        )
    ),
    ReleaseNote(
        version = "1.0.1",
        items = listOf("修复封面蒙纱、顶栏重叠、扫描提示反复弹出等首批问题")
    ),
    ReleaseNote(
        version = "1.0.0",
        items = listOf("首个版本：完全本地的漫画阅读器")
    )
)
