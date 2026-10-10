package com.gaze.reader.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 手绘图标集。
 *
 * 为什么不直接用 `Icons.Default.*`：`material-icons-core` 只有几十个基础图标，
 * 缺的那些属于 `material-icons-extended` —— 为了几个图标引入整包几千个矢量图，
 * 与文档第二十一章「不为功能丰富牺牲轻量性」直接冲突。
 *
 * 所以：**core 有的就用 core，core 没有的自己画**。这里画的都是 24×24 视口、
 * 1.7 描边、圆角接点 —— 与界面里的发丝线、弱圆角是同一套语言。
 */
object GazeIcons {

    private const val STAR_NAME = "GazeStar"

    /** 未收藏：只有一圈细描边，安静地待在角落里。 */
    val StarOutline: ImageVector by lazy { buildStar(filled = false) }

    /** 已收藏：实心，配合强调色使用。 */
    val StarFilled: ImageVector by lazy { buildStar(filled = true) }

    /** 历史记录：一只安静的时钟。 */
    val History: ImageVector by lazy {
        strokeIcon("GazeHistory") {
            moveTo(12f, 3f)
            arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 21f)
            arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 3f)
            close()
            moveTo(12f, 7.4f)
            lineTo(12f, 12.2f)
            lineTo(15.4f, 14.2f)
        }
    }

    /** 文件夹（书架文件夹视图）。 */
    val Folder: ImageVector by lazy {
        strokeIcon("GazeFolder") {
            moveTo(3f, 6.2f)
            lineTo(9.4f, 6.2f)
            lineTo(11.4f, 8.6f)
            lineTo(21f, 8.6f)
            lineTo(21f, 18.8f)
            lineTo(3f, 18.8f)
            close()
        }
    }

    /** 标签（书架标签视图）。 */
    val Tag: ImageVector by lazy {
        strokeIcon("GazeTag") {
            moveTo(3.4f, 11.2f)
            lineTo(11.2f, 3.4f)
            lineTo(20.6f, 3.4f)
            lineTo(20.6f, 12.8f)
            lineTo(12.8f, 20.6f)
            close()
            moveTo(16.4f, 6.2f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16.4f, y1 = 9.2f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16.4f, y1 = 6.2f)
            close()
        }
    }

    /** 亮度：一个太阳。 */
    val Brightness: ImageVector by lazy {
        strokeIcon("GazeBrightness") {
            moveTo(12f, 8.2f)
            arcTo(3.8f, 3.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 15.8f)
            arcTo(3.8f, 3.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 8.2f)
            close()
            // 八条射线
            ray(12f, 3.4f, 12f, 5.2f)
            ray(12f, 18.8f, 12f, 20.6f)
            ray(3.4f, 12f, 5.2f, 12f)
            ray(18.8f, 12f, 20.6f, 12f)
            ray(6.0f, 6.0f, 7.3f, 7.3f)
            ray(16.7f, 16.7f, 18.0f, 18.0f)
            ray(6.0f, 18.0f, 7.3f, 16.7f)
            ray(16.7f, 7.3f, 18.0f, 6.0f)
        }
    }

    /** 屏幕旋转。 */
    val Rotate: ImageVector by lazy {
        strokeIcon("GazeRotate") {
            moveTo(7.6f, 3.6f)
            lineTo(16.4f, 3.6f)
            lineTo(16.4f, 20.4f)
            lineTo(7.6f, 20.4f)
            close()
            moveTo(10.6f, 18.0f)
            lineTo(13.4f, 18.0f)
        }
    }

    /** 音量键翻页。 */
    val VolumeKeys: ImageVector by lazy {
        strokeIcon("GazeVolumeKeys") {
            // 机身
            moveTo(9.4f, 3.2f)
            lineTo(14.6f, 3.2f)
            lineTo(14.6f, 20.8f)
            lineTo(9.4f, 20.8f)
            close()
            // 上键
            moveTo(17.6f, 6.4f)
            lineTo(20.2f, 6.4f)
            // 下键
            moveTo(17.6f, 10.4f)
            lineTo(20.2f, 10.4f)
        }
    }

    /** 页码显示。 */
    val PageNumber: ImageVector by lazy {
        strokeIcon("GazePageNumber") {
            moveTo(4.6f, 4.2f)
            lineTo(19.4f, 4.2f)
            lineTo(19.4f, 19.8f)
            lineTo(4.6f, 19.8f)
            close()
            moveTo(8.2f, 9.4f)
            lineTo(15.8f, 9.4f)
            moveTo(8.2f, 13.2f)
            lineTo(13.4f, 13.2f)
        }
    }

    /** 网格排版（2 列 / 3 列共用，靠外边距区分）。 */
    fun grid(columns: Int): ImageVector = strokeIcon("GazeGrid$columns") {
        val gap = 1.6f
        val total = 20.4f - 3.6f
        val cell = (total - gap * (columns - 1)) / columns
        for (c in 0 until columns) {
            val x = 3.6f + c * (cell + gap)
            moveTo(x, 3.6f)
            lineTo(x + cell, 3.6f)
            lineTo(x + cell, 20.4f)
            lineTo(x, 20.4f)
            close()
        }
    }

    /** 列表排版。[large] 控制封面方块的宽窄。 */
    fun list(large: Boolean): ImageVector = strokeIcon("GazeList${if (large) "L" else "S"}") {
        val coverWidth = if (large) 8.4f else 5.6f
        for (row in 0 until 2) {
            val top = 5.0f + row * 7.6f
            moveTo(3.6f, top)
            lineTo(3.6f + coverWidth, top)
            lineTo(3.6f + coverWidth, top + 6.0f)
            lineTo(3.6f, top + 6.0f)
            close()
            val textLeft = 3.6f + coverWidth + 2.4f
            moveTo(textLeft, top + 1.0f)
            lineTo(20.4f, top + 1.0f)
            moveTo(textLeft, top + 3.6f)
            lineTo(17.4f, top + 3.6f)
        }
    }

    /** 详情：一张带折角的纸。 */
    val Detail: ImageVector by lazy {
        strokeIcon("GazeDetail") {
            moveTo(5.4f, 3.4f)
            lineTo(14.2f, 3.4f)
            lineTo(18.6f, 7.8f)
            lineTo(18.6f, 20.6f)
            lineTo(5.4f, 20.6f)
            close()
            moveTo(14.0f, 3.6f)
            lineTo(14.0f, 8.0f)
            lineTo(18.4f, 8.0f)
            moveTo(8.4f, 12.4f)
            lineTo(15.6f, 12.4f)
            moveTo(8.4f, 15.8f)
            lineTo(13.4f, 15.8f)
        }
    }

    /** 溢出菜单（三条横线，比 material 的圆点更贴 Editorial）。 */
    val Overflow: ImageVector by lazy {
        strokeIcon("GazeOverflow") {
            moveTo(4.6f, 7.0f)
            lineTo(19.4f, 7.0f)
            moveTo(4.6f, 12.0f)
            lineTo(19.4f, 12.0f)
            moveTo(4.6f, 17.0f)
            lineTo(19.4f, 17.0f)
        }
    }

    /** 亮度自适应 / 跟随系统。 */
    val Auto: ImageVector by lazy {
        strokeIcon("GazeAuto") {
            moveTo(12f, 8.6f)
            arcTo(3.4f, 3.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 15.4f)
            arcTo(3.4f, 3.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 8.6f)
            close()
            // 右半圆填充示意「自动」
            moveTo(12f, 5.4f)
            lineTo(12f, 18.6f)
        }
    }

    /** 调色板（自定义主题色）。 */
    val Palette: ImageVector by lazy {        strokeIcon("GazePalette") {
            moveTo(12f, 3.6f)
            arcTo(8.4f, 8.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 20.4f)
            arcTo(8.4f, 8.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 3.6f)
            close()
            moveTo(8.4f, 9.4f)
            arcTo(1.3f, 1.3f, 0f, false, true, 8.4f, 12.0f)
            arcTo(1.3f, 1.3f, 0f, false, true, 8.4f, 9.4f)
            close()
            moveTo(15.6f, 9.4f)
            arcTo(1.3f, 1.3f, 0f, false, true, 15.6f, 12.0f)
            arcTo(1.3f, 1.3f, 0f, false, true, 15.6f, 9.4f)
            close()
            moveTo(12f, 15.0f)
            arcTo(1.3f, 1.3f, 0f, false, true, 12f, 17.6f)
            arcTo(1.3f, 1.3f, 0f, false, true, 12f, 15.0f)
            close()
        }
    }

    /** 设置：一只齿轮。 */
    val Gear: ImageVector by lazy {
        strokeIcon("GazeGear") {
            moveTo(12f, 8.4f)
            arcTo(3.6f, 3.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 15.6f)
            arcTo(3.6f, 3.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 8.4f)
            close()
            // 八个齿：用短射线代替复杂的齿形，24px 下反而更清楚
            ray(12f, 2.6f, 12f, 5.0f)
            ray(12f, 19.0f, 12f, 21.4f)
            ray(2.6f, 12f, 5.0f, 12f)
            ray(19.0f, 12f, 21.4f, 12f)
            ray(5.4f, 5.4f, 7.0f, 7.0f)
            ray(17.0f, 17.0f, 18.6f, 18.6f)
            ray(5.4f, 18.6f, 7.0f, 17.0f)
            ray(17.0f, 7.0f, 18.6f, 5.4f)
        }
    }

    /** 阅读行为：一本摊开的书。 */
    val Book: ImageVector by lazy {
        strokeIcon("GazeBook") {
            moveTo(4.2f, 4.6f)
            lineTo(10.0f, 6.2f)
            lineTo(10.0f, 19.6f)
            lineTo(4.2f, 18.0f)
            close()
            moveTo(20.0f, 4.6f)
            lineTo(14.2f, 6.2f)
            lineTo(14.2f, 19.6f)
            lineTo(20.0f, 18.0f)
            close()
            moveTo(12.0f, 6.2f)
            lineTo(12.0f, 19.6f)
        }
    }

    /** 备份与恢复：一只收纳盒。 */
    val Archive: ImageVector by lazy {
        strokeIcon("GazeArchive") {
            moveTo(3.6f, 4.4f)
            lineTo(20.4f, 4.4f)
            lineTo(20.4f, 8.4f)
            lineTo(3.6f, 8.4f)
            close()
            moveTo(5.2f, 8.4f)
            lineTo(5.2f, 19.6f)
            lineTo(18.8f, 19.6f)
            lineTo(18.8f, 8.4f)
            moveTo(9.8f, 12.2f)
            lineTo(14.2f, 12.2f)
        }
    }

    /** 文件名解析：几行文字。 */
    val TextLines: ImageVector by lazy {
        strokeIcon("GazeTextLines") {
            moveTo(4.4f, 6.4f)
            lineTo(19.6f, 6.4f)
            moveTo(4.4f, 11.0f)
            lineTo(15.6f, 11.0f)
            moveTo(4.4f, 15.6f)
            lineTo(19.6f, 15.6f)
            moveTo(4.4f, 20.2f)
            lineTo(12.0f, 20.2f)
        }
    }

    /** 目录：条目按层级缩进，像书里的目录页。 */
    val Toc: ImageVector by lazy {
        strokeIcon("GazeToc") {
            moveTo(4.4f, 5.6f)
            lineTo(12.6f, 5.6f)
            moveTo(8.0f, 10.0f)
            lineTo(19.6f, 10.0f)
            moveTo(8.0f, 14.4f)
            lineTo(19.6f, 14.4f)
            moveTo(4.4f, 18.8f)
            lineTo(12.6f, 18.8f)
        }
    }

    /**
     * 书架视图「全部」：三本高低不齐的书立在搁板上。
     *
     * 刻意**不用网格图标** —— 顶栏上「排版」那颗按钮可能正好就是 2 列 / 3 列网格，
     * 两个挨在一起、长得一样，用户根本分不出哪个是哪个。
     */
    val Shelf: ImageVector by lazy {
        strokeIcon("GazeShelf") {
            moveTo(4.2f, 6.0f)
            lineTo(7.4f, 6.0f)
            lineTo(7.4f, 16.8f)
            lineTo(4.2f, 16.8f)
            close()
            moveTo(9.0f, 3.8f)
            lineTo(12.2f, 3.8f)
            lineTo(12.2f, 16.8f)
            lineTo(9.0f, 16.8f)
            close()
            moveTo(13.8f, 7.2f)
            lineTo(17.0f, 7.2f)
            lineTo(17.0f, 16.8f)
            lineTo(13.8f, 16.8f)
            close()
            moveTo(3.2f, 19.4f)
            lineTo(20.8f, 19.4f)
        }
    }

    /** 返回顶部：一支向上的箭头 + 下面一条横线（「顶」）。 */
    val ToTop: ImageVector by lazy {
        strokeIcon("GazeToTop") {
            moveTo(12f, 5.2f)
            lineTo(12f, 18.4f)
            moveTo(6.6f, 10.6f)
            lineTo(12f, 5.2f)
            lineTo(17.4f, 10.6f)
        }
    }

    /** 关于：一个信息圆。 */
    val Info: ImageVector by lazy {
        strokeIcon("GazeInfo") {
            moveTo(12f, 3.4f)
            arcTo(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 20.6f)
            arcTo(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 3.4f)
            close()
            moveTo(12f, 10.8f)
            lineTo(12f, 16.6f)
            moveTo(12f, 7.6f)
            lineTo(12f, 7.9f)
        }
    }

    /**
     * 帮助：一个问号圆。
     *
     * 与 [Info] 是同一套圆环，只把里面的「i」换成「?」——
     * 两者可能出现在同一屏，圆环一致才像一对。
     */
    val Help: ImageVector by lazy {
        strokeIcon("GazeHelp") {
            moveTo(12f, 3.4f)
            arcTo(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 20.6f)
            arcTo(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 3.4f)
            close()
            // 问号的钩：上半圆 + 收向中间
            moveTo(9.7f, 9.9f)
            arcTo(2.3f, 2.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 14.3f, y1 = 9.9f)
            lineTo(12f, 13.8f)
            // 下面的一点
            moveTo(12f, 17.2f)
            lineTo(12f, 17.5f)
        }
    }

    // ------------------------------------------------------------ 内部

    private fun PathBuilder.ray(x0: Float, y0: Float, x1: Float, y1: Float) {
        moveTo(x0, y0)
        lineTo(x1, y1)
    }

    private fun strokeIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block
            )
        }.build()

    private fun buildStar(filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = if (filled) "${STAR_NAME}Fill" else STAR_NAME,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12.00f, 3.40f)
                lineTo(14.23f, 8.93f)
                lineTo(20.18f, 9.34f)
                lineTo(15.61f, 13.17f)
                lineTo(17.06f, 18.96f)
                lineTo(12.00f, 15.80f)
                lineTo(6.94f, 18.96f)
                lineTo(8.39f, 13.17f)
                lineTo(3.82f, 9.34f)
                lineTo(9.77f, 8.93f)
                close()
            }
        }.build()
}
