package com.gaze.reader.ui.screens

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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gaze.reader.core.Formatters
import com.gaze.reader.core.SortOrder
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.TagEntity
import com.gaze.reader.data.db.TagWithCount
import com.gaze.reader.domain.TagPalette
import com.gaze.reader.ui.components.BackToTopButton
import com.gaze.reader.ui.components.ComicCover
import com.gaze.reader.ui.components.EmptyState
import com.gaze.reader.ui.components.GazeIcons
import com.gaze.reader.ui.components.Hairline
import com.gaze.reader.ui.components.LazyGridScrollbar
import com.gaze.reader.ui.components.ScreenTitle
import com.gaze.reader.ui.components.SectionLabel
import com.gaze.reader.ui.components.TagColorPicker
import com.gaze.reader.ui.components.ThinProgress
import com.gaze.reader.ui.components.combinedClickableCompat
import com.gaze.reader.ui.theme.gazeColors

/**
 * 标签页。
 *
 * 两个刻意的设计：
 * 1. **没有溢出菜单**。之前右上角那个「⋮」点下去是设置页 —— 它和底部导航的
 *    「设置」是同一个目的地，重复入口只会让人怀疑自己点错了。设置只留一处。
 * 2. **长按标签进入批量管理**。和书架长按漫画是同一套操作直觉：长按 = 选中。
 */
@Composable
fun TagsScreen(
    tags: List<TagWithCount>,
    favoriteCount: Int,
    selection: Set<Long>,
    onOpenFavorites: () -> Unit,
    onOpenTag: (Long) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onBatchDelete: () -> Unit,
    onBatchColor: (Int) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onSetColor: (Long, Int) -> Unit,
    onDelete: (Long, String) -> Unit
) {
    var editorTarget by remember { mutableStateOf<TagWithCount?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showBatchColor by remember { mutableStateOf(false) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    val selectionMode = selection.isNotEmpty()
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            title = "标签",
            subtitle = if (tags.isEmpty()) null else "${tags.size} 个"
        )

        // 「收藏」单独占一块区域，**不跟标签混排**。
        //
        // 之前它被当成「第一个标签」放进同一个网格里，两个问题：一是语义不对
        // （收藏不是一个标签，是一组跨标签的漫画），二是没有标签时整个网格
        // 会被空状态顶掉，收藏入口跟着一起消失 —— 而收藏恰恰是新用户最可能
        // 已经有内容的地方。现在它有自己的位置，永远在。
        FavoriteEntryCard(
            count = favoriteCount,
            enabled = !selectionMode,
            onClick = onOpenFavorites,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("全部标签")
            Spacer(Modifier.weight(1f))
            if (!selectionMode) {
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = "新建标签", tint = gazeColors.ink)
                }
            }
        }

        Hairline()

        Box(Modifier.weight(1f)) {
            if (tags.isEmpty()) {
                EmptyState(
                    title = "还没有标签",
                    message = "标签完全由你自己创建。\n可以给漫画分类，比如「日常」「短篇」「已完结」。",
                    primaryLabel = "新建标签",
                    onPrimary = { showCreate = true }
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = 20.dp, end = 20.dp, top = 14.dp, bottom = 96.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(tags, key = { "tag-${it.id}" }) { tag ->
                        TagCard(
                            accent = Color(tag.color),
                            name = tag.name,
                            countLabel = "${tag.comicCount} 本",
                            selectionMode = selectionMode,
                            selected = tag.id in selection,
                            onClick = {
                                if (selectionMode) onToggleSelect(tag.id) else onOpenTag(tag.id)
                            },
                            onLongClick = { onToggleSelect(tag.id) },
                            onEdit = { editorTarget = tag }
                        )
                    }
                }
                LazyGridScrollbar(
                    state = gridState,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
                )
                BackToTopButton(
                    state = gridState,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
                )
            }
        }

        if (selectionMode) {
            TagBatchBar(
                count = selection.size,
                onClear = onClearSelection,
                onSelectAll = onSelectAll,
                onColor = { showBatchColor = true },
                onDelete = { confirmBatchDelete = true }
            )
        }
    }

    if (showCreate) {
        TagEditDialog(
            title = "新建标签",
            initialName = "",
            initialColor = TagPalette.COLORS[tags.size % TagPalette.COLORS.size],
            onDismiss = { showCreate = false },
            onConfirm = { name, _ ->
                onCreate(name)
                showCreate = false
            }
        )
    }

    editorTarget?.let { target ->
        TagEditDialog(
            title = "编辑标签",
            initialName = target.name,
            initialColor = target.color,
            onDismiss = { editorTarget = null },
            onConfirm = { name, color ->
                if (name != target.name) onRename(target.id, name)
                if (color != target.color) onSetColor(target.id, color)
                editorTarget = null
            },
            onDelete = {
                onDelete(target.id, target.name)
                editorTarget = null
            }
        )
    }

    if (showBatchColor) {
        BatchColorDialog(
            onDismiss = { showBatchColor = false },
            onPick = {
                onBatchColor(it)
                showBatchColor = false
            }
        )
    }

    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("删除这 ${selection.size} 个标签？") },
            text = {
                Text(
                    "标签本身会被删除，漫画与原文件都不受影响 ——" +
                        "只是这些漫画上不再有这几个标签。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBatchDelete = false
                    onBatchDelete()
                }) { Text("删除", color = gazeColors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) { Text("取消") }
            }
        )
    }
}

