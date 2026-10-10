package com.gaze.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 标签表的数据访问。
 *
 * 计数口径与书架一致：只统计 `missing = 0` 的漫画 ——
 * 文件已经找不到的漫画不该再算进「这个标签下有 N 本」。
 */
@Dao
interface TagDao {

    @Query(
        """
        SELECT t.id AS id, t.name AS name, t.color AS color, t.createdAt AS createdAt,
               (SELECT COUNT(*) FROM comic_tags ct
                  INNER JOIN comics c ON c.id = ct.comicId
                  WHERE ct.tagId = t.id AND c.missing = 0) AS comicCount
        FROM tags t
        ORDER BY (t.sortOrder IS NULL) ASC, t.sortOrder ASC, comicCount DESC, t.createdAt ASC
        """
    )
    fun observeAllWithCount(): Flow<List<TagWithCount>>

    /**
     * 标签列表顺序。
     *
     * 手动排过序的（`sortOrder` 非空）排前面，按位次升序；
     * 没排过的（NULL）排在后面，按创建时间 —— 新建的标签默认落在末尾，
     * 而不是插到用户精心排好的序列中间。
     */
    @Query("SELECT * FROM tags ORDER BY (sortOrder IS NULL) ASC, sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<TagEntity>>

    /** 全部「漫画 ↔ 标签」关系，供书架搜索按标签名匹配时使用。 */
    @Query("SELECT * FROM comic_tags")
    fun observeAllRelations(): Flow<List<ComicTagEntity>>

    @Query("SELECT * FROM tags ORDER BY (sortOrder IS NULL) ASC, sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE tags SET color = :color WHERE id = :id")
    suspend fun setColor(id: Long, color: Int)

    @Query("UPDATE tags SET color = :color WHERE id IN (:ids)")
    suspend fun setColorMany(ids: List<Long>, color: Int)

    /** 写入手动排序位次（0 起）。 */
    @Query("UPDATE tags SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tags")
    suspend fun clearAll()

    /**
     * 清空全部「漫画 ↔ 标签」关系。
     *
     * 覆盖恢复时必须连着调一次：`comic_tags` 是一张独立的多对多表，
     * 删 `comics` 和 `tags` 都不会带走它（表上没有外键）。不清的话，
     * 恢复后库里会留下一批指向已不存在 id 的孤儿关系 —— 平时看不见，
     * 但一旦新插入的漫画 / 标签恰好复用了旧 id，就会「凭空多出」一个标签。
     */
    @Query("DELETE FROM comic_tags")
    suspend fun clearRelations()

    /** 关系总条数，用于恢复后向用户报告「标签挂上了多少条」。 */
    @Query("SELECT COUNT(*) FROM comic_tags")
    suspend fun relationCountAll(): Int

    // ------------------------------------------------------------ 关系

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN comic_tags ct ON ct.tagId = t.id
        WHERE ct.comicId = :comicId
        ORDER BY t.createdAt ASC
        """
    )
    fun observeTagsOfComic(comicId: Long): Flow<List<TagEntity>>

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN comic_tags ct ON ct.tagId = t.id
        WHERE ct.comicId = :comicId
        ORDER BY t.createdAt ASC
        """
    )
    suspend fun tagsOfComic(comicId: Long): List<TagEntity>

    @Query("SELECT tagId FROM comic_tags WHERE comicId = :comicId")
    suspend fun tagIdsOfComic(comicId: Long): List<Long>

    /**
     * 批量取这批漫画已有的全部标签关系。
     *
     * 用 `SELECT *` 而不是只选两列：Room 会用查询结果直接构造实体，
     * 少选一列（这里是 createdAt）会被判成「非空列没数据」而编译失败。
     */
    @Query("SELECT * FROM comic_tags WHERE comicId IN (:comicIds)")
    suspend fun relationsOf(comicIds: List<Long>): List<ComicTagEntity>

    /** 全量关系，导出备份时一次性取完。 */
    @Query("SELECT * FROM comic_tags")
    suspend fun relationsOfAll(): List<ComicTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attachAll(relations: List<ComicTagEntity>): List<Long>

    /**
     * 批量解绑 —— 这正是文档第五章「删除 = 指定标签 ∩ 已存在标签」的实现：
     * `tagId IN (:tagIds)` 天然只命中两者交集，不存在的关系不会被误伤。
     */
    @Query("DELETE FROM comic_tags WHERE comicId IN (:comicIds) AND tagId IN (:tagIds)")
    suspend fun detachMany(comicIds: List<Long>, tagIds: List<Long>)

    @Query("DELETE FROM comic_tags WHERE comicId = :comicId AND tagId = :tagId")
    suspend fun detach(comicId: Long, tagId: Long)

    @Query("DELETE FROM comic_tags WHERE tagId = :tagId")
    suspend fun detachAllFromTag(tagId: Long)

    @Query("DELETE FROM comic_tags WHERE comicId IN (:comicIds)")
    suspend fun detachAllFromComics(comicIds: List<Long>)

    @Query("SELECT COUNT(*) FROM comic_tags WHERE tagId = :tagId")
    suspend fun relationCount(tagId: Long): Int

    /** 某个标签下的全部漫画 id，用于按标签筛选。 */
    @Query("SELECT comicId FROM comic_tags WHERE tagId = :tagId")
    suspend fun comicIdsOfTag(tagId: Long): List<Long>

    /** 同上，响应式版本 —— 标签详情页要随标签增删实时刷新。 */
    @Query("SELECT comicId FROM comic_tags WHERE tagId = :tagId")
    fun comicIdsOfTagFlow(tagId: Long): Flow<List<Long>>
}
