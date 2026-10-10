package com.gaze.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 书架文件夹的自定义封面。
 *
 * 表很小（一条记录 = 一个被改过封面的文件夹），所以整表观察、
 * 在内存里做成 map 交给 UI，比按 key 单独查询省事得多。
 */
@Dao
interface ShelfCoverDao {

    @Query("SELECT * FROM shelf_covers")
    fun observeAll(): Flow<List<ShelfCoverEntity>>

    @Query("SELECT * FROM shelf_covers")
    suspend fun getAll(): List<ShelfCoverEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(cover: ShelfCoverEntity)

    @Query("DELETE FROM shelf_covers WHERE `key` = :key")
    suspend fun remove(key: String)

    /** 合并重复记录时，把「用这本当封面」的指定改挂到保留下来的那条上。 */
    @Query("UPDATE shelf_covers SET comicId = :toComicId WHERE comicId = :fromComicId")
    suspend fun reassignComic(fromComicId: Long, toComicId: Long)

    @Query("DELETE FROM shelf_covers")
    suspend fun clearAll()
}
