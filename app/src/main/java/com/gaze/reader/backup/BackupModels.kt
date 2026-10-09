package com.gaze.reader.backup

import org.json.JSONArray
import org.json.JSONObject

/** 备份包内的格式标识与版本。 */
object BackupFormat {
    const val MARKER = "gaze-backup"
    const val VERSION = 1

    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_DATABASE = "database.json"
    const val ENTRY_SETTINGS = "settings.json"

    /** 自定义 mime —— 用 `application/octet-stream` 会被系统强制改扩展名。 */
    const val MIME = "application/x-gaze-backup"
    const val EXTENSION = "gaze-backup"
}

/** 备份包里的一本漫画。**不含漫画原文件**，只存元数据。 */
data class BackupComic(
    val fileName: String,
    val title: String,
    val author: String?,
    val size: Long,
    val pageCount: Int,
    val fingerprint: String?,
    val sampleHash: String?,
    val favorite: Boolean,
    val currentPage: Int,
    val progress: Float,
    val lastReadTime: Long,
    val addedTime: Long,
    val readMode: String?,
    val readDirection: String?,
    val fitMode: String?,
    val zoom: Float?,
    /** 扩展元数据（P2）：系列 / 卷号 / 备注 / 评分。 */
    val series: String? = null,
    val volume: Int? = null,
    val note: String? = null,
    val rating: Int? = null,
    val tagNames: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fileName", fileName)
        put("title", title)
        put("author", author ?: JSONObject.NULL)
        put("size", size)
        put("pageCount", pageCount)
        put("fingerprint", fingerprint ?: JSONObject.NULL)
        put("sampleHash", sampleHash ?: JSONObject.NULL)
        put("favorite", favorite)
        put("currentPage", currentPage)
        put("progress", progress.toDouble())
        put("lastReadTime", lastReadTime)
        put("addedTime", addedTime)
        put("readMode", readMode ?: JSONObject.NULL)
        put("readDirection", readDirection ?: JSONObject.NULL)
        put("fitMode", fitMode ?: JSONObject.NULL)
        put("zoom", zoom?.toDouble() ?: JSONObject.NULL)
        put("series", series ?: JSONObject.NULL)
        put("volume", volume ?: JSONObject.NULL)
        put("note", note ?: JSONObject.NULL)
        put("rating", rating ?: JSONObject.NULL)
        put("tagNames", JSONArray(tagNames))
    }

    companion object {
        fun fromJson(o: JSONObject): BackupComic = BackupComic(
            fileName = o.optString("fileName"),
            title = o.optString("title"),
            author = o.optStringOrNull("author"),
            size = o.optLong("size"),
            pageCount = o.optInt("pageCount"),
            fingerprint = o.optStringOrNull("fingerprint"),
            sampleHash = o.optStringOrNull("sampleHash"),
            favorite = o.optBoolean("favorite"),
            currentPage = o.optInt("currentPage"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            lastReadTime = o.optLong("lastReadTime"),
            addedTime = o.optLong("addedTime"),
            readMode = o.optStringOrNull("readMode"),
            readDirection = o.optStringOrNull("readDirection"),
            fitMode = o.optStringOrNull("fitMode"),
            zoom = if (o.isNull("zoom")) null else o.optDouble("zoom").toFloat(),
            // 老备份里没有这几个字段，读出来是 null —— 向后兼容
            series = o.optStringOrNull("series"),
            volume = if (o.isNull("volume")) null else o.optInt("volume"),
            note = o.optStringOrNull("note"),
            rating = if (o.isNull("rating")) null else o.optInt("rating"),
            tagNames = o.optJSONArray("tagNames")?.let { array ->
                (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
        )
    }
}

/** 备份包里的一个标签。 */
data class BackupTag(val name: String, val color: Int, val createdAt: Long) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("color", color)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = BackupTag(
            name = o.optString("name"),
            color = o.optInt("color"),
            createdAt = o.optLong("createdAt")
        )
    }
}

/** 备份包里的一个授权文件夹（只存 uri 与显示名，实际授权需要用户重新确认）。 */
data class BackupFolder(val uri: String, val displayName: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("displayName", displayName)
    }

    companion object {
        fun fromJson(o: JSONObject) = BackupFolder(
            uri = o.optString("uri"),
            displayName = o.optString("displayName")
        )
    }
}

/** 备份包的摘要信息，用于导入前展示。 */
data class BackupInfo(
    val appVersion: String,
    val createdAt: Long,
    val comicCount: Int,
    val tagCount: Int,
    val valid: Boolean,
    val error: String? = null
)

/** 恢复方式。 */
enum class RestoreMode(val label: String, val description: String) {
    MERGE(
        "合并恢复",
        "保留本地已有漫画，只把备份里的进度、收藏与标签合并进来；备份里有而本地没有的会标记为「待重新定位」。"
    ),
    OVERWRITE(
        "覆盖恢复",
        "先清空本地数据库，再整体按备份重建。本地的手动改名、标签调整都会被备份内容替换。"
    )
}

/** 备份 / 恢复的结果。 */
data class BackupResult(
    val success: Boolean,
    val message: String,
    val restored: Int = 0,
    val matched: Int = 0,
    val pending: Int = 0
)

/** 备份文件夹里的一个备份文件。 */
data class BackupEntry(
    val uri: String,
    val name: String,
    val size: Long,
    val modified: Long
)

/** 备份里的某本漫画在本地找不到。 */
data class MissingComic(
    val title: String,
    val fileName: String,
    val size: Long
)

/**
 * 文件匹配报告：恢复备份之后，核对备份里的每一本在本地还在不在。
 *
 * 换设备、换存储卡、整理目录之后，「备份里有、本地没有」是最常见的情况。
 * 不主动核对的话，用户只会看到书架里一堆「已失效」却不知道少了什么。
 */
data class MissingReport(
    val backupName: String,
    val total: Int,
    val matched: Int,
    val missing: List<MissingComic>
) {
    val allPresent: Boolean get() = missing.isEmpty()
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
