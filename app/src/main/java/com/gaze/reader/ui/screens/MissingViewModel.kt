package com.gaze.reader.ui.screens

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gaze.reader.data.AppContainer
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.FolderEntity
import com.gaze.reader.data.repository.ComicRepository
import com.gaze.reader.domain.UriDisplay
import com.gaze.reader.scan.RelocateOutcome
import com.gaze.reader.scan.ScanPhase
import com.gaze.reader.scan.ScanProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 媒体匹配页里的一条待定位记录。 */
data class MissingItem(
    val comic: ComicEntity,
    /** 它身上还挂着的标签名 —— 「历史数据还在」的直接证据。 */
    val tagNames: List<String>
) {
    /** 除了「文件找不到」，这条记录身上还有没有值得接回来的东西。 */
    val hasHistory: Boolean
        get() = comic.progress > 0f || comic.favorite || tagNames.isNotEmpty()
}

/**
 * 一组待定位记录 —— 一个原始文件夹（或「位置未知」）下的全部漫画。
 *
 * 上百本漫画平铺成一列是没法用的：用户真正想先知道的是「哪个目录整块掉了」，
 * 而不是逐条读一百行标题。所以第一层只列文件夹，点进去才看具体是哪几本。
 */
data class MissingGroup(
    /** `FOLDER:<tree uri>`，或 [UNKNOWN_GROUP]。 */
    val key: String,
    val title: String,
    /** tree uri；「位置未知」组为 null。 */
    val uri: String?,
    /** 这个目录来自恢复的备份、还没重新授权 —— 组内漫画得先授权才可能被扫回来。 */
    val pending: Boolean,
    val items: List<MissingItem>
) {
    val count: Int get() = items.size

    /** 组内「历史数据还在」的条数 —— 组行副标题用它，比单纯报个数有用。 */
    val withHistory: Int get() = items.count { it.hasHistory }
}

/** 媒体匹配页需要的全部数据。 */
data class MissingUiState(
    val groups: List<MissingGroup> = emptyList(),
    /**
     * 备份带回来、本机还没重新授权、**且名下没有待定位漫画**的目录。
     *
     * 有名下漫画的目录已经作为分组出现在列表里了（组内自带「重新授权」入口），
     * 再在顶部重复列一遍只是噪音。
     */
    val orphanFolders: List<FolderEntity> = emptyList()
) {
    val total: Int get() = groups.fold(0) { acc, group -> acc + group.items.size }

    fun group(key: String?): MissingGroup? = key?.let { k -> groups.firstOrNull { it.key == k } }
}

/** 「位置未知」分组 —— 记录里没有文件夹归属时（老备份、或手动指定过文件）落在这里。 */
const val UNKNOWN_GROUP = "FOLDER:__unknown__"

/**
 * 「媒体匹配」页（文档第十五章的下游）。
 *
 * 这一页存在的理由只有一个：**恢复备份之后，找不到对应文件的那些漫画不能丢**。
 * 换机、改目录、重命名、取消授权都会让一批记录指向不存在的文件。
 * 直接删掉最省事，但阅读进度、收藏、标签也就一起没了 ——
 * 那些恰恰是 Gaze 才有的东西，文件本身反而更容易再找回来。
 *
 * 所以这里只做三件事：**列出来、重新指路、确认无误后清掉**。
 */
class MissingViewModel(private val container: AppContainer) : ViewModel() {

    private val comics = container.comicRepository
    private val tags = container.tagRepository
    private val folders = container.folderRepository

