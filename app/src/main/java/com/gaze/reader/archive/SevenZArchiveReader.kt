package com.gaze.reader.archive

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.IOException
import java.io.InputStream

/**
 * 7Z / CB7 读取。
 *
 * 注意 [SevenZFile] **不是线程安全的**：它内部维护一个共享的解压游标，
 * 并发 `getInputStream` 会互相踩。用 [lock] 把「定位 + 取流」串起来，
 * 真正的解码仍在调用方线程上进行（流拿到之后就可以自由读了）。
 */
class SevenZArchiveReader(private val source: UriChannel) : ArchiveReader {

    private val sevenZ: SevenZFile = SevenZFile.builder()
        .setSeekableByteChannel(source.channel)
        .get()

    private val lock = Any()

    private val entryMap: Map<String, SevenZArchiveEntry> = run {
        val map = LinkedHashMap<String, SevenZArchiveEntry>()
        for (entry in sevenZ.entries) {
            if (entry.isDirectory) continue
            if (!isPageCandidate(entry.name)) continue
            map[entry.name] = entry
        }
        map
    }

    override val formatLabel: String = "7Z"

    override val pages: List<ArchiveEntry> = buildPages(
        entryMap.values.map { it.name to it.size }
    )

    override fun openPage(entry: ArchiveEntry): InputStream {
        val native = entryMap[entry.name]
            ?: throw IOException("归档内条目已消失：${entry.name}")
        synchronized(lock) {
            return sevenZ.getInputStream(native)
        }
    }

    override fun close() {
        runCatching { sevenZ.close() }
        source.close()
    }
}
