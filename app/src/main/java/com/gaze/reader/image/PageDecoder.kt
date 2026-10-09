package com.gaze.reader.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/** 一张图的原始尺寸。 */
data class PageSize(val width: Int, val height: Int) {
    val isValid: Boolean get() = width > 0 && height > 0
    /** 长宽比（高 / 宽）。条漫会远大于 1。 */
    val aspect: Float get() = if (width <= 0) 1f else height.toFloat() / width.toFloat()
    val isUltraTall: Boolean get() = aspect >= ULTRA_TALL_ASPECT
    /** 像素总数，用来判断「整页解出来会不会太大」。 */
    val pixels: Long get() = width.toLong() * height.toLong()

    companion object {
        const val ULTRA_TALL_ASPECT = 3.2f
    }
}

/**
 * 图片解码器。
 *
 * ## 原则：原图保真优先
 *
 * 这里**不再用 `RGB_565`**。565 只有 5/6/5 位色深，漫画里最常见的
 * 「大面积平滑渐变」（皮肤、天空、白色布料的高光过渡）会被量化成一圈圈
 * 同心色带 —— 看起来像木纹，用户读到的是「糊」。省下的那点内存在 8888
 * 面前不值一提，清晰度是硬指标。
 *
 * ## 两条解码路径
 *
 * - [decode]：整页解码。采样率取「解码结果**不小于**请求尺寸」的最大 2 的幂，
 *   也就是说解码出来的位图**永远不比显示尺寸小**，缩小交给 GPU —— 这既是
 *   超采样（画质更好），也彻底避免「先降采样再放大」导致的模糊。
 *   只有当整页像素数超过 [MAX_BITMAP_PIXELS] 时才会继续抬高采样率，
 *   而那种情况本来就该走 [decodeRegion]。
 * - [decodeRegion]：区域解码（Tile Rendering）。放大查看、超长条漫、
 *   超高分辨率扫描图都走这条 —— **分块的目的是省内存，不是降分辨率**：
 *   每块按它在屏幕上的实际像素数解码，块与块拼起来就是原图。
 */
object PageDecoder {

    /**
     * 单张位图的像素上限（ARGB_8888 下约 96 MB）。
     *
     * 超过它就意味着「这张图不该整页解出来」，调用方应当改用 [decodeRegion]。
     * 这里保留一个硬上限只是为了防止极端图把堆直接撑爆 —— 它不是画质档位。
     */
    const val MAX_BITMAP_PIXELS: Int = 24_000_000

    /**
     * 「整页解出来也划算」的像素上限（约 32 MB）。
     *
     * 纵向连续模式下用它判断一页要不要分块：超过就只解可视那一段。
     * 它比 [MAX_BITMAP_PIXELS] 保守得多，因为连续模式会同时挂着好几页。
     */
    const val MAX_INLINE_PIXELS: Long = 8_000_000L

