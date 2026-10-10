package com.gaze.reader.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.gaze.reader.archive.PageProviderFactory
import com.gaze.reader.archive.describeOpenError
import com.gaze.reader.core.ArchiveFormat
import com.gaze.reader.core.fileExtension
import com.gaze.reader.data.repository.ComicRepository
import com.gaze.reader.domain.MediaFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 重新定位的结果。 */
sealed interface RelocateOutcome {
    data class Success(val fileName: String, val pageCount: Int) : RelocateOutcome

    /** 指到的文件本来就在库里 —— 把这条记录的历史数据并了过去，没有留下重复。 */
    data class Merged(val title: String) : RelocateOutcome

    data class Failure(val reason: String) : RelocateOutcome
}

/**
 * 媒体匹配（文档第十五章「恢复时绝不用文件路径当标识」的下游）。
 *
 * 恢复备份之后，备份里有、本机找不到的那些漫画会被留成 `missing = true` 的记录 ——
 * 阅读进度、收藏、标签一样没少，只是没指到文件。这个类负责把用户**手动挑的那个文件**
 * 接回原记录。
 *
 * 两条硬规则：
 *
 * 1. **绝不新建记录。** 用户挑文件是为了「接回历史数据」，新建一条空壳等于把
 *    进度、标签、收藏全丢掉 —— 那正是用户最不想看到的。
 * 2. **先真正打开一次再认。** 只按扩展名放行会让用户指到一个改过名的坏文件上，
 *    界面显示「定位成功」、点开却报错。
 */
class MediaRelocator(
    private val context: Context,
    private val comics: ComicRepository,
    private val covers: CoverGenerator
) {

    private val resolver get() = context.contentResolver

    suspend fun relocate(comicId: Long, uri: Uri): RelocateOutcome = withContext(Dispatchers.IO) {
        val comic = comics.getById(comicId)
            ?: return@withContext RelocateOutcome.Failure("这条记录已经不在库里了")

        val info = queryFileInfo(uri)
            ?: return@withContext RelocateOutcome.Failure("读不出这个文件的信息，换一个再试")
        val (name, size, modified) = info

        val format = ArchiveFormat.of(name)
        if (format == ArchiveFormat.UNKNOWN) {
            val ext = fileExtension(name).ifBlank { "无扩展名" }
            return@withContext RelocateOutcome.Failure("Gaze 打不开「$ext」这种格式")
        }

        val pageCount = try {
            PageProviderFactory.open(context, uri, format).use { it.pageCount }
        } catch (t: Throwable) {
            return@withContext RelocateOutcome.Failure("这个文件打不开：${describeOpenError(t)}")
        }
        if (pageCount <= 0) {
            return@withContext RelocateOutcome.Failure("这个文件里没有找到任何可显示的页面")
        }

        val sampleHash = MediaFingerprint.sampleHash(resolver, uri, size)
        val fingerprint = MediaFingerprint.fingerprint(name, size, pageCount, sampleHash)

        // 用户指过来的文件可能**已经在库里**（另有一条记录指着它）。直接写进去会撞上
        // uri 的唯一索引，而 upsert 是 REPLACE 语义 —— 原来那条记录连同它的标签、
        // 进度、收藏会被一起删掉。所以改成「把这条记录并进那条」，谁的数据都不丢。
        val occupant = comics.findByUri(uri.toString())
        if (occupant != null && occupant.id != comicId) {
            comics.absorbMissing(occupant.id, comicId)
            comics.getById(occupant.id)?.let { covers.generateOne(it) }
            return@withContext RelocateOutcome.Merged(occupant.title)
        }

        // 一次写全：只调 relocate() 的话 pageCount / format 还是旧值 ——
        // 用户指过来的很可能正是「换了个更好的版本」，页数就该按新文件算。
        comics.upsert(
            comic.copy(
                uri = uri.toString(),
                folderUri = comic.folderUri ?: guessTreeUri(uri),
                fileName = name,
                format = format.label,
                size = size,
                modifiedTime = modified,
                pageCount = pageCount,
                fingerprint = fingerprint,
                sampleHash = sampleHash,
                missing = false,
                // 旧封面是给旧文件拍的照，换了文件就不对了 —— 清掉重生成
                coverPath = null
            )
        )

        comics.getById(comicId)?.let { covers.generateOne(it) }

        RelocateOutcome.Success(fileName = name, pageCount = pageCount)
    }

    // ------------------------------------------------------------ 工具

    private data class FileInfo(val name: String, val size: Long, val modified: Long)

    /**
     * 查文件信息。
     *
     * 先按「显示名 + 大小 + 修改时间」查；部分 provider 不认 `COLUMN_LAST_MODIFIED`，
     * 那就降级成只查前两列，而不是让整次重新定位失败。
     */
    private fun queryFileInfo(uri: Uri): FileInfo? =
        queryInfo(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
        ) ?: queryInfo(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))

    private fun queryInfo(uri: Uri, columns: Array<String>): FileInfo? = try {
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            val size = if (cursor.columnCount > 1 && !cursor.isNull(1)) cursor.getLong(1) else 0L
            val modified = if (cursor.columnCount > 2 && !cursor.isNull(2)) cursor.getLong(2) else 0L
            FileInfo(name, size, modified)
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 从单个文档 uri 反推它所在目录的 tree uri。
     *
     * SAF 的 document id 形如 `primary:Comics/短篇/xxx.cbz`，去掉最后一段就是父目录。
     * 推不出来（provider 的 id 格式没有标准）就返回 null —— 那本漫画在文件夹视图里
     * 会落到「未归类」，但记录本身完好，用户重新授权目录再扫一次就能归位。
     */
    private fun guessTreeUri(documentUri: Uri): String? {
        val authority = documentUri.authority ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull()
            ?: return null
        val parent = docId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isBlank()) return null
        return runCatching {
            DocumentsContract.buildTreeDocumentUri(authority, parent)?.toString()
        }.getOrNull()
    }
}
