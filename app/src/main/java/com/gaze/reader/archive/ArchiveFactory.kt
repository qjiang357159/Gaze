package com.gaze.reader.archive

import android.content.Context
import android.net.Uri
import com.gaze.reader.core.ArchiveFormat
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 归档读取层的统一入口。
 *
 * 优先按扩展名选择实现；扩展名认不出来（或与实际内容不符，比如把 RAR 改名成 `.cbz`）
 * 时，回退到**按文件头魔数探测**。漫画包里命名混乱是常态，
 * 「尽可能正确处理」比「严格按后缀」更实用。
 */
object ArchiveFactory {

    fun open(context: Context, uri: Uri, format: ArchiveFormat): ArchiveReader {
        val source = UriChannel.openOrThrow(context, uri)
        val resolved = if (format == ArchiveFormat.UNKNOWN) detect(source) else format
        return try {
            openReader(source, resolved)
        } catch (t: Throwable) {
            // 构造失败时把已打开的 fd 关掉，避免泄漏
            runCatching { source.close() }
            throw t
        }
    }

    /**
     * 用**已经打开的通道**构造读取器。
     *
     * 与 [open] 的差别只有一个：失败时**不**关闭 [source] —— 通道的所有权还在
     * 调用方手上（调用方通常还要在同一个通道上做别的事，或者需要自己兜住异常）。
     * 这是为了不为了让「探测文件到底是什么」多付一次开文件的钱。
     */
    fun openReader(source: UriChannel, format: ArchiveFormat): ArchiveReader = when (format) {
        ArchiveFormat.ZIP -> ZipArchiveReader(source)
        ArchiveFormat.RAR -> RarArchiveReader(source)
        ArchiveFormat.SEVEN_Z -> SevenZArchiveReader(source)
        ArchiveFormat.TAR -> TarArchiveReader(source)
        ArchiveFormat.UNKNOWN -> throw IOException("无法识别的归档格式")
        // PDF / EPUB 不走容器这条路径，各自有专门的 PageProvider
        ArchiveFormat.PDF, ArchiveFormat.EPUB -> throw IOException("该格式不是归档容器")
    }

    /** 按文件头魔数判断容器类型。认不出来返回 [ArchiveFormat.UNKNOWN]。 */
    fun detect(source: UriChannel): ArchiveFormat {
        val head = ByteArray(8)
        val filled = try {
            source.channel.position(0)
            source.channel.read(ByteBuffer.wrap(head))
        } catch (t: Throwable) {
            -1
        }
        if (filled >= 4) {
            // ZIP：PK 开头（本地文件头 / 空归档 / 分卷）
            if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
                return ArchiveFormat.ZIP
            }
        }
        if (filled >= 7) {
            // RAR4: 52 61 72 21 1A 07 00 / RAR5: 52 61 72 21 1A 07 01 00
            if (head[0] == 0x52.toByte() && head[1] == 0x61.toByte() &&
                head[2] == 0x72.toByte() && head[3] == 0x21.toByte() &&
                head[4] == 0x1A.toByte() && head[5] == 0x07.toByte()
            ) {
                return ArchiveFormat.RAR
            }
        }
        if (filled >= 6) {
            // 7Z: 37 7A BC AF 27 1C
            if (head[0] == 0x37.toByte() && head[1] == 0x7A.toByte() &&
                head[2] == 0xBC.toByte() && head[3] == 0xAF.toByte() &&
                head[4] == 0x27.toByte() && head[5] == 0x1C.toByte()
            ) {
                return ArchiveFormat.SEVEN_Z
            }
        }
        // TAR: 第 257 字节起是 "ustar"
        return try {
            val buf = ByteBuffer.allocate(5)
            source.channel.position(257)
            val n = source.channel.read(buf)
            if (n == 5 && String(buf.array(), Charsets.US_ASCII) == "ustar") {
                ArchiveFormat.TAR
            } else {
                ArchiveFormat.UNKNOWN
            }
        } catch (t: Throwable) {
            ArchiveFormat.UNKNOWN
        }
    }
}
