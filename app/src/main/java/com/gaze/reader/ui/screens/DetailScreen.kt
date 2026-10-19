package com.gaze.reader.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gaze.reader.core.Formatters
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.TagEntity
import com.gaze.reader.data.db.TagWithCount
import com.gaze.reader.domain.UriDisplay
import com.gaze.reader.ui.components.BackToTopButton
import com.gaze.reader.ui.components.ComicCover
import com.gaze.reader.ui.components.GazeIcons
import com.gaze.reader.ui.components.Hairline
import com.gaze.reader.ui.components.ScrollStateScrollbar
import com.gaze.reader.ui.components.SectionLabel
import com.gaze.reader.ui.components.ThinProgress
import com.gaze.reader.ui.components.scrolledBeyond
import com.gaze.reader.ui.theme.gazeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 移除后的撤销窗口。 */
private const val REMOVE_UNDO_MS = 5_000L

/**
 * 详情页换封面的入口集合。
 *
 * 打包成一个对象而不是六七个散着的回调：它们本来就是**同一件事的六个动作**，
 * 拆开传会让 DetailScreen 的参数表长到读不下去。
 */
class CoverActions(
    /** 打开选页面板。后台开归档，开好了由 [coverPickerOpen] 立起面板。 */
    val open: () -> Unit,
    val close: () -> Unit,
    val thumb: suspend (Int) -> Bitmap?,
    val pickPage: (Int) -> Unit,
    val pickImage: (Uri) -> Unit,
    val regenerate: () -> Unit
)

