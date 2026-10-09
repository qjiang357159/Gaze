package com.gaze.reader.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gaze.reader.core.Formatters
import com.gaze.reader.core.LibraryLayout
import com.gaze.reader.core.LibraryView
import com.gaze.reader.core.SortOrder
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.ShelfGroup
import com.gaze.reader.scan.ScanPhase
import com.gaze.reader.ui.components.BackToTopButton
import com.gaze.reader.ui.components.ComicCover
import com.gaze.reader.ui.components.EmptyState
import com.gaze.reader.ui.components.GazeIcons
import com.gaze.reader.ui.components.Hairline
import com.gaze.reader.ui.components.LazyGridScrollbar
import com.gaze.reader.ui.components.LazyListScrollbar
import com.gaze.reader.ui.components.ScreenTitle
import com.gaze.reader.ui.components.SectionLabel
import com.gaze.reader.ui.components.ThinProgress
import com.gaze.reader.ui.components.combinedClickableCompat
import com.gaze.reader.ui.theme.gazeColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** 滚动条与「顶部菜单自动收起」共用的判据：已经滚离顶部。 */
private fun LazyListState.isScrolled(): Boolean =
    firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

private fun LazyGridState.isScrolled(): Boolean =
    firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    openedGroup: ShelfGroup?,
    onViewChange: (LibraryView) -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onQueryChange: (String) -> Unit,
    onLayoutChange: (LibraryLayout) -> Unit,
    onOpenComic: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenGroup: (ShelfGroup) -> Unit,
    onCloseGroup: () -> Unit,
    onSetGroupCover: (String, Long) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onBatchFavorite: () -> Unit,
    onBatchTags: () -> Unit,
    onBatchDelete: () -> Unit,
    onUndoRemove: () -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onAddFolder: () -> Unit,
    onDismissContinue: () -> Unit,
    onConsumeMessage: () -> Unit
) {
    var groupCoverTarget by remember { mutableStateOf<ShelfGroup?>(null) }
    var showSelectionDetail by remember { mutableStateOf(false) }

    // 滚动状态提升到这一层：顶部菜单的自动收起要靠它判断「有没有滚离顶部」。
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val groupState = rememberLazyGridState()
    var searchFocused by remember { mutableStateOf(false) }

    /**
     * 顶部菜单是否展开。
     *
     * 规则很简单：**滚离顶部就收起，回到顶部就展开**。不做「下滑才收、
     * 上滑就放」那套方向判断 —— 那需要额外记住上一次的滚动方向，
     * 且在惯性滚动时容易抖。搜索框有焦点或有搜索词时永远展开，
     * 否则用户正在打字，菜单自己收起来就成了事故。
     */
    val headerExpanded by remember(state.layout, state.view, openedGroup) {
        derivedStateOf {
            val scrolled = when {
                openedGroup != null -> groupState.isScrolled()
                state.layout.isGrid -> gridState.isScrolled()
                else -> listState.isScrolled()
            }
            !scrolled
        }
    }
    val showHeader = headerExpanded || state.selectionMode || searchFocused || state.query.isNotEmpty()

    /**
     * 当前这一屏到底在用哪个滚动状态。
     *
     * 书架页有三种排布（分组网格 / 漫画网格 / 漫画列表），它们各自持有自己的
     * 滚动位置 —— 「返回顶部」必须作用在**当前这一个**上，不能三个一起推。
     */
    val folderLike = openedGroup != null ||
        state.view == LibraryView.FOLDER || state.view == LibraryView.TAG
    val activeGrid = if (folderLike) groupState else gridState
    val useGrid = folderLike || state.layout.isGrid

    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showHeader,
            enter = expandVertically(animationSpec = tween(140)),
            exit = shrinkVertically(animationSpec = tween(140))
        ) {
            Column {
                if (openedGroup == null) {
                    ScreenTitle(
                        title = "书架",
                        subtitle = state.stats?.let { "${it.totalCount} 本 · ${Formatters.fileSize(it.totalBytes, "0 B")}" }
                    )
                } else {
                    GroupHeader(group = openedGroup, onBack = onCloseGroup)
                }

                // -------------------------------------------------- 扫描 / 封面进度
                val scan = state.scan
                val cover = state.cover
                if (scan != null || cover != null) {
                    ProgressBanner(
                        title = when {
                            scan?.phase == ScanPhase.DISCOVERING -> "正在查找漫画…"
                            scan?.phase == ScanPhase.IMPORTING -> "正在索引 ${scan.processed} / ${scan.total}"
                            scan?.phase == ScanPhase.MARKING -> "正在校验文件…"
                            scan?.phase == ScanPhase.DONE -> "扫描完成"
                            cover != null -> "正在生成封面 ${cover.processed} / ${cover.total}"
                            else -> "处理中…"
                        },
                        detail = scan?.currentName?.takeIf { it.isNotBlank() } ?: cover?.currentName.orEmpty(),
                        progress = when {
                            scan != null && scan.total > 0 -> scan.processed.toFloat() / scan.total
                            cover != null && cover.total > 0 -> cover.processed.toFloat() / cover.total
                            else -> 0f
                        },
                        indeterminate = scan?.phase == ScanPhase.DISCOVERING,
                        onCancel = onCancelScan
                    )
                }

                if (openedGroup == null) {
                    SearchRow(
                        query = state.query,
                        onQueryChange = onQueryChange,
                        onFocusChange = { searchFocused = it }
                    )
                    ToolbarRow(
                        sort = state.sort,
                        view = state.view,
                        layout = state.layout,
                        onSortChange = onSortChange,
                        onViewChange = onViewChange,
                        onLayoutChange = onLayoutChange,
                        onHistory = onOpenHistory,
                        onScan = onScan,
                        showActions = !state.selectionMode
                    )
                }
            }
        }

        Hairline()

        // ------------------------------------------------------ 内容
        Box(Modifier.weight(1f)) {
            when {
                openedGroup != null -> GroupContent(
                    group = openedGroup,
                    layout = state.layout,
                    selected = state.selected,
                    selectionMode = state.selectionMode,
                    gridState = groupState,
                    listState = listState,
                    onOpen = onOpenComic,
                    onToggleSelect = onToggleSelect
                )

                // 首帧还没拿到数据时不判定「空」—— 否则会闪一下「书架还是空的」
                state.loading -> Unit

                state.folders.isEmpty() && state.comics.isEmpty() && state.groups.isEmpty() ->
                    EmptyState(
                        title = "书架还是空的",
                        message = "授权一个或多个文件夹，Gaze 会自动扫描其中的漫画。\n不需要逐个添加文件。",
                        primaryLabel = "授权文件夹",
                        onPrimary = onAddFolder
                    )

                state.view == LibraryView.FOLDER || state.view == LibraryView.TAG ->
                    if (state.groups.isEmpty()) {
                        EmptyState(
                            title = if (state.view == LibraryView.FOLDER) "还没有文件夹" else "还没有标签",
                            message = if (state.view == LibraryView.FOLDER)
                                "授权文件夹并扫描之后，同一个目录下的漫画会收进一个书架文件夹。"
                            else
                                "给漫画加上标签之后，同一个标签下的漫画会收进一个书架文件夹。"
                        )
                    } else {
                        GroupGrid(
                            groups = state.groups,
                            layout = state.layout,
                            gridState = groupState,
                            onOpen = onOpenGroup,
                            onLongPress = { groupCoverTarget = it }
                        )
                    }

                state.comics.isEmpty() ->
                    EmptyState(
                        title = if (state.query.isBlank()) {
                            if (state.view == LibraryView.FAVORITE) "还没有收藏" else "没有找到漫画"
                        } else {
                            "没有匹配的结果"
                        },
                        message = when {
                            state.query.isNotBlank() -> "换个关键词试试 —— 可以搜标题、作者、标签或文件名。"
                            state.view == LibraryView.FAVORITE -> "在书架里长按选中漫画，然后点「收藏」。"
                            else -> "已授权的文件夹里没有找到可读的漫画文件。"
                        },
                        secondaryLabel = if (state.query.isBlank()) "重新扫描" else "清空搜索",
                        onSecondary = {
                            if (state.query.isBlank()) onScan() else onQueryChange("")
                        }
                    )

                else -> ComicCollection(
                    comics = state.comics,
                    layout = state.layout,
                    selected = state.selected,
                    selectionMode = state.selectionMode,
                    gridState = gridState,
                    listState = listState,
                    onOpen = onOpenComic,
                    onToggleSelect = onToggleSelect
                )
            }

            // -------------------------------------------------- 继续阅读（右下角悬浮）
            //
            // 把「该不该显示」收进 takeIf：这样下面 `if (continueComic != null)`
            // 就是唯一的判空，不需要在 fabVisible 之后再重复判一次
            // （重复判空编译器会直接报「条件恒为真」）。
            val continueComic = state.continueComic?.takeIf {
                openedGroup == null && !state.continueDismissed && !state.selectionMode
            }
            val fabVisible = continueComic != null
            if (continueComic != null) {
                ContinueReadingFab(
                    comic = continueComic,
                    raisedByNotice = state.message != null,
                    onClick = { onOpenComic(continueComic.id) },
                    onDismiss = onDismissContinue,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            // -------------------------------------------------- 返回顶部
            //
            // 与滚动条是一对：滚动条解决「跳到哪儿」，它解决「一步回去」。
            // 位置叠在「继续阅读」上方 —— 拇指本来就在右下角，不必为它换手。
            if (useGrid) {
                BackToTopButton(
                    state = activeGrid,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = if (fabVisible) 104.dp else 20.dp)
                )
            } else {
                BackToTopButton(
                    state = listState,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = if (fabVisible) 104.dp else 20.dp)
                )
            }

            // -------------------------------------------------- 通知（紧贴底部导航栏上方）
            state.message?.let { text ->
                BottomNotice(
                    text = text,
                    actionLabel = if (state.canUndoRemove) "撤销" else null,
                    onAction = onUndoRemove,
                    onDismiss = onConsumeMessage,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // ------------------------------------------------------ 批量操作栏
        if (state.selectionMode) {
            BatchBar(
                count = state.selected.size,
                allFavorite = state.selectedAllFavorite,
                onClear = onClearSelection,
                onSelectAll = onSelectAll,
                onFavorite = onBatchFavorite,
                onTags = onBatchTags,
                onDetail = {
                    if (state.selected.size == 1) {
                        onOpenDetail(state.selected.first())
                    } else {
                        showSelectionDetail = true
                    }
                },
                onDelete = onBatchDelete
            )
        }
    }

    // 长按书架文件夹 -> 换封面
    groupCoverTarget?.let { target ->
        GroupCoverDialog(
            group = target,
            onDismiss = { groupCoverTarget = null },
            onPick = { comicId ->
                onSetGroupCover(target.key, comicId)
                groupCoverTarget = null
            }
        )
    }

    if (showSelectionDetail) {
        SelectionDetailDialog(
            comics = state.visibleComics.filter { it.id in state.selected },
            onDismiss = { showSelectionDetail = false }
        )
    }
}

// ==================================================================== 顶栏

@Composable
private fun GroupHeader(group: ShelfGroup, onBack: () -> Unit) {
    val colors = gazeColors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 20.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                group.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkFaint)
                }
            }
        }
        Hairline()
    }
}

