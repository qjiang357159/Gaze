package com.gaze.reader.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gaze.reader.core.LibraryFilter
import com.gaze.reader.core.SortOrder
import com.gaze.reader.data.AppContainer
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.TagEntity
import com.gaze.reader.data.db.TagWithCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagsViewModel(private val container: AppContainer) : ViewModel() {

    val tags: StateFlow<List<TagWithCount>> = container.tagRepository.observeAllWithCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 「收藏」入口右侧的数字。它和标签一样是「一组漫画的入口」，所以放在标签页顶部。 */
    val favoriteCount: StateFlow<Int> = container.comicRepository.observeStats()
        .map { it.favoriteCount }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val message = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = message

    /**
     * 批量管理。
     *
     * 标签长按进入 —— 和书架长按漫画是同一套肌肉记忆，不该为「管理标签」
     * 再发明一个入口。非空即表示处于选择模式。
     */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    fun toggleSelect(id: Long) {
        val current = _selection.value
        _selection.value = if (id in current) current - id else current + id
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectAll() {
        _selection.value = tags.value.mapTo(HashSet()) { it.id }
    }

    /** 批量删除标签。标签本身删掉，漫画与原文件都不受影响。 */
    fun deleteSelection() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.tagRepository.deleteMany(ids)
            _selection.value = emptySet()
            message.value = "已删除 ${ids.size} 个标签（漫画本身不受影响）"
        }
    }

    /** 批量改色。 */
    fun setColorOnSelection(color: Int) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.tagRepository.setColorMany(ids, color)
            _selection.value = emptySet()
            message.value = "已更新 ${ids.size} 个标签的颜色"
        }
    }

    /**
     * 新建标签。
     *
     * [color] 由界面上的取色器决定 —— 之前这里只收名字、颜色交给仓库自动推荐，
     * 于是用户在对话框里挑的颜色**根本没被用上**（挑的和建出来的是两个色）。
     * 颜色是用户明确做出的选择，没有任何理由丢掉它。
     */
    fun createTag(name: String, color: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val tag = container.tagRepository.create(trimmed, color)
            message.value = if (tag != null) "已创建标签「${tag.name}」" else "标签创建失败"
        }
    }

    /** 一次输入多个标签，用空格 / 逗号 / 顿号分隔。 */
    fun createTags(raw: String) {
        val names = raw.split(' ', ',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return
        viewModelScope.launch {
            val created = container.tagRepository.createMany(names)
            message.value = "已创建 ${created.size} 个标签"
        }
    }

    fun renameTag(id: Long, name: String) {
        viewModelScope.launch { container.tagRepository.rename(id, name) }
    }

    fun setColor(id: Long, color: Int) {
        viewModelScope.launch { container.tagRepository.setColor(id, color) }
    }

    /** 长按拖动排序：把整个新顺序一次性写下去。 */
    fun reorder(orderedIds: List<Long>) {
        if (orderedIds.isEmpty()) return
        viewModelScope.launch { container.tagRepository.reorder(orderedIds) }
    }

    fun deleteTag(id: Long, name: String) {
        viewModelScope.launch {
            container.tagRepository.delete(id)
            message.value = "已删除标签「$name」（漫画本身不受影响）"
        }
    }

    fun consumeMessage() {
        message.value = null
    }
}

class TagDetailViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val tagId: Long = savedStateHandle.get<String>("tagId")?.toLongOrNull() ?: -1L

    private val sort = MutableStateFlow(SortOrder.ADDED_DESC)

    val tag: StateFlow<TagEntity?> = flow { emit(container.tagRepository.getById(tagId)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val comics: StateFlow<List<ComicEntity>> = sort
        .flatMapLatest { container.comicRepository.observeByTag(tagId, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(value: SortOrder) {
        sort.value = value
    }

    fun removeTagFrom(comicIds: Collection<Long>) {
        if (comicIds.isEmpty()) return
        viewModelScope.launch { container.tagRepository.removeFrom(comicIds, listOf(tagId)) }
    }
}

/**
 * 收藏页。
 *
 * 它和书架的「收藏」视图共用同一份数据（都是 `favorite = 1`），但**是两个
 * 不同的入口**：书架那个是「换一面墙的排列方式」，这里是「标签体系里的一个
 * 集合」—— 点进去看的是这一组漫画本身，可以单独排序、单独取消收藏。
 */
class FavoritesViewModel(private val container: AppContainer) : ViewModel() {

    private val sortState = MutableStateFlow(SortOrder.ADDED_DESC)
    val sort: StateFlow<SortOrder> = sortState

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val comics: StateFlow<List<ComicEntity>> = sortState
        .flatMapLatest { container.comicRepository.observeLibrary(LibraryFilter.FAVORITE, it, "") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(value: SortOrder) {
        sortState.value = value
    }

    fun removeFavorite(comicId: Long) {
        viewModelScope.launch { container.comicRepository.setFavorite(listOf(comicId), false) }
    }
}
