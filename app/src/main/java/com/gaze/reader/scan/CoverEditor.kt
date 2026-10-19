package com.gaze.reader.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.gaze.reader.archive.PageProvider
import com.gaze.reader.archive.PageProviderFactory
import com.gaze.reader.core.ArchiveFormat
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.repository.ComicRepository
import com.gaze.reader.image.CoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * 更换封面。
 *
 * 两个来源：**漫画文件内部的某一页**、**本地图片**。两者最后都走
 * [CoverStore.save]，把结果写进 `comics.coverPath` —— 和自动生成封面
 * 落在同一个位置，所以「恢复自动生成」只是再跑一次生成器而已。
 *
 * 之所以单独开一个类而不是塞进 [CoverGenerator]：选页要**反复读同一本书**
 * （缩略图网格里每滑一格就是一次解码），生成器是一次性的，
 * 生命周期完全不同。
 */
class CoverEditor(
    private val context: Context,
    private val comics: ComicRepository,
    private val store: CoverStore
) {

    /**
     * 打开一本漫画的选页会话。
     *
     * 整个选页过程**共用同一个 [PageProvider]**：缩略图网格里每翻一屏都重新
     * 打开一次归档的话，大压缩包每次都要重读中央目录，滑动会卡成幻灯片。
     * 用完必须 [Session.close]，否则归档通道会一直占着文件。
     *
     * 打不开就返回 null（文件失效、格式坏了），调用方据此提示用户。
     */
    fun openSession(comic: ComicEntity): Session? {
        val uri = runCatching { Uri.parse(comic.uri) }.getOrNull() ?: return null
        val provider = runCatching {
            PageProviderFactory.open(context, uri, ArchiveFormat.of(comic.fileName))
        }.getOrNull() ?: return null
        return Session(provider, comic.id)
    }

    inner class Session(
        private val provider: PageProvider,
        private val comicId: Long
    ) : Closeable {

        val pageCount: Int get() = provider.pageCount

        /**
         * 缩略图。**同步阻塞**，调用方自己切到 IO 线程。
         *
         * 只按缩略图宽度解码，不会把整页原图读进内存 —— 一个选页面板同时
         * 铺十几张原图，几十 MB 起步，很容易把堆吃穿。
         */
        suspend fun thumb(page: Int, maxWidth: Int = THUMB_WIDTH): Bitmap? =
            withContext(Dispatchers.IO) {
                runCatching {
                    provider.decode(page, maxWidth, maxWidth * 3 / 2, fullResolution = false)
                }.getOrNull()
            }

        /** 用这一页当封面。 */
        suspend fun applyPage(page: Int): String? = withContext(Dispatchers.IO) {
            val bitmap = runCatching {
                provider.decode(page, COVER_WIDTH, COVER_WIDTH * 3 / 2, fullResolution = false)
            }.getOrNull() ?: return@withContext null
            saveAndAttach(comicId, bitmap)
        }

        override fun close() {
            runCatching { provider.close() }
        }
    }

    /**
     * 用一张本地图片当封面。
     *
     * 只认图片文件；用户从文件管理器挑了个 PDF 之类的东西时解码会失败，
     * 这里返回 null，界面给一句「这个文件不是图片」，不覆盖原封面。
     */
    suspend fun applyImage(comicId: Long, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = decodeLocal(uri) ?: return@withContext null
        saveAndAttach(comicId, bitmap)
    }

    private suspend fun saveAndAttach(comicId: Long, bitmap: Bitmap): String? {
        val path = try {
            store.save(comicId, bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        } ?: return null
        comics.setCoverPath(comicId, path)
        return path
    }

    /**
     * 解码本地图片。
     *
     * 先只读尺寸再算采样率：相册里随手一张照片就是 4000px 宽，整张解出来
     * 几十 MB，而封面只要 480 —— 多解出来的像素纯属浪费，还可能直接 OOM。
     */
    private fun decodeLocal(uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        val width = bounds.outWidth
        if (width <= 0) return null

        var sample = 1
        while (width / (sample * 2) >= COVER_WIDTH) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private companion object {
        /** 与 [CoverStore] 内部的落盘宽度一致。 */
        const val COVER_WIDTH = 480

        /** 选页面板里一格的大小。再大也显示不出细节，只会拖慢滑动。 */
        const val THUMB_WIDTH = 240
    }
}
