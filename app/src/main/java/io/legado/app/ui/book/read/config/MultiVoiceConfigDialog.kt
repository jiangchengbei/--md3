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
import io.legado.app.tts.config.TtsGlobalConfig
import io.legado.app.tts.voice.CharacterVoice
import io.legado.app.tts.voice.MultiVoiceManager

/**
 * 多人朗读配置对话框（Compose 实现）。
 * 管理角色-音色绑定，支持添加/删除/编辑角色。
 */
@Composable
fun MultiVoiceConfigDialog(
    onDismiss: () -> Unit
) {
    val characters = remember {
        MultiVoiceManager.getCharacters().toMutableStateList()
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var editCharacter by remember { mutableStateOf<CharacterVoice?>(null) }

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
                TopAppBar(
                    title = { Text("多人朗读") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        TextButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("添加角色")
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
                        Text("启用多人朗读", style = MaterialTheme.typography.titleSmall)
                        Text("自动识别对话并使用不同音色朗读", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = MultiVoiceManager.enabled,
                        onCheckedChange = {
                            TtsGlobalConfig.multiVoiceEnabled = it
                            MultiVoiceManager.enabled = it
                        }
                    )
                }

                Divider()

                if (characters.isEmpty() && !showAddDialog && editCharacter == null) {
                    // 空状态
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "还没有角色配置",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "点击右上角添加角色，设置对话匹配规则和音色",
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
                        itemsIndexed(characters) { index, cv ->
                            CharacterItem(
                                character = cv,
                                onToggle = { enabled ->
                                    cv.enabled.let {
                                        val updated = cv.copy(enabled = enabled)
                                        MultiVoiceManager.setCharacter(updated)
                                        // 自动设置旁白
                                        if (updated.isNarrator) {
                                            val list = MultiVoiceManager.getCharacters()
                                            characters.clear()
                                            characters.addAll(list)
                                        } else {
                                            characters[index] = updated
                                        }
                                    }
                                },
                                onEdit = {
                                    editCharacter = cv
                                },
                                onDelete = {
                                    MultiVoiceManager.removeCharacter(cv.id)
                                    characters.removeAt(index)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 添加/编辑角色弹窗
    if (showAddDialog || editCharacter != null) {
        CharacterEditDialog(
            initial = editCharacter,
            onDismiss = {
                showAddDialog = false
                editCharacter = null
            },
            onSave = { cv ->
                MultiVoiceManager.setCharacter(cv)
                val list = MultiVoiceManager.getCharacters()
                characters.clear()
                characters.addAll(list)
                showAddDialog = false
                editCharacter = null
            }
        )
    }
}

@Composable
private fun CharacterItem(
    character: CharacterVoice,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(
            containerColor = if (character.enabled)
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
                        text = character.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (character.isNarrator) {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("旁白", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                if (character.patterns.isNotEmpty()) {
                    Text(
                        text = "匹配: ${character.patterns.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "引擎: ${character.engineId} | 音色: ${character.voiceId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
            Switch(
                checked = character.enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun CharacterEditDialog(
    initial: CharacterVoice?,
    onDismiss: () -> Unit,
    onSave: (CharacterVoice) -> Unit
) {
    val isNew = initial == null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var patternsText by remember { mutableStateOf(initial?.patterns?.joinToString(", ") ?: "") }
    var voiceId by remember { mutableStateOf(initial?.voiceId ?: "default") }
    var engineId by remember { mutableStateOf(initial?.engineId ?: "system") }
    var speed by remember { mutableStateOf((initial?.speed ?: 1.0f).toString()) }
    var isNarrator by remember { mutableStateOf(initial?.isNarrator ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加角色" else "编辑角色") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("角色名称") },
                    placeholder = { Text("如：主角、师父、对话") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = patternsText,
                    onValueChange = { patternsText = it },
                    label = { Text("匹配规则（逗号分隔）") },
                    placeholder = { Text("如：说道,问道,怒道") },
                    singleLine = false,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = voiceId,
                    onValueChange = { voiceId = it },
                    label = { Text("音色 ID") },
                    placeholder = { Text("如：default, xiaoxiao") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = speed,
                    onValueChange = { speed = it },
                    label = { Text("语速倍率") },
                    placeholder = { Text("1.0") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isNarrator,
                        onCheckedChange = { isNarrator = it }
                    )
                    Text("设为旁白（只允许一个旁白角色）")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cv = CharacterVoice(
                        id = initial?.id ?: "char_${System.currentTimeMillis()}",
                        name = name.ifBlank { "未命名" },
                        patterns = patternsText.split(",", "，").map { it.trim() }.filter { it.isNotBlank() },
                        voiceId = voiceId.ifBlank { "default" },
                        engineId = engineId,
                        speed = speed.toFloatOrNull() ?: 1.0f,
                        isNarrator = isNarrator
                    )
                    onSave(cv)
                },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
