package com.gaze.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicDao {

    /**
     * 全量读取（不含失效条目）。
     *
     * 排序刻意**不放在 SQL 里**：文档第十三章要求「名称 A-Z」按自然数字顺序排，
     * 而 SQLite 没有自然排序。统一由 Repository 在内存里排，
     * 保证 ZIP / 列表 / 搜索结果三处顺序一致。
     */
    @Query("SELECT * FROM comics WHERE missing = 0")
    fun observeAll(): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comics WHERE missing = 0 AND id = :id")
    fun observeById(id: Long): Flow<ComicEntity?>

    @Query("SELECT * FROM comics WHERE id = :id")
    suspend fun getById(id: Long): ComicEntity?

    @Query("SELECT * FROM comics WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ComicEntity>

    @Query("SELECT * FROM comics WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): ComicEntity?

    @Query("SELECT * FROM comics WHERE fingerprint = :fingerprint LIMIT 2")
    suspend fun findByFingerprint(fingerprint: String): List<ComicEntity>

    /**
     * 按内容特征找「最该被接回来的那条记录」。
     *
     * `ORDER BY missing DESC` 是关键：**已失效的记录优先**。同一个文件既有一条
     * `missing = 1` 的占位记录、又有一条在库记录，正是「先恢复备份、后授权扫描」
     * 留下的重复 —— 阅读进度、收藏、标签全在占位那条上。
     *
     * 旧写法是 `LIMIT 2` + 调用方 `singleOrNull()`：一旦两条同时存在就直接
     * 放弃匹配，于是扫描器又新建一条，重复越滚越多，用户看到的就是「标签没了」。
     *
     * 老数据没有 sampleHash（为 NULL），退化成 [findLegacyCandidate]。
     */
    @Query("SELECT * FROM comics WHERE sampleHash = :sampleHash ORDER BY missing DESC, id ASC LIMIT 1")
    suspend fun findSampleHashCandidate(sampleHash: String): ComicEntity?

    @Query(
        "SELECT * FROM comics WHERE sampleHash IS NULL AND size = :size AND pageCount = :pageCount " +
            "ORDER BY missing DESC, id ASC LIMIT 1"
    )
    suspend fun findLegacyCandidate(size: Long, pageCount: Int): ComicEntity?

    @Query("SELECT * FROM comics WHERE missing = 1")
    fun observeMissing(): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comics WHERE missing = 1")
    suspend fun getMissing(): List<ComicEntity>

    @Query("SELECT * FROM comics WHERE missing = 0")
    suspend fun getAllPresent(): List<ComicEntity>

    @Query("SELECT * FROM comics")
    suspend fun getAll(): List<ComicEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(comic: ComicEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(comics: List<ComicEntity>): List<Long>

    @Query("DELETE FROM comics WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM comics WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE comics SET missing = :missing WHERE id = :id")
    suspend fun setMissing(id: Long, missing: Boolean)

    @Query("UPDATE comics SET missing = :missing WHERE uri LIKE :uriPrefix || '%'")
    suspend fun setMissingByUriPrefix(uriPrefix: String, missing: Boolean)

    @Query("UPDATE comics SET favorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<Long>, favorite: Boolean)

    // ------------------------------------------------------------ 阅读进度

    @Query(
        """
        UPDATE comics SET
            currentPage = :page,
            progress = :progress,
            lastReadTime = :time,
            readMode = :readMode,
            readDirection = :readDirection,
            fitMode = :fitMode,
            zoom = :zoom
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: Long,
        page: Int,
        progress: Float,
        time: Long,
        readMode: String?,
        readDirection: String?,
        fitMode: String?,
        zoom: Float?
    )

    /** 只更新页码/百分比/时间 —— 用于翻页过程中的轻量落库。 */
    @Query(
        """
        UPDATE comics SET
            currentPage = :page,
            progress = :progress,
            lastReadTime = :time
        WHERE id = :id
        """
    )
    suspend fun updateReadingPosition(id: Long, page: Int, progress: Float, time: Long)

    @Query("UPDATE comics SET readMode = :mode WHERE id = :id")
    suspend fun setReadMode(id: Long, mode: String)

    @Query("UPDATE comics SET readDirection = :direction WHERE id = :id")
    suspend fun setReadDirection(id: Long, direction: String)

    @Query("UPDATE comics SET fitMode = :fit WHERE id = :id")
    suspend fun setFitMode(id: Long, fit: String)

    /**
     * 清掉这本漫画的阅读偏好，让它重新跟随设置页里的全局默认。
     *
     * 三个字段设计上就是「null = 跟随全局」，所以恢复默认 = 置回 null，
     * 而不是把当前全局值抄一份进来 —— 后者会让「以后改默认」又失效。
     */
    @Query("UPDATE comics SET readMode = NULL, readDirection = NULL, fitMode = NULL WHERE id = :id")
    suspend fun clearReadingPrefs(id: Long)

    @Query("UPDATE comics SET coverPath = :path WHERE id = :id")
    suspend fun setCoverPath(id: Long, path: String?)

    /**
     * 扩展元数据（系列 / 卷号 / 备注）。
     *
     * 一次全写而不是三个方法：这三个字段永远在同一个「编辑详细信息」对话框里改，
     * 拆开只会让调用方写三次、多两次无谓的 IO。
     */
    @Query(
        """
        UPDATE comics SET
            series = :series,
            volume = :volume,
            note = :note
        WHERE id = :id
        """
    )
    suspend fun setMetadata(id: Long, series: String?, volume: Int?, note: String?)

    @Query("UPDATE comics SET title = :title, manualTitle = 1 WHERE id = :id")
    suspend fun setTitleManually(id: Long, title: String)

    @Query("UPDATE comics SET author = :author, manualAuthor = 1 WHERE id = :id")
    suspend fun setAuthorManually(id: Long, author: String?)

    /** 扫描回写：只在用户没手动改过时才更新标题 / 作者。 */
    @Query(
        """
        UPDATE comics SET
            title = CASE WHEN manualTitle = 0 THEN :title ELSE title END,
            author = CASE WHEN manualAuthor = 0 THEN :author ELSE author END
        WHERE id = :id
        """
    )
    suspend fun refreshParsedNames(id: Long, title: String, author: String?)

    /**
     * 只回写标题，**不动作者**。
     *
     * 用在「用户关掉了从文件名解析作者」的情况：那时我们不该再推断作者，
     * 但也不该把已有的作者抹掉 —— 「不再自动推断」和「删除已有数据」是两件事。
     */
    @Query("UPDATE comics SET title = :title WHERE id = :id AND manualTitle = 0")
    suspend fun refreshParsedTitle(id: Long, title: String)

    @Query("UPDATE comics SET sampleHash = :hash WHERE id = :id")
    suspend fun setSampleHash(id: Long, hash: String)

    @Query("UPDATE comics SET fingerprint = :fingerprint, sampleHash = :hash WHERE id = :id")
    suspend fun setFingerprint(id: Long, fingerprint: String, hash: String)

    /**
     * 把一条记录重新绑定到本地文件。
     *
     * 顺带刷新 `format` 与 `pageCount`：恢复备份留下的占位记录可能记着
     * 「ZIP、0 页」（老备份里没有格式字段，页数也可能是 0），只改 uri 的话
     * PDF / EPUB 会按 ZIP 打开、进度条也会算错。
     */
    @Query(
        """
        UPDATE comics SET
            uri = :uri, folderUri = :folderUri, missing = 0,
            size = :size, modifiedTime = :modifiedTime, fileName = :fileName,
            format = :format, pageCount = :pageCount
        WHERE id = :id
        """
    )
    suspend fun relocate(
        id: Long,
        uri: String,
        folderUri: String?,
        size: Long,
        modifiedTime: Long,
        fileName: String,
        format: String,
        pageCount: Int
    )

    // ------------------------------------------------------------ 统计

    @Query(
        """
        SELECT
            COUNT(*) AS totalCount,
            COALESCE(SUM(size), 0) AS totalBytes,
            COALESCE(SUM(CASE WHEN favorite = 1 THEN 1 ELSE 0 END), 0) AS favoriteCount,
            COALESCE(SUM(CASE WHEN progress <= 0 THEN 1 ELSE 0 END), 0) AS unreadCount,
            COALESCE(SUM(CASE WHEN progress >= 0.999 THEN 1 ELSE 0 END), 0) AS finishedCount
        FROM comics WHERE missing = 0
        """
    )
    fun observeStats(): Flow<LibraryStats>

    @Query("SELECT COUNT(*) FROM comics WHERE missing = 0")
    suspend fun countPresent(): Int

    @Query("DELETE FROM comics")
    suspend fun clearAll()
}
