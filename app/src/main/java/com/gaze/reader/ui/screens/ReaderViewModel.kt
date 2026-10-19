package com.gaze.reader.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gaze.reader.archive.PageProvider
import com.gaze.reader.archive.PageProviderFactory
import com.gaze.reader.archive.TocEntry
import com.gaze.reader.archive.describeOpenError
import com.gaze.reader.core.ArchiveFormat
import com.gaze.reader.core.FitMode
import com.gaze.reader.core.ReadMode
import com.gaze.reader.core.ReaderBackground
import com.gaze.reader.core.RenderMode
import com.gaze.reader.core.ScreenOrientation
import com.gaze.reader.data.AppContainer
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.ReadingSessionEntity
import com.gaze.reader.image.PageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class ReaderUiState(
    val comic: ComicEntity? = null,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val mode: ReadMode = ReadMode.SINGLE,
    val fitMode: FitMode = FitMode.SCREEN,
    val renderMode: RenderMode = RenderMode.OPTIMIZED,
    val background: ReaderBackground = ReaderBackground.BLACK,
    val showControls: Boolean = true,
    val showPageNumber: Boolean = true,
    val keepScreenOn: Boolean = true,
    val screenOrientation: ScreenOrientation = ScreenOrientation.SYSTEM,
    val brightnessFollowSystem: Boolean = true,
    val brightness: Float = 0.5f,
    val volumeKeyPaging: Boolean = false,
    /** 点击翻页：左三分之一上一页、右三分之一下一页、中间切换控件栏。 */
    val tapToPage: Boolean = true,
    val loading: Boolean = true,
    val error: String? = null,
    /** 目录。PDF 恒为空（系统渲染器不暴露大纲），EPUB 从 nav / ncx 里读。 */
    val toc: List<TocEntry> = emptyList(),
    /** 展示用格式名，例如 "PDF" / "EPUB" / "ZIP"。 */
    val formatLabel: String = ""
) {
    /** 双页模式下 pager 的页数（每页承载 1~2 张漫画页）。 */
    val pagerCount: Int
        get() = when (mode) {
            ReadMode.DUAL -> if (pageCount % 2 == 1) 1 + (pageCount - 1) / 2 else pageCount / 2
            else -> pageCount
        }

    /** 当前页对应的 pager 下标。 */
    val pagerIndex: Int
        get() = if (mode == ReadMode.DUAL) dualPagerIndexFor(currentPage, pageCount) else currentPage
}

/**
 * 阅读会话。
 *
 * 关键设计：
 * 1. **书只打开一次**，翻页时复用同一个 [PageProvider] ——
 *    每翻一页重开一次文件会把「按需读取」的优势全部抵消。
 *    至于「第 N 页怎么变成位图」，那是 provider 内部的事：归档解图、PDF 光栅化、
 *    EPUB 取图 / 排字，阅读器一概不关心。
 * 2. **位图用字节数计量的 LRU 缓存**（见 [bitmaps]）。
 *    漫画页动辄几 MB，无上限缓存必然 OOM；按「页数」计量又约束不住内存。
 * 3. **读取串行、解码并行** —— 归档库 / `PdfRenderer` 都不是线程安全的，
 *    但**解码本身**是。串行化已经下沉到各 provider 内部，
 *    这里只保留 [decodeLimiter] 控制并发度，避免「滑到哪都要等前面全部解完」。
 * 4. **进度与阅读偏好分开落库**：翻页只写页码/百分比；模式/适配
 *    只在用户显式改动时写。否则全局默认对任何读过的漫画都会永久失效。
 * 5. **清晰度优先的分档解码**（见 [loadPage] / [loadRegion]）：
 *    整页解码的采样率永远不低于显示尺寸；一旦「图像在屏幕上占的像素」
 *    超过视口（用户放大、或图本身比屏幕大），就改成**按可见区域解码**，
 *    每块按屏幕实际像素数解 —— 分块只为省内存，不为降分辨率。
 */