@Composable
private fun ToolbarRow(
    sort: SortOrder,
    view: LibraryView,
    layout: LibraryLayout,
    onSortChange: (SortOrder) -> Unit,
    onViewChange: (LibraryView) -> Unit,
    onLayoutChange: (LibraryLayout) -> Unit,
    onHistory: () -> Unit,
    onScan: () -> Unit,
    showActions: Boolean
) {
    val colors = gazeColors
    var sortOpen by remember { mutableStateOf(false) }
    var layoutOpen by remember { mutableStateOf(false) }
    var viewOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 排序
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable { sortOpen = true }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(sort.label, style = MaterialTheme.typography.bodySmall, color = colors.ink)
                Icon(
                    GazeIcons.Overflow,
                    contentDescription = "排序",
                    tint = colors.inkFaint,
                    modifier = Modifier.size(15.dp).padding(start = 3.dp)
                )
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                SortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                order.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (order == sort) colors.accent else colors.ink
                            )
                        },
                        onClick = {
                            onSortChange(order)
                            sortOpen = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        if (showActions) {
            // 历史记录（扫描控件左侧）
            IconButton(onClick = onHistory) {
                Icon(GazeIcons.History, contentDescription = "阅读历史", tint = colors.inkFaint)
            }
            IconButton(onClick = onScan) {
                Icon(Icons.Default.Refresh, contentDescription = "扫描", tint = colors.inkFaint)
            }
        }

        // 排版
        Box {
            IconButton(onClick = { layoutOpen = true }) {
                Icon(
                    imageVector = if (layout.isGrid) GazeIcons.grid(if (layout == LibraryLayout.GRID_2) 2 else 3)
                    else GazeIcons.list(layout == LibraryLayout.LIST_LARGE),
                    contentDescription = "排版",
                    tint = colors.inkFaint
                )
            }
            DropdownMenu(expanded = layoutOpen, onDismissRequest = { layoutOpen = false }) {
                LibraryLayout.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                candidate.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (candidate == layout) colors.accent else colors.ink
                            )
                        },
                        onClick = {
                            onLayoutChange(candidate)
                            layoutOpen = false
                        }
                    )
                }
            }
        }

        // 视图切换
        Box {
            IconButton(onClick = { viewOpen = true }) {
                Icon(
                    imageVector = when (view) {
                        LibraryView.ALL -> GazeIcons.Shelf
                        LibraryView.FAVORITE -> GazeIcons.StarOutline
                        LibraryView.FOLDER -> GazeIcons.Folder
                        LibraryView.TAG -> GazeIcons.Tag
                    },
                    contentDescription = "书架视图",
                    tint = colors.inkFaint
                )
            }
            DropdownMenu(expanded = viewOpen, onDismissRequest = { viewOpen = false }) {
                SectionLabel("书架视图", modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp))
                LibraryView.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                candidate.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (candidate == view) colors.accent else colors.ink
                            )
                        },
                        onClick = {
                            onViewChange(candidate)
                            viewOpen = false
                        }
                    )
                }
            }
        }
    }
}

