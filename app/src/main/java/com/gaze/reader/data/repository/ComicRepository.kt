package com.gaze.reader.data.repository

import com.gaze.reader.core.LibraryFilter
import com.gaze.reader.core.LibraryView
import com.gaze.reader.core.NaturalOrder
import com.gaze.reader.core.SortOrder
import com.gaze.reader.data.db.ComicDao
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.ComicTagEntity
import com.gaze.reader.data.db.LibraryStats
import com.gaze.reader.data.db.ShelfCoverDao
import com.gaze.reader.data.db.ShelfCoverEntity
import com.gaze.reader.data.db.ShelfGroup
import com.gaze.reader.data.db.StatsDao
import com.gaze.reader.data.db.TagDao
import com.gaze.reader.domain.UriDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 漫画仓库。
 *
 * 书架的数据流（筛选 + 搜索 + 排序 + 分组）刻意**放在这里一次算完**，
 * 而不是散在 ViewModel 与各个屏幕里 —— 文档第十三章要求多种排序、
 * 第十二章要求搜索标题 / 作者 / 标签 / 文件名，这两件事都依赖同一份
 * 「漫画 + 标签关系」的快照，拆开做只会出现「列表和搜索结果顺序不一致」。
 */
class ComicRepository(
    private val comicDao: ComicDao,
    private val tagDao: TagDao,
    private val statsDao: StatsDao,
    private val shelfCoverDao: ShelfCoverDao
) {

    fun observeAll(): Flow<List<ComicEntity>> = comicDao.observeAll()
    fun observeById(id: Long): Flow<ComicEntity?> = comicDao.observeById(id)
    fun observeStats(): Flow<LibraryStats> = comicDao.observeStats()
    fun observeMissing(): Flow<List<ComicEntity>> = comicDao.observeMissing()
    fun observeTagsOfComic(comicId: Long) = tagDao.observeTagsOfComic(comicId)

    suspend fun getById(id: Long): ComicEntity? = comicDao.getById(id)
    suspend fun getByIds(ids: Collection<Long>): List<ComicEntity> =
        if (ids.isEmpty()) emptyList() else comicDao.getByIds(ids.toList())
    suspend fun getAll(): List<ComicEntity> = comicDao.getAll()
    suspend fun getAllPresent(): List<ComicEntity> = comicDao.getAllPresent()
    suspend fun getMissing(): List<ComicEntity> = comicDao.getMissing()
    suspend fun countPresent(): Int = comicDao.countPresent()

    /**
     * 书架主数据流（全部 / 收藏两种平铺视图）。
     *
     * 排序全部在内存里做，因为「名称 A-Z」按文档要求是**自然数字排序**
     * （`1.jpg / 2.jpg / 10.jpg`），SQLite 的 `ORDER BY` 做不到这件事。
     * 几千本的排序开销在毫秒级，换来的是三处顺序永远一致。
     */
    fun observeLibrary(
        filter: LibraryFilter,
        sort: SortOrder,
        query: String
    ): Flow<List<ComicEntity>> = combine(
        comicDao.observeAll(),
        tagDao.observeAll(),
        tagDao.observeAllRelations()
    ) { comics, tags, relations ->
        val nameById = tags.associate { it.id to it.name }
        val tagsByComic = relations.groupBy(
            keySelector = { it.comicId },
            valueTransform = { nameById[it.tagId].orEmpty() }
        )
        val terms = splitTerms(query)

        comics.asSequence()
            .filter { matchesFilter(it, filter) }
            .filter { matchesQuery(it, tagsByComic[it.id].orEmpty(), terms) }
            .sortedWith(comparatorFor(sort))
            .toList()
    }

    /**
     * 书架文件夹流（文件夹视图 / 标签视图）。
     *
     * 与 [observeLibrary] 的区别是**先分组再排序**：同一个目录 / 同一个标签
     * 下的漫画收进一个格子，格子里再按当前排序规则排。
     *
     * 一本漫画可以同时挂多个标签，所以在标签视图里会出现在多个格子中 ——
     * 这是对的，用户从「日常」和「短篇」两个入口都应该找得到它。
     */
    fun observeShelfGroups(
        view: LibraryView,
        sort: SortOrder,
        query: String
    ): Flow<List<ShelfGroup>> = combine(
        comicDao.observeAll(),
        tagDao.observeAll(),
        tagDao.observeAllRelations(),
        shelfCoverDao.observeAll()
    ) { comics, tags, relations, covers ->
        val coverOverride = covers.associate { it.key to it.comicId }
        val nameById = tags.associate { it.id to it.name }
        val tagsByComic = relations.groupBy(
            keySelector = { it.comicId },
            valueTransform = { nameById[it.tagId].orEmpty() }
        )
        val terms = splitTerms(query)

        val visible = comics.asSequence()
            .filter { matchesQuery(it, tagsByComic[it.id].orEmpty(), terms) }
            .toList()

        // key -> (组标题, 组内漫画)
        val buckets = LinkedHashMap<String, Pair<String, MutableList<ComicEntity>>>()

        when (view) {
            LibraryView.FOLDER -> visible.forEach { comic ->
                val uri = comic.folderUri
                val key = if (uri.isNullOrBlank()) UNCLASSIFIED_KEY else FOLDER_PREFIX + uri
                val title = if (uri.isNullOrBlank()) "未归类" else folderDisplayName(uri)
                buckets.getOrPut(key) { title to mutableListOf() }.second.add(comic)
            }

            LibraryView.TAG -> {
                val tagIdsByComic = relations.groupBy({ it.comicId }, { it.tagId })
                visible.forEach { comic ->
                    tagIdsByComic[comic.id].orEmpty().forEach { tagId ->
                        val name = nameById[tagId] ?: return@forEach
                        val key = TAG_PREFIX + tagId
                        buckets.getOrPut(key) { name to mutableListOf() }.second.add(comic)
                    }
                }
            }

            else -> return@combine emptyList()
        }

        buckets.map { (key, bucket) ->
            val sorted = bucket.second.sortedWith(comparatorFor(sort))
            val override = coverOverride[key]
            // 封面：优先用户指定的那一本；否则取组内最近添加的一本
            val cover = override?.let { id -> sorted.firstOrNull { it.id == id } }
                ?: sorted.maxByOrNull { it.addedTime }
                ?: sorted.first()
            ShelfGroup(
                key = key,
                title = bucket.first,
                subtitle = "${sorted.size} 本",
                coverPath = cover.coverPath,
                coverTitle = cover.title,
                comics = sorted
            )
        }.sortedWith(compareBy({ it.title }, { it.key }))
    }

    /** 按标签筛选（标签页点进去看该标签下的漫画）。 */
    fun observeByTag(tagId: Long, sort: SortOrder): Flow<List<ComicEntity>> =
        combine(comicDao.observeAll(), tagDao.comicIdsOfTagFlow(tagId)) { comics, ids ->
            val set = ids.toHashSet()
            comics.filter { it.id in set }.sortedWith(comparatorFor(sort))
        }

    /** 阅读历史：按最近阅读时间倒序。 */
    fun observeHistory(): Flow<List<ComicEntity>> =
        comicDao.observeAll().map { comics ->
            comics.filter { it.lastReadTime > 0L }.sortedByDescending { it.lastReadTime }
        }

    // ------------------------------------------------------------ 书架文件夹封面

    fun observeShelfCovers(): Flow<Map<String, Long>> =
        shelfCoverDao.observeAll().map { list -> list.associate { it.key to it.comicId } }

    /** 全量自定义封面记录 —— 导出备份时用。 */
    suspend fun getShelfCovers(): List<ShelfCoverEntity> = shelfCoverDao.getAll()

    suspend fun setShelfCover(key: String, comicId: Long) =
        shelfCoverDao.put(ShelfCoverEntity(key, comicId))

    suspend fun clearShelfCover(key: String) = shelfCoverDao.remove(key)

    // ------------------------------------------------------------ 写操作

    suspend fun updateProgress(
        id: Long,
        page: Int,
        progress: Float,
        readMode: String?,
        readDirection: String?,
        fitMode: String?,
        zoom: Float?
    ) = comicDao.updateProgress(
        id = id,
        page = page,
        progress = progress.coerceIn(0f, 1f),
        time = System.currentTimeMillis(),
        readMode = readMode,
        readDirection = readDirection,
        fitMode = fitMode,
        zoom = zoom
    )

    suspend fun updateReadingPosition(id: Long, page: Int, progress: Float) =
        comicDao.updateReadingPosition(id, page, progress.coerceIn(0f, 1f), System.currentTimeMillis())

    suspend fun setFavorite(ids: Collection<Long>, favorite: Boolean) {
        if (ids.isEmpty()) return
        comicDao.setFavorite(ids.toList(), favorite)
    }

    suspend fun toggleFavorite(id: Long) {
        val comic = comicDao.getById(id) ?: return
        comicDao.setFavorite(listOf(id), !comic.favorite)
    }

    suspend fun setTitle(id: Long, title: String) = comicDao.setTitleManually(id, title)
    suspend fun setAuthor(id: Long, author: String?) = comicDao.setAuthorManually(id, author)
    suspend fun setCoverPath(id: Long, path: String?) = comicDao.setCoverPath(id, path)

    /** 扩展元数据：系列 / 卷号 / 备注。 */
    suspend fun setMetadata(id: Long, series: String?, volume: Int?, note: String?) =
        comicDao.setMetadata(
            id = id,
            series = series?.trim()?.takeIf { it.isNotEmpty() },
            volume = volume,
            note = note?.trim()?.takeIf { it.isNotEmpty() }
        )

    suspend fun setReadMode(id: Long, mode: String) = comicDao.setReadMode(id, mode)
    suspend fun setFitMode(id: Long, fit: String) = comicDao.setFitMode(id, fit)

    /** 恢复为「跟随全局默认」。 */
    suspend fun clearReadingPrefs(id: Long) = comicDao.clearReadingPrefs(id)
    suspend fun setMissing(id: Long, missing: Boolean) = comicDao.setMissing(id, missing)

    /** 删除漫画，同时清掉它的标签关系与阅读会话（标签本身不受影响）。 */
    suspend fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val list = ids.toList()
        tagDao.detachAllFromComics(list)
        // 会话记录挂在 comicId 上，不跟着删就成了永远查不到主的孤儿行
        statsDao.deleteByComics(list)
        comicDao.deleteByIds(list)
    }

    suspend fun relocate(
        id: Long,
        uri: String,
        folderUri: String?,
        size: Long,
        modifiedTime: Long,
        fileName: String,
        format: String,
        pageCount: Int
    ) = comicDao.relocate(id, uri, folderUri, size, modifiedTime, fileName, format, pageCount)

    /**
     * 把一条「文件找不到」的占位记录并进在库记录，然后删掉占位记录。
     *
     * 修复的是「先恢复备份、后授权扫描」留下的重复：历史数据（标签、进度、
     * 收藏、阅读会话、自定义封面）全在占位那条上，而在库那条是扫描新建的，
     * 除了文件位置什么都没有。所以除了位置信息，其余一律以占位记录为准。
     *
     * 幂等：占位记录已经被合并过时，[comicDao.getById] 会直接返回 null。
     */
    suspend fun absorbMissing(presentId: Long, shellId: Long) {
        val present = comicDao.getById(presentId) ?: return
        val shell = comicDao.getById(shellId) ?: return
        if (present.id == shell.id || !shell.missing) return

        // 进度取「读得更晚的那一次」—— 在库那条也可能已经被用户翻过几页
        val shellIsNewer = shell.lastReadTime >= present.lastReadTime
        comicDao.upsert(
            present.copy(
                favorite = present.favorite || shell.favorite,
                progress = if (shellIsNewer) shell.progress else present.progress,
                currentPage = if (shellIsNewer) shell.currentPage else present.currentPage,
                lastReadTime = maxOf(present.lastReadTime, shell.lastReadTime),
                // 阅读偏好 / 扩展元数据：在库那条为空就用占位记录里的
                readMode = present.readMode ?: shell.readMode,
                readDirection = present.readDirection ?: shell.readDirection,
                fitMode = present.fitMode ?: shell.fitMode,
                zoom = present.zoom ?: shell.zoom,
                series = present.series ?: shell.series,
                volume = present.volume ?: shell.volume,
                note = present.note ?: shell.note,
                rating = present.rating ?: shell.rating,
                // 「用户手动改过」这个事实要合并，不能因为换了条记录就丢掉
                manualTitle = present.manualTitle || shell.manualTitle,
                manualAuthor = present.manualAuthor || shell.manualAuthor,
                addedTime = minOf(present.addedTime, shell.addedTime)
            )
        )

        // 标签关系搬过去。attachAll 是 INSERT OR IGNORE，重复的自动跳过 ——
        // 在库那条本来就挂着同一个标签时也不会冲突。
        val relations = tagDao.relationsOf(listOf(shell.id))
        if (relations.isNotEmpty()) {
            tagDao.attachAll(relations.map { ComicTagEntity(presentId, it.tagId, it.createdAt) })
        }
        tagDao.detachAllFromComics(listOf(shell.id))

        // 阅读会话与「拿这本当文件夹封面」的指定都得跟着走，
        // 否则「合并重复」会顺手把用户的阅读统计和封面设置删掉
        statsDao.reassignComic(shell.id, presentId)
        shelfCoverDao.reassignComic(shell.id, presentId)

        comicDao.deleteById(shell.id)
    }

    suspend fun setFingerprint(id: Long, fingerprint: String, sampleHash: String) =
        comicDao.setFingerprint(id, fingerprint, sampleHash)

    suspend fun setSampleHash(id: Long, hash: String) = comicDao.setSampleHash(id, hash)

    suspend fun refreshParsedNames(id: Long, title: String, author: String?) =
        comicDao.refreshParsedNames(id, title, author)

    /** 只回写标题（用户关闭作者解析时用）。 */
    suspend fun refreshParsedTitle(id: Long, title: String) = comicDao.refreshParsedTitle(id, title)

    suspend fun findByUri(uri: String): ComicEntity? = comicDao.findByUri(uri)
    suspend fun findByFingerprint(fingerprint: String): ComicEntity? =
        comicDao.findByFingerprint(fingerprint).singleOrNull()

    /** 按内容特征挑一条最该被接回来的记录（失效记录优先）。 */
    suspend fun findSampleHashCandidate(hash: String): ComicEntity? =
        comicDao.findSampleHashCandidate(hash)

    /** 老数据没有 sampleHash 时的退化匹配（大小 + 页数，失效记录优先）。 */
    suspend fun findLegacyCandidate(size: Long, pageCount: Int): ComicEntity? =
        comicDao.findLegacyCandidate(size, pageCount)

    suspend fun upsert(comic: ComicEntity): Long = comicDao.upsert(comic)
    suspend fun upsertAll(comics: List<ComicEntity>): List<Long> = comicDao.upsertAll(comics)

    /**
     * 清空整个库（覆盖恢复用）。
     *
     * **`comic_tags` 必须一起清** —— 它没有外键，删 comics / tags 都不会带走它。
     * 旧实现漏了这一步，覆盖恢复后库里会留一批指向已删 id 的孤儿关系。
     */
    suspend fun clearAll() {
        tagDao.clearRelations()
        tagDao.clearAll()
        statsDao.clearAll()
        shelfCoverDao.clearAll()
        comicDao.clearAll()
    }

    suspend fun setMissingByUriPrefix(prefix: String, missing: Boolean) =
        comicDao.setMissingByUriPrefix(prefix, missing)

    // ------------------------------------------------------------ 内部

    /** 从 tree uri 里取一个像样的文件夹名（`primary:Comics` -> `Comics`）。 */
    private fun folderDisplayName(uri: String): String =
        UriDisplay.displayName(uri).substringAfterLast(':').ifBlank { "文件夹" }

    private fun splitTerms(query: String): List<String> =
        query.trim().lowercase().split(' ', '\t', '\n').filter { it.isNotEmpty() }

    private fun matchesFilter(comic: ComicEntity, filter: LibraryFilter): Boolean = when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.RECENT -> comic.lastReadTime > 0L
        LibraryFilter.ADDED -> true
        LibraryFilter.UNREAD -> comic.progress <= 0f
        LibraryFilter.READING -> comic.progress > 0f && comic.progress < 0.999f
        LibraryFilter.FINISHED -> comic.progress >= 0.999f
        LibraryFilter.FAVORITE -> comic.favorite
    }

    /**
     * 模糊搜索：标题 / 作者 / 标签 / 文件名。
     *
     * 空格分词后要求**每个词都命中**（AND 语义）—— 用户输入「作者 短篇」
     * 时期待的是同时满足，而不是命中任意一个就全糊上来。
     */
    private fun matchesQuery(
        comic: ComicEntity,
        tagNames: List<String>,
        terms: List<String>
    ): Boolean {
        if (terms.isEmpty()) return true
        val haystack = buildString {
            append(comic.title.lowercase()).append('\n')
            append(comic.fileName.lowercase()).append('\n')
            comic.author?.lowercase()?.let { append(it).append('\n') }
            tagNames.forEach { append(it.lowercase()).append('\n') }
        }
        return terms.all { haystack.contains(it) }
    }

    private fun comparatorFor(sort: SortOrder): Comparator<ComicEntity> = when (sort) {
        SortOrder.NAME_ASC -> Comparator { a, b -> NaturalOrder.compare(a.title, b.title) }
        SortOrder.NAME_DESC -> Comparator { a, b -> NaturalOrder.compare(b.title, a.title) }
        SortOrder.AUTHOR -> Comparator { a, b ->
            val c = NaturalOrder.compare(a.author.orEmpty(), b.author.orEmpty())
            if (c != 0) c else NaturalOrder.compare(a.title, b.title)
        }
        SortOrder.LAST_READ -> Comparator { a, b -> b.lastReadTime.compareTo(a.lastReadTime) }
        SortOrder.ADDED_DESC -> Comparator { a, b -> b.addedTime.compareTo(a.addedTime) }
        SortOrder.MODIFIED_DESC -> Comparator { a, b -> b.modifiedTime.compareTo(a.modifiedTime) }
        SortOrder.SIZE_DESC -> Comparator { a, b -> b.size.compareTo(a.size) }
        SortOrder.PAGES_DESC -> Comparator { a, b -> b.pageCount.compareTo(a.pageCount) }
        SortOrder.PROGRESS_DESC -> Comparator { a, b -> b.progress.compareTo(a.progress) }
    }

    companion object {
        const val FOLDER_PREFIX = "FOLDER:"
        const val TAG_PREFIX = "TAG:"
        const val UNCLASSIFIED_KEY = "FOLDER:__unclassified__"

        fun groupKeyOf(comic: ComicEntity): String =
            if (comic.folderUri.isNullOrBlank()) UNCLASSIFIED_KEY
            else FOLDER_PREFIX + comic.folderUri
    }
}
