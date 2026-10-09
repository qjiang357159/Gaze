package com.gaze.reader.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.util.Xml
import com.gaze.reader.image.PageDecoder
import com.gaze.reader.image.PageSize
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * EPUB 的页面提供者。
 *
 * ## 设计取向
 *
 * 目标是**本地阅读 + 漫画 / 图片型 EPUB**，不是做一个完整的 EPUB 排版引擎。
 * 所以这里刻意**不引入任何 HTML 渲染引擎**（那才是「过重的依赖」）：
 *
 * - **图片型章节**（漫画）：按 spine 顺序把每张图片当成一页，
 *   字节原样交给 [PageDecoder] —— 与 CBZ 走的是同一条解码、分块、缓存链路，
 *   连「放大时按区域解码」都是白送的。
 * - **文字型章节**（小说）：用系统自带的 `Html.fromHtml` + `StaticLayout`
 *   把整章排一次版，再按固定页高切成若干页，**现画成位图**。
 *   这样它也能落进阅读器那套「按屏幕像素渲染 / 放大时重画」的框架里，
 *   而不是为它单开一条排版路径。
 *
 * 于是「加一种格式不碰阅读器」这件事成立了：阅读器只认 [PageProvider]。
 *
 * ## 明确不支持的
 *
 * CSS 布局、竖排、脚注浮层、DRM、多媒体。这些要么需要真正的排版引擎，
 * 要么需要解密，都不值得为「看漫画」引入。
 */
class EpubPageProvider(context: Context, uri: Uri) : PageProvider {

    private val zip = ZipEntries(UriChannel.openOrThrow(context, uri))

    /** OPF（清单文件）在归档内的路径与所在目录。 */
    private val opfPath: String
    private val opfDir: String

    /** manifest：id -> (绝对路径, media-type, properties)。 */
    private val manifest = HashMap<String, ManifestItem>()

    /** spine：按阅读顺序排列的 manifest id。 */
    private val spine = ArrayList<String>()

    private var navPath: String? = null
    private var ncxPath: String? = null
    private var coverPath: String? = null

    override val formatLabel: String = "EPUB"

    /**
     * 页面索引（图片页 / 某章节的第几个文字页）与「资源路径 -> 首页序号」。
     *
     * **惰性构建**：建索引要把 spine 里每一章都读一遍、文字章节还要排一次版，
     * 是有成本的操作。而封面生成器只想要封面、`CoverGenerator` 会给库里每一本
     * 缺封面的书都开一次 provider —— 让它们为「页数」付全价没有道理。
     */
    private val book: BookIndex by lazy { buildIndex() }

    override val pageCount: Int get() = book.pages.size

    /** 文字页的前景 / 背景（跟随阅读器背景）。 */
    private var textForeground: Int = Color.BLACK
    private var textBackground: Int = Color.WHITE

