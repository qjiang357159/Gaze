package com.gaze.reader.domain

/**
 * 标签配色。
 *
 * 刻意选一组**低饱和的印刷油墨色**，而不是系统默认那套高饱和色板 ——
 * Gaze 的视觉关键词是「纸张 / 墨迹 / 留白」，标签是这套语言里唯一的彩色元素，
 * 所以它应该是「印上去的」，不是「发光的」。
 */
object TagPalette {

    val COLORS: List<Int> = listOf(
        0xFF3A5A8C.toInt(), // 靛蓝
        0xFFB4472F.toInt(), // 朱红
        0xFF3F6B4F.toInt(), // 墨绿
        0xFFA8763E.toInt(), // 赭石
        0xFF6B4E7A.toInt(), // 紫
        0xFF3E6F73.toInt(), // 青
        0xFFA64B6B.toInt(), // 玫
        0xFF5A6472.toInt(), // 灰蓝
        0xFF2F5F8C.toInt(), // 群青
        0xFF8C5A2F.toInt(), // 焦糖
        0xFF4A7A5F.toInt(), // 松绿
        0xFF7A5A3F.toInt(), // 棕
        0xFF5A4A7A.toInt(), // 紫棠
        0xFF3A6B4A.toInt(), // 苔
        0xFF8C3F4A.toInt(), // 绛
        0xFF3A3A3E.toInt(), // 墨
    )

    /** 挑一个当前还没被用过的颜色，避免一屏标签全是同色。 */
    fun nextUnused(used: List<Int>): Int {
        val usedSet = used.toHashSet()
        return COLORS.firstOrNull { it !in usedSet } ?: COLORS[used.size % COLORS.size]
    }
}