@Composable
fun DetailScreen(
    comic: ComicEntity?,
    tags: List<TagEntity>,
    allTags: List<TagWithCount>,
    message: String?,
    removed: Boolean,
    cover: CoverActions,
    /** 选页面板是否可见。状态在 ViewModel 里 —— 打开它要先把归档读出来。 */
    coverPickerOpen: Boolean,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSetTags: (Collection<Long>) -> Unit,
    onCreateTag: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onSetMetadata: (String?, Int?, String?) -> Unit,
    onRemove: () -> Unit,
    onUndoRemove: () -> Unit,
    onConsumeMessage: () -> Unit
) {
    val colors = gazeColors
    var showTagEditor by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showMetadata by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    // 关掉选页面板时一定要把会话放掉：它占着归档通道，不关会一直握着文件。
    val closeCoverEditor = { cover.close() }

    // 「从本机图片选择」。OpenDocument 而不是 GetContent —— 前者给出的
    // 是文档 uri，能直接 openInputStream，也不必担心临时授权过期。
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            cover.pickImage(uri)
            closeCoverEditor()
        }
    }

    // 移除后给 5 秒反悔窗口，时间到自动退回上一页。
    // 撤销时 removed 变回 false，这个 effect 重启、倒计时自动作废。
    LaunchedEffect(removed) {
        if (removed) {
            delay(REMOVE_UNDO_MS)
            onBack()
        }
    }

    if (comic == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("找不到这本漫画", color = colors.inkFaint)
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.ink)
            }
            Text(
                text = "详情",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (comic.favorite) GazeIcons.StarFilled else GazeIcons.StarOutline,
                    contentDescription = "收藏",
                    tint = if (comic.favorite) colors.accent else colors.inkFaint
                )
            }
        }
        Hairline()

        message?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onConsumeMessage) { Text("知道了") }
            }
        }

        val detailScroll = rememberScrollState()
        Box(Modifier.weight(1f)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(detailScroll)
        ) {
            Row(Modifier.fillMaxWidth().padding(20.dp)) {
                ComicCover(
                    coverPath = comic.coverPath,
                    title = comic.title,
                    modifier = Modifier.width(112.dp).height(156.dp)
                )
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = comic.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    if (!comic.author.isNullOrBlank()) {
                        Text(
                            text = comic.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DetailStat("格式", comic.format)
                    DetailStat("页数", "${comic.pageCount} 页")
                    DetailStat("大小", Formatters.fileSize(comic.size, "未知"))
                    DetailStat("修改时间", Formatters.date(comic.modifiedTime))
                    // 扩展元数据只在填过时才占位置，没填就不显示空行
                    comic.series?.let { series ->
                        DetailStat(
                            "系列",
                            comic.volume?.let { "$series · 第 $it 卷" } ?: series
                        )
                    }
                }
            }

            if (comic.progress > 0f) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("阅读进度")
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "第 ${comic.currentPage + 1} / ${comic.pageCount} 页",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkFaint
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ThinProgress(progress = comic.progress)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "上次阅读：${Formatters.relative(comic.lastReadTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkFaint
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                PrimaryAction(
                    label = if (comic.progress > 0f) "继续阅读" else "开始阅读",
                    onClick = onRead,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("标签")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (tags.isEmpty()) {
                    Text(
                        text = "还没有标签",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkFaint,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tags.take(4).forEach { tag ->
                            TagPill(name = tag.name, color = Color(tag.color))
                        }
                    }
                }
                TextButton(onClick = { showTagEditor = true }) { Text("编辑") }
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("文件")
            DetailAction("编辑书名与作者", "手动修改后，重新扫描不会覆盖") { showRename = true }
            Hairline(Modifier.padding(start = 20.dp))
            DetailAction("编辑详细信息", "系列、卷号与备注") { showMetadata = true }
            Hairline(Modifier.padding(start = 20.dp))
            DetailAction("更换封面", "从漫画里选一页，或用本地图片", onClick = cover.open)
            Hairline(Modifier.padding(start = 20.dp))
            DetailAction("从书架移除", "不会删除原文件", onRemove = { confirmRemove = true })
            Hairline(Modifier.padding(start = 20.dp))

            comic.note?.let { note ->
                Spacer(Modifier.height(14.dp))
                SectionHeader("备注")
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionHeader("位置")
            Text(
                text = UriDisplay.readable(comic.uri),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "原始文件名：${comic.fileName}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(48.dp))
        }
            ScrollStateScrollbar(
                state = detailScroll,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
            )
            BackToTopButton(
                state = detailScroll,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
            )
        }
    }

    // 移除后的撤销条：贴在底部，5 秒内可以反悔
    if (removed) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, colors.hairline, RoundedCornerShape(3.dp))
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(2.dp).height(14.dp).background(colors.accent))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "已从书架移除（原文件未删除）",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUndoRemove) {
                Text("撤销", style = MaterialTheme.typography.bodySmall, color = colors.accent)
            }
        }
    }
    }

    if (showTagEditor) {
        TagPickerDialog(
            allTags = allTags,
            selected = tags.map { it.id }.toSet(),
            onDismiss = { showTagEditor = false },
            onConfirm = { ids ->
                onSetTags(ids)
                showTagEditor = false
            },
            onCreate = { name -> onCreateTag(name) }
        )
    }

    if (showRename) {
        RenameDialog(
            initialTitle = comic.title,
            initialAuthor = comic.author.orEmpty(),
            onDismiss = { showRename = false },
            onConfirm = { title, author ->
                onRename(title, author)
                showRename = false
            }
        )
    }

    if (showMetadata) {
        MetadataDialog(
            initialSeries = comic.series.orEmpty(),
            initialVolume = comic.volume?.toString().orEmpty(),
            initialNote = comic.note.orEmpty(),
            onDismiss = { showMetadata = false },
            onConfirm = { series, volume, note ->
                onSetMetadata(series, volume, note)
                showMetadata = false
            }
        )
    }

    if (coverPickerOpen) {
        CoverEditorDialog(
            pageCount = comic.pageCount,
            thumb = cover.thumb,
            onPickPage = { page ->
                cover.pickPage(page)
                closeCoverEditor()
            },
            onPickImage = { imagePicker.launch(arrayOf("image/*")) },
            onRegenerate = {
                cover.regenerate()
                closeCoverEditor()
            },
            onDismiss = { closeCoverEditor() }
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("从书架移除？") },
            text = {
                Text(
                    "只会移除 Gaze 里的记录（阅读进度与标签也会一并清除）。\n" +
                        "原始漫画文件不会被删除。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text("移除", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } }
        )
    }
}

// ==================================================================== 组件

/**
 * 换封面面板。
 *
 * 三条路摆在一起：从漫画里挑一页、从本机挑一张图、退回自动生成。
 * 选页放在主位（缩略图网格），因为「这页画得好看」是最常见的换封面理由；
 * 另外两条做成顶部的两个按钮。
 *
 * 用自绘 Dialog 而不是 `ModalBottomSheet`：后者容器色取自 Material 主题，
 * 和阅读页那边遇到过的是同一个坑 —— 主题与页面不同色时会弹出一张突兀的板。
 */
