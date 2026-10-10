package com.gaze.reader.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gaze.reader.core.Formatters
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.domain.UriDisplay
import com.gaze.reader.scan.persistUriPermission
import com.gaze.reader.ui.components.GazeIcons
import com.gaze.reader.ui.components.Hairline
import com.gaze.reader.ui.theme.gazeColors
import kotlin.math.roundToInt

/**
 * 媒体匹配页（文档第十五章的下游）。
 *
 * 恢复备份之后，备份里有、但本机找不到对应文件的那些漫画全都落在这里。
 * 页面要回答用户三个问题：
 *
 * 1. **我的数据还在吗？** —— 在。每条都写着「已读 62% · 已收藏 · 日常 短篇」，
 *    这些是 Gaze 自己记的，跟文件在不在没关系。
 * 2. **丢的是哪一块？** —— 按原文件夹分组。上百本平铺一列没法看，
 *    但「哪个目录整块掉了」一眼就能数清楚。
 * 3. **怎么把它们接回来？** —— 点进那一组，手动指文件或者让它自己扫一遍。
 */
@Composable
fun MissingScreen(
    viewModel: MissingViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    /**
     * 当前打开的文件夹分组。
     *
     * 用 `rememberSaveable` 存 key 而不是存整个对象：分组数据会随数据库变化
     * 重新算出来，存对象会立刻过期；存 key 则每次重组都从最新数据里取。
     */
    var openedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 组被清空（全接回来了）时 opened 自然变 null，界面自动退回分组列表，
    // 不必再写一个「清理失效 key」的副作用。
    val opened = state.group(openedKey)

    /** 正在等用户挑文件的那一条 */
    var relocating by remember { mutableStateOf<ComicEntity?>(null) }
    var removingAll by remember { mutableStateOf(false) }
    var removingGroup by remember { mutableStateOf<MissingGroup?>(null) }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val target = relocating
        relocating = null
        // content URI 的读权限只在本次结果里有效，拿到就立刻处理，不能存下来等以后再用
        if (uri != null && target != null) {
            context.persistUriPermission(uri, isTree = false)
            viewModel.relocate(target.id, uri)
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.persistUriPermission(uri, isTree = true)
            viewModel.addFolder(uri)
        }
    }

    // 用户点了「重新定位」之后，文件选择器由这里弹出来。
    // mimeTypes 传 */* ：不少 ROM 对 .cbz / .cbr 反查出来的类型是
    // application/octet-stream，只过滤具体类型会把它们全挡在外面。
    LaunchedEffect(relocating) {
        if (relocating != null) pickFile.launch(arrayOf("*/*"))
    }

    // 系统返回：在某一组里就先退到分组列表，而不是直接离开整页
    BackHandler(enabled = opened != null) { openedKey = null }

    val goBack: () -> Unit = { if (opened != null) openedKey = null else onBack() }

    SettingsPage(
        title = if (opened != null) opened.title else "媒体匹配",
        subtitle = when {
            opened != null -> "${opened.count} 本等待重新定位"
            state.total == 0 -> "没有待定位的漫画"
            else -> "${state.total} 本 · ${state.groups.size} 个文件夹"
        },
        onBack = goBack
    ) {
        ScanBanner(scan = scan, onCancel = viewModel::cancelScan)
        MessageBar(message = message, onConsume = viewModel::consumeMessage)

        if (state.groups.isEmpty()) {
            SettingsSection("状态", icon = GazeIcons.Info) {
                SettingsNote("所有漫画都能在本机找到对应文件。")
                SettingsNote(
                    "恢复备份之后，如果某些漫画找不到文件，它们会出现在这里 —— " +
                        "阅读进度、收藏和标签都完整保留，只是暂时没指到文件。"
                )
            }
        } else if (opened != null) {
            GroupDetail(
                group = opened,
                onRelocate = { relocating = it },
                onRemove = { viewModel.remove(listOf(it.id)) },
                onRemoveAll = { removingGroup = opened },
                onAuthorize = { pickFolder.launch(null) }
            )
        } else {
            GroupList(
                state = state,
                onOpen = { openedKey = it.key },
                onScan = viewModel::scanAndMatch,
                onRemoveAll = { removingAll = true },
                onAuthorize = { pickFolder.launch(null) }
            )
        }
    }

    if (removingAll) {
        AlertDialog(
            onDismissRequest = { removingAll = false },
            title = { Text("移除全部 ${state.total} 条记录？") },
            text = {
                Text(
                    "会清掉这些漫画的阅读进度、收藏和标签关系。手机上的文件不会被删。\n\n" +
                        "不可撤销 —— 还想再找找文件的话，先试「扫描并自动匹配」。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(state.groups.flatMap { group -> group.items.map { it.comic.id } })
                    removingAll = false
                }) { Text("移除", color = gazeColors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { removingAll = false }) { Text("取消") }
            }
        )
    }

    removingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { removingGroup = null },
            title = { Text("移除「${group.title}」的 ${group.count} 条记录？") },
            text = {
                Text(
                    "会清掉这一组漫画的阅读进度、收藏和标签关系。手机上的文件不会被删。\n\n" +
                        "不可撤销 —— 还想再找找文件的话，先试「扫描并自动匹配」。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(group.items.map { it.comic.id })
                    removingGroup = null
                }) { Text("移除", color = gazeColors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { removingGroup = null }) { Text("取消") }
            }
        )
    }
}

