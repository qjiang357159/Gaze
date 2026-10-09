package com.gaze.reader.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.gaze.reader.image.PageSize
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDF 的页面提供者。
 *
 * ## 为什么用系统的 `PdfRenderer`
 *
 * 它是 Android 自带的 PDF 光栅化器：**零依赖**（不引入 PdfBox / MuPDF 那种
 * 动辄几 MB 的库）、走的是和系统「文件」应用同一套渲染、支持随机访问任意一页。
 * 漫画阅读器对 PDF 的诉求（按页看、放大看细节）它正好都覆盖。
 *
 * ## 它没有的东西
 *
 * - **没有大纲 / 书签**：`PdfRenderer` 不暴露 PDF 的 `/Outlines`，所以 [toc] 恒为空。
 *   要读大纲就得自己解析 PDF 对象树 —— 那正是「引入过重依赖」的典型，
 *   不值得。导航改由「页码跳转 + 页缩略网格」承担。
 * - **没有文本层**：只能拿到位图（这一点对漫画阅读器无所谓）。
 *
 * ## 与分块渲染的关系
 *
 * PDF 是矢量的，所以「放大」天然清晰 —— 只要按放大后的**屏幕像素数**重新渲一次。
 * [decodeRegion] 正是干这个：把页面坐标里的一小块，以屏幕上的实际尺寸重新光栅化。
 * 于是 PDF 也完整复用了阅读器那套「整页 / 区域 / 分块」策略。
 */
class PdfPageProvider(context: Context, uri: Uri) : PageProvider {

    private val descriptor: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("无法打开 PDF 文件描述符")

    private val renderer: PdfRenderer = try {
        PdfRenderer(descriptor)
    } catch (t: Throwable) {
        runCatching { descriptor.close() }
        throw t
    }

    /**
     * `PdfRenderer` **不是线程安全的**，而且同一时刻只能打开一页
     * （`openPage` 返回的 Page 必须 close 之后才能开下一页）。
     * 所以这里必须串行 —— 好在渲染本身很快，用户感知不到。
     */
    private val lock = Any()

    /** 页面尺寸是固定的元信息，缓存起来免得每次翻页都去 openPage。 */
    private val sizeCache = HashMap<Int, PageSize>()

    override val pageCount: Int = renderer.pageCount

    override val formatLabel: String = "PDF"

    override fun sizeOf(index: Int): PageSize? {
        synchronized(sizeCache) { sizeCache[index] }?.let { return it }
        if (index < 0 || index >= pageCount) return null
        return synchronized(lock) {
            try {
                val page = renderer.openPage(index)
                try {
                    PageSize(page.width, page.height).also {
                        synchronized(sizeCache) { sizeCache[index] = it }
                    }
                } finally {
                    page.close()
                }
            } catch (t: Throwable) {
                null
            }
        }
    }

    override fun decode(index: Int, reqWidth: Int, reqHeight: Int, fullResolution: Boolean): Bitmap? {
        val natural = sizeOf(index) ?: return null
        // 刻意**忽略** fullResolution：PDF 没有「像素分辨率」这回事，
        // 它的原尺寸是矢量坐标（点）。按坐标渲出来反而糊，
        // 所以永远按请求尺寸光栅化 —— 这就是 PDF 的「原图保真」。
        val target = fitSize(natural, reqWidth, reqHeight)
        // 整页区域 = 页面自身坐标
        return renderInto(index, Rect(0, 0, natural.width, natural.height), target)
    }

    override fun decodeRegion(index: Int, region: Rect, outWidth: Int, outHeight: Int): Bitmap? {
        if (region.isEmpty) return null
        val out = PageSize(max(1, outWidth), max(1, outHeight))
        return renderInto(index, region, out)
    }

    /**
     * 把 [region]（页面坐标）渲染到一张 [target] 像素大小的位图上。
     *
     * `PdfRenderer.Page.render` 的 `transform` 是「页面坐标 -> 位图坐标」的映射，
     * 所以「放大看细节」就是让这个矩阵的比例大于 1 —— 矢量重绘，不是位图放大。
     */
    private fun renderInto(index: Int, region: Rect, target: PageSize): Bitmap? {
        if (index < 0 || index >= pageCount) return null
        if (target.width <= 0 || target.height <= 0) return null

        return synchronized(lock) {
            try {
                val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                // PDF 页面本身是透明的，不铺白底会得到「黑底黑字」
                bitmap.eraseColor(Color.WHITE)

                val page = renderer.openPage(index)
                try {
                    val sx = target.width.toFloat() / region.width().toFloat()
                    val sy = target.height.toFloat() / region.height().toFloat()
                    val matrix = Matrix().apply {
                        setScale(sx, sy)
                        postTranslate(-region.left * sx, -region.top * sy)
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    page.close()
                }
                bitmap
            } catch (t: OutOfMemoryError) {
                null
            } catch (t: Throwable) {
                null
            }
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

/**
 * 把「请求的屏幕像素」翻译成「页面按几倍光栅化」。
 *
 * 这里的取舍与图片链路不同，因为 PDF 是**矢量**：`PdfRenderer` 报的页面尺寸
 * 单位是点（1/72 英寸），一页 A4 只有 595×842 —— 直接按它渲出来放到 1080 宽的
 * 屏幕上必然发虚。所以请求比「原尺寸」大时必须**放大渲**，而不是像位图那样
 * 「解码结果不小于请求即可」。
 *
 * 档位取 2 的幂：缩放过程中只会有几档，位图缓存才命中得上；
 * 否则每挪一个像素就是一个新键，等于没有缓存。
 */
private fun fitSize(natural: PageSize, reqWidth: Int, reqHeight: Int): PageSize {
    var required = 1f
    if (reqWidth > 0) required = maxOf(required, reqWidth.toFloat() / natural.width)
    if (reqHeight > 0 && reqHeight != Int.MAX_VALUE) {
        required = maxOf(required, reqHeight.toFloat() / natural.height)
    }

    var scale = 1f
    while (scale < required && scale < MAX_SCALE) scale *= 2f
    // 请求比原尺寸小时也别渲过头 —— 但要保证结果**不小于**请求，
    // 否则又变成「渲小了再放大」。
    while (scale > MIN_SCALE && scale / 2f >= required) scale /= 2f

    var w = (natural.width * scale).roundToInt().coerceAtLeast(1)
    var h = (natural.height * scale).roundToInt().coerceAtLeast(1)
    // 极端尺寸的兜底：宁可稍小，也不能把堆撑爆
    while (w.toLong() * h.toLong() > MAX_RENDER_PIXELS && scale > MIN_SCALE) {
        scale /= 2f
        w = (natural.width * scale).roundToInt().coerceAtLeast(1)
        h = (natural.height * scale).roundToInt().coerceAtLeast(1)
    }
    return PageSize(w, h)
}

/** 光栅化倍率的上下限。上限 8× 已足够覆盖 6× 手势缩放。 */
private const val MAX_SCALE = 8f
private const val MIN_SCALE = 0.125f

/** 单张渲染位图的像素上限（ARGB_8888 下约 96MB，与图片链路同一口径）。 */
private const val MAX_RENDER_PIXELS = 24_000_000L
