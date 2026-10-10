package com.gaze.reader.data.repository

import com.gaze.reader.data.db.FolderDao
import com.gaze.reader.data.db.FolderEntity
import com.gaze.reader.domain.UriDisplay
import kotlinx.coroutines.flow.Flow

/**
 * 已授权文件夹仓库。
 *
 * 文件夹的权威来源是数据库而不是偏好文件 —— 每个文件夹还带着
 * 「上次扫描时间」「扫到多少本」这类结构化信息，塞进 DataStore 只会变成一坨 JSON。
 */
class FolderRepository(private val folderDao: FolderDao) {

    /** 含「待重新授权」的全部记录，仅供设置页展示。 */
    fun observeAll(): Flow<List<FolderEntity>> = folderDao.observeAll()

    /** 真正授权过的文件夹 —— 扫描、封面、导出备份都走它。 */
    fun observeAuthorized(): Flow<List<FolderEntity>> = folderDao.observeAuthorized()

    suspend fun getAll(): List<FolderEntity> = folderDao.getAll()

    suspend fun getAuthorized(): List<FolderEntity> = folderDao.getAuthorized()

    suspend fun getByUri(uri: String): FolderEntity? = folderDao.getByUri(uri)

    suspend fun add(uri: String, displayName: String? = null) {
        val name = displayName?.takeIf { it.isNotBlank() } ?: UriDisplay.displayName(uri)
        val existing = folderDao.getByUri(uri)
        folderDao.upsert(
            FolderEntity(
                uri = uri,
                displayName = name,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                lastScanAt = existing?.lastScanAt ?: 0L,
                comicCount = existing?.comicCount ?: 0,
                // 用户亲自走过一次目录选择器 —— 不管之前是不是「待重新授权」，现在都是真的了。
                pending = null
            )
        )
    }

    /**
     * 记下一个「来自备份、但本机还没授权」的文件夹。
     *
     * **不写 uri 权限、也不参与扫描** —— 只留一条线索，好在设置页里告诉用户
     * 「备份里原来授权过这些目录，需要你重新指一次」。SAF 的读权限没法从
     * 备份文件里恢复，假装它有是最糟的处理方式。
     */
    suspend fun addPending(uri: String, displayName: String?) {
        if (uri.isBlank()) return
        val existing = folderDao.getByUri(uri)
        // 已经真正授权过的，别被备份里的记录降级成待授权
        if (existing != null && existing.pending != true) return
        val name = displayName?.takeIf { it.isNotBlank() } ?: UriDisplay.displayName(uri)
        folderDao.upsert(
            FolderEntity(
                uri = uri,
                displayName = name,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                lastScanAt = existing?.lastScanAt ?: 0L,
                comicCount = existing?.comicCount ?: 0,
                pending = true
            )
        )
    }

    /** 用户重新授权成功 —— 抹掉「待重新授权」标记。 */
    suspend fun confirm(uri: String) = folderDao.confirm(uri)

    suspend fun remove(uri: String) = folderDao.deleteByUri(uri)

    suspend fun updateScanResult(uri: String, comicCount: Int) =
        folderDao.updateScanResult(uri, System.currentTimeMillis(), comicCount)

    suspend fun clearAll() = folderDao.clearAll()
}
