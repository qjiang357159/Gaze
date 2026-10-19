package com.gaze.reader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gaze.reader.ui.theme.gazeColors
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 点击 + 长按。
 *
 * `combinedClickable` 目前仍是实验性 API，每个调用点都写一遍 `@OptIn` 太啰嗦，
 * 统一收口到这里。长按进入批量选择（文档第十一章）。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** 发丝线 —— Editorial 风格里的核心分隔元素，替代阴影与色块。 */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    strong: Boolean = false
) {
    val colors = gazeColors
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (strong) colors.hairlineStrong else colors.hairline)
    )
}

/** 竖向发丝线。 */
@Composable
fun VerticalHairline(
    modifier: Modifier = Modifier,
    height: Int = 14
) {
    Box(
        modifier
            .width(1.dp)
            .height(height.dp)
            .background(gazeColors.hairline)
    )
}

/**
 * 分区小标签。
 *
 * 用拉开字距的小字 + 淡色，是「书籍目录排版」的标志性元素（文档第十九章）。
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color ?: gazeColors.inkFaint,
        modifier = modifier
    )
}

/** 页面主标题：大字号 + 粗体 + 负字距，下方一条细线收口。 */
@Composable
fun ScreenTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = gazeColors.inkFaint,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
        }
        Hairline()
    }
}

/** 漫画封面。缺图时退化成「纸色 + 标题首字」的排版块，而不是灰色方块。 */
@Composable
fun ComicCover(
    coverPath: String?,
    title: String,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 2,
    showSpine: Boolean = true
) {
    val colors = gazeColors
    val shape = RoundedCornerShape(cornerRadius.dp)
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, colors.hairline, shape)
    ) {
        if (!coverPath.isNullOrBlank() && File(coverPath).exists()) {
            val file = File(coverPath)
            // 换封面是**覆盖同一个文件**（`covers/<id>.jpg`），路径永远不变。
            // 缓存键必须带上修改时间，否则 Coil 会一直拿内存里那张旧图 ——
            // 用户点了「更换封面」却看不出任何变化。
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file)
                    .memoryCacheKey("${file.absolutePath}:${file.lastModified()}")
                    .diskCacheKey("${file.absolutePath}:${file.lastModified()}")
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PlaceholderCover(title = title, modifier = Modifier.fillMaxSize())
        }
        // 书脊：左侧一道极淡的竖线，让封面看起来像「一册书」而不是一张图。
        //
        // 注意修饰符顺序：必须是 `width(1.dp).fillMaxHeight()`。
        // 反过来写成 `fillMaxSize().width(1.dp)` 是无效的 —— fillMaxSize 会把约束
        // 固定成满宽，随后的 width(1.dp) 会被 Constraints.constrain() 压回去，
        // 于是这条「线」实际铺满整个封面，变成一层蒙纱（v1.0.0 的显示问题）。
        if (showSpine) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 3.dp)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(colors.hairline.copy(alpha = 0.5f))
            )
        }
    }
}

@Composable
private fun PlaceholderCover(title: String, modifier: Modifier = Modifier) {
    val colors = gazeColors
    Box(
        modifier = modifier.padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title.take(1).ifBlank { "漫" },
                style = MaterialTheme.typography.headlineMedium,
                color = colors.inkFaint,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .width(16.dp)
                    .height(1.dp)
                    .background(colors.hairlineStrong)
            )
        }
    }
}

/** 极细的阅读进度条。 */
@Composable
fun ThinProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color? = null,
    barColor: Color? = null
) {
    val colors = gazeColors
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(trackColor ?: colors.hairline)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(2.dp)
                .background(barColor ?: colors.accent)
        )
    }
}

