package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.model.AiCharacterAnalyze
import io.legado.app.model.AiCharacterAnalyze.CharacterAnalyzeResult
import io.legado.app.tts.voice.CharacterVoice
import io.legado.app.tts.voice.MultiVoiceManager

/**
 * 角色分析结果弹窗。
 *
 * 显示 AI 分析出的角色列表，支持：
 * 1. 查看角色详情（姓名、性别、年龄、别名、描述）
 * 2. 选择/取消选择角色
 * 3. 一键分配选中角色的 TTS 音色
 * 4. 单独编辑角色的音色绑定
 */
@Composable
fun CharacterAnalyzeResultDialog(
    results: List<CharacterAnalyzeResult>,
    onDismiss: () -> Unit,
    onAssign: (List<CharacterAnalyzeResult>) -> Unit
) {
    // 使用状态跟踪每个结果的选中状态
    val selectedStates = remember(results) {
        results.map { mutableStateOf(true) }.toMutableList()
    }
    val anySelected = selectedStates.any { it.value }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column {
                // 标题栏
                TopAppBar(
                    title = { Text("AI 角色分析结果") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                // 全选 / 全不选
                                val newVal = !anySelected
                                selectedStates.forEach { it.value = newVal }
                            }
                        ) {
                            Text(if (anySelected) "全不选" else "全选")
                        }
                    }
                )

                Divider()

                // 提示信息
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "共分析出 ${results.size} 个角色，已自动分配发音人，TTS朗读时将自动切换音色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                if (results.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.SentimentDissatisfied,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "未分析出任何角色",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "请尝试分析更多文本内容",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // 角色列表
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        itemsIndexed(results) { index, result ->
                            AnalyzeResultItem(
                                result = result,
                                selected = selectedStates[index].value,
                                onToggle = { selectedStates[index].value = !selectedStates[index].value },
                                onAssignOne = {
                                    onAssign(listOf(result))
                                }
                            )
                        }
                    }

                    Divider()

                    // 底部操作栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(end = 8.dp)) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                val selected = results.filterIndexed { index, _ ->
                                    selectedStates[index].value
                                }
                                onAssign(selected)
                            },
                            enabled = anySelected
                        ) {
                            Icon(Icons.Default.VolunteerActivism, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("分配音色 (${selectedStates.count { it.value }})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyzeResultItem(
    result: CharacterAnalyzeResult,
    selected: Boolean,
    onToggle: () -> Unit,
    onAssignOne: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.surfaceContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选中复选框
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() }
            )

            Spacer(Modifier.width(8.dp))

            // 角色信息
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = result.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
                    // 性别标签
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "${result.gender} · ${result.age}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )
                }

                if (result.aliases.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "别名：${result.aliases.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (result.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = result.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }

            // 单独分配按钮
            TextButton(onClick = onAssignOne) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("分配", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
