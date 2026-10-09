package com.gaze.reader.archive

import java.io.Closeable
import java.io.InputStream

/**
 * 归档内的一条页面记录。
 *
 * [name] 是归档里的原始条目路径（可能带子目录），
 * [index] 是它在**自然排序之后**的页序号，与 [ArchiveReader.pages] 的下标一致。
 */
data class ArchiveEntry(
    val name: String,
    val size: Long,
    val index: Int
)

/**
 * 归档读取层。
 *
 * 这一层只负责回答两个问题：
 *   1. 里面有哪些页面（[pages]）；
 *   2. 某一页的字节流怎么拿（[openPage]）。
 *
 * 它**不知道**图片怎么解码、也不知道 UI 长什么样 —— 这正是文档第十八章
 * 要求的「文件格式解析层与阅读器 UI 完全分离」。以后加格式只需新增一个实现。
 *
 * 关键约束：**不要解压全部页面**。实现必须是「按需读取」的，
 * 用户读第 50 页就只读第 50 页。超大 CBZ 尤其依赖这一点。
 */
interface ArchiveReader : Closeable {

    /** 归档内的图片条目，已按自然顺序排好，下标即页序号。 */
    val pages: List<ArchiveEntry>

    val pageCount: Int get() = pages.size

    /** 打开某一页的数据流。调用方负责关闭。 */
    fun openPage(entry: ArchiveEntry): InputStream

    /** 打开第 [index] 页。越界返回 null。 */
    fun openPageAt(index: Int): InputStream? {
        val entry = pages.getOrNull(index) ?: return null
        return openPage(entry)
    }

    /** 归档格式的展示名，用于错误提示。 */
    val formatLabel: String
}
