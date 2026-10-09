package com.gaze.reader.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gaze.reader.core.FitMode
import com.gaze.reader.core.ReadMode
import com.gaze.reader.core.ReaderBackground
import com.gaze.reader.core.RenderMode
import com.gaze.reader.core.ScreenOrientation
import com.gaze.reader.image.PageDecoder
import com.gaze.reader.image.PageSize
import com.gaze.reader.ui.components.GazeIcons
import com.gaze.reader.ui.components.SectionLabel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 阅读页向 ViewModel 取图的三个入口。
 *
 * 打包成一组而不是散着传，是因为它们**必须来自同一个会话** ——
 * 归档只打开一次，区域解码依赖同一份已打开的流。
 */
class PageLoader(
    /** 整页解码（按显示尺寸）。 */
    val loadFit: suspend (page: Int, reqWidth: Int, reqHeight: Int) -> Bitmap?,
    /** 只读文件头拿原始尺寸。 */
    val loadSize: suspend (page: Int) -> PageSize?,
    /** 区域解码（放大 / 条漫 / 超高分辨率图）。 */
    val loadRegion: suspend (page: Int, region: Rect, outWidth: Int, outHeight: Int) -> Bitmap?
)

/**
 * 阅读页的取色。
 *
 * **控件背景跟随阅读器背景** —— 阅读器背景是用户选的，控件栏没理由写死成黑色。
 * 墨色按背景明暗切换，白底阅读时不会出现「浅灰字压白底」。
 */
data class ReaderColors(
    val background: Color,
    /** 控件栏底色：阅读器背景加一点透明度，底下的画面会隐隐透出来。 */
    val bar: Color,
    val ink: Color,
    val faint: Color,
    val line: Color
)

private fun readerColorsFor(argb: Int): ReaderColors {
    val bg = Color(argb)
    return if (bg.luminance() < 0.5f) {
        ReaderColors(
            background = bg,
            bar = bg.copy(alpha = 0.94f),
            ink = Color(0xFFEDEAE3),
            faint = Color(0xFFA8A49C),
            line = Color(0xFF3E3E3E)
        )
    } else {
        ReaderColors(
            background = bg,
            bar = bg.copy(alpha = 0.94f),
            ink = Color(0xFF1A1A1A),
            faint = Color(0xFF6E6A63),
            line = Color(0xFFD2CEC7)
        )
    }
}

private val LocalReaderColors = staticCompositionLocalOf {
    readerColorsFor(ReaderBackground.BLACK.argb)
}

private val readerColors: ReaderColors
    @Composable get() = LocalReaderColors.current

/**
 * 单页加载的三态。
 *
 * v1.0.0 把「还在加载」和「解码失败」共用同一个 `null`，于是每张图在解出来的
 * 前一瞬都会先显示「第 N 页无法解码」，再变成图片 —— 用户看到的就是
 * 「一边加载一边报错」。三态拆开之后，加载中只说加载中，失败才说失败。
 */
private sealed interface PageState {
    data object Loading : PageState
    data class Ready(val bitmap: Bitmap) : PageState
    data object Failed : PageState
}

/** 一页在当前视口下的几何关系。 */
private data class PageGeometry(
    val image: PageSize,
    /** 缩放系数：原图像素 -> 屏幕像素（用户未缩放时）。 */
    val baseScale: Float
)

private const val MAX_ZOOM = 6f

/** 区域外扩比例：平移一点点不用重新解码。 */
private const val REGION_MARGIN = 0.18f

/** 区域解码的防抖：捏合过程中不要每一帧都去解一次。 */
private const val REGION_DEBOUNCE_MS = 90L

/** 区域量化网格（原图像素）。落在同一格里的平移复用同一张位图。 */
private const val REGION_QUANTUM = 32