class ReaderViewModel(
    private val container: AppContainer,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val comicId: Long = savedStateHandle.get<String>("comicId")?.toLongOrNull() ?: -1L

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var provider: PageProvider? = null

    /**
     * 解码后的位图缓存。
     *
     * 按**字节数**而不是页数计量：一页可能是 4MB 的 ARGB_8888，也可能是
     * 20MB 的整张长图，按页数算根本约束不住内存。上限取堆的 1/6，
     * 给解码过程中的临时分配留足空间。
     *
     * 键是「档位 + 页 + 目标尺寸」——同一页在不同缩放档位下是**不同**的位图，
     * 混用同一个键会让「放大后拿到的还是低分辨率那张」。
     *
     * 不做 `entryRemoved` 里 recycle() —— 被淘汰的位图可能正在被绘制，
     * 立刻回收会直接崩；交给 GC 更安全。
     */
    private val bitmaps = object : LruCache<String, Bitmap>(bitmapCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 解码限流：最多 2 路并发，兼顾响应速度与峰值内存。 */
    private val decodeLimiter = Semaphore(2)

    private var saveJob: Job? = null

    // ------------------------------------------------------------ 阅读统计

    /** 本次会话的起点。ViewModel 与一次阅读页停留同生命周期，所以在这里取时间是对的。 */
    private val sessionStartedAt: Long = System.currentTimeMillis()

    /** 会话开始时的页码；-1 表示还没确定（书还没打开成功）。 */
    private var sessionStartPage: Int = -1

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val comic = container.comicRepository.getById(comicId)
        if (comic == null) {
            _state.update { it.copy(loading = false, error = "找不到这本漫画") }
            return
        }
        val settings = container.settingsRepository.current()

        _state.update {
            it.copy(
                comic = comic,
                pageCount = comic.pageCount,
                currentPage = comic.currentPage.coerceIn(0, (comic.pageCount - 1).coerceAtLeast(0)),
                // 每本漫画可以独立保存阅读模式 / 适配；没存过就跟随全局默认
                mode = ReadMode.of(comic.readMode ?: settings.defaultReadMode.key),
                fitMode = FitMode.of(comic.fitMode ?: settings.defaultFitMode.key),
                renderMode = settings.renderMode,
                background = settings.readerBackground,
                showPageNumber = settings.showPageNumber,
                keepScreenOn = settings.keepScreenOn,
                screenOrientation = settings.screenOrientation,
                brightnessFollowSystem = settings.brightnessFollowSystem,
                brightness = settings.brightness,
                volumeKeyPaging = settings.volumeKeyPaging,
                tapToPage = settings.tapToPage
            )
        }

        // 页数与目录**必须在 IO 线程上取**。
        //
        // 对归档容器它们只是读个中央目录，但对 EPUB 就是实打实的重活：
        // `pageCount` 要遍历 spine 的每一章，纯文字章节还得真排一次版才能
        // 知道一章切成几页；`toc` 要解 nav.xhtml / toc.ncx。
        // 放在 `_state.update` 里读就等于在主线程上给整本书建索引 —— 直接 ANR。
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                val format = ArchiveFormat.of(comic.fileName)
                val opened = PageProviderFactory.open(container.appContext, Uri.parse(comic.uri), format)
                // EPUB 的纯文字章节要跟着阅读器背景走（黑底黑字等于没字）。
                // 图片与 PDF 会忽略它。
                val bg = settings.readerBackground.argb
                opened.setTextColors(bg, textColorOn(bg))
                Opened(opened, opened.pageCount, opened.toc)
            }
        }

        if (opened.isFailure) {
            _state.update {
                it.copy(
                    loading = false,
                    error = describeOpenFailure(opened.exceptionOrNull(), comic.fileName)
                )
            }
            return
        }

        val result = opened.getOrNull() ?: return
        provider = result.provider
        sessionStartPage = _state.value.currentPage
        _state.update {
            it.copy(
                pageCount = result.pageCount,
                loading = false,
                formatLabel = result.provider.formatLabel,
                toc = result.toc
            )
        }
    }

    /** [open] 的一次性结果 —— 把「贵」的三个值在 IO 线程上一次取齐。 */
    private class Opened(
        val provider: PageProvider,
        val pageCount: Int,
        val toc: List<TocEntry>
    )

    // ------------------------------------------------------------ 页面加载

    /**
     * 取某一页的原始尺寸。只读元信息，不解像素，可以放心在首帧调用。
     *
     * 连续纵向模式下每页都要先知道自己的长宽比，才能把占位高度摆对 ——
     * 高度不对，滚动条与可视区域的计算就全错。
     */
    suspend fun loadPageSize(index: Int): PageSize? {
        if (index < 0) return null
        val current = provider ?: return null
        return withContext(Dispatchers.IO) { runCatching { current.sizeOf(index) }.getOrNull() }
    }

    /**
     * 整页解码 —— 用于「整张图在屏幕上的像素不超过视口」的常规阅读。
     *
     * 采样率保证解码结果不小于请求尺寸（见 [PageDecoder.sampleSizeFor]），
     * 所以这里永远不会产生「低分辨率位图被放大」的模糊。
     */
    suspend fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (index < 0) return null
        val full = _state.value.renderMode == RenderMode.TRADITIONAL
        val key = fitKey(index, reqWidth, reqHeight, full)
        bitmaps.get(key)?.let { return it }

        val current = provider ?: return null

        // 解码放到锁外，并限流 —— 这是「翻到哪都能立刻出图」的关键
        val bitmap = decodeLimiter.withPermit {
            withContext(Dispatchers.IO) {
                runCatching { current.decode(index, reqWidth, reqHeight, full) }.getOrNull()
            }
        }
        if (bitmap != null) bitmaps.put(key, bitmap)
        return bitmap
    }

    /**
     * 区域解码 —— 放大查看、超长条漫、超高分辨率扫描图都走这条。
     *
     * @param region 要解的**原图坐标**区域。
     * @param outWidth / [outHeight] 这一块在屏幕上的像素尺寸，解码分辨率按它算。
     *
     * 这是「分块只为省内存」的落点：块本身按屏幕像素解，拼起来就是原图，
     * 而任何单块占用的内存都不会超过视口大小。
     */
    suspend fun loadRegion(
        index: Int,
        region: Rect,
        outWidth: Int,
        outHeight: Int
    ): Bitmap? {
        if (index < 0 || region.isEmpty) return null
        val key = regionKey(index, region, outWidth, outHeight)
        bitmaps.get(key)?.let { return it }

        val current = provider ?: return null

        val bitmap = decodeLimiter.withPermit {
            withContext(Dispatchers.IO) {
                runCatching { current.decodeRegion(index, region, outWidth, outHeight) }.getOrNull()
            }
        }
        if (bitmap != null) bitmaps.put(key, bitmap)
        return bitmap
    }

    private fun fitKey(index: Int, w: Int, h: Int, full: Boolean): String =
        if (full) "F:$index" else "f:$index:$w:$h"

    private fun regionKey(index: Int, region: Rect, outWidth: Int, outHeight: Int): String =
        "r:$index:${region.left},${region.top},${region.right},${region.bottom}:$outWidth:$outHeight"

    // ------------------------------------------------------------ 翻页

    fun onPageChanged(page: Int) {
        if (page == _state.value.currentPage) return
        _state.update { it.copy(currentPage = page) }
        scheduleSave()
    }

    fun nextPage() {
        val s = _state.value
        if (s.currentPage < s.pageCount - 1) onPageChanged(s.currentPage + 1)
    }

    fun previousPage() {
        val s = _state.value
        if (s.currentPage > 0) onPageChanged(s.currentPage - 1)
    }

    fun goToPage(page: Int) {
        val s = _state.value
        onPageChanged(page.coerceIn(0, (s.pageCount - 1).coerceAtLeast(0)))
    }

    // ------------------------------------------------------------ 阅读设置

    fun setMode(mode: ReadMode) {
        if (mode == _state.value.mode) return
        _state.update { it.copy(mode = mode) }
        savePreference { container.comicRepository.setReadMode(comicId, mode.key) }
    }

    fun setFitMode(fitMode: FitMode) {
        if (fitMode == _state.value.fitMode) return
        _state.update { it.copy(fitMode = fitMode) }
        savePreference { container.comicRepository.setFitMode(comicId, fitMode.key) }
    }

    // 下面几项都是**全局阅读偏好**：在阅读页改一次，以后打开别的漫画也生效。
    // 它们不是「这本书的设置」，所以不写进 comics 表，而是写进设置。

    fun setRenderMode(value: RenderMode) {
        if (value == _state.value.renderMode) return
        _state.update { it.copy(renderMode = value) }
        viewModelScope.launch { container.settingsRepository.setRenderMode(value) }
    }

    fun setScreenOrientation(value: ScreenOrientation) {
        if (value == _state.value.screenOrientation) return
        _state.update { it.copy(screenOrientation = value) }
        viewModelScope.launch { container.settingsRepository.setScreenOrientation(value) }
    }

    fun setBrightnessFollowSystem(follow: Boolean) {
        if (follow == _state.value.brightnessFollowSystem) return
        _state.update { it.copy(brightnessFollowSystem = follow) }
        viewModelScope.launch { container.settingsRepository.setBrightnessFollowSystem(follow) }
    }

    fun setBrightness(value: Float) {
        val clamped = value.coerceIn(0.05f, 1f)
        _state.update { it.copy(brightness = clamped) }
        viewModelScope.launch { container.settingsRepository.setBrightness(clamped) }
    }

    fun setKeepScreenOn(value: Boolean) {
        if (value == _state.value.keepScreenOn) return
        _state.update { it.copy(keepScreenOn = value) }
        viewModelScope.launch { container.settingsRepository.setKeepScreenOn(value) }
    }

    fun setShowPageNumber(value: Boolean) {
        if (value == _state.value.showPageNumber) return
        _state.update { it.copy(showPageNumber = value) }
        viewModelScope.launch { container.settingsRepository.setShowPageNumber(value) }
    }

    /**
     * 阅读器背景。
     *
     * 它是全局阅读偏好（不是「这本书的」），所以在阅读页改一次，
     * 以后打开别的漫画也生效 —— 与设置页「阅读行为」里的那一项是同一个值。
     *
     * 顺带把新配色推给 provider：EPUB 的纯文字章节是**现排成位图**的，
     * 颜色在建版时烘进去了，不重排就会一直是旧底色（黑底黑字）。
     */
    fun setReaderBackground(value: ReaderBackground) {
        if (value == _state.value.background) return
        _state.update { it.copy(background = value) }
        val bg = value.argb
        val fg = textColorOn(bg)
        val current = provider
        if (current != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { current.setTextColors(bg, fg) }
                // 文字页的位图是按旧颜色画的，缓存里的必须作废
                withContext(Dispatchers.Main) { bitmaps.evictAll() }
            }
        }
        viewModelScope.launch { container.settingsRepository.setReaderBackground(value) }
    }

    fun setVolumeKeyPaging(value: Boolean) {
        if (value == _state.value.volumeKeyPaging) return
        _state.update { it.copy(volumeKeyPaging = value) }
        viewModelScope.launch { container.settingsRepository.setVolumeKeyPaging(value) }
    }

    fun setTapToPage(value: Boolean) {
        if (value == _state.value.tapToPage) return
        _state.update { it.copy(tapToPage = value) }
        viewModelScope.launch { container.settingsRepository.setTapToPage(value) }
    }

    /**
     * 放弃这本漫画的独立设置，重新跟随设置页里的全局默认。
     *
     * 必须有这个出口：否则用户在阅读页手滑改过一次模式，
     * 这本漫画就永远锁死在那个值上，全局默认再也管不到它。
     */
    fun followGlobalDefaults() {
        if (comicId <= 0L) return
        viewModelScope.launch {
            container.comicRepository.clearReadingPrefs(comicId)
            val settings = container.settingsRepository.current()
            _state.update {
                it.copy(
                    mode = settings.defaultReadMode,
                    fitMode = settings.defaultFitMode
                )
            }
        }
    }

    fun toggleControls() {
        _state.update { it.copy(showControls = !it.showControls) }
    }

    fun setControlsVisible(visible: Boolean) {
        _state.update { it.copy(showControls = visible) }
    }

    // ------------------------------------------------------------ 落库

    /** 阅读偏好：只在用户显式改动时写，和翻页进度是两条独立的路径。 */
    private fun savePreference(block: suspend () -> Unit) {
        if (comicId <= 0L) return
        viewModelScope.launch { runCatching { block() } }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            // 连续翻页时只在停下来的那一刻写一次
            delay(700)
            persist()
        }
    }

    /**
     * 只落「读到哪儿」。
     *
     * **不要**在这里写 readMode / fitMode —— v1.0.0 把它们一起写，
     * 结果每本读过的漫画都被固化了独立偏好，设置页里的「默认阅读模式」
     * 从此对它们全部失效。
     */
    suspend fun persist() {
        val s = _state.value
        val comic = s.comic ?: return
        val progress = if (s.pageCount > 0) (s.currentPage + 1).toFloat() / s.pageCount else 0f
        container.comicRepository.updateReadingPosition(
            id = comic.id,
            page = s.currentPage,
            progress = progress
        )
    }

    override fun onCleared() {
        super.onCleared()
        // 退出阅读页时补一次进度，避免「刚翻两页就返回」丢失位置
        val s = _state.value
        val comic = s.comic
        if (comic != null) {
            val progress = if (s.pageCount > 0) (s.currentPage + 1).toFloat() / s.pageCount else 0f
            val now = System.currentTimeMillis()
            val startPage = if (sessionStartPage >= 0) sessionStartPage else s.currentPage
            kotlinx.coroutines.runBlocking {
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching {
                        container.comicRepository.updateReadingPosition(
                            id = comic.id,
                            page = s.currentPage,
                            progress = progress
                        )
                    }
                    // 阅读统计：一段停留落一条会话。过短的会话由 Repository 自己丢弃，
                    // 这里不做判断 —— 阈值属于统计口径，不该散落在调用方。
                    runCatching {
                        container.statsRepository.record(
                            ReadingSessionEntity(
                                comicId = comic.id,
                                startedAt = sessionStartedAt,
                                endedAt = now,
                                durationMs = now - sessionStartedAt,
                                startPage = startPage,
                                endPage = s.currentPage,
                                pagesRead = kotlin.math.abs(s.currentPage - startPage)
                            )
                        )
                    }
                }
            }
        }
        runCatching { provider?.close() }
        provider = null
        bitmaps.evictAll()
    }
}

