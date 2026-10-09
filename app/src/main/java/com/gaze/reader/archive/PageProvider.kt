package com.gaze.reader.archive

import android.graphics.Bitmap
import android.graphics.Rect
import com.gaze.reader.image.PageSize
import java.io.Closeable

/**
 * 一本书的目录里的一条。
 *
 * PDF 目前拿不到（系统 `PdfRenderer` 不暴露大纲），EPUB 从 `nav.xhtml` / `toc.ncx` 里读。
 */
data class TocEntry(val title: String, val pageIndex: Int, val depth: Int = 0)

/**
 * 页面提供者 —— 阅读器唯一认识的「书」。
 *
 * 它把「第 N 页怎么变成一张位图」这件事**完全封装在格式实现里**：
 *
 * | 格式族 | 实现 | 第 N 页从哪来 |
 * |---|---|---|
 * | ZIP / RAR / 7Z / TAR | [ArchivePageProvider] | 解出第 N 张图片的字节，交给 [com.gaze.reader.image.PageDecoder] |
 * | PDF | [PdfPageProvider] | 系统 `PdfRenderer` 现渲（矢量，放大依然清晰） |
 * | EPUB | [EpubPageProvider] | 清单里的图片资源，或把章节文字现排成位图 |
 *
 * 这样 [com.gaze.reader.ui.screens.ReaderViewModel] 里那套
 * 「整页解码 / 区域解码 / 分块」的清晰度策略对**所有格式一视同仁** ——
 * 加一种格式不需要碰阅读器一行代码。
 *
 * 三个方法都必须是**线程安全**的（调用方会并发调用），但内部可以串行化：
 * `PdfRenderer` 一次只能开一页，归档库的通道游标也是共享的。
 */
interface PageProvider : Closeable {

    val pageCount: Int

    /** 展示用格式名（错误提示里会用到）。 */
    val formatLabel: String

    /** 目录。没有就返回空表。 */
    val toc: List<TocEntry> get() = emptyList()

    /**
     * 第 [index] 页的原始像素尺寸。
     *
     * 只读元信息，不解码像素 —— 连续纵向模式要在首帧就给每页摆好占位高度，
     * 这一步必须便宜。拿不到返回 null。
     */
    fun sizeOf(index: Int): PageSize?

    /**
     * 整页解码。
     *
     * @param reqWidth / [reqHeight] 目标显示尺寸；实现应保证**解码结果不小于它**
     *   （超采样），避免「低分辨率位图被放大」。
     * @param fullResolution 强制按原尺寸解（用户选了「原尺寸渲染」）。
     */
    fun decode(index: Int, reqWidth: Int, reqHeight: Int, fullResolution: Boolean): Bitmap?

    /**
     * 区域解码 —— 放大查看、超长条漫、超高分辨率扫描图都走这条。
     *
     * @param region **原图坐标**区域。
     * @param outWidth / [outHeight] 这一块在屏幕上的像素尺寸。
     */
    fun decodeRegion(index: Int, region: Rect, outWidth: Int, outHeight: Int): Bitmap?

    /**
     * 封面：直接给第一页的一张缩略位图。
     *
     * 单独抽出来是因为三种格式的「第一页」差别很大：归档要解第一张图、
     * PDF 要渲第一页、EPUB 要先找封面资源（`properties="cover-image"`），
     * 而封面生成器不该知道这些。
     */
    fun coverBitmap(maxWidth: Int): Bitmap? =
        decode(0, maxWidth, maxWidth * 3 / 2, fullResolution = false)

    /**
     * 文字型页面（EPUB 的纯文字章节）的前景 / 背景色。
     *
     * 阅读器背景是用户选的，纯文字章节必须跟着走 —— 黑底上用黑字等于没字。
     * 只对文字页有效，图片与 PDF 忽略它。默认白底黑字。
     */
    fun setTextColors(background: Int, foreground: Int) = Unit
}
