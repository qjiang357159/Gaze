package com.gaze.reader.data.repository

import com.gaze.reader.data.db.ComicTagEntity
import com.gaze.reader.data.db.TagDao
import com.gaze.reader.data.db.TagEntity
import com.gaze.reader.data.db.TagWithCount
import com.gaze.reader.domain.TagPalette
import kotlinx.coroutines.flow.Flow

/**
 * 标签仓库。
 *
 * 核心是文档第五章那条**按差集写入**的规则：
 *
 * ```
 * 原本 A B C
 * 批量添加 C D E  ->  最终 A B C D E      （新增 = 新标签 - 已存在标签）
 * 批量删除 B E    ->  最终 A C D          （删除 = 指定标签 ∩ 已存在标签）
 * ```
 *
 * 绝不「整体覆盖」用户的标签集合 —— 批量操作最容易造成的破坏就是
 * 把用户之前辛苦打好的标签一次性抹掉。
 *
 * 单个和批量走**同一条路径**（批量就是传一个元素的集合），
 * 两套实现各写一遍迟早会出现「批量能加、单个加不上」这种不一致。
 */
class TagRepository(private val tagDao: TagDao) {

    fun observeAllWithCount(): Flow<List<TagWithCount>> = tagDao.observeAllWithCount()
    fun observeAll(): Flow<List<TagEntity>> = tagDao.observeAll()
    fun observeTagsOfComic(comicId: Long): Flow<List<TagEntity>> = tagDao.observeTagsOfComic(comicId)

    suspend fun getAll(): List<TagEntity> = tagDao.getAll()
    suspend fun getById(id: Long): TagEntity? = tagDao.getById(id)
    suspend fun tagsOfComic(comicId: Long): List<TagEntity> = tagDao.tagsOfComic(comicId)
    suspend fun tagIdsOfComic(comicId: Long): List<Long> = tagDao.tagIdsOfComic(comicId)

    /** 给新标签挑一个还没被用过的颜色。 */
    suspend fun suggestColor(): Int = TagPalette.nextUnused(tagDao.getAll().map { it.color })

    /**
     * 新建标签。重名时直接返回已有的那个 ——
     * 用户重复输入同一个名字，期待的是「选中它」，而不是一个错误。
     */
    suspend fun create(name: String, color: Int? = null): TagEntity? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        tagDao.findByName(trimmed)?.let { return it }
        val resolved = color ?: suggestColor()
        val id = tagDao.insert(TagEntity(name = trimmed, color = resolved, createdAt = System.currentTimeMillis()))
        return if (id > 0L) tagDao.getById(id) else tagDao.findByName(trimmed)
    }

    /** 一次建一批标签（用户在批量操作里输入「日常 恋爱 搞笑」这种）。 */
    suspend fun createMany(names: Collection<String>): List<TagEntity> =
        names.mapNotNull { create(it) }

    suspend fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val existing = tagDao.findByName(trimmed)
        if (existing != null && existing.id != id) return
        tagDao.rename(id, trimmed)
    }

    suspend fun setColor(id: Long, color: Int) = tagDao.setColor(id, color)

    /** 批量改色（标签长按批量管理用）。一条 SQL 搞定，不逐个更新。 */
    suspend fun setColorMany(ids: Collection<Long>, color: Int) {
        if (ids.isEmpty()) return
        tagDao.setColorMany(ids.toList(), color)
    }

    /** 删除标签：先解关联再删本体，漫画本身不受影响。 */
    suspend fun delete(id: Long) {
        tagDao.detachAllFromTag(id)
        tagDao.deleteById(id)
    }

    suspend fun deleteMany(ids: Collection<Long>) {
        ids.forEach { delete(it) }
    }

    /**
     * 批量添加标签 —— 差集语义（新增 = 新标签 - 已存在标签）。
     *
     * 先一次性把这批漫画已有的关系捞出来，在内存里求差，
     * 而不是对每个 (漫画, 标签) 组合都查一次库。
     */
    suspend fun addTo(comicIds: Collection<Long>, tagIds: Collection<Long>): Int {
        if (comicIds.isEmpty() || tagIds.isEmpty()) return 0
        val comicList = comicIds.toList()
        val tagList = tagIds.toList()
        val existing = tagDao.relationsOf(comicList)
            .mapTo(HashSet()) { it.comicId to it.tagId }
        val now = System.currentTimeMillis()
        val toAdd = ArrayList<ComicTagEntity>()
        for (comicId in comicList) {
            for (tagId in tagList) {
                if ((comicId to tagId) !in existing) {
                    toAdd += ComicTagEntity(comicId = comicId, tagId = tagId, createdAt = now)
                }
            }
        }
        if (toAdd.isEmpty()) return 0
        return tagDao.attachAll(toAdd).count { it > 0L }
    }

    /**
     * 批量移除标签 —— 差集语义（删除 = 指定标签 ∩ 已存在标签）。
     *
     * 一条 `WHERE comicId IN (...) AND tagId IN (...)` 天然只命中交集，
     * 不存在的关系不会被误伤。
     */
    suspend fun removeFrom(comicIds: Collection<Long>, tagIds: Collection<Long>): Int {
        if (comicIds.isEmpty() || tagIds.isEmpty()) return 0
        tagDao.detachMany(comicIds.toList(), tagIds.toList())
        return comicIds.size
    }

    /**
     * 把一本漫画的标签整体设为 [tagIds]。
     * 详情页的标签编辑器用它 —— 用户在里面自由勾选，保存时做一次差集。
     */
    suspend fun setTagsOf(comicId: Long, tagIds: Collection<Long>) {
        val current = tagDao.tagIdsOfComic(comicId).toHashSet()
        val target = tagIds.toHashSet()
        (current - target).forEach { tagDao.detach(comicId, it) }
        if (target.isNotEmpty()) {
            val now = System.currentTimeMillis()
            tagDao.attachAll(
                (target - current).map { ComicTagEntity(comicId = comicId, tagId = it, createdAt = now) }
            )
        }
    }

    suspend fun relationCount(tagId: Long): Int = tagDao.relationCount(tagId)

    /** 全量「漫画 ↔ 标签」关系，导出备份时一次性取完，避免逐本查库。 */
    suspend fun allRelations(): List<ComicTagEntity> = tagDao.relationsOfAll()

    /** 按标签取漫画 id（标签详情页用）。 */
    suspend fun comicIdsOfTag(tagId: Long): List<Long> = tagDao.comicIdsOfTag(tagId)
}
