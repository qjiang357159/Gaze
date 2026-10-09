package com.gaze.reader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gaze.reader.core.Formatters
import com.gaze.reader.data.db.ComicEntity
import com.gaze.reader.ui.components.BackToTopButton
import com.gaze.reader.ui.components.ComicCover
import com.gaze.reader.ui.components.EmptyState
import com.gaze.reader.ui.components.Hairline
import com.gaze.reader.ui.components.LazyListScrollbar
import com.gaze.reader.ui.components.SectionLabel
import com.gaze.reader.ui.components.ThinProgress
import com.gaze.reader.ui.components.combinedClickableCompat
import com.gaze.reader.ui.theme.gazeColors

/**
 * 阅读历史。
 *
 * 独立成页而不是塞进书架的筛选条：历史是「时间轴」，书架是「收藏夹」，
 * 两者的排序依据根本不同，混在一起谁都不好用。
 */
@Composable
fun HistoryScreen(
    comics: List<ComicEntity>,
    onBack: () -> Unit,
    onOpenComic: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    val colors = gazeColors
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 20.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "阅读历史",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (comics.isEmpty()) "还没有记录" else "${comics.size} 本",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint
                )
            }
        }
        Hairline()

        if (comics.isEmpty()) {
            EmptyState(
                title = "还没有阅读记录",
                message = "读过任何一本之后，这里会按时间倒序列出它们。"
            )
            return@Column
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics, key = { it.id }) { comic ->
                    HistoryRow(
                        comic = comic,
                        onClick = { onOpenComic(comic.id) },
                        onLongClick = { onOpenDetail(comic.id) }
                    )
                }
            }
            LazyListScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
            )
            BackToTopButton(
                state = listState,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
            )
        }
    }
}

@Composable
private fun HistoryRow(
    comic: ComicEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = gazeColors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ComicCover(
                coverPath = comic.coverPath,
                title = comic.title,
                modifier = Modifier.width(44.dp).height(60.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = comic.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(Formatters.relative(comic.lastReadTime))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "第 ${comic.currentPage + 1} / ${comic.pageCount} 页",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkFaint
                    )
                }
                Spacer(Modifier.height(7.dp))
                ThinProgress(progress = comic.progress)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = Formatters.percent(comic.progress),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint
            )
        }
        Hairline(Modifier.padding(start = 20.dp))
    }
}
