package io.legado.app.tts.voice

import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/**
 * 多人朗读管理器。
 *
 * 负责：
 * 1. 解析段落中的对话文本（识别"xxx说"、中文引号等模式）
 * 2. 根据角色配置分配对应音色
 * 3. 管理角色-音色绑定配置
 *
 * 配置持久化：通过 SharedPreferences 以 JSON 形式存储。
 */
object MultiVoiceManager {

    private const val PREF_KEY = "multi_voice_configs"

    /** 当前配置的角色列表 */
    private val characters = mutableListOf<CharacterVoice>()

    /** 是否启用多人朗读 */
    var enabled: Boolean = false

    /**
     * 加载已保存的角色配置。
     */
    fun load() {
        val json = appCtx.getPrefString(PREF_KEY)
        if (json.isNullOrBlank()) return
        kotlin.runCatching {
            val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
            characters.clear()
            for (elem in arr) {
                try {
                    val cv = GSON.fromJson(elem.toString(), CharacterVoice::class.java)
                    characters.add(cv)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 保存角色配置。
     */
    fun save() {
        val json = GSON.toJson(characters)
        appCtx.putPrefString(PREF_KEY, json)
    }

    /** 获取所有角色配置 */
    fun getCharacters(): List<CharacterVoice> = characters.toList()

    /** 添加/更新角色 */
    fun setCharacter(cv: CharacterVoice) {
        val idx = characters.indexOfFirst { it.id == cv.id }
        if (idx >= 0) characters[idx] = cv else characters.add(cv)
        save()
    }

    /** 删除角色 */
    fun removeCharacter(id: String) {
        characters.removeAll { it.id == id }
        save()
    }

    /** 获取旁白角色（默认朗读者） */
    fun getNarrator(): CharacterVoice? = characters.find { it.isNarrator }

    /**
     * 解析段落文本为对话片段列表。
     *
     * 识别策略：
     * 1. 中文引号 "xxx" 或 「xxx」
     * 2. "xxx说" 模式
     * 3. 冒号开头的对话
     *
     * @param text 段落文本
     * @return 切分后的对话片段列表
     */
    fun parseSegments(text: String): List<DialogueSegment> {
        if (!enabled || characters.isEmpty()) {
            return listOf(DialogueSegment(text, false, null, 0, text.length))
        }

        val segments = mutableListOf<DialogueSegment>()

        // 中文引号对话检测
        val quotePattern = Regex("[\u201c\u201d\u300c\u300d\u300e\u300f\u2018\u2019]([^\u201c\u201d\u300c\u300d\u300e\u300f\u2018\u2019]+)[\u201c\u201d\u300c\u300d\u300e\u300f\u2018\u2019]")

        var lastEnd = 0
        quotePattern.findAll(text).forEach { match ->
            // 前导文本（非对话）
            if (match.range.first > lastEnd) {
                val nonDialogue = text.substring(lastEnd, match.range.first).trim()
                if (nonDialogue.isNotEmpty()) {
                    segments.add(DialogueSegment(nonDialogue, false, null, lastEnd, nonDialogue.length))
                }
            }
            // 对话文本
            val dialogueText = match.value
            val matchedChar = matchCharacter(dialogueText)
            segments.add(DialogueSegment(
                dialogueText,
                true,
                matchedChar?.id,
                match.range.first,
                dialogueText.length
            ))
            lastEnd = match.range.last + 1
        }

        // 剩余文本
        if (lastEnd < text.length) {
            val remaining = text.substring(lastEnd).trim()
            if (remaining.isNotEmpty()) {
                segments.add(DialogueSegment(remaining, false, null, lastEnd, remaining.length))
            }
        }

        if (segments.isEmpty()) {
            segments.add(DialogueSegment(text, false, null, 0, text.length))
        }

        return segments
    }

    /**
     * 根据对话文本匹配角色。
     * 按优先级依次尝试每个角色的模式，返回第一个匹配的。
     */
    private fun matchCharacter(dialogueText: String): CharacterVoice? {
        return characters
            .filter { it.enabled && !it.isNarrator && it.patterns.isNotEmpty() }
            .sortedByDescending { it.priority }
            .firstOrNull { cv ->
                cv.patterns.any { pattern ->
                    kotlin.runCatching {
                        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(dialogueText)
                    }.getOrDefault(false)
                }
            }
    }

    /**
     * 获取指定角色的音色信息。
     */
    fun getVoiceForCharacter(characterId: String): CharacterVoice? {
        return characters.find { it.id == characterId }
    }
}
