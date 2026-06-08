package io.legado.app.ui.main.ainovelfilter

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val PREF_AI_ENABLED = "aiNovelFilterEnabled"
private const val PREF_AI_API_URL = "aiNovelFilterApiUrl"
private const val PREF_AI_API_KEY = "aiNovelFilterApiKey"
private const val PREF_AI_MODEL = "aiNovelFilterModel"
private const val PREF_AI_SYSTEM_PROMPT = "aiNovelFilterSystemPrompt"
private const val PREF_AI_USER_PROMPT_TEMPLATE = "aiNovelFilterUserPromptTemplate"

private const val DEFAULT_SYSTEM_PROMPT = "你是一个专业的小说推荐助手，只返回编号列表。"
private const val DEFAULT_USER_PROMPT_TEMPLATE = "用户想找「{input}」类型的小说。\n以下是本地书库中的小说列表，请根据用户描述，返回最匹配的 5-10 本书的编号（仅返回编号列表，用逗号分隔）。\n如果都不匹配，请返回\"无\"。\n\n{books}"

/**
 * 中文小说类型的AI关键词映射系统
 * 将用户的自然语言描述映射到具体的小说类型关键词
 */
private val GENRE_KEYWORD_MAP = mapOf(
    "玄幻" to listOf("玄幻", "异界", "大陆", "修炼", "魔法", "斗气", "血脉"),
    "修真" to listOf("修真", "修仙", "仙侠", "练气", "筑基", "金丹", "元婴", "渡劫", "飞升"),
    "都市" to listOf("都市", "现代", "医生", "总裁", "豪门", "兵王", "校花", "保镖"),
    "言情" to listOf("言情", "恋爱", "总裁", "甜宠", "虐恋", "结婚", "暗恋", "渣男", "霸总"),
    "穿越" to listOf("穿越", "重生", "穿书", "古代", "回到过去", "异世"),
    "科幻" to listOf("科幻", "星际", "机甲", "末日", "外星", "太空", "未来", "文明"),
    "恐怖" to listOf("恐怖", "灵异", "鬼怪", "惊悚", "悬疑", "诅咒", "僵尸"),
    "武侠" to listOf("武侠", "江湖", "武林", "剑客", "刀客", "侠女", "内功"),
    "历史" to listOf("历史", "古代", "宫廷", "王朝", "三国", "帝王", "后宫", "权谋"),
    "游戏" to listOf("游戏", "网游", "电竞", "副本", "虚拟", "全息"),
    "军事" to listOf("军事", "战争", "特种兵", "军队", "战场"),
    "竞技" to listOf("竞技", "体育", "篮球", "足球", "拳击", "赛车"),
    "悬疑" to listOf("悬疑", "侦探", "推理", "密室", "案件", "凶手"),
    "二次元" to listOf("动漫"),
    "动漫" to listOf("动漫", "二次元", "同人", "穿越动漫"),
    "奇幻" to listOf("奇幻", "龙", "精灵", "矮人", "魔法", "巫师"),
    "女频" to listOf("言情", "总裁", "甜宠", "虐恋", "古言", "现言", "女强"),
    "男频" to listOf("玄幻", "都市", "兵王", "升级", "多人", "系统"),
    "系统" to listOf("系统", "签到", "金手指", "抽奖"),
    "轻松" to listOf("轻松", "搞笑", "日常", "吐槽", "种田"),
    "热血" to listOf("热血", "战斗", "升级", "逆袭", "无敌"),
    "青春" to listOf("青春", "校园", "学霸", "学渣", "高考", "同桌"),
    "纯爱" to listOf("纯爱"),
    "耽美" to listOf("耽美", "纯爱", "双男主", "BL"),
    "百合" to listOf("百合", "双女主", "GL"),
    "同人" to listOf("同人", "衍生", "影视同人"),
    "短篇" to listOf("短篇", "短故事", "微型"),
    "灾厄" to listOf("末世", "灾厄", "丧尸", "废土", "核爆"),
    "无限流" to listOf("无限流", "轮回", "副本世界", "生存游戏"),
    "盗墓" to listOf("盗墓", "古董", "风水", "摸金"),
)

