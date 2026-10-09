package com.gaze.reader.archive

import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.io.InputStream

/**
 * ZIP / CBZ 读取。
 *
 * 用 commons-compress 的 [ZipFile] 而不是 `java.util.zip.ZipFile`：
 * 前者能直接吃一个 [java.nio.channels.SeekableByteChannel]，
 * 于是 SAF 的 `content://` 也能随机访问中央目录，不必先整包落盘。
 */
class ZipArchiveReader(private val source: UriChannel) : ArchiveReader {

    private val zip: ZipFile = ZipFile.builder()
        .setSeekableByteChannel(source.channel)
        .get()

    override val formatLabel: String = "ZIP"

    override val pages: List<ArchiveEntry> = run {
        val collected = ArrayList<Pair<String, Long>>()
        val enumeration = zip.entries
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entry.isDirectory) continue
            if (!isPageCandidate(entry.name)) continue
            collected += entry.name to entry.size
        }
        buildPages(collected)
    }

    override fun openPage(entry: ArchiveEntry): InputStream {
        val native = zip.getEntry(entry.name)
            ?: throw IOException("归档内条目已消失：${entry.name}")
        return zip.getInputStream(native)
    }

    override fun close() {
        runCatching { zip.close() }
        source.close()
    }
}
