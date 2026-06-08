package io.legado.app.tts.voice

/**
 * 角色-音色绑定配置。
 *
 * 用户可以为不同的角色/对话模式绑定特定的 TTS 音色，
 * 实现多人有声书效果。
 */
data class CharacterVoice(
    /** 唯一 ID（通常 = characterId） */
    val id: String,
    /** 角色名称，如 "主角"、"旁白"、"对话" */
    val name: String,
    /** 对话检测模式，用于匹配对话文本 */
    val patterns: List<String> = emptyList(),
    /** 绑定的引擎 ID */
    val engineId: String = "system",
    /** 绑定的音色 ID */
    val voiceId: String,
    /** 语速倍率，1.0 为正常 */
    val speed: Float = 1.0f,
    /** 音量，1.0 为正常 */
    val volume: Float = 1.0f,
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 优先级（数字越大优先级越高） */
    val priority: Int = 0,
    /** 是否为旁白（旁白使用默认引擎处理非对话文本） */
    val isNarrator: Boolean = false
)
