package com.gaze.reader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一本漫画。
 *
 * 字段按文档第二十二章列出，并补上阅读进度（第十章）与备份匹配（第十五章）所需的部分。
 *
 * 关于「用户手动改过的数据任何自动规则都不许覆盖」：
 * [manualTitle] / [manualAuthor] 两个标志位就是为此存在的 ——
 * 重新扫描时只要它们为 true，解析规则就不得回写这两个字段。
 */
@Entity(
    tableName = "comics",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["fingerprint"]),
        Index(value = ["sampleHash"]),
        Index(value = ["missing"])
    ]
)
data class ComicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** SAF 文档 uri，唯一。 */
    val uri: String,

    /** 原始文件名，永远保留（文档第四章）。 */
    val fileName: String,

    /** 显示标题。 */
    val title: String,

    /** 作者：文件名最后一个下划线右侧的内容。 */
    val author: String? = null,

    /** 容器格式标签：ZIP / RAR / 7Z / TAR。 */
    val format: String = "ZIP",

    /** 文件字节数。 */
    val size: Long = 0,

    /** 文件最后修改时间。 */
    val modifiedTime: Long = 0,

    /** 页面数量。 */
    val pageCount: Int = 0,

    /** 缩略图缓存文件的绝对路径；未生成时为 null。 */
    val coverPath: String? = null,

    /** 收藏 —— 与标签是两个独立概念（文档第十四章）。 */
    val favorite: Boolean = false,

    /** 入库时间。 */
    val addedTime: Long = 0,

    // ---------------------------------------------------------- 阅读进度

    val lastReadTime: Long = 0,
    val currentPage: Int = 0,
    /** 0f..1f */
    val progress: Float = 0f,

    /** 每本漫画可独立保存阅读模式 / 适配方式，null 表示跟随全局默认。 */
    val readMode: String? = null,

    /**
     * 已废弃的阅读方向列。
     *
     * 阅读方向已从产品里去掉（单页 / 双页一律从左向右），但**列不能删** ——
     * 删列要重建表，风险远大于收益。保留它只为兼容老数据与老备份。
     */
    val readDirection: String? = null,
    val fitMode: String? = null,
    val zoom: Float? = null,

    // ---------------------------------------------------------- 备份 / 重匹配

    /** 内容指纹：sha256(算法版本 | 文件名 | 大小 | 页数 | 头尾采样哈希)。 */
    val fingerprint: String? = null,

    /** 头尾各 64KB 的采样哈希 —— 纯内容特征，与文件名无关，用于识别「同一个文件改了名」。 */
    val sampleHash: String? = null,

    /** 文件当前是否找不到（被删 / 移动 / 授权失效）。标记而不删数据。 */
    val missing: Boolean = false,

    /** 所属授权文件夹的 tree uri，用于按目录批量标记失效。 */
    val folderUri: String? = null,

    // ---------------------------------------------------------- 用户意图

    /** 用户手动改过标题，自动解析不得覆盖。 */
    val manualTitle: Boolean = false,

    /** 用户手动改过作者，自动解析不得覆盖。 */
    val manualAuthor: Boolean = false,

    // ---------------------------------------------------------- 扩展元数据

    /**
     * 系列名。
     *
     * 同一系列的多卷共用，用于把散落的单行本聚合起来看。
     * 不做自动归组 —— 那需要猜测，方案第四章明确禁止。
     */
    val series: String? = null,

    /** 卷号。仅作展示与排序，不参与任何自动逻辑。 */
    val volume: Int? = null,

    /** 用户备注。 */
    val note: String? = null,

    /** 评分 1..5；null 表示未评分。 */
    val rating: Int? = null
)

/** 标签。完全由用户自行创建。 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
    val createdAt: Long,

    /**
     * 用户拖动排序后的位次。
     *
     * 可空、无默认值 —— 带 `DEFAULT` 的 `ADD COLUMN` 会和 Room 编译期 schema
     * 对不上，运行时直接抛异常（v1→v2 踩过这个坑）。
     *
     * null 表示「用户还没手动排过」，此时按创建时间排在所有排过序的标签之后。
     */
    val sortOrder: Int? = null
)