/**
 * 阅读界面。
 *
 * **不再自动沉浸**：进入阅读页时控制栏与系统栏都在，用户点一下画面才进入
 * 沉浸阅读（控制栏与系统栏一起隐去），再点一下回来。没有 3.5 秒自动隐藏 ——
 * 「自动」正是用户要拿掉的东西。
 */
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onBack: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onSetMode: (ReadMode) -> Unit,
    onSetFitMode: (FitMode) -> Unit,
    onSetRenderMode: (RenderMode) -> Unit,
    onSetScreenOrientation: (ScreenOrientation) -> Unit,
    onSetBrightnessFollowSystem: (Boolean) -> Unit,
    onSetBrightness: (Float) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetShowPageNumber: (Boolean) -> Unit,
    onSetVolumeKeyPaging: (Boolean) -> Unit,
    onSetReaderBackground: (ReaderBackground) -> Unit,
    onFollowDefault: () -> Unit,
    onOpenDetail: () -> Unit,
    onToggleControls: () -> Unit,
    loader: PageLoader
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val colors = readerColorsFor(state.background.argb)
    val focusRequester = remember { FocusRequester() }

    // 进入阅读页之前系统栏图标的明暗。阅读页会把它改成「跟阅读器背景走」，
    // 退出时必须原样还回去 —— MainActivity 那个 LaunchedEffect 只在
    // 应用主题变化时才重跑，不管导航，不主动还原就会把设置页的状态带坏。
    val originalBarAppearance = remember(activity) {
        activity?.window?.let {
            val c = WindowInsetsControllerCompat(it, it.decorView)
            c.isAppearanceLightStatusBars to c.isAppearanceLightNavigationBars
        }
    }

    // 系统栏图标跟着**阅读器背景**走，而不是应用主题。
    //
    // 应用主题是浅色时 MainActivity 会把图标设成深色 —— 用户挑黑色阅读器
    // 阅读时，深色图标压在纯黑上等于没有，时间、电量、信号全都看不见。
    LaunchedEffect(colors.background) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val lightIcons = colors.background.luminance() > 0.5f
        controller.isAppearanceLightStatusBars = lightIcons
        controller.isAppearanceLightNavigationBars = lightIcons
    }

    // 系统栏跟着控制栏走：控制栏在 = 系统栏在（非沉浸），控制栏隐 = 全屏沉浸。
    //
    // 刻意不把 keepScreenOn 放进这个 effect 的 key —— 那样用户一改「屏幕常亮」，
    // effect 会先 dispose（排下一次延迟 show）再重建，220ms 后系统栏就冒出来了。
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            // 图标明暗**立刻**还原，系统栏本身延迟显示 —— 这两件事必须分开。
            //
            // 只影响图标颜色的那部分不引起任何布局变化，可以马上做；而延迟做
            // 会让状态栏在新页面的浅色背景上顶着一组浅色图标，泛成一片白 ——
            // 用户看到的「正常 → 泛白 → 正常」就是这 180ms。
            originalBarAppearance?.let { (lightStatus, lightNav) ->
                controller?.let {
                    it.isAppearanceLightStatusBars = lightStatus
                    it.isAppearanceLightNavigationBars = lightNav
                }
            }

            // 系统栏本身要等转场走完再显示。立刻 show() 会与导航返回的动画
            // 撞在一起：用户看到的是「满屏漫画瞬间被状态栏挤下去」的硬跳变。
            window?.decorView?.postDelayed({
                runCatching { controller?.show(WindowInsetsCompat.Type.systemBars()) }
            }, TRANSITION_DELAY_MS)

            // 亮度与方向都是**阅读页专属**的临时状态，退出时必须还原 ——
            // 它们作用在同一个 Activity 上，不还原会污染书架与设置页。
            window?.let { w ->
                val attrs = w.attributes
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = attrs
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 控制栏显隐 -> 系统栏显隐
    LaunchedEffect(state.showControls) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (state.showControls) {
            runCatching { controller.show(WindowInsetsCompat.Type.systemBars()) }
        } else {
            runCatching { controller.hide(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    // 屏幕常亮：跟着设置走，与系统栏的显隐无关。
    DisposableEffect(state.keepScreenOn) {
        val window = activity?.window
        if (state.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (state.keepScreenOn) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // 屏幕方向：阅读页可以独立于系统设定
    LaunchedEffect(state.screenOrientation) {
        activity?.requestedOrientation = when (state.screenOrientation) {
            ScreenOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // 亮度：跟随系统时置 -1（系统默认），否则用用户设定的值
    LaunchedEffect(state.brightnessFollowSystem, state.brightness) {
        val window = activity?.window ?: return@LaunchedEffect
        val attrs = window.attributes
        attrs.screenBrightness = if (state.brightnessFollowSystem) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            state.brightness.coerceIn(0.05f, 1f)
        }
        window.attributes = attrs
    }

    // 音量键翻页需要视图拿到焦点
    LaunchedEffect(state.volumeKeyPaging) {
        if (state.volumeKeyPaging) runCatching { focusRequester.requestFocus() }
    }

    CompositionLocalProvider(LocalReaderColors provides colors) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (!state.volumeKeyPaging) return@onPreviewKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.VolumeDown -> {
                            if (state.currentPage < state.pageCount - 1) {
                                onPageChanged(state.currentPage + 1)
                            }
                            true
                        }
                        Key.VolumeUp -> {
                            if (state.currentPage > 0) onPageChanged(state.currentPage - 1)
                            true
                        }
                        else -> false
                    }
                }
        ) {
            when {
                state.loading -> LoadingIndicator()

                state.error != null -> ReaderError(
                    message = state.error,
                    onBack = onBack
                )

                else -> ReaderContent(
                    state = state,
                    loader = loader,
                    onPageChanged = onPageChanged,
                    onToggleControls = onToggleControls
                )
            }

            if (state.showControls && !state.loading && state.error == null) {
                ReaderControls(
                    state = state,
                    onBack = onBack,
                    onPageChanged = onPageChanged,
                    onSetMode = onSetMode,
                    onSetFitMode = onSetFitMode,
                    onSetRenderMode = onSetRenderMode,
                    onSetScreenOrientation = onSetScreenOrientation,
                    onSetBrightnessFollowSystem = onSetBrightnessFollowSystem,
                    onSetBrightness = onSetBrightness,
                    onSetKeepScreenOn = onSetKeepScreenOn,
                    onSetShowPageNumber = onSetShowPageNumber,
                    onSetVolumeKeyPaging = onSetVolumeKeyPaging,
                    onSetReaderBackground = onSetReaderBackground,
                    onFollowDefault = onFollowDefault,
                    onOpenDetail = onOpenDetail
                )
            }
        }
    }
}

/** 退出转场时长：与导航的淡入淡出对齐（见 GazeRoot 的 NAV_TRANSITION_MS）。 */
private const val TRANSITION_DELAY_MS = 180L

// ==================================================================== 内容

@Composable
private fun ReaderContent(
    state: ReaderUiState,
    loader: PageLoader,
    onPageChanged: (Int) -> Unit,
    onToggleControls: () -> Unit
) {
    when (state.mode) {
        ReadMode.SINGLE -> SinglePagePager(
            state = state,
            loader = loader,
            onPageChanged = onPageChanged,
            onToggleControls = onToggleControls
        )

        ReadMode.DUAL -> DualPagePager(
            state = state,
            loader = loader,
            onPageChanged = onPageChanged,
            onToggleControls = onToggleControls
        )

        ReadMode.CONTINUOUS_V -> VerticalContinuous(
            state = state,
            loader = loader,
            onPageChanged = onPageChanged,
            onToggleControls = onToggleControls
        )
    }
}

@Composable
private fun SinglePagePager(
    state: ReaderUiState,
    loader: PageLoader,
    onPageChanged: (Int) -> Unit,
    onToggleControls: () -> Unit
) {
    val liveCount by rememberUpdatedState(state.pageCount)
    val pagerState = rememberPagerState(initialPage = state.pagerIndex.coerceAtLeast(0)) { liveCount }
    val scope = rememberCoroutineScope()

    // pager -> 状态
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            onPageChanged(page)
        }
    }
    // 状态 -> pager（进度条跳转、恢复进度）
    LaunchedEffect(state.pagerIndex, state.pageCount) {
        if (state.pageCount > 0 && pagerState.currentPage != state.pagerIndex) {
            pagerState.scrollToPage(state.pagerIndex.coerceIn(0, state.pageCount - 1))
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        pageSpacing = 0.dp,
        // 预先组合相邻页，翻过去时不用等解码
        beyondViewportPageCount = 1
    ) { page ->
        ZoomablePage(
            page = page,
            loader = loader,
            fitMode = state.fitMode,
            onTap = onToggleControls,
            // 这一页坏了不代表整本读不下去，给个直接越过去的出口
            onSkip = {
                if (page < state.pageCount - 1) {
                    scope.launch { pagerState.animateScrollToPage(page + 1) }
                }
            }
        )
    }
}

@Composable
private fun DualPagePager(
    state: ReaderUiState,
    loader: PageLoader,
    onPageChanged: (Int) -> Unit,
    onToggleControls: () -> Unit
) {
    val liveCount by rememberUpdatedState(state.pageCount)
    val pagerState = rememberPagerState(initialPage = state.pagerIndex.coerceAtLeast(0)) { liveCount }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { index ->
            val range = dualPageRange(index, state.pageCount)
            if (!range.isEmpty()) onPageChanged(range.first)
        }
    }
    LaunchedEffect(state.pagerIndex, state.pageCount) {
        if (state.pageCount > 0 && pagerState.currentPage != state.pagerIndex) {
            pagerState.scrollToPage(state.pagerIndex.coerceIn(0, (state.pagerCount - 1).coerceAtLeast(0)))
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        pageSpacing = 0.dp,
        beyondViewportPageCount = 1
    ) { index ->
        val range = dualPageRange(index, state.pageCount)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val slotWidth = maxWidth / 2
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 双页一律从左向右：前页在左，后页在右
                range.toList().forEach { pageIndex ->
                    ZoomablePage(
                        page = pageIndex,
                        loader = loader,
                        fitMode = state.fitMode,
                        onTap = onToggleControls,
                        modifier = Modifier.width(slotWidth),
                        onSkip = {
                            if (index < state.pagerCount - 1) {
                                scope.launch { pagerState.animateScrollToPage(index + 1) }
                            }
                        }
                    )
                }
                if (range.count() == 1) {
                    Spacer(Modifier.width(slotWidth))
                }
            }
        }
    }
}

