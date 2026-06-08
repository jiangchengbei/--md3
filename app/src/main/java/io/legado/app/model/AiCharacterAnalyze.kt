package io.legado.app.model

import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.tts.voice.CharacterVoice
import io.legado.app.tts.voice.MultiVoiceManager
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import io.legado.app.help.http.okHttpClient
import splitties.init.appCtx
import java.util.concurrent.TimeUnit

/**
 * AI 角色分析管理器。
 *
 * 功能（完整自动化）：
 * 1. 管理多个 AI 模型配置（添加/删除/切换）
 * 2. 使用 AI 分析小说文本，自动提取所有角色
 * 3. 自动为角色判性别、年龄，并匹配 TTS 音色类型
 * 4. 一键将分析结果写入 MultiVoiceManager，启用多人朗读
 *    → TTS 朗读时自动识别对话归属人并切换对应音色
 */
object AiCharacterAnalyze {

    private const val KEY_MODEL_PROFILES = "ai_character_analyze_model_profiles"
    private const val KEY_SELECTED_MODEL_PROFILE = "ai_character_analyze_selected_model_profile"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    data class ModelProfile(
        val id: String,
        val name: String,
        val apiUrl: String,
        val apiKey: String,
        val model: String,
        val temperature: Float = 0.7f,
        val maxTokens: Int = 4096
    )

    /**
     * 角色分析结果。
     */
    data class CharacterAnalyzeResult(
        val name: String,
        val gender: String,
        val age: String,
        val aliases: List<String> = emptyList(),
        val description: String = ""
    )

    /** 获取所有已保存的模型配置 */
    fun getModelProfiles(): List<ModelProfile> {
        val json = appCtx.getPrefString(KEY_MODEL_PROFILES)
        if (json.isNullOrBlank()) return emptyList()
        return kotlin.runCatching {
            val arr = JsonParser.parseString(json).asJsonArray
            arr.map { GSON.fromJson(it.toString(), ModelProfile::class.java) }
        }.getOrDefault(emptyList())
    }

    private fun saveModelProfiles(profiles: List<ModelProfile>) {
        appCtx.putPrefString(KEY_MODEL_PROFILES, GSON.toJson(profiles))
    }

    fun saveModelProfile(profile: ModelProfile) {
        val list = getModelProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        saveModelProfiles(list)
    }

    fun deleteModelProfile(id: String) {
        saveModelProfiles(getModelProfiles().filter { it.id != id })
    }

    fun getSelectedModelProfileId(): String? =
        appCtx.getPrefString(KEY_SELECTED_MODEL_PROFILE)?.takeIf { it.isNotBlank() }

    fun setSelectedModelProfileId(id: String?) {
        appCtx.putPrefString(KEY_SELECTED_MODEL_PROFILE, id ?: "")
    }

    fun getCurrentModelProfile(): ModelProfile? {
        val id = getSelectedModelProfileId() ?: return null
        return getModelProfiles().find { it.id == id }
    }

