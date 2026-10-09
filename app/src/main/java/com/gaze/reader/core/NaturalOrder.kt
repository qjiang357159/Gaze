package com.gaze.reader.core

/**
 * 自然数字排序。
 *
 * 不做简单的字符串排序，而是把字符串切成「数字段」和「文本段」，
 * 数字段按数值大小比较，文本段按不区分大小写的字典序比较。
 *
 * 1.jpg / 2.jpg / 3.jpg / 10.jpg / 11.jpg  ->  1, 2, 3, 10, 11
 * 001.jpg / 002.jpg / 10.jpg               ->  001, 002, 10
 * Page 1 / Page 2 / Page 10                ->  1, 2, 10
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var i2 = i
                while (i2 < a.length && a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < b.length && b[j2].isDigit()) j2++
                val cmp = compareNumeric(a.substring(i, i2), b.substring(j, j2))
                if (cmp != 0) return cmp
                i = i2
                j = j2
            } else {
                val lc = ca.lowercaseChar()
                val rc = cb.lowercaseChar()
                if (lc != rc) return lc.compareTo(rc)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** 纯数字段比较：先比有效位数（去掉前导零），再逐位比，最后前导零少的优先。 */
    private fun compareNumeric(x: String, y: String): Int {
        val sx = x.trimStart('0')
        val sy = y.trimStart('0')
        if (sx.length != sy.length) return sx.length - sy.length
        val c = sx.compareTo(sy)
        if (c != 0) return c
        return x.length - y.length
    }
}

/** 按自然顺序排序字符串列表。 */
fun List<String>.sortedNaturally(): List<String> = sortedWith(NaturalOrder)
