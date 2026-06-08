package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.tts.config.TtsGlobalConfig
import io.legado.app.tts.rule.RuleCategory
import io.legado.app.tts.rule.TtsProcessRule
import io.legado.app.tts.rule.TtsRuleChain

/**
 * TTS 规则链配置对话框（Compose 实现）。
 * 支持查看所有规则、启用/禁用、调整优先级。
 */
@Composable
fun TtsRuleConfigDialog(
    onDismiss: () -> Unit
) {
    val rules = remember { TtsRuleChain.getAllRules().toMutableStateList() }
    var showAddRegexDialog by remember { mutableStateOf(false) }

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
                    title = { Text("TTS 文本规则") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            showAddRegexDialog = true
                        }) {
                            Text("添加替换规则")
                        }
                    }
                )

                Divider()

                // 启用开关
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("启用文本预处理", style = MaterialTheme.typography.titleSmall)
                        Text("合成前自动清洁、过滤、转换文本", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = TtsGlobalConfig.ruleChainEnabled,
                        onCheckedChange = {
                            TtsGlobalConfig.ruleChainEnabled = it
                            if (it) TtsRuleChain.init() else TtsRuleChain.getAllRules().forEach { r -> r.enabled = false }
                        }
                    )
                }

                Divider()

                // 规则列表
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(rules) { index, rule ->
                        RuleItem(
                            rule = rule,
                            canToggle = TtsGlobalConfig.ruleChainEnabled,
                            onToggle = { enabled ->
                                rule.enabled = enabled
                                rules[index] = rule
                                TtsRuleChain.saveConfig()
                            },
                            onMoveUp = if (index > 0) {
                                {
                                    val item = rules.removeAt(index)
                                    rules.add(index - 1, item)
                                    rules.forEachIndexed { i, r -> r.priority = i * 10 }
                                    TtsRuleChain.saveConfig()
                                }
                            } else null,
                            onMoveDown = if (index < rules.lastIndex) {
                                {
                                    val item = rules.removeAt(index)
                                    rules.add(index + 1, item)
                                    rules.forEachIndexed { i, r -> r.priority = i * 10 }
                                    TtsRuleChain.saveConfig()
                                }
                            } else null
                        )
                    }
                }
            }
        }
    }

    // 添加正则替换规则弹窗
    if (showAddRegexDialog) {
        AddRegexRuleDialog(
            onDismiss = { showAddRegexDialog = false },
            onConfirm = { pattern, replacement ->
                TtsRuleChain.addRegexRule(pattern, replacement)
                TtsRuleChain.saveConfig()
                showAddRegexDialog = false
            }
        )
    }
}

@Composable
private fun RuleItem(
    rule: TtsProcessRule,
    canToggle: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled)
                MaterialTheme.colorScheme.surfaceContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(rule.category.displayName, style = MaterialTheme.typography.labelSmall) }
                    )
                }
                Text(
                    text = rule.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (onMoveUp != null) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "上移", modifier = Modifier.size(16.dp))
                }
            }
            if (onMoveDown != null) {
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "下移", modifier = Modifier.size(16.dp))
                }
            }

            Switch(
                checked = rule.enabled,
                onCheckedChange = if (canToggle) onToggle else {{}},
                enabled = canToggle
            )
        }
    }
}

@Composable
private fun AddRegexRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (pattern: String, replacement: String) -> Unit
) {
    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加正则替换") },
        text = {
            Column {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("正则匹配模式") },
                    placeholder = { Text("如：\\[.*?\\]") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("替换为") },
                    placeholder = { Text("留空表示删除匹配内容") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pattern, replacement) },
                enabled = pattern.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
