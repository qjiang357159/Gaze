package com.gaze.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gaze.reader.core.Formatters
import com.gaze.reader.data.repository.StatsData
import com.gaze.reader.data.repository.TopComic
import com.gaze.reader.ui.components.BackToTopButton
import com.gaze.reader.ui.components.ComicCover
import com.gaze.reader.ui.components.GazeIcons
import com.gaze.reader.ui.components.Hairline
import com.gaze.reader.ui.components.ScrollStateScrollbar
import com.gaze.reader.ui.components.SectionLabel
import com.gaze.reader.ui.components.ThinProgress
import com.gaze.reader.ui.components.scrolledBeyond
import com.gaze.reader.ui.theme.gazeColors
import kotlinx.coroutines.launch

/**
 * 阅读统计页（方案第二十五章 P2）。
 *
 * 放在设置页的二级入口，不占底部导航 —— 底部只保留书架 / 标签 / 设置，
 * 这是之前定下的克制原则，统计不值得再占一个 tab。
 */
@Composable
fun StatsScreen(
    data: StatsData,
    message: String?,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onConsumeMessage: () -> Unit
) {
    val colors = gazeColors
    var confirmClear by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val stats = data.stats

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------- 顶栏
        Row(
            Modifier.fillMaxWidth().padding(end = 20.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "阅读统计",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
        }
        Hairline()

        message?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onConsumeMessage) { Text("知道了") }
            }
        }

        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                // ------------------------------------------------ 累计时长
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    SectionLabel("累计阅读")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = Formatters.duration(stats.totalDurationMs),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SpanCard(label = "今天", value = Formatters.duration(stats.todayDurationMs), modifier = Modifier.weight(1f))
                        SpanCard(label = "本周", value = Formatters.duration(stats.weekDurationMs), modifier = Modifier.weight(1f))
                    }
                }

                // ------------------------------------------------ 明细
                StatsSection("明细", icon = GazeIcons.TextLines) {
                    StatsRow("翻页总数", "${stats.totalPagesRead} 页")
                    StatsRow("读过的漫画", "${stats.booksRead} 本")
                    StatsRow("已完成", "${stats.finishedCount} 本")
                    StatsRow("阅读会话", "${stats.totalSessions} 次")
                }

                // ------------------------------------------------ 排行
                StatsSection("阅读时长排行", icon = GazeIcons.History) {
                    if (data.topComics.isEmpty()) {
                        Text(
                            text = "还没有统计数据。开始阅读后，这里会按停留时长排出最常读的漫画。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkFaint,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    } else {
                        val max = data.topComics.maxOf { it.durationMs }.coerceAtLeast(1L)
                        data.topComics.forEach { item ->
                            TopComicRow(item = item, fraction = item.durationMs.toFloat() / max)
                        }
                    }
                }

                // ------------------------------------------------ 维护
                StatsSection("维护", icon = GazeIcons.Archive) {
                    ActionRow(
                        label = "清空统计数据",
                        subtitle = "只删除阅读时长记录，不影响阅读进度、标签与收藏"
                    ) { confirmClear = true }
                }

                Spacer(Modifier.height(40.dp))
            }
            ScrollStateScrollbar(
                state = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
            )
            BackToTopButton(
                state = scrollState,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空阅读统计？") },
            text = {
                Text(
                    "将删除全部阅读时长与会话记录。\n" +
                        "阅读进度、标签、收藏都不会受影响。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("清空", color = gazeColors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

// ==================================================================== 组件

@Composable
private fun SpanCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = gazeColors.inkFaint)
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TopComicRow(item: TopComic, fraction: Float) {
    val colors = gazeColors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ComicCover(
                coverPath = item.comic.coverPath,
                title = item.comic.title,
                modifier = Modifier.width(32.dp).height(45.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.comic.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.comic.author.isNullOrBlank()) {
                    Text(
                        text = item.comic.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = Formatters.duration(item.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint
            )
        }
        Spacer(Modifier.height(6.dp))
        ThinProgress(progress = fraction, modifier = Modifier.fillMaxWidth(0.72f))
    }
}

@Composable
private fun StatsSection(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    val colors = gazeColors
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(colors.hairline))
        }
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = gazeColors.inkFaint,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun ActionRow(label: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = gazeColors.inkFaint)
        }
    }
}
