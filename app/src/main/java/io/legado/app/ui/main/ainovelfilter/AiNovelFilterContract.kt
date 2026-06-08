package io.legado.app.ui.main.ainovelfilter

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import kotlinx.collections.immutable.ImmutableList

data class AiNovelFilterUiState(
    val inputText: String = "",
    val isAnalyzing: Boolean = false,
    val books: ImmutableList<Book> = kotlinx.collections.immutable.persistentListOf(),
    val resultMessage: String = "",
    val chips: ImmutableList<String> = kotlinx.collections.immutable.persistentListOf(),
    val showConfig: Boolean = false,
    val config: AiModelConfig = AiModelConfig(),
    val isTestingConnection: Boolean = false,
    val testResultMessage: String = "",
    val sourceGroupId: Long? = null,
    val targetGroupId: Long? = null,
    val availableGroups: ImmutableList<BookGroup> = kotlinx.collections.immutable.persistentListOf(),
    val isAddingToGroup: Boolean = false,
)

data class AiModelConfig(
    val enabled: Boolean = false,
    val apiUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "你是一个专业的小说推荐助手，只返回编号列表。",
    val userPromptTemplate: String = "用户想找「{input}」类型的小说。\n以下是本地书库中的小说列表，请根据用户描述，返回最匹配的 5-10 本书的编号（仅返回编号列表，用逗号分隔）。\n如果都不匹配，请返回\"无\"。\n\n{books}",
)

sealed interface AiNovelFilterIntent {
    data class UpdateInput(val text: String) : AiNovelFilterIntent
    data object Analyze : AiNovelFilterIntent
    data object DismissResult : AiNovelFilterIntent
    data object ToggleConfig : AiNovelFilterIntent
    data class UpdateConfig(val config: AiModelConfig) : AiNovelFilterIntent
    data object SaveConfig : AiNovelFilterIntent
    data object TestConnection : AiNovelFilterIntent
    data object DismissTestResult : AiNovelFilterIntent
    data class SelectSourceGroup(val groupId: Long?) : AiNovelFilterIntent
    data class SelectTargetGroup(val groupId: Long?) : AiNovelFilterIntent
    data object AddToGroup : AiNovelFilterIntent
}
