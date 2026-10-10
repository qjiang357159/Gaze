package com.gaze.reader.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.gaze.reader.BuildConfig
import com.gaze.reader.core.AutoBackupInterval
import com.gaze.reader.core.FitMode
import com.gaze.reader.core.LibraryView
import com.gaze.reader.core.ReadMode
import com.gaze.reader.core.ReaderBackground
import com.gaze.reader.core.RenderMode
import com.gaze.reader.core.ScreenOrientation
import com.gaze.reader.core.ThemeMode
import com.gaze.reader.core.ThemeVariant
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.db.ReadingSessionEntity
import com.gaze.reader.data.repository.ComicRepository
import com.gaze.reader.data.repository.FolderRepository
import com.gaze.reader.data.repository.StatsRepository
import com.gaze.reader.data.repository.TagRepository
import com.gaze.reader.data.prefs.SettingsRepository
import com.gaze.reader.domain.UriDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复（文档第十五章）。
 *
 * 设计要点：
 *
 * 1. **只备份元数据，不复制漫画原文件。** 几百 GB 的漫画库如果连原文件一起打包，
 *    备份会大到没法用。备份里是阅读进度、收藏、标签、数据库、设置、文件夹授权。
 * 2. **恢复时绝不用文件路径当标识。** 用户换设备、整理目录之后，
 *    路径几乎必然变化。这里按「内容指纹 -> 采样哈希 -> 文件名 + 大小」三级匹配，
 *    尽可能把备份里的记录接回本地文件；接不上的**不删除历史数据**，
 *    而是新建一条「待重新定位」的记录，等用户重新指定文件时再绑回去。
 */