/** 漫画 ↔ 标签 多对多关系。 */
@Entity(
    tableName = "comic_tags",
    primaryKeys = ["comicId", "tagId"],
    indices = [Index(value = ["tagId"]), Index(value = ["comicId"])]
)
data class ComicTagEntity(
    val comicId: Long,
    val tagId: Long,
    val createdAt: Long
)

/** 用户授权给 Gaze 的文件夹。 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val addedAt: Long,
    val lastScanAt: Long = 0,
    /** 该文件夹下最近一次扫描发现的漫画数量。 */
    val comicCount: Int = 0,

    /**
     * 「待重新授权」—— 这个文件夹来自一份恢复回来的备份，本机还没有真正拿到它的读权限。
     *
     * 恢复备份时**绝不能**把备份里记的 uri 直接当成已授权：SAF 的读权限是跟着
     * 「用户点过系统目录选择器」这件事走的，备份文件里存不下它。旧实现直接调
     * `folders.add(...)` 写库，界面上就出现一个「已授权」的文件夹，
     * 但每次扫描都读不到任何文件，用户完全不知道为什么。
     *
     * 可空、无默认值（Room 迁移的老规矩）：null 与 false 都表示真正授权，
     * 只有显式的 true 才是「等你重新授权」。扫描器与书架都不认它。
     */
    val pending: Boolean? = null
)

/**
 * 书架文件夹的自定义封面。
 *
 * 文件夹视图 / 标签视图里，每个「书架文件夹」默认拿组内**最新入库**那本的封面；
 * 用户长按可以指定成组内任意一本。这里存的就是这个覆盖关系。
 *
 * [key] 形如 `FOLDER:<tree uri>` 或 `TAG:<tagId>` —— 用一个字符串主键
 * 覆盖两种分组，省掉一张只有一列的判别表。
 */
@Entity(tableName = "shelf_covers")
data class ShelfCoverEntity(
    @PrimaryKey val key: String,
    val comicId: Long
)

/**
 * 书架文件夹（文件夹视图 / 标签视图里的一个格子）。
 *
 * [coverPath] / [coverTitle] 已经按「自定义封面 -> 组内最新」算好，
 * UI 不需要再自己判断该用哪一本的封面。
 */
data class ShelfGroup(
    val key: String,
    val title: String,
    val subtitle: String?,
    val coverPath: String?,
    val coverTitle: String,
    val comics: List<ComicEntity>
)

/** 标签 + 它下面有多少本漫画。 */
data class TagWithCount(
    val id: Long,
    val name: String,
    val color: Int,
    val createdAt: Long,
    val comicCount: Int
)

/** 媒体库容量统计。 */
data class LibraryStats(
    val totalCount: Int,
    val totalBytes: Long,
    val favoriteCount: Int,
    val unreadCount: Int,
    val finishedCount: Int
)

/**
 * 一次阅读会话（方案第二十五章 P2「阅读统计」）。
 *
 * 只在**离开阅读页**时落一条，记录这一段停留了多久、从第几页翻到第几页。
 *
 * 刻意不做「每 N 秒心跳」：那会把数据库写入变成高频操作，
 * 既费电也违背「轻量」原则，而且用户中途被杀进程时丢的也只是最后一段。
 */
@Entity(
    tableName = "reading_sessions",
    indices = [Index(value = ["comicId"]), Index(value = ["startedAt"])]
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comicId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val startPage: Int,
    val endPage: Int,
    /** 这次会话翻过的页数绝对值。落库时算好，省得聚合时再写表达式。 */
    val pagesRead: Int
)

/** 阅读统计汇总。 */
data class ReadingStats(
    val totalDurationMs: Long = 0,
    val totalSessions: Int = 0,
    val totalPagesRead: Int = 0,
    val booksRead: Int = 0,
    val todayDurationMs: Long = 0,
    val weekDurationMs: Long = 0,
    val finishedCount: Int = 0
)

/** 单本漫画的阅读时长排行项。 */
data class ComicReadingTime(
    val comicId: Long,
    val durationMs: Long
)
