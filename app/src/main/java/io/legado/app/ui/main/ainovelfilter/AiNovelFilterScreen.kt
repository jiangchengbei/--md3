package io.legado.app.ui.main.ainovelfilter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import org.koin.androidx.compose.koinViewModel

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun AiNovelFilterScreen(
    onBack: () -> Unit,
    onBookClick: (Book) -> Unit,
    viewModel: AiNovelFilterViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.ai_novel_filter),
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
                actions = {
                    IconButton(onClick = { viewModel.onIntent(AiNovelFilterIntent.ToggleConfig) }) {
                        Icon(
                            imageVector = if (uiState.showConfig) Icons.Default.ExpandLess else Icons.Default.Settings,
                            contentDescription = "模型设置"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding()
                )
        ) {
            // ===== AI Model Config Panel (collapsible) =====
            AnimatedVisibility(visible = uiState.showConfig) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.ai_model_config),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Switch(
                                checked = uiState.config.enabled,
                                onCheckedChange = {
                                    viewModel.onIntent(
                                        AiNovelFilterIntent.UpdateConfig(
                                            uiState.config.copy(enabled = it)
                                        )
                                    )
                                }
                            )
                        }

                        OutlinedTextField(
                            value = uiState.config.apiUrl,
                            onValueChange = {
                                viewModel.onIntent(
                                    AiNovelFilterIntent.UpdateConfig(
                                        uiState.config.copy(apiUrl = it)
                                    )
                                )
                            },
                            label = { Text(stringResource(R.string.ai_model_api_url), style = MaterialTheme.typography.bodySmall) },
                            placeholder = { Text("https://.../v1/chat/completions", style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = uiState.config.enabled,
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        OutlinedTextField(
                            value = uiState.config.apiKey,
                            onValueChange = {
                                viewModel.onIntent(
                                    AiNovelFilterIntent.UpdateConfig(
                                        uiState.config.copy(apiKey = it)
                                    )
                                )
                            },
                            label = { Text(stringResource(R.string.ai_model_api_key), style = MaterialTheme.typography.bodySmall) },
                            placeholder = { Text("sk-...", style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = uiState.config.enabled,
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        OutlinedTextField(
                            value = uiState.config.model,
                            onValueChange = {
                                viewModel.onIntent(
                                    AiNovelFilterIntent.UpdateConfig(
                                        uiState.config.copy(model = it)
                                    )
                                )
                            },
                            label = { Text(stringResource(R.string.ai_model_name), style = MaterialTheme.typography.bodySmall) },
                            placeholder = { Text("gpt-4o-mini", style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = uiState.config.enabled,
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        // ===== AI 提示词配置 =====
                        Text(
                            text = stringResource(R.string.ai_model_prompt_config),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        OutlinedTextField(
                            value = uiState.config.systemPrompt,
                            onValueChange = {
                                viewModel.onIntent(
                                    AiNovelFilterIntent.UpdateConfig(
                                        uiState.config.copy(systemPrompt = it)
                                    )
                                )
                            },
                            label = { Text(stringResource(R.string.ai_model_system_prompt), style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                            maxLines = 3,
                            enabled = uiState.config.enabled,
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        OutlinedTextField(
                            value = uiState.config.userPromptTemplate,
                            onValueChange = {
                                viewModel.onIntent(
                                    AiNovelFilterIntent.UpdateConfig(
                                        uiState.config.copy(userPromptTemplate = it)
                                    )
                                )
                            },
                            label = { Text(stringResource(R.string.ai_model_user_prompt_template), style = MaterialTheme.typography.bodySmall) },
                            supportingText = {
                                Text(
                                    text = stringResource(R.string.ai_model_prompt_template_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            maxLines = 5,
                            enabled = uiState.config.enabled,
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        // ===== 测试连接按钮 =====
                        OutlinedButton(
                            onClick = { viewModel.onIntent(AiNovelFilterIntent.TestConnection) },
                            enabled = uiState.config.enabled && !uiState.isTestingConnection,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.NetworkCheck,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (uiState.isTestingConnection) stringResource(R.string.ai_model_testing)
                                else stringResource(R.string.ai_model_test_connection),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        // ===== 测试结果显示 =====
                        AnimatedVisibility(visible = uiState.testResultMessage.isNotEmpty()) {
                            Text(
                                text = uiState.testResultMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.testResultMessage.startsWith("✓"))
                                    MaterialTheme.colorScheme.primary
                                else if (uiState.testResultMessage.startsWith("✗"))
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (uiState.testResultMessage.startsWith("✓")) FontWeight.Medium else FontWeight.Normal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            )
                        }

                        FilledTonalButton(
                            onClick = { viewModel.onIntent(AiNovelFilterIntent.SaveConfig) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.save_config), style = MaterialTheme.typography.labelMedium)
                        }

                        if (!uiState.config.enabled) {
                            Text(
                                text = stringResource(R.string.ai_model_disabled_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ===== Search input area (compact) =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = uiState.inputText,
                    onValueChange = { viewModel.onIntent(AiNovelFilterIntent.UpdateInput(it)) },
                    placeholder = {
                        Text(
                            stringResource(R.string.ai_novel_filter_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !uiState.isAnalyzing,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (uiState.inputText.isNotBlank()) {
                                viewModel.onIntent(AiNovelFilterIntent.Analyze)
                            }
                        }
                    ),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                // ===== Source group selector =====
                SourceGroupSelector(
                    selectedGroupId = uiState.sourceGroupId,
                    groups = uiState.availableGroups,
                    enabled = !uiState.isAnalyzing,
                    onGroupSelected = { viewModel.onIntent(AiNovelFilterIntent.SelectSourceGroup(it)) }
                )

                FilledTonalButton(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.onIntent(AiNovelFilterIntent.Analyze)
                    },
                    enabled = uiState.inputText.isNotBlank() && !uiState.isAnalyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (uiState.config.enabled) stringResource(R.string.ai_novel_filter_analyze_ai)
                        else stringResource(R.string.ai_novel_filter_analyze),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // ===== Loading indicator =====
            AnimatedVisibility(visible = uiState.isAnalyzing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                )
            }

            // ===== Suggested chips (compact horizontal scroll) =====
            AnimatedVisibility(
                visible = uiState.chips.isNotEmpty() && !uiState.isAnalyzing,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.suggested_genres),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        uiState.chips.forEach { chip ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    viewModel.onIntent(AiNovelFilterIntent.UpdateInput(chip))
                                    viewModel.onIntent(AiNovelFilterIntent.Analyze)
                                },
                                label = {
                                    Text(
                                        chip,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                modifier = Modifier.height(32.dp)
                            )
                        }
                    }
                }
            }

            // ===== Result message =====
            AnimatedVisibility(
                visible = uiState.resultMessage.isNotEmpty() && !uiState.isAnalyzing
            ) {
                Text(
                    text = uiState.resultMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // ===== Add to group (shown when results are available) =====
            AnimatedVisibility(
                visible = uiState.books.isNotEmpty() && !uiState.isAnalyzing
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TargetGroupSelector(
                            selectedGroupId = uiState.targetGroupId,
                            groups = uiState.availableGroups,
                            enabled = !uiState.isAddingToGroup,
                            onGroupSelected = { viewModel.onIntent(AiNovelFilterIntent.SelectTargetGroup(it)) }
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.onIntent(AiNovelFilterIntent.AddToGroup) },
                        enabled = uiState.targetGroupId != null && !uiState.isAddingToGroup,
                    ) {
                        Icon(
                            Icons.Default.PlaylistAdd,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (uiState.isAddingToGroup) "添加中…" else "加入分组",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))

            // ===== Book results (takes all remaining space) =====
            if (uiState.books.isEmpty() && !uiState.isAnalyzing && uiState.resultMessage.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = "输入小说类型开始筛选",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    items(
                        items = uiState.books,
                        key = { it.bookUrl }
                    ) { book ->
                        AiNovelBookItem(
                            book = book,
                            onClick = { onBookClick(book) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AiNovelBookItem(
    book: Book,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = book.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (book.author.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!book.kind.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Category,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = book.kind ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (!book.intro.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = book.intro ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceGroupSelector(
    selectedGroupId: Long?,
    groups: List<BookGroup>,
    enabled: Boolean,
    onGroupSelected: (Long?) -> Unit,
) {
    val expanded = remember { mutableStateOf(false) }
    val selectedName = when (selectedGroupId) {
        null -> "全部书籍"
        else -> groups.find { it.groupId == selectedGroupId }?.groupName ?: "全部书籍"
    }

    ExposedDropdownMenuBox(
        expanded = expanded.value,
        onExpandedChange = { if (enabled) expanded.value = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            leadingIcon = {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded.value)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(12.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded.value,
            onDismissRequest = { expanded.value = false },
        ) {
            // "All Books" option
            DropdownMenuItem(
                text = {
                    Text(
                        "全部书籍",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (selectedGroupId == null) FontWeight.Medium else FontWeight.Normal,
                        color = if (selectedGroupId == null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    onGroupSelected(null)
                    expanded.value = false
                },
            )
            groups.forEach { group ->
                DropdownMenuItem(
                    text = {
                        Text(
                            group.groupName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (selectedGroupId == group.groupId) FontWeight.Medium else FontWeight.Normal,
                            color = if (selectedGroupId == group.groupId) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onGroupSelected(group.groupId)
                        expanded.value = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetGroupSelector(
    selectedGroupId: Long?,
    groups: List<BookGroup>,
    enabled: Boolean,
    onGroupSelected: (Long?) -> Unit,
) {
    val expanded = remember { mutableStateOf(false) }
    val selectedName = when (selectedGroupId) {
        null -> "选择分组"
        else -> groups.find { it.groupId == selectedGroupId }?.groupName ?: "选择分组"
    }

    ExposedDropdownMenuBox(
        expanded = expanded.value,
        onExpandedChange = { if (enabled) expanded.value = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(12.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded.value,
            onDismissRequest = { expanded.value = false },
        ) {
            groups.forEach { group ->
                DropdownMenuItem(
                    text = {
                        Text(
                            group.groupName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (selectedGroupId == group.groupId) FontWeight.Medium else FontWeight.Normal,
                            color = if (selectedGroupId == group.groupId) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onGroupSelected(group.groupId)
                        expanded.value = false
                    },
                )
            }
        }
    }
}
