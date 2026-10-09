package com.gaze.reader.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.gaze.reader.archive.PageProviderFactory
import com.gaze.reader.core.ArchiveFormat
import com.gaze.reader.core.FileNameParser
import com.gaze.reader.core.fileExtension
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.data.repository.ComicRepository
import com.gaze.reader.data.repository.FolderRepository
import com.gaze.reader.data.prefs.SettingsRepository
import com.gaze.reader.domain.MediaFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

enum class ScanPhase { DISCOVERING, IMPORTING, MARKING, DONE }

data class ScanProgress(
    val phase: ScanPhase,
    val processed: Int = 0,
    val total: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val currentName: String = ""
)

/** 扫描过程中发现的候选文件。 */
private data class DiscoveredFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val modified: Long,
    val folderUri: String
)

/**
 * 一次扫描内固定的文件名解析规则。
 *
 * 读一次 DataStore 然后全程复用 —— 逐条文件去读设置会让扫描多出几千次磁盘访问。
 */
private typealias ParseRules = FileNameParser.Rules

/**
 * 增量扫描器（文档第三章）。
 *
 * 核心是「不要每次启动都重新解析整个漫画库」：
 *
 * ```
 * 首次扫描：建立完整索引
 * 后续扫描：只检查发生变化的文件
 *   新增 -> 加入数据库
 *   修改 -> 更新索引
 *   删除 -> 标记失效
 *   未变化 -> 跳过
 * ```
 *
 * 判定依据是「路径 + 大小 + 修改时间」三元组 —— 三者都没变就直接跳过，
 * 连归档目录都不去读。这是大型漫画库能秒开的关键。
 *
 * 整个流程跑在 IO 线程上、支持取消、并且持续汇报进度，不阻塞 UI。
 */
