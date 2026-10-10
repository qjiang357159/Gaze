package com.gaze.reader.backup

import org.json.JSONArray
import org.json.JSONObject

/** 备份包内的格式标识与版本。 */
object BackupFormat {
    const val MARKER = "gaze-backup"

    /**
     * 备份格式版本。
     *
     * - **1**：漫画 + 标签 + 文件夹 + 设置。
     * - **2**：补全漫画的 `folderUri` / `format` / `modifiedTime` /
     *   `manualTitle` / `manualAuthor`，标签补 `sortOrder`，
     *   并新增 `shelfCovers` 与 `sessions` 两段。
     *
     * 版本号只写进 manifest 供人看，**读取时不校验** —— 老备份照常能读
     * （缺的字段取默认值），新备份被老版本 App 读到也只是忽略多出来的键。
     */
    const val VERSION = 2

    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_DATABASE = "database.json"
    const val ENTRY_SETTINGS = "settings.json"

    /** 自定义 mime —— 用 `application/octet-stream` 会被系统强制改扩展名。 */
    const val MIME = "application/x-gaze-backup"
    const val EXTENSION = "gaze-backup"
}

/**
 * 漫画的跨设备引用。
 *
 * 备份与恢复两侧**必须用同一个算法** —— 一处用指纹、另一处用「文件名 + 大小」，
 * 恢复出来的阅读会话与自定义封面就会全部指不到漫画。所以抽成一个函数。
 */
fun comicRefOf(fingerprint: String?, fileName: String, size: Long): String =
    fingerprint ?: "name:$fileName|$size"

/**
 * 备份包里的一本漫画。**不含漫画原文件**，只存元数据。
 *
 * 字段划分成两拨：前一半是 v1 就有的核心元数据，后一半（`folderUri` 起）
 * 是 v2 补上的 —— 它们在 v1 里被漏掉了，恢复出来的记录会丢掉文件夹归属、
 * 容器格式和「用户手动改过」这两个意图标志。
 */
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
    val tagNames: List<String>,

    // ---------------------------------------------------------- v2 补全

    /**
     * 所属授权文件夹的 tree uri。
     *
     * 不备份它，恢复之后文件夹视图里所有漫画都会掉进「未归类」——
     * 用户看到的是「我的分类没了」，而数据库里其实一条记录都没少。
     */
    val folderUri: String? = null,

    /** 容器格式（ZIP / RAR / 7Z / TAR / PDF / EPUB）。缺了会被当成 ZIP，PDF 与 EPUB 直接打不开。 */
    val format: String? = null,

    val modifiedTime: Long = 0L,

    /** 用户手动改过标题 —— 「意图」比 title 本身更该保住。 */
    val manualTitle: Boolean = false,

    /** 用户手动改过作者。 */
    val manualAuthor: Boolean = false
) {
    /**
     * 跨设备的漫画引用。
     *
     * 恢复时本地 id 一定会变，所以会话与封面记录都用它回指漫画。
     * 优先用内容指纹，退化到「文件名 + 大小」—— 与匹配链路同一口径。
     */
    val ref: String
        get() = comicRefOf(fingerprint, fileName, size)

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
        // v2
        put("folderUri", folderUri ?: JSONObject.NULL)
        put("format", format ?: JSONObject.NULL)
        put("modifiedTime", modifiedTime)
        put("manualTitle", manualTitle)
        put("manualAuthor", manualAuthor)
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
            } ?: emptyList(),
            // v2 字段：老备份里不存在，取默认值
            folderUri = o.optStringOrNull("folderUri"),
            format = o.optStringOrNull("format"),
            modifiedTime = o.optLong("modifiedTime"),
            manualTitle = o.optBoolean("manualTitle"),
            manualAuthor = o.optBoolean("manualAuthor")
        )
    }
}

/** 备份包里的一个标签。 */
data class BackupTag(
    val name: String,
    val color: Int,
    val createdAt: Long,
    /**
     * 用户拖动排序后的位次。
     *
     * v1 漏了它，恢复回来的标签顺序会退化成「按创建时间」，
     * 用户精心排过的那一列顺序就白排了。
     */
    val sortOrder: Int? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("color", color)
        put("createdAt", createdAt)
        put("sortOrder", sortOrder ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = BackupTag(
            name = o.optString("name"),
            color = o.optInt("color"),
            createdAt = o.optLong("createdAt"),
            sortOrder = if (o.isNull("sortOrder")) null else o.optInt("sortOrder")
        )
    }
}

/**
 * 备份包里的一个文件夹。
 *
 * **只存 uri 与显示名 —— 权限本身存不下来。** SAF 的读权限绑定在
 * 「用户点过系统目录选择器」这件事上，跟备份文件无关。所以恢复时
 * 只能记成「待重新授权」，等用户自己再指一次。
 */
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

/**
 * 备份包里的一个「书架文件夹自定义封面」。
 *
 * [key] 形如 `FOLDER:<tree uri>` 或 `TAG:<tagId>`；后者里的 id 换设备就失效了，
 * 所以同时存一份 [tagName]，恢复时按名字重新解析成本地 id。
 * 指向的漫画用 [comicRef]（见 [BackupComic.ref]）而不是 id。
 */
data class BackupShelfCover(
    val key: String,
    val tagName: String?,
    val comicRef: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("tagName", tagName ?: JSONObject.NULL)
        put("comicRef", comicRef)
    }

    companion object {
        fun fromJson(o: JSONObject) = BackupShelfCover(
            key = o.optString("key"),
            tagName = o.optStringOrNull("tagName"),
            comicRef = o.optString("comicRef")
        )
    }
}

/** 备份包里的一次阅读会话。挂载的漫画同样用 [comicRef] 回指。 */
data class BackupSession(
    val comicRef: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val startPage: Int,
    val endPage: Int,
    val pagesRead: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("comicRef", comicRef)
        put("startedAt", startedAt)
        put("endedAt", endedAt)
        put("durationMs", durationMs)
        put("startPage", startPage)
        put("endPage", endPage)
        put("pagesRead", pagesRead)
    }

    companion object {
        fun fromJson(o: JSONObject) = BackupSession(
            comicRef = o.optString("comicRef"),
            startedAt = o.optLong("startedAt"),
            endedAt = o.optLong("endedAt"),
            durationMs = o.optLong("durationMs"),
            startPage = o.optInt("startPage"),
            endPage = o.optInt("endPage"),
            pagesRead = o.optInt("pagesRead")
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
    val error: String? = null,
    /**
     * 「漫画 ↔ 标签」关系条数。
     *
     * 恢复前就把它摆出来，是为了让「标签到底有没有被备份」这件事**当场可见** ——
     * 用户看到「12 个标签 · 340 条标签关系」，就不用等恢复完再猜是不是丢了。
     * 老备份（格式 v1）的 manifest 里没有这个键，读出来是 0。
     */
    val relationCount: Int = 0
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
    val pending: Int = 0,
    /** 本次恢复写入 / 命中的标签数。 */
    val tags: Int = 0,
    /** 本次恢复写回的「漫画 ↔ 标签」关系条数。 */
    val relations: Int = 0,
    /** 备份里记录、但需要用户重新授权的文件夹数。 */
    val pendingFolders: Int = 0
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
