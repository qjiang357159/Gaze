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

    fun observeAll(): Flow<List<FolderEntity>> = folderDao.observeAll()

    suspend fun getAll(): List<FolderEntity> = folderDao.getAll()

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
                comicCount = existing?.comicCount ?: 0
            )
        )
    }

    suspend fun remove(uri: String) = folderDao.deleteByUri(uri)

    suspend fun updateScanResult(uri: String, comicCount: Int) =
        folderDao.updateScanResult(uri, System.currentTimeMillis(), comicCount)

    suspend fun clearAll() = folderDao.clearAll()
}