    /**
     * 待定位记录，按原始文件夹分组。
     *
     * 组内按「最后阅读时间倒序」排 —— 用户最想先接回来的一定是最近在读的那几本；
     * 组之间按条数倒序，掉得最多的那个目录排最前面。
     */
    val state: StateFlow<MissingUiState> = combine(
        comics.observeMissing(),
        tags.observeAll(),
        tags.observeAllRelations(),
        folders.observeAll()
    ) { missing, tagList, relations, folderList ->
        val nameById = tagList.associate { it.id to it.name }
        val namesByComic = relations.groupBy(
            keySelector = { it.comicId },
            valueTransform = { nameById[it.tagId].orEmpty() }
        )
        val folderByUri = folderList.associateBy { it.uri }

        val buckets = LinkedHashMap<String, MutableList<MissingItem>>()
        missing
            .sortedWith(
                compareByDescending<ComicEntity> { it.lastReadTime }
                    .thenByDescending { it.addedTime }
            )
            .forEach { comic ->
                val uri = comic.folderUri?.takeIf { it.isNotBlank() }
                val key = uri?.let { ComicRepository.FOLDER_PREFIX + it } ?: UNKNOWN_GROUP
                buckets.getOrPut(key) { mutableListOf() }.add(
                    MissingItem(
                        comic = comic,
                        tagNames = namesByComic[comic.id].orEmpty().filter { it.isNotBlank() }
                    )
                )
            }

        val groups = buckets.map { (key, items) ->
            val uri = key.takeIf { it != UNKNOWN_GROUP }
                ?.removePrefix(ComicRepository.FOLDER_PREFIX)
            val folder = uri?.let { folderByUri[it] }
            MissingGroup(
                key = key,
                // 数据库里的显示名最准（用户在设置页看到的就是它），
                // 没有记录时退回从 uri 里抠出来的那一段
                title = folder?.displayName
                    ?: uri?.let { UriDisplay.displayName(it) }
                    ?: "位置未知",
                uri = uri,
                pending = folder?.pending == true,
                items = items
            )
        }.sortedWith(compareByDescending<MissingGroup> { it.count }.thenBy { it.title })

        val grouped = groups.mapNotNullTo(HashSet()) { it.uri }
        MissingUiState(
            groups = groups,
            orphanFolders = folderList.filter { it.pending == true && it.uri !in grouped }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MissingUiState())

    private val _scan = MutableStateFlow<ScanProgress?>(null)
    val scan: StateFlow<ScanProgress?> = _scan

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var scanJob: Job? = null

    /**
     * 把一条记录重新指向用户选中的文件。
     *
     * 校验与写入全在 [com.gaze.reader.scan.MediaRelocator] 里 ——
     * 那里会真正打开一次归档确认能读，而不是只按扩展名放行。
     */
    fun relocate(id: Long, uri: Uri) {
        viewModelScope.launch {
            when (val outcome = container.mediaRelocator.relocate(id, uri)) {
                is RelocateOutcome.Success ->
                    _message.value = "已接回《${outcome.fileName}》（${outcome.pageCount} 页），" +
                        "进度、标签、收藏都还在"
                is RelocateOutcome.Merged ->
                    _message.value = "这个文件本来就在库里（《${outcome.title}》）——" +
                        "已把这条记录的历史数据并了过去，没有留下重复"
                is RelocateOutcome.Failure -> _message.value = outcome.reason
            }
        }
    }

    /**
     * 扫描并自动匹配。
     *
     * 不需要另写一套匹配逻辑 —— 扫描器入库时本来就有「按内容找回」这一环
     * （URI -> 指纹 -> 采样哈希 -> 大小 + 页数），只要这些 missing 记录还在库里，
     * 重扫一遍就能把它们自动接回去；同一份内容同时存在失效与在库两条记录时，
     * 扫描器还会顺手合并成一条（见 `LibraryScanner.mergeMissingTwins`）。
     */
    fun scanAndMatch() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            try {
                container.scanner.scanAll().collect { progress ->
                    _scan.value = progress
                    if (progress.phase == ScanPhase.DONE) {
                        _message.value = when {
                            progress.total == 0 -> "没有可扫描的文件夹 —— 先重新授权目录"
                            progress.updated > 0 || progress.merged > 0 -> buildString {
                                append("扫描完成")
                                if (progress.updated > 0) append("：接回 ${progress.updated} 条记录")
                                if (progress.merged > 0) {
                                    append(if (progress.updated > 0) "，" else "：")
                                    append("合并 ${progress.merged} 条重复记录")
                                }
                            }
                            else -> "扫描完成：没有找到能自动接回的文件，试试手动重新定位"
                        }
                        delay(1_200)
                        _scan.value = null
                    }
                }
                container.coverGenerator.generateMissing().collect { }
            } finally {
                if (_scan.value?.phase != ScanPhase.DONE) _scan.value = null
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _scan.value = null
    }

    /**
     * 用户在本页直接重新授权一个目录。
     *
     * 授权成功后立刻扫一遍 —— 用户点「重新授权」的意图本来就是「把文件找回来」，
     * 让他再自己去点一次「扫描」是多余的一步。
     */
    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            container.folderRepository.add(uri.toString())
            _message.value = "已授权，开始扫描…"
            scanAndMatch()
        }
    }

    /** 从 Gaze 移除这些记录。**不删手机上的文件。** */
    fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            comics.delete(ids)
            _message.value = "已移除 ${ids.size} 条记录（手机上的文件没有动）"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
