package com.gaze.reader.core

/**
 * 文件名元数据解析。
 *
 * 提供两类互不相同的识别方式，**可以分别启用**：
 *
 * | 类型 | 判据 | 适合的命名 |
 * |---|---|---|
 * | 分隔符 | 文件名中**最后一个**分隔符右侧的内容是作者 | `作品名_作者.cbz` |
 * | 括号 | 文件名中**第一对**括号里的内容是作者 | `[作者] 作品名.cbz` |
 *
 * 两类都启用时**括号优先** —— 括号是显式标记，比「最后一个分隔符」这种
 * 位置约定可靠得多。`[作者] 作品名_第1卷.cbz` 如果先按分隔符切，
 * 作者会变成「第1卷」；先按括号切才是对的。
 *
 * ## 刻意不做的事
 *
 * 不做任何启发式猜测（不猜「哪一段像人名」、不查词典、不调模型）。
 * 漫画文件命名极其混乱，任何自动规则的准确率都上不去 ——
 * 这里只是把开发者个人的命名习惯做成开关，真正的兜底永远是**手动改**。
 */
object FileNameParser {

    data class Parsed(val title: String, val author: String?)

    /** 当前生效的解析规则。 */
    data class Rules(
        val bySeparator: Boolean = true,
        val separator: String = DEFAULT_SEPARATOR,
        val byBracket: Boolean = false,
        val bracket: String = DEFAULT_BRACKET
    ) {
        /** 是否要推断作者。两个开关都关掉时就只解析标题。 */
        val parseAuthor: Boolean get() = bySeparator || byBracket
    }

    const val DEFAULT_SEPARATOR = "_"
    const val DEFAULT_BRACKET = "()"

    /** 预设分隔符。自定义的那个走 [sanitizeSeparator]。 */
    val SEPARATORS: List<String> = listOf("_", "-", " ")

    /** 预设括号（开 + 闭两个字符）。 */
    val BRACKETS: List<String> = listOf("()", "[]", "【】", "（）")

    /** 自定义分隔符只允许**单个**非空白字符。 */
    fun sanitizeSeparator(raw: String): String? {
        val value = raw.trim()
        return value.takeIf { it.length == 1 && !it[0].isWhitespace() }
    }

    /** 自定义括号只允许**一对**非空白字符，且开闭不能相同。 */
    fun sanitizeBracket(raw: String): String? {
        val value = raw.trim()
        if (value.length != 2) return null
        if (value[0].isWhitespace() || value[1].isWhitespace()) return null
        if (value[0] == value[1]) return null
        return value
    }

    /**
     * @param rules 见 [Rules]。
     */
    fun parse(rawFileName: String, rules: Rules = Rules()): Parsed {
        val base = rawFileName.substringBeforeLast('.', rawFileName).trim()
        if (base.isEmpty()) return Parsed(rawFileName.trim(), null)

        // 括号优先：显式标记 > 位置约定
        if (rules.byBracket) parseBracket(base, rules.bracket)?.let { return it }
        if (rules.bySeparator) parseSeparator(base, rules.separator)?.let { return it }
        return Parsed(base, null)
    }

    /**
     * 取**第一对**括号里的内容作为作者，标题是去掉这一对括号之后剩下的部分。
     *
     * 只用 `indexOf` 做字面查找，不碰正则 —— 自定义括号完全可能是
     * `(`、`[`、`{` 这类正则元字符，用正则会直接抛异常或匹配出诡异结果。
     */
    private fun parseBracket(base: String, bracket: String): Parsed? {
        if (bracket.length != 2) return null
        val open = bracket[0]
        val close = bracket[1]

        val start = base.indexOf(open)
        if (start < 0) return null
        val end = base.indexOf(close, start + 1)
        if (end < 0) return null

        val author = base.substring(start + 1, end).trim()
        if (author.isEmpty()) return null

        // 去掉这一对括号（连同内容），再把剩下的空白收拢成一个空格
        val title = (base.substring(0, start) + " " + base.substring(end + 1))
            .replace(WHITESPACE, " ")
            .trim()
        // 整个文件名就是一对括号时，没有标题可言 —— 保持原名更安全
        if (title.isEmpty()) return null

        return Parsed(title, author)
    }

    /** 最后一个分隔符右侧是作者。分隔符在开头或结尾都不构成作者语义。 */
    private fun parseSeparator(base: String, separator: String): Parsed? {
        if (separator.isEmpty()) return null
        val idx = base.lastIndexOf(separator)
        if (idx <= 0 || idx + separator.length >= base.length) return null

        val title = base.substring(0, idx).trim()
        val author = base.substring(idx + separator.length).trim()
        if (title.isEmpty() || author.isEmpty()) return null
        return Parsed(title, author)
    }

    private val WHITESPACE = Regex("\\s+")
}