    /** 只读文件头拿尺寸，不解码像素。失败返回 null（图片损坏 / 格式不支持）。 */
    fun probe(bytes: ByteArray): PageSize? {
        if (bytes.isEmpty()) return null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val size = PageSize(options.outWidth, options.outHeight)
            if (size.isValid) size else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 整页解码。
     *
     * @param reqWidth / [reqHeight] 目标显示尺寸（像素）。采样率保证解码结果
     *   **不小于**它，所以任何情况下都不会出现「低分辨率位图被放大」。
     * @param fullResolution true 时强制 `inSampleSize = 1`（原尺寸整页解码）。
     */
    fun decode(
        bytes: ByteArray,
        reqWidth: Int,
        reqHeight: Int,
        fullResolution: Boolean
    ): Bitmap? {
        if (bytes.isEmpty()) return null

        val bounds = boundsOf(bytes) ?: return null

        val sample = if (fullResolution) {
            1
        } else {
            sampleSizeFor(bounds.width, bounds.height, reqWidth, reqHeight)
        }

        return try {
            decodeWith(bytes, sample)
        } catch (t: OutOfMemoryError) {
            // 降级重试：只在堆真的不够时抬高采样率。
            // 这是「宁可不崩」的兜底，不是常规路径。
            decodeWithFallback(bytes, bounds, reqWidth, reqHeight)
        } catch (t: Throwable) {
            null
        }
    }

    private fun decodeWith(bytes: ByteArray, sample: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample.coerceAtLeast(1)
            // 关掉 inScaled，避免系统按屏幕密度再替我们缩放一次 ——
            // 那一次缩放的插值质量不由我们控制，且会把已经算好的采样率再打折。
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** OOM 后的降级：采样率翻倍再翻倍，直到成功或放弃。 */
    private fun decodeWithFallback(
        bytes: ByteArray,
        bounds: PageSize,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        var sample = max(2, sampleSizeFor(bounds.width, bounds.height, reqWidth, reqHeight))
        repeat(4) {
            try {
                val bitmap = decodeWith(bytes, sample)
                if (bitmap != null) return bitmap
            } catch (t: OutOfMemoryError) {
                // 继续抬高采样率
            } catch (t: Throwable) {
                return null
            }
            sample *= 2
        }
        return null
    }

    /**
     * 区域解码 —— 只解出 [region] 这一块。
     *
     * @param outWidth / [outHeight] 这一块**在屏幕上的像素尺寸**。采样率同样保证
     *   解出来的块不小于它，所以区域解码不会比整页解码糊 —— 它只是不把
     *   视口之外的部分也解出来。
     *
     * 注意 `BitmapRegionDecoder` 需要图片格式支持随机访问（JPEG / PNG / WebP
     * 都支持）。不支持时返回 null，调用方退回整页位图即可，不会崩。
     */
    fun decodeRegion(
        bytes: ByteArray,
        region: Rect,
        outWidth: Int,
        outHeight: Int
    ): Bitmap? {
        if (bytes.isEmpty() || region.isEmpty) return null

        // BitmapRegionDecoder 既不是 Closeable 也不是 AutoCloseable，
        // 释放只能走 recycle()，所以这里显式 try/finally，
        // 确保任何一条异常路径都会回收解码器（否则原生内存会一直挂着）。
        var decoder: BitmapRegionDecoder? = null
        try {
            @Suppress("DEPRECATION")
            val opened = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
            decoder = opened
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = if (outWidth > 0 && outHeight > 0) {
                    sampleSizeFor(region.width(), region.height(), outWidth, outHeight)
                } else {
                    1
                }
                inScaled = false
            }
            return opened.decodeRegion(region, options)
        } catch (t: OutOfMemoryError) {
            return null
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { decoder?.recycle() }
        }
    }

    /**
     * 计算 2 的幂采样率。
     *
     * **第一优先是清晰度**：取「解码结果不小于请求尺寸」的最大 2 的幂，
     * 于是解码位图最多比显示尺寸大一倍（超采样），绝不会更小。
     *
     * 取「宽高两个方向里较小的那个比值」，这样超长图不会被按高度算出一个
     * 把宽度压没了的采样率。
     *
     * **第二优先才是内存**：只有整页像素数超过 [maxPixels] 时才继续抬高采样率。
     */
    fun sampleSizeFor(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
        maxPixels: Int = MAX_BITMAP_PIXELS
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        val safeReqWidth = if (reqWidth > 0) reqWidth else srcWidth
        val safeReqHeight = if (reqHeight > 0 && reqHeight != Int.MAX_VALUE) reqHeight else srcHeight

        val ratio = min(
            srcWidth.toFloat() / safeReqWidth,
            srcHeight.toFloat() / safeReqHeight
        )
        var sample = 1
        while (sample * 2 <= ratio) sample *= 2

        // 内存兜底：走到这里说明原图大得离谱，整页解码本来就不合适
        while (pixelsAfterSample(srcWidth, srcHeight, sample) > maxPixels && sample < 128) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    /** 采样后剩余像素数。 */
    fun pixelsAfterSample(srcWidth: Int, srcHeight: Int, sample: Int): Long {
        val s = sample.coerceAtLeast(1).toLong()
        return (srcWidth / s) * (srcHeight / s)
    }

    private fun boundsOf(bytes: ByteArray): PageSize? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        PageSize(options.outWidth, options.outHeight).takeIf { it.isValid }
    } catch (t: Throwable) {
        null
    }
}
