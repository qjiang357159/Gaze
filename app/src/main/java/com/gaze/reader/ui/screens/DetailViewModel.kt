package com.gaze.reader.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gaze.reader.data.AppContainer
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.TagEntity
import com.gaze.reader.data.db.TagWithCount
import com.gaze.reader.scan.CoverEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailViewModel(
    private val container: AppContainer,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val comicId: Long = savedStateHandle.get<String>("comicId")?.toLongOrNull() ?: -1L

    val comic: StateFlow<ComicEntity?> = container.comicRepository.observeById(comicId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tags: StateFlow<List<TagEntity>> = container.tagRepository.observeTagsOfComic(comicId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTags: StateFlow<List<TagWithCount>> = container.tagRepository.observeAllWithCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** 已移除、正处于可撤销窗口内。 */
    private val _removed = MutableStateFlow(false)
    val removed: StateFlow<Boolean> = _removed

    /** 移除前的完整快照 —— 撤销时按原样写回。 */
    private var removedSnapshot: ComicEntity? = null
    private var removedTagIds: List<Long> = emptyList()

    fun toggleFavorite() {
        viewModelScope.launch { container.comicRepository.toggleFavorite(comicId) }
    }

    /** 标签编辑器的保存：整体替换，内部做差集。 */
    fun setTags(tagIds: Collection<Long>) {
        viewModelScope.launch { container.tagRepository.setTagsOf(comicId, tagIds) }
    }

    fun createAndAttach(name: String) {
        viewModelScope.launch {
            val tag = container.tagRepository.create(name) ?: return@launch
            val current = container.tagRepository.tagIdsOfComic(comicId).toMutableSet()
            current += tag.id
            container.tagRepository.setTagsOf(comicId, current)
        }
    }

    fun rename(title: String, author: String) {
        viewModelScope.launch {
            val trimmedTitle = title.trim()
            if (trimmedTitle.isNotEmpty()) container.comicRepository.setTitle(comicId, trimmedTitle)
            container.comicRepository.setAuthor(comicId, author.trim().ifBlank { null })
            _message.value = "已更新书名与作者（重新扫描不会覆盖）"
        }
    }

    /**
     * 扩展元数据：系列 / 卷号 / 备注。
     *
     * 与书名作者不同，这些字段**本来就没有自动来源**，所以不需要 manual 标志位 ——
     * 扫描回写永远不会碰它们。
     */
    fun setMetadata(series: String?, volume: Int?, note: String?) {
        viewModelScope.launch {
            container.comicRepository.setMetadata(comicId, series, volume, note)
            _message.value = "已更新详细信息"
        }
    }

    fun regenerateCover() {
        viewModelScope.launch {
            val current = comic.value ?: return@launch
            _message.value = "正在重新生成封面…"
            val path = container.coverGenerator.generateOne(current)
            _message.value = if (path != null) "封面已更新" else "封面生成失败"
        }
    }

    // ------------------------------------------------------------ 换封面

    /**
     * 选页会话。整个选页面板共用这一个 —— 每滑一屏重开一次归档，
     * 大压缩包会卡到没法用。
     */
    private var coverSession: CoverEditor.Session? = null

    /** 选页面板是否可见。**由 ViewModel 管**，因为打开它要先在后台开归档。 */
    private val _coverPickerOpen = MutableStateFlow(false)
    val coverPickerOpen: StateFlow<Boolean> = _coverPickerOpen

    /**
     * 打开选页面板。
     *
     * 开归档是**阻塞 IO**（归档要读中央目录、EPUB 要解析 OPF 清单），
     * 直接在点击回调里做会让界面卡一下，几千页的大包更明显。
     * 所以这里在后台开，开好了再把面板立起来。
     */
    fun openCoverPicker() {
        viewModelScope.launch {
            closeCoverPicker()
            val current = comic.value ?: return@launch
            val session = withContext(Dispatchers.IO) {
                container.coverEditor.openSession(current)
            }
            if (session == null) {
                _message.value = "打不开这个文件，可能已经失效了"
            } else {
                coverSession = session
                _coverPickerOpen.value = true
            }
        }
    }

    fun closeCoverPicker() {
        coverSession?.close()
        coverSession = null
        _coverPickerOpen.value = false
    }

    /** 取一页的缩略图。会话没开（或已关）时返回 null。 */
    suspend fun coverThumb(page: Int): Bitmap? = coverSession?.thumb(page)

    fun applyCoverFromPage(page: Int) {
        val session = coverSession ?: return
        viewModelScope.launch {
            _message.value = "正在生成封面…"
            val path = session.applyPage(page)
            _message.value = if (path != null) "封面已更新" else "这一页没法用作封面"
            closeCoverPicker()
        }
    }

    fun applyCoverFromImage(uri: Uri) {
        viewModelScope.launch {
            _message.value = "正在生成封面…"
            val path = container.coverEditor.applyImage(comicId, uri)
            _message.value = if (path != null) "封面已更新" else "这个文件不是图片，封面没有改动"
            closeCoverPicker()
        }
    }

    fun markMissing(missing: Boolean) {
        viewModelScope.launch {
            container.comicRepository.setMissing(comicId, missing)
            _message.value = if (missing) "已标记为失效" else "已恢复"
        }
    }

    /**
     * 从书架移除（可撤销）。
     *
     * 先存快照再删。**不在这里做倒计时** —— 倒计时属于界面行为，
     * 放在 ViewModel 里会和「页面被销毁」互相打架；界面负责 5 秒后返回，
     * ViewModel 只负责「删了」和「撤回来」两件事。
     */
    fun remove() {
        viewModelScope.launch {
            val current = comic.value ?: return@launch
            removedSnapshot = current
            removedTagIds = container.tagRepository.tagIdsOfComic(comicId)
            container.comicRepository.delete(listOf(comicId))
            _removed.value = true
        }
    }

    fun undoRemove() {
        val snapshot = removedSnapshot ?: return
        viewModelScope.launch {
            container.comicRepository.upsert(snapshot)
            if (removedTagIds.isNotEmpty()) {
                container.tagRepository.addTo(listOf(snapshot.id), removedTagIds)
            }
            removedSnapshot = null
            removedTagIds = emptyList()
            _removed.value = false
            _message.value = "已恢复"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 选页会话占着归档通道，ViewModel 销毁时必须放掉。 */
    override fun onCleared() {
        closeCoverPicker()
        super.onCleared()
    }
}
