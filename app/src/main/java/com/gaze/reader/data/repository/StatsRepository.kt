package com.gaze.reader.data.repository

import com.gaze.reader.data.db.ComicDao
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.ReadingSessionEntity
import com.gaze.reader.data.db.ReadingStats
import com.gaze.reader.data.db.StatsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Calendar

/** 阅读时长排行的一项。 */
data class TopComic(val comic: ComicEntity, val durationMs: Long)

/** 统计页需要的全部数据。 */
data class StatsData(
    val stats: ReadingStats = ReadingStats(),
    val topComics: List<TopComic> = emptyList()
)

/**
 * 阅读统计（方案第二十五章 P2）。
 *
 * 全部聚合都在这里完成，UI 只负责画。今日 / 本周的边界按**本地时区**在 Kotlin 侧算，
 * 不写进 SQL —— 拼日期函数既难读，也容易在时区变化时算错。
 */
class StatsRepository(
    private val statsDao: StatsDao,
    private val comicDao: ComicDao
) {

    /** 阅读时长排行取前几名。 */
    private val topLimit = 8

    fun observe(): Flow<StatsData> = combine(
        statsDao.observeTotals(),
        statsDao.observeSpans(),
        statsDao.observeTopComics(topLimit),
        comicDao.observeAll()
    ) { totals, spans, top, comics ->

        val now = System.currentTimeMillis()
        val todayStart = startOfToday(now)
        val weekStart = startOfWeek(now)

        var today = 0L
        var week = 0L
        spans.forEach { span ->
            if (span.startedAt >= todayStart) today += span.durationMs
            if (span.startedAt >= weekStart) week += span.durationMs
        }

        // 用已经观察到的列表在内存里匹配，而不是逐条 getById ——
        // combine 的转换 lambda 不是 suspend，调不了挂起查询。
        val byId = comics.associateBy { it.id }
        val topComics = top.mapNotNull { row ->
            byId[row.comicId]?.let { TopComic(it, row.durationMs) }
        }

        StatsData(
            stats = ReadingStats(
                totalDurationMs = totals.totalDurationMs,
                totalSessions = totals.totalSessions,
                totalPagesRead = totals.totalPagesRead,
                booksRead = totals.booksRead,
                todayDurationMs = today,
                weekDurationMs = week,
                finishedCount = comics.count { it.progress >= FINISHED_THRESHOLD }
            ),
            topComics = topComics
        )
    }

    suspend fun record(session: ReadingSessionEntity) {
        // 时长过短的会话不入库：翻两页又退出去会制造一堆噪声数据
        if (session.durationMs < MIN_SESSION_MS) return
        runCatching { statsDao.insert(session) }
    }

    suspend fun count(): Int = runCatching { statsDao.count() }.getOrDefault(0)

    /** 全量会话 —— 导出备份时用。 */
    suspend fun getAll(): List<ReadingSessionEntity> =
        runCatching { statsDao.getAll() }.getOrDefault(emptyList())

    /**
     * 恢复备份时批量写回历史会话。
     *
     * 与 [record] 的区别是**不过滤时长** —— 那条 5 秒闸门是防「翻两页就退出」
     * 制造噪声的，而备份里的每一条都是当初已经通过过闸门的数据，再滤一遍
     * 只会把用户真实的短时阅读记录吃掉。
     */
    suspend fun restoreAll(sessions: List<ReadingSessionEntity>): Int {
        if (sessions.isEmpty()) return 0
        var written = 0
        sessions.forEach { session ->
            if (runCatching { statsDao.insert(session) }.getOrDefault(-1L) > 0L) written++
        }
        return written
    }

    suspend fun clear() {
        runCatching { statsDao.clearAll() }
    }

    suspend fun deleteFor(comicIds: List<Long>) {
        if (comicIds.isEmpty()) return
        runCatching { statsDao.deleteByComics(comicIds) }
    }

    /** 只保留最近 [keepDays] 天的会话。 */
    suspend fun prune(keepDays: Int = 365) {
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        runCatching { statsDao.pruneBefore(cutoff) }
    }

    private companion object {
        /** 少于 5 秒的停留不算一次阅读。 */
        const val MIN_SESSION_MS = 5_000L

        /** 与书架「已完成」筛选保持同一口径。 */
        const val FINISHED_THRESHOLD = 0.999f
    }
}

/** 今天 00:00 的时间戳。 */
internal fun startOfToday(now: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * 本周 00:00 的时间戳，**以周一为一周之首**。
 *
 * 不用 `Calendar.set(DAY_OF_WEEK, MONDAY)`：那是惰性求值，周日当天设置会跳到下一周，
 * 是个很容易踩的坑。这里直接用「今天是周几」反推偏移量，结果无歧义。
 */
internal fun startOfWeek(now: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)

    val dow = cal.get(Calendar.DAY_OF_WEEK)
    val offsetDays = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
    return cal.timeInMillis - offsetDays * 86_400_000L
}
