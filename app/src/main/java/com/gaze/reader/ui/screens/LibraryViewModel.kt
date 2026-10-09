package com.gaze.reader.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gaze.reader.core.LibraryFilter
import com.gaze.reader.core.LibraryLayout
import com.gaze.reader.core.LibraryView
import com.gaze.reader.core.SortOrder
import com.gaze.reader.data.AppContainer
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.FolderEntity
import com.gaze.reader.data.db.LibraryStats
import com.gaze.reader.data.db.ShelfGroup
import com.gaze.reader.scan.CoverProgress
import com.gaze.reader.scan.ScanPhase
import com.gaze.reader.scan.ScanProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
data class LibraryUiState(
    val view: LibraryView = LibraryView.ALL,
    val comics: List<ComicEntity> = emptyList(),
    val groups: List<ShelfGroup> = emptyList(),
    val stats: LibraryStats? = null,
    val sort: SortOrder = SortOrder.ADDED_DESC,
    val query: String = "",
    val layout: LibraryLayout = LibraryLayout.GRID_2,
    val selected: Set<Long> = emptySet(),
    val folders: List<FolderEntity> = emptyList(),
    val scan: ScanProgress? = null,
    val cover: CoverProgress? = null,
    val message: String? = null,
    /** 继续阅读悬浮窗（可被左滑关掉）。 */
    val continueComic: ComicEntity? = null,
    val continueDismissed: Boolean = false,
    /** 刚移除、还能撤销。 */
    val canUndoRemove: Boolean = false,
    val loading: Boolean = true
) {
    val selectionMode: Boolean get() = selected.isNotEmpty()

    /** 当前可见的漫画（分组视图下把各组摊平）。 */
    val visibleComics: List<ComicEntity>
        get() = if (view == LibraryView.ALL || view == LibraryView.FAVORITE) {
            comics
        } else {
            groups.flatMap { it.comics }
        }

    /** 选中项是否**全部**已收藏 —— 决定批量栏那个按钮显示「收藏」还是「取消收藏」。 */
    val selectedAllFavorite: Boolean
        get() {
            if (selected.isEmpty()) return false
            val byId = visibleComics.associateBy { it.id }
            return selected.all { byId[it]?.favorite == true }
        }
}

/**
 * 书架里**属于当前会话**的状态：搜索词与选中项。
 *
 * 视图 / 排序 / 排版**不在这里** —— 它们是持久化设置（[SettingsRepository]），
 * 唯一数据源放在设置里。v1.2.0 把它们同时存在 ViewModel 内存状态与设置里，
 * 读的时候只读内存状态，于是「改了排版、重启又回到默认」。
 */
private data class LibraryPrefs(
    val query: String = "",
    val selected: Set<Long> = emptySet()
)

private data class LibraryTransient(
    val scan: ScanProgress? = null,
    val cover: CoverProgress? = null,
    val message: String? = null,
    val canUndo: Boolean = false
)

/** 持久化的书架偏好（唯一数据源：设置）。 */
private data class ShelfPrefs(
    val view: LibraryView,
    val sort: SortOrder,
    val layout: LibraryLayout
)

private data class LibraryContent(
    val view: LibraryView,
    val sort: SortOrder,
    val layout: LibraryLayout,
    val query: String,
    val comics: List<ComicEntity>,
    val groups: List<ShelfGroup>
)