@Composable
private fun VerticalContinuous(
    state: ReaderUiState,
    loader: PageLoader,
    onPageChanged: (Int) -> Unit,
    onToggleControls: () -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.currentPage.coerceAtLeast(0)
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            onPageChanged(index)
        }
    }
    LaunchedEffect(state.currentPage) {
        if (listState.firstVisibleItemIndex != state.currentPage) {
            listState.scrollToItem(state.currentPage)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 纵向连续刻意**不留额外空隙**（文档第六章第 4 条）
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(state.pageCount) { index ->
            VerticalPage(
                index = index,
                loader = loader,
                listState = listState,
                onToggleControls = onToggleControls,
                onSkip = {
                    if (index < state.pageCount - 1) {
                        scope.launch { listState.animateScrollToItem(index + 1) }
                    }
                }
            )
        }
    }
}

/**
 * 纵向连续里的单页。
 *
 * 关键点：**先探尺寸，再决定怎么解**。
 *
 * - 普通页：按「适应宽度」整页解一次，滚动过程中不重解码；
 * - 超长条漫（整页像素数超过 [PageDecoder.MAX_INLINE_PIXELS]）：
 *   只解**当前可视那一段**，随滚动窗口滑动重新解。这就是「Tile Rendering」——
 *   分块是为了让一条 2 万像素高的条漫只占视口大小的内存，
 *   而不是把它压成低分辨率再放大。
 */
@Composable
private fun VerticalPage(
    index: Int,
    loader: PageLoader,
    listState: LazyListState,
    onToggleControls: () -> Unit,
    onSkip: () -> Unit
) {
    var retry by remember(index) { mutableIntStateOf(0) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth

        val size by produceState<PageSize?>(null, index, retry) {
            value = loader.loadSize(index)
        }
        val pageSize = size

        if (pageSize == null) {
            PageBlock(onTap = onToggleControls) { SectionLabel("正在加载…", color = readerColors.faint) }
            return@BoxWithConstraints
        }

        val scale = if (pageSize.width > 0) widthPx.toFloat() / pageSize.width else 1f
        val fullHeightPx = max(1, (pageSize.height * scale).roundToInt())

        // 超长条漫：整页解出来会吃掉太多内存，改成按可视窗口分块解码
        val tiled = pageSize.pixels > PageDecoder.MAX_INLINE_PIXELS

        Box(
            Modifier
                .fillMaxWidth()
                .height(with(density) { fullHeightPx.toDp() })
                .clickable(onClick = onToggleControls)
        ) {
            if (tiled) {
                TiledPage(
                    index = index,
                    loader = loader,
                    pageSize = pageSize,
                    scale = scale,
                    fullHeightPx = fullHeightPx,
                    listState = listState,
                    retry = retry,
                    onRetry = { retry++ },
                    onSkip = onSkip
                )
            } else {
                val state by produceState<PageState>(
                    PageState.Loading, index, widthPx, retry
                ) {
                    value = loader.loadFit(index, widthPx, 0)
                        ?.let { PageState.Ready(it) }
                        ?: PageState.Failed
                }
                when (val s = state) {
                    is PageState.Ready -> Image(
                        bitmap = s.bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )

                    PageState.Loading -> SectionLabel("正在加载…", color = readerColors.faint)

                    PageState.Failed -> PageFailed(
                        pageNumber = index + 1,
                        onRetry = { retry++ },
                        onSkip = onSkip
                    )
                }
            }
        }
    }
}