/** 卡片高度固定：两列网格只要有一格高一点，整面墙的节奏就乱了。 */
private val TAG_CARD_HEIGHT = 68.dp

/**
 * 标签页顶部的「收藏」入口。
 *
 * 与标签卡片共用同一套语言（`surfaceVariant` 底 + 细边 + 6dp 圆角），
 * 但它独占一行：收藏不是一个标签，是一组跨标签、跨文件夹的漫画 ——
 * 混进标签网格会让人以为「收藏」也是可以编辑/删除的标签之一。
 *
 * 批量管理标签时置灰并停止响应：那时点它会带着选择状态跳走，
 * 比按钮不可用更让人费解。
 */
@Composable
private fun FavoriteEntryCard(
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = gazeColors
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, colors.hairline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = GazeIcons.StarFilled,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "收藏",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count 本",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkFaint
        )
        Spacer(Modifier.width(8.dp))
        Text(text = "›", style = MaterialTheme.typography.bodyLarge, color = colors.inkFaint)
    }
}

/**
 * 标签卡片。
 *
 * 从「一行一个」改成「两列网格」的理由很直接：标签名通常只有两三个字，
 * 一条 360dp 宽的整行只放两三个字，右侧全空着 —— 用户说的「占地面积很大、
 * 有所浪费」就是这个。两列之后同样的信息只占一半面积，一屏能看全的标签翻倍。
 *
 * 「收藏」复用同一个组件（只是没有编辑按钮），于是它和标签卡片的边界、
 * 圆角、配色完全一致，放在网格里毫无突兀感。
 *
 * @param onEdit 传 null 表示这张卡片不可编辑（收藏卡片）。
 */
@Composable
private fun TagCard(
    accent: Color,
    name: String,
    countLabel: String,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    val colors = gazeColors
    val shape = RoundedCornerShape(6.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .height(TAG_CARD_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.accent else colors.hairline,
                shape = shape
            )
            .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = countLabel,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint
            )
            Spacer(Modifier.weight(1f))
            if (onEdit != null && !selectionMode) {
                // 触摸区 26dp 与视觉 15dp 分开：图标太小按不准，
                // 但直接放大图标又会和卡片上那一行字抢注意力。
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑",
                        tint = colors.inkFaint,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

/** 标签的批量操作栏。与书架的批量栏同一套视觉。 */
@Composable
private fun TagBatchBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onColor: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = gazeColors
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Hairline(strong = true)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "退出选择", tint = colors.ink)
            }
            Text(
                text = "已选 $count 个",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSelectAll) { Text("全选", style = MaterialTheme.typography.bodySmall) }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BatchAction("改颜色", onColor, Modifier.weight(1f))
            BatchAction("删除", onDelete, Modifier.weight(1f), destructive = true)
        }
    }
}

@Composable
private fun BatchAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false
) {
    val colors = gazeColors
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier
            .clip(shape)
            .border(1.dp, if (destructive) colors.accent else colors.hairline, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (destructive) colors.accent else colors.ink,
            maxLines = 1
        )
    }
}

