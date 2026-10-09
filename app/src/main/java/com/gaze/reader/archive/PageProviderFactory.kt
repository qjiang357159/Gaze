package com.gaze.reader.archive

import android.content.Context
import android.net.Uri
import com.gaze.reader.core.ArchiveFormat
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 「打开一本书」的唯一入口。
 *
 * 上层的三处调用（阅读器 / 扫描器 / 封面生成器 / 健康检查）都走这里，
 * 于是「新格式怎么接进来」只有一处需要改。
 *
 * ## 判定顺序
 *
 * ```
 * 扩展名是 pdf / epub  ->  直接按它处理
 * 其余                ->  开一个通道做魔数探测
 *                         · 第一条条目叫 mimetype 且内容是 application/epub+zip  ->  EPUB
 *                         · 头 5 字节是 "%PDF-"                                        ->  PDF
 *                         · 否则交给 ArchiveFactory 认容器（ZIP / RAR / 7Z / TAR）
 * ```
 *
 * 为什么要多读这几十个字节：**EPUB 的底层就是 ZIP**。一个 `.cbz` 或 `.zip`
 * 里装的完全可能是 EPUB（用户改过名、或者下载站按后缀粗暴命名），
 * 只认后缀会把它当成「一个没有图片的压缩包」然后报错。这个探测只读文件头，
 * 不给常规 ZIP 增加一次开文件的开销。
 *
 * ## 明确不做的
 *
 * 加密 PDF（`PdfRenderer` 直接抛异常，按「无法读取」提示）、
 * 分卷 CBR / 分卷 ZIP（见 [RarArchiveReader] 的说明）、DRM 保护的 EPUB。
 */
object PageProviderFactory {

    fun open(context: Context, uri: Uri, format: ArchiveFormat): PageProvider {
        // 后缀能确定就直接走 —— 这两种格式各有自己的实现，没有容器要猜
        if (format == ArchiveFormat.PDF) return PdfPageProvider(context, uri)
        if (format == ArchiveFormat.EPUB) return EpubPageProvider(context, uri)

        val source = UriChannel.openOrThrow(context, uri)
        var handedOver = false
        try {
            if (isEpub(source)) {
                // EPUB 有自己的随机读取通道，把这个探测用的通道还回去再开
                handedOver = true
                source.close()
                return EpubPageProvider(context, uri)
            }

            val resolved = if (format == ArchiveFormat.UNKNOWN) {
                if (isPdf(source)) {
                    handedOver = true
                    source.close()
                    return PdfPageProvider(context, uri)
                }
                ArchiveFactory.detect(source)
            } else {
                format
            }

            val reader = ArchiveFactory.openReader(source, resolved)
            // reader 接管了通道的生命周期，finally 不能再关
            handedOver = true
            return ArchivePageProvider(PageSource(reader, resolved, uri.toString()))
        } catch (t: Throwable) {
            if (t is IOException && format == ArchiveFormat.UNKNOWN) {
                throw IOException("无法识别的文件格式：既不是压缩包，也不是 PDF / EPUB")
            }
            throw t
        } finally {
            if (!handedOver) runCatching { source.close() }
        }
    }

    // ------------------------------------------------------------ 魔数探测

    /**
     * 是不是 EPUB。
     *
     * 看 ZIP 本地文件头的**第一个条目名**：EPUB 规范要求第一条必须是
     * `mimetype`（且必须是不压缩存储），所以读 30 字节头 + 8 字节名字就够，
     * 不用去解析中央目录。
     */
    private fun isEpub(source: UriChannel): Boolean {
        val head = readAt(source, 0L, LOCAL_HEADER_SIZE)
        if (head == null || head.size < LOCAL_HEADER_SIZE) return false
        // 本地文件头签名 PK\x03\x04
        if (head[0] != 0x50.toByte() || head[1] != 0x4B.toByte() ||
            head[2] != 0x03.toByte() || head[3] != 0x04.toByte()
        ) {
            return false
        }
        val nameLength = (head[26].toInt() and 0xFF) or ((head[27].toInt() and 0xFF) shl 8)
        if (nameLength !in 1..64) return false
        val name = readAt(source, LOCAL_HEADER_SIZE.toLong(), nameLength) ?: return false
        return String(name, Charsets.US_ASCII).equals("mimetype", ignoreCase = true)
    }

    /** 头 5 个字节是 `%PDF-`。 */
    private fun isPdf(source: UriChannel): Boolean {
        val head = readAt(source, 0L, 5) ?: return false
        return head.size == 5 && String(head, Charsets.US_ASCII) == "%PDF-"
    }

    /** 从 [position] 读 [length] 个字节。读不满就返回实际读到的部分，失败返回 null。 */
    private fun readAt(source: UriChannel, position: Long, length: Int): ByteArray? = try {
        val buffer = ByteBuffer.allocate(length)
        source.channel.position(position)
        while (buffer.hasRemaining()) {
            if (source.channel.read(buffer) <= 0) break
        }
        buffer.flip()
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (t: Throwable) {
        null
    }

    /** ZIP 本地文件头到「文件名长度」字段为止的固定部分。 */
    private const val LOCAL_HEADER_SIZE = 30
}
