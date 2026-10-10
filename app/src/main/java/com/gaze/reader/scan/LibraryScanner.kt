package com.gaze.reader.scan

import android.content.ContentResolver
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
    /** 本次扫描顺手合并掉的「重复记录」条数（失效记录并回在库记录）。 */
    val merged: Int = 0,
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
        // 只扫真正拿到过权限的文件夹。待重新授权的那些在本机读不出任何东西，
        // 喂进来只会得到「扫到 0 本」，然后把它们名下的记录全标成失效。
        val folderList = folders.getAuthorized()
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

        // ---------------------------------------------------- 阶段二点五：合并重复
        //
        // 「先恢复备份、后授权扫描」在旧版本下会给同一个文件留下两条记录：
        // 一条带着标签与阅读进度的失效记录（备份恢复出来的），一条扫描新建的
        // 在库记录。这里把它们并回去，历史数据才不会一直挂在看不见的空壳上。
        val merged = mergeMissingTwins()

        // ---------------------------------------------------- 阶段三：标记失效
        emit(
            ScanProgress(
                phase = ScanPhase.MARKING,
                processed = total,
                total = total,
                added = added,
                updated = updated,
                merged = merged
            )
        )
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
                removed = removed,
                merged = merged
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
     * 改名 / 换目录 / 接回占位记录。
     *
     * 顺序：内容特征（sampleHash）-> 老数据的「大小 + 页数」退化匹配。
     * 命中后必须确认候选记录**确实缺一个文件**，否则会误伤
     * 「同一个文件用户确实存了两份」的正常情况 —— 两种「缺文件」：
     *
     * - 记录已经标成 `missing = true`（恢复备份留下的占位记录就是这种），
     *   它本来就是等着被接回来的，直接认；
     * - 记录还没标记失效，那要确认它原来指向的 uri 已经打不开（见 [isStale]）。
     */
    private suspend fun tryRelink(file: DiscoveredFile, rules: ParseRules): Boolean {
        val hash = MediaFingerprint.sampleHash(resolver, file.uri, file.size) ?: return false
        val realPages = readPageCount(file.uri, file.name)
        val candidate = comics.findSampleHashCandidate(hash)
            ?: comics.findLegacyCandidate(file.size, realPages.coerceAtLeast(0))
            ?: return false

        if (!candidate.missing && !isStale(candidate.uri)) return false

        val parsed = FileNameParser.parse(file.name, rules)
        val format = ArchiveFormat.of(file.name)
        // 页数按真实文件重算：老备份里页数可能是 0，不补的话进度条会一直算错
        val pages = if (realPages > 0) realPages else candidate.pageCount

        // 一次写全 uri / 格式 / 页数。
        //
        // **绝不能在这之后拿 relocate 之前的快照去 upsert** —— 那个快照里的 uri
        // 还是 `gaze://missing/…`、missing 还是 true，写回去等于把刚接好的记录
        // 又打回「找不到文件」，而且每次扫描都重演一遍，永远接不回来。
        comics.relocate(
            id = candidate.id,
            uri = file.uri.toString(),
            folderUri = file.folderUri,
            size = file.size,
            modifiedTime = file.modified,
            fileName = file.name,
            format = format.label,
            pageCount = pages
        )
        // 用户改名往往就是为了改作者名，所以标题 / 作者要一起刷新
        applyParsedNames(candidate.id, parsed, rules)
        comics.setFingerprint(
            candidate.id,
            MediaFingerprint.fingerprint(file.name, file.size, pages, hash),
            hash
        )
        return true
    }

    /**
     * 候选记录原来指向的 uri 是否已经打不开。
     *
     * 三个分支都算「打不开」：
     *
     * - **非 content 协议**（恢复备份留下的 `gaze://missing/…` 占位符）——
     *   这里必须显式判掉。`ContentResolver.query` 对认不出的 scheme 是**返回 null
     *   而不是抛异常**，旧实现只把异常当失效，于是占位记录永远被当成「文件还在」，
     *   扫描时既不接它、又新建一条重复记录，标签就留在了那条永远不显示的空壳上；
     * - 查询返回 null 或空游标（文件已删 / 授权已失效）；
     * - 查询抛异常（失效 uri 走的正是这个分支，不能 getOrDefault(false)）。
     */
    private fun isStale(uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return true
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return true
        return try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } != true
        } catch (t: Throwable) {
            true
        }
    }

    /**
     * 合并「同一个文件的失效记录与在库记录」。
     *
     * 「先恢复备份、后授权扫描」这个顺序下，旧版扫描器认不出占位记录
     * （原因见 [isStale]），会给同一个文件再建一条新记录 —— 阅读进度、收藏和
     * 标签全留在那条 `missing = 1` 的空壳上，用户看到的就是「标签没恢复」。
     * 这里把空壳上的历史数据并进在库记录，再删掉空壳。
     *
     * 代价是「失效记录条数」次查询：库正常时 `getMissing()` 返回空列表，直接返回。
     */
    private suspend fun mergeMissingTwins(): Int {
        val missing = comics.getMissing()
        if (missing.isEmpty()) return 0
        val present = comics.getAllPresent()
        if (present.isEmpty()) return 0

        val byHash = HashMap<String, MutableList<ComicEntity>>()
        val byNameSize = HashMap<String, MutableList<ComicEntity>>()
        present.forEach { comic ->
            comic.sampleHash?.let { byHash.getOrPut(it) { mutableListOf() }.add(comic) }
            byNameSize.getOrPut("${comic.fileName}|${comic.size}") { mutableListOf() }.add(comic)
        }

        var merged = 0
        for (shell in missing) {
            currentCoroutineContext().ensureActive()
            // 唯一命中才合并：两条在库记录都指向同一份内容时，并到哪条都不对，
            // 那种情况该让用户自己去「媒体匹配」里指。
            val target = shell.sampleHash?.let { byHash[it] }?.singleOrNull()
                ?: byNameSize["${shell.fileName}|${shell.size}"]?.singleOrNull()
                ?: continue
            comics.absorbMissing(target.id, shell.id)
            merged++
        }
        return merged
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
