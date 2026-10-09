package com.gaze.reader.core

/**
 * 阅读模式。
 *
 * 统一为**单页 / 双页 / 纵向**三种，单双页一律**从左向右**翻。
 *
 * 之前还有「横向连续」与独立的「阅读方向」两项 —— 前者与单页行为完全一致，
 * 后者让用户多背一个概念却几乎没人改。去掉之后模式选择一目了然。
 */
enum class ReadMode(val key: String, val label: String, val hint: String) {
    SINGLE("SINGLE", "单页", "一页一页，从左向右翻"),
    DUAL("DUAL", "双页", "同时显示两页，从左向右翻"),
    CONTINUOUS_V("CONTINUOUS_V", "纵向", "上下滚动，适合条漫与 Webtoon");

    companion object {
        fun of(key: String?): ReadMode = entries.firstOrNull { it.key == key } ?: SINGLE
    }
}

/**
 * 页面渲染模式。
 *
 * 两者都是**清晰度优先**，区别只在「要不要超出显示所需的像素」：
 * - [OPTIMIZED] 按显示尺寸解码，采样率保证解码结果不小于屏幕像素，
 *   超出视口的部分（放大时才看得见）按需区域解码；
 * - [TRADITIONAL] 整页按原图像素解码，永远是最清晰的那一档，
 *   代价是内存与打开时间。
 */
enum class RenderMode(val key: String, val label: String, val hint: String) {
    OPTIMIZED("OPTIMIZED", "按需渲染", "按显示尺寸解码 · 放大时自动请求更高分辨率区域"),
    TRADITIONAL("TRADITIONAL", "原尺寸渲染", "整页按原图像素解码，最清晰，内存占用最高");

    companion object {
        fun of(key: String?): RenderMode =
            entries.firstOrNull { it.key == key } ?: OPTIMIZED
    }
}

/** 图片适配方式。 */
enum class FitMode(val key: String, val label: String) {
    SCREEN("SCREEN", "适应屏幕"),
    WIDTH("WIDTH", "适应宽度"),
    HEIGHT("HEIGHT", "适应高度"),
    ORIGINAL("ORIGINAL", "原始比例");

    companion object {
        fun of(key: String?): FitMode =
            entries.firstOrNull { it.key == key } ?: SCREEN
    }
}