/**
 * 超长条漫的分块渲染。
 *
 * 只解「当前可视带 + 上下各外扩一点」那一段，块在屏幕上的像素尺寸就是它的
 * 解码目标尺寸 —— 所以看到的依然是原图分辨率，只是不把整条 2 万像素高的图
 * 一次性塞进内存。
 */
@Composable
private fun TiledPage(
    index: Int,
    loader: PageLoader,
    pageSize: PageSize,
    scale: Float,
    fullHeightPx: Int,
    listState: LazyListState,
    retry: Int,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    val density = LocalDensity.current

    // 本页在列表视口里的可视带（页内坐标，px）
    val band by remember(index, fullHeightPx, scale) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewport = info.viewportSize.height
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                ?: return@derivedStateOf null
            val top = max(0, -item.offset)
            val bottom = min(fullHeightPx, -item.offset + viewport)
            if (bottom <= top) null else top to bottom
        }
    }

    val bandKey = band?.let { (top, bottom) -> "${top / 64}:${bottom / 64}" }

    var loaded by remember(index) { mutableStateOf<LoadedBand?>(null) }

    LaunchedEffect(index, bandKey, retry) {
        val (top, bottom) = band ?: run {
            loaded = null
            return@LaunchedEffect
        }
        val current = loaded
        if (current != null && current.covers(top, bottom)) return@LaunchedEffect
        delay(REGION_DEBOUNCE_MS)

        val margin = ((bottom - top) * 0.35f).roundToInt()
        var y0 = max(0, top - margin)
        var y1 = min(fullHeightPx, bottom + margin)
        // 量化到 64px 网格：小幅滚动命中同一块
        y0 = (y0 / 64) * 64
        y1 = min(fullHeightPx, ((y1 + 63) / 64) * 64)
        if (y1 <= y0) return@LaunchedEffect

        val imageTop = (y0 / scale).roundToInt().coerceIn(0, pageSize.height - 1)
        val imageBottom = (y1 / scale).roundToInt().coerceIn(imageTop + 1, pageSize.height)
        val region = Rect(0, imageTop, pageSize.width, imageBottom)
        val outWidth = max(1, (pageSize.width * scale).roundToInt())
        val outHeight = max(1, (y1 - y0))

        val bitmap = loader.loadRegion(index, region, outWidth, outHeight) ?: return@LaunchedEffect
        loaded = LoadedBand(y0, y1, bitmap)
    }

    val current = loaded
    if (current == null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SectionLabel("正在加载…", color = readerColors.faint)
        }
        return
    }

    val bandHeightPx = current.bottom - current.top
    Image(
        bitmap = current.bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .offset { IntOffset(0, current.top) }
            .requiredSize(
                width = with(density) { (pageSize.width * scale).toDp() },
                height = with(density) { bandHeightPx.toDp() }
            )
    )
}

/** 已解出的可视带（页内坐标，px）。 */
private data class LoadedBand(val top: Int, val bottom: Int, val bitmap: Bitmap) {
    fun covers(top: Int, bottom: Int): Boolean = this.top <= top && this.bottom >= bottom
}

@Composable
private fun PageBlock(onTap: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun PageFailed(
    pageNumber: Int,
    onRetry: () -> Unit,
    onSkip: (() -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SectionLabel("第 $pageNumber 页无法解码", color = readerColors.faint)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FailedAction(label = "重试", onClick = onRetry)
            // 「跳过」是「单页损坏不能导致整本读不下去」这条要求的落点：
            // 光有重试，坏页永远解不开，用户只能一页页手划过去。
            if (onSkip != null) FailedAction(label = "跳过此页", onClick = onSkip)
        }
    }
}

