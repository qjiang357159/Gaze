package com.gaze.reader.archive

/**
 * 把「文件打不开」的异常翻译成一句用户能看懂、且**能据此行动**的说明。
 *
 * v1.0.0 只有一句「可能是压缩包损坏、带密码，或者文件已被移动」——
 * 三种完全不同的原因混在一起，用户不知道该去做什么。
 *
 * 放在这里而不是 ViewModel 里，是因为阅读页和文件健康检查都要用同一套判断。
 * 名字里不带具体格式：ZIP / RAR / 7Z / TAR / PDF / EPUB 共用这一套。
 */
fun describeOpenError(t: Throwable?): String {
    val raw = (t?.message ?: "").lowercase()
    return when {
        // 加密压缩包 / 加密 PDF：ZipFile、junrar、SevenZFile、PdfRenderer
        // 抛出的消息里都会带这些词
        raw.contains("password") || raw.contains("encrypt") ||
            raw.contains("crypto") || raw.contains("decrypt") ->
            "文件带密码，Gaze 无法读取加密文件"

        t is java.io.FileNotFoundException ->
            "文件已不存在，可能被移动或删除"

        t is SecurityException ->
            "没有读取权限，所在文件夹的授权可能已失效"

        // EPUB 少了 OPF 清单就等于没有目录结构，读不出一页
        raw.contains("opf") || raw.contains("epub") ->
            "EPUB 结构不完整（缺少清单文件），可能已损坏"

        raw.contains("pdf") ->
            "PDF 无法渲染，可能是文件损坏或格式不受支持"

        raw.contains("无法识别") || raw.contains("not a") ||
            raw.contains("unsupported") || raw.contains("unknown") ->
            "无法识别格式，可能已损坏或扩展名与实际内容不符"

        raw.contains("eof") || raw.contains("corrupt") || raw.contains("truncat") ->
            "文件结构已损坏，可能是下载不完整"

        else -> "文件读取失败，可能是文件损坏或存储暂时不可用"
    }
}
