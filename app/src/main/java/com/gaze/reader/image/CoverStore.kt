package com.gaze.reader.image

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * 封面缩略图缓存（文档第十七章）。
 *
 * 「避免每次打开书架都重新解压第一张图片」是这里的唯一目的：
 * 首次扫描时把封面解出来、压成小 JPEG 落到应用私有目录，
 * 之后书架只读这几 KB 的文件，不再碰压缩包。
 *
 * 缓存带容量上限与 LRU 清理 —— 用户可能导入几千本，无上限的缓存迟早吃满存储。
 */
class CoverStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    /** 封面统一按这个宽度存。书架卡片最大也就 200dp 左右，480px 足够高清屏用。 */
    private val targetWidth = 480

    fun fileFor(comicId: Long): File = File(dir, "$comicId.jpg")

    /**
     * 把一张已解码的位图存成封面。
     *
     * @return 成功时返回文件绝对路径，失败返回 null（调用方应保留原值，不要清空）。
     */
    fun save(comicId: Long, bitmap: Bitmap): String? {
        return try {
            val scaled = scaleToWidth(bitmap)
            val file = fileFor(comicId)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            file.absolutePath
        } catch (t: Throwable) {
            null
        }
    }

    fun delete(comicId: Long) {
        runCatching { fileFor(comicId).delete() }
    }

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * 超过容量上限时按「最后修改时间」从旧到新删。
     *
     * 用文件自身的 mtime 而不是额外维护一张表 —— 封面是纯派生数据，
     * 删掉最多是下次重扫再生成一次，不值得为它引入状态。
     */
    fun trimToLimit(limitMb: Int) {
        val limit = limitMb.toLong() * 1024 * 1024
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= limit) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun scaleToWidth(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= targetWidth) return bitmap
        val ratio = targetWidth.toFloat() / bitmap.width
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, height, true)
    }
}
