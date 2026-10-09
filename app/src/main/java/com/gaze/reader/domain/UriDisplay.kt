package com.gaze.reader.domain

import android.net.Uri

/**
 * 把 SAF 的 `content://` 文档 uri 翻译成用户能看懂的位置。
 *
 * 直接显示 uri 会得到
 * `content://com.android.externalstorage.documents/document/primary%3AComics%2Fxxx.cbz`
 * 这种没法辨认的乱码 —— 百分号编码加一个没人认识的 authority。
 *
 * 认不出来时**老实返回解码后的原段**，不要假装知道一个其实不知道的绝对路径。
 */
object UriDisplay {

    fun readable(uriString: String): String {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return uriString
        val last = uri.lastPathSegment ?: return uriString

        var decoded = runCatching { Uri.decode(last) }.getOrDefault(last)

        // raw:/storage/emulated/0/... 形态
        if (decoded.startsWith("raw:")) decoded = decoded.removePrefix("raw:")

        // 已经是绝对路径
        if (decoded.startsWith("/")) return decoded

        // DownloadsProvider 的纯数字 id，认不出来就原样返回
        if (!decoded.contains(':')) return decoded

        val volume = decoded.substringBefore(':')
        val path = decoded.substringAfter(':')
        val root = when {
            volume.equals("primary", ignoreCase = true) -> "内部存储"
            volume.equals("home", ignoreCase = true) -> "文档"
            // 可移动存储的卷标形如 XXXX-XXXX
            volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "存储卡"
            else -> volume
        }
        return if (path.isBlank()) root else "$root/$path"
    }

    /** 只要最后一段的显示名（文件名 / 文件夹名）。 */
    fun displayName(uriString: String): String {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return uriString
        val last = uri.lastPathSegment ?: return uriString
        val decoded = runCatching { Uri.decode(last) }.getOrDefault(last)
        val cleaned = decoded.removePrefix("raw:")
        return cleaned.substringAfterLast('/').ifBlank { cleaned }
    }
}