@Composable
private fun FailedAction(label: String, onClick: () -> Unit) {
    val rc = readerColors
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, rc.line, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Text(label, color = rc.ink, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 可缩放页面。
 *
 * 手势分三层：
 * - 双击：在 1× 与 2.5× 之间切换；已是放大态时双击复位到 100%；
 * - 双指：连续缩放 + 拖动；
 * - 单击：切换控制栏显隐；
 * - **单指横滑且未放大：一律不消费，交给外层 Pager 翻页**。
 *
 * 清晰度分两档：
 * - 图像在屏幕上占的像素**不超过视口** -> 用整页位图（已按显示尺寸解码）；
 * - 超过视口（用户放大、或图本身比屏幕大）-> 改成**按可见区域解码**，
 *   区域按屏幕实际像素数解，必要时接近原图像素级。
 *   分块只是省内存，分辨率不降。
 */
@Composable
private fun ZoomablePage(
    page: Int,
    loader: PageLoader,
    fitMode: FitMode,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onSkip: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    var scale by remember(page) { mutableFloatStateOf(1f) }
    var offsetX by remember(page) { mutableFloatStateOf(0f) }
    var offsetY by remember(page) { mutableFloatStateOf(0f) }
    var retry by remember(page) { mutableIntStateOf(0) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewW = constraints.maxWidth
        val viewH = constraints.maxHeight

        val size by produceState<PageSize?>(null, page, retry) {
            value = loader.loadSize(page)
        }
        val pageState by produceState<PageState>(PageState.Loading, page, viewW, viewH, retry) {
            value = loader.loadFit(page, viewW, viewH)
                ?.let { PageState.Ready(it) }
                ?: PageState.Failed
        }

        val geometry = remember(size, viewW, viewH, fitMode) {
            size?.let { PageGeometry(it, baseScaleOf(it, viewW, viewH, fitMode)) }
        }

        // 视口在「原图坐标」里对应的区域（含外扩与量化），以及它该解多大
        val request by remember(page, geometry, viewW, viewH) {
            derivedStateOf {
                val geo = geometry ?: return@derivedStateOf null
                regionRequestFor(geo, scale, offsetX, offsetY, viewW, viewH)
            }
        }
        val requestKey = request?.let { req ->
            "${req.rect.left},${req.rect.top},${req.rect.right},${req.rect.bottom}:${req.outWidth}:${req.outHeight}"
        }

        var loaded by remember(page) { mutableStateOf<LoadedRegion?>(null) }

        LaunchedEffect(page, requestKey, retry) {
            val req = request
            if (req == null) {
                loaded = null
                return@LaunchedEffect
            }
            val current = loaded
            if (current != null && current.covers(req)) return@LaunchedEffect
            delay(REGION_DEBOUNCE_MS)
            val bitmap = loader.loadRegion(page, req.rect, req.outWidth, req.outHeight)
                ?: return@LaunchedEffect
            loaded = LoadedRegion(req, bitmap)
        }

        // 手势可拖动的前提：放大过，或者图本身就比视口大
        val baseOverflows = geometry?.let {
            it.image.width * it.baseScale > viewW + 0.5f ||
                it.image.height * it.baseScale > viewH + 0.5f
        } ?: false

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(page) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(page, viewW, viewH, geometry) {
                    detectPageGestures(
                        isPanEnabled = { scale > 1.01f || baseOverflows },
                        onTransform = { pan, zoom ->
                            val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            scale = next
                            val geo = geometry
                            if (geo != null) {
                                val eff = geo.baseScale * next
                                val maxX = max(0f, (geo.image.width * eff - viewW) / 2f)
                                val maxY = max(0f, (geo.image.height * eff - viewH) / 2f)
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val current = loaded
            if (current != null && geometry != null) {
                // 高分辨率区域：按它在屏幕上的真实位置与大小摆放，像素级对齐
                val eff = geometry.baseScale * scale
                val cx = geometry.image.width / 2f
                val cy = geometry.image.height / 2f
                val region = current.request.rect
                val leftPx = viewW / 2f + (region.left - cx) * eff + offsetX
                val topPx = viewH / 2f + (region.top - cy) * eff + offsetY
                val wPx = region.width() * eff
                val hPx = region.height() * eff
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                        .requiredSize(
                            width = with(density) { wPx.toDp() },
                            height = with(density) { hPx.toDp() }
                        )
                )
            } else {
                when (val s = pageState) {
                    is PageState.Ready -> Image(
                        bitmap = s.bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = when (fitMode) {
                            FitMode.SCREEN -> ContentScale.Fit
                            FitMode.WIDTH -> ContentScale.FillWidth
                            FitMode.HEIGHT -> ContentScale.FillHeight
                            FitMode.ORIGINAL -> ContentScale.None
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
                    )

                    PageState.Loading -> SectionLabel("正在加载…", color = readerColors.faint)

                    PageState.Failed -> PageFailed(
                        pageNumber = page + 1,
                        onRetry = { retry++ },
                        onSkip = onSkip
                    )
                }
            }
        }
    }
}

/** 待解码的区域及其目标像素尺寸。 */
private data class RegionRequest(
    val rect: Rect,
    val outWidth: Int,
    val outHeight: Int
)

private data class LoadedRegion(val request: RegionRequest, val bitmap: Bitmap) {
    fun covers(other: RegionRequest): Boolean =
        request.rect.contains(other.rect) && request.outWidth >= other.outWidth
}

/** 当前适配方式下的「原图像素 -> 屏幕像素」系数。 */
private fun baseScaleOf(image: PageSize, viewW: Int, viewH: Int, fitMode: FitMode): Float {
    if (image.width <= 0 || image.height <= 0) return 1f
    return when (fitMode) {
        FitMode.SCREEN -> min(viewW.toFloat() / image.width, viewH.toFloat() / image.height)
        FitMode.WIDTH -> viewW.toFloat() / image.width
        FitMode.HEIGHT -> viewH.toFloat() / image.height
        FitMode.ORIGINAL -> 1f
    }.coerceAtLeast(0.01f)
}

/**
 * 算出「当前视口需要哪一块原图、以及它该按多少像素解」。
 *
 * 返回 null 表示**不需要区域解码** —— 图像在屏幕上占的像素不超过视口，
 * 整页位图已经足够清晰（它的解码尺寸保证不小于视口）。
 */
private fun regionRequestFor(
    geo: PageGeometry,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    viewW: Int,
    viewH: Int
): RegionRequest? {
    val eff = geo.baseScale * zoom
    if (eff <= 0f) return null
    val imageW = geo.image.width
    val imageH = geo.image.height
    val displayW = imageW * eff
    val displayH = imageH * eff
    if (displayW <= viewW + 0.5f && displayH <= viewH + 0.5f) return null

    val cx = imageW / 2f
    val cy = imageH / 2f
    val halfW = viewW / 2f
    val halfH = viewH / 2f

    var x0 = cx - (halfW + offsetX) / eff
    var x1 = cx + (halfW - offsetX) / eff
    var y0 = cy - (halfH + offsetY) / eff
    var y1 = cy + (halfH - offsetY) / eff

    // 外扩：平移一点点不该触发重新解码
    val padX = (x1 - x0) * REGION_MARGIN
    val padY = (y1 - y0) * REGION_MARGIN
    x0 -= padX
    x1 += padX
    y0 -= padY
    y1 += padY

    // 量化：落在同一格里的平移复用同一张位图
    val left = (x0 / REGION_QUANTUM).toInt() * REGION_QUANTUM
    val top = (y0 / REGION_QUANTUM).toInt() * REGION_QUANTUM
    val right = ((x1 / REGION_QUANTUM).toInt() + 1) * REGION_QUANTUM
    val bottom = ((y1 / REGION_QUANTUM).toInt() + 1) * REGION_QUANTUM

    val rect = Rect(
        left.coerceIn(0, max(0, imageW - 1)),
        top.coerceIn(0, max(0, imageH - 1)),
        right.coerceIn(1, imageW),
        bottom.coerceIn(1, imageH)
    )
    if (rect.width() <= 0 || rect.height() <= 0) return null

    // 目标像素：就是这块在屏幕上的实际大小；上限是原图像素（不做无意义的放大）
    val outWidth = (rect.width() * eff).roundToInt().coerceIn(1, rect.width())
    val outHeight = (rect.height() * eff).roundToInt().coerceIn(1, rect.height())
    return RegionRequest(rect, outWidth, outHeight)
}

/**
 * 阅读页的手势识别。
 *
 * 和 `detectTransformGestures` 的区别就在**消费策略**上：
 * 后者只要位移超过 touch slop 就 `consume()` 掉整个事件流，外层
 * `HorizontalPager` 再也收不到拖动 —— 于是单页 / 双页模式完全翻不了页。
 *
 * 这里改成按需消费：
 * - 单指 + 未放大 + 图没超出视口 -> 判定为翻页手势，**不消费**，放行给 Pager；
 * - 双指，或已放大 / 图超出视口后的单指拖动 -> 消费，做缩放 / 平移。
 */
private suspend fun PointerInputScope.detectPageGestures(
    isPanEnabled: () -> Boolean,
    onTransform: (pan: Offset, zoom: Float) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var multiTouch = false

        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) multiTouch = true

            if (multiTouch || isPanEnabled()) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (zoomChange != 1f || panChange != Offset.Zero) {
                    onTransform(panChange, zoomChange)
                    // 已经判定是缩放 / 平移手势了，整个事件流都归我们
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun LoadingIndicator() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SectionLabel("正在打开…", color = readerColors.faint)
    }
}

@Composable
private fun ReaderError(message: String, onBack: () -> Unit) {
    val rc = readerColors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "无法打开",
                style = MaterialTheme.typography.titleLarge,
                color = rc.ink
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = rc.faint
            )
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, rc.line, RoundedCornerShape(3.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text("返回", color = rc.ink, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ==================================================================== 控制栏

@Composable
private fun ReaderControls(
    state: ReaderUiState,
    onBack: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onSetMode: (ReadMode) -> Unit,
    onSetFitMode: (FitMode) -> Unit,
    onSetRenderMode: (RenderMode) -> Unit,
    onSetScreenOrientation: (ScreenOrientation) -> Unit,
    onSetBrightnessFollowSystem: (Boolean) -> Unit,
    onSetBrightness: (Float) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetShowPageNumber: (Boolean) -> Unit,
    onSetVolumeKeyPaging: (Boolean) -> Unit,
    onSetReaderBackground: (ReaderBackground) -> Unit,
    onFollowDefault: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val rc = readerColors
    val ink = rc.ink
    val faint = rc.faint
    val author = state.comic?.author
    var showBrightness by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------- 顶栏
        //
        // 顺序要紧：先铺底色（这样栏底会盖住状态栏区域），再让出状态栏高度。
        // 反过来写会得到「底色从状态栏下沿才开始」的一条断层。
        Row(
            Modifier
                .fillMaxWidth()
                .background(rc.bar)
                .statusBarsPadding()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = ink)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.comic?.title ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!author.isNullOrBlank()) {
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // 顶栏与画面之间补一条分隔线。
        //
        // 栏底是「背景色 + 6% 透明」，在纯黑背景上几乎和画面连成一片 ——
        // 加了这条线，控件栏才读得出「这是一条栏」而不是「浮在画面上的几个字」。
        HorizontalDivider(color = rc.line)

        Spacer(Modifier.weight(1f))

        // ---------------------------------------------------- 底栏
        //
        // 底栏要同时满足两件事：
        // 1. **让开系统导航栏**，否则按钮压在导航条上根本点不准；
        // 2. 在此之上再留一段自己的余量（12dp），让拇指有地方落。
        //
        // 分隔线画在「让开导航栏之后、加内边距之前」，这样它和顶栏那条一样
        // 是通栏的 —— 两条线对齐，控件栏才像同一套东西。
        Column(
            Modifier
                .fillMaxWidth()
                .background(rc.bar)
                .navigationBarsPadding()
        ) {
            HorizontalDivider(color = rc.line)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                if (state.showPageNumber && state.pageCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${state.currentPage + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ink
                        )
                        Text(
                            text = " / ${state.pageCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = faint
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = state.mode.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = faint
                        )
                    }
                    Slider(
                        value = state.currentPage.toFloat(),
                        onValueChange = { onPageChanged(it.toInt().coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))) },
                        valueRange = 0f..((state.pageCount - 1).coerceAtLeast(1)).toFloat(),
                        steps = 0,
                        colors = SliderDefaults.colors(
                            thumbColor = ink,
                            activeTrackColor = ink,
                            inactiveTrackColor = rc.line
                        )
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlsMenu(label = state.mode.label, onFollowDefault = onFollowDefault) { dismiss ->
                        ReadMode.entries.forEach { mode ->
                            MenuItem(mode.label, mode == state.mode) {
                                onSetMode(mode)
                                dismiss()
                            }
                        }
                    }
                    ControlsMenu(label = state.fitMode.label, onFollowDefault = onFollowDefault) { dismiss ->
                        FitMode.entries.forEach { fit ->
                            MenuItem(fit.label, fit == state.fitMode) {
                                onSetFitMode(fit)
                                dismiss()
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // 目录 / 页码跳转。
                    // PDF 拿不到内嵌大纲（系统渲染器不暴露），所以这个面板**同时**
                    // 承担「目录」和「跳到第 N 页」两件事 —— 底栏那根滑条只能粗跳，
                    // 输入页码才是精确的。
                    ControlsIcon(GazeIcons.Toc, "目录", { showToc = true })

                    // 详情：跳到详情页看文件信息
                    ControlsIcon(GazeIcons.Detail, "详情", onOpenDetail)

                    // 溢出菜单：屏幕方向 / 亮度 / 渲染模式 / 音量键翻页 / 屏幕常亮 / 显示页码
                    ControlsOverflowMenu(
                        state = state,
                        onSetRenderMode = onSetRenderMode,
                        onSetScreenOrientation = onSetScreenOrientation,
                        onSetKeepScreenOn = onSetKeepScreenOn,
                        onSetShowPageNumber = onSetShowPageNumber,
                        onSetVolumeKeyPaging = onSetVolumeKeyPaging,
                        onSetReaderBackground = onSetReaderBackground,
                        onOpenBrightness = { showBrightness = true }
                    )
                    }
            }
        }
    }

    if (showBrightness) {
        BrightnessDialog(
            followSystem = state.brightnessFollowSystem,
            value = state.brightness,
            onSetFollowSystem = onSetBrightnessFollowSystem,
            onSetValue = onSetBrightness,
            onDismiss = { showBrightness = false }
        )
    }

    if (showToc) {
        ReaderTocPanel(
            state = state,
            onJump = { page ->
                onPageChanged(page)
                showToc = false
            },
            onDismiss = { showToc = false }
        )
    }
}

/**
 * 目录 / 页码跳转面板。
 *
 * 不用 `ModalBottomSheet`：它的容器色取自 Material 主题，浅色应用下会弹出
 * 「黑色阅读器上的一张白纸」，和 [ReaderDropdownMenu] 遇到的是同一个坑。
 * 这里自己铺一层遮罩 + 一块贴底的板，配色全部取自 [readerColors]。
 */
@Composable
private fun ReaderTocPanel(
    state: ReaderUiState,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val rc = readerColors
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // 「当前读到的这一条」= 最后一个起点不晚于当前页的条目
    val activeIndex = remember(state.toc, state.currentPage) {
        state.toc.indexOfLast { it.pageIndex <= state.currentPage }
    }

    // 打开时定位到当前章节，而不是每次都从第一章开始
    LaunchedEffect(Unit) {
        if (activeIndex > 0) listState.scrollToItem(activeIndex)
    }

    fun submit() {
        val page = input.trim().toIntOrNull() ?: return
        onJump((page - 1).coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss)
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .background(rc.bar)
                .navigationBarsPadding()
        ) {
            HorizontalDivider(color = rc.line)

            // ---------------------------------------------------- 标题行
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("目录", style = MaterialTheme.typography.titleSmall, color = rc.ink)
                if (state.formatLabel.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(state.formatLabel, style = MaterialTheme.typography.bodySmall, color = rc.faint)
                }
                Spacer(Modifier.weight(1f))
                Text("关闭", style = MaterialTheme.typography.bodySmall, color = rc.faint,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
            }

            // ---------------------------------------------------- 页码跳转
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("跳到", style = MaterialTheme.typography.bodySmall, color = rc.faint)
                Box(
                    Modifier
                        .width(78.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .border(1.dp, rc.line, RoundedCornerShape(3.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { raw -> input = raw.filter { it.isDigit() }.take(6) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = rc.ink),
                        cursorBrush = SolidColor(rc.ink),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (input.isEmpty()) {
                        Text("${state.currentPage + 1}", color = rc.faint,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("/ ${state.pageCount}", style = MaterialTheme.typography.bodySmall, color = rc.faint)
                Spacer(Modifier.weight(1f))
                FailedAction("跳转") { submit() }
            }

            HorizontalDivider(color = rc.line, modifier = Modifier.padding(top = 8.dp))

            // ---------------------------------------------------- 目录条目
            if (state.toc.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "这个文件没有内嵌目录，可以用上面的页码跳转",
                        style = MaterialTheme.typography.bodySmall,
                        color = rc.faint
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.toc.size) { i ->
                        val entry = state.toc[i]
                        val active = i == activeIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onJump(entry.pageIndex) }
                                .padding(
                                    start = 16.dp + (entry.depth.coerceAtMost(4) * 14).dp,
                                    end = 16.dp,
                                    top = 11.dp,
                                    bottom = 11.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (active) rc.ink else rc.faint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "${entry.pageIndex + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = rc.faint
                            )
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ControlsIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    val rc = readerColors
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, rc.line, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Icon(icon, contentDescription = description, tint = rc.ink, modifier = Modifier.width(18.dp).height(18.dp))
    }
}

/**
 * 阅读页专用的弹出菜单容器。
 *
 * 菜单底色必须与控件栏**同源**。阅读器背景是用户选的（很可能是纯黑），
 * 而应用主题可能是浅色的 —— 直接用 Material 默认的 surface，就会得到
 * 「黑色阅读器上弹出一张白纸」，同时菜单里的字用的是 `rc.ink`（浅色），
 * 两者一撞，字几乎看不见。所以这里把容器色显式钉成 `rc.bar`，
 * 并补一圈 `rc.line` 描边，让菜单和底下的控件栏看起来是同一层。
 */
@Composable
private fun ReaderDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val rc = readerColors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = rc.bar,
        border = BorderStroke(1.dp, rc.line),
        content = content
    )
}

@Composable
private fun ControlsOverflowMenu(
    state: ReaderUiState,
    onSetRenderMode: (RenderMode) -> Unit,
    onSetScreenOrientation: (ScreenOrientation) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetShowPageNumber: (Boolean) -> Unit,
    onSetVolumeKeyPaging: (Boolean) -> Unit,
    onSetReaderBackground: (ReaderBackground) -> Unit,
    onOpenBrightness: () -> Unit
) {
    val rc = readerColors
    var open by remember { mutableStateOf(false) }
    var submenu by remember { mutableStateOf(Submenu.NONE) }

    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(3.dp))
                .border(1.dp, rc.line, RoundedCornerShape(3.dp))
                .clickable { open = true }
                .padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
            Icon(
                GazeIcons.Overflow,
                contentDescription = "更多",
                tint = rc.ink,
                modifier = Modifier.width(18.dp).height(18.dp)
            )
        }

        ReaderDropdownMenu(expanded = open, onDismissRequest = { open = false; submenu = Submenu.NONE }) {
            when (submenu) {
                Submenu.NONE -> {
                    MenuItem("屏幕旋转 · ${state.screenOrientation.label}", false) { submenu = Submenu.ORIENTATION }
                    MenuItem(
                        "亮度 · ${if (state.brightnessFollowSystem) "跟随系统" else "${(state.brightness * 100).toInt()}%"}",
                        false
                    ) { onOpenBrightness(); open = false }
                    MenuItem("渲染模式 · ${state.renderMode.label}", false) { submenu = Submenu.RENDER }
                    MenuItem("阅读器背景 · ${state.background.label}", false) { submenu = Submenu.BACKGROUND }
                    HorizontalDivider(color = rc.line)
                    MenuItem("音量键翻页", state.volumeKeyPaging) { onSetVolumeKeyPaging(!state.volumeKeyPaging) }
                    MenuItem("屏幕常亮", state.keepScreenOn) { onSetKeepScreenOn(!state.keepScreenOn) }
                    MenuItem("显示页码", state.showPageNumber) { onSetShowPageNumber(!state.showPageNumber) }
                }

                Submenu.ORIENTATION -> {
                    ScreenOrientation.entries.forEach { o ->
                        MenuItem(o.label, o == state.screenOrientation) {
                            onSetScreenOrientation(o)
                            submenu = Submenu.NONE
                        }
                    }
                }

                Submenu.RENDER -> {
                    RenderMode.entries.forEach { r ->
                        MenuItem(r.label, r == state.renderMode) {
                            onSetRenderMode(r)
                            submenu = Submenu.NONE
                        }
                    }
                }

                Submenu.BACKGROUND -> {
                    ReaderBackground.entries.forEach { b ->
                        MenuItem(b.label, b == state.background) {
                            onSetReaderBackground(b)
                            submenu = Submenu.NONE
                        }
                    }
                }
            }
        }
    }
}

private enum class Submenu { NONE, ORIENTATION, RENDER, BACKGROUND }

@Composable
private fun MenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    val rc = readerColors
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (checked) rc.ink else rc.faint
            )
        },
        trailingIcon = {
            if (checked) Text("✓", color = rc.ink, style = MaterialTheme.typography.bodySmall)
        },
        onClick = onClick
    )
}

@Composable
private fun ControlsMenu(
    label: String,
    onFollowDefault: (() -> Unit)? = null,
    items: @Composable (dismiss: () -> Unit) -> Unit
) {
    val rc = readerColors
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(3.dp))
                .border(1.dp, rc.line, RoundedCornerShape(3.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = rc.ink
            )
        }
        ReaderDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items { open = false }
            // 出口：清掉这本漫画的独立设置，重新跟随全局默认。
            // 没有它的话，在阅读页手滑改过一次，全局默认就永远管不到这本了。
            if (onFollowDefault != null) {
                HorizontalDivider(color = rc.line)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "跟随默认设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = rc.faint
                        )
                    },
                    onClick = {
                        onFollowDefault()
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * 亮度调节。
 *
 * 默认跟随系统；关掉之后才用滑块 —— 直接把「跟随系统」做成一个开关，
 * 比让用户在 0~100% 里手动复刻系统亮度要诚实得多。
 */
@Composable
private fun BrightnessDialog(
    followSystem: Boolean,
    value: Float,
    onSetFollowSystem: (Boolean) -> Unit,
    onSetValue: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("屏幕亮度") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "跟随系统",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = followSystem, onCheckedChange = onSetFollowSystem)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (followSystem) "当前使用系统亮度" else "自定义亮度 ${(value * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = value,
                    onValueChange = {
                        if (followSystem) onSetFollowSystem(false)
                        onSetValue(it)
                    },
                    valueRange = 0.05f..1f
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

// ==================================================================== 工具

/** Compose 里拿 Activity：Context 可能是 ContextWrapper，要循环 baseContext 往上找。 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