/** 被移除漫画的快照 —— 撤销时按原样恢复。 */
private data class RemovedSnapshot(
    val comics: List<ComicEntity>,
    val tagIdsByComic: Map<Long, List<Long>>
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    private val prefs = MutableStateFlow(LibraryPrefs())
    private val transient = MutableStateFlow(LibraryTransient())
    private val continueDismissed = MutableStateFlow(false)

    private var scanJob: Job? = null
    private var undoJob: Job? = null
    private var pendingUndo: RemovedSnapshot? = null

    /**
     * 书架视图 / 排序 / 排版统一由设置持有。
     *
     * 放在设置而不是 ViewModel 内部状态，是为了让它们成为**跨会话持久**的概念：
     * 用户改过排版，重启之后还是那个排版；标签页点「收藏」，回到书架时就是收藏视图。
     */
    private val shelfPrefsFlow: Flow<ShelfPrefs> = container.settingsRepository.settings
        .map { ShelfPrefs(it.libraryView, it.sortOrder, it.libraryLayout) }
        .distinctUntilChanged()

    /**
     * 书架内容。
     *
     * 用 `flatMapLatest` 让「视图 / 排序 / 搜索」变化时**重新订阅**查询，
     * 而不是把全部数据拉到内存里再过滤 —— 后者在几千本的库上会让
     * 每次输入搜索词都触发一次全量重排。
     */
    private val contentFlow = combine(shelfPrefsFlow, prefs) { shelf, p -> shelf to p }
        .distinctUntilChanged()
        .flatMapLatest { (shelf, p) ->
            val query = p.query
            when (shelf.view) {
                LibraryView.ALL -> container.comicRepository
                    .observeLibrary(LibraryFilter.ALL, shelf.sort, query)
                    .map {
                        LibraryContent(shelf.view, shelf.sort, shelf.layout, query, it, emptyList())
                    }

                LibraryView.FAVORITE -> container.comicRepository
                    .observeLibrary(LibraryFilter.FAVORITE, shelf.sort, query)
                    .map {
                        LibraryContent(shelf.view, shelf.sort, shelf.layout, query, it, emptyList())
                    }

                else -> container.comicRepository
                    .observeShelfGroups(shelf.view, shelf.sort, query)
                    .map {
                        LibraryContent(shelf.view, shelf.sort, shelf.layout, query, emptyList(), it)
                    }
            }
        }

    /** 「继续阅读」独立于当前视图 —— 切到标签视图也照样能一键回到上次读的那本。 */
    private val continueFlow = container.comicRepository.observeAll().map { list ->
        list.filter { it.lastReadTime > 0L }.maxByOrNull { it.lastReadTime }
    }

    private val leftFlow = combine(
        contentFlow,
        container.comicRepository.observeStats(),
        prefs
    ) { content, stats, p -> Triple(content, stats, p) }

    private val rightFlow = combine(
        container.folderRepository.observeAll(),
        transient,
        continueFlow,
        continueDismissed
    ) { folders, t, continueComic, dismissed ->
        FoldersAndTransient(folders, t, continueComic, dismissed)
    }

    val state: StateFlow<LibraryUiState> = combine(leftFlow, rightFlow) { left, right ->
        val (content, stats, p) = left
        LibraryUiState(
            view = content.view,
            comics = content.comics,
            groups = content.groups,
            stats = stats,
            sort = content.sort,
            query = content.query,
            layout = content.layout,
            selected = p.selected,
            folders = right.folders,
            scan = right.transient.scan,
            cover = right.transient.cover,
            message = right.transient.message,
            continueComic = right.continueComic,
            continueDismissed = right.dismissed,
            canUndoRemove = right.transient.canUndo,
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private data class FoldersAndTransient(
        val folders: List<FolderEntity>,
        val transient: LibraryTransient,
        val continueComic: ComicEntity?,
        val dismissed: Boolean
    )

    // ------------------------------------------------------------ 视图偏好
    //
    // 三个 setter 都只写设置 —— 内存里不再留一份副本，
    // 于是「读」与「写」永远是同一个来源，不可能出现「写了但读的是旧的」。

    fun setView(value: LibraryView) {
        prefs.value = prefs.value.copy(selected = emptySet())
        viewModelScope.launch { container.settingsRepository.setLibraryView(value) }
    }

    fun setSort(value: SortOrder) {
        viewModelScope.launch { container.settingsRepository.setSortOrder(value) }
    }

    fun setQuery(value: String) {
        // 搜索会改变可见集合，顺手退出选择模式 —— 否则会出现
        // 「选中 5 本，搜完之后只看得见 2 本，删除却删掉 5 本」
        prefs.value = prefs.value.copy(query = value, selected = emptySet())
    }

    fun setLayout(value: LibraryLayout) {
        viewModelScope.launch { container.settingsRepository.setLibraryLayout(value) }
    }

    // ------------------------------------------------------------ 选择模式

    fun toggleSelect(id: Long) {
        val current = prefs.value.selected
        prefs.value = prefs.value.copy(
            selected = if (id in current) current - id else current + id
        )
    }

    fun clearSelection() {
        prefs.value = prefs.value.copy(selected = emptySet())
    }

    fun selectAll() {
        val all = state.value.visibleComics.mapTo(HashSet()) { it.id }
        prefs.value = prefs.value.copy(selected = all)
    }

    // ------------------------------------------------------------ 批量操作

    /**
     * 批量标签：**增删合一**。
     *
     * 一个入口同时回答「加哪些」和「去哪些」—— 用户不必先想清楚是加还是删，
     * 打开面板看当前状态再决定。差集语义照旧，绝不整体覆盖。
     */
    fun applyTagsToSelection(addIds: Collection<Long>, removeIds: Collection<Long>) {
        val ids = prefs.value.selected
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (addIds.isNotEmpty()) container.tagRepository.addTo(ids, addIds)
            if (removeIds.isNotEmpty()) container.tagRepository.removeFrom(ids, removeIds)
            transient.value = transient.value.copy(
                message = "已更新 ${ids.size} 本的标签"
            )
            clearSelection()
        }
    }

    /** 收藏：一次点击同时覆盖「收藏」与「取消收藏」两种意图。 */
    fun toggleFavoriteOnSelection() {
        val ids = prefs.value.selected
        if (ids.isEmpty()) return
        val makeFavorite = !state.value.selectedAllFavorite
        viewModelScope.launch {
            container.comicRepository.setFavorite(ids, makeFavorite)
            transient.value = transient.value.copy(
                message = if (makeFavorite) "已收藏 ${ids.size} 本" else "已取消收藏 ${ids.size} 本"
            )
            clearSelection()
        }
    }

    /**
     * 移除（可撤销）。
     *
     * 先快照整条记录与它的标签关系再删；5 秒内点「撤销」就按原样写回去。
     * 5 秒之后快照释放 —— 不做无限期保留，否则就是「软删除」，
     * 与「移除不删原文件」的语义混在一起会让数据越来越脏。
     */
    fun deleteSelection() {
        val ids = prefs.value.selected
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val comics = container.comicRepository.getByIds(ids)
            val tagsByComic = ids.associateWith { container.tagRepository.tagIdsOfComic(it) }
            container.comicRepository.delete(ids)
            clearSelection()

            pendingUndo = RemovedSnapshot(comics, tagsByComic)
            transient.value = transient.value.copy(
                message = "已从书架移除 ${ids.size} 本（原文件未删除）",
                canUndo = true
            )
            undoJob?.cancel()
            undoJob = viewModelScope.launch {
                delay(UNDO_WINDOW_MS)
                pendingUndo = null
                transient.value = transient.value.copy(message = null, canUndo = false)
            }
        }
    }

    fun undoRemove() {
        val snapshot = pendingUndo ?: return
        undoJob?.cancel()
        pendingUndo = null
        viewModelScope.launch {
            snapshot.comics.forEach { container.comicRepository.upsert(it) }
            snapshot.tagIdsByComic.forEach { (comicId, tagIds) ->
                if (tagIds.isNotEmpty()) container.tagRepository.addTo(listOf(comicId), tagIds)
            }
            transient.value = transient.value.copy(
                message = "已恢复 ${snapshot.comics.size} 本",
                canUndo = false
            )
        }
    }

    /** 书架文件夹换封面（长按指定组内某一本）。 */
    fun setShelfCover(groupKey: String, comicId: Long) {
        viewModelScope.launch {
            container.comicRepository.setShelfCover(groupKey, comicId)
            transient.value = transient.value.copy(message = "已更换书架封面")
        }
    }

    // ------------------------------------------------------------ 扫描

    /**
     * 扫描漫画库。
     *
     * @param silent 后台自动扫描传 true：进度条照常显示，但**不弹完成提示**。
     *   用户没有主动发起扫描，就不该被一条「扫描完成」打断 ——
     *   自动扫描是后台行为，符合文档第十章「不阻塞 UI」的要求。
     */
    fun startScan(silent: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            try {
                container.scanner.scanAll().collect { progress ->
                    when {
                        progress.phase != ScanPhase.DONE ->
                            transient.value = transient.value.copy(scan = progress)

                        silent -> transient.value = transient.value.copy(scan = null)

                        else -> {
                            transient.value = transient.value.copy(scan = progress)
                            transient.value = transient.value.copy(
                                message = buildScanSummary(progress),
                                canUndo = false
                            )
                            delay(1_200)
                            transient.value = transient.value.copy(scan = null)
                        }
                    }
                }
                // 扫描完接着补封面 —— 封面生成最慢，所以放在最后、且不阻塞书架显示
                val limit = container.settingsRepository.current().coverCacheLimitMb
                container.coverGenerator.generateMissing().collect {
                    transient.value = transient.value.copy(cover = it)
                }
                transient.value = transient.value.copy(cover = null)
                container.coverStore.trimToLimit(limit)
            } finally {
                if (transient.value.scan?.phase != ScanPhase.DONE) {
                    transient.value = transient.value.copy(scan = null)
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        transient.value = transient.value.copy(scan = null, cover = null)
    }

    private fun buildScanSummary(progress: ScanProgress): String = buildString {
        append("扫描完成")
        if (progress.added > 0) append("，新增 ${progress.added} 本")
        if (progress.updated > 0) append("，更新 ${progress.updated} 本")
        if (progress.removed > 0) append("，${progress.removed} 本已失效")
        if (progress.added == 0 && progress.updated == 0 && progress.removed == 0) append("，没有变化")
    }

    // ------------------------------------------------------------ 文件夹

    fun addFolder(uri: String) {
        viewModelScope.launch {
            container.folderRepository.add(uri)
            startScan()
        }
    }

    fun removeFolder(uri: String) {
        viewModelScope.launch {
            container.folderRepository.remove(uri)
            transient.value = transient.value.copy(message = "已取消授权（不会删除已导入的漫画）")
        }
    }

    // ------------------------------------------------------------ 其它

    fun consumeMessage() {
        transient.value = transient.value.copy(message = null, canUndo = false)
        pendingUndo = null
    }

    /** 左滑关掉「继续阅读」悬浮窗（本次会话内不再出现）。 */
    fun dismissContinue() {
        continueDismissed.value = true
    }

    private companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}