class LibraryScanner(
    private val context: Context,
    private val comics: ComicRepository,
    private val folders: FolderRepository,
    private val settings: SettingsRepository
) {

    private val resolver get() = context.contentResolver

    fun scanAll(): Flow<ScanProgress> = flow {
        val folderList = folders.getAll()
        if (folderList.isEmpty()) {
            emit(ScanProgress(phase = ScanPhase.DONE))
            return@flow
        }

        val current = settings.current()
        val rules = FileNameParser.Rules(
            bySeparator = current.parseAuthorBySeparator,
            separator = current.authorSeparator,
            byBracket = current.parseAuthorByBracket,
            bracket = current.authorBracket
        )

        // ---------------------------------------------------- 阶段一：发现
        emit(ScanProgress(phase = ScanPhase.DISCOVERING))
        val discovered = ArrayList<DiscoveredFile>()
        for (folder in folderList) {
            currentCoroutineContext().ensureActive()
            discoverFolder(folder.uri, discovered)
        }
        val total = discovered.size

        // ---------------------------------------------------- 阶段二：比对入库
        var added = 0
        var updated = 0
        val presentUris = HashSet<String>(total * 2)
        val countByFolder = HashMap<String, Int>()

        for ((index, file) in discovered.withIndex()) {
            currentCoroutineContext().ensureActive()
            presentUris += file.uri.toString()
            countByFolder[file.folderUri] = (countByFolder[file.folderUri] ?: 0) + 1

            val result = processFile(file, rules)
            when (result) {
                FileResult.ADDED -> added++
                FileResult.UPDATED -> updated++
                FileResult.SKIPPED -> Unit
            }

            emit(
                ScanProgress(
                    phase = ScanPhase.IMPORTING,
                    processed = index + 1,
                    total = total,
                    added = added,
                    updated = updated,
                    currentName = file.name
                )
            )
        }

        // ---------------------------------------------------- 阶段三：标记失效
        emit(ScanProgress(phase = ScanPhase.MARKING, processed = total, total = total, added = added, updated = updated))
        val authorized = folderList.mapTo(HashSet()) { it.uri }
        var removed = 0
        for (comic in comics.getAllPresent()) {
            currentCoroutineContext().ensureActive()
            // 只处理「属于某个仍然授权的文件夹、但这次没扫到」的条目。
            // 用户主动取消授权的目录由 removeFolder 单独负责，不走这里。
            val folder = comic.folderUri ?: continue
            if (folder !in authorized) continue
            if (comic.uri in presentUris) continue
            comics.setMissing(comic.id, true)
            removed++
        }

        for (folder in folderList) {
            folders.updateScanResult(folder.uri, countByFolder[folder.uri] ?: 0)
        }

        emit(
            ScanProgress(
                phase = ScanPhase.DONE,
                processed = total,
                total = total,
                added = added,
                updated = updated,
                removed = removed
            )
        )
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------ 单个文件

    private enum class FileResult { ADDED, UPDATED, SKIPPED }

    private suspend fun processFile(file: DiscoveredFile, rules: ParseRules): FileResult {
        val uriString = file.uri.toString()
        val existing = comics.findByUri(uriString)

        if (existing != null) {
            val changed = existing.size != file.size || existing.modifiedTime != file.modified
            return when {
                changed -> {
                    refresh(existing, file, rules)
                    FileResult.UPDATED
                }
                existing.missing -> {
                    // 文件回来了：撤销失效标记，顺手补算内容特征
                    comics.setMissing(existing.id, false)
                    ensureContentFeatures(existing, file)
                    FileResult.UPDATED
                }
                else -> {
                    // 未变化 —— 但如果还缺内容特征，补一次（老数据升级路径）
                    if (existing.sampleHash == null) ensureContentFeatures(existing, file)
                    FileResult.SKIPPED
                }
            }
        }

        // 库里没有这个 uri：可能是「改了名 / 换了目录」的同一份文件
        val relinked = tryRelink(file, rules)
        if (relinked) return FileResult.UPDATED

        return if (insertNew(file, rules)) FileResult.ADDED else FileResult.SKIPPED
    }

    /**
     * 改名 / 换目录识别。
     *
     * 顺序：内容特征（sampleHash）-> 老数据的「大小 + 页数」退化匹配。
     * 命中后必须再确认**候选记录原本的 uri 已经打不开**，
     * 否则会误伤「同一个文件用户确实存了两份」的正常情况。
     */
    private suspend fun tryRelink(file: DiscoveredFile, rules: ParseRules): Boolean {
        val hash = MediaFingerprint.sampleHash(resolver, file.uri, file.size) ?: return false
        val candidate = comics.findBySampleHash(hash)
            ?: comics.findLegacyBySizeAndPages(file.size, readPageCount(file.uri, file.name).coerceAtLeast(0))
            ?: return false

        if (!isStale(candidate.uri)) return false

        val parsed = FileNameParser.parse(file.name, rules)
        val format = ArchiveFormat.of(file.name)
        comics.relocate(
            id = candidate.id,
            uri = file.uri.toString(),
            folderUri = file.folderUri,
            size = file.size,
            modifiedTime = file.modified,
            fileName = file.name
        )
        // 用户改名往往就是为了改作者名，所以标题 / 作者要一起刷新
        applyParsedNames(candidate.id, parsed, rules)
        comics.setFingerprint(
            candidate.id,
            MediaFingerprint.fingerprint(file.name, file.size, candidate.pageCount, hash),
            hash
        )
        // 格式可能也变了（.zip 改名成 .cbz）
        if (candidate.format != format.label) {
            comics.upsert(candidate.copy(format = format.label))
        }
        return true
    }

    /** 候选记录原来指向的 uri 是否已经打不开。查询抛异常一律按「不存在」处理。 */
    private fun isStale(uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return true
        return try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (t: Throwable) {
            // 失效 uri 走的正是异常分支。写成 getOrDefault(true) 会让整个识别静默失效。
            true
        }
    }

    private suspend fun insertNew(file: DiscoveredFile, rules: ParseRules): Boolean {
        val pageCount = readPageCount(file.uri, file.name)
        if (pageCount < 0) return false

        val parsed = FileNameParser.parse(file.name, rules)
        val format = ArchiveFormat.of(file.name)
        val hash = MediaFingerprint.sampleHash(resolver, file.uri, file.size)

        comics.upsert(
            ComicEntity(
                uri = file.uri.toString(),
                fileName = file.name,
                title = parsed.title,
                author = parsed.author,
                format = format.label,
                size = file.size,
                modifiedTime = file.modified,
                pageCount = pageCount,
                addedTime = System.currentTimeMillis(),
                fingerprint = MediaFingerprint.fingerprint(file.name, file.size, pageCount, hash),
                sampleHash = hash,
                missing = false,
                folderUri = file.folderUri
            )
        )
        return true
    }

    private suspend fun refresh(existing: ComicEntity, file: DiscoveredFile, rules: ParseRules) {
        val pageCount = readPageCount(file.uri, file.name)
        val parsed = FileNameParser.parse(file.name, rules)
        val hash = MediaFingerprint.sampleHash(resolver, file.uri, file.size)

        comics.upsert(
            existing.copy(
                fileName = file.name,
                format = ArchiveFormat.of(file.name).label,
                size = file.size,
                modifiedTime = file.modified,
                pageCount = if (pageCount >= 0) pageCount else existing.pageCount,
                missing = false,
                folderUri = file.folderUri,
                fingerprint = MediaFingerprint.fingerprint(file.name, file.size, existing.pageCount, hash),
                sampleHash = hash
            )
        )
        // 用户手动改过的标题 / 作者不许被覆盖
        applyParsedNames(existing.id, parsed, rules)
    }

    /**
     * 回写解析结果。
     *
     * 关掉作者解析时**只写标题**：不推断作者 ≠ 把已有作者删掉。
     * 两条 SQL 里都已经带了 `manual* = 0` 的保护。
     */
    private suspend fun applyParsedNames(id: Long, parsed: FileNameParser.Parsed, rules: ParseRules) {
        if (rules.parseAuthor) {
            comics.refreshParsedNames(id, parsed.title, parsed.author)
        } else {
            comics.refreshParsedTitle(id, parsed.title)
        }
    }

    /** 给老数据补算内容特征（不改其它字段）。 */
    private suspend fun ensureContentFeatures(existing: ComicEntity, file: DiscoveredFile) {
        val hash = MediaFingerprint.sampleHash(resolver, file.uri, file.size) ?: return
        comics.setFingerprint(
            existing.id,
            MediaFingerprint.fingerprint(file.name, file.size, existing.pageCount, hash),
            hash
        )
    }

    /**
     * 读目录拿页数 —— **不解压任何一页**。
     * 这是「大型库扫描要快」的关键：读中央目录是毫秒级的，解压图片才是重活。
     *
     * 走 [PageProviderFactory] 而不是 `ArchiveFactory`：PDF 的页数来自
     * `PdfRenderer.pageCount`、EPUB 的要按 spine 数一遍，都不是「读中央目录」。
     */
    private fun readPageCount(uri: Uri, name: String): Int = try {
        PageProviderFactory.open(context, uri, ArchiveFormat.of(name)).use { it.pageCount }
    } catch (t: Throwable) {
        -1
    }

    // ------------------------------------------------------------ 目录遍历

    private fun discoverFolder(folderUriString: String, out: MutableList<DiscoveredFile>) {
        val treeUri = runCatching { Uri.parse(folderUriString) }.getOrNull() ?: return
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return
        walk(treeUri, rootId, folderUriString, out, 0)
    }

    private fun walk(
        treeUri: Uri,
        documentId: String,
        folderUri: String,
        out: MutableList<DiscoveredFile>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        } catch (t: Throwable) {
            return
        }
        val cursor = try {
            resolver.query(childrenUri, PROJECTION, null, null, null)
        } catch (t: Throwable) {
            // 目录读不了（权限失效 / provider 异常）就跳过，不影响其它目录
            null
        } ?: return

        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idIdx < 0 || nameIdx < 0) return

            while (c.moveToNext()) {
                val id = c.getString(idIdx) ?: continue
                val name = c.getString(nameIdx) ?: continue
                if (name.startsWith(".")) continue
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, id, folderUri, out, depth + 1)
                    continue
                }
                if (!isArchiveName(name)) continue

                out += DiscoveredFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    name = name,
                    size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L,
                    modified = if (modifiedIdx >= 0) c.getLong(modifiedIdx) else 0L,
                    folderUri = folderUri
                )
            }
        }
    }

    private fun isArchiveName(name: String): Boolean =
        fileExtension(name) in ArchiveFormat.ALL_EXT

    private companion object {
        const val MAX_DEPTH = 24

        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
