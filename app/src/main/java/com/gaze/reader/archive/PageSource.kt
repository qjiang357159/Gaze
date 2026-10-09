package com.gaze.reader.archive

import com.gaze.reader.core.ArchiveFormat
import java.io.Closeable
import java.io.InputStream

/**
 * 页面提供者（文档第十八章的 Page Provider）。
 *
 * 职责边界很清楚：**它只把「第 N 页」翻译成一段字节**。
 * 不碰解码、不碰渲染、不碰 UI。
 *
 * 数据流刻意做成：
 * ```
 * 压缩包 -> 获取目录 -> 建立页面索引 -> 用户读第 50 页 -> 只读第 50 页 -> 解码 -> 显示
 * ```
 * 而不是「解压全部页面再阅读」。超大 CBZ 的可用性完全取决于这一点。
 */
class PageSource(
    private val reader: ArchiveReader,
    val format: ArchiveFormat,
    val displayName: String
) : Closeable {

    val pageCount: Int get() = reader.pageCount

    val pages: List<ArchiveEntry> get() = reader.pages

    /** 单页压缩字节的上限。超过它基本可以确定不是正常漫画页，直接拒绝以免 OOM。 */
    private val maxPageBytes = 192L * 1024 * 1024

    /** 打开某一页的流。越界或读取失败返回 null —— 单页坏了不该拖垮整本。 */
    fun openPage(index: Int): InputStream? {
        if (index < 0 || index >= pageCount) return null
        return try {
            reader.openPageAt(index)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 把某一页读成字节数组。
     *
     * 之所以整页读进来而不是流式解码：解码器（BitmapFactory / BitmapRegionDecoder）
     * 本身就要求一次拿到完整编码数据，而且漫画单页的**压缩字节**通常只有几百 KB 到几 MB，
     * 真正吃内存的是解码后的位图 —— 那部分由 [com.gaze.reader.image.PageDecoder]
     * 通过采样率控制。超过 [maxPageBytes] 的条目视为异常并放弃。
     */
    fun readPageBytes(index: Int): ByteArray? {
        val entry = pages.getOrNull(index) ?: return null
        if (entry.size > maxPageBytes) return null
        return try {
            reader.openPageAt(index)?.use { stream -> stream.readBytesCapped(maxPageBytes) }
        } catch (t: Throwable) {
            null
        }
    }

    /** 条目声明的原始尺寸，读不出时返回 -1。 */
    fun pageSize(index: Int): Long = pages.getOrNull(index)?.size ?: -1L

    override fun close() {
        runCatching { reader.close() }
    }
}

/**
 * 带上限的整段读取。
 *
 * 不能用 `InputStream.readBytes()` —— 它没有任何上限，遇到一个声明尺寸说谎
 * 或者解压后爆炸的条目会直接把堆撑爆。这里边读边累加，越界立刻放弃。
 */
private fun InputStream.readBytesCapped(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream(64 * 1024)
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > limit) throw java.io.IOException("页面数据超过上限")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
