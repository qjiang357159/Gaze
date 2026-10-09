package com.gaze.reader.archive

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * 按**任意条目名**随机读取的 ZIP。
 *
 * [ZipArchiveReader] 只认图片（它是给漫画包用的），而 EPUB 要读的是
 * `META-INF/container.xml`、OPF 清单、NCX 目录这些非图片条目 —— 所以单开一个。
 * 底层同样是 commons-compress 的 [ZipFile] + [UriChannel]，
 * 于是 `content://` 也能只读一个条目、不必整包落盘。
 */
class ZipEntries(private val source: UriChannel) : Closeable {

    private val zip: ZipFile = ZipFile.builder()
        .setSeekableByteChannel(source.channel)
        .get()

    /** 规范化路径 -> 条目。EPUB 里的 href 大小写与转义常常不规范，查表要宽容。 */
    private val byPath: Map<String, ZipArchiveEntry> = run {
        val map = HashMap<String, ZipArchiveEntry>()
        val enumeration = zip.entries
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entry.isDirectory) continue
            map[normalize(entry.name)] = entry
        }
        map
    }

    val paths: Set<String> get() = byPath.keys

    fun contains(path: String): Boolean = byPath.containsKey(normalize(path))

    fun sizeOf(path: String): Long = byPath[normalize(path)]?.size ?: -1L

    /** 读一个条目的全部字节。条目不存在或超过 [limit] 返回 null。 */
    fun read(path: String, limit: Long = DEFAULT_ENTRY_LIMIT): ByteArray? {
        val entry = byPath[normalize(path)] ?: return null
        if (entry.size > limit) return null
        return try {
            zip.getInputStream(entry).use { it.readCapped(limit) }
        } catch (t: Throwable) {
            null
        }
    }

    /** 读成文本。EPUB 的 XML 声明里可能写 UTF-8，也可能什么都没写。 */
    fun readText(path: String, limit: Long = DEFAULT_ENTRY_LIMIT): String? =
        read(path, limit)?.toString(Charsets.UTF_8)

    override fun close() {
        runCatching { zip.close() }
        source.close()
    }

    companion object {
        /** 单个条目的读取上限。XHTML / OPF / NCX 都远小于它，图片另走 [sizeOf]。 */
        const val DEFAULT_ENTRY_LIMIT: Long = 64L * 1024 * 1024

        /**
         * 路径规范化：统一分隔符、去掉 `./` 前缀、百分号解码。
         *
         * EPUB 的 `href` 是 URI，里面的空格会写成 `%20`，而 ZIP 条目名是原始字符；
         * 不统一就会「明明有这个文件却找不到」。
         */
        fun normalize(path: String): String {
            var p = path.replace('\\', '/')
            while (p.startsWith("./")) p = p.substring(2)
            if (p.startsWith("/")) p = p.substring(1)
            return runCatching { java.net.URLDecoder.decode(p, "UTF-8") }.getOrDefault(p)
        }

        /**
         * 把 `href` 相对 [basePath] 解析成归档内的绝对路径。
         *
         * 两处都要规范化：`href` 可能带 `../`，`basePath` 可能带 `%20`。
         */
        fun resolve(basePath: String, href: String): String {
            val cleanHref = href.substringBefore('#').substringBefore('?')
            if (cleanHref.isEmpty()) return normalize(basePath)
            if (cleanHref.startsWith("/")) return normalize(cleanHref)

            val baseDir = normalize(basePath).substringBeforeLast('/', "")
            val segments = ArrayDeque<String>()
            if (baseDir.isNotEmpty()) baseDir.split('/').forEach { segments.addLast(it) }

            normalize(cleanHref).split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeLast()
                    else -> segments.addLast(part)
                }
            }
            return segments.joinToString("/")
        }
    }
}

private fun InputStream.readCapped(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream(64 * 1024)
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > limit) throw IOException("条目数据超过上限")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
