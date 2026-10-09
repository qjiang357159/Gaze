package com.gaze.reader.scan

import android.content.Context
import android.net.Uri
import com.gaze.reader.archive.PageProviderFactory
import com.gaze.reader.core.ArchiveFormat
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.repository.ComicRepository
import com.gaze.reader.image.CoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

data class CoverProgress(
    val processed: Int,
    val total: Int,
    val currentName: String
)

/**
 * 后台封面生成（文档第十七章 / 第二十六章）。
 *
 * 刻意与扫描**分开**：
 * ```
 * 打开 App -> 立即读取数据库 -> 立即显示书架 -> 后台增量扫描 -> 后台更新封面
 * ```
 * 生成封面要解压每一本的第一页，是扫描链路里最慢的一环。把它从「入库」里拆出来，
 * 用户才能「一打开就看到书架」，而不是盯着进度条等几千个封面。
 */
class CoverGenerator(
    private val context: Context,
    private val comics: ComicRepository,
    private val store: CoverStore
) {

    fun generateMissing(): Flow<CoverProgress> = flow {
        val pending = comics.getAllPresent().filter { needsCover(it) }
        if (pending.isEmpty()) return@flow

        var done = 0
        for (comic in pending) {
            currentCoroutineContext().ensureActive()
            generateFor(comic)
            done++
            emit(CoverProgress(processed = done, total = pending.size, currentName = comic.title))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 单独为某一本生成（详情页「重新生成封面」用）。
     *
     * 里面自带 `withContext(Dispatchers.IO)`：详情页是从 `viewModelScope` 直接调的，
     * 而这里要开文件、解一页图、落一次盘 —— 全是阻塞调用。EPUB 更重，
     * 光是构造 provider 就要读 container.xml 和 OPF 清单。
     */
    suspend fun generateOne(comic: ComicEntity): String? = withContext(Dispatchers.IO) {
        generateFor(comic)
    }

    private fun needsCover(comic: ComicEntity): Boolean {
        val path = comic.coverPath
        if (path.isNullOrBlank()) return true
        return !File(path).exists()
    }

    private suspend fun generateFor(comic: ComicEntity): String? {
        val uri = runCatching { Uri.parse(comic.uri) }.getOrNull() ?: return null
        // 走统一的 provider 入口：封面从哪来是格式自己的事
        // （归档解第一张图、PDF 渲第一页、EPUB 找 properties="cover-image" 那条），
        // 封面生成器不该知道这些差别。
        val bitmap = try {
            PageProviderFactory.open(context, uri, ArchiveFormat.of(comic.fileName)).use { provider ->
                provider.coverBitmap(COVER_WIDTH)
            }
        } catch (t: Throwable) {
            null
        } ?: return null

        val path = try {
            store.save(comic.id, bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        } ?: return null

        comics.setCoverPath(comic.id, path)
        return path
    }

    private companion object {
        /** 与 [CoverStore] 内部的落盘宽度保持一致，多解出来的像素纯属浪费。 */
        const val COVER_WIDTH = 480
    }
}
