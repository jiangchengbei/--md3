package io.legado.app.tts.plugin

import java.util.Locale

/**
 * TTS 声音/音色描述。
 * 一个引擎可以有多个 voice（如"晓晓"、"云泽"），
 * 用于多人朗读等场景。
 */
data class TtsVoice(
    /** 唯一标识，如 "zh-CN-XiaoxiaoNeural" */
    val id: String,
    /** 显示名称，如 "晓晓" */
    val name: String,
    /** 语言区域，如 Locale.SIMPLIFIED_CHINESE */
    val locale: Locale,
    /** 性别 */
    val gender: VoiceGender = VoiceGender.UNKNOWN,
    /** 所属引擎 ID */
    val engineId: String
)

enum class VoiceGender {
    MALE, FEMALE, NEUTRAL, UNKNOWN
}