    /** 图片字节的小缓存 —— 与归档链路同口径，避免同一页反复解压。 */
    private val imageBytes = object : LruCache<Int, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ByteArray): Int = value.size
    }

    /** 排版结果缓存。一章一个 `StaticLayout`，只留最近几个。 */
    private val layouts = object : LruCache<String, StaticLayout>(4) {}

    init {
        opfPath = findOpfPath() ?: throw java.io.IOException("EPUB 缺少 OPF 清单")
        opfDir = opfPath.substringBeforeLast('/', "")
        parseOpf()
    }

    // ------------------------------------------------------------ 索引构建

    private fun findOpfPath(): String? {
        val text = zip.readText("META-INF/container.xml", 4L * 1024 * 1024) ?: return null
        var path: String? = null
        walkXml(
            text,
            onStart = { name, attr ->
                if (name == "rootfile") {
                    path = attr("full-path")
                }
            }
        )
        return path?.let { ZipEntries.normalize(it) }
    }

    private fun parseOpf() {
        val text = zip.readText(opfPath, 32L * 1024 * 1024) ?: return
        val tocId = StringBuilder()
        walkXml(
            text,
            onStart = { name, attr ->
                when (name) {
                    "item" -> {
                        val id = attr("id")
                        val href = attr("href")
                        if (id != null && href != null) {
                            val abs = resolveHref(href)
                            val media = attr("media-type").orEmpty()
                            val properties = attr("properties").orEmpty()
                            manifest[id] = ManifestItem(abs, media, properties)

                            if (properties.split(' ', ',').any { it.trim() == "nav" }) navPath = abs
                            if (media.equals("application/x-dtbncx+xml", ignoreCase = true)) ncxPath = abs
                            if (properties.contains("cover-image")) coverPath = abs
                        }
                    }
                    "spine" -> tocId.append(attr("toc").orEmpty())
                    "itemref" -> attr("idref")?.let { spine += it }
                }
            }
        )
        // EPUB2 的目录不在 manifest 的 properties 里，而是 spine@toc 指过来的
        if (ncxPath == null && tocId.isNotEmpty()) ncxPath = manifest[tocId.toString()]?.path
        if (coverPath == null) {
            // 退化：id 或文件名里带 cover 的第一张图
            coverPath = manifest.values
                .firstOrNull { it.mediaType.startsWith("image/") && it.path.contains("cover", true) }
                ?.path
        }
    }

    private fun buildIndex(): BookIndex {
        val pages = ArrayList<PageRef>()
        val firstPage = HashMap<String, Int>()

        fun append(ref: PageRef, path: String) {
            firstPage.putIfAbsent(path, pages.size)
            pages += ref
        }

        spine.forEach { idref ->
            val item = manifest[idref] ?: return@forEach
            val html = zip.readText(item.path, 32L * 1024 * 1024) ?: return@forEach
            val images = imagesIn(html).map { ZipEntries.resolve(item.path, it) }
                .filter { zip.contains(it) }
            val plainLength = plainTextLength(html)

            val useImages = images.isNotEmpty() &&
                (plainLength < TEXT_THRESHOLD || images.size >= MIN_IMAGE_PAGE_COUNT)

            if (useImages) {
                images.forEach { path -> append(PageRef.Image(path), path) }
                return@forEach
            }
            if (plainLength <= 0) return@forEach

            val parts = partsOf(item.path)
            repeat(parts) { part -> append(PageRef.Text(item.path, part), item.path) }
        }
        return BookIndex(pages, firstPage)
    }

    /**
     * 一章按固定页高能切成几页。
     *
     * 必须真排一次版才知道 —— 用字数估算在中文（等宽近似）上勉强能用，
     * 遇到中英混排、标点、插图就完全不准，而**估少了会把章节尾巴弄丢**，
     * 那比多等一会儿严重得多。排出来的 `StaticLayout` 会进 [layouts] 缓存，
     * 真正翻到这章时不用再排一次。
     */
    private fun partsOf(path: String): Int {
        val layout = layoutFor(path) ?: return 0
        if (layout.height <= 0) return 0
        return max(1, ceil(layout.height.toDouble() / CONTENT_HEIGHT).toInt())
    }

    // ------------------------------------------------------------ 取页

    override fun sizeOf(index: Int): PageSize? = when (val ref = book.pages.getOrNull(index)) {
        is PageRef.Image -> imageSizeOf(index, ref.path)
        is PageRef.Text -> TEXT_PAGE_SIZE
        null -> null
    }

    private fun imageSizeOf(index: Int, path: String): PageSize? {
        val data = imageData(index, path) ?: return null
        return PageDecoder.probe(data)
    }

    override fun decode(index: Int, reqWidth: Int, reqHeight: Int, fullResolution: Boolean): Bitmap? {
        return when (val ref = book.pages.getOrNull(index) ?: return null) {
            is PageRef.Image -> {
                val data = imageData(index, ref.path) ?: return null
                PageDecoder.decode(data, reqWidth, reqHeight, fullResolution)
            }
            is PageRef.Text -> {
                val target = if (reqWidth > 0) {
                    PageSize(reqWidth, max(1, (reqWidth * PAGE_RATIO).roundToInt()))
                } else {
                    TEXT_PAGE_SIZE
                }
                renderText(ref, Rect(0, 0, TEXT_PAGE_SIZE.width, TEXT_PAGE_SIZE.height), target)
            }
        }
    }

    override fun decodeRegion(index: Int, region: Rect, outWidth: Int, outHeight: Int): Bitmap? {
        if (region.isEmpty || outWidth <= 0 || outHeight <= 0) return null
        return when (val ref = book.pages.getOrNull(index) ?: return null) {
            is PageRef.Image -> {
                val data = imageData(index, ref.path) ?: return null
                PageDecoder.decodeRegion(data, region, outWidth, outHeight)
            }
            is PageRef.Text -> renderText(ref, region, PageSize(outWidth, outHeight))
        }
    }

    override fun coverBitmap(maxWidth: Int): Bitmap? {
        // 刻意不碰 book：封面只需要 OPF 里那条 cover-image，没必要为它建整本索引
        val cover = coverPath
        if (cover != null) {
            val data = zip.read(cover, MAX_IMAGE_BYTES)
            if (data != null) {
                PageDecoder.decode(data, maxWidth, maxWidth * 3 / 2, false)?.let { return it }
            }
        }
        return decode(0, maxWidth, maxWidth * 3 / 2, false)
    }

    override fun setTextColors(background: Int, foreground: Int) {
        if (background == textBackground && foreground == textForeground) return
        textBackground = background
        textForeground = foreground
        // 排版本身与颜色无关（只影响绘制时的 paint），但已排好的 layout 里
        // 每个 span 的颜色是建 layout 时烘进去的 —— 换色必须重排。
        layouts.evictAll()
    }

    private fun imageData(index: Int, path: String): ByteArray? {
        imageBytes.get(index)?.let { return it }
        val data = zip.read(path, MAX_IMAGE_BYTES) ?: return null
        imageBytes.put(index, data)
        return data
    }

    // ------------------------------------------------------------ 文字渲染

    private fun layoutFor(path: String): StaticLayout? {
        layouts.get(path)?.let { return it }
        val html = zip.readText(path, 32L * 1024 * 1024) ?: return null
        val spanned = runCatching {
            Html.fromHtml(stripNonContent(html), Html.FROM_HTML_MODE_COMPACT)
        }.getOrNull() ?: return null

        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = TEXT_SIZE
            color = textForeground
        }
        val layout = runCatching {
            StaticLayout.Builder
                .obtain(spanned, 0, spanned.length, paint, TEXT_LAYOUT_WIDTH - 2 * TEXT_PADDING)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, TEXT_LINE_SPACING)
                .setIncludePad(false)
                .build()
        }.getOrNull() ?: return null

        layouts.put(path, layout)
        return layout
    }

    /**
     * 把某一页（或页内一块）画出来。
     *
     * 关键在**不是把排好的位图缩放**，而是把矢量排版按目标比例重新绘制 ——
     * 所以放大看文字是清晰的，和图片链路的「区域解码」是同一个精神。
     */
    private fun renderText(ref: PageRef.Text, region: Rect, target: PageSize): Bitmap? {
        val layout = layoutFor(ref.path) ?: return null
        return try {
            val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(textBackground)
            val canvas = Canvas(bitmap)
            val scale = target.width.toFloat() / region.width().toFloat()
            canvas.scale(scale, scale)
            // 页面坐标 -> 位图坐标：先抵消 region 原点，再摆到这一页的位置
            canvas.translate(
                (TEXT_PADDING - region.left).toFloat(),
                (TEXT_PADDING - ref.part * CONTENT_HEIGHT - region.top).toFloat()
            )
            layout.draw(canvas)
            bitmap
        } catch (t: OutOfMemoryError) {
            null
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ 目录

    override val toc: List<TocEntry> by lazy { buildToc() }

    private fun buildToc(): List<TocEntry> {
        val nav = navPath
        if (nav != null) {
            val html = zip.readText(nav, 8L * 1024 * 1024)
            if (html != null) {
                val entries = anchorsIn(html).mapNotNull { (title, href) ->
                    val page = pageOf(nav, href) ?: return@mapNotNull null
                    TocEntry(title, page)
                }
                if (entries.isNotEmpty()) return entries
            }
        }
        val ncx = ncxPath
        if (ncx != null) {
            val text = zip.readText(ncx, 8L * 1024 * 1024)
            if (text != null) return ncxToc(text, ncx)
        }
        return emptyList()
    }

    /**
     * 解析 EPUB2 的 `toc.ncx`。
     *
     * 必须**成对**处理 `navPoint` 的开始与结束：条目是在闭合时才能确定的
     * （标题在 `<navLabel><text>` 里、目标在紧随其后的 `<content src>` 里），
     * 而且嵌套结构要求「当前标题」跟着栈走 —— 用一个变量存标题，
     * 子条目一闭合就会把父条目的标题覆盖掉。
     */
    private fun ncxToc(text: String, ncx: String): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        val stack = ArrayList<NavPoint>()

        walkXml(
            text,
            onStart = { name, attr ->
                when (name) {
                    "navpoint" -> stack += NavPoint()
                    "text" -> if (stack.isNotEmpty()) stack.last().capturing = true
                    // 只认 navPoint 自己的 content；嵌套时内层会先闭合，所以不会被覆盖
                    "content" -> if (stack.isNotEmpty() && stack.last().src == null) {
                        stack.last().src = attr("src")
                    }
                }
            },
            onEnd = { name ->
                when (name) {
                    "text" -> if (stack.isNotEmpty()) {
                        stack.last().let { it.capturing = false; it.title = it.buffer.toString().trim() }
                    }
                    "navpoint" -> {
                        if (stack.isNotEmpty()) {
                            val node = stack.removeAt(stack.size - 1)
                            val page = node.src?.let { pageOf(ncx, it) }
                            if (page != null && node.title.isNotBlank()) {
                                out += TocEntry(node.title, page, stack.size)
                            }
                        }
                    }
                }
            },
            onText = { t -> if (stack.lastOrNull()?.capturing == true) stack.last().buffer.append(t) }
        )
        // 条目在文件里的顺序天然就是阅读顺序，不必再排
        return out
    }

    /**
     * 把目录里的 `href` 翻成页码。
     *
     * 目录经常指向章节内的锚点（`ch1.xhtml#s3`）或一个不含图片的 XHTML 壳，
     * 这些都会在 [ZipEntries.resolve] 里退化成「同一个章节文件」，
     * 于是落到 [firstPageOf] 上。找不到就返回 null（该条目不显示，而不是跳到第一页）。
     */
    private fun pageOf(basePath: String, href: String): Int? =
        book.firstPage[ZipEntries.resolve(basePath, href)]

    /** NCX 的 `<navPoint>` 解析中间态。 */
    private class NavPoint {
        var title: String = ""
        var src: String? = null
        var capturing: Boolean = false
        val buffer = StringBuilder()
    }

    override fun close() {
        runCatching { zip.close() }
        imageBytes.evictAll()
        layouts.evictAll()
    }

    // ------------------------------------------------------------ 内部类型

    private sealed interface PageRef {
        data class Image(val path: String) : PageRef
        data class Text(val path: String, val part: Int) : PageRef
    }

    private data class ManifestItem(val path: String, val mediaType: String, val properties: String)

    /** 建好的页面索引。一起算出来，因为 `firstPage` 必须和 `pages` 同步。 */
    private class BookIndex(val pages: List<PageRef>, val firstPage: Map<String, Int>)

    private fun resolveHref(href: String): String =
        ZipEntries.normalize(if (opfDir.isEmpty()) href else "$opfDir/$href")
}

