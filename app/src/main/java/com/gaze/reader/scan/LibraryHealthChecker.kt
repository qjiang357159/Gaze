package com.gaze.reader.scan

import android.content.Context
import android.net.Uri
import com.gaze.reader.archive.PageProviderFactory
import com.gaze.reader.archive.describeOpenError
import com.gaze.reader.core.ArchiveFormat
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.repository.ComicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** 一处文件问题。 */
data class HealthIssue(
    val comicId: Long,
    val title: String,
    val fileName: String,
    val reason: String
)

/** 健康检查进度与结果。 */
data class HealthReport(
    val running: Boolean = false,
    val checked: Int = 0,
    val total: Int = 0,
    val issues: List<HealthIssue> = emptyList()
) {
    /** 跑完了才有意义 —— 没开始跑时 total 也是 0，不能用它判断。 */
    val finished: Boolean get() = !running && total > 0
}

/**
 * 文件健康检查（方案第二十五章 P2「更完善的损坏文件恢复」）。
 *
 * 扫描只解决「文件还在不在」，回答不了「文件还能不能读」。这个检查会真正
 * **逐个打开归档读中央目录**，找出三类问题：
 *
 * 1. 完全打不开（损坏 / 加密 / 权限失效）；
 * 2. 打开了但一页图片都没有；
 * 3. 页数与库里记录的不一致（文件被替换过）。
 *
 * 只读目录、不解压任何一页，所以几千本也只是几秒到几十秒的量级。
 * 检查**只报告，不自动修改任何数据** —— 删不删由用户决定。
 */
class LibraryHealthChecker(
    private val context: Context,
    private val comics: ComicRepository
) {

    fun check(): Flow<HealthReport> = flow {
        val all = comics.getAllPresent()
        val total = all.size
        if (total == 0) {
            emit(HealthReport(running = false, checked = 0, total = 0))
            return@flow
        }

        val issues = ArrayList<HealthIssue>()
        emit(HealthReport(running = true, checked = 0, total = total))

        all.forEachIndexed { index, comic ->
            currentCoroutineContext().ensureActive()
            inspect(comic)?.let { issues += it }
            emit(
                HealthReport(
                    running = true,
                    checked = index + 1,
                    total = total,
                    issues = issues.toList()
                )
            )
        }

        emit(HealthReport(running = false, checked = total, total = total, issues = issues))
    }
        // 检查一本可能要几十毫秒，几千本会产生几千次 emit。
        // 收集端跟不上时丢掉中间值即可 —— 进度是「最新值有意义」的典型场景。
        .conflate()
        .flowOn(Dispatchers.IO)

    private fun inspect(comic: ComicEntity): HealthIssue? {
        val uri = runCatching { Uri.parse(comic.uri) }.getOrNull()
            ?: return issue(comic, "文件路径无效")

        return try {
            val format = ArchiveFormat.of(comic.fileName)
            PageProviderFactory.open(context, uri, format).use { provider ->
                val count = provider.pageCount
                when {
                    count <= 0 -> issue(comic, "文件里没有找到任何可显示的页面")
                    count != comic.pageCount ->
                        issue(comic, "页数已变化：库中记录 ${comic.pageCount} 页，实际 $count 页")
                    else -> null
                }
            }
        } catch (t: Throwable) {
            issue(comic, describeOpenError(t))
        }
    }

    private fun issue(comic: ComicEntity, reason: String) = HealthIssue(
        comicId = comic.id,
        title = comic.title,
        fileName = comic.fileName,
        reason = reason
    )
}