/**
 * 空状态。
 *
 * 主次按钮的视觉重量刻意分开：主按钮 Filled、次按钮 Outlined ——
 * 两个同等重量的按钮并排会让用户犹豫点哪个。
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    val colors = gazeColors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(40.dp)
                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkFaint,
            textAlign = TextAlign.Center
        )
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPrimary, shape = RoundedCornerShape(4.dp)) {
                Text(primaryLabel)
            }
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onSecondary, shape = RoundedCornerShape(4.dp)) {
                Text(secondaryLabel)
            }
        }
    }
}

/** 元信息行：用小圆点分隔的淡色文字，例如「ZIP · 132 页 · 48.2 MB」。 */
@Composable
fun MetaLine(
    parts: List<String>,
    modifier: Modifier = Modifier,
    color: Color? = null
) {
    val colors = gazeColors
    Text(
        text = parts.filter { it.isNotBlank() }.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = color ?: colors.inkFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

// ==================================================================== 滚动条

/** 滚动停止后滚动条停留多久再淡出。 */
private const val SCROLLBAR_FADE_DELAY_MS = 550L

/**
 * 内容至少要这么多个屏幕高，才值得配滚动条与「返回顶部」。
 *
 * 「划一两下就到底」的页面（设置主入口、阅读统计这种）根本不需要导航辅助 ——
 * 一条几乎满格的滚动条既给不出方向感，还白占一条边。
 */
private const val SCROLLBAR_MIN_SCREENS = 2.0f

/** 可见比例大于它，说明内容不足 [SCROLLBAR_MIN_SCREENS] 屏。 */
private const val SCROLLBAR_MIN_FRACTION = 1f / SCROLLBAR_MIN_SCREENS

/** 滚动条可见部分的宽度。 */
private val SCROLLBAR_WIDTH = 3.dp

/**
 * 可拖动时把触摸区放宽到这么宽。
 *
 * 3dp 的线用拇指根本捏不住 —— 但视觉上又不能变粗，所以只放宽**触摸**范围，
 * 拇指本身还是 3dp。多出来的 11dp 盖在内容右缘，用户几乎不会察觉。
 */
private val SCROLLBAR_TOUCH_WIDTH = 14.dp

/** 拇指最短长度（px），免得几万条内容时它细成一根看不见的线。 */
private const val SCROLLBAR_MIN_THUMB_PX = 18f

/**
 * 细滚动条。
 *
 * Compose 没有内置滚动条，但「能滑很久的页面」没有它就很没有方向感 ——
 * 用户不知道自己在 500 条记录里的哪一段。这里的做法是：
 * 平时完全隐形，**一开始滑动就出现，停下来 0.55 秒后淡出**。
 *
 * 传了 [onSeek] 就可以**直接拖**：拖动期间不会淡出，松手后照常。
 *
 * @param progress 0..1 的滚动位置。
 * @param size 0..1 的可见比例（拇指长度）。内容不足 [SCROLLBAR_MIN_SCREENS] 屏时不画。
 * @param onSeek 拖到某个位置（0..1）时回调；传 null 就是只读的指示条。
 */
@Composable
fun GazeScrollbar(
    progress: Float,
    size: Float,
    scrolling: Boolean,
    modifier: Modifier = Modifier,
    onSeek: ((Float) -> Unit)? = null
) {
    var shown by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var trackPx by remember { mutableIntStateOf(0) }

    // 拖动期间算作「正在滚动」：手指按着的时候条子不该淡走
    val active = scrolling || dragging
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(SCROLLBAR_FADE_DELAY_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "gazeScrollbar"
    )

    // 内容不足两屏：没有值得导航的空间，画一条几乎满格的线只会让人困惑
    if (size > SCROLLBAR_MIN_FRACTION || (alpha <= 0.01f && !dragging)) return

    val thumbFraction = size.coerceIn(0.05f, 1f)
    val thumbPx = (trackPx * thumbFraction).coerceAtLeast(SCROLLBAR_MIN_THUMB_PX)
    val maxOffset = (trackPx - thumbPx).coerceAtLeast(0f)
    val topPx = progress.coerceIn(0f, 1f) * maxOffset

    Box(
        modifier
            .width(if (onSeek == null) SCROLLBAR_WIDTH else SCROLLBAR_TOUCH_WIDTH)
            .fillMaxHeight()
            .onSizeChanged { trackPx = it.height }
            .then(
                if (onSeek == null) Modifier else Modifier.pointerInput(onSeek, thumbFraction) {
                    // 手指按到哪里，拇指中心就到哪里 —— 绝对定位比「按位移增量」
                    // 手感好得多（后者在快速拖动时会因为丢帧而落后）。
                    fun seekTo(y: Float) {
                        val thumb = (trackPx * thumbFraction)
                            .coerceAtLeast(SCROLLBAR_MIN_THUMB_PX)
                        val span = (trackPx - thumb).coerceAtLeast(0f)
                        if (span <= 0f) return
                        onSeek(((y - thumb / 2f) / span).coerceIn(0f, 1f))
                    }
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            seekTo(offset.y)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onDrag = { change, _ ->
                            change.consume()
                            seekTo(change.position.y)
                        }
                    )
                }
            ),
        contentAlignment = Alignment.TopEnd
    ) {
        Box(
            Modifier
                .offset { IntOffset(0, topPx.roundToInt()) }
                .width(SCROLLBAR_WIDTH)
                .height(with(LocalDensity.current) { thumbPx.toDp() })
                .clip(RoundedCornerShape(2.dp))
                .background(gazeColors.inkFaint.copy(alpha = 0.5f * alpha))
        )
    }
}

/** LazyColumn / LazyRow 用的滚动条（可拖动）。 */
@Composable
fun LazyListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
    val scope = rememberCoroutineScope()
    val span = (total - visible).coerceAtLeast(1)
    GazeScrollbar(
        progress = if (total <= visible) 0f else first.toFloat() / span.toFloat(),
        size = if (total <= 0) 1f else visible.toFloat() / total.toFloat(),
        scrolling = state.isScrollInProgress,
        modifier = modifier,
        onSeek = { fraction ->
            scope.launch { state.scrollToItem((fraction * span).roundToInt()) }
        }
    )
}