// ================================================================ 常量

/** 文字页的排版宽度（px）。所有文字页的「原始尺寸」都按它算，比例才稳定。 */
private const val TEXT_LAYOUT_WIDTH = 1000

/** 页高 / 页宽。取 1:1.414（近似 A 系列的 √2），竖屏阅读时比例自然。 */
private const val PAGE_RATIO = 1.414f

private val TEXT_PAGE_SIZE = PageSize(TEXT_LAYOUT_WIDTH, (TEXT_LAYOUT_WIDTH * PAGE_RATIO).toInt())

/** 文字四周的留白（排版坐标下）。 */
private const val TEXT_PADDING = 56

/** 每页正文高度（去掉上下留白）。 */
private const val CONTENT_HEIGHT = (TEXT_LAYOUT_WIDTH * PAGE_RATIO).toInt() - 2 * TEXT_PADDING

private const val TEXT_SIZE = 30f
private const val TEXT_LINE_SPACING = 1.25f

/** 一章里的纯文字超过这个字数，就按「文字章节」处理（哪怕它有插图）。 */
private const val TEXT_THRESHOLD = 300

/** 一章里图片多于这个数，无条件按图片章节处理（漫画的判定）。 */
private const val MIN_IMAGE_PAGE_COUNT = 4

private const val MAX_IMAGE_BYTES = 192L * 1024 * 1024