/** 主题模式（明暗）。 */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("SYSTEM", "跟随系统"),
    LIGHT("LIGHT", "浅色"),
    DARK("DARK", "深色");

    companion object {
        fun of(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * 主题配色。
 *
 * 每一套预设都是「一种纸 + 一种墨 + 一个强调色」的完整组合 ——
 * 用户换的不只是底色，而是整本书的气质。另有 [CUSTOM]：
 * 由用户挑一个主色，程序从它派生出纸色、墨色与强调色。
 */
enum class ThemeVariant(val key: String, val label: String) {
    PAPER("PAPER", "宣纸"),
    CREAM("CREAM", "米黄"),
    MIST("MIST", "灰蓝"),
    SAGE("SAGE", "青瓷"),
    ROSE("ROSE", "藕粉"),
    GRAPHITE("GRAPHITE", "石墨"),
    PLUM("PLUM", "紫檀"),
    CUSTOM("CUSTOM", "自定义");

    companion object {
        fun of(key: String?): ThemeVariant =
            entries.firstOrNull { it.key == key } ?: PAPER
    }
}

/** 阅读器背景（与系统主题解耦）。 */
enum class ReaderBackground(val key: String, val label: String, val argb: Int) {
    BLACK("BLACK", "黑色", 0xFF000000.toInt()),
    DARK_GRAY("DARK_GRAY", "深灰", 0xFF1C1C1E.toInt()),
    WHITE("WHITE", "白色", 0xFFFFFFFF.toInt());

    companion object {
        fun of(key: String?): ReaderBackground =
            entries.firstOrNull { it.key == key } ?: BLACK
    }
}

/**
 * 书架视图。
 *
 * - [ALL] / [FAVORITE]：把漫画平铺成一面墙；
 * - [FOLDER] / [TAG]：以「手机文件夹」「标签」为单位聚合 ——
 *   同一个目录 / 同一个标签下的漫画收进一个**书架文件夹**里，点进去再看。
 */
enum class LibraryView(val key: String, val label: String) {
    ALL("ALL", "全部"),
    FAVORITE("FAVORITE", "收藏"),
    FOLDER("FOLDER", "文件夹"),
    TAG("TAG", "标签");

    companion object {
        fun of(key: String?): LibraryView =
            entries.firstOrNull { it.key == key } ?: ALL
    }
}

/** 书架筛选（内部口径，UI 上不再单独陈列 —— 视图切换已经覆盖主要场景）。 */
enum class LibraryFilter(val key: String, val label: String) {
    ALL("ALL", "全部"),
    RECENT("RECENT", "最近阅读"),
    ADDED("ADDED", "最近添加"),
    UNREAD("UNREAD", "未读"),
    READING("READING", "阅读中"),
    FINISHED("FINISHED", "已完成"),
    FAVORITE("FAVORITE", "收藏");

    companion object {
        fun of(key: String?): LibraryFilter =
            entries.firstOrNull { it.key == key } ?: ALL
    }
}

/**
 * 排序方式。
 *
 * 已去掉「评分」—— 评分功能不做了，排序里留一个永远空着的选项只会让人困惑。
 */
enum class SortOrder(val key: String, val label: String) {
    NAME_ASC("NAME_ASC", "名称 A-Z"),
    NAME_DESC("NAME_DESC", "名称 Z-A"),
    AUTHOR("AUTHOR", "作者"),
    LAST_READ("LAST_READ", "最近阅读"),
    ADDED_DESC("ADDED_DESC", "最近添加"),
    MODIFIED_DESC("MODIFIED_DESC", "最近修改"),
    SIZE_DESC("SIZE_DESC", "文件大小"),
    PAGES_DESC("PAGES_DESC", "页数"),
    PROGRESS_DESC("PROGRESS_DESC", "阅读进度");

    companion object {
        fun of(key: String?): SortOrder =
            entries.firstOrNull { it.key == key } ?: ADDED_DESC
    }
}

/**
 * 书架排版。
 *
 * 两档网格 + 两档列表，共四种。老值 `GRID` / `LIST` 做兼容映射，
 * 用户升级后不会突然掉回默认排版。
 */
enum class LibraryLayout(val key: String, val label: String) {
    GRID_2("GRID_2", "网格 · 2 列"),
    GRID_3("GRID_3", "网格 · 3 列"),
    LIST_SMALL("LIST_SMALL", "列表 · 小封面"),
    LIST_LARGE("LIST_LARGE", "列表 · 大封面");

    val isGrid: Boolean get() = this == GRID_2 || this == GRID_3

    companion object {
        fun of(key: String?): LibraryLayout = when (key) {
            "GRID" -> GRID_2
            "LIST" -> LIST_SMALL
            else -> entries.firstOrNull { it.key == key } ?: GRID_2
        }
    }
}

/** 阅读页屏幕方向偏好。 */
enum class ScreenOrientation(val key: String, val label: String) {
    SYSTEM("SYSTEM", "跟随系统"),
    PORTRAIT("PORTRAIT", "竖屏"),
    LANDSCAPE("LANDSCAPE", "横屏");

    companion object {
        fun of(key: String?): ScreenOrientation =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** 自动备份频率。 */
enum class AutoBackupInterval(val key: String, val label: String, val durationMs: Long) {
    OFF("OFF", "关闭", 0L),
    DAILY("DAILY", "每天", 24L * 60 * 60 * 1000),
    WEEKLY("WEEKLY", "每周", 7L * 24 * 60 * 60 * 1000),
    MONTHLY("MONTHLY", "每月", 30L * 24 * 60 * 60 * 1000);

    companion object {
        fun of(key: String?): AutoBackupInterval =
            entries.firstOrNull { it.key == key } ?: OFF
    }
}
