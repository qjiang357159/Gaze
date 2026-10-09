package com.gaze.reader.archive

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.SeekableByteChannel

/**
 * 把一个 SAF `content://` 文档变成**可随机访问**的通道。
 *
 * 为什么需要它：ZIP / 7Z / TAR / RAR 的目录都在文件尾部或中间，
 * 要「只读第 50 页」就必须能 seek。而 SAF 只给 fd，不给路径 ——
 * `FileInputStream(fd).channel` 正好是一个支持 `position()` 的 `FileChannel`，
 * 这就是本地文件场景下最省事的随机访问来源。
 *
 * 生命周期：channel 属于 stream，stream 又属于 pfd。关闭顺序必须是
 * 先 stream 后 pfd，否则会 double close。统一用 [close] 兜住。
 */
class UriChannel private constructor(
    private val pfd: ParcelFileDescriptor,
    private val stream: FileInputStream,
    val channel: SeekableByteChannel
) : Closeable {

    override fun close() {
        runCatching { stream.close() }
        runCatching { pfd.close() }
    }

    companion object {
        /** 打不开（文件被删、授权失效、provider 不支持）时返回 null。 */
        fun open(context: Context, uri: Uri): UriChannel? {
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (t: Throwable) {
                null
            } ?: return null

            return try {
                val stream = FileInputStream(pfd.fileDescriptor)
                UriChannel(pfd, stream, stream.channel)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                null
            }
        }

        fun openOrThrow(context: Context, uri: Uri): UriChannel =
            open(context, uri) ?: throw IOException("无法打开文件：$uri")
    }
}
