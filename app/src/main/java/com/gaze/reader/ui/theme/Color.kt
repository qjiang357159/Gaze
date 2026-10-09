package com.gaze.reader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Gaze 的配色。
 *
 * 视觉关键词：**纸张 / 墨迹 / 留白 / 书页 / 编辑排版 / 黑白漫画**。
 *
 * 每一套主题都是「一种纸 + 一种墨 + 一个强调色」的完整组合。强调色不再
 * 全局唯一 —— 既然允许用户挑主题，纸色与强调色就必须一起走，否则
 * 「青瓷纸 + 朱红点」会互相打架。但每套内部仍然只有一个强调色，
 * Editorial 的克制感不变。
 */

// ---------------------------------------------------------------- 宣纸（默认）

val Paper = Color(0xFFF5F2EC)
val PaperRaised = Color(0xFFFCFAF5)
val PaperSunken = Color(0xFFEBE7DE)

val Ink = Color(0xFF141414)
val InkSoft = Color(0xFF4C4A46)
val InkFaint = Color(0xFF8C8880)

val Hairline = Color(0xFFD9D4C8)
val HairlineStrong = Color(0xFFBDB7A8)

/** 宣纸的强调色：印刷朱红。 */
val Vermilion = Color(0xFFB4472F)
val VermilionSoft = Color(0xFFF0DAD3)

val InkDeep = Color(0xFF0D0D0D)
val InkRaised = Color(0xFF171717)
val InkSunken = Color(0xFF060606)

val PaperOnInk = Color(0xFFEDEAE3)
val PaperOnInkSoft = Color(0xFFA8A49C)
val PaperOnInkFaint = Color(0xFF6E6A63)

val HairlineDark = Color(0xFF2C2C2C)
val HairlineDarkStrong = Color(0xFF3E3E3E)

val VermilionOnInk = Color(0xFFD96A4F)

// ---------------------------------------------------------------- 米黄（护眼）

val CreamBg = Color(0xFFF7F0DF)
val CreamRaised = Color(0xFFFDF8EC)
val CreamSunken = Color(0xFFEDE3CC)
val CreamInk = Color(0xFF1A1610)
val CreamInkSoft = Color(0xFF514A3E)
val CreamInkFaint = Color(0xFF8E8776)
val CreamHairline = Color(0xFFDED3B8)
val CreamHairlineStrong = Color(0xFFC6B995)

/** 米黄的强调色：赭石。 */
val Ochre = Color(0xFF9A6B2F)
val OchreOnInk = Color(0xFFD9A45E)

val InkDeepWarm = Color(0xFF100E0A)
val InkRaisedWarm = Color(0xFF1A1712)

// ---------------------------------------------------------------- 灰蓝（技术）

val MistBg = Color(0xFFEEF1F5)
val MistRaised = Color(0xFFF7F9FC)
val MistSunken = Color(0xFFE2E7EE)
val MistInk = Color(0xFF14181D)
val MistInkSoft = Color(0xFF4A525C)
val MistInkFaint = Color(0xFF848D99)
val MistHairline = Color(0xFFD2D9E2)
val MistHairlineStrong = Color(0xFFB4BECB)

/** 灰蓝的强调色：靛蓝。 */
val Indigo = Color(0xFF3A5A8C)
val IndigoOnInk = Color(0xFF7FA3D6)

val InkDeepCool = Color(0xFF0A0D12)
val InkRaisedCool = Color(0xFF141920)

// ---------------------------------------------------------------- 青瓷（静）

val SageBg = Color(0xFFEEF2EC)
val SageRaised = Color(0xFFF7FAF5)
val SageSunken = Color(0xFFE2E8DF)
val SageInk = Color(0xFF151A13)
val SageInkSoft = Color(0xFF4A5346)
val SageInkFaint = Color(0xFF858E80)
val SageHairline = Color(0xFFD3DBCE)
val SageHairlineStrong = Color(0xFFB6C1AE)

/** 青瓷的强调色：墨绿。 */
val Moss = Color(0xFF3F6B4F)
val MossOnInk = Color(0xFF7FB894)

val InkDeepSage = Color(0xFF0B100C)
val InkRaisedSage = Color(0xFF151B16)

// ---------------------------------------------------------------- 藕粉（柔）

val RoseBg = Color(0xFFF8F0F1)
val RoseRaised = Color(0xFFFDF8F9)
val RoseSunken = Color(0xFFEEE2E4)
val RoseInk = Color(0xFF1C1417)
val RoseInkSoft = Color(0xFF54464B)
val RoseInkFaint = Color(0xFF918287)
val RoseHairline = Color(0xFFE0D2D6)
val RoseHairlineStrong = Color(0xFFC9B4BA)

/** 藕粉的强调色：玫。 */
val Rose = Color(0xFFA64B6B)
val RoseOnInk = Color(0xFFDC8CA8)

val InkDeepRose = Color(0xFF110C0E)
val InkRaisedRose = Color(0xFF1B1417)

// ---------------------------------------------------------------- 石墨（冷硬）

val GraphiteBg = Color(0xFFF0F0F1)
val GraphiteRaised = Color(0xFFF9F9FA)
val GraphiteSunken = Color(0xFFE4E4E6)
val GraphiteInk = Color(0xFF131314)
val GraphiteInkSoft = Color(0xFF4A4A4E)
val GraphiteInkFaint = Color(0xFF87878C)
val GraphiteHairline = Color(0xFFD5D5D8)
val GraphiteHairlineStrong = Color(0xFFB8B8BD)

/** 石墨的强调色：冷钢蓝。 */
val Steel = Color(0xFF4A5A6E)
val SteelOnInk = Color(0xFF8FA6C0)

val InkDeepGraphite = Color(0xFF0B0B0C)
val InkRaisedGraphite = Color(0xFF151517)

// ---------------------------------------------------------------- 紫檀（沉）

val PlumBg = Color(0xFFF6F1F6)
val PlumRaised = Color(0xFFFCF8FC)
val PlumSunken = Color(0xFFECE3ED)
val PlumInk = Color(0xFF1A1319)
val PlumInkSoft = Color(0xFF524552)
val PlumInkFaint = Color(0xFF8F828F)
val PlumHairline = Color(0xFFDED0E0)
val PlumHairlineStrong = Color(0xFFC5B2C8)

/** 紫檀的强调色：紫檀木。 */
val Plum = Color(0xFF8A3F70)
val PlumOnInk = Color(0xFFD48BC2)

val InkDeepPlum = Color(0xFF0F0A0F)
val InkRaisedPlum = Color(0xFF1A131B)

/** 阅读器专用背景（与系统主题解耦）。 */
val ReaderBlack = Color(0xFF000000)
val ReaderDarkGray = Color(0xFF1C1C1E)
val ReaderWhite = Color(0xFFFFFFFF)
