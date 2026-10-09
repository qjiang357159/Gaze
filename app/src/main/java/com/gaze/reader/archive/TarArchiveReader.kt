package com.gaze.reader.archive

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarFile
import java.io.IOException
import java.io.InputStream

/**
 * TAR / CBT 读取。
 *
 * 用 commons-compress 的 [TarFile]（1.22+）而不是顺序的 `TarArchiveInputStream` ——
 * 前者同样基于 [java.nio.channels.SeekableByteChannel]，可以真正按需读取单页，
 * 不必为了看第 200 页把前 199 页全扫一遍。
 */
class TarArchiveReader(private val source: UriChannel) : ArchiveReader {

    private val tar: TarFile = TarFile(source.channel)

    private val entryMap: Map<String, TarArchiveEntry> = run {
        val map = LinkedHashMap<String, TarArchiveEntry>()
        for (entry in tar.entries) {
            if (entry.isDirectory) continue
            if (!isPageCandidate(entry.name)) continue
            map[entry.name] = entry
        }
        map
    }

    override val formatLabel: String = "TAR"

    override val pages: List<ArchiveEntry> = buildPages(
        entryMap.values.map { it.name to it.size }
    )

    override fun openPage(entry: ArchiveEntry): InputStream {
        val native = entryMap[entry.name]
            ?: throw IOException("归档内条目已消失：${entry.name}")
        return tar.getInputStream(native)
    }

    override fun close() {
        runCatching { tar.close() }
        source.close()
    }
}
