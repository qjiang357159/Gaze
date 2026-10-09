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
import com.gaze.reader.data.repository.ComicRepository
import com.gaze.reader.data.repository.FolderRepository
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
        val folderList = folders.getAll()

        val tagNameById = tagList.associate { it.id to it.name }
        val tagsByComic = relations.groupBy(
            keySelector = { it.comicId },
            valueTransform = { tagNameById[it.tagId].orEmpty() }
        ).mapValues { (_, names) -> names.filter { it.isNotBlank() } }

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
                                tagNames = tagsByComic[comic.id].orEmpty()
                            ).toJson()
                        )
                    }
                }
            )
            put(
                "tags",
                JSONArray().apply { tagList.forEach { put(BackupTag(it.name, it.color, it.createdAt).toJson()) } }
            )
            put(
                "folders",
                JSONArray().apply { folderList.forEach { put(BackupFolder(it.uri, it.displayName).toJson()) } }
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
                entries[BackupFormat.ENTRY_SETTINGS]?.let { applySettings(JSONObject(String(it, Charsets.UTF_8))) }

                if (mode == RestoreMode.OVERWRITE) {
                    comics.clearAll()
                }

                // 标签先落地，恢复漫画时才能挂上关系
                for (tag in backupTags) {
                    tags.create(tag.name, tag.color)
                }
                for (folder in backupFolders) {
                    folders.add(folder.uri, folder.displayName)
                }

                val local = comics.getAll()
                val byFingerprint = HashMap<String, ComicEntity>()
                val bySampleHash = HashMap<String, ComicEntity>()
                val byFileNameSize = HashMap<String, ComicEntity>()
                local.forEach { comic ->
                    comic.fingerprint?.let { byFingerprint.putIfAbsent(it, comic) }
                    comic.sampleHash?.let { bySampleHash.putIfAbsent(it, comic) }
                    byFileNameSize.putIfAbsent("${comic.fileName}|${comic.size}", comic)
                }

                var restored = 0
                var matched = 0
                var pending = 0
                val tagIdByName = tags.getAll().associate { it.name to it.id }

                for (record in backupComics) {
                    val hit = record.fingerprint?.let { byFingerprint[it] }
                        ?: record.sampleHash?.let { bySampleHash[it] }
                        ?: byFileNameSize["${record.fileName}|${record.size}"]

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
                                // 备份里的书名只在本地没有手动改名时才采用
                                title = if (hit.manualTitle) hit.title else record.title,
                                author = if (hit.manualAuthor) hit.author else record.author
                            )
                        )
                        applyTags(hit.id, record.tagNames, tagIdByName)
                    } else {
                        // 匹配不上：新建一条「待重新定位」记录，**不删任何历史数据**
                        pending++
                        val placeholder = "gaze://missing/${record.fingerprint ?: record.fileName.hashCode()}"
                        val newId = comics.upsert(
                            ComicEntity(
                                uri = placeholder,
                                fileName = record.fileName,
                                title = record.title,
                                author = record.author,
                                format = "ZIP",
                                size = record.size,
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
                                missing = true
                            )
                        )
                        if (newId > 0L) applyTags(newId, record.tagNames, tagIdByName)
                    }
                    restored++
                }

                BackupResult(
                    success = true,
                    message = buildString {
                        append("已恢复 $restored 本")
                        if (matched > 0) append("，其中 $matched 本直接匹配到本地文件")
                        if (pending > 0) append("，$pending 本待重新定位")
                    },
                    restored = restored,
                    matched = matched,
                    pending = pending
                )
            }
        } catch (t: Throwable) {
            BackupResult(false, "恢复失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun applyTags(comicId: Long, names: List<String>, idByName: Map<String, Long>) {
        val ids = names.mapNotNull { idByName[it] }
        if (ids.isEmpty()) return
        // 用差集语义：只补上缺的，不动用户本地已有的其它标签
        tags.addTo(listOf(comicId), ids)
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
                local.forEach { comic ->
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
