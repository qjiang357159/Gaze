package com.gaze.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gaze.reader.data.db.TagWithCount
import com.gaze.reader.ui.theme.gazeColors

/** 标签面板的两种动作。 */
enum class TagPickMode(val label: String) {
    ADD("添加"),
    REMOVE("移除")
}

/**
 * 批量标签选择器（增删合一）。
 *
 * 用 [AlertDialog] 而不是底部弹层：对话框的窗口自带 `decorFitsSystemWindows`，
 * 键盘弹出时整个弹窗被抬高，底部的「新建标签」输入框始终可见；
 * 底部弹层会被键盘直接压住，各家 ROM 的补偿行为还不一致。
 *
 * 「添加 / 移除」做成面板内的切换而不是两个入口 —— 用户不必先想清楚
 * 自己是加还是删，打开看一眼再决定，少点一次。
 */
@Composable
fun GazeTagPicker(
    title: String,
    allTags: List<TagWithCount>,
    onDismiss: () -> Unit,
    onApply: (TagPickMode, Set<Long>) -> Unit,
    onCreate: (String, TagPickMode) -> Unit
) {
    var mode by remember { mutableStateOf(TagPickMode.ADD) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                // 添加 / 移除 切换
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagPickMode.entries.forEach { candidate ->
                        val isSelected = candidate == mode
                        val shape = RoundedCornerShape(2.dp)
                        Box(
                            Modifier
                                .clip(shape)
                                .background(if (isSelected) gazeColors.ink else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isSelected) gazeColors.ink else gazeColors.hairline,
                                    shape
                                )
                                .clickable {
                                    mode = candidate
                                    selected = emptySet()
                                }
                                .padding(horizontal = 14.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = candidate.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.background
                                } else {
                                    gazeColors.ink
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (allTags.isEmpty()) {
                    Text(
                        "还没有标签。在下面输入一个名字就能新建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = gazeColors.inkFaint
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        allTags.forEach { tag ->
                            val checked = tag.id in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (checked) selected - tag.id else selected + tag.id
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(tag.color))
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${tag.comicCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = gazeColors.inkFaint
                                )
                                Spacer(Modifier.width(10.dp))
                                if (checked) {
                                    Text("✓", color = gazeColors.accent, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("新建标签") },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row {
                if (newName.isNotBlank()) {
                    TextButton(onClick = {
                        onCreate(newName.trim(), mode)
                        newName = ""
                    }) { Text("新建") }
                }
                TextButton(
                    onClick = { onApply(mode, selected) },
                    enabled = selected.isNotEmpty()
                ) { Text("应用") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