// ==================================================================== 第一层：分组列表

@Composable
private fun GroupList(
    state: MissingUiState,
    onOpen: (MissingGroup) -> Unit,
    onScan: () -> Unit,
    onRemoveAll: () -> Unit,
    onAuthorize: () -> Unit
) {
    SettingsSection("这一页在做什么", icon = GazeIcons.Info) {
        SettingsNote(
            "这些漫画的记录都还在 —— 阅读进度、收藏、标签一样没少，" +
                "只是指向的文件在本机找不到了。\n\n" +
                "「重新定位」由你手动指文件；「扫描并自动匹配」让 Gaze 自己按内容找。" +
                "两者都是把历史数据接回原记录，不会新建空壳。"
        )
    }

    if (state.orphanFolders.isNotEmpty()) {
        SettingsSection("需要重新授权", icon = GazeIcons.Folder) {
            SettingsNote(
                "备份带回来的文件夹没法自动恢复读权限 —— 系统只认「你亲自点过一次目录选择器」。\n" +
                    "重新授权一次，它们名下的漫画才可能被扫描接回来。"
            )
            state.orphanFolders.forEach { folder ->
                StatRow(folder.displayName, UriDisplay.readable(folder.uri), stacked = true)
                Hairline(Modifier.padding(start = SettingsRowInset))
            }
            AuthorizeRow(label = "重新授权文件夹", onAuthorize = onAuthorize)
        }
    }

    SettingsSection("批量操作", icon = Icons.Default.Refresh) {
        ActionRow(
            label = "扫描并自动匹配",
            subtitle = "按文件名、大小、内容采样重新比对，能对上的会自动接回原记录",
            onClick = onScan
        )
        Hairline(Modifier.padding(start = SettingsRowInset))
        ActionRow(
            label = "全部移除",
            subtitle = "${state.total} 条记录 —— 只清 Gaze 里的数据，手机上的文件不动",
            destructive = true,
            onClick = onRemoveAll
        )
    }

    SettingsSection("待重新定位（${state.total} 本）", icon = Icons.Default.Warning) {
        SettingsNote("按原文件夹分组 —— 点进一组能看到具体是哪些漫画，并逐条重新定位。")
        state.groups.forEach { group ->
            GroupRow(group = group, onClick = { onOpen(group) })
            Hairline(Modifier.padding(start = SettingsRowInset))
        }
    }
}

/**
 * 一个文件夹分组。
 *
 * 副标题里塞的是「为什么这组值得管」：还剩多少本有历史数据、
 * 以及这个目录本身还没重新授权（那才是能一次接回一批的前提）。
 */
