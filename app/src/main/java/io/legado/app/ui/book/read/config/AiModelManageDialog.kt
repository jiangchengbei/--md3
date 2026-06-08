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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.model.AiCharacterAnalyze
import io.legado.app.model.AiCharacterAnalyze.ModelProfile

/**
 * AI 模型管理对话框。
 * 支持添加、编辑、删除、选择 AI 模型配置。
 */
@Composable
fun AiModelManageDialog(
    onDismiss: () -> Unit
) {
    val profiles = remember {
        AiCharacterAnalyze.getModelProfiles().toMutableStateList()
    }
    val selectedId = remember { mutableStateOf(AiCharacterAnalyze.getSelectedModelProfileId()) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ModelProfile?>(null) }

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
                    title = { Text("AI 模型管理") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            editingProfile = ModelProfile(
                                id = "model_${System.currentTimeMillis()}",
                                name = "",
                                apiUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                                apiKey = "",
                                model = "glm-4-flash"
                            )
                            showAddEditDialog = true
                        }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("添加模型")
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
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "支持 OpenAI 兼容接口的模型（如智谱GLM、DeepSeek、通义千问等）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (profiles.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "还没有配置 AI 模型",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "点击右上角添加模型，用于角色智能分析",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        itemsIndexed(profiles) { index, profile ->
                            ModelProfileItem(
                                profile = profile,
                                isSelected = profile.id == selectedId.value,
                                onSelect = {
                                    selectedId.value = profile.id
                                    AiCharacterAnalyze.setSelectedModelProfileId(profile.id)
                                },
                                onEdit = {
                                    editingProfile = profile
                                    showAddEditDialog = true
                                },
                                onDelete = {
                                    AiCharacterAnalyze.deleteModelProfile(profile.id)
                                    if (selectedId.value == profile.id) {
                                        selectedId.value = null
                                        AiCharacterAnalyze.setSelectedModelProfileId(null)
                                    }
                                    profiles.removeAt(index)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 添加/编辑模型弹窗
    if (showAddEditDialog && editingProfile != null) {
        ModelEditDialog(
            initial = editingProfile!!,
            onDismiss = {
                showAddEditDialog = false
                editingProfile = null
            },
            onSave = { profile ->
                AiCharacterAnalyze.saveModelProfile(profile)
                // 如果这是唯一的模型，自动选中
                val updated = AiCharacterAnalyze.getModelProfiles()
                profiles.clear()
                profiles.addAll(updated)
                if (updated.size == 1) {
                    selectedId.value = profile.id
                    AiCharacterAnalyze.setSelectedModelProfileId(profile.id)
                }
                showAddEditDialog = false
                editingProfile = null
            }
        )
    }
}

@Composable
private fun ModelProfileItem(
    profile: ModelProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainer
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
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
                        text = profile.name.ifBlank { "未命名" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (isSelected) {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("使用中", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${profile.model} | ${profile.apiUrl.take(50)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelEditDialog(
    initial: ModelProfile,
    onDismiss: () -> Unit,
    onSave: (ModelProfile) -> Unit
) {
    val isNew = initial.name.isBlank()
    var name by remember { mutableStateOf(initial.name) }
    var apiUrl by remember { mutableStateOf(initial.apiUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var temperature by remember { mutableStateOf(initial.temperature.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加 AI 模型" else "编辑 AI 模型") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("配置名称") },
                    placeholder = { Text("如：智谱GLM-4") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiUrl,
                    onValueChange = { apiUrl = it },
                    label = { Text("API 地址") },
                    placeholder = { Text("https://open.bigmodel.cn/api/paas/v4/chat/completions") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API 密钥") },
                    placeholder = { Text("输入 API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名称") },
                    placeholder = { Text("如：glm-4-flash, deepseek-chat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text("温度 (0.0-2.0)") },
                    placeholder = { Text("0.7") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val profile = initial.copy(
                        name = name.ifBlank { "未命名" },
                        apiUrl = apiUrl,
                        apiKey = apiKey,
                        model = model,
                        temperature = temperature.toFloatOrNull() ?: 0.7f
                    )
                    onSave(profile)
                },
                enabled = apiUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
