package com.gaze.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体层级。
 *
 * 文档第十九章要求「强调字体层级」「类似书籍目录的排版」。
 * 做法是**把层级差拉开**：标题用较粗的字重 + 稍大的字号 + 负字距（更紧凑、更像印刷品），
 * 正文与说明文字则明显更小、更淡。中间不要留太多「差不多大」的层级 ——
 * 那正是「看不出重点」的来源。
 */
val GazeTypography = Typography(
    // 页面主标题：书架、标签、设置
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp
    ),
    // 分区块标题
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    /**
     * 栏目小标题（设置页的「外观」「阅读」「备份」这类）。
     *
     * 刻意比 [labelSmall] 更重、更黑：`labelSmall` 是 10sp 的浅灰宽字距小字，
     * 用在「书籍目录」那种装饰性场合很好，但用来区分设置栏目完全不够 ——
     * 一屏七八个栏目扫下来分不出边界。这里用 13sp SemiBold + 正文墨色，
     * 配上图标，栏目边界一眼可见。
     */
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp
    ),
    // 漫画标题
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp
    ),
    // 正文
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    // 作者 / 元信息
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.1.sp
    ),
    // 全部大写的分区小标签（Editorial 风格的标志性元素）
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.6.sp
    )
)