@Composable
private fun CoverEditorDialog(
    pageCount: Int,
    thumb: suspend (Int) -> Bitmap?,
    onPickPage: (Int) -> Unit,
    onPickImage: () -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = gazeColors

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(top = 14.dp, bottom = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "更换封面",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlineAction("从本机图片选择", onPickImage, Modifier.weight(1f))
                OutlineAction("恢复自动生成", onRegenerate, Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = if (pageCount > 0) "或者从这本漫画里选一页" else "这本书没有可选的页面",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            Spacer(Modifier.height(8.dp))

            if (pageCount > 0) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                ) {
                    items(pageCount) { page ->
                        PageThumbCell(
                            page = page,
                            thumb = thumb,
                            onClick = { onPickPage(page) }
                        )
                    }
                }
            }
        }
    }
}

/** 选页面板里的一格。缩略图按需解，滚出视野就回收。 */
@Composable
private fun PageThumbCell(
    page: Int,
    thumb: suspend (Int) -> Bitmap?,
    onClick: () -> Unit
) {
    val colors = gazeColors
    val bitmap by produceState<Bitmap?>(null, page) { value = thumb(page) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, colors.hairline, RoundedCornerShape(2.dp))
                .clickable(onClick = onClick)
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "第 ${page + 1} 页",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = "${page + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkFaint
        )
    }
}

@Composable
private fun OutlineAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = gazeColors
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SectionLabel(text)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(gazeColors.hairline))
    }
}

@Composable
private fun DetailStat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = gazeColors.inkFaint,
            modifier = Modifier.width(62.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = gazeColors
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(colors.ink)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.background
        )
    }
}

@Composable
private fun DetailAction(label: String, subtitle: String?, onRemove: () -> Unit = {}, onClick: (() -> Unit)? = null) {
    val colors = gazeColors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick?.invoke() ?: onRemove() }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick == null) colors.accent else MaterialTheme.colorScheme.onBackground
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.inkFaint)
        }
    }
}

@Composable
private fun TagPill(name: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .border(1.dp, gazeColors.hairline, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.bodySmall, color = gazeColors.ink)
    }
}

@Composable
private fun TagPickerDialog(
    allTags: List<TagWithCount>,
    selected: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
    onCreate: (String) -> Unit
) {
    var current by remember { mutableStateOf(selected) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标签") },
        text = {
            Column {
                if (allTags.isEmpty()) {
                    Text(
                        "还没有标签。在下面输入一个名字就能新建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = gazeColors.inkFaint
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        allTags.forEach { tag ->
                            val checked = tag.id in current
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        current = if (checked) current - tag.id else current + tag.id
                                    }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(tag.color))
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f)
                                )
                                if (checked) {
                                    Text(
                                        "✓",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = gazeColors.accent
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("新建标签") },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row {
                if (newName.isNotBlank()) {
                    TextButton(onClick = {
                        onCreate(newName.trim())
                        newName = ""
                    }) { Text("新建") }
                }
                TextButton(onClick = { onConfirm(current) }) { Text("保存") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RenameDialog(
    initialTitle: String,
    initialAuthor: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var author by remember { mutableStateOf(initialAuthor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑书名与作者") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("书名") },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    singleLine = true,
                    label = { Text("作者") },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "手动填写后，重新扫描不会覆盖这两个字段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = gazeColors.inkFaint
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, author) },
                enabled = title.trim().isNotEmpty()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 扩展元数据编辑。
 *
 * 这三个字段**没有自动来源**，所以不需要 manual 标志位 ——
 * 扫描回写永远不碰它们，用户填了就是用户的。
 *
 * 评分已经去掉：产品上不做评分，留一个没人用的星级只会让人以为漏了什么。
 */
@Composable
private fun MetadataDialog(
    initialSeries: String,
    initialVolume: String,
    initialNote: String,
    onDismiss: () -> Unit,
    onConfirm: (String?, Int?, String?) -> Unit
) {
    val colors = gazeColors
    var series by remember { mutableStateOf(initialSeries) }
    var volume by remember { mutableStateOf(initialVolume) }
    var note by remember { mutableStateOf(initialNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑详细信息") },
        text = {
            Column {
                OutlinedTextField(
                    value = series,
                    onValueChange = { series = it },
                    singleLine = true,
                    label = { Text("系列") },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = volume,
                    // 卷号只收数字，顺手挡掉「第三卷」这类输入造成的解析失败
                    onValueChange = { input -> volume = input.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    label = { Text("卷号") },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "这些字段不会被扫描覆盖，也不影响文件名解析。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    series.trim().ifBlank { null },
                    volume.toIntOrNull(),
                    note.trim().ifBlank { null }
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