/** 位图缓存上限：堆的 1/6，夹在 32MB ~ 192MB 之间。 */
private fun bitmapCacheBytes(): Int {
    val maxHeap = Runtime.getRuntime().maxMemory()
    return (maxHeap / 6L).coerceIn(32L * 1024 * 1024, 192L * 1024 * 1024).toInt()
}

/**
 * 阅读器背景上该用什么字色。
 *
 * 与阅读页控件的取色同一套判据（背景亮度过半用深色字），
 * 这样 EPUB 的文字章节和上下控制栏看起来是同一套配色。
 */
private fun textColorOn(background: Int): Int {
    val r = (background shr 16) and 0xFF
    val g = (background shr 8) and 0xFF
    val b = background and 0xFF
    // 感知亮度（ITU-R BT.601），比简单平均更贴近人眼
    val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (luminance > 0.5) Color.BLACK else Color.WHITE
}

/** 当前页 -> 双页 pager 下标。 */
private fun dualPagerIndexFor(page: Int, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    return if (pageCount % 2 == 1) {
        if (page <= 0) 0 else 1 + (page - 1) / 2
    } else {
        page / 2
    }
}

/**
 * 打开失败的完整提示。
 *
 * 原因文案由 [describeOpenError] 统一给出（阅读页与健康检查共用一套判断），
 * 这里只负责把「是哪个文件」补上 —— 用户书架里可能有好几个同名文件。
 */
private fun describeOpenFailure(t: Throwable?, fileName: String): String =
    "${describeOpenError(t)}。\n\n文件：$fileName"

/** 双页模式下，第 [pagerIndex] 屏承载哪几页。 */
fun dualPageRange(pagerIndex: Int, pageCount: Int): IntRange {
    if (pageCount <= 0) return IntRange.EMPTY
    val odd = pageCount % 2 == 1
    val start = if (odd) {
        if (pagerIndex <= 0) 0 else 1 + (pagerIndex - 1) * 2
    } else {
        pagerIndex * 2
    }
    val end = (start + 1).coerceAtMost(pageCount - 1)
    return start..end
}
