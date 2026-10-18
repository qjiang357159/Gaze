package com.gaze.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import kotlin.math.roundToInt

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
    onCreate: (String, Int) -> Unit,
    onRename: (Long, String) -> Unit,
    onSetColor: (Long, Int) -> Unit,
    onDelete: (Long, String) -> Unit,
    onReorder: (List<Long>) -> Unit
) {
    var editorTarget by remember { mutableStateOf<TagWithCount?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showBatchColor by remember { mutableStateOf(false) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    val selectionMode = selection.isNotEmpty()
    val gridState = rememberLazyGridState()

    // ------------------------------------------------------------ 拖动排序
    //
    // 长按标签：**不动松手 = 进入批量选择**（原有肌肉记忆照旧），
    // **拖动 = 排序**。两者共用一个手势源，靠「有没有真的挪动」区分 ——
    // 再单独挂一个长按监听的话，两个 500ms 判定会互相抢，必然有一个永远不触发。
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var movedEnough by remember { mutableStateOf(false) }

    // 拖动期间用本地顺序渲染（让其他卡片实时让位），松手提交后再交还给数据源。
    var localIds by remember { mutableStateOf<List<Long>?>(null) }
    val display = remember(tags, localIds) {
        val ids = localIds ?: return@remember tags
        // 拖动时这段每帧都要跑一次，用索引而不是对每个 id 扫一遍列表。
        val byId = tags.associateBy { it.id }
        ids.mapNotNull { byId[it] }
    }

    // 提交后数据库回流要一两帧。顺序对上了才把控制权交还，
    // 否则会先闪一下旧顺序、再跳到新顺序。
    LaunchedEffect(tags) {
        val ids = localIds ?: return@LaunchedEffect
        if (tags.map { it.id } == ids) localIds = null
    }

    val dragShape = remember { RoundedCornerShape(6.dp) }
    val haptic = LocalHapticFeedback.current

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

        Box(
            Modifier
                .weight(1f)
                // 长按与拖动排序的手势。
                //
                // 挂在**容器**上而不是每张卡片上：卡片坐标会随重排变化，
                // 挂在卡片上会让「手指位置 -> 目标格子」的换算在让位那一帧错位；
                // 挂在这个不动的容器上，坐标自始至终只有一个参照系。
                //
                // 它能成立的前提是**卡片不消费指针事件**（见 TagCard 里那段注释）——
                // 长按判定要求 down 是干净的，而 clickable 会把 down 消费掉，
                // 于是长按会被判成「被别的手势抢走」，批量管理和拖动排序会一起失灵。
                .then(
                    if (selectionMode) Modifier
                    else Modifier.pointerInput(tags) {
                        val slop = viewConfiguration.touchSlop
                        val gapPx = TAG_GRID_GAP.toPx()
                        // 手指在容器坐标里的当前位置，以及「手指相对卡片中心的偏移」。
                        //
                        // 刻意**不**用「被拖卡片的位置 + dragOffset」反推它：卡片一换格子，
                        // 那个位置就跟着变，反推出来的坐标会自己漂 —— 而下面的判定
                        // 只要跟着漂，就会漂出「换过去又换回来」的死循环。
                        var pointerPos = Offset.Zero
                        var grabOffset = Offset.Zero

                        // 格子网：拖动开始时量一次，整个拖动期间都用这一份。
                        //
                        // 不实时读 `layoutInfo` 是有原因的：让位动画进行中时，读到的
                        // 位置处在「旧格子和新格子之间」，拿它当基准算出来的目标格
                        // 会跟着动画一起抖。量一次之后，基准就固定了。
                        var anchorIndex = 0
                        var anchorCenter = Offset.Zero
                        var stepX = 0f
                        var stepY = 0f

                        detectDragGesturesAfterLongPress(
                            onDragStart = { position ->
                                val hit = gridState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { item ->
                                        position.x >= item.offset.x &&
                                            position.x < item.offset.x + item.size.width &&
                                            position.y >= item.offset.y &&
                                            position.y < item.offset.y + item.size.height
                                    }
                                val id = hit?.key as? Long
                                    ?: return@detectDragGesturesAfterLongPress
                                pointerPos = position
                                // 记下抓在哪：抓左边缘和抓正中间，换位时机不该差半张卡。
                                grabOffset = position - Offset(
                                    hit.offset.x + hit.size.width / 2f,
                                    hit.offset.y + hit.size.height / 2f
                                )
                                anchorIndex = hit.index
                                anchorCenter = Offset(
                                    hit.offset.x + hit.size.width / 2f,
                                    hit.offset.y + hit.size.height / 2f
                                )
                                stepX = hit.size.width + gapPx
                                stepY = hit.size.height + gapPx
                                draggingId = id
                                localIds = tags.map { it.id }
                                dragOffset = Offset.Zero
                                movedEnough = false
                                // 长按成立时震一下：手指底下有反馈，用户才知道
                                // 「现在松手是批量管理，继续挪是排序」。
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, delta ->
                                val id = draggingId
                                    ?: return@detectDragGesturesAfterLongPress
                                change.consume()
                                pointerPos += delta
                                dragOffset += delta
                                if (!movedEnough) {
                                    // 长按之后几乎没挪 = 用户要的是批量管理，不是排序。
                                    // 用位移而不是时间区分：手抖一下不该把排序误判成选择。
                                    if (dragOffset.getDistance() <= slop) {
                                        return@detectDragGesturesAfterLongPress
                                    }
                                    movedEnough = true
                                }
                                if (stepX <= 0f || stepY <= 0f) {
                                    return@detectDragGesturesAfterLongPress
                                }

                                // 目标格 = 被拖卡片的中心落在哪一格。
                                //
                                // 早先这里是「离哪张卡片最近就换给谁」，在两张卡之间
                                // 来回蹭的时候会失控地闪：换位之后被让出来的那张正好停在
                                // 手指底下，成了「最近的那一个」，于是下一次事件又换回去，
                                // 一帧一个来回。
                                //
                                // 现在改成先算出一张**固定的格子网**，再问「中心落在网眼哪一格」。
                                // 这张网只由「列数 / 卡片尺寸 / 间距」决定，跟当前排列顺序
                                // 无关 —— 换算结果不会因为刚才换过一次位而变，反馈回路
                                // 从源头上被切断了。
                                val center = pointerPos - grabOffset
                                // 列要夹回 0..列数-1：往右边拖出网格时，横向多出来的
                                // 那一截不该被算成「又往后跳一格」。
                                val col = (anchorIndex % TAG_GRID_COLUMNS +
                                    ((center.x - anchorCenter.x) / stepX).roundToInt())
                                    .coerceIn(0, TAG_GRID_COLUMNS - 1)
                                val row = anchorIndex / TAG_GRID_COLUMNS +
                                    ((center.y - anchorCenter.y) / stepY).roundToInt()

                                val ids = localIds
                                    ?: return@detectDragGesturesAfterLongPress
                                if (ids.isEmpty()) return@detectDragGesturesAfterLongPress
                                val from = ids.indexOf(id)
                                if (from < 0) return@detectDragGesturesAfterLongPress
                                val to = (row * TAG_GRID_COLUMNS + col)
                                    .coerceIn(0, ids.lastIndex)
                                if (from == to) return@detectDragGesturesAfterLongPress

                                localIds = ids.toMutableList().apply { add(to, removeAt(from)) }
                                // 让位之后被拖的卡片会「落」进目标格子，
                                // 把这段位移从累积量里减掉，卡片才会稳稳停在手指下。
                                // 用格子步长算而不是读布局：此刻布局还是换位前的。
                                dragOffset -= Offset(
                                    (to % TAG_GRID_COLUMNS - from % TAG_GRID_COLUMNS) * stepX,
                                    (to / TAG_GRID_COLUMNS - from / TAG_GRID_COLUMNS) * stepY
                                )
                            },
                            onDragEnd = {
                                val id = draggingId
                                val ids = localIds
                                if (id != null && movedEnough && ids != null) {
                                    onReorder(ids)
                                    // localIds 先留着，等数据库回流后再交还，免得闪回旧顺序
                                } else {
                                    // 长按了但没挪：回到原本的语义 —— 批量管理。
                                    if (id != null) onToggleSelect(id)
                                    localIds = null
                                }
                                draggingId = null
                                dragOffset = Offset.Zero
                                movedEnough = false
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffset = Offset.Zero
                                movedEnough = false
                                localIds = null
                            }
                        )
                    }
                )
        ) {
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
                    columns = GridCells.Fixed(TAG_GRID_COLUMNS),
                    contentPadding = PaddingValues(
                        start = 20.dp, end = 20.dp, top = 14.dp, bottom = 96.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(TAG_GRID_GAP),
                    verticalArrangement = Arrangement.spacedBy(TAG_GRID_GAP),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(display, key = { it.id }) { tag ->
                        val dragging = tag.id == draggingId
                        // 被拖的那张不用 animateItem：它的位移由手指直接驱动，
                        // 再叠一层位置动画只会让它追着手指跑，越拖越滞后。
                        val base = if (dragging) Modifier else Modifier.animateItem()
                        Box(
                            base
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging) {
                                        translationX = dragOffset.x
                                        translationY = dragOffset.y
                                        // 长按一成立就抬起。它同时是「长按生效了」的反馈：
                                        // 抬着不动再松手 = 批量管理，抬着挪走 = 排序。
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                        shadowElevation = 14f
                                        shape = dragShape
                                        clip = false
                                    }
                                }
                        ) {
                            TagCard(
                                accent = Color(tag.color),
                                name = tag.name,
                                countLabel = "${tag.comicCount} 本",
                                selectionMode = selectionMode,
                                selected = tag.id in selection,
                                onClick = {
                                    if (selectionMode) onToggleSelect(tag.id) else onOpenTag(tag.id)
                                },
                                onEdit = { editorTarget = tag }
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
            // 默认色用「第一个还没被用过的」——和仓库自动推荐的口径一致。
            // 之前是按下标取色，和实际落库的颜色对不上，用户看到的默认色是假的。
            initialColor = TagPalette.nextUnused(tags.map { it.color }),
            onDismiss = { showCreate = false },
            onConfirm = { name, color ->
                // 这里必须把 color 传下去：对话框里选了什么就用什么。
                onCreate(name, color)
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
 * 网格间距。
 *
 * 抽成常量是因为**拖动排序的格子换算也要用它** —— 那套换算假定格子是等距的，
 * 一旦这里改了而换算处还写着字面量，拖到后半屏就会偏半个格子。
 */
private val TAG_GRID_GAP = 10.dp

/** 列数。拖动换算要靠它把「第几列」折回合法范围，和上面的间距同理。 */
private const val TAG_GRID_COLUMNS = 2

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
 * 长按**不在这里**处理：长按既可能变成拖动排序、也可能变成批量选择，
 * 两者共用一个判定，统一交给网格上那层手势处理，卡片自己只认点击。
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
    onEdit: (() -> Unit)? = null
) {
    val colors = gazeColors
    val shape = RoundedCornerShape(6.dp)
    var pressed by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .height(TAG_CARD_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // 按压反馈自己画：这里不能用 clickable，也就没有它的水波纹。
            .background(if (pressed) colors.ink.copy(alpha = 0.05f) else Color.Transparent)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.accent else colors.hairline,
                shape = shape
            )
            // 点击自己实现，**刻意不消费任何指针事件**。
            //
            // 长按判定挂在网格容器上，而 clickable 会在 Main pass 把 down 消费掉 ——
            // 容器那边一看「down 已被消费」就认定长按被别的手势抢走，直接放弃，
            // 于是长按永远不成立，批量管理和拖动排序会一起失灵。这里只观察、不消费。
            //
            // 代价是没有水波纹，所以要自己补两件事：
            //  * 按下时叠一层极淡的墨色（就是上面那层 background）；
            //  * 自己把「不算点击」的几种情况排除掉（见下）。
            //
            // 判定写在 **Final pass** 上：Main pass 之后所有手势该消费的都消费完了，
            // 在这里能一次性看清「这一串事件到底有没有被别人拿走」。
            .pointerInput(Unit) {
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    var upTime: Long? = null
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // 被别人消费了 —— 滚动接管了这次手势，不该再算点击。
                        //
                        // 这一条是「上下滑动老是点进标签里」的正解：LazyGrid 的滚动
                        // 在 Main pass 消费 move，而只按「按下时长」判定的话，
                        // 一次快速的滑动（按下到抬起不到 500ms）会被整段误判成点击。
                        if (change.isConsumed) {
                            cancelled = true
                            break
                        }
                        // 位移兜底：滚动还没到消费那一步时，手指早就滑出 touchSlop 了。
                        if ((change.position - down.position).getDistance() > slop) {
                            cancelled = true
                            break
                        }
                        if (!change.pressed) {
                            upTime = change.uptimeMillis
                            break
                        }
                    }
                    pressed = false
                    // 长按之后的那次抬手也要排除：容器在长按后消费了后续事件，
                    // 唯独 up 是它来不及消费的那一个，不按时长兜一道的话，
                    // 长按松手会顺带把标签详情打开。
                    val held = upTime?.minus(down.uptimeMillis)
                    if (!cancelled && held != null && held < longPressTimeout) onClick()
                }
            }
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