// ==================================================================== 书架文件夹

@Composable
private fun GroupGrid(
    groups: List<ShelfGroup>,
    layout: LibraryLayout,
    gridState: LazyGridState,
    onOpen: (ShelfGroup) -> Unit,
    onLongPress: (ShelfGroup) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = if (layout == LibraryLayout.GRID_3) GridCells.Fixed(3) else GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(groups, key = { it.key }) { group ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickableCompat(
                            onClick = { onOpen(group) },
                            onLongClick = { onLongPress(group) }
                        )
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        ComicCover(
                            coverPath = group.coverPath,
                            title = group.coverTitle,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)
                        )
                        // 叠一个小角标表明「这是一个文件夹」，而不是一本书
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(5.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(2.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = group.subtitle ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = gazeColors.inkFaint
                            )
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        LazyGridScrollbar(
            state = gridState,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
        )
    }
}

@Composable
private fun GroupContent(
    group: ShelfGroup,
    layout: LibraryLayout,
    selected: Set<Long>,
    selectionMode: Boolean,
    gridState: LazyGridState,
    listState: LazyListState,
    onOpen: (Long) -> Unit,
    onToggleSelect: (Long) -> Unit
) {
    ComicCollection(
        comics = group.comics,
        layout = layout,
        selected = selected,
        selectionMode = selectionMode,
        gridState = gridState,
        listState = listState,
        onOpen = onOpen,
        onToggleSelect = onToggleSelect
    )
}