class BackupRepository(
    private val context: Context,
    private val comics: ComicRepository,
    private val tags: TagRepository,
    private val folders: FolderRepository,
    private val stats: StatsRepository,
    private val settings: SettingsRepository
) {

    private val resolver get() = context.contentResolver

    // ================================================================ 导出

    /**
     * 导出备份。
     *
     * 先在应用私有缓存里生成整包，再整体复制到目标位置 —— 直接往
     * `openOutputStream(uri)` 返回的流里写 ZIP 有两个坑：各家 DocumentsProvider
     * 对写入模式支持不一致；写到一半抛异常就留下一个损坏的半成品文件。
     */
    suspend fun export(targetUri: Uri, displayName: String): BackupResult = withContext(Dispatchers.IO) {
        var temp: File? = null
        try {
            temp = File(context.cacheDir, "gaze-export-${System.currentTimeMillis()}.tmp")
            writeArchive(temp)
            copyToTarget(temp, targetUri)
            runCatching {
                resolver.takePersistableUriPermission(
                    targetUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            settings.rememberExport(targetUri.toString(), displayName)
            BackupResult(success = true, message = "备份已导出：$displayName")
        } catch (t: Throwable) {
            BackupResult(success = false, message = "导出失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { temp?.delete() }
        }
    }

    private suspend fun writeArchive(file: File) {
        val comicList = comics.getAll()
        val tagList = tags.getAll()
        val relations = tags.allRelations()
        // 只导出真正拿到过权限的文件夹。待重新授权的那些本来就没授权，
        // 把它们写进备份等于把一个「没生效的授权」再传播给下一台设备。
        val folderList = folders.getAuthorized()
        val shelfCovers = comics.getShelfCovers()
        val sessions = stats.getAll()

        val tagNameById = tagList.associate { it.id to it.name }
        val tagsByComic = relations.groupBy(
            keySelector = { it.comicId },
            valueTransform = { tagNameById[it.tagId].orEmpty() }
        ).mapValues { (_, names) -> names.filter { it.isNotBlank() } }

        // 漫画 id -> 跨设备引用。阅读会话与自定义封面都靠它回指漫画 ——
        // 存 id 的话换台设备就全指到别人身上去了。
        val refByComicId = comicList.associate { it.id to comicRefOf(it.fingerprint, it.fileName, it.size) }

        val database = JSONObject().apply {
            put(
                "comics",
                JSONArray().apply {
                    comicList.forEach { comic ->
                        put(
                            BackupComic(
                                fileName = comic.fileName,
                                title = comic.title,
                                author = comic.author,
                                size = comic.size,
                                pageCount = comic.pageCount,
                                fingerprint = comic.fingerprint,
                                sampleHash = comic.sampleHash,
                                favorite = comic.favorite,
                                currentPage = comic.currentPage,
                                progress = comic.progress,
                                lastReadTime = comic.lastReadTime,
                                addedTime = comic.addedTime,
                                readMode = comic.readMode,
                                readDirection = comic.readDirection,
                                fitMode = comic.fitMode,
                                zoom = comic.zoom,
                                series = comic.series,
                                volume = comic.volume,
                                note = comic.note,
                                rating = comic.rating,
                                tagNames = tagsByComic[comic.id].orEmpty(),
                                // v2 补全的字段
                                folderUri = comic.folderUri,
                                format = comic.format,
                                modifiedTime = comic.modifiedTime,
                                manualTitle = comic.manualTitle,
                                manualAuthor = comic.manualAuthor
                            ).toJson()
                        )
                    }
                }
            )
            put(
                "tags",
                JSONArray().apply {
                    tagList.forEach { put(BackupTag(it.name, it.color, it.createdAt, it.sortOrder).toJson()) }
                }
            )
            put(
                "folders",
                JSONArray().apply { folderList.forEach { put(BackupFolder(it.uri, it.displayName).toJson()) } }
            )
            put(
                "shelfCovers",
                JSONArray().apply {
                    shelfCovers.forEach { cover ->
                        val ref = refByComicId[cover.comicId] ?: return@forEach
                        put(
                            BackupShelfCover(
                                key = cover.key,
                                // TAG:<id> 里的 id 换设备就废了，额外记一份标签名好重建
                                tagName = cover.key
                                    .takeIf { it.startsWith(ComicRepository.TAG_PREFIX) }
                                    ?.removePrefix(ComicRepository.TAG_PREFIX)
                                    ?.toLongOrNull()
                                    ?.let { tagNameById[it] },
                                comicRef = ref
                            ).toJson()
                        )
                    }
                }
            )
            put(
                "sessions",
                JSONArray().apply {
                    sessions.forEach { session ->
                        val ref = refByComicId[session.comicId] ?: return@forEach
                        put(
                            BackupSession(
                                comicRef = ref,
                                startedAt = session.startedAt,
                                endedAt = session.endedAt,
                                durationMs = session.durationMs,
                                startPage = session.startPage,
                                endPage = session.endPage,
                                pagesRead = session.pagesRead
                            ).toJson()
                        )
                    }
                }
            )
        }

        val current = settings.current()
        val settingsJson = JSONObject().apply {
            put("themeMode", current.themeMode.key)
            put("themeVariant", current.themeVariant.key)
            put("themeSeed", current.themeSeed)
            put("readerBackground", current.readerBackground.key)
            put("defaultReadMode", current.defaultReadMode.key)
            put("defaultFitMode", current.defaultFitMode.key)
            put("renderMode", current.renderMode.key)
            put("libraryView", current.libraryView.key)
            put("coverCacheLimitMb", current.coverCacheLimitMb)
            put("screenOrientation", current.screenOrientation.key)
            put("brightnessFollowSystem", current.brightnessFollowSystem)
            put("brightness", current.brightness.toDouble())
            put("volumeKeyPaging", current.volumeKeyPaging)
            put("keepScreenOn", current.keepScreenOn)
            put("showPageNumber", current.showPageNumber)
            // JSON 键沿用旧名，老备份读进来仍能对上；语义即「按分隔符解析」。
            put("parseAuthorFromFileName", current.parseAuthorBySeparator)
            put("authorSeparator", current.authorSeparator)
            put("parseAuthorByBracket", current.parseAuthorByBracket)
            put("authorBracket", current.authorBracket)
        }

        val manifest = JSONObject().apply {
            put("format", BackupFormat.MARKER)
            put("version", BackupFormat.VERSION)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("createdAt", System.currentTimeMillis())
            put("comicCount", comicList.size)
            put("tagCount", tagList.size)
            // 下面三个是诊断用的：恢复完发现「标签没进来」时，
            // 第一件事就是打开备份看看它到底有没有被写进去。
            put("relationCount", relations.size)
            put("sessionCount", sessions.size)
            put("shelfCoverCount", shelfCovers.size)
            put("containsOriginalFiles", false)
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            zip.putText(BackupFormat.ENTRY_MANIFEST, manifest)
            zip.putText(BackupFormat.ENTRY_DATABASE, database)
            zip.putText(BackupFormat.ENTRY_SETTINGS, settingsJson)
        }
    }

    private fun ZipOutputStream.putText(name: String, json: JSONObject) {
        putNextEntry(ZipEntry(name))
        write(json.toString().toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /**
     * 复制到 SAF 目标。
     *
     * 按 `w` -> `wt` -> `rwt` 逐个降级，但**只有「打不开流」才换下一种** ——
     * 流已经打开之后的写入异常换模式没用，只会把真实原因盖掉。
     */
    private fun copyToTarget(source: File, target: Uri) {
        var lastError: Throwable? = null
        for (mode in listOf("w", "wt", "rwt")) {
            try {
                val out = resolver.openOutputStream(target, mode)
                if (out == null) {
                    lastError = IOException("无法打开输出流（$mode）")
                    continue
                }
                out.use { sink -> FileInputStream(source).use { it.copyTo(sink) } }
                return
            } catch (t: FileNotFoundException) {
                lastError = t
            } catch (t: SecurityException) {
                lastError = t
            }
        }
        throw lastError ?: IOException("无法写入目标位置")
    }

    // ================================================================ 读取摘要

    /** 只读 manifest，用于导入前给用户看清楚「这是什么时候、多少本」。 */
    suspend fun peek(sourceUri: Uri): BackupInfo = withContext(Dispatchers.IO) {
        try {
            openArchive(sourceUri) { entries ->
                val manifest = entries[BackupFormat.ENTRY_MANIFEST] ?: return@openArchive BackupInfo(
                    appVersion = "", createdAt = 0, comicCount = 0, tagCount = 0,
                    valid = false, error = "备份包里缺少 manifest.json"
                )
                val json = JSONObject(String(manifest, Charsets.UTF_8))
                if (json.optString("format") != BackupFormat.MARKER) {
                    return@openArchive BackupInfo(
                        appVersion = "", createdAt = 0, comicCount = 0, tagCount = 0,
                        valid = false, error = "这不是 Gaze 备份文件"
                    )
                }
                BackupInfo(
                    appVersion = json.optString("appVersion"),
                    createdAt = json.optLong("createdAt"),
                    comicCount = json.optInt("comicCount"),
                    tagCount = json.optInt("tagCount"),
                    relationCount = json.optInt("relationCount"),
                    valid = true
                )
            }
        } catch (t: Throwable) {
            BackupInfo(
                appVersion = "", createdAt = 0, comicCount = 0, tagCount = 0,
                valid = false, error = "无法读取备份：${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    // ================================================================ 恢复

    suspend fun restore(sourceUri: Uri, mode: RestoreMode): BackupResult = withContext(Dispatchers.IO) {
        try {
            openArchive(sourceUri) { entries ->
                val manifestBytes = entries[BackupFormat.ENTRY_MANIFEST]
                    ?: return@openArchive BackupResult(false, "备份包里缺少 manifest.json")
                val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                if (manifest.optString("format") != BackupFormat.MARKER) {
                    return@openArchive BackupResult(false, "这不是 Gaze 备份文件")
                }
                val dbBytes = entries[BackupFormat.ENTRY_DATABASE]
                    ?: return@openArchive BackupResult(false, "备份包里缺少 database.json")

                val database = JSONObject(String(dbBytes, Charsets.UTF_8))
                val backupComics = database.optJSONArray("comics").toBackupComics()
                val backupTags = database.optJSONArray("tags").toBackupTags()
                val backupFolders = database.optJSONArray("folders").toBackupFolders()
                val backupCovers = database.optJSONArray("shelfCovers").toBackupShelfCovers()
                val backupSessions = database.optJSONArray("sessions").toBackupSessions()
                entries[BackupFormat.ENTRY_SETTINGS]?.let { applySettings(JSONObject(String(it, Charsets.UTF_8))) }

                if (mode == RestoreMode.OVERWRITE) {
                    comics.clearAll()
                }

                // ---------------------------------------------------------- 标签
                //
                // 先把标签全部落地，后面挂关系才有 id 可用。
                // 起点是**当前库里已有的标签**而不是空表：合并恢复时本地本来就有标签，
                // 只认备份里的那批会让「本地有、备份没有」的标签在挂关系时被当成不存在。
                val tagIdByName = HashMap<String, Long>()
                tags.getAll().forEach { tag -> tagIdByName[tag.name] = tag.id }
                for (tag in backupTags) {
                    val id = tags.restore(tag.name, tag.color, tag.createdAt, tag.sortOrder)
                    if (id > 0L) tagIdByName[tag.name] = id
                }

                // ---------------------------------------------------------- 文件夹
                //
                // **只登记成「待重新授权」，绝不冒充已授权。** 备份文件里存不下 SAF 读权限，
                // 旧实现直接调 folders.add(...) 写库，界面上就出现一个「已授权」的文件夹，
                // 但每次扫描都读不到任何东西，用户完全不知道为什么。
                for (folder in backupFolders) {
                    folders.addPending(folder.uri, folder.displayName)
                }
                val pendingFolders = folders.getAll().count { it.pending == true }

                // ---------------------------------------------------------- 漫画
                val local = comics.getAll()
                val byFingerprint = HashMap<String, ComicEntity>()
                val bySampleHash = HashMap<String, ComicEntity>()
                val byFileNameSize = HashMap<String, ComicEntity>()
                // 非失效的优先：同一份文件既有「已失效」的旧记录、又有新扫进来的记录时，
                // 必须命中后者，否则恢复出来的条目会一直挂着「已失效」。
                local.sortedBy { if (it.missing) 1 else 0 }.forEach { comic ->
                    comic.fingerprint?.let { byFingerprint.putIfAbsent(it, comic) }
                    comic.sampleHash?.let { bySampleHash.putIfAbsent(it, comic) }
                    byFileNameSize.putIfAbsent("${comic.fileName}|${comic.size}", comic)
                }

                var restored = 0
                var matched = 0
                var pending = 0
                var relationsWritten = 0
                val refToNewId = HashMap<String, Long>()

                for ((index, record) in backupComics.withIndex()) {
                    val hit = record.fingerprint?.let { byFingerprint[it] }
                        ?: record.sampleHash?.let { bySampleHash[it] }
                        ?: byFileNameSize["${record.fileName}|${record.size}"]

                    val targetId: Long
                    if (hit != null) {
                        matched++
                        comics.upsert(
                            hit.copy(
                                favorite = record.favorite,
                                currentPage = record.currentPage,
                                progress = record.progress,
                                lastReadTime = record.lastReadTime,
                                readMode = record.readMode ?: hit.readMode,
                                readDirection = record.readDirection ?: hit.readDirection,
                                fitMode = record.fitMode ?: hit.fitMode,
                                zoom = record.zoom ?: hit.zoom,
                                // 元数据同上：备份里有就用备份的，没有就保留本地
                                series = record.series ?: hit.series,
                                volume = record.volume ?: hit.volume,
                                note = record.note ?: hit.note,
                                rating = record.rating ?: hit.rating,
                                // 文件夹归属：本地扫描出来的更准，本地没有才用备份的
                                folderUri = hit.folderUri ?: record.folderUri,
                                // 标题 / 作者谁说了算：本机手改 > 备份里手改 > 备份里的值
                                title = if (hit.manualTitle) hit.title else record.title,
                                author = if (hit.manualAuthor) hit.author else record.author,
                                // 但「手改过」这个事实要合并，不能因为换台设备就丢掉
                                manualTitle = hit.manualTitle || record.manualTitle,
                                manualAuthor = hit.manualAuthor || record.manualAuthor
                            )
                        )
                        targetId = hit.id
                    } else {
                        // 匹配不上：新建一条「待重新定位」记录，**不删任何历史数据**
                        pending++
                        // 占位 uri 只用来占住「这条记录没有文件」这个位置，不参与匹配。
                        // 但它必须**唯一** —— uri 上有唯一索引，两条撞上会触发 REPLACE，
                        // 后一条会把前一条连同它的标签与阅读进度一起删掉。
                        // 所以带上循环下标：老写法只用 `fileName.hashCode()`，
                        // 同名的两份文件（不同目录）会互相顶掉。
                        val placeholder = "gaze://missing/$index-${record.fileName.hashCode()}-${record.size}"
                        targetId = comics.upsert(
                            ComicEntity(
                                uri = placeholder,
                                fileName = record.fileName,
                                title = record.title,
                                author = record.author,
                                // 备份里记了格式就用它 —— 一律写成 ZIP 会让 PDF / EPUB 打不开
                                format = record.format ?: "ZIP",
                                size = record.size,
                                modifiedTime = record.modifiedTime,
                                pageCount = record.pageCount,
                                favorite = record.favorite,
                                addedTime = record.addedTime,
                                lastReadTime = record.lastReadTime,
                                currentPage = record.currentPage,
                                progress = record.progress,
                                readMode = record.readMode,
                                readDirection = record.readDirection,
                                fitMode = record.fitMode,
                                zoom = record.zoom,
                                series = record.series,
                                volume = record.volume,
                                note = record.note,
                                rating = record.rating,
                                fingerprint = record.fingerprint,
                                sampleHash = record.sampleHash,
                                folderUri = record.folderUri,
                                manualTitle = record.manualTitle,
                                manualAuthor = record.manualAuthor,
                                missing = true
                            )
                        )
                    }

                    if (targetId > 0L) {
                        refToNewId[record.ref] = targetId
                        relationsWritten += applyTags(targetId, record.tagNames, tagIdByName)
                    }
                    restored++
                }

                // ---------------------------------------------------------- 自定义封面
                //
                // 覆盖恢复时 clearAll 已经把旧记录清了；合并恢复时逐条覆盖同 key 的记录。
                for (cover in backupCovers) {
                    val comicId = refToNewId[cover.comicRef] ?: continue
                    val key = when {
                        // TAG:<旧 id> 换设备就废了，按标签名解析成本地的新 id
                        cover.key.startsWith(ComicRepository.TAG_PREFIX) -> {
                            val name = cover.tagName ?: continue
                            val newTagId = tagIdByName[name] ?: continue
                            ComicRepository.TAG_PREFIX + newTagId
                        }
                        else -> cover.key
                    }
                    comics.setShelfCover(key, comicId)
                }

                // ---------------------------------------------------------- 阅读会话
                //
                // 按 startedAt 去重：合并恢复反复跑同一份备份时，不该把同一段阅读
                // 记成好几次。覆盖恢复时本地是空的，这条判据自然全放行。
                val existingStarts = stats.getAll().mapTo(HashSet()) { it.startedAt }
                val sessionRows = ArrayList<ReadingSessionEntity>()
                for (session in backupSessions) {
                    if (session.startedAt in existingStarts) continue
                    val comicId = refToNewId[session.comicRef] ?: continue
                    sessionRows += ReadingSessionEntity(
                        comicId = comicId,
                        startedAt = session.startedAt,
                        endedAt = session.endedAt,
                        durationMs = session.durationMs,
                        startPage = session.startPage,
                        endPage = session.endPage,
                        pagesRead = session.pagesRead
                    )
                }
                val sessionsWritten = stats.restoreAll(sessionRows)

                BackupResult(
                    success = true,
                    message = buildString {
                        append("已恢复 $restored 本")
                        if (matched > 0) append("，其中 $matched 本直接匹配到本地文件")
                        if (pending > 0) append("，$pending 本待重新定位")
                        if (backupTags.isNotEmpty() || relationsWritten > 0) {
                            append("。标签 ${backupTags.size} 个，挂回关系 $relationsWritten 条")
                        }
                        if (sessionsWritten > 0) append("，阅读记录 $sessionsWritten 条")
                        if (pendingFolders > 0) {
                            append("。$pendingFolders 个文件夹需要你重新授权后才能扫描")
                        }
                    },
                    restored = restored,
                    matched = matched,
                    pending = pending,
                    tags = backupTags.size,
                    relations = relationsWritten,
                    pendingFolders = pendingFolders
                )
            }
        } catch (t: Throwable) {
            BackupResult(false, "恢复失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun applyTags(comicId: Long, names: List<String>, idByName: Map<String, Long>): Int {
        if (names.isEmpty()) return 0
        val ids = names.mapNotNull { idByName[it] }
        if (ids.isEmpty()) return 0
        // 直接 `INSERT OR IGNORE`：已存在的关系自动跳过，本来就是幂等的差集语义，
        // 但省掉了 addTo 里「先查一遍已存在关系」的那次 IO —— 几千本逐本查库很慢。
        return tags.attachAll(comicId, ids)
    }

    /**
     * 恢复设置。
     *
     * 每个字段都先 `has()` 再写 —— 老备份里没有的键直接跳过，
     * 保持用户当前的设置，而不是被 `optString()` 的空串打成默认值。
     * 同理，`readDirection` 这种已废弃的键读到了也**直接忽略**。
     */
    private suspend fun applySettings(json: JSONObject) {
        if (json.has("themeMode")) settings.setThemeMode(ThemeMode.of(json.optString("themeMode")))
        if (json.has("themeVariant")) {
            settings.setThemeVariant(ThemeVariant.of(json.optString("themeVariant")))
        }
        if (json.has("themeSeed")) settings.setThemeSeed(json.optInt("themeSeed"))
        if (json.has("readerBackground")) {
            settings.setReaderBackground(ReaderBackground.of(json.optString("readerBackground")))
        }
        if (json.has("defaultReadMode")) {
            settings.setDefaultReadMode(ReadMode.of(json.optString("defaultReadMode")))
        }
        if (json.has("defaultFitMode")) {
            settings.setDefaultFitMode(FitMode.of(json.optString("defaultFitMode")))
        }
        if (json.has("renderMode")) {
            settings.setRenderMode(RenderMode.of(json.optString("renderMode")))
        }
        if (json.has("libraryView")) {
            settings.setLibraryView(LibraryView.of(json.optString("libraryView")))
        }
        if (json.has("screenOrientation")) {
            settings.setScreenOrientation(ScreenOrientation.of(json.optString("screenOrientation")))
        }
        if (json.has("brightnessFollowSystem")) {
            settings.setBrightnessFollowSystem(json.optBoolean("brightnessFollowSystem"))
        }
        if (json.has("brightness")) {
            settings.setBrightness(json.optDouble("brightness", 0.5).toFloat())
        }
        if (json.has("volumeKeyPaging")) settings.setVolumeKeyPaging(json.optBoolean("volumeKeyPaging"))
        if (json.has("keepScreenOn")) settings.setKeepScreenOn(json.optBoolean("keepScreenOn"))
        if (json.has("showPageNumber")) settings.setShowPageNumber(json.optBoolean("showPageNumber"))
        if (json.has("parseAuthorFromFileName")) {
            settings.setParseAuthorBySeparator(json.optBoolean("parseAuthorFromFileName"))
        }
        if (json.has("authorSeparator")) {
            settings.setAuthorSeparator(json.optString("authorSeparator"))
        }
        // 老备份没有这两个键 → 保持用户当前值，不被打成默认值。
        if (json.has("parseAuthorByBracket")) {
            settings.setParseAuthorByBracket(json.optBoolean("parseAuthorByBracket"))
        }
        if (json.has("authorBracket")) {
            settings.setAuthorBracket(json.optString("authorBracket"))
        }
    }

    // ================================================================ 工具

    /**
     * 读出 ZIP 内需要的条目。
     *
     * `ZipInputStream.readBytes()` 在条目末尾返回 -1，所以一次 readBytes 读到的
     * 就是**当前条目**，不必自己算长度。
     */
    private inline fun <T> openArchive(sourceUri: Uri, block: (Map<String, ByteArray>) -> T): T {
        val wanted = setOf(
            BackupFormat.ENTRY_MANIFEST,
            BackupFormat.ENTRY_DATABASE,
            BackupFormat.ENTRY_SETTINGS
        )
        val entries = HashMap<String, ByteArray>()
        val input = resolver.openInputStream(sourceUri) ?: throw IOException("无法打开备份文件")
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (entry.name in wanted) entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return block(entries)
    }

    private fun JSONArray?.toBackupComics(): List<BackupComic> {
        if (this == null) return emptyList()
        return (0 until length()).map { BackupComic.fromJson(optJSONObject(it) ?: JSONObject()) }
    }

    private fun JSONArray?.toBackupTags(): List<BackupTag> {
        if (this == null) return emptyList()
        return (0 until length()).map { BackupTag.fromJson(optJSONObject(it) ?: JSONObject()) }
    }

    private fun JSONArray?.toBackupFolders(): List<BackupFolder> {
        if (this == null) return emptyList()
        return (0 until length()).map { BackupFolder.fromJson(optJSONObject(it) ?: JSONObject()) }
    }

    private fun JSONArray?.toBackupShelfCovers(): List<BackupShelfCover> {
        if (this == null) return emptyList()
        return (0 until length()).map { BackupShelfCover.fromJson(optJSONObject(it) ?: JSONObject()) }
    }

    private fun JSONArray?.toBackupSessions(): List<BackupSession> {
        if (this == null) return emptyList()
        return (0 until length()).map { BackupSession.fromJson(optJSONObject(it) ?: JSONObject()) }
    }

    // ================================================================ 备份文件夹

    /**
     * 列出备份文件夹里的所有备份，按时间倒序。
     *
     * 直接查 SAF 的子文档，不缓存 —— 用户可能在文件管理器里删过文件，
     * 缓存列表只会让他对着一条已经不存在的记录反复点。
     */
    suspend fun listBackups(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val folder = settings.current().backupFolderUri ?: return@withContext emptyList()
        queryChildren(Uri.parse(folder))
            .filter { it.name.endsWith(".${BackupFormat.EXTENSION}", ignoreCase = true) }
            .sortedByDescending { it.modified }
    }

    private fun queryChildren(treeUri: Uri): List<BackupEntry> {
        val entries = mutableListOf<BackupEntry>()
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return entries
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    entries += BackupEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                        name = name,
                        size = cursor.getLong(2),
                        modified = cursor.getLong(3)
                    )
                }
            }
        }
        return entries
    }

    /** 导出到已指定的备份文件夹 —— 不必每次都过一遍系统文件选择器。 */
    suspend fun exportToFolder(displayName: String): BackupResult {
        val folder = settings.current().backupFolderUri
            ?: return BackupResult(false, "还没有指定备份文件夹")
        val target = runCatching { createDocumentIn(Uri.parse(folder), displayName) }
            .getOrElse { return BackupResult(false, "无法在备份文件夹里创建文件：${it.message}") }
        return export(target, displayName)
    }

    private fun createDocumentIn(treeUri: Uri, displayName: String): Uri {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return DocumentsContract.createDocument(resolver, parent, BackupFormat.MIME, displayName)
            ?: throw IOException("无法在备份文件夹里创建文件")
    }

    /**
     * 到点就自动备份一次。
     *
     * @param force 用户手动点「立即备份」时传 true —— 跳过「关闭 / 没到点」两道闸门，
     *   但仍然要求已经指定备份文件夹（没有落点就无从备份）。
     * @return 本次真的备份了才返回结果；没到点 / 没开 / 没指定文件夹都返回 null。
     */
    suspend fun runAutoBackupIfDue(force: Boolean = false): BackupResult? {
        val current = settings.current()
        if (current.backupFolderUri.isNullOrBlank()) {
            return if (force) BackupResult(false, "请先指定备份文件夹") else null
        }
        if (!force && current.autoBackupInterval == AutoBackupInterval.OFF) return null

        val now = System.currentTimeMillis()
        if (!force) {
            val last = current.lastAutoBackupTime
            if (last > 0L && now - last < current.autoBackupInterval.durationMs) return null
        }

        val result = exportToFolder(autoBackupFileName())
        if (result.success) {
            settings.rememberAutoBackup(now)
            pruneAutoBackups(current.autoBackupKeep)
        }
        return result
    }

    /**
     * 自动备份只保留最近 N 份。
     *
     * **只删自动生成的**（文件名带 `gaze-auto-` 前缀）——
     * 用户手动导出的备份在同一个文件夹里，被程序悄悄删掉是不可接受的。
     */
    private suspend fun pruneAutoBackups(keep: Int) {
        listBackups()
            .filter { it.name.startsWith(AUTO_PREFIX) }
            .drop(keep)
            .forEach { runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(it.uri)) } }
    }

    /**
     * 文件匹配：核对备份里的漫画，本地还剩几本。
     *
     * 匹配口径与恢复时完全一致（指纹 -> 采样哈希 -> 文件名+大小），
     * 所以「这里说在、恢复时却说不在」这种事不会发生。
     */
    suspend fun verify(sourceUri: Uri): MissingReport = withContext(Dispatchers.IO) {
        try {
            openArchive(sourceUri) { entries ->
                val dbBytes = entries[BackupFormat.ENTRY_DATABASE]
                    ?: return@openArchive MissingReport(
                        backupName = UriDisplay.displayName(sourceUri.toString()),
                        total = 0, matched = 0, missing = emptyList()
                    )
                val records = JSONObject(String(dbBytes, Charsets.UTF_8))
                    .optJSONArray("comics").toBackupComics()

                val local = comics.getAll()
                val byFingerprint = HashMap<String, ComicEntity>()
                val bySampleHash = HashMap<String, ComicEntity>()
                val byFileNameSize = HashMap<String, ComicEntity>()
                // 与 restore 同一口径：非失效的优先。两边不一致的话，
                // 就会出现「这里说在、恢复时却说不在」。
                local.sortedBy { if (it.missing) 1 else 0 }.forEach { comic ->
                    comic.fingerprint?.let { byFingerprint.putIfAbsent(it, comic) }
                    comic.sampleHash?.let { bySampleHash.putIfAbsent(it, comic) }
                    byFileNameSize.putIfAbsent("${comic.fileName}|${comic.size}", comic)
                }

                val missing = mutableListOf<MissingComic>()
                var matched = 0
                records.forEach { record ->
                    val hit = record.fingerprint?.let { byFingerprint[it] }
                        ?: record.sampleHash?.let { bySampleHash[it] }
                        ?: byFileNameSize["${record.fileName}|${record.size}"]
                    if (hit != null) {
                        matched++
                    } else {
                        missing += MissingComic(record.title, record.fileName, record.size)
                    }
                }
                MissingReport(
                    backupName = UriDisplay.displayName(sourceUri.toString()),
                    total = records.size,
                    matched = matched,
                    missing = missing
                )
            }
        } catch (t: Throwable) {
            MissingReport(
                backupName = UriDisplay.displayName(sourceUri.toString()),
                total = 0, matched = 0, missing = emptyList()
            )
        }
    }

    private companion object {
        /** 自动备份的文件名前缀 —— 清理时只认它。 */
        const val AUTO_PREFIX = "gaze-auto-"
    }
}

/** 自动备份文件名。 */
fun autoBackupFileName(): String {
    val cal = java.util.Calendar.getInstance()
    return String.format(
        java.util.Locale.US,
        "gaze-auto-%04d%02d%02d-%02d%02d.%s",
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
        BackupFormat.EXTENSION
    )
}

/** 默认导出文件名。 */
fun defaultBackupFileName(): String {
    val cal = java.util.Calendar.getInstance()
    return String.format(
        java.util.Locale.US,
        "gaze-%04d%02d%02d-%02d%02d.%s",
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
        BackupFormat.EXTENSION
    )
}
