package com.gaze.reader.archive

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.LruCache
import com.gaze.reader.image.PageDecoder
import com.gaze.reader.image.PageSize

/**
 * 归档容器（ZIP / RAR / 7Z / TAR）的页面提供者。
 *
 * 行为与 v1.3.x 完全一致，只是把原先散在 `ReaderViewModel` 里的
 * 「编码字节 LRU + 归档读取串行锁」搬到了这里 —— 因为它们是**归档特有**的，
 * PDF 与 EPUB 不需要（也不该）共用。
 *
 * 锁用 `synchronized` 而不是协程 `Mutex`：这一层的接口是同步的
 * （`BitmapFactory` 要求一次拿到完整数据），调用方本来就在 IO 线程上，
 * 阻塞是正确的语义。套一层 `runBlocking` 只会把简单问题复杂化。
 */
class ArchivePageProvider(private val source: PageSource) : PageProvider {

    override val pageCount: Int get() = source.pageCount

    override val formatLabel: String get() = source.format.label

    /**
     * 压缩后的原始字节缓存（按字节数计量）。
     *
     * 放大时要用 `BitmapRegionDecoder` 反复解同一页的不同区域，
     * 每解一次都回压缩包里读一遍是不可接受的（7Z / RAR 解压一次是几十毫秒起）。
     * 编码字节比解码后的位图小一个数量级，缓存它最划算。
     */
    private val bytes = object : LruCache<Int, ByteArray>(encodedCacheBytes()) {
        override fun sizeOf(key: Int, value: ByteArray): Int = value.size
    }

    /** 归档读取串行化：ZipFile / SevenZFile 内部共用一个通道游标，并发读会互相踩。 */
    private val readLock = Any()

    private val sizeCache = HashMap<Int, PageSize>()

    override fun sizeOf(index: Int): PageSize? {
        synchronized(sizeCache) { sizeCache[index] }?.let { return it }
        val data = readBytes(index) ?: return null
        val size = PageDecoder.probe(data) ?: return null
        synchronized(sizeCache) { sizeCache[index] = size }
        return size
    }

    override fun decode(index: Int, reqWidth: Int, reqHeight: Int, fullResolution: Boolean): Bitmap? {
        val data = readBytes(index) ?: return null
        if (synchronized(sizeCache) { sizeCache[index] } == null) {
            PageDecoder.probe(data)?.let { synchronized(sizeCache) { sizeCache[index] = it } }
        }
        return PageDecoder.decode(data, reqWidth, reqHeight, fullResolution)
    }

    override fun decodeRegion(index: Int, region: Rect, outWidth: Int, outHeight: Int): Bitmap? {
        val data = readBytes(index) ?: return null
        return PageDecoder.decodeRegion(data, region, outWidth, outHeight)
    }

    private fun readBytes(index: Int): ByteArray? {
        bytes.get(index)?.let { return it }
        val data = synchronized(readLock) { source.readPageBytes(index) } ?: return null
        bytes.put(index, data)
        return data
    }

    override fun close() {
        runCatching { source.close() }
        bytes.evictAll()
    }
}

/** 编码字节缓存上限：堆的 1/16，夹在 8–48MB 之间。 */
private fun encodedCacheBytes(): Int {
    val heap = Runtime.getRuntime().maxMemory()
    val target = heap / 16
    return target.coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024).toInt()
}