@Composable
private fun GroupCoverDialog(
    group: ShelfGroup,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更换书架封面") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "从「${group.title}」里挑一本作为封面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = gazeColors.inkFaint
                )
                Spacer(Modifier.height(10.dp))
                group.comics.forEach { comic ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(comic.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ComicCover(
                            coverPath = comic.coverPath,
                            title = comic.title,
                            modifier = Modifier.width(34.dp).height(46.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = comic.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================================================================== 漫画集合

@Composable
private fun ComicCollection(
    comics: List<ComicEntity>,
    layout: LibraryLayout,
    selected: Set<Long>,
    selectionMode: Boolean,
    gridState: LazyGridState,
    listState: LazyListState,
    onOpen: (Long) -> Unit,
    onToggleSelect: (Long) -> Unit
) {
    if (layout.isGrid) {
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = gridState,
                columns = if (layout == LibraryLayout.GRID_3) GridCells.Fixed(3) else GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics, key = { it.id }) { comic ->
                    GridCard(
                        comic = comic,
                        compact = layout == LibraryLayout.GRID_3,
                        selected = comic.id in selected,
                        selectionMode = selectionMode,
                        onClick = { if (selectionMode) onToggleSelect(comic.id) else onOpen(comic.id) },
                        onLongClick = { onToggleSelect(comic.id) }
                    )
                }
            }
            LazyGridScrollbar(
                state = gridState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
            )
        }
    } else {
        val large = layout == LibraryLayout.LIST_LARGE
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics, key = { it.id }) { comic ->
                    ListRow(
                        comic = comic,
                        large = large,
                        selected = comic.id in selected,
                        selectionMode = selectionMode,
                        onClick = { if (selectionMode) onToggleSelect(comic.id) else onOpen(comic.id) },
                        onLongClick = { onToggleSelect(comic.id) }
                    )
                }
            }
            LazyListScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
            )
        }
    }
}

