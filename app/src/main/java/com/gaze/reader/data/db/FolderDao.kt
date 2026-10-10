package com.gaze.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    /** 全部记录，**含待重新授权的**。只给设置页展示用，别拿它去扫描。 */
    @Query("SELECT * FROM folders ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY addedAt ASC")
    suspend fun getAll(): List<FolderEntity>

    /**
     * 真正拿到过系统读权限的文件夹。
     *
     * 扫描器、封面生成、导出备份一律走这个 —— 待重新授权的文件夹在本机读不出
     * 任何东西，把它们喂给扫描器只会得到「扫到 0 本」然后把已有记录全标成失效。
     */
    @Query("SELECT * FROM folders WHERE pending IS NULL OR pending = 0 ORDER BY addedAt ASC")
    suspend fun getAuthorized(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE pending IS NULL OR pending = 0 ORDER BY addedAt ASC")
    fun observeAuthorized(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE uri = :uri")
    suspend fun getByUri(uri: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: FolderEntity)

    /** 用户重新走过一次系统目录选择器 —— 这条记录从此是真正授权的了。 */
    @Query("UPDATE folders SET pending = NULL WHERE uri = :uri")
    suspend fun confirm(uri: String)

    @Query("DELETE FROM folders WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM folders")
    suspend fun clearAll()

    @Query("UPDATE folders SET lastScanAt = :time, comicCount = :count WHERE uri = :uri")
    suspend fun updateScanResult(uri: String, time: Long, count: Int)
}