// ================================================================ 解析工具

/**
 * 遍历 XML。
 *
 * 只关心元素与文本，不关心命名空间（EPUB 的 `dc:` / `opf:` 前缀满天飞，
 * 而属性一律用**不带前缀的原名**查 —— 例如 `full-path`、`media-type`）。
 * 解析失败时静默结束，让调用方拿到的部分结果兜底。
 */
private inline fun walkXml(
    text: String,
    onStart: (name: String, attr: (String) -> String?) -> Unit,
    onEnd: (String) -> Unit = {},
    onText: (String) -> Unit = {}
) {
    val parser = Xml.newPullParser()
    runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
    runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
    runCatching { parser.setInput(StringReader(text)) }.onFailure { return }
    var event = runCatching { parser.eventType }.getOrDefault(XmlPullParser.END_DOCUMENT)
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                val name = runCatching { parser.name.substringAfter(':').lowercase(Locale.ROOT) }
                    .getOrDefault("")
                onStart(name) { attr ->
                    runCatching { parser.getAttributeValue(null, attr) }.getOrNull()
                }
            }
            XmlPullParser.END_TAG -> {
                val name = runCatching { parser.name.substringAfter(':').lowercase(Locale.ROOT) }
                    .getOrDefault("")
                onEnd(name)
            }
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                val t = runCatching { parser.text }.getOrNull()
                if (!t.isNullOrEmpty()) onText(t)
            }
        }
        event = runCatching { parser.next() }.getOrDefault(XmlPullParser.END_DOCUMENT)
    }
}