@Composable
private fun GridCard(
    comic: ComicEntity,
    compact: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = gazeColors
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxWidth()) {
            ComicCover(
                coverPath = comic.coverPath,
                title = comic.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)
            )
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(24.dp)
                )
            }
            if (comic.favorite && !selectionMode) {
                Icon(
                    GazeIcons.StarFilled,
                    contentDescription = "已收藏",
                    tint = colors.accent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(14.dp)
                )
            }
            if (comic.missing) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(2.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("已失效", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = comic.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!compact && !comic.author.isNullOrBlank()) {
            Text(
                text = comic.author,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(5.dp))
        if (comic.progress > 0f) {
            ThinProgress(progress = comic.progress)
        } else {
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.hairline.copy(alpha = 0.4f)))
        }
    }
}

@Composable
private fun ListRow(
    comic: ComicEntity,
    large: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = gazeColors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 20.dp, vertical = if (large) 14.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
            }
            ComicCover(
                coverPath = comic.coverPath,
                title = comic.title,
                modifier = if (large) Modifier.width(76.dp).height(104.dp)
                else Modifier.width(44.dp).height(60.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = comic.title,
                    style = if (large) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = listOfNotNull(
                        comic.author,
                        comic.format,
                        "${comic.pageCount} 页",
                        Formatters.fileSize(comic.size, "")
                    ).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(7.dp))
                ThinProgress(progress = comic.progress)
            }
            if (comic.favorite && !selectionMode) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    GazeIcons.StarFilled,
                    contentDescription = "已收藏",
                    tint = colors.accent,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Hairline(Modifier.padding(start = 20.dp))
    }
}

// ==================================================================== 悬浮 / 通知

/**
 * 「继续阅读」悬浮窗。
 *
 * 从顶部一整条改成右下角的小窗之后，书架的第一屏能多放一整行封面；
 * 左滑或右滑都能关掉（本次会话内不再出现），不需要跑到设置里关。
 */