@Composable
private fun BatchColorDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var color by remember { mutableStateOf(TagPalette.COLORS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量改颜色") },
        text = {
            Column {
                Text(
                    "选一个颜色应用到所有选中的标签。",
                    style = MaterialTheme.typography.bodySmall,
                    color = gazeColors.inkFaint
                )
                Spacer(Modifier.height(14.dp))
                TagColorPicker(selected = color, onSelect = { color = it })
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun TagEditDialog(
    title: String,
    initialName: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("标签名") },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                TagColorPicker(selected = color, onSelect = { color = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), color) },
                enabled = name.trim().isNotEmpty()
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = gazeColors.accent)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ==================================================================== 收藏页

/**
 * 收藏页。
 *
 * 与书架的「收藏」视图**内容相同、入口不同**：书架那个是换一面墙的排列方式，
 * 这里是标签体系里的一个集合入口。所以它有自己的一套操作 ——
 * 排序、点进详情、直接取消收藏 —— 而不是把人送回书架。
 */
@Composable
fun FavoritesScreen(
    comics: List<ComicEntity>,
    sort: SortOrder,
    onSortChange: (SortOrder) -> Unit,
    onBack: () -> Unit,
    onOpenComic: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onRemoveFavorite: (Long) -> Unit
) {
    val colors = gazeColors
    var sortMenu by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 20.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.ink)
            }
            Icon(
                GazeIcons.StarFilled,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "收藏",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (comics.isEmpty()) "还没有收藏" else "${comics.size} 本",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint
                )
            }
            Box {
                Text(
                    text = sort.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint,
                    modifier = Modifier
                        .clickable { sortMenu = true }
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                )
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    order.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (order == sort) colors.accent
                                    else MaterialTheme.colorScheme.onBackground
                                )
                            },
                            onClick = {
                                onSortChange(order)
                                sortMenu = false
                            }
                        )
                    }
                }
            }
        }
        Hairline()

        if (comics.isEmpty()) {
            EmptyState(
                title = "还没有收藏",
                message = "在书架里长按选中漫画，然后点「收藏」。\n收藏是一组跨文件夹、跨标签的漫画。"
            )
            return@Column
        }

        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics, key = { it.id }) { comic ->
                    Column(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickableCompat(
                                    onClick = { onOpenComic(comic.id) },
                                    onLongClick = { onOpenDetail(comic.id) }
                                )
                        ) {
                            ComicCover(
                                coverPath = comic.coverPath,
                                title = comic.title,
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)
                            )
                            Icon(
                                GazeIcons.StarFilled,
                                contentDescription = "已收藏",
                                tint = colors.accent,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(5.dp)
                                    .size(13.dp)
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            text = comic.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(5.dp))
                        ThinProgress(progress = comic.progress)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "取消收藏",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkFaint,
                            modifier = Modifier.clickable { onRemoveFavorite(comic.id) }
                        )
                    }
                }
            }
            LazyGridScrollbar(
                state = gridState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
            )
            BackToTopButton(
                state = gridState,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
            )
        }
    }
}

// ==================================================================== 标签详情

@Composable
fun TagDetailScreen(
    tag: TagEntity?,
    comics: List<ComicEntity>,
    sort: SortOrder,
    onSortChange: (SortOrder) -> Unit,
    onBack: () -> Unit,
    onOpenComic: (Long) -> Unit,
    onRemoveFromTag: (Long) -> Unit
) {
    val colors = gazeColors
    var sortMenu by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.ink)
            }
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(tag?.color ?: colors.inkFaint.hashCode()))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = tag?.name ?: "标签",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                Text(
                    text = sort.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint,
                    modifier = Modifier
                        .clickable { sortMenu = true }
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                )
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(order.label, style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                onSortChange(order)
                                sortMenu = false
                            }
                        )
                    }
                }
            }
        }
        Hairline()

        if (comics.isEmpty()) {
            EmptyState(
                title = "这个标签下还没有漫画",
                message = "在书架里长按选中漫画，然后用「加标签」把它们归到这里。"
            )
        } else {
            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(comics, key = { it.id }) { comic ->
                        Column(Modifier.fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().clickable { onOpenComic(comic.id) }) {
                                ComicCover(
                                    coverPath = comic.coverPath,
                                    title = comic.title,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)
                                )
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(
                                text = comic.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(5.dp))
                            ThinProgress(progress = comic.progress)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "移除此标签",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkFaint,
                                modifier = Modifier.clickable { onRemoveFromTag(comic.id) }
                            )
                        }
                    }
                }
                LazyGridScrollbar(
                    state = gridState,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
                )
                BackToTopButton(
                    state = gridState,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
                )
            }
        }
    }
}

/** 详情页里展示漫画元信息时复用。 */
@Composable
fun ComicMetaLine(comic: ComicEntity, modifier: Modifier = Modifier) {
    Text(
        text = listOfNotNull(
            comic.author,
            comic.format,
            "${comic.pageCount} 页",
            Formatters.fileSize(comic.size, "")
        ).filter { it.isNotBlank() }.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = gazeColors.inkFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