    /**
     * 分析小说文本中的角色（异步）。
     */
    fun analyzeCharacters(
        text: String,
        modelProfile: ModelProfile,
        onResult: (List<CharacterAnalyzeResult>) -> Unit,
        onError: (String) -> Unit
    ) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                onResult(requestAiAnalysis(text, modelProfile))
            } catch (e: Exception) {
                onError(e.message ?: "分析失败")
            }
        }
    }

    fun cancelAnalyze() {
        currentJob?.cancel()
    }

    /**
     * 自动分析并分配：调用 AI → 自动写入 MultiVoiceManager → 启用多人朗读。
     * 一键完成全流程。
     */
    fun analyzeAndApply(
        text: String,
        modelProfile: ModelProfile,
        onResult: (List<CharacterAnalyzeResult>) -> Unit,
        onError: (String) -> Unit
    ) {
        analyzeCharacters(text, modelProfile, { results ->
            if (results.isNotEmpty()) {
                applyAnalyzeResults(results)
                MultiVoiceManager.enabled = true
            }
            onResult(results)
        }, onError)
    }

    /**
     * 将分析结果写入 MultiVoiceManager。
     * 自动根据性别+年龄匹配对应的 TTS 音色类别，构建对话匹配规则。
     */
    fun applyAnalyzeResults(results: List<CharacterAnalyzeResult>) {
        for (result in results) {
            val voiceType = getVoiceTypeByGenderAge(result.gender, result.age)
            val characterId = "ai_${result.name}"
            val cv = CharacterVoice(
                id = characterId,
                name = result.name,
                patterns = buildPatterns(result.name, result.aliases),
                voiceId = voiceType,
                engineId = "system",
                speed = 1.0f,
                enabled = true,
                priority = 100,
                isNarrator = false
            )
            MultiVoiceManager.setCharacter(cv)
        }
    }

    /**
     * 性别+年龄 → TTS 音色类别标签。
     * 与 朗读规则.json GENSHIN_CHARACTERS 对齐：
     * 女/女童01  女/少女01  女/女青年01  女/女中年01  女/女老年01
     * 男/男童01  男/少年01  男/男青年01  男/男中年01  男/男老年01
     */
    private fun getVoiceTypeByGenderAge(gender: String, age: String): String {
        val g = if (gender.contains("女")) "女" else "男"
        val a = when {
            age.contains("童") || age.contains("儿童") -> "童01"
            age.contains("少") -> "少01"
            age.contains("青年") || age.contains("成年") || age.contains("成人") -> "青年01"
            age.contains("中年") -> "中年01"
            age.contains("老") || age.contains("长者") -> "老年01"
            else -> "青年01"
        }
        return "$g/${g}$a"
    }

    /**
     * 构建角色匹配规则。
     * 生成角色名及其常见对话引述模式（xxx说、xxx问道、xxx答 等）。
     */
    private fun buildPatterns(name: String, aliases: List<String>): List<String> {
        val names = mutableListOf(name)
        names.addAll(aliases.filter { it.isNotBlank() })
        val dialogSuffix = "[说问道叫喊答叹笑骂哭怒吼嚷唤呼言讲叙曰]"
        return names.flatMap { n ->
            if (n.length >= 2) {
                listOf(n, "${n}${dialogSuffix}")
            } else {
                listOf(n)
            }
        }.distinct()
    }

    // ====== AI API 调用 ======

    private fun requestAiAnalysis(
        text: String,
        modelProfile: ModelProfile
    ): List<CharacterAnalyzeResult> {
        val truncatedText = if (text.length > 6000) text.substring(0, 6000) else text

        val systemPrompt = """你是一个专业的小说角色分析器。请仔细阅读以下小说片段，找出所有出场的角色。

对每个角色，严格按以下JSON格式输出（只输出JSON数组，不要任何其他文字）：
[
  {
    "name": "角色名（必须完整准确）",
    "gender": "男或女",
    "age": "从以下选一个最合适的：童/少年/青年/中年/老年",
    "aliases": ["别名1如果有的话", "别名2"],
    "description": "一句话角色简介"
  }
]

规则：
1. 只输出在给定文本中实际出场（有对话或行为描述）的角色
2. 没有标准人名的次要角色可以忽略
3. 别名只填写在原文中实际出现的称呼方式
4. JSON 数组必须是合法JSON，可直接解析"""

        val userPrompt = "分析以下小说片段中的所有角色：\n\n$truncatedText"

        val requestBody = mapOf(
            "model" to modelProfile.model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "temperature" to modelProfile.temperature,
            "max_tokens" to modelProfile.maxTokens,
            "stream" to false
        )

        val client = okHttpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(modelProfile.apiUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${modelProfile.apiKey}")
            .post(GSON.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("API 返回为空")
        response.close()

        return parseAiResponse(responseBody)
    }

    private fun parseAiResponse(responseBody: String): List<CharacterAnalyzeResult> {
        return kotlin.runCatching {
            val jsonObj = JsonParser.parseString(responseBody).asJsonObject
            val choices = jsonObj.getAsJsonArray("choices")
            val content = choices[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString

            val start = content.indexOf('[')
            val end = content.lastIndexOf(']')
            if (start == -1 || end == -1) throw Exception("AI返回格式异常")

            val arr = JsonParser.parseString(content.substring(start, end + 1)).asJsonArray
            arr.map { elem ->
                val obj = elem.asJsonObject
                CharacterAnalyzeResult(
                    name = obj.get("name")?.asString ?: "未知",
                    gender = obj.get("gender")?.asString ?: "男",
                    age = obj.get("age")?.asString ?: "青年",
                    aliases = obj.getAsJsonArray("aliases")?.map { it.asString } ?: emptyList(),
                    description = obj.get("description")?.asString ?: ""
                )
            }
        }.getOrElse {
            AppLog.put("AI角色分析解析失败: ${it.message}", it)
            emptyList()
        }
    }
}