@Composable
private fun ContinueReadingFab(
    comic: ComicEntity,
    raisedByNotice: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = gazeColors
    val density = LocalDensity.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(density) { 96.dp.toPx() }
    val maxDragPx = with(density) { 320.dp.toPx() }

    Column(
        modifier
            // 通知出现时抬高一点，避免两者叠在一起
            .padding(end = 16.dp, bottom = if (raisedByNotice) 68.dp else 16.dp)
            .width(232.dp)
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .pointerInput(comic.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // 左右两个方向都算关闭，超过阈值就滑走，否则弹回原位
                        if (abs(offsetX) > dismissThresholdPx) onDismiss() else offsetX = 0f
                    },
                    onDragCancel = { offsetX = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX = (offsetX + dragAmount).coerceIn(-maxDragPx, maxDragPx)
                    }
                )
            }
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ComicCover(
                coverPath = comic.coverPath,
                title = comic.title,
                modifier = Modifier.width(38.dp).height(52.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                SectionLabel("继续阅读")
                Spacer(Modifier.height(3.dp))
                Text(
                    text = comic.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "第 ${comic.currentPage + 1} / ${comic.pageCount} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkFaint
                )
                Spacer(Modifier.height(5.dp))
                ThinProgress(progress = comic.progress)
            }
        }
    }
}

/**
 * 底部通知条。
 *
 * 贴在**导航栏正上方**，与阅读页的底栏、书架页的悬浮窗形成同一条视觉基线 ——
 * 之前飘在顶部时会盖住搜索框，用户还以为是页面标题变了。
 */
@Composable
private fun BottomNotice(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = gazeColors
    Row(
        modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
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
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.bodySmall, color = colors.accent)
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "关闭", tint = colors.inkFaint, modifier = Modifier.size(16.dp))
        }
    }
}

// ==================================================================== 其它组件

@Composable
private fun ProgressBanner(
    title: String,
    detail: String,
    progress: Float,
    indeterminate: Boolean,
    onCancel: () -> Unit
) {
    val colors = gazeColors
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            TextButton(onClick = onCancel) { Text("取消") }
        }
        ThinProgress(progress = if (indeterminate) 0.35f else progress)
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit = {}
) {
    val colors = gazeColors
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChange(it.isFocused) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        placeholder = {
            Text("搜索标题、作者、标签或文件名", style = MaterialTheme.typography.bodyMedium)
        },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, tint = colors.inkFaint)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "清空", tint = colors.inkFaint)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(4.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = colors.ink,
            unfocusedIndicatorColor = colors.hairline,
            cursorColor = colors.accent
        ),
        textStyle = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun BatchBar(
    count: Int,
    allFavorite: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onTags: () -> Unit,
    onDetail: () -> Unit,
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
                text = "已选 $count 本",
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
            // 「收藏 / 取消收藏」按当前状态合成一个按钮
            BatchAction(if (allFavorite) "取消收藏" else "收藏", onFavorite, Modifier.weight(1f))
            BatchAction("标签", onTags, Modifier.weight(1f))
            BatchAction("详情", onDetail, Modifier.weight(1f))
            BatchAction("移除", onDelete, Modifier.weight(1f), destructive = true)
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

/** 多选时的文件信息弹层。 */
@Composable
private fun SelectionDetailDialog(comics: List<ComicEntity>, onDismiss: () -> Unit) {
    val colors = gazeColors
    val totalBytes = comics.sumOf { it.size }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件信息") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "共 ${comics.size} 本 · ${Formatters.fileSize(totalBytes, "0 B")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink
                )
                Spacer(Modifier.height(12.dp))
                comics.forEach { comic ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(
                            text = comic.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = listOfNotNull(
                                comic.author,
                                comic.format,
                                "${comic.pageCount} 页",
                                Formatters.fileSize(comic.size, "")
                            ).filter { it.isNotBlank() }.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = comic.fileName,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Hairline()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
