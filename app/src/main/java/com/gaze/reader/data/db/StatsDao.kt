package com.gaze.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 会话的时间跨度 —— 今日 / 本周的边界要按本地时区算，SQL 里做不干净，交给上层。 */
data class SessionSpan(
    val startedAt: Long,
    val durationMs: Long
)

/** 会话聚合的中间结果，列名与 SQL 的 AS 别名一一对应。 */
data class SessionTotals(
    val totalDurationMs: Long,
    val totalSessions: Int,
    val totalPagesRead: Int,
    val booksRead: Int
)

@Dao
interface StatsDao {

    @Insert
    suspend fun insert(session: ReadingSessionEntity): Long

    /**
     * 全部会话的「开始时间 + 时长」。
     *
     * 没有写成 `SUM(...) WHERE startedAt >= :today` 是因为「今天」「本周」的起点
     * 依赖本地时区和当前时刻，交给 Kotlin 算比在 SQL 里拼日期函数清楚得多，
     * 也不会因为时区设置变化算错。会话是「一次阅读一条」，量级很小。
     */
    @Query("SELECT startedAt, durationMs FROM reading_sessions")
    fun observeSpans(): Flow<List<SessionSpan>>

    @Query(
        """
        SELECT
            COALESCE(SUM(durationMs), 0) AS totalDurationMs,
            COUNT(*) AS totalSessions,
            COALESCE(SUM(pagesRead), 0) AS totalPagesRead,
            COUNT(DISTINCT comicId) AS booksRead
        FROM reading_sessions
        """
    )
    fun observeTotals(): Flow<SessionTotals>

    /** 阅读时长排行。 */
    @Query(
        """
        SELECT comicId, SUM(durationMs) AS durationMs
        FROM reading_sessions
        GROUP BY comicId
        ORDER BY durationMs DESC
        LIMIT :limit
        """
    )
    fun observeTopComics(limit: Int): Flow<List<ComicReadingTime>>

    @Query("SELECT COUNT(*) FROM reading_sessions")
    suspend fun count(): Int

    /** 全量会话 —— 导出备份时一次性取完。 */
    @Query("SELECT * FROM reading_sessions")
    suspend fun getAll(): List<ReadingSessionEntity>

    @Query("DELETE FROM reading_sessions WHERE comicId = :comicId")
    suspend fun deleteByComic(comicId: Long)

    /**
     * 把一段会话改挂到另一本漫画上。
     *
     * 用在「合并重复记录」：恢复备份留下的占位记录被并进在库记录时，
     * 会话不能跟着占位记录一起被删，否则阅读统计会凭空少一截。
     */
    @Query("UPDATE reading_sessions SET comicId = :toComicId WHERE comicId = :fromComicId")
    suspend fun reassignComic(fromComicId: Long, toComicId: Long)

    @Query("DELETE FROM reading_sessions WHERE comicId IN (:comicIds)")
    suspend fun deleteByComics(comicIds: List<Long>)

    @Query("DELETE FROM reading_sessions")
    suspend fun clearAll()

    /** 只保留最近 [keepSince] 之后的会话，避免表无限增长。 */
    @Query("DELETE FROM reading_sessions WHERE startedAt < :keepSince")
    suspend fun pruneBefore(keepSince: Long)
}