/** LazyVerticalGrid 用的滚动条（可拖动）。 */
@Composable
fun LazyGridScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
    val scope = rememberCoroutineScope()
    val span = (total - visible).coerceAtLeast(1)
    GazeScrollbar(
        progress = if (total <= visible) 0f else first.toFloat() / span.toFloat(),
        size = if (total <= 0) 1f else visible.toFloat() / total.toFloat(),
        scrolling = state.isScrollInProgress,
        modifier = modifier,
        onSeek = { fraction ->
            scope.launch { state.scrollToItem((fraction * span).roundToInt()) }
        }
    )
}

/** `verticalScroll` 用的滚动条（可拖动）。 */
@Composable
fun ScrollStateScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    val max = state.maxValue
    val viewport = state.viewportSize
    val total = max + viewport
    val scope = rememberCoroutineScope()
    GazeScrollbar(
        progress = if (max <= 0) 0f else state.value.toFloat() / max.toFloat(),
        size = if (total <= 0) 1f else viewport.toFloat() / total.toFloat(),
        scrolling = state.isScrollInProgress,
        modifier = modifier,
        onSeek = { fraction ->
            scope.launch { state.scrollTo((fraction * max).roundToInt()) }
        }
    )
}

// ==================================================================== 返回顶部

/** 滚过这么多像素之后才值得出现「返回顶部」。太早出现只会挡内容。 */
const val BACK_TO_TOP_THRESHOLD_PX = 900

/** 某个可滚动区域是不是「长内容」——不足两屏就不配导航辅助。 */
val LazyListState.isLongScroll: Boolean
    get() {
        val info = layoutInfo
        if (info.viewportSize.height <= 0) return false
        return info.totalItemsCount > info.visibleItemsInfo.size * SCROLLBAR_MIN_SCREENS
    }

/** [LazyListState.isLongScroll] 的网格版。 */
val LazyGridState.isLongScroll: Boolean
    get() {
        val info = layoutInfo
        if (info.viewportSize.height <= 0) return false
        return info.totalItemsCount > info.visibleItemsInfo.size * SCROLLBAR_MIN_SCREENS
    }

/** [LazyListState.isLongScroll] 的 `verticalScroll` 版。 */
val ScrollState.isLongScroll: Boolean
    get() = viewportSize > 0 && maxValue > viewportSize * (SCROLLBAR_MIN_SCREENS - 1f)

/** 已经滚离顶部、且内容确实够长。 */
fun LazyListState.scrolledBeyond(thresholdPx: Int = BACK_TO_TOP_THRESHOLD_PX): Boolean =
    isLongScroll && (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > thresholdPx)

/** [LazyListState.scrolledBeyond] 的网格版。 */
fun LazyGridState.scrolledBeyond(thresholdPx: Int = BACK_TO_TOP_THRESHOLD_PX): Boolean =
    isLongScroll && (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > thresholdPx)

/** [LazyListState.scrolledBeyond] 的 `verticalScroll` 版。 */
fun ScrollState.scrolledBeyond(thresholdPx: Int = BACK_TO_TOP_THRESHOLD_PX): Boolean =
    isLongScroll && value > thresholdPx

/**
 * 返回顶部。
 *
 * 与滚动条是一对：滚动条解决「跳到哪儿」，它解决「一步回去」。
 * 长列表翻到底之后想回到开头，靠滑是几百下的事。
 *
 * 三个重载各自知道怎么判断「该不该出现」和「怎么回去」，
 * 调用方只需要把它放进一个 `Box` 里对齐 —— 少一处在每帧读 `layoutInfo`
 * 的隐患（那个读取不包在 `derivedStateOf` 里会让整页逐帧重组）。
 */
@Composable
fun BackToTopButton(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val visible by remember(state) { derivedStateOf { state.scrolledBeyond() } }
    BackToTopButton(
        visible = visible,
        onClick = { scope.launch { state.scrollToItem(0) } },
        modifier = modifier
    )
}

/** [BackToTopButton] 的网格版。 */
@Composable
fun BackToTopButton(
    state: LazyGridState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val visible by remember(state) { derivedStateOf { state.scrolledBeyond() } }
    BackToTopButton(
        visible = visible,
        onClick = { scope.launch { state.scrollToItem(0) } },
        modifier = modifier
    )
}

/** [BackToTopButton] 的 `verticalScroll` 版。 */
@Composable
fun BackToTopButton(
    state: ScrollState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val visible by remember(state) { derivedStateOf { state.scrolledBeyond() } }
    BackToTopButton(
        visible = visible,
        onClick = { scope.launch { state.scrollTo(0) } },
        modifier = modifier
    )
}

@Composable
private fun BackToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = gazeColors
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.8f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
        modifier = modifier
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, colors.hairlineStrong, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = GazeIcons.ToTop,
                contentDescription = "返回顶部",
                tint = colors.ink,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
