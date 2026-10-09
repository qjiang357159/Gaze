package com.gaze.reader.archive

import com.gaze.reader.core.ImageFormats
import com.gaze.reader.core.NaturalOrder

/**
 * 判断归档里的一条记录是否算「漫画页面」。
 *
 * 非图片文件一律忽略（文档第二章要求），同时顺手排掉几类常见的垃圾条目：
 * - macOS 打包产生的 `__MACOSX/` 资源叉；
 * - 隐藏文件（`.DS_Store`、`.hidden.jpg`）；
 * - 缩略图目录里的小图（`Thumbs.db` 本身不是图片，但有些包里有 `thumb/`）。
 */
internal fun isPageCandidate(name: String): Boolean {
    if (name.isEmpty()) return false
    if (name.startsWith("__MACOSX/") || name.contains("/__MACOSX/")) return false
    val base = name.substringAfterLast('/')
    if (base.isEmpty() || base.startsWith(".")) return false
    if (!ImageFormats.isImage(base)) return false
    return true
}

/**
 * 把「原始条目名 → 尺寸」的列表转成自然排序后的页面表。
 *
 * 所有归档实现共用这一段，保证 ZIP / RAR / 7Z / TAR 的页序规则完全一致 ——
 * 同一本漫画换成另一种压缩包，翻页顺序不该变。
 */
internal fun buildPages(entries: List<Pair<String, Long>>): List<ArchiveEntry> {
    val sorted = entries.sortedWith { a, b -> NaturalOrder.compare(a.first, b.first) }
    return sorted.mapIndexed { i, (name, size) -> ArchiveEntry(name, size, i) }
}