private val SUGGESTED_CHIPS = listOf(
    "玄幻修真", "都市言情", "仙侠奇幻", "悬疑推理",
    "穿越重生", "科幻末世", "古代言情", "系统升级",
    "轻松搞笑", "热血战斗", "游戏竞技", "武侠江湖"
)

class AiNovelFilterViewModel(
    application: Application
) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(
        AiNovelFilterUiState(
            chips = SUGGESTED_CHIPS.toImmutableList(),
            config = loadConfig()
        )
    )
    val uiState: StateFlow<AiNovelFilterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appDb.bookGroupDao.flowSelect().collect { groups ->
                _uiState.update { it.copy(availableGroups = groups.toImmutableList()) }
            }
        }
    }

    fun onIntent(intent: AiNovelFilterIntent) {
        when (intent) {
            is AiNovelFilterIntent.UpdateInput -> {
                _uiState.update { it.copy(inputText = intent.text) }
            }
            is AiNovelFilterIntent.Analyze -> {
                performAnalysis()
            }
            is AiNovelFilterIntent.DismissResult -> {
                _uiState.update { it.copy(books = kotlinx.collections.immutable.persistentListOf(), resultMessage = "") }
            }
            is AiNovelFilterIntent.ToggleConfig -> {
                _uiState.update { it.copy(showConfig = !it.showConfig) }
            }
            is AiNovelFilterIntent.UpdateConfig -> {
                _uiState.update { it.copy(config = intent.config) }
            }
            is AiNovelFilterIntent.SaveConfig -> {
                saveConfig(_uiState.value.config)
                _uiState.update { it.copy(showConfig = false) }
            }
            is AiNovelFilterIntent.TestConnection -> {
                testConnection()
            }
            is AiNovelFilterIntent.DismissTestResult -> {
                _uiState.update { it.copy(testResultMessage = "") }
            }
            is AiNovelFilterIntent.SelectSourceGroup -> {
                _uiState.update { it.copy(sourceGroupId = intent.groupId) }
            }
            is AiNovelFilterIntent.SelectTargetGroup -> {
                _uiState.update { it.copy(targetGroupId = intent.groupId) }
            }
            is AiNovelFilterIntent.AddToGroup -> {
                addToGroup()
            }
        }
    }

    private fun loadConfig(): AiModelConfig {
        return AiModelConfig(
            enabled = context.getPrefBoolean(PREF_AI_ENABLED, false),
            apiUrl = context.getPrefString(PREF_AI_API_URL, "https://api.openai.com/v1/chat/completions") ?: "",
            apiKey = context.getPrefString(PREF_AI_API_KEY, "") ?: "",
            model = context.getPrefString(PREF_AI_MODEL, "gpt-4o-mini") ?: "",
            systemPrompt = context.getPrefString(PREF_AI_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT,
            userPromptTemplate = context.getPrefString(PREF_AI_USER_PROMPT_TEMPLATE, DEFAULT_USER_PROMPT_TEMPLATE) ?: DEFAULT_USER_PROMPT_TEMPLATE,
        )
    }

    private fun saveConfig(config: AiModelConfig) {
        context.putPrefBoolean(PREF_AI_ENABLED, config.enabled)
        context.putPrefString(PREF_AI_API_URL, config.apiUrl)
        context.putPrefString(PREF_AI_API_KEY, config.apiKey)
        context.putPrefString(PREF_AI_MODEL, config.model)
        context.putPrefString(PREF_AI_SYSTEM_PROMPT, config.systemPrompt)
        context.putPrefString(PREF_AI_USER_PROMPT_TEMPLATE, config.userPromptTemplate)
    }

    private fun testConnection() {
        val config = _uiState.value.config
        if (config.apiUrl.isBlank() || config.apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    testResultMessage = "API 地址或 Key 不能为空",
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTestingConnection = true,
                    testResultMessage = "正在测试连接…",
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    // 发送一个最小请求来验证连接
                    val requestBody = mapOf(
                        "model" to config.model.ifBlank { "gpt-4o-mini" },
                        "messages" to listOf(
                            mapOf("role" to "user", "content" to "ping")
                        ),
                        "max_tokens" to 1,
                        "temperature" to 0.0,
                    )

                    val json = GSON.toJson(requestBody)
                    val response = okHttpClient.newCallStrResponse {
                        url(config.apiUrl)
                        header("Authorization", "Bearer ${config.apiKey}")
                        header("Content-Type", "application/json")
                        post(json.toRequestBody("application/json".toMediaType()))
                    }

                    if (!response.raw.isSuccessful) {
                        val errorBody = response.body?.take(200) ?: ""
                        throw Exception("HTTP ${response.raw.code}: $errorBody")
                    }
                }
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        testResultMessage = "✓ 连接成功",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        testResultMessage = "✗ 连接失败: ${e.message?.take(100)}",
                    )
                }
            }
        }
    }

    private fun performAnalysis() {
        val input = _uiState.value.inputText.trim()
        if (input.isBlank()) return
        val config = _uiState.value.config

        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, resultMessage = "") }

            val result = if (config.enabled && config.apiUrl.isNotBlank() && config.apiKey.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) { analyzeByAi(input, config) }
                } catch (e: Exception) {
                    // AI 失败时回退到本地分析
                    withContext(Dispatchers.IO) { analyzeLocal(input) }
                        .copy(message = "AI 调用失败（${e.message}），已切换本地分析。")
                }
            } else {
                delay(600)
                withContext(Dispatchers.IO) { analyzeLocal(input) }
            }

            _uiState.update {
                it.copy(
                    isAnalyzing = false,
                    books = result.books.toImmutableList(),
                    resultMessage = result.message,
                )
            }
        }
    }

    private data class AnalysisResult(
        val books: List<Book>,
        val message: String,
    )

    private fun addToGroup() {
        val targetGroupId = _uiState.value.targetGroupId ?: return
        val books = _uiState.value.books
        if (books.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isAddingToGroup = true) }
            withContext(Dispatchers.IO) {
                books.forEach { book ->
                    if ((book.group and targetGroupId) == 0L) {
                        book.group = book.group or targetGroupId
                        appDb.bookDao.update(book)
                    }
                }
            }
            _uiState.update {
                it.copy(
                    isAddingToGroup = false,
                    resultMessage = it.resultMessage + "\n已将 ${books.size} 本书加入分组",
                )
            }
        }
    }

    private fun getBooksToAnalyze(): List<Book> {
        val sourceGroupId = _uiState.value.sourceGroupId
        val allBooks = if (sourceGroupId != null) {
            appDb.bookDao.getBooksByGroup(sourceGroupId)
        } else {
            appDb.bookDao.all
        }
        return allBooks.filter { book ->
            book.type and BookType.local != 0
        }
    }

    private fun analyzeLocal(input: String): AnalysisResult {
        val matchedGenres = extractGenres(input)
        val keywords = matchedGenres.flatMap { GENRE_KEYWORD_MAP[it] ?: emptyList() }.distinct()

        val searchTerms = if (keywords.isNotEmpty()) {
            keywords + input.split("\\s+".toRegex()).filter { it.length >= 2 }
        } else {
            input.split("\\s+".toRegex()).filter { it.length >= 2 }
        }.distinct()

        if (searchTerms.isEmpty()) {
            return AnalysisResult(emptyList(), "未识别到有效的小说类型关键词，请尝试输入更具体的小说类型描述")
        }

        val allBooks = getBooksToAnalyze()

        if (allBooks.isEmpty()) {
            return AnalysisResult(emptyList(), "本地没有找到小说文件，请先导入本地小说")
        }

        val scoredBooks = allBooks.map { book ->
            val score = calculateMatchScore(book, searchTerms)
            Pair(book, score)
        }

        val sorted = scoredBooks
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

        val message = buildString {
            append("根据「$input」的分析：")
            append("\n匹配类型：${matchedGenres.joinToString("、") { it }.ifEmpty { "关键词匹配" }}")
            append("\n搜索关键词：${searchTerms.take(5).joinToString("、")}")
            if (sorted.isNotEmpty()) {
                append("\n共找到 ${sorted.size} 本相关小说")
            } else {
                append("\n未找到匹配的小说，试试其他描述")
            }
        }

        return AnalysisResult(sorted, message)
    }

    private suspend fun analyzeByAi(input: String, config: AiModelConfig): AnalysisResult {
        val allBooks = getBooksToAnalyze()

        if (allBooks.isEmpty()) {
            return AnalysisResult(emptyList(), "本地没有找到小说文件，请先导入本地小说")
        }

        // 构建 prompt：只发送书名、作者、分类、简介，避免 token 过长
        val bookDescriptions = allBooks.take(80).mapIndexed { index, book ->
            "${index + 1}. 《${book.name}》 作者：${book.author} 分类：${book.kind ?: "未知"} 简介：${book.intro?.take(120) ?: "无"}"
        }.joinToString("\n")

        // 构建用户提示词：使用可配置的模板，替换占位符
        val prompt = config.userPromptTemplate
            .replace("{input}", input)
            .replace("{books}", bookDescriptions)

        val systemPrompt = config.systemPrompt.ifBlank {
            DEFAULT_SYSTEM_PROMPT
        }

        val requestBody = mapOf(
            "model" to config.model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to prompt)
            ),
            "temperature" to 0.3,
            "max_tokens" to 256
        )

        val json = GSON.toJson(requestBody)
        val response = okHttpClient.newCallStrResponse {
            url(config.apiUrl)
            header("Authorization", "Bearer ${config.apiKey}")
            header("Content-Type", "application/json")
            post(json.toRequestBody("application/json".toMediaType()))
        }

        val content = parseAiResponse(response.body ?: "")
        val matchedIndices = parseIndices(content)

        val matchedBooks = if (matchedIndices.isEmpty()) {
            emptyList()
        } else {
            matchedIndices.mapNotNull { idx ->
                allBooks.getOrNull(idx - 1)
            }
        }

        val fallback = if (matchedBooks.isEmpty()) {
            analyzeLocal(input).books.take(10)
        } else matchedBooks

        val message = buildString {
            append("AI 根据「$input」智能分析结果：")
            if (matchedBooks.isNotEmpty()) {
                append("\n共找到 ${matchedBooks.size} 本最匹配的小说")
            } else {
                append("\nAI 未精确匹配，已用本地算法辅助推荐")
            }
        }

        return AnalysisResult(fallback, message)
    }

    private fun parseAiResponse(body: String): String {
        val json = GSON.fromJsonObject<Map<String, Any>>(body).getOrNull() ?: return ""
        val choices = json["choices"] as? List<Map<String, Any>> ?: return ""
        val first = choices.firstOrNull() ?: return ""
        val message = first["message"] as? Map<String, Any> ?: return ""
        return message["content"] as? String ?: ""
    }

    private fun parseIndices(content: String): List<Int> {
        if (content.contains("无") || content.isBlank()) return emptyList()
        val regex = Regex("""\d+""")
        return regex.findAll(content).map { it.value.toInt() }.filter { it > 0 }.toList()
    }

    private fun extractGenres(input: String): List<String> {
        val matched = mutableListOf<String>()
        for ((genre, _) in GENRE_KEYWORD_MAP) {
            if (input.contains(genre, ignoreCase = true)) {
                matched.add(genre)
            }
        }
        return matched
    }

    private fun calculateMatchScore(book: Book, keywords: List<String>): Int {
        var score = 0
        val searchText = buildString {
            append(book.kind ?: "")
            append(" ")
            append(book.name)
            append(" ")
            append(book.customTag ?: "")
            append(" ")
            append(book.intro ?: "")
            append(" ")
            append(book.author)
            append(" ")
            append(book.originName)
        }.lowercase()

        for (keyword in keywords) {
            val kw = keyword.lowercase()
            val kind = book.kind?.lowercase() ?: ""
            if (kind.contains(kw)) score += 4
            if (book.name.lowercase().contains(kw)) score += 3
            val tag = book.customTag?.lowercase() ?: ""
            if (tag.contains(kw)) score += 3
            val intro = book.intro?.lowercase() ?: ""
            if (intro.contains(kw)) score += 2
            if (book.author.lowercase().contains(kw)) score += 1
            if (book.originName.lowercase().contains(kw)) score += 1
        }
        return score
    }
}