/**
 * 从 XHTML 里抠出图片引用。
 *
 * 用正则而不是 XML 解析器：真实的 EPUB 里 XHTML 经常带未声明的实体、
 * 自闭合标签写法不统一、`<svg>` 包 `<image>` 的写法满天飞，
 * XML 解析器一遇到就整篇抛异常。而我们要的只是几个 URL。
 */
private val IMG_PATTERN = Regex(
    """<(?:img|image)\b[^>]*?\b(?:src|xlink:href|href)\s*=\s*["']([^"']+)["']""",
    RegexOption.IGNORE_CASE
)

private fun imagesIn(html: String): List<String> =
    IMG_PATTERN.findAll(html)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() && !it.startsWith("data:") }
        .toList()

/** 目录锚点：`<a href="x.xhtml">标题</a>`。 */
private val ANCHOR_PATTERN = Regex(
    """<a\b[^>]*?\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

private fun anchorsIn(html: String): List<Pair<String, String>> =
    ANCHOR_PATTERN.findAll(html).mapNotNull { m ->
        val href = m.groupValues[1]
        val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
        if (title.isEmpty()) null else title to href
    }.toList()

/** 去掉 head / style / script，避免把 CSS 当成正文。 */
private fun stripNonContent(html: String): String = html
    .replace(Regex("<head\\b.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    .replace(Regex("<style\\b.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    .replace(Regex("<script\\b.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

/** 正文纯文字长度，用来判断这一章到底是漫画还是小说。 */
private fun plainTextLength(html: String): Int {
    val body = stripNonContent(html)
    val withoutTags = body
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
    return withoutTags.trim().length
}
