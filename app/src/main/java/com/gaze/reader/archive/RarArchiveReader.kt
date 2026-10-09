package com.gaze.reader.archive

import com.github.junrar.Archive
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.rarfile.FileHeader
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * RAR / CBR 读取。
 *
 * junrar 的 [Archive] 只接受 `File` 或 `InputStream`，没有「给我一个可随机访问的通道」这个口子。
 * 而 `InputStream` 那条路是**不可接受**的：它内部把已读过的数据按 512 字节块缓存进
 * `RandomAccessInputStream` 的 `Vector<ByteArray>` 里，一本几百 MB 的 CBR 会逐步变成
 * 几百 MB 常驻内存 —— 正好踩中文档「大文件按需加载」的红线。
 *
 * 所以这里实现 junrar 自己的 [VolumeManager] / [Volume] 接口，把 SAF 的
 * [SeekableByteChannel] 直接接上去：零额外缓冲、真随机访问，和 ZIP / 7Z / TAR 一样
 * 只解压需要的那一页。
 *
 * 加密（带密码）的 RAR 会在构造或取流时抛 [com.github.junrar.exception.RarException]，
 * 由上层统一按「无法读取」处理并提示用户（文档第二十三章）。
 */
class RarArchiveReader(private val source: UriChannel) : ArchiveReader {

    private val archive: Archive = Archive(SingleVolumeManager(source), null, null)

    private val entryMap: Map<String, FileHeader> = run {
        val map = LinkedHashMap<String, FileHeader>()
        for (header in archive.fileHeaders) {
            if (header.isDirectory) continue
            if (!isPageCandidate(header.fileName)) continue
            map[header.fileName] = header
        }
        map
    }

    override val formatLabel: String = "RAR"

    override val pages: List<ArchiveEntry> = buildPages(
        entryMap.values.map { it.fileName to it.fullUnpackSize }
    )

    override fun openPage(entry: ArchiveEntry): InputStream {
        val native = entryMap[entry.name]
            ?: throw IOException("归档内条目已消失：${entry.name}")
        return archive.getInputStream(native)
    }

    override fun close() {
        runCatching { archive.close() }
        source.close()
    }
}

/**
 * 单卷 RAR：第一次被问就交出通道，之后一律回答「没有下一卷」。
 *
 * 分卷 CBR（`part1.rar` / `part2.rar`）在本地漫画场景里极少见，而且 SAF 拿到的每个
 * 文档 URI 都是独立的，跨卷拼接需要用户把每一卷都授权进来 —— 收益远小于复杂度，
 * 因此明确不做（文档也不要求）。返回 null 时 junrar 会正常结束当前归档。
 */
private class SingleVolumeManager(private val source: UriChannel) : VolumeManager {

    private var handedOut = false

    override fun nextVolume(archive: Archive, previous: Volume?): Volume? {
        if (handedOut) return null
        handedOut = true
        return SingleVolume(archive, source)
    }
}

private class SingleVolume(
    private val owner: Archive,
    private val source: UriChannel
) : Volume {

    // 只造一次：junrar 可能多次索取通道，每次新建会把读写位置重置回 0。
    private val adapter: SeekableReadOnlyByteChannel = ChannelSeekableReadOnly(source.channel)

    override fun getChannel(): SeekableReadOnlyByteChannel = adapter

    override fun getLength(): Long = runCatching { source.channel.size() }.getOrDefault(0L)

    override fun getArchive(): Archive = owner
}

/**
 * 把 [SeekableByteChannel] 适配成 junrar 的 [SeekableReadOnlyByteChannel]。
 *
 * 语义对齐 junrar 自己的 `SeekableReadOnlyInputStream`：
 * - `read()` 到末尾返回 -1；
 * - `readFully(buffer, count)` 从 `buffer[0]` 起填 `count` 字节，返回 `count`。
 */
private class ChannelSeekableReadOnly(
    private val channel: SeekableByteChannel
) : SeekableReadOnlyByteChannel {

    private val single = ByteBuffer.allocate(1)

    override fun getPosition(): Long = channel.position()

    override fun setPosition(position: Long) {
        channel.position(position)
    }

    override fun read(): Int {
        single.clear()
        return if (channel.read(single) <= 0) -1 else single.get(0).toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (count <= 0) return 0
        return channel.read(ByteBuffer.wrap(buffer, offset, count))
    }

    override fun readFully(buffer: ByteArray, count: Int): Int {
        if (count <= 0) return 0
        val target = ByteBuffer.wrap(buffer, 0, count)
        while (target.hasRemaining()) {
            val n = channel.read(target)
            if (n <= 0) break
        }
        return count
    }

    override fun close() {
        channel.close()
    }
}