@Composable
private fun GroupRow(group: MissingGroup, onClick: () -> Unit) {
    val colors = gazeColors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SettingsRowInset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = groupSummary(group),
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${group.count} 本",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.inkFaint,
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun groupSummary(group: MissingGroup): String {
    val parts = buildList {
        if (group.pending) add("还没重新授权")
        if (group.withHistory > 0) add("${group.withHistory} 本还留着进度或标签")
        group.uri?.let { add("原位置：${UriDisplay.readable(it)}") }
    }
    return parts.joinToString("  ·  ")
}

// ==================================================================== 第二层：组内明细

@Composable
private fun GroupDetail(
    group: MissingGroup,
    onRelocate: (ComicEntity) -> Unit,
    onRemove: (ComicEntity) -> Unit,
    onRemoveAll: () -> Unit,
    onAuthorize: () -> Unit
) {
    SettingsSection(group.title, icon = GazeIcons.Folder) {
        SettingsNote(
            "这 ${group.count} 本都来自同一个目录。记录都在 —— 阅读进度、收藏、标签一样没少，" +
                "只是文件在本机找不到了。"
        )
        group.uri?.let { uri ->
            StatRow("原位置", UriDisplay.readable(uri))
            Hairline(Modifier.padding(start = SettingsRowInset))
        }
        if (group.pending) {
            SettingsNote(
                "这个目录还没重新授权。授权之后点「扫描并自动匹配」，这一组通常能一次接回大半。"
            )
            AuthorizeRow(label = "重新授权这个目录", onAuthorize = onAuthorize)
        }
        ActionRow(
            label = "移除这一组",
            subtitle = "${group.count} 条记录 —— 只清 Gaze 里的数据，手机上的文件不动",
            destructive = true,
            onClick = onRemoveAll
        )
    }

    SettingsSection("具体是哪几本", icon = Icons.Default.Warning) {
        group.items.forEach { item ->
            MissingRow(
                item = item,
                onRelocate = { onRelocate(item.comic) },
                onRemove = { onRemove(item.comic) }
            )
            Hairline(Modifier.padding(start = SettingsRowInset))
        }
    }
}

@Composable
private fun AuthorizeRow(label: String, onAuthorize: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = SettingsRowInset, end = 8.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onAuthorize) { Text("选择") }
    }
}

// ==================================================================== 零件

/**
 * 一条待匹配的漫画。
 *
 * 刻意**不显示封面**：文件都找不到了，封面缓存自然也没有，
 * 硬塞一个占位图只会让人以为是加载失败。用警示图标，语义更直白。
 *
 * 也不再单独印「原位置」—— 它已经被所在的分组表达过了，逐条重复只是噪音。
 */
@Composable
private fun MissingRow(
    item: MissingItem,
    onRelocate: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = gazeColors
    val comic = item.comic

    Column(Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = comic.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "未找到",
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent
            )
        }

        Spacer(Modifier.height(3.dp))
        Text(
            text = listOfNotNull(
                comic.fileName.takeIf { it.isNotBlank() },
                Formatters.fileSize(comic.size, "").takeIf { it.isNotBlank() },
                comic.format.takeIf { it.isNotBlank() && it != "?" }
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 「历史数据还在」的证据。用户看到「已读 62%」才明白重新定位是有意义的 ——
        // 否则他只会想「一个找不到的文件，删了算了」。
        historyLine(item)?.let { line ->
            Spacer(Modifier.height(3.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onRemove) {
                Text(
                    text = "移除记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint
                )
            }
            TextButton(onClick = onRelocate) { Text("重新定位") }
        }
    }
}

/** 这条记录身上还留着什么。什么都没有就如实说「没有阅读记录」，别粉饰。 */
private fun historyLine(item: MissingItem): String? {
    val comic = item.comic
    val parts = buildList {
        if (comic.progress > 0f) add("已读 ${(comic.progress * 100).roundToInt()}%")
        if (comic.favorite) add("已收藏")
        if (item.tagNames.isNotEmpty()) add(item.tagNames.joinToString(" "))
    }
    return parts.joinToString("  ·  ").takeIf { it.isNotBlank() }
}
