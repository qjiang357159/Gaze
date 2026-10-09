package com.gaze.reader.core

import java.util.Locale

/**
 * 可阅读的文件格式。
 *
 * 前四个是**归档容器**（按需取页的压缩包），后两个是**文档格式**：
 * PDF 交给系统的 `PdfRenderer`，EPUB 是「带清单的 ZIP」。
 * 它们共用同一个枚举，是因为对上层来说它们回答的是同一个问题 ——
 * 「这本书有多少页、第 N 页怎么画出来」，而不是「里面是怎么装的」。
 */
enum class ArchiveFormat(val label: String, val extensions: List<String>) {
    ZIP("ZIP", listOf("zip", "cbz")),
    RAR("RAR", listOf("rar", "cbr")),
    SEVEN_Z("7Z", listOf("7z", "cb7")),
    TAR("TAR", listOf("tar", "cbt")),
    PDF("PDF", listOf("pdf")),
    EPUB("EPUB", listOf("epub")),
    UNKNOWN("?", emptyList());

    companion object {
        /** 是否为漫画专用扩展名（cbz / cbr / cbt / cb7）。 */
        fun isComicExtension(ext: String): Boolean = ext.lowercase(Locale.ROOT) in COMIC_EXT

        private val COMIC_EXT = setOf("cbz", "cbr", "cbt", "cb7")

        /** 所有被支持的文件扩展名。 */
        val ALL_EXT: Set<String> =
            (ZIP.extensions + RAR.extensions + SEVEN_Z.extensions +
                TAR.extensions + PDF.extensions + EPUB.extensions).toSet()

        fun of(fileName: String): ArchiveFormat {
            val ext = fileExtension(fileName)
            return entries.firstOrNull { ext in it.extensions } ?: UNKNOWN
        }
    }
}

/** 取出文件扩展名（小写，不含点）。 */
fun fileExtension(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    if (dot < 0 || dot == fileName.length - 1) return ""
    return fileName.substring(dot + 1).lowercase(Locale.ROOT)
}

/** 被识别为漫画页面的图片格式。 */
object ImageFormats {
    val EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "jpe", "png", "webp", "gif", "avif", "bmp", "heic", "heif"
    )

    fun isImage(name: String): Boolean = fileExtension(name) in EXTENSIONS
}

/** 文件大小 / 时间的展示格式化。 */
object Formatters {

    fun fileSize(bytes: Long, zeroAs: String = "未知"): String {
        if (bytes <= 0L) return zeroAs
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "${bytes} ${units[0]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unit])
        }
    }

    fun dateTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return "—"
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMillis
        return String.format(
            Locale.US, "%04d-%02d-%02d %02d:%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }

    fun date(epochMillis: Long): String {
        if (epochMillis <= 0L) return "—"
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMillis
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    /** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 具体日期。 */
    fun relative(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0L) return "未读"
        val diff = now - epochMillis
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
            else -> date(epochMillis)
        }
    }

    fun percent(value: Float): String = String.format(Locale.US, "%.0f%%", value * 100f)

    /**
     * 时长：毫秒 -> 「2 小时 13 分钟」/「13 分钟」/「42 秒」。
     *
     * 不做「1.5 小时」这种小数写法 —— 阅读时长是个粗略量级，
     * 精确到分钟已经足够，小数反而难读。
     */
    fun duration(ms: Long): String {
        if (ms <= 0L) return "0 分钟"
        val totalSeconds = ms / 1000L
        if (totalSeconds < 60L) return "$totalSeconds 秒"
        val totalMinutes = totalSeconds / 60L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "$hours 小时 $minutes 分钟"
            hours > 0L -> "$hours 小时"
            else -> "$minutes 分钟"
        }
    }
}
