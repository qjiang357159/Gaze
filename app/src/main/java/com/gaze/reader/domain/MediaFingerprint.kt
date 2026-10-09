package com.gaze.reader.domain

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest

/**
 * 内容指纹。
 *
 * **绝不用文件路径当唯一标识**：换设备或用户整理目录后，
 * `/storage/emulated/0/Comics/xxx.cbz` 很可能变成 `/storage/emulated/0/Download/xxx.cbz`。
 * 恢复备份时如果按路径匹配，整库都会「丢失」。
 *
 * 于是分两层：
 * - [fingerprint]：`sha256(算法版本 | 文件名 | 大小 | 页数 | 采样哈希)`，
 *   用于「同一份备份 + 同一批文件」的精确匹配；
 * - [sampleHash]：**头尾各 64KB 的哈希，纯内容特征，与文件名无关**，
 *   用于识别「同一个文件被改了名」。用户把 `标题.cbz` 改成 `标题_作者.cbz` 之后，
 *   指纹变了、路径可能也变了，但采样哈希不变 —— 靠它才能接上，而不是新增一条重复记录。
 */
object MediaFingerprint {

    private const val VERSION = 1
    private const val SAMPLE_BYTES = 64 * 1024

    /** 组合指纹。采样哈希缺失时退化为「文件名 + 大小 + 页数」。 */
    fun fingerprint(
        fileName: String,
        size: Long,
        pageCount: Int,
        sampleHash: String?
    ): String {
        val raw = "$VERSION|$fileName|$size|$pageCount|${sampleHash.orEmpty()}"
        return sha256(raw.toByteArray(Charsets.UTF_8))
    }

    /**
     * 头尾各取 64KB 做采样哈希。
     *
     * 不做全量 SHA-256 —— 几百 MB 的漫画全量哈希会让扫描变得难以忍受。
     * 采样哈希足以区分「不同的漫画」，也足以识别「同一个文件改了名」。
     */
    fun sampleHash(resolver: ContentResolver, uri: Uri, size: Long): String? {
        if (size <= 0L) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(SAMPLE_BYTES)
                val n = readFully(input, head)
                if (n > 0) digest.update(head, 0, n)
            } ?: return null

            // 文件足够大时才单独读尾部；小文件头部已覆盖全文
            if (size > SAMPLE_BYTES * 2L) {
                resolver.openInputStream(uri)?.use { input ->
                    if (skipFully(input, size - SAMPLE_BYTES)) {
                        val tail = ByteArray(SAMPLE_BYTES)
                        val m = readFully(input, tail)
                        if (m > 0) digest.update(tail, 0, m)
                    }
                }
            }
            digest.digest().toHex()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 循环读满。
     *
     * `InputStream.read()` 允许只返回一部分，单次调用不保证填满缓冲区。
     */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }

    /**
     * 循环跳过 [count] 字节。
     *
     * **必须循环重试**：部分 SAF provider 的 `skip()` 会提前返回 0，
     * 一次调用跳不到目标位置，退化成单字节读兜底。
     */
    private fun skipFully(input: InputStream, count: Long): Boolean {
        var remaining = count
        var guard = 0
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                guard = 0
                continue
            }
            // skip 返回 0：改用单字节读推进
            if (input.read() < 0) return false
            remaining--
            if (++guard > 1_000_000) return false
        }
        return true
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private val HEX = "0123456789abcdef".toCharArray()
}
